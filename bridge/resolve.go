package tsbridge

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/netip"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/sagernet/tailscale/ipn"
	"github.com/sagernet/tailscale/ipn/ipnstate"
)

type peerNames struct {
	mu       sync.Mutex
	updated  time.Time
	forced   time.Time // last on-demand re-read, so a miss cannot hammer Status
	byName   map[string]string
	prefixes []netip.Prefix // peer addresses and approved subnet routes
}

// normalizeName compares peer names and MagicDNS names without their case,
// trailing dot or IPv6 brackets.
func normalizeName(host string) string {
	return strings.ToLower(strings.TrimSuffix(strings.Trim(host, "[]"), "."))
}

// resolveHost maps a bare peer name ("nas") or MagicDNS name to its tailnet
// IP so users can type short names regardless of the tailnet's search domain.
func (e *engine) resolveHost(host string) string {
	if ip, ok := e.peerIP(host); ok {
		return ip
	}
	return host
}

// peerIP reads the status peer table. A miss forces one re-read: right after
// the node starts the table can still be empty.
func (e *engine) peerIP(host string) (string, bool) {
	key := normalizeName(host)
	if key == "" {
		return "", false
	}
	e.names.mu.Lock()
	defer e.names.mu.Unlock()
	e.refreshNamesLocked(false)
	if ip, ok := e.names.byName[key]; ok {
		return ip, true
	}
	if time.Since(e.names.forced) > 5*time.Second {
		e.names.forced = time.Now()
		e.refreshNamesLocked(true)
	}
	ip, ok := e.names.byName[key]
	return ip, ok
}

// dialTarget returns the address an SSH client dials for host: a peer IP from
// the status table, else the name through the node's own MagicDNS, never the
// system resolver.
func (e *engine) dialTarget(ctx context.Context, host string, port int) (string, error) {
	if ip, err := netip.ParseAddr(normalizeName(host)); err == nil {
		return net.JoinHostPort(ip.String(), strconv.Itoa(port)), nil
	}
	if ip, ok := e.peerIP(host); ok {
		return net.JoinHostPort(ip, strconv.Itoa(port)), nil
	}
	if !e.nodeRunning(ctx) {
		return "", fmt.Errorf("cannot reach %q: the tailnet node is not running", host)
	}
	return net.JoinHostPort(host, strconv.Itoa(port)), nil
}

// nodeRunning reports whether the node reached Running; only then does tsnet
// answer MagicDNS names itself.
func (e *engine) nodeRunning(ctx context.Context) bool {
	st, err := e.lc.Status(ctx)
	return err == nil && st.BackendState == ipn.Running.String()
}

