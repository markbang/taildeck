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
	"os"
	"path/filepath"
	"strings"
	"sync"
	"unsafe"

	tsbridge "github.com/markbang/taildeck/bridge"
)

// SSH event kinds; the node uses 1..3.
const (
	eventSSHData   = 4 // {"id":N,"data":"<base64>"}
	eventSSHClosed = 5 // {"id":N,"closed":true,"reason":""}
)

type sshConfig struct {
	Host       string `json:"host"`
	Port       int    `json:"port"`
	User       string `json:"user"`
	Password   string `json:"password"`
	KeyPath    string `json:"keyPath"`
	Passphrase string `json:"passphrase"`
	CertPath   string `json:"certPath"`
	Cols       int    `json:"cols"`
	Rows       int    `json:"rows"`
}

type sshEntry struct {
	id   int
	once sync.Once // one close event per session
	mu   sync.Mutex
	sess *tsbridge.SSHSession
	cols int
	rows int
	gone bool
}

var (
	sshMu   sync.Mutex
	sshOpen = map[int]*sshEntry{}
	sshNext = 1
)

//export td_ssh_dial
func td_ssh_dial(configJSON *C.char) *C.char {
	return guard("td_ssh_dial", func() *C.char {
		var cfg sshConfig
		raw := strings.TrimSpace(C.GoString(configJSON))
		if raw == "" {
			return fail(errors.New("ssh config is required"))
		}
		if err := json.Unmarshal([]byte(raw), &cfg); err != nil {
			return fail(fmt.Errorf("parse ssh config: %w", err))
		}
		cfg.Host = strings.TrimSpace(cfg.Host)
		if cfg.Host == "" {
			return fail(errors.New("ssh host is required"))
		}
		if cfg.Port == 0 {
			cfg.Port = 22
		}
		if cfg.Port < 1 || cfg.Port > 65535 {
			return fail(fmt.Errorf("ssh port %d is out of range", cfg.Port))
		}
		if cfg.User == "" {
			cfg.User = "root"
		}
		if cfg.Cols <= 0 {
			cfg.Cols = 80
		}
		if cfg.Rows <= 0 {
			cfg.Rows = 24
		}
		if err := nodeReady(); err != nil {
			return fail(err)
		}

		entry := &sshEntry{cols: cfg.Cols, rows: cfg.Rows}
		sshMu.Lock()
		entry.id = sshNext
		sshNext++
		sshOpen[entry.id] = entry
		sshMu.Unlock()

		// Dialing blocks (host key TOFU, Tailscale check mode), so it runs off the
		// Dart thread; failures arrive as a close event with the reason.
		notice := fmt.Sprintf("\x1b[2mConnecting to %s@%s:%d…\x1b[0m\r\n", cfg.User, cfg.Host, cfg.Port)
		emit(eventSSHData, map[string]any{
			"id":     entry.id,
			"data":   base64.StdEncoding.EncodeToString([]byte(notice)),
			"notice": true,
		})
		go func() {
			defer func() {
				if r := recover(); r != nil {
					entry.close(tsbridge.NotePanic("td_ssh_dial", r).Error())
				}
			}()
			entry.mu.Lock()
			cols, rows := entry.cols, entry.rows
			entry.mu.Unlock()
			sess, err := tsbridge.DialSSH(cfg.Host, cfg.Port, cfg.User, cfg.Password,
				cfg.KeyPath, cfg.Passphrase, cfg.CertPath, cols, rows, entry)
			if err != nil {
				entry.close(err.Error())
				return
			}
			entry.mu.Lock()
			gone, cols, rows := entry.gone, entry.cols, entry.rows
			if !gone {
				entry.sess = sess
			}
			entry.mu.Unlock()
			if gone {
				sess.Close()
				return
			}
			// The host may have resized the grid while the handshake was running.
			if cols != cfg.Cols || rows != cfg.Rows {
				_ = sess.Resize(cols, rows)
			}
		}()
		return ok(map[string]any{"id": entry.id})
	})
}

