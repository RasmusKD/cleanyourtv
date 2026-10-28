package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func testRunner(t *testing.T) (*runner, string) {
	t.Helper()
	r := newRunner([]string{`mark() { echo "$1" >> "$OUT"; }`})
	r.shell, r.timeout = "/bin/sh", 500*time.Millisecond
	out := filepath.Join(t.TempDir(), "out")
	t.Setenv("OUT", out)
	return r, out
}

func lines(t *testing.T, path string) string {
	t.Helper()
	b, _ := os.ReadFile(path)
	return strings.Join(strings.Fields(string(b)), " ")
}

// A command that exits, reads stdin, has an unclosed quote or never ends affects only itself; the helper
// functions stay defined for every command, and the commands run in order.
func TestEachCommandIsolated(t *testing.T) {
	r, out := testRunner(t)
	commands := make(chan string, 8)
	for _, c := range []string{"mark 1", "exit 3", "read x; mark read-$x", `mark "2`, "sleep 30", "mark 3", "exec true", "mark 4"} {
		commands <- c
	}
	close(commands)
	start := time.Now()
	r.serve(commands)
	if got := lines(t, out); got != "1 read- 3 4" {
		t.Fatalf("%q", got)
	}
	if time.Since(start) > 5*time.Second {
		t.Fatal("the sleeping command was not ended")
	}
}

func TestCheckRefusesUnparseable(t *testing.T) {
	r, _ := testRunner(t)
	if err := r.check(`am start -n "x/.Y`); err == nil {
		t.Fatal("unclosed quote passed")
	}
	if err := r.check("mark ok"); err != nil {
		t.Fatal(err)
	}
}
