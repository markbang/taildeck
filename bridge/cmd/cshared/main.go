// Command cshared exposes the tsbridge engine through a plain C ABI for
// non-gomobile hosts (Flutter via dart:ffi, desktop, iOS later).
//
//	go build -buildmode=c-shared -o build/libtaildeck_bridge.so ./cmd/cshared
package main

/*
#include <stdint.h>
#include <stdlib.h>

typedef void (*td_event_fn)(int kind, char *json, void *ctx);
// The platform hooks return malloc'd strings; this package frees them.
typedef char *(*td_ifaces_fn)(void *ctx);
typedef char *(*td_default_if_fn)(void *ctx);
typedef char *(*td_lookup_fn)(char *host, void *ctx);

// Go cannot call a C function pointer directly, so trampoline the callbacks.
static void td_emit(td_event_fn cb, int kind, char *json, uintptr_t ctx) {
	cb(kind, json, (void *)ctx);
}
static char *td_call_ifaces(td_ifaces_fn fn, uintptr_t ctx) { return fn((void *)ctx); }
static char *td_call_default_if(td_default_if_fn fn, uintptr_t ctx) { return fn((void *)ctx); }
static char *td_call_lookup(td_lookup_fn fn, char *host, uintptr_t ctx) { return fn(host, (void *)ctx); }
*/
import "C"

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"
	"unsafe"

	tsbridge "github.com/markbang/taildeck/bridge"
)

// Event kinds passed to the registered callback.
const (
	eventState = 1 // {"state":"Running","authUrl":""}
	eventLogin = 2 // {"url":"https://login.tailscale.com/a/..."}
	eventLog   = 3 // {"line":"15:04:05 ..."}
)

type config struct {
	Dir         string `json:"dir"`
	Hostname    string `json:"hostname"`
	ControlURL  string `json:"controlURL"`
	AuthKey     string `json:"authKey"`
	GatewayPort int    `json:"gatewayPort"`
	// Platform selects the network adapters: "go" (default) uses the Go
	// stdlib, "host" uses the callbacks from td_set_platform_cb.
	Platform string `json:"platform"`
}

var (
	cbMu     sync.Mutex
	cbFn     C.td_event_fn
	cbCtx    C.uintptr_t
	logsMu   sync.Mutex
	logsAt   int
	tailStop chan struct{}
)

//export td_start
func td_start(configJSON *C.char) *C.char {
	return guard("td_start", func() *C.char {
		var cfg config
		if raw := C.GoString(configJSON); raw != "" {
			if err := json.Unmarshal([]byte(raw), &cfg); err != nil {
				return fail(fmt.Errorf("parse config: %w", err))
			}
		}
		if cfg.Dir == "" {
			return fail(errors.New("config.dir is required"))
		}
		if cfg.Hostname == "" {
			cfg.Hostname = "taildeck"
		}
		var platform tsbridge.Platform
		switch cfg.Platform {
		case "", "go":
			platform = goPlatform{}
		case "host":
			if !hostHooksReady() {
				return fail(errors.New("platform \"host\" needs td_set_platform_cb first"))
			}
			platform = hostPlatform{}
		default:
			return fail(fmt.Errorf("unknown platform %q (want \"go\" or \"host\")", cfg.Platform))
		}
		if err := tsbridge.Start(cfg.Dir, cfg.Hostname, cfg.ControlURL, cfg.AuthKey, cfg.GatewayPort, platform, listener{}); err != nil {
			return fail(err)
		}
		startTail()
		return ok(map[string]any{"gatewayPort": tsbridge.GatewayPort()})
	})
}

//export td_stop
func td_stop() *C.char {
	return guard("td_stop", func() *C.char {
		closeHerdr()
		tsbridge.Stop()
		stopTail()
		return ok(nil)
	})
}

//export td_logout
func td_logout() *C.char {
	return guard("td_logout", func() *C.char {
		closeHerdr()
		if err := tsbridge.Logout(); err != nil {
			return fail(err)
		}
		return ok(nil)
	})
}

