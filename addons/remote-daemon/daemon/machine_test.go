package main

import (
	"fmt"
	"os"
	"reflect"
	"strings"
	"testing"
	"time"
)

type record []string

func (r *record) Forward(code uint16, value int32) {
	*r = append(*r, fmt.Sprintf("fwd %d %d", code, value))
}
func (r *record) Press(code uint16)   { *r = append(*r, fmt.Sprintf("press %d", code)) }
func (r *record) Release(code uint16) { *r = append(*r, fmt.Sprintf("release %d", code)) }
func (r *record) Run(command string)  { *r = append(*r, "run "+command) }

const sample = `
# comment
sh bl() { :; }
VOLUMEUP smooth
CHANNELUP repeat bl 5
CHANNELDOWN tap bl -5
CHANNELDOWN hold am start -n x/.Y
GREEN tap am start -n hue/.Low
`

func machine(t *testing.T) (*Machine, *record) { return machineIn(t, false) }

func machineIn(t *testing.T, watch bool) (*Machine, *record) {
	t.Helper()
	cfg, err := ParseConfig(strings.NewReader(sample))
	if err != nil {
		t.Fatal(err)
	}
	r := &record{}
	return NewMachine(cfg, r, watch), r
}

func TestParse(t *testing.T) {
	cfg, err := ParseConfig(strings.NewReader(sample))
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(cfg.Init, []string{"bl() { :; }"}) {
		t.Fatalf("init %q", cfg.Init)
	}
	want := &Binding{Tap: "bl -5", Hold: "am start -n x/.Y"}
	if got := cfg.Bindings[403]; !reflect.DeepEqual(got, want) {
		t.Fatalf("CHANNELDOWN %+v", got)
	}
	if got := cfg.Bindings[402]; got.Tap != "bl 5" || !got.Repeat {
		t.Fatalf("CHANNELUP %+v", got)
	}
}

func TestShippedConfigParses(t *testing.T) {
	f, err := os.Open("prosonic.conf")
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	if _, err := ParseConfig(f); err != nil {
		t.Fatal(err)
	}
}

func TestParseRefusesNonsense(t *testing.T) {
	for _, bad := range []string{"VOLUMEUP smooth now", "GREEN tap", "NOPE tap x", "GREEN blink x", "VOLUMEUP smooth\nVOLUMEUP tap x",
		"GREEN repeat x\nGREEN tap y", "GREEN tap x\nGREEN tap y", "GREEN hold x\nGREEN hold y", "800 tap x"} {
		if _, err := ParseConfig(strings.NewReader(bad)); err == nil {
			t.Errorf("%q parsed", bad)
		}
	}
}

func TestUnboundKeysPassThrough(t *testing.T) {
	m, r := machine(t)
	now := time.Now()
	m.Key(103, 1, now)
	m.Key(103, 2, now)
	m.Key(103, 0, now)
	if !reflect.DeepEqual(*r, record{"fwd 103 1", "fwd 103 2", "fwd 103 0"}) {
		t.Fatal(*r)
	}
}

func TestHeldVolumeIsSinglePresses(t *testing.T) {
	m, r := machine(t)
	t0 := time.Now()
	m.Key(115, 1, t0)
	m.Key(115, 2, t0.Add(300*time.Millisecond)) // the kernel's repeat is not passed on
	for at := t0; at.Before(t0.Add(650 * time.Millisecond)); at = at.Add(10 * time.Millisecond) {
		m.Tick(at)
	}
	m.Key(115, 0, t0.Add(650*time.Millisecond))
	m.Tick(t0.Add(time.Second))
	// the press, one at 300 ms, then every 60 ms up to 600
	if len(*r) != 7 || (*r)[0] != "press 115" {
		t.Fatal(*r)
	}
}

func TestTapThenHold(t *testing.T) {
	m, r := machine(t)
	t0 := time.Now()
	m.Key(403, 1, t0)
	m.Tick(t0.Add(200 * time.Millisecond))
	m.Key(403, 0, t0.Add(250*time.Millisecond))
	m.Key(403, 1, t0.Add(time.Second))
	m.Tick(t0.Add(time.Second + HoldAfter))
	m.Key(403, 0, t0.Add(2*time.Second))
	if !reflect.DeepEqual(*r, record{"run bl -5", "run am start -n x/.Y"}) {
		t.Fatal(*r)
	}
}

func TestRepeatWhileHeld(t *testing.T) {
	m, r := machine(t)
	t0 := time.Now()
	m.Key(402, 1, t0)
	m.Tick(t0.Add(RepeatDelay))
	m.Tick(t0.Add(RepeatDelay + RepeatEvery))
	m.Key(402, 0, t0.Add(time.Second))
	m.Tick(t0.Add(2 * time.Second))
	if len(*r) != 3 {
		t.Fatal(*r)
	}
	if !m.Next().IsZero() {
		t.Fatal("work left after release")
	}
}

func TestWatchedHeldVolumeStopsAndroidRepeat(t *testing.T) {
	m, r := machineIn(t, true)
	t0 := time.Now()
	m.Key(115, 1, t0)
	for at := t0; at.Before(t0.Add(650 * time.Millisecond)); at = at.Add(10 * time.Millisecond) {
		m.Tick(at)
	}
	m.Key(115, 0, t0.Add(650*time.Millisecond))
	m.Tick(t0.Add(time.Second))
	// Android's own press, its repeat ended at 250 ms, presses every 60 ms from 300 to 600
	want := []string{"release 115", "press 115", "press 115", "press 115", "press 115", "press 115", "press 115"}
	if !reflect.DeepEqual([]string(*r), want) {
		t.Fatal(*r)
	}
}

// A key-up the kernel lost must not leave a key pressing forever.
func TestKeyWithoutUpStopsAtMaxHeld(t *testing.T) {
	m, r := machineIn(t, true)
	t0 := time.Now()
	m.Key(115, 1, t0)
	m.Key(402, 1, t0)
	for at := t0; at.Before(t0.Add(MaxHeld + time.Second)); at = at.Add(10 * time.Millisecond) {
		m.Tick(at)
	}
	n := len(*r)
	m.Tick(t0.Add(time.Minute))
	if len(*r) != n || !m.Next().IsZero() || len(m.Held()) != 0 {
		t.Fatalf("still pressing after %v: %d", MaxHeld, len(*r)-n)
	}
}

func TestWatchedQuickVolumeTapAddsNothing(t *testing.T) {
	m, r := machineIn(t, true)
	t0 := time.Now()
	m.Key(115, 1, t0)
	m.Tick(t0.Add(100 * time.Millisecond))
	m.Key(115, 0, t0.Add(150*time.Millisecond))
	m.Tick(t0.Add(time.Second))
	if len(*r) != 0 {
		t.Fatal(*r)
	}
}
