package tsbridge

import (
	"bufio"
	"context"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/sagernet/tailscale/ipn/ipnstate"
)

type ipnStatusPeer = ipnstate.PeerStatus

// httpProxy is a loopback-only HTTP/CONNECT proxy that sends every request
// into the tailnet. WebView is pointed at it via androidx.webkit ProxyController.
type httpProxy struct {
	ln        net.Listener
	port      int
	e         *engine
	transport *http.Transport
}

func startProxy(e *engine) (*httpProxy, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	p := &httpProxy{ln: ln, port: ln.Addr().(*net.TCPAddr).Port, e: e}
	p.transport = &http.Transport{
		DialContext: func(ctx context.Context, _, addr string) (net.Conn, error) {
			return p.dial(ctx, addr)
		},
		MaxIdleConnsPerHost: 4,
		IdleConnTimeout:     60 * time.Second,
	}
	go p.serve()
	return p, nil
}

func (p *httpProxy) close() {
	p.ln.Close()
	p.transport.CloseIdleConnections()
}

func (p *httpProxy) serve() {
	for {
		c, err := p.ln.Accept()
		if err != nil {
			return
		}
		go p.handle(c)
	}
}

func (p *httpProxy) dial(ctx context.Context, hostport string) (net.Conn, error) {
	ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	return p.e.srv.Dial(ctx, "tcp", p.e.resolveHostPort(hostport))
}

func (p *httpProxy) handle(client net.Conn) {
	defer client.Close()
	br := bufio.NewReader(client)
	req, err := http.ReadRequest(br)
	if err != nil {
		return
	}
	if req.Method == http.MethodConnect {
		upstream, err := p.dial(req.Context(), req.Host)
		if err != nil {
			io.WriteString(client, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
			return
		}
		defer upstream.Close()
		io.WriteString(client, "HTTP/1.1 200 Connection Established\r\n\r\n")
		pipe(client, br, upstream)
		return
	}
	// Plain HTTP: a proxied keep-alive connection may carry requests for
	// different hosts, so each request is round-tripped individually.
	for {
		req.RequestURI = ""
		req.Header.Del("Proxy-Connection")
		req.Header.Del("Proxy-Authorization")
		if req.URL.Host == "" {
			req.URL.Host = req.Host
		}
		if req.URL.Scheme == "" {
			req.URL.Scheme = "http"
		}
		resp, err := p.transport.RoundTrip(req)
		if err != nil {
			writeError(client, err)
			return
		}
		if resp.StatusCode == http.StatusSwitchingProtocols {
			upstream, ok := resp.Body.(io.ReadWriteCloser)
			if !ok {
				resp.Body.Close()
				return
			}
			resp.Body = nil
			if err := resp.Write(client); err != nil {
				upstream.Close()
				return
			}
			go io.Copy(upstream, br)
			io.Copy(client, upstream)
			upstream.Close()
			return
		}
		err = resp.Write(client)
		resp.Body.Close()
		if err != nil || req.Close || resp.Close {
			return
		}
		req, err = http.ReadRequest(br)
		if err != nil {
			return
		}
	}
}

func writeError(w io.Writer, err error) {
	body := "Tailscale proxy: " + err.Error()
	resp := &http.Response{
		StatusCode:    http.StatusBadGateway,
		ProtoMajor:    1,
		ProtoMinor:    1,
		Header:        http.Header{"Content-Type": {"text/plain; charset=utf-8"}},
		ContentLength: int64(len(body)),
		Body:          io.NopCloser(strings.NewReader(body)),
		Close:         true,
	}
	resp.Write(w)
}

func pipe(client net.Conn, clientReader io.Reader, upstream net.Conn) {
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		io.Copy(upstream, clientReader)
		if c, ok := upstream.(interface{ CloseWrite() error }); ok {
			c.CloseWrite()
		}
	}()
	io.Copy(client, upstream)
	client.Close()
	upstream.Close()
	wg.Wait()
}

type ringLog struct {
	mu    sync.Mutex
	lines []string
}

var logs = &ringLog{}

func (r *ringLog) add(s string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.lines = append(r.lines, time.Now().Format("15:04:05 ")+strings.TrimRight(s, "\n"))
	if len(r.lines) > 500 {
		r.lines = r.lines[len(r.lines)-500:]
	}
}

func (r *ringLog) String() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return strings.Join(r.lines, "\n")
}
