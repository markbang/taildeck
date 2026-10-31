package tsbridge

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/ssh"
	"golang.org/x/crypto/ssh/knownhosts"
)

type SSHListener interface {
	OnData(data []byte)
	OnClosed(reason string)
}

type SSHSession struct {
	client  *ssh.Client
	session *ssh.Session
	stdin   io.WriteCloser
	once    sync.Once
}

// DialSSH opens an interactive PTY shell to host:port over the tailnet.
// With empty password and keyPath it relies on Tailscale SSH ("none" auth).
func DialSSH(host string, port int, user, password, keyPath string, cols, rows int, listener SSHListener) (*SSHSession, error) {
	e, err := current()
	if err != nil {
		return nil, err
	}
	if listener == nil {
		return nil, errors.New("listener is required")
	}
	var auths []ssh.AuthMethod
	if keyPath != "" {
		signer, err := loadSigner(keyPath)
		if err != nil {
			return nil, err
		}
		auths = append(auths, ssh.PublicKeys(signer))
	}
	if password != "" {
		auths = append(auths, ssh.Password(password))
	}
	auths = append(auths, ssh.KeyboardInteractive(func(name, instruction string, questions []string, echos []bool) ([]string, error) {
		// Tailscale SSH "check" mode sends the re-auth URL as an instruction.
		if msg := strings.TrimSpace(name + "\n" + instruction); msg != "" {
			listener.OnData([]byte(strings.ReplaceAll(msg, "\n", "\r\n") + "\r\n"))
		}
		answers := make([]string, len(questions))
		for i := range answers {
			answers[i] = password
		}
		return answers, nil
	}))

	addr := net.JoinHostPort(e.resolveHost(host), fmt.Sprint(port))
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	conn, err := e.srv.Dial(ctx, "tcp", addr)
	if err != nil {
		return nil, fmt.Errorf("connect %s: %w", addr, err)
	}
	config := &ssh.ClientConfig{
		User:            user,
		Auth:            auths,
		HostKeyCallback: tofuHostKeys(filepath.Join(e.dir, "known_hosts"), listener),
		BannerCallback: func(message string) error {
			listener.OnData([]byte(strings.ReplaceAll(message, "\n", "\r\n")))
			return nil
		},
		Timeout:         20 * time.Second,
	}
	// Tailscale SSH check mode may block auth until the user approves in a browser.
	conn.SetDeadline(time.Now().Add(5 * time.Minute))
	c, chans, reqs, err := ssh.NewClientConn(conn, addr, config)
	if err != nil {
		conn.Close()
		return nil, err
	}
	conn.SetDeadline(time.Time{})
	client := ssh.NewClient(c, chans, reqs)
	session, err := client.NewSession()
	if err != nil {
		client.Close()
		return nil, err
	}
	modes := ssh.TerminalModes{ssh.ECHO: 1, ssh.TTY_OP_ISPEED: 115200, ssh.TTY_OP_OSPEED: 115200}
	if err := session.RequestPty("xterm-256color", rows, cols, modes); err != nil {
		client.Close()
		return nil, err
	}
	stdin, err := session.StdinPipe()
	if err != nil {
		client.Close()
		return nil, err
	}
	stdout, err := session.StdoutPipe()
	if err != nil {
		client.Close()
		return nil, err
	}
	stderr, err := session.StderrPipe()
	if err != nil {
		client.Close()
		return nil, err
	}
	if err := session.Shell(); err != nil {
		client.Close()
		return nil, err
	}
	s := &SSHSession{client: client, session: session, stdin: stdin}
	var wg sync.WaitGroup
	forward := func(r io.Reader) {
		defer wg.Done()
		buf := make([]byte, 32*1024)
		for {
			n, err := r.Read(buf)
			if n > 0 {
				listener.OnData(append([]byte(nil), buf[:n]...))
			}
			if err != nil {
				return
			}
		}
	}
	wg.Add(2)
	go forward(stdout)
	go forward(stderr)
	go func() {
		err := session.Wait()
		wg.Wait()
		s.Close()
		reason := ""
		if err != nil {
			reason = err.Error()
		}
		listener.OnClosed(reason)
	}()
	return s, nil
}

func (s *SSHSession) Write(data []byte) error {
	_, err := s.stdin.Write(data)
	return err
}

func (s *SSHSession) Resize(cols, rows int) error {
	return s.session.WindowChange(rows, cols)
}

func (s *SSHSession) Close() {
	s.once.Do(func() {
		s.session.Close()
		s.client.Close()
	})
}

// tofuHostKeys trusts a host key on first use and rejects later changes.
func tofuHostKeys(path string, listener SSHListener) ssh.HostKeyCallback {
	return func(hostname string, remote net.Addr, key ssh.PublicKey) error {
		if f, err := os.OpenFile(path, os.O_CREATE|os.O_RDONLY, 0o600); err == nil {
			f.Close()
		}
		check, err := knownhosts.New(path)
		if err != nil {
			return err
		}
		err = check(hostname, remote, key)
		var keyErr *knownhosts.KeyError
		if err == nil || !errors.As(err, &keyErr) {
			return err
		}
		if len(keyErr.Want) > 0 {
			return fmt.Errorf("host key for %s changed (%s); remove it in settings if this is expected", hostname, ssh.FingerprintSHA256(key))
		}
		f, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0o600)
		if err != nil {
			return err
		}
		defer f.Close()
		if _, err := f.WriteString(knownhosts.Line([]string{knownhosts.Normalize(hostname)}, key) + "\n"); err != nil {
			return err
		}
		listener.OnData([]byte(fmt.Sprintf("\x1b[2mTrusted new host key %s %s\x1b[0m\r\n", key.Type(), ssh.FingerprintSHA256(key))))
		return nil
	}
}

// ForgetHostKeys clears the trusted host key store.
func ForgetHostKeys(dir string) error {
	err := os.Remove(filepath.Join(dir, "known_hosts"))
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	return err
}

// EnsureKey creates an ed25519 key at path if missing and returns its
// public key in authorized_keys format.
func EnsureKey(path, comment string) (string, error) {
	if _, err := os.Stat(path); errors.Is(err, os.ErrNotExist) {
		_, priv, err := ed25519.GenerateKey(rand.Reader)
		if err != nil {
			return "", err
		}
		block, err := ssh.MarshalPrivateKey(priv, comment)
		if err != nil {
			return "", err
		}
		if err := os.WriteFile(path, pem.EncodeToMemory(block), 0o600); err != nil {
			return "", err
		}
	}
	signer, err := loadSigner(path)
	if err != nil {
		return "", err
	}
	pub := strings.TrimSpace(string(ssh.MarshalAuthorizedKey(signer.PublicKey())))
	if comment != "" {
		pub += " " + comment
	}
	return pub, nil
}

func loadSigner(path string) (ssh.Signer, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read key: %w", err)
	}
	signer, err := ssh.ParsePrivateKey(data)
	if err != nil {
		return nil, fmt.Errorf("parse key: %w", err)
	}
	return signer, nil
}
