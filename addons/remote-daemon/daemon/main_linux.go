// remote-daemon takes the TV remote's input device for itself and stands a copy of it up through uinput: every key
// it has no binding for goes to Android through the copy unchanged, bound keys run shell commands instead, and
// smooth keys reach Android as separate presses. It runs as the ADB shell user, whose input and uhid groups give it
// both devices; no root.
//
// When the daemon exits, for any reason, the kernel ends its grab and the remote works as before.
package main

import (
	"bytes"
	"encoding/binary"
	"flag"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

const (
	evSyn = 0x00
	evKey = 0x01

	// 32-bit ARM userland: struct input_event is two 32-bit time fields, type, code and value.
	eventSize = 16
	keyMax    = 0x2ff
)

func ioc(dir, typ, nr, size uintptr) uintptr { return dir<<30 | size<<16 | typ<<8 | nr }

var (
	eviocgname = func(n uintptr) uintptr { return ioc(2, 'E', 0x06, n) }
	eviocgid   = ioc(2, 'E', 0x02, 8)
	eviocgbit  = func(ev, n uintptr) uintptr { return ioc(2, 'E', 0x20+ev, n) }
	eviocgrab  = ioc(1, 'E', 0x90, 4)

	uiSetEvbit   = ioc(1, 'U', 100, 4)
	uiSetKeybit  = ioc(1, 'U', 101, 4)
	uiDevCreate  = ioc(0, 'U', 1, 0)
	uiDevDestroy = ioc(0, 'U', 2, 0)
)

func ioctl(fd, req, arg uintptr) error {
	if _, _, e := syscall.Syscall(syscall.SYS_IOCTL, fd, req, arg); e != 0 {
		return e
	}
	return nil
}

type inputID struct{ Bustype, Vendor, Product, Version uint16 }

// findDevice opens the first /dev/input/event* whose name contains want.
func findDevice(want string) (*os.File, string, error) {
	paths, _ := filepath.Glob("/dev/input/event*")
	for _, p := range paths {
		f, err := os.OpenFile(p, os.O_RDONLY, 0)
		if err != nil {
			continue
		}
		name := make([]byte, 256)
		if ioctl(f.Fd(), eviocgname(uintptr(len(name))), uintptr(unsafe.Pointer(&name[0]))) == nil {
			if n := string(bytes.TrimRight(name, "\x00")); strings.Contains(n, want) {
				return f, n, nil
			}
		}
		f.Close()
	}
	return nil, "", fmt.Errorf("no input device named like %q", want)
}

// cloneDevice creates a uinput device with the remote's name, ids and keys, so Android gives it the same key
// layout file and treats its keys exactly like the remote's.
func cloneDevice(src *os.File, name string) (*os.File, error) {
	var id inputID
	if err := ioctl(src.Fd(), eviocgid, uintptr(unsafe.Pointer(&id))); err != nil {
		return nil, fmt.Errorf("EVIOCGID: %w", err)
	}
	keys := make([]byte, keyMax/8+1)
	if err := ioctl(src.Fd(), eviocgbit(evKey, uintptr(len(keys))), uintptr(unsafe.Pointer(&keys[0]))); err != nil {
		return nil, fmt.Errorf("EVIOCGBIT: %w", err)
	}
	u, err := os.OpenFile("/dev/uinput", os.O_WRONLY|syscall.O_NONBLOCK, 0)
	if err != nil {
		return nil, err
	}
	fd := u.Fd()
	for _, ev := range []uintptr{evSyn, evKey} {
		if err := ioctl(fd, uiSetEvbit, ev); err != nil {
			u.Close()
			return nil, fmt.Errorf("UI_SET_EVBIT: %w", err)
		}
	}
	for code := 0; code <= keyMax; code++ {
		if keys[code/8]&(1<<(code%8)) != 0 {
			if err := ioctl(fd, uiSetKeybit, uintptr(code)); err != nil {
				u.Close()
				return nil, fmt.Errorf("UI_SET_KEYBIT %d: %w", code, err)
			}
		}
	}
	// struct uinput_user_dev: name[80], input_id, ff_effects_max, then four 64-entry abs arrays
	dev := make([]byte, 80+8+4+4*64*4)
	copy(dev[:79], name)
	binary.LittleEndian.PutUint16(dev[80:], id.Bustype)
	binary.LittleEndian.PutUint16(dev[82:], id.Vendor)
	binary.LittleEndian.PutUint16(dev[84:], id.Product)
	binary.LittleEndian.PutUint16(dev[86:], id.Version)
	if _, err := u.Write(dev); err != nil {
		u.Close()
		return nil, fmt.Errorf("uinput setup: %w", err)
	}
	if err := ioctl(fd, uiDevCreate, 0); err != nil {
		u.Close()
		return nil, fmt.Errorf("UI_DEV_CREATE: %w", err)
	}
	return u, nil
}

type uinputOut struct {
	dev *os.File
	sh  io.Writer
}

func (o *uinputOut) write(typ, code uint16, value int32) {
	if o.dev == nil {
		return // not holding the remote: its own events already reach Android
	}
	ev := make([]byte, eventSize)
	binary.LittleEndian.PutUint16(ev[8:], typ)
	binary.LittleEndian.PutUint16(ev[10:], code)
	binary.LittleEndian.PutUint32(ev[12:], uint32(value))
	if _, err := o.dev.Write(ev); err != nil {
		log.Printf("uinput write: %v", err)
	}
}

func (o *uinputOut) Forward(code uint16, value int32) {
	o.write(evKey, code, value)
	o.write(evSyn, 0, 0)
}

func (o *uinputOut) Press(code uint16) {
	o.Forward(code, 1)
	o.Forward(code, 0)
}

func (o *uinputOut) Run(command string) {
	log.Printf("run %s", command)
	if _, err := io.WriteString(o.sh, command+"\n"); err != nil {
		log.Printf("action shell: %v", err)
	}
}

func main() {
	device := flag.String("device", "IR Receiver", "part of the remote's input device name")
	mode := flag.String("mode", "full", "full: take the remote and pass its keys on through a copy; "+
		"watch: only read it, so bound keys also keep their own function and smooth keys do nothing; "+
		"clone-only and grab-only: one half of full, for finding which one a TV does not take")
	flag.Parse()
	switch *mode {
	case "full", "watch", "clone-only", "grab-only":
	default:
		log.Fatalf("unknown -mode %q", *mode)
	}
	if flag.NArg() != 1 {
		fmt.Fprintln(os.Stderr, "usage: remote-daemon [-device name] [-mode m] <config>")
		os.Exit(2)
	}
	text, err := os.ReadFile(flag.Arg(0))
	if err != nil {
		log.Fatal(err)
	}
	cfg, err := ParseConfig(bytes.NewReader(text))
	if err != nil {
		log.Fatalf("%s: %v", flag.Arg(0), err)
	}

	src, name, err := findDevice(*device)
	if err != nil {
		log.Fatal(err)
	}
	var dst *os.File
	if *mode == "full" || *mode == "clone-only" {
		if dst, err = cloneDevice(src, name); err != nil {
			log.Fatal(err)
		}
		defer ioctl(dst.Fd(), uiDevDestroy, 0)
	}
	if *mode == "full" || *mode == "grab-only" {
		// A second daemon fails here (EBUSY) and exits; the first keeps the remote.
		if err := ioctl(src.Fd(), eviocgrab, 1); err != nil {
			log.Fatalf("EVIOCGRAB: %v", err)
		}
	}
	if *mode == "clone-only" {
		dst = nil // the remote still reaches Android itself; the copy only has to exist
	}

	// One shell runs every command in order, so helper functions from the config's sh lines stay defined.
	sh := exec.Command("/system/bin/sh")
	shIn, err := sh.StdinPipe()
	if err != nil {
		log.Fatal(err)
	}
	sh.Stdout, sh.Stderr = os.Stderr, os.Stderr
	if err := sh.Start(); err != nil {
		log.Fatal(err)
	}
	go func() {
		// a command that kills the shell would leave bound keys dead: exit, and the grab goes with us
		log.Fatalf("action shell ended: %v", sh.Wait())
	}()
	out := &uinputOut{dev: dst, sh: shIn}
	for _, line := range cfg.Init {
		out.Run(line)
	}
	log.Printf("%s %q, %d keys bound", map[bool]string{true: "holding", false: "watching"}[dst != nil], name, len(cfg.Bindings))

	type keyEvent struct {
		code  uint16
		value int32
	}
	events := make(chan keyEvent, 64)
	go func() {
		buf := make([]byte, eventSize*64)
		for {
			n, err := src.Read(buf)
			if err != nil {
				log.Fatalf("read %s: %v", name, err)
			}
			for i := 0; i+eventSize <= n; i += eventSize {
				ev := buf[i : i+eventSize]
				if binary.LittleEndian.Uint16(ev[8:]) == evKey {
					events <- keyEvent{binary.LittleEndian.Uint16(ev[10:]), int32(binary.LittleEndian.Uint32(ev[12:]))}
				}
			}
		}
	}()

	m := NewMachine(cfg, out)
	timer := time.NewTimer(time.Hour)
	for {
		if next := m.Next(); next.IsZero() {
			timer.Reset(time.Hour)
		} else {
			timer.Reset(time.Until(next))
		}
		select {
		case ev := <-events:
			m.Key(ev.code, ev.value, time.Now())
		case <-timer.C:
		}
		m.Tick(time.Now())
	}
}
