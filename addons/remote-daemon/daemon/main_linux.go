// remote-daemon reads the TV remote's input device and runs shell commands for bound keys; smooth keys reach Android
// as separate presses. In watch mode (the default) Android reads the remote too and the daemon presses smooth keys
// through Android's input injection; in full mode it takes the remote for itself and passes every key on through a
// uinput copy. It runs as the ADB shell user, whose input and uhid groups give it both devices; no root.
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

// clonePhys marks the daemon's own uinput copy, so finding the remote never picks the copy instead.
const clonePhys = "remote-daemon/clone"

func ioc(dir, typ, nr, size uintptr) uintptr { return dir<<30 | size<<16 | typ<<8 | nr }

var (
	eviocgname = func(n uintptr) uintptr { return ioc(2, 'E', 0x06, n) }
	eviocgphys = func(n uintptr) uintptr { return ioc(2, 'E', 0x07, n) }
	eviocgid   = ioc(2, 'E', 0x02, 8)
	eviocgkey  = func(n uintptr) uintptr { return ioc(2, 'E', 0x18, n) }
	eviocgbit  = func(ev, n uintptr) uintptr { return ioc(2, 'E', 0x20+ev, n) }
	eviocgrab  = ioc(1, 'E', 0x90, 4)

	uiSetEvbit   = ioc(1, 'U', 100, 4)
	uiSetKeybit  = ioc(1, 'U', 101, 4)
	uiSetPhys    = ioc(1, 'U', 108, unsafe.Sizeof(uintptr(0)))
	uiDevCreate  = ioc(0, 'U', 1, 0)
	uiDevDestroy = ioc(0, 'U', 2, 0)
)

func ioctl(fd, req, arg uintptr) error {
	if _, _, e := syscall.Syscall(syscall.SYS_IOCTL, fd, req, arg); e != 0 {
		return e
	}
	return nil
}

func ioctlString(f *os.File, req func(uintptr) uintptr) (string, error) {
	buf := make([]byte, 256)
	if err := ioctl(f.Fd(), req(uintptr(len(buf))), uintptr(unsafe.Pointer(&buf[0]))); err != nil {
		return "", err
	}
	return string(bytes.TrimRight(buf, "\x00")), nil
}

type inputID struct{ Bustype, Vendor, Product, Version uint16 }

