package tsbridge

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"html"
	"io"
	"net"
	"net/http"
	"net/http/httputil"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/sagernet/tailscale/ipn/ipnstate"
)

type ipnStatusPeer = ipnstate.PeerStatus

// gateway is a loopback reverse proxy that lets any browser on the device
// reach tailnet services. The target is encoded in the Host header:
//
//	http://<node>-<port>[s].localhost:<gw>/path
//
// Browsers resolve *.localhost to loopback, and each service gets its own
// host, so cookies and storage stay isolated per service. A trailing "s"
// means the upstream speaks TLS; the gateway skips verification because
// tailnet services commonly use self-signed certificates.
type gateway struct {
	srv  *http.Server
	ln   net.Listener
	port int
	e    *engine

	transport *http.Transport
	forward   *httputil.ReverseProxy
	proxies   sync.Map // label -> *httputil.ReverseProxy
}

type target struct {
	host  string
	port  int
	https bool
}

func (t target) scheme() string {
	if t.https {
		return "https"
	}
	return "http"
}

func (t target) hostPort() string {
	return net.JoinHostPort(t.host, strconv.Itoa(t.port))
}

// origin is what the upstream expects to see in Host/Origin/Referer.
func (t target) origin() string {
	if (t.https && t.port == 443) || (!t.https && t.port == 80) {
		return t.scheme() + "://" + bracket(t.host)
	}
	return t.scheme() + "://" + t.hostPort()
}

func (t target) label() string {
	host := t.host
	if ip, err := netip.ParseAddr(host); err == nil && ip.Is4() {
		host = strings.ReplaceAll(host, ".", "-")
	}
	s := fmt.Sprintf("%s-%d", strings.ToLower(host), t.port)
	if t.https {
		s += "s"
	}
	return s
}

func bracket(h string) string {
	if strings.Contains(h, ":") {
		return "[" + h + "]"
	}
	return h
}

func parseLabel(hostHeader string) (target, bool) {
	h := strings.ToLower(hostHeader)
	if hh, _, err := net.SplitHostPort(h); err == nil {
		h = hh
	}
	label, ok := strings.CutSuffix(h, ".localhost")
	if !ok || strings.Contains(label, ".") {
		return target{}, false
	}
	i := strings.LastIndexByte(label, '-')
	if i <= 0 {
		return target{}, false
	}
	host, portStr := label[:i], label[i+1:]
	t := target{host: host}
	if s, ok := strings.CutSuffix(portStr, "s"); ok {
		t.https = true
		portStr = s
	}
	port, err := strconv.Atoi(portStr)
	if err != nil || port <= 0 || port > 65535 {
		return target{}, false
	}
	t.port = port
	if dotted := strings.ReplaceAll(host, "-", "."); strings.Count(dotted, ".") == 3 {
		if ip, err := netip.ParseAddr(dotted); err == nil && ip.Is4() {
			t.host = dotted
		}
	}
	return t, true
}

func startGateway(e *engine, preferredPort int) (*gateway, error) {
	var ln net.Listener
	var err error
	if preferredPort > 0 {
		ln, err = net.Listen("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(preferredPort)))
	}
	if ln == nil {
		ln, err = net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			return nil, err
		}
	}
	g := &gateway{ln: ln, port: ln.Addr().(*net.TCPAddr).Port, e: e}
	g.transport = &http.Transport{
		DialContext: func(ctx context.Context, _, addr string) (net.Conn, error) {
			return e.dialAny(ctx, addr)
		},
		TLSClientConfig:       &tls.Config{InsecureSkipVerify: true},
		ForceAttemptHTTP2:     true,
		DisableCompression:    true,
		MaxIdleConnsPerHost:   8,
		IdleConnTimeout:       90 * time.Second,
		TLSHandshakeTimeout:   15 * time.Second,
		ResponseHeaderTimeout: 2 * time.Minute,
	}
	g.forward = &httputil.ReverseProxy{
		Rewrite: func(pr *httputil.ProxyRequest) {
			pr.Out.URL = pr.In.URL
			pr.Out.Host = pr.In.Host
			pr.Out.Header.Del("Proxy-Connection")
			pr.Out.Header.Del("Proxy-Authorization")
		},
		Transport:     g.transport,
		FlushInterval: -1,
		ErrorHandler:  proxyError,
	}
	g.srv = &http.Server{Handler: g, ReadHeaderTimeout: 30 * time.Second}
	go g.srv.Serve(ln)
	return g, nil
}

func (g *gateway) close() {
	g.srv.Close()
	g.transport.CloseIdleConnections()
}

func (g *gateway) localOrigin(t target) string {
	return fmt.Sprintf("http://%s.localhost:%d", t.label(), g.port)
}

func (g *gateway) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	// The same port doubles as a forward proxy for the in-app WebView, which
	// can then load http://nas:5000 directly.
	if r.Method == http.MethodConnect {
		g.tunnel(w, r)
		return
	}
	if r.URL.IsAbs() {
		g.forward.ServeHTTP(w, r)
		return
	}
	t, ok := parseLabel(r.Host)
	if !ok {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.WriteHeader(http.StatusNotFound)
		fmt.Fprintf(w, "Taildeck gateway\n\nOpen http://<node>-<port>.localhost:%d/ (append \"s\" to the port for HTTPS upstreams).\n", g.port)
		return
	}
	p, ok := g.proxies.Load(t.label())
	if !ok {
		p, _ = g.proxies.LoadOrStore(t.label(), g.newProxy(t))
	}
	p.(*httputil.ReverseProxy).ServeHTTP(w, r)
}

