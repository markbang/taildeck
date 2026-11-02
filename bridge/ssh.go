package tsbridge

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/json"
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
// certPath optionally points to an OpenSSH certificate for the key.
func DialSSH(host string, port int, user, password, keyPath, passphrase, certPath string, cols, rows int, listener SSHListener) (*SSHSession, error) {
	e, err := current()
	if err != nil {
		return nil, err
	}
	if listener == nil {
		return nil, errors.New("listener is required")
	}
	client, err := e.dialClient(host, port, user, password, keyPath, passphrase, certPath, func(msg string) {
		listener.OnData([]byte(strings.ReplaceAll(msg, "\n", "\r\n")))
	})
	if err != nil {
		return nil, err
	}
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

// identConn records the start of the server stream to recover its
// "SSH-2.0-..." identification line when the handshake fails.
type identConn struct {
	net.Conn
	mu   sync.Mutex
	head []byte
}

func (c *identConn) Read(p []byte) (int, error) {
	n, err := c.Conn.Read(p)
	c.mu.Lock()
	if len(c.head) < 512 && n > 0 {
		c.head = append(c.head, p[:min(n, 512-len(c.head))]...)
	}
	c.mu.Unlock()
	return n, err
}

func (c *identConn) serverVersion() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	for _, line := range strings.Split(string(c.head), "\n") {
		if strings.HasPrefix(line, "SSH-") {
			return strings.TrimSpace(line)
		}
	}
	return ""
}

// dialClient authenticates an SSH client to host:port over the tailnet. notice
// receives banners, Tailscale SSH check-mode URLs and host key trust messages.
func (e *engine) dialClient(host string, port int, user, password, keyPath, passphrase, certPath string, notice func(string)) (*ssh.Client, error) {
	var auths []ssh.AuthMethod
	if keyPath != "" {
		signers, err := loadSigners(keyPath, passphrase, certPath)
		if err != nil {
			return nil, err
		}
		auths = append(auths, ssh.PublicKeys(signers...))
	}
	if password != "" {
		auths = append(auths, ssh.Password(password))
	}
	auths = append(auths, ssh.KeyboardInteractive(func(name, instruction string, questions []string, echos []bool) ([]string, error) {
		// Tailscale SSH "check" mode sends the re-auth URL as an instruction.
		if msg := strings.TrimSpace(name + "\n" + instruction); msg != "" {
			notice(msg + "\n")
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
	raw, err := e.srv.Dial(ctx, "tcp", addr)
	if err != nil {
		return nil, fmt.Errorf("connect %s: %w", addr, err)
	}
	conn := &identConn{Conn: raw}
	config := &ssh.ClientConfig{
		User:            user,
		Auth:            auths,
		HostKeyCallback: tofuHostKeys(filepath.Join(e.dir, "known_hosts"), notice),
		BannerCallback: func(message string) error {
			notice(message)
			return nil
		},
		Timeout: 20 * time.Second,
	}
	// Tailscale SSH check mode may block auth until the user approves in a browser.
	conn.SetDeadline(time.Now().Add(5 * time.Minute))
	c, chans, reqs, err := ssh.NewClientConn(conn, addr, config)
	if err != nil {
		conn.Close()
		// The server's version string tells OpenSSH, Dropbear and Tailscale
		// SSH apart, which decides where keys have to be authorized.
		if v := conn.serverVersion(); v != "" {
			return nil, fmt.Errorf("%w [server %s]", err, v)
		}
		return nil, err
	}
	conn.SetDeadline(time.Time{})
	return ssh.NewClient(c, chans, reqs), nil
}

// tofuHostKeys trusts a host key on first use and rejects later changes.
func tofuHostKeys(path string, notice func(string)) ssh.HostKeyCallback {
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
		notice(fmt.Sprintf("\x1b[2mTrusted new host key %s %s\x1b[0m\n", key.Type(), ssh.FingerprintSHA256(key)))
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
	signer, err := parseSigner(path, "")
	if err != nil {
		return "", err
	}
	pub := strings.TrimSpace(string(ssh.MarshalAuthorizedKey(signer.PublicKey())))
	if comment != "" {
		pub += " " + comment
	}
	return pub, nil
}

func parseSigner(path, passphrase string) (ssh.Signer, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read key: %w", err)
	}
	return parsePrivateKey(data, passphrase)
}

func parsePrivateKey(data []byte, passphrase string) (ssh.Signer, error) {
	var signer ssh.Signer
	var err error
	if passphrase != "" {
		signer, err = ssh.ParsePrivateKeyWithPassphrase(data, []byte(passphrase))
	} else {
		signer, err = ssh.ParsePrivateKey(data)
	}
	var missing *ssh.PassphraseMissingError
	if errors.As(err, &missing) {
		return nil, errors.New("PASSPHRASE_REQUIRED: this key is encrypted, enter its passphrase")
	}
	if err != nil {
		return nil, fmt.Errorf("parse key: %w", err)
	}
	return signer, nil
}

func parseCert(data []byte, signer ssh.Signer) (*ssh.Certificate, error) {
	pub, _, _, _, err := ssh.ParseAuthorizedKey(data)
	if err != nil {
		return nil, fmt.Errorf("parse certificate: %w", err)
	}
	cert, ok := pub.(*ssh.Certificate)
	if !ok {
		return nil, errors.New("not an OpenSSH certificate (expected *-cert.pub)")
	}
	if cert.CertType != ssh.UserCert {
		return nil, errors.New("certificate is not a user certificate")
	}
	if string(cert.Key.Marshal()) != string(signer.PublicKey().Marshal()) {
		return nil, errors.New("certificate does not match the private key")
	}
	return cert, nil
}

// loadSigners returns the certificate signer first (when present) so servers
// that trust the CA accept it, with the bare key as a fallback.
func loadSigners(keyPath, passphrase, certPath string) ([]ssh.Signer, error) {
	signer, err := parseSigner(keyPath, passphrase)
	if err != nil {
		return nil, err
	}
	if certPath == "" {
		return []ssh.Signer{signer}, nil
	}
	data, err := os.ReadFile(certPath)
	if err != nil {
		return nil, fmt.Errorf("read certificate: %w", err)
	}
	cert, err := parseCert(data, signer)
	if err != nil {
		return nil, err
	}
	certSigner, err := ssh.NewCertSigner(cert, signer)
	if err != nil {
		return nil, err
	}
	return []ssh.Signer{certSigner, signer}, nil
}

// InspectKey validates a private key (and optional certificate) and returns
// JSON {type, fingerprint, publicKey, cert?: {keyId, principals, validBefore}}.
// Errors starting with PASSPHRASE_REQUIRED mean the key needs a passphrase.
func InspectKey(keyData, passphrase, certData string) (string, error) {
	signer, err := parsePrivateKey([]byte(keyData), passphrase)
	if err != nil {
		return "", err
	}
	pub := signer.PublicKey()
	out := map[string]any{
		"type":        pub.Type(),
		"fingerprint": ssh.FingerprintSHA256(pub),
		"publicKey":   strings.TrimSpace(string(ssh.MarshalAuthorizedKey(pub))),
	}
	if strings.TrimSpace(certData) != "" {
		cert, err := parseCert([]byte(certData), signer)
		if err != nil {
			return "", err
		}
		info := map[string]any{"keyId": cert.KeyId, "principals": cert.ValidPrincipals}
		if cert.ValidBefore != ssh.CertTimeInfinity {
			info["validBefore"] = time.Unix(int64(cert.ValidBefore), 0).Format(time.RFC3339)
		}
		out["cert"] = info
	}
	b, err := json.Marshal(out)
	return string(b), err
}