//export td_ssh_write
func td_ssh_write(id C.int, data unsafe.Pointer, n C.int) *C.char {
	return guard("td_ssh_write", func() *C.char {
		entry := lookupSSH(int(id))
		if entry == nil {
			return fail(fmt.Errorf("ssh session %d is not open", int(id)))
		}
		entry.mu.Lock()
		sess, gone := entry.sess, entry.gone
		entry.mu.Unlock()
		if gone {
			return fail(fmt.Errorf("ssh session %d is closed", int(id)))
		}
		if sess == nil {
			return fail(fmt.Errorf("ssh session %d is not connected yet", int(id)))
		}
		if n <= 0 {
			return ok(nil)
		}
		if err := sess.Write(C.GoBytes(data, n)); err != nil {
			return fail(fmt.Errorf("ssh write: %w", err))
		}
		return ok(nil)
	})
}

//export td_ssh_resize
func td_ssh_resize(id C.int, cols C.int, rows C.int) *C.char {
	return guard("td_ssh_resize", func() *C.char {
		entry := lookupSSH(int(id))
		if entry == nil {
			return fail(fmt.Errorf("ssh session %d is not open", int(id)))
		}
		if cols <= 0 || rows <= 0 {
			return fail(errors.New("ssh resize needs positive cols and rows"))
		}
		entry.mu.Lock()
		entry.cols, entry.rows = int(cols), int(rows)
		sess, gone := entry.sess, entry.gone
		entry.mu.Unlock()
		// Resizing before the handshake finishes is kept for the dial goroutine.
		if sess == nil || gone {
			return ok(nil)
		}
		if err := sess.Resize(int(cols), int(rows)); err != nil {
			return fail(fmt.Errorf("ssh resize: %w", err))
		}
		return ok(nil)
	})
}

//export td_ssh_close
func td_ssh_close(id C.int) *C.char {
	return guard("td_ssh_close", func() *C.char {
		entry := lookupSSH(int(id))
		if entry == nil {
			return ok(nil)
		}
		sshMu.Lock()
		delete(sshOpen, entry.id)
		sshMu.Unlock()
		entry.mu.Lock()
		entry.gone = true
		sess := entry.sess
		entry.mu.Unlock()
		if sess != nil {
			sess.Close()
			return ok(nil)
		}
		// A dial still in flight closes itself once it returns.
		entry.close("closed")
		return ok(nil)
	})
}

//export td_key_ensure
func td_key_ensure(path, comment *C.char) *C.char {
	return guard("td_key_ensure", func() *C.char {
		p := strings.TrimSpace(C.GoString(path))
		if p == "" {
			return fail(errors.New("key path is required"))
		}
		if err := os.MkdirAll(filepath.Dir(p), 0o700); err != nil {
			return fail(fmt.Errorf("create key dir: %w", err))
		}
		pub, err := tsbridge.EnsureKey(p, C.GoString(comment))
		if err != nil {
			return fail(err)
		}
		return ok(map[string]any{"public": pub, "path": p})
	})
}

//export td_forget_host_keys
func td_forget_host_keys(dir *C.char) *C.char {
	return guard("td_forget_host_keys", func() *C.char {
		if err := tsbridge.ForgetHostKeys(C.GoString(dir)); err != nil {
			return fail(err)
		}
		return ok(nil)
	})
}

// nodeReady rejects dials before the node is signed in, instead of letting
// the dialer time out on an unreachable tailnet.
func nodeReady() error {
	raw, err := tsbridge.Status()
	if err != nil {
		return fmt.Errorf("tailscale is not running: %w", err)
	}
	var st struct {
		State string `json:"state"`
	}
	if err := json.Unmarshal([]byte(raw), &st); err != nil {
		return fmt.Errorf("read tailscale state: %w", err)
	}
	if st.State != "Running" {
		return fmt.Errorf("tailscale is not logged in (state %q); sign in before opening SSH", st.State)
	}
	return nil
}

func lookupSSH(id int) *sshEntry {
	sshMu.Lock()
	defer sshMu.Unlock()
	return sshOpen[id]
}

func (e *sshEntry) OnData(data []byte) {
	if len(data) == 0 {
		return
	}
	emit(eventSSHData, map[string]any{
		"id":   e.id,
		"data": base64.StdEncoding.EncodeToString(data),
	})
}

func (e *sshEntry) OnClosed(reason string) { e.close(reason) }

// close emits the one close event for this session and forgets it.
func (e *sshEntry) close(reason string) {
	e.once.Do(func() {
		sshMu.Lock()
		delete(sshOpen, e.id)
		sshMu.Unlock()
		emit(eventSSHClosed, map[string]any{"id": e.id, "closed": true, "reason": reason})
	})
}