func (g *gateway) newProxy(t target) *httputil.ReverseProxy {
	local := g.localOrigin(t)
	remote := t.origin()
	upstream := &url.URL{Scheme: t.scheme(), Host: t.hostPort()}
	return &httputil.ReverseProxy{
		Rewrite: func(pr *httputil.ProxyRequest) {
			pr.SetURL(upstream)
			pr.Out.Host = strings.TrimPrefix(strings.TrimPrefix(remote, "https://"), "http://")
			// Apps that check Origin/Referer against Host (CSRF guards,
			// WebSocket origin checks) must see their own origin.
			if o := pr.Out.Header.Get("Origin"); o != "" && strings.EqualFold(o, local) {
				pr.Out.Header.Set("Origin", remote)
			}
			if ref := pr.Out.Header.Get("Referer"); ref != "" {
				pr.Out.Header.Set("Referer", replacePrefixFold(ref, local, remote))
			}
		},
		Transport:     g.transport,
		FlushInterval: -1,
		ModifyResponse: func(resp *http.Response) error {
			for _, h := range []string{"Location", "Content-Location", "Refresh"} {
				if v := resp.Header.Get(h); v != "" {
					resp.Header.Set(h, g.rewriteAbsolute(v, t))
				}
			}
			if cookies := resp.Header.Values("Set-Cookie"); len(cookies) > 0 {
				resp.Header.Del("Set-Cookie")
				for _, c := range cookies {
					resp.Header.Add("Set-Cookie", stripCookieDomain(c))
				}
			}
			return nil
		},
		ErrorHandler: proxyError,
	}
}

func proxyError(w http.ResponseWriter, r *http.Request, err error) {
	if errors.Is(err, context.Canceled) {
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(http.StatusBadGateway)
	fmt.Fprintf(w, `<!doctype html><meta name="viewport" content="width=device-width"><body style="font-family:sans-serif;padding:24px"><h3>%s</h3><p>%s</p></body>`,
		html.EscapeString(r.Host), html.EscapeString(err.Error()))
}

func (g *gateway) tunnel(w http.ResponseWriter, r *http.Request) {
	upstream, err := g.e.dialAny(r.Context(), r.Host)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	hj, ok := w.(http.Hijacker)
	if !ok {
		upstream.Close()
		http.Error(w, "hijack unsupported", http.StatusInternalServerError)
		return
	}
	client, buf, err := hj.Hijack()
	if err != nil {
		upstream.Close()
		return
	}
	io.WriteString(client, "HTTP/1.1 200 Connection Established\r\n\r\n")
	done := make(chan struct{})
	go func() {
		// Bytes the client sent right after the CONNECT may already be buffered.
		io.Copy(upstream, buf)
		if c, ok := upstream.(interface{ CloseWrite() error }); ok {
			c.CloseWrite()
		}
		close(done)
	}()
	io.Copy(client, upstream)
	client.Close()
	upstream.Close()
	<-done
}

// rewriteAbsolute maps absolute redirects back onto the gateway, including
// redirects to another port or scheme on the same node.
func (g *gateway) rewriteAbsolute(v string, t target) string {
	prefix := ""
	raw := v
	if i := strings.Index(strings.ToLower(v), "url="); i >= 0 && !strings.Contains(v[:i], "://") {
		prefix, raw = v[:i+4], v[i+4:]
	}
	u, err := url.Parse(raw)
	if err != nil || !u.IsAbs() || (u.Scheme != "http" && u.Scheme != "https") {
		return v
	}
	host := strings.ToLower(u.Hostname())
	name := strings.ToLower(t.host)
	same := host == name || strings.HasPrefix(host, name+".") || host == g.e.resolveHost(name)
	if !same {
		return v
	}
	nt := target{host: t.host, https: u.Scheme == "https"}
	if p := u.Port(); p != "" {
		nt.port, _ = strconv.Atoi(p)
	} else if nt.https {
		nt.port = 443
	} else {
		nt.port = 80
	}
	u.Scheme = "http"
	u.Host = fmt.Sprintf("%s.localhost:%d", nt.label(), g.port)
	return prefix + u.String()
}

func replacePrefixFold(s, prefix, repl string) string {
	if len(s) >= len(prefix) && strings.EqualFold(s[:len(prefix)], prefix) {
		return repl + s[len(prefix):]
	}
	return s
}

func stripCookieDomain(c string) string {
	parts := strings.Split(c, ";")
	out := parts[:1]
	for _, p := range parts[1:] {
		if strings.HasPrefix(strings.ToLower(strings.TrimSpace(p)), "domain=") {
			continue
		}
		out = append(out, p)
	}
	return strings.Join(out, ";")
}

// LocalURL converts a tailnet service URL (http://nas:5000/x) to its gateway
// URL (http://nas-5000.localhost:<gw>/x).
func LocalURL(raw string) (string, error) {
	e, err := current()
	if err != nil {
		return "", err
	}
	u, err := url.Parse(raw)
	if err != nil {
		return "", err
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return "", fmt.Errorf("unsupported scheme %q", u.Scheme)
	}
	t := target{host: u.Hostname(), https: u.Scheme == "https"}
	if t.host == "" {
		return "", errors.New("missing host")
	}
	if strings.Contains(t.host, ".") {
		if ip, err := netip.ParseAddr(t.host); err != nil || !ip.Is4() {
			// MagicDNS FQDN: the first label is the peer name.
			t.host, _, _ = strings.Cut(t.host, ".")
		}
	} else if strings.Contains(t.host, ":") {
		return "", errors.New("IPv6 targets are not supported; use the node name")
	}
	if p := u.Port(); p != "" {
		t.port, _ = strconv.Atoi(p)
	} else if t.https {
		t.port = 443
	} else {
		t.port = 80
	}
	u.Scheme = "http"
	u.Host = fmt.Sprintf("%s.localhost:%d", t.label(), e.gw.port)
	return u.String(), nil
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
