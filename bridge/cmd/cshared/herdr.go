package main

/*
#include <stdint.h>
#include <stdlib.h>
*/
import "C"

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
	"unsafe"

	tsbridge "github.com/markbang/taildeck/bridge"
)

// Herdr events (kind 6) are invalidation signals: the client re-reads state.
const eventHerdr = 6

type herdrConfig struct {
	Host       string `json:"host"`
	Port       int    `json:"port"`
	User       string `json:"user"`
	Password   string `json:"password"`
	KeyPath    string `json:"keyPath"`
	Passphrase string `json:"passphrase"`
	CertPath   string `json:"certPath"`
}

var (
	herdrMu   sync.Mutex
	herdrConn *tsbridge.Herdr
	herdrSeq  int
)

//export td_herdr_connect
func td_herdr_connect(configJSON *C.char) *C.char {
	return guard("td_herdr_connect", func() *C.char {
		var cfg herdrConfig
		raw := strings.TrimSpace(C.GoString(configJSON))
		if raw == "" {
			return fail(errors.New("herdr config is required"))
		}
		if err := json.Unmarshal([]byte(raw), &cfg); err != nil {
			return fail(fmt.Errorf("parse herdr config: %w", err))
		}
		cfg.Host = strings.TrimSpace(cfg.Host)
		if cfg.Host == "" {
			return fail(errors.New("herdr host is required"))
		}
		if cfg.Port == 0 {
			cfg.Port = 22
		}
		if cfg.Port < 1 || cfg.Port > 65535 {
			return fail(fmt.Errorf("herdr port %d is out of range", cfg.Port))
		}
		if cfg.User == "" {
			cfg.User = "root"
		}
		if err := nodeReady(); err != nil {
			return fail(err)
		}

		herdrMu.Lock()
		herdrSeq++
		id := herdrSeq
		herdrMu.Unlock()

		h, err := tsbridge.ConnectHerdr(cfg.Host, cfg.Port, cfg.User, cfg.Password,
			cfg.KeyPath, cfg.Passphrase, cfg.CertPath, herdrListener{id: id})
		if err != nil {
			return fail(err)
		}
		herdrMu.Lock()
		old := herdrConn
		herdrConn = h
		herdrMu.Unlock()
		if old != nil {
			old.Close()
		}
		return ok(map[string]any{"id": id})
	})
}

//export td_herdr_close
func td_herdr_close() *C.char {
	return guard("td_herdr_close", func() *C.char {
		closeHerdr()
		return ok(nil)
	})
}

//export td_herdr_call
func td_herdr_call(method, paramsJSON *C.char) *C.char {
	return guard("td_herdr_call", func() *C.char {
		name := strings.TrimSpace(C.GoString(method))
		if name == "" {
			return fail(errors.New("herdr method is required"))
		}
		params := strings.TrimSpace(C.GoString(paramsJSON))
		if params == "" {
			params = "{}"
		}
		h, err := currentHerdr()
		if err != nil {
			return fail(err)
		}
		reply, err := h.Call(name, params)
		if err != nil {
			return fail(err)
		}
		return ok(map[string]any{"result": reply})
	})
}

//export td_herdr_transcript
func td_herdr_transcript(pane *C.char) *C.char {
	return guard("td_herdr_transcript", func() *C.char {
		h, target, err := herdrPane(pane)
		if err != nil {
			return fail(err)
		}
		reply, err := h.Transcript(target)
		if err != nil {
			return fail(err)
		}
		return ok(map[string]any{"result": reply})
	})
}

//export td_herdr_transcript_more
func td_herdr_transcript_more(pane *C.char) *C.char {
	return guard("td_herdr_transcript_more", func() *C.char {
		h, target, err := herdrPane(pane)
		if err != nil {
			return fail(err)
		}
		return ok(map[string]any{"more": h.TranscriptMore(target)})
	})
}

//export td_herdr_restart
func td_herdr_restart(pane, thenPrompt *C.char) *C.char {
	return guard("td_herdr_restart", func() *C.char {
		h, target, err := herdrPane(pane)
		if err != nil {
			return fail(err)
		}
		name, err := h.RestartAgent(target, strings.TrimSpace(C.GoString(thenPrompt)))
		if err != nil {
			return fail(err)
		}
		return ok(map[string]any{"name": name})
	})
}

