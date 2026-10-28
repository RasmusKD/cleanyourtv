package main

import (
	"encoding/binary"
	"fmt"
	"io"
	"log"
)

const (
	evSyn = 0x00
	evKey = 0x01

	synReport  = 0
	synDropped = 3

	// 32-bit ARM userland: struct input_event is two 32-bit time fields, type, code and value.
	eventSize = 16
	keyMax    = 0x2ff
)

// uinputOut is the Output the daemon runs with.
type uinputOut struct {
	// dev is the uinput copy of the remote when the daemon holds it; nil when watching, since the remote's own
	// events already reach Android
	dev io.Writer
	// commands feeds the runner, which runs each command in a shell of its own
	commands chan<- string
	// inject is the starter app's Inject helper, which presses keys through Android's input injection: how a
	// watching daemon, holding no copy of the remote, adds presses (nil when no key is smooth)
	inject io.Writer
}

func (o *uinputOut) write(typ, code uint16, value int32) {
	if o.dev == nil {
		return
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
	o.write(evSyn, synReport, 0)
}

func (o *uinputOut) Press(code uint16) {
	if o.dev == nil {
		o.injectKey("p", code)
		return
	}
	o.Forward(code, 1)
	o.Forward(code, 0)
}

// Release only matters when watching: holding the remote, Android never saw the key go down.
func (o *uinputOut) Release(code uint16) {
	if o.dev == nil {
		o.injectKey("u", code)
	}
}

func (o *uinputOut) injectKey(action string, code uint16) {
	if o.inject == nil {
		return
	}
	if _, err := fmt.Fprintf(o.inject, "%s %d\n", action, androidKeys[code]); err != nil {
		log.Printf("inject: %v", err)
	}
}

// Run queues the command and never waits: the key loop must not stall behind a slow command. A full queue drops it.
func (o *uinputOut) Run(command string) {
	select {
	case o.commands <- command:
		log.Printf("run %s", command)
	default:
		log.Printf("dropped %s: %d commands already waiting", command, cap(o.commands))
	}
}
