package tsbridge

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"sort"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/ssh"
)

// HerdrListener receives invalidation signals; the client re-reads state on OnChange.
type HerdrListener interface {
	OnChange()
	OnClosed(reason string)
}

// Herdr talks to a herdr server's socket API (newline-delimited JSON) on a
// tailnet node, tunnelled over SSH.
type Herdr struct {
	client   *ssh.Client
	socket   string
	listener HerdrListener

	mu     sync.Mutex
	conn   io.ReadWriteCloser
	reader *bufio.Reader
	nextID int

	tmu         sync.Mutex
	transcripts map[string]*transcript
	windows     map[string]int // bytes of history read per pane, grown by TranscriptMore
	images      *imageStore

	smu      sync.Mutex
	sessions map[string]*paneSession // sessions found from a pane's processes

	done      chan struct{}
	closeOnce sync.Once
}

const (
	errNotInstalled = "HERDR_NOT_INSTALLED"
	errNotRunning   = "HERDR_NOT_RUNNING"
)

// ConnectHerdr logs in like DialSSH and attaches to the node's herdr server.
// Errors start with HERDR_NOT_INSTALLED or HERDR_NOT_RUNNING when herdr is
// missing, so callers can tell "no herdr" from connection problems.
func ConnectHerdr(host string, port int, user, password, keyPath, passphrase, certPath string, listener HerdrListener) (*Herdr, error) {
	e, err := current()
	if err != nil {
		return nil, err
	}
	if listener == nil {
		return nil, errors.New("listener is required")
	}
	client, err := e.dialClient(host, port, user, password, keyPath, passphrase, certPath, func(string) {})
	if err != nil {
		return nil, err
	}
	h := &Herdr{
		client: client, listener: listener, done: make(chan struct{}),
		transcripts: map[string]*transcript{}, windows: map[string]int{},
		images: newImageStore(), sessions: map[string]*paneSession{},
	}
	socket, err := h.findSocket()
	if err != nil {
		client.Close()
		return nil, err
	}
	h.socket = socket
	if _, err := h.Call("ping", "{}"); err != nil {
		client.Close()
		return nil, fmt.Errorf("%s: %v", errNotRunning, err)
	}
	go h.watch()
	go func() {
		err := client.Wait()
		h.shutdown(fmt.Sprint(err))
	}()
	return h, nil
}

// findSocket mirrors herdr's resolution: default session first, then named sessions.
func (h *Herdr) findSocket() (string, error) {
	out, err := h.exec(`d="${XDG_CONFIG_HOME:-$HOME/.config}/herdr"
for s in "$d/herdr.sock" "$d"/sessions/*/herdr.sock; do [ -S "$s" ] && echo "SOCK $s"; done
for p in herdr "$HOME/.local/bin/herdr" "$HOME/.cargo/bin/herdr" /opt/homebrew/bin/herdr /usr/local/bin/herdr; do
  command -v "$p" >/dev/null 2>&1 && { echo BIN; break; }
done
true`, 10*time.Second)
	if err != nil {
		return "", err
	}
	installed := false
	for _, line := range strings.Split(string(out), "\n") {
		if s, ok := strings.CutPrefix(line, "SOCK "); ok {
			return s, nil
		}
		if line == "BIN" {
			installed = true
		}
	}
	if installed {
		return "", errors.New(errNotRunning + ": herdr is installed but no server is running")
	}
	return "", errors.New(errNotInstalled + ": herdr was not found on this node")
}

// exec runs a POSIX sh script on the node. The script goes over stdin so the
// user's login shell (bash, zsh, fish, ...) never parses it.
func (h *Herdr) exec(script string, timeout time.Duration) ([]byte, error) {
	s, err := h.client.NewSession()
	if err != nil {
		return nil, err
	}
	defer s.Close()
	s.Stdin = strings.NewReader(script)
	var out, stderr bytes.Buffer
	s.Stdout = &out
	s.Stderr = &stderr
	if err := s.Start("sh -s"); err != nil {
		return nil, err
	}
	done := make(chan error, 1)
	go func() { done <- s.Wait() }()
	select {
	case err := <-done:
		var exit *ssh.ExitError
		if err != nil && !errors.As(err, &exit) {
			return nil, err
		}
		return out.Bytes(), nil
	case <-time.After(timeout):
		return nil, fmt.Errorf("remote command timed out: %s", strings.TrimSpace(stderr.String()))
	}
}

// openSocket opens a byte stream to the herdr socket: direct streamlocal
// forwarding when sshd allows it, otherwise a relay process on the node.
func (h *Herdr) openSocket() (io.ReadWriteCloser, error) {
	if c, err := h.client.Dial("unix", h.socket); err == nil {
		return c, nil
	}
	s, err := h.client.NewSession()
	if err != nil {
		return nil, err
	}
	stdin, _ := s.StdinPipe()
	stdout, _ := s.StdoutPipe()
	q := shellQuote(h.socket)
	relay := "socat - UNIX-CONNECT:" + q + " 2>/dev/null || nc -U " + q + " 2>/dev/null || " +
		`python3 -c 'import socket,sys,threading;s=socket.socket(socket.AF_UNIX);s.connect(sys.argv[1]);threading.Thread(target=lambda:[s.sendall(l) for l in iter(lambda:sys.stdin.buffer.readline(),b"")],daemon=True).start();[sys.stdout.buffer.write(b) or sys.stdout.flush() for b in iter(lambda:s.recv(65536),b"")]' ` + q
	if err := s.Start("sh -c " + shellQuote(relay)); err != nil {
		s.Close()
		return nil, err
	}
	return &sessionStream{Reader: stdout, Writer: stdin, s: s}, nil
}