//export td_herdr_models
func td_herdr_models(kind, pane *C.char) *C.char {
	return guard("td_herdr_models", func() *C.char {
		h, target, err := herdrPane(pane)
		if err != nil {
			return fail(err)
		}
		reply, err := h.ModelOptions(strings.TrimSpace(C.GoString(kind)), target)
		if err != nil {
			return fail(err)
		}
		return ok(map[string]any{"result": reply})
	})
}

//export td_herdr_commands
func td_herdr_commands(kind, cwd *C.char) *C.char {
	return guard("td_herdr_commands", func() *C.char {
		h, err := currentHerdr()
		if err != nil {
			return fail(err)
		}
		reply, err := h.Commands(strings.TrimSpace(C.GoString(kind)), C.GoString(cwd))
		if err != nil {
			return fail(err)
		}
		return ok(map[string]any{"result": reply})
	})
}

//export td_herdr_image
func td_herdr_image(ref *C.char) *C.char {
	return guard("td_herdr_image", func() *C.char {
		name := strings.TrimSpace(C.GoString(ref))
		if name == "" {
			return fail(errors.New("image ref is required"))
		}
		h, err := currentHerdr()
		if err != nil {
			return fail(err)
		}
		data, err := h.Image(name)
		if err != nil {
			return fail(err)
		}
		return ok(map[string]any{"data": base64.StdEncoding.EncodeToString(data)})
	})
}

// td_herdr_upload copies the bytes into the node's upload dir and returns the
// path, for prompts that reference the file.
//
//export td_herdr_upload
func td_herdr_upload(name *C.char, data *C.uchar, length C.int) *C.char {
	return guard("td_herdr_upload", func() *C.char {
		if length < 1 {
			return fail(errors.New("upload is empty"))
		}
		if length > maxUpload {
			return fail(fmt.Errorf("upload is larger than %d MB", maxUpload>>20))
		}
		h, err := currentHerdr()
		if err != nil {
			return fail(err)
		}
		file := strings.TrimSpace(C.GoString(name))
		path, err := h.Upload(file, C.GoBytes(unsafe.Pointer(data), length))
		if err != nil {
			return fail(err)
		}
		return ok(map[string]any{"path": path})
	})
}

//export td_herdr_subagent
func td_herdr_subagent(target, callID *C.char) *C.char {
	return guard("td_herdr_subagent", func() *C.char {
		call := strings.TrimSpace(C.GoString(callID))
		if call == "" {
			return fail(errors.New("subagent call id is required"))
		}
		h, pane, err := herdrPane(target)
		if err != nil {
			return fail(err)
		}
		reply, err := h.Subagent(pane, call)
		if err != nil {
			return fail(err)
		}
		return ok(map[string]any{"result": reply})
	})
}

const maxUpload = 32 << 20

func currentHerdr() (*tsbridge.Herdr, error) {
	herdrMu.Lock()
	defer herdrMu.Unlock()
	if herdrConn == nil {
		return nil, errors.New("herdr is not connected; open a connection to the node first")
	}
	return herdrConn, nil
}

func takeHerdr() *tsbridge.Herdr {
	herdrMu.Lock()
	defer herdrMu.Unlock()
	h := herdrConn
	herdrConn = nil
	return h
}

func closeHerdr() {
	if h := takeHerdr(); h != nil {
		h.Close()
	}
}

func herdrPane(pane *C.char) (*tsbridge.Herdr, string, error) {
	target := strings.TrimSpace(C.GoString(pane))
	if target == "" {
		return nil, "", errors.New("herdr pane is required")
	}
	h, err := currentHerdr()
	return h, target, err
}

// herdrListener tags events with the connection they belong to, so a replaced
// connection's late OnClosed cannot be mistaken for the current one.
type herdrListener struct{ id int }

func (l herdrListener) OnChange() {
	emit(eventHerdr, map[string]any{"event": "changed", "id": l.id})
}

func (l herdrListener) OnClosed(reason string) {
	emit(eventHerdr, map[string]any{"event": "closed", "reason": reason, "id": l.id})
}