//export td_network_changed
func td_network_changed() *C.char {
	return guard("td_network_changed", func() *C.char {
		tsbridge.NetworkChanged()
		return ok(nil)
	})
}

//export td_status
func td_status() *C.char {
	return guard("td_status", func() *C.char {
		raw, err := tsbridge.Status()
		if err != nil {
			return fail(err)
		}
		return okJSON(raw)
	})
}

//export td_logs
func td_logs() *C.char {
	return guard("td_logs", func() *C.char {
		return ok(map[string]any{"logs": tsbridge.Logs()})
	})
}

//export td_gateway_port
func td_gateway_port() C.int {
	return guardInt("td_gateway_port", func() C.int {
		return C.int(tsbridge.GatewayPort())
	}, 0)
}

//export td_ping
func td_ping(ip *C.char) *C.char {
	return guard("td_ping", func() *C.char {
		raw, err := tsbridge.Ping(C.GoString(ip))
		if err != nil {
			return fail(err)
		}
		return okJSON(raw)
	})
}

// td_ping_cancel stops a ping in flight to ip, so leaving the page does not
// leave a call running in the library.
//
//export td_ping_cancel
func td_ping_cancel(ip *C.char) *C.char {
	return guard("td_ping_cancel", func() *C.char {
		return ok(map[string]any{"cancelled": tsbridge.CancelPing(C.GoString(ip))})
	})
}

//export td_probe
func td_probe(host *C.char, portsCSV *C.char) *C.char {
	return guard("td_probe", func() *C.char {
		raw, err := tsbridge.Probe(C.GoString(host), C.GoString(portsCSV))
		if err != nil {
			return fail(err)
		}
		return ok(map[string]any{"ports": json.RawMessage(raw)})
	})
}

// td_favicon fetches a service icon through the node. host is the peer name,
// ports is a comma-separated list to try in order, scheme is "http" or "https".
// Third-party favicon services are never contacted: only the peer answers.
//
//export td_favicon
func td_favicon(host *C.char, portsCSV *C.char, scheme *C.char) *C.char {
	return guard("td_favicon", func() *C.char {
		raw, err := tsbridge.Favicon(C.GoString(host), C.GoString(portsCSV), C.GoString(scheme))
		if err != nil {
			return fail(err)
		}
		return okJSON(raw)
	})
}

//export td_set_event_cb
func td_set_event_cb(cb C.td_event_fn, ctx C.uintptr_t) {
	guardVoid("td_set_event_cb", func() {
		cbMu.Lock()
		cbFn, cbCtx = cb, ctx
		cbMu.Unlock()
	})
}

//export td_free
func td_free(p *C.char) {
	guardVoid("td_free", func() {
		C.free(unsafe.Pointer(p))
	})
}

// td_set_platform_cb installs the host's network hooks: Android 11+ blocks
// Go's netlink access and has no resolv.conf. Each hook returns a malloc'd
// UTF-8 string, which hostPlatform frees.
//
//export td_set_platform_cb
func td_set_platform_cb(ifaces C.td_ifaces_fn, def C.td_default_if_fn, lookup C.td_lookup_fn, ctx C.uintptr_t) {
	guardVoid("td_set_platform_cb", func() {
		hostHooks.mu.Lock()
		hostHooks.ifaces, hostHooks.def, hostHooks.lookup, hostHooks.ctx = ifaces, def, lookup, ctx
		hostHooks.mu.Unlock()
	})
}

var hostHooks struct {
	mu     sync.Mutex
	ifaces C.td_ifaces_fn
	def    C.td_default_if_fn
	lookup C.td_lookup_fn
	ctx    C.uintptr_t
}

// hostPlatform forwards to td_set_platform_cb. Android 11+ blocks Go's
// net.Interfaces and has no resolv.conf, so the app supplies both.
type hostPlatform struct{}

func (hostPlatform) Interfaces() string {
	hostHooks.mu.Lock()
	fn, ctx := hostHooks.ifaces, hostHooks.ctx
	hostHooks.mu.Unlock()
	if fn == nil {
		return "[]"
	}
	raw := C.td_call_ifaces(fn, ctx)
	if raw == nil {
		return "[]"
	}
	defer C.free(unsafe.Pointer(raw))
	if s := C.GoString(raw); s != "" {
		return s
	}
	return "[]"
}

