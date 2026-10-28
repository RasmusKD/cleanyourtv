package main

import (
	"bufio"
	"fmt"
	"io"
	"strconv"
	"strings"
)

// Linux input key codes (linux/input-event-codes.h) a config may name; any other key is written as its number.
var keyNames = map[string]uint16{
	"MUTE": 113, "VOLUMEDOWN": 114, "VOLUMEUP": 115, "POWER": 116,
	"HOME": 102, "BACK": 158, "MENU": 139, "INFO": 358,
	"RED": 398, "GREEN": 399, "YELLOW": 400, "BLUE": 401,
	"CHANNELUP": 402, "CHANNELDOWN": 403,
	"PAGEUP": 104, "PAGEDOWN": 109,
	"NUMERIC_0": 512, "NUMERIC_1": 513, "NUMERIC_2": 514, "NUMERIC_3": 515, "NUMERIC_4": 516,
	"NUMERIC_5": 517, "NUMERIC_6": 518, "NUMERIC_7": 519, "NUMERIC_8": 520, "NUMERIC_9": 521,
}

// androidKeys are the Android key codes of the keys a smooth binding can press (Android's Generic.kl): a watching
// daemon presses them through Android's input injection, which takes Android codes.
var androidKeys = map[uint16]int{
	114: 25, 115: 24, // VOLUMEDOWN, VOLUMEUP
	402: 166, 403: 167, // CHANNELUP, CHANNELDOWN
	103: 19, 108: 20, 105: 21, 106: 22, // DPAD UP, DOWN, LEFT, RIGHT
}

// Binding is what one remote key does. A key with no binding passes through to Android unchanged.
type Binding struct {
	// Smooth sends a held key as separate presses, so Android takes one step per press instead of its
	// accelerated repeat (volume: 1 step per press where a held key moves 3).
	Smooth bool
	// Tap runs on the press, or on the release when the key also has a Hold command.
	Tap string
	// Repeat runs Tap again while the key is held. Ignored with Hold.
	Repeat bool
	// Hold runs once the key has been held for the hold time; Tap then does not run.
	Hold string
}

// Config is the parsed config file: shell lines to run once at start (helper functions), and the key bindings.
type Config struct {
	Init     []string
	Bindings map[uint16]*Binding
}

// ParseConfig reads lines of "<key> <kind> [command]"; kind is smooth, tap, repeat or hold. "sh <line>" runs the
// line in the action shell at start. Blank lines and lines starting with # are skipped.
func ParseConfig(r io.Reader) (*Config, error) {
	cfg := &Config{Bindings: map[uint16]*Binding{}}
	scan := bufio.NewScanner(r)
	for n := 1; scan.Scan(); n++ {
		line := strings.TrimSpace(scan.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		if rest, ok := strings.CutPrefix(line, "sh "); ok {
			cfg.Init = append(cfg.Init, strings.TrimSpace(rest))
			continue
		}
		fields := strings.Fields(line)
		if len(fields) < 2 {
			return nil, fmt.Errorf("line %d: want <key> <kind> [command]", n)
		}
		code, err := keyCode(fields[0])
		if err != nil {
			return nil, fmt.Errorf("line %d: %w", n, err)
		}
		command := strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(strings.TrimPrefix(line, fields[0])), fields[1]))
		b := cfg.Bindings[code]
		if b == nil {
			b = &Binding{}
			cfg.Bindings[code] = b
		}
		switch fields[1] {
		case "smooth":
			if command != "" {
				return nil, fmt.Errorf("line %d: smooth takes no command", n)
			}
			if _, ok := androidKeys[code]; !ok {
				return nil, fmt.Errorf("line %d: smooth works on volume, channel and arrow keys only", n)
			}
			b.Smooth = true
		case "tap", "repeat", "hold":
			if command == "" {
				return nil, fmt.Errorf("line %d: %s needs a command", n, fields[1])
			}
			switch fields[1] {
			case "tap":
				b.Tap = command
			case "repeat":
				b.Tap, b.Repeat = command, true
			case "hold":
				b.Hold = command
			}
		default:
			return nil, fmt.Errorf("line %d: unknown kind %q (smooth, tap, repeat or hold)", n, fields[1])
		}
		if b.Smooth && (b.Tap != "" || b.Hold != "") {
			return nil, fmt.Errorf("line %d: a smooth key cannot also run commands", n)
		}
	}
	return cfg, scan.Err()
}

func (c *Config) hasSmooth() bool {
	for _, b := range c.Bindings {
		if b.Smooth {
			return true
		}
	}
	return false
}

func keyCode(name string) (uint16, error) {
	if code, ok := keyNames[strings.ToUpper(strings.TrimPrefix(name, "KEY_"))]; ok {
		return code, nil
	}
	n, err := strconv.ParseUint(name, 10, 16)
	if err != nil {
		return 0, fmt.Errorf("unknown key %q", name)
	}
	return uint16(n), nil
}