// findDevice opens the first /dev/input/event* whose name contains want, skipping a copy this daemon (or an
// earlier run of it) made.
func findDevice(want string) (*os.File, string, error) {
	paths, _ := filepath.Glob("/dev/input/event*")
	for _, p := range paths {
		f, err := os.OpenFile(p, os.O_RDONLY, 0)
		if err != nil {
			continue
		}
		if n, err := ioctlString(f, eviocgname); err == nil && strings.Contains(n, want) {
			if phys, _ := ioctlString(f, eviocgphys); phys != clonePhys {
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
	phys := append([]byte(clonePhys), 0)
	if err := ioctl(fd, uiSetPhys, uintptr(unsafe.Pointer(&phys[0]))); err != nil {
		u.Close()
		return nil, fmt.Errorf("UI_SET_PHYS: %w", err)
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

// startInject runs the starter app's Inject class as this (shell) user through app_process, from the copy of the
// app install.sh left in the daemon's own directory: only the shell user can write there, so what runs is what
// install.sh pushed, whatever package later holds the starter's name. If it dies, smooth keys fall back to
// Android's own repeat and every other binding keeps working.
func startInject(apk, device string) (io.Writer, error) {
	cmd := exec.Command("/system/bin/sh", "-c", `CLASSPATH="$1" exec app_process / io.github.rasmuskd.remotedaemon.Inject "$2"`,
		"inject", apk, device)
	in, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	cmd.Stdout, cmd.Stderr = os.Stderr, os.Stderr
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	go func() { log.Printf("inject helper ended: %v", cmd.Wait()) }()
	return in, nil
}

// lockOnly takes the lock file next to the config, so a second daemon started at the same moment exits; the
// lock goes with the process.
func lockOnly(dir string) error {
	f, err := os.OpenFile(filepath.Join(dir, "remote-daemon.lock"), os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return err
	}
	if err := syscall.Flock(int(f.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		f.Close()
		return fmt.Errorf("another remote-daemon runs: %w", err)
	}
	return nil // f stays open, and locked, for the life of the process
}

// input is what the reader hands the key loop: a key event, or after an overflow the keys the kernel has down.
type input struct {
	code  uint16
	value int32
	down  []byte // set on a resync: the EVIOCGKEY bitmap
}

// read passes the remote's key events on. After SYN_DROPPED the kernel discards events up to the next SYN_REPORT,
// so key-ups may be gone: the reader then asks the kernel which keys are down. A read error reopens the device.
func read(src *os.File, name, want string, grab bool, events chan<- input) {
	buf := make([]byte, eventSize*64)
	dropping := false
	for {
		n, err := src.Read(buf)
		if err != nil {
			log.Printf("read %s: %v; reopening", name, err)
			src.Close()
			for {
				time.Sleep(2 * time.Second)
				if src, name, err = findDevice(want); err != nil {
					continue
				}
				if grab {
					if err = ioctl(src.Fd(), eviocgrab, 1); err != nil {
						log.Printf("EVIOCGRAB: %v", err)
						src.Close()
						continue
					}
				}
				break
			}
			dropping = false
			continue
		}
		for i := 0; i+eventSize <= n; i += eventSize {
			ev := buf[i : i+eventSize]
			typ, code := binary.LittleEndian.Uint16(ev[8:]), binary.LittleEndian.Uint16(ev[10:])
			switch {
			case typ == evSyn && code == synDropped:
				dropping = true
			case typ == evSyn && code == synReport && dropping:
				dropping = false
				keys := make([]byte, keyMax/8+1)
				if err := ioctl(src.Fd(), eviocgkey(uintptr(len(keys))), uintptr(unsafe.Pointer(&keys[0]))); err != nil {
					log.Printf("EVIOCGKEY: %v", err)
					keys = make([]byte, keyMax/8+1) // unknown: end every held key
				}
				events <- input{down: keys}
			case typ == evKey && !dropping:
				events <- input{code: code, value: int32(binary.LittleEndian.Uint32(ev[12:]))}
			}
		}
	}
}

func main() {
	device := flag.String("device", "IR Receiver", "part of the remote's input device name")
	mode := flag.String("mode", "full", "full: take the remote and pass its keys on through a copy; "+
		"watch: only read it, so bound keys also keep their own function and smooth keys go through Android's input injection; "+
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
	dir := filepath.Dir(flag.Arg(0))
	if err := lockOnly(dir); err != nil {
		log.Fatal(err)
	}
	text, err := os.ReadFile(flag.Arg(0))
	if err != nil {
		log.Fatal(err)
	}
	cfg, err := ParseConfig(bytes.NewReader(text))
	if err != nil {
		log.Fatalf("%s: %v", flag.Arg(0), err)
	}
	run := newRunner(cfg.Init)
	for _, b := range cfg.Bindings {
		for _, command := range []string{b.Tap, b.Hold} {
			if command == "" {
				continue
			}
			if err := run.check(command); err != nil {
				log.Fatalf("%s: %v", flag.Arg(0), err)
			}
		}
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
	grab := *mode == "full" || *mode == "grab-only"
	if grab {
		if err := ioctl(src.Fd(), eviocgrab, 1); err != nil {
			log.Fatalf("EVIOCGRAB: %v", err)
		}
	}
	if *mode == "clone-only" {
		dst = nil // the remote still reaches Android itself; the copy only has to exist
	}

	commands := make(chan string, 32)
	go run.serve(commands)
	out := &uinputOut{commands: commands}
	if dst != nil {
		out.dev = dst
	}
	if dst == nil && cfg.hasSmooth() {
		if out.inject, err = startInject(filepath.Join(dir, "remote-daemon.apk"), *device); err != nil {
			log.Printf("inject helper: %v; smooth keys keep Android's own repeat", err)
		}
	}
	log.Printf("%s %q, %d keys bound", map[bool]string{true: "holding", false: "watching"}[dst != nil], name, len(cfg.Bindings))

	events := make(chan input, 64)
	go read(src, name, *device, grab, events)

	// a watching daemon whose helper failed adds nothing to smooth keys: Android's own repeat must then run on
	m := NewMachine(cfg, out, dst == nil && out.inject != nil)
	timer := time.NewTimer(time.Hour)
	for {
		if next := m.Next(); next.IsZero() {
			timer.Reset(time.Hour)
		} else {
			timer.Reset(time.Until(next))
		}
		select {
		case ev := <-events:
			now := time.Now()
			if ev.down == nil {
				m.Key(ev.code, ev.value, now)
				break
			}
			for _, code := range m.Held() {
				if ev.down[code/8]&(1<<(code%8)) == 0 {
					m.Key(code, 0, now)
				}
			}
		case <-timer.C:
		}
		m.Tick(time.Now())
	}
}