type sessionStream struct {
	io.Reader
	io.Writer
	s *ssh.Session
}

func (s *sessionStream) Close() error { return s.s.Close() }

// Call sends one request and returns its "result" object as JSON.
// Server errors come back as Go errors carrying herdr's code and message.
func (h *Herdr) Call(method, paramsJSON string) (string, error) {
	if paramsJSON == "" {
		paramsJSON = "{}"
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	for attempt := 0; attempt < 2; attempt++ {
		if h.conn == nil {
			c, err := h.openSocket()
			if err != nil {
				return "", err
			}
			h.conn, h.reader = c, bufio.NewReaderSize(c, 1<<20)
		}
		h.nextID++
		id := fmt.Sprintf("td%d", h.nextID)
		req := fmt.Sprintf(`{"id":%q,"method":%q,"params":%s}`+"\n", id, method, paramsJSON)
		if _, err := io.WriteString(h.conn, req); err != nil {
			h.dropConn()
			continue
		}
		for {
			line, err := h.reader.ReadBytes('\n')
			if err != nil {
				h.dropConn()
				if attempt == 0 {
					break
				}
				return "", err
			}
			var resp struct {
				ID     string          `json:"id"`
				Result json.RawMessage `json:"result"`
				Error  *struct {
					Code    string `json:"code"`
					Message string `json:"message"`
				} `json:"error"`
			}
			if json.Unmarshal(line, &resp) != nil || (resp.ID != id && resp.ID != "") {
				continue
			}
			if resp.Error != nil {
				return "", fmt.Errorf("%s: %s", resp.Error.Code, resp.Error.Message)
			}
			return string(resp.Result), nil
		}
	}
	return "", errors.New("herdr connection lost")
}

func (h *Herdr) dropConn() {
	if h.conn != nil {
		h.conn.Close()
	}
	h.conn, h.reader = nil, nil
}

// watch keeps an event subscription open and turns every pushed event into
// OnChange. Events are only invalidation signals; state comes from reads.
func (h *Herdr) watch() {
	for {
		panes := h.agentPanes()
		sub, err := h.openSocket()
		if err == nil {
			err = h.subscribe(sub, panes)
			sub.Close()
		}
		select {
		case <-h.done:
			return
		case <-time.After(2 * time.Second):
		}
	}
}

func (h *Herdr) subscribe(sub io.ReadWriteCloser, panes []string) error {
	subs := []map[string]string{}
	for _, t := range []string{"pane.created", "pane.closed", "pane.exited", "pane.updated", "pane.agent_detected", "pane.moved", "workspace.renamed", "workspace.closed"} {
		subs = append(subs, map[string]string{"type": t})
	}
	for _, p := range panes {
		subs = append(subs, map[string]string{"type": "pane.agent_status_changed", "pane_id": p})
	}
	params, _ := json.Marshal(map[string]any{"subscriptions": subs})
	if _, err := fmt.Fprintf(sub, `{"id":"sub","method":"events.subscribe","params":%s}`+"\n", params); err != nil {
		return err
	}
	r := bufio.NewReaderSize(sub, 1<<20)
	var debounce *time.Timer
	known := strings.Join(panes, ",")
	for {
		line, err := r.ReadBytes('\n')
		if err != nil {
			return err
		}
		if bytes.Contains(line, []byte(`"error"`)) && bytes.Contains(line, []byte(`"id":"sub"`)) {
			return errors.New(string(line))
		}
		if debounce != nil {
			debounce.Stop()
		}
		debounce = time.AfterFunc(150*time.Millisecond, h.listener.OnChange)
		// New or closed agent panes need a new set of status subscriptions.
		if !bytes.Contains(line, []byte("agent_status_changed")) && strings.Join(h.agentPanes(), ",") != known {
			return nil
		}
	}
}

func (h *Herdr) agentPanes() []string {
	out, err := h.Call("agent.list", "{}")
	if err != nil {
		return nil
	}
	var res struct {
		Agents []struct {
			PaneID string `json:"pane_id"`
		} `json:"agents"`
	}
	json.Unmarshal([]byte(out), &res)
	var panes []string
	for _, a := range res.Agents {
		panes = append(panes, a.PaneID)
	}
	sort.Strings(panes)
	return panes
}

func (h *Herdr) shutdown(reason string) {
	h.closeOnce.Do(func() {
		close(h.done)
		h.mu.Lock()
		h.dropConn()
		h.mu.Unlock()
		h.client.Close()
		h.listener.OnClosed(reason)
	})
}

func (h *Herdr) Close() { h.shutdown("") }

func shellQuote(s string) string {
	return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'"
}
