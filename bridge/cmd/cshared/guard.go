package main

/*
#include <stdlib.h>
*/
import "C"

import (
	tsbridge "github.com/markbang/taildeck/bridge"
)

// guard answers a panic inside an export with the usual JSON error instead of
// letting it unwind into C, which aborts the process.
func guard(name string, fn func() *C.char) (out *C.char) {
	if err := tsbridge.Recover(name, func() error { out = fn(); return nil }); err != nil {
		return fail(err)
	}
	return out
}

func guardVoid(name string, fn func()) {
	_ = tsbridge.Recover(name, func() error { fn(); return nil })
}

func guardInt(name string, fn func() C.int, fallback C.int) (out C.int) {
	if err := tsbridge.Recover(name, func() error { out = fn(); return nil }); err != nil {
		return fallback
	}
	return out
}