func (e *engine) refreshNamesLocked(force bool) {
	if !force && e.names.byName != nil && time.Since(e.names.updated) <= 10*time.Second {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	st, err := e.lc.Status(ctx)
	cancel()
	if err != nil {
		return
	}
	e.names.byName, e.names.prefixes = peerTable(st)
	e.names.updated = time.Now()
}

// peerTable maps every peer's host name and MagicDNS name (also its first
// label) to its tailnet IP, and collects the routes it advertises.
func peerTable(st *ipnstate.Status) (map[string]string, []netip.Prefix) {
	m := map[string]string{}
	var prefixes []netip.Prefix
	for _, p := range st.Peer {
		if p.AllowedIPs != nil {
			for _, pfx := range p.AllowedIPs.All() {
				// Skip exit-node default routes; only tailnet-specific space counts.
				if pfx.Bits() > 0 {
					prefixes = append(prefixes, pfx)
				}
			}
		}
		if len(p.TailscaleIPs) == 0 {
			continue
		}
		ip := p.TailscaleIPs[0].String()
		dns := normalizeName(p.DNSName)
		if dns != "" {
			m[dns] = ip
			if label, _, ok := strings.Cut(dns, "."); ok {
				m[label] = ip
			}
		}
		if h := normalizeName(p.HostName); h != "" {
			if _, exists := m[h]; !exists {
				m[h] = ip
			}
		}
	}
	return m, prefixes
}

func (e *engine) inTailnet(ip netip.Addr) bool {
	ip = ip.Unmap()
	e.names.mu.Lock()
	defer e.names.mu.Unlock()
	e.refreshNamesLocked(false)
	for _, p := range e.names.prefixes {
		if p.Contains(ip) {
			return true
		}
	}
	return false
}

// dialAny sends tailnet destinations (peer names, MagicDNS names, peer IPs
// and subnet routes) through the embedded node and everything else straight
// to the physical network, so pages can still load public CDNs and APIs.
func (e *engine) dialAny(ctx context.Context, hostport string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(hostport)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	resolved := e.resolveHost(host)
	if ip, err := netip.ParseAddr(strings.Trim(resolved, "[]")); err == nil {
		if e.inTailnet(ip) {
			return e.srv.Dial(ctx, "tcp", net.JoinHostPort(ip.String(), port))
		}
		var d net.Dialer
		return d.DialContext(ctx, "tcp", net.JoinHostPort(ip.String(), port))
	}
	addrs, err := platformLookup(e.platform, host)
	if err != nil {
		return nil, err
	}
	var lastErr error
	for _, ip := range addrs {
		var c net.Conn
		if e.inTailnet(ip) {
			c, lastErr = e.srv.Dial(ctx, "tcp", net.JoinHostPort(ip.String(), port))
		} else {
			var d net.Dialer
			c, lastErr = d.DialContext(ctx, "tcp", net.JoinHostPort(ip.String(), port))
		}
		if lastErr == nil {
			return c, nil
		}
	}
	return nil, lastErr
}

func (e *engine) resolveHostPort(hostport string) string {
	host, port, err := net.SplitHostPort(hostport)
	if err != nil {
		return hostport
	}
	return net.JoinHostPort(e.resolveHost(host), port)
}

// Probe bounds: a refused or filtered port answers fast, an offline peer does
// not, so every dial gets its own deadline and the whole scan has a cap.
const (
	probeDialTimeout = time.Second
	probeTimeout     = 8 * time.Second
	probeParallel    = 16
)

// Probe tries TCP connects to the comma-separated ports on host and returns a
// JSON array of the open ones.
func Probe(host, ports string) (string, error) {
	e, err := current()
	if err != nil {
		return "", err
	}
	ip := e.resolveHost(host)
	list := parsePorts(ports)
	open := probePorts(context.Background(), probeTimeout, probeDialTimeout, list,
		func(ctx context.Context, port int) (net.Conn, error) {
			return e.srv.Dial(ctx, "tcp", net.JoinHostPort(ip, strconv.Itoa(port)))
		})
	b, err := json.Marshal(open)
	return string(b), err
}

func parsePorts(ports string) []int {
	var out []int
	for _, s := range strings.Split(ports, ",") {
		port, err := strconv.Atoi(strings.TrimSpace(s))
		if err != nil || port <= 0 || port > 65535 {
			continue
		}
		out = append(out, port)
	}
	return out
}

// probePorts dials ports in parallel and returns the open ones, sorted. It
// returns by overall even when a dial ignores its deadline.
func probePorts(ctx context.Context, overall, perPort time.Duration, ports []int, dial func(context.Context, int) (net.Conn, error)) []int {
	ctx, cancel := context.WithTimeout(ctx, overall)
	defer cancel()
	results := make(chan int, len(ports))
	sem := make(chan struct{}, probeParallel)
	var wg sync.WaitGroup
	for _, port := range ports {
		wg.Add(1)
		go func() {
			defer wg.Done()
			select {
			case sem <- struct{}{}:
			case <-ctx.Done():
				return
			}
			defer func() { <-sem }()
			dctx, dcancel := context.WithTimeout(ctx, perPort)
			defer dcancel()
			conn, err := dial(dctx, port)
			if err != nil {
				return
			}
			conn.Close()
			select {
			case results <- port:
			case <-ctx.Done():
			}
		}()
	}
	done := make(chan struct{})
	go func() {
		wg.Wait()
		close(done)
	}()
	open := []int{}
	select {
	case <-done:
		close(results)
		for port := range results {
			open = append(open, port)
		}
	case <-ctx.Done():
	}
	sort.Ints(open)
	return open
}
// hand edit
