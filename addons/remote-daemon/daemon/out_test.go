package main

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"strings"
	"testing"
	"time"
)

func TestWatchingPressesAndReleasesThroughInject(t *testing.T) {
	var inject bytes.Buffer
	o := &uinputOut{inject: &inject}
	o.Press(115)
	o.Release(115)
	o.Forward(115, 1) // Android reads the remote itself: nothing to forward
	if got := inject.String(); got != "p 24\nu 24\n" {
		t.Fatalf("%q", got)
	}
}

func TestHoldingPressesThroughTheCopy(t *testing.T) {
	var dev, inject bytes.Buffer
	o := &uinputOut{dev: &dev, inject: &inject}
	o.Press(114)
	o.Release(114) // Android never saw the key go down
	var got []string
	for b := dev.Bytes(); len(b) >= eventSize; b = b[eventSize:] {
		got = append(got, fmt.Sprintf("%d %d %d", binary.LittleEndian.Uint16(b[8:]),
			binary.LittleEndian.Uint16(b[10:]), binary.LittleEndian.Uint32(b[12:])))
	}
	if strings.Join(got, ",") != "1 114 1,0 0 0,1 114 0,0 0 0" || inject.Len() != 0 {
		t.Fatalf("%v inject %q", got, inject.String())
	}
}

func TestRunNeverWaits(t *testing.T) {
	commands := make(chan string, 1)
	o := &uinputOut{commands: commands}
	o.Run("a")
	o.Run("b") // the queue is full: dropped, not blocking
	if len(commands) != 1 || <-commands != "a" {
		t.Fatal("queue")
	}
}

// Android starts its own repeat of a held key at long_press_timeout (400 ms by default): a watching daemon must
// end the remote's press before then, or volume takes Android's accelerated steps as well as the daemon's.
func TestStopRepeatBeforeAndroidRepeats(t *testing.T) {
	if StopRepeatAfter >= 400*time.Millisecond || StopRepeatAfter >= SmoothDelay {
		t.Fatal(StopRepeatAfter)
	}
}
