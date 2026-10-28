//go:build !linux

package main

import (
	"fmt"
	"os"
)

// The daemon runs on the TV (Linux); other platforms build it only to run the tests.
func main() {
	fmt.Fprintln(os.Stderr, "remote-daemon runs on the TV: GOOS=linux GOARCH=arm GOARM=7 go build")
	os.Exit(2)
}
