package main

import (
	"log"
	"os"
	"os/exec"
	"strings"
	"syscall"
	"time"
)

// CommandTimeout ends a bound command that has not finished, with everything it started.
const CommandTimeout = 10 * time.Second

// runner runs each bound command in a shell of its own, one after another in the order the keys came, with the
// config's sh lines (helper functions) defined first. A command that waits, reads stdin, has an unclosed quote,
// or runs exit or exec affects only itself.
type runner struct {
	shell   string
	prelude string
	timeout time.Duration
}

func newRunner(init []string) *runner {
	return &runner{shell: "/system/bin/sh", prelude: strings.Join(init, "\n"), timeout: CommandTimeout}
}

func (r *runner) script(command string) string {
	if r.prelude == "" {
		return command
	}
	return r.prelude + "\n" + command
}

// check refuses a command the shell cannot parse, so a broken config line fails at start, not at its key press.
func (r *runner) check(command string) error {
	out, err := exec.Command(r.shell, "-n", "-c", r.script(command)).CombinedOutput()
	if err != nil {
		return &shellError{command: command, out: strings.TrimSpace(string(out)), err: err}
	}
	return nil
}

// run runs one command; stdin is empty, and after the timeout its whole process group is killed.
func (r *runner) run(command string) {
	cmd := exec.Command(r.shell, "-c", r.script(command))
	cmd.Stdout, cmd.Stderr = os.Stderr, os.Stderr
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	if err := cmd.Start(); err != nil {
		log.Printf("run %s: %v", command, err)
		return
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case err := <-done:
		if err != nil {
			log.Printf("%s: %v", command, err)
		}
	case <-time.After(r.timeout):
		syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
		<-done
		log.Printf("%s: killed after %v", command, r.timeout)
	}
}

func (r *runner) serve(commands <-chan string) {
	for command := range commands {
		r.run(command)
	}
}

type shellError struct {
	command, out string
	err          error
}

func (e *shellError) Error() string { return e.command + ": " + e.err.Error() + ": " + e.out }