func (hostPlatform) DefaultInterface() string {
	hostHooks.mu.Lock()
	fn, ctx := hostHooks.def, hostHooks.ctx
	hostHooks.mu.Unlock()
	if fn == nil {
		return ""
	}
	raw := C.td_call_default_if(fn, ctx)
	if raw == nil {
		return ""
	}
	defer C.free(unsafe.Pointer(raw))
	return C.GoString(raw)
}

func (hostPlatform) Lookup(host string) string {
	hostHooks.mu.Lock()
	fn, ctx := hostHooks.lookup, hostHooks.ctx
	hostHooks.mu.Unlock()
	if fn == nil {
		return ""
	}
	chost := C.CString(host)
	defer C.free(unsafe.Pointer(chost))
	raw := C.td_call_lookup(fn, chost, ctx)
	if raw == nil {
		return ""
	}
	defer C.free(unsafe.Pointer(raw))
	return C.GoString(raw)
}

func hostHooksReady() bool {
	hostHooks.mu.Lock()
	defer hostHooks.mu.Unlock()
	return hostHooks.ifaces != nil && hostHooks.def != nil && hostHooks.lookup != nil
}

// emit sends an event to the host callback. json is malloc'd and the host
// takes ownership: it must release it with td_free, which keeps the string
// valid past the callback (Dart reads it on another thread).
func emit(kind int, v any) {
	cbMu.Lock()
	cb, ctx := cbFn, cbCtx
	cbMu.Unlock()
	if cb == nil {
		return
	}
	b, err := json.Marshal(v)
	if err != nil {
		return
	}
	C.td_emit(cb, C.int(kind), C.CString(string(b)), ctx)
}

type listener struct{}

func (listener) OnState(state, authURL string) {
	emit(eventState, map[string]any{"state": state, "authUrl": authURL})
	if authURL != "" {
		emit(eventLogin, map[string]any{"url": authURL})
	}
}

// tailLogs turns new ring-log lines into log events.
func tailLogs(stop chan struct{}) {
	defer func() {
		if r := recover(); r != nil {
			_ = tsbridge.NotePanic("tailLogs", r)
		}
	}()
	for {
		select {
		case <-stop:
			return
		case <-time.After(500 * time.Millisecond):
		}
		lines := strings.Split(tsbridge.Logs(), "\n")
		logsMu.Lock()
		seen := logsAt
		if len(lines) < seen {
			seen = 0 // log ring wrapped
		}
		fresh := lines[seen:]
		logsAt = len(lines)
		logsMu.Unlock()
		for _, line := range fresh {
			if line != "" {
				emit(eventLog, map[string]any{"line": line})
			}
		}
	}
}

func startTail() {
	logsMu.Lock()
	defer logsMu.Unlock()
	if tailStop != nil {
		return
	}
	// The ring keeps lines from a previous run; skip those.
	logsAt = len(strings.Split(tsbridge.Logs(), "\n"))
	stop := make(chan struct{})
	tailStop = stop
	go tailLogs(stop)
}

func stopTail() {
	logsMu.Lock()
	defer logsMu.Unlock()
	if tailStop != nil {
		close(tailStop)
		tailStop = nil
	}
}

func ok(extra map[string]any) *C.char {
	m := map[string]any{"ok": true}
	for k, v := range extra {
		m[k] = v
	}
	b, err := json.Marshal(m)
	if err != nil {
		return fail(err)
	}
	return C.CString(string(b))
}

func fail(err error) *C.char {
	b, _ := json.Marshal(map[string]any{"ok": false, "error": err.Error()})
	return C.CString(string(b))
}

// okJSON merges an object produced by tsbridge into the envelope.
func okJSON(raw string) *C.char {
	var m map[string]any
	if err := json.Unmarshal([]byte(raw), &m); err != nil {
		return fail(fmt.Errorf("decode result: %w", err))
	}
	return ok(m)
}

func main() {}
