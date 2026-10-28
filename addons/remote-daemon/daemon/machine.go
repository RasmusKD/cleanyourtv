package main

import "time"

const (
	// HoldAfter is how long a key with a Hold command must be held before it runs.
	HoldAfter = 600 * time.Millisecond
	// SmoothDelay and SmoothEvery pace a held smooth key's extra presses (like a keyboard's repeat).
	SmoothDelay = 300 * time.Millisecond
	SmoothEvery = 60 * time.Millisecond
	// StopRepeatAfter is when a watching daemon ends Android's own repeat of a held smooth key: before Android's
	// repeat starts (long_press_timeout, 400 ms by default), so the press Android saw stays a single step.
	StopRepeatAfter = 250 * time.Millisecond
	// RepeatDelay and RepeatEvery pace a held repeat key's command.
	RepeatDelay = 450 * time.Millisecond
	RepeatEvery = 220 * time.Millisecond
)

// Output is where the machine's decisions go: Forward hands a key event to Android unchanged (value 0 up, 1 down,
// 2 the kernel's own repeat), Press sends one whole press (down and up), Release ends Android's hold of a key it saw
// go down from the remote itself, Run queues a shell command.
type Output interface {
	Forward(code uint16, value int32)
	Press(code uint16)
	Release(code uint16)
	Run(command string)
}

type held struct {
	binding *Binding
	next    time.Time // zero when nothing more is due while held
	fired   bool      // the Hold command ran, so the release runs nothing
	at      time.Time // when the key went down
	release bool      // a smooth key's Release is the next thing due
}

// Machine turns raw key events into forwarded keys, single presses and commands. It owns no clock or timer:
// the caller passes the time in and asks Next when to call Tick.
type Machine struct {
	cfg  *Config
	out  Output
	down map[uint16]*held
	// watch: Android reads the remote too, so it already has every key event; the machine only adds to them
	watch bool
}

func NewMachine(cfg *Config, out Output, watch bool) *Machine {
	return &Machine{cfg: cfg, out: out, down: map[uint16]*held{}, watch: watch}
}

func (m *Machine) Key(code uint16, value int32, now time.Time) {
	b := m.cfg.Bindings[code]
	if b == nil {
		m.out.Forward(code, value)
		return
	}
	switch value {
	case 1:
		h := &held{binding: b, at: now}
		m.down[code] = h
		switch {
		case b.Smooth && m.watch:
			// Android took this press itself; stop its repeat before it starts, then add presses at the same pace
			h.next, h.release = now.Add(StopRepeatAfter), true
		case b.Smooth:
			m.out.Press(code)
			h.next = now.Add(SmoothDelay)
		case b.Hold != "":
			h.next = now.Add(HoldAfter)
		default:
			m.out.Run(b.Tap)
			if b.Repeat {
				h.next = now.Add(RepeatDelay)
			}
		}
	case 0:
		h := m.down[code]
		delete(m.down, code)
		if h != nil && b.Hold != "" && !h.fired && b.Tap != "" {
			m.out.Run(b.Tap)
		}
	}
	// value 2, the kernel's repeat: the machine paces held keys itself
}

// Tick runs whatever has come due by now.
func (m *Machine) Tick(now time.Time) {
	for code, h := range m.down {
		if h.next.IsZero() || now.Before(h.next) {
			continue
		}
		b := h.binding
		switch {
		case h.release:
			m.out.Release(code)
			h.next, h.release = h.at.Add(SmoothDelay), false
		case b.Smooth:
			m.out.Press(code)
			h.next = now.Add(SmoothEvery)
		case b.Hold != "":
			m.out.Run(b.Hold)
			h.fired, h.next = true, time.Time{}
		default:
			m.out.Run(b.Tap)
			h.next = now.Add(RepeatEvery)
		}
	}
}

// Next is the earliest time Tick has work, or the zero time when nothing is due.
func (m *Machine) Next() time.Time {
	var next time.Time
	for _, h := range m.down {
		if !h.next.IsZero() && (next.IsZero() || h.next.Before(next)) {
			next = h.next
		}
	}
	return next
}
