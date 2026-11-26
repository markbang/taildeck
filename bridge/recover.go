package tsbridge

import (
	"fmt"
	"runtime/debug"
)

// Recover runs fn and turns a panic into an error. The cgo entry points use it
// so a panic answers with the usual JSON envelope: a Go panic that unwinds into
// C cannot be caught there and aborts the process, which on Android looks like
// the app dying on a tap.
func Recover(where string, fn func() error) (err error) {
	defer func() {
		if r := recover(); r != nil {
			err = NotePanic(where, r)
		}
	}()
	return fn()
}

// NotePanic records a recovered panic in the log ring the app shows and returns
// the error to report.
func NotePanic(where string, r any) error {
	logs.add(fmt.Sprintf("panic in %s: %v\n%s", where, r, debug.Stack()))
	return fmt.Errorf("%s: internal error: %v", where, r)
}
