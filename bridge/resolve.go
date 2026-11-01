package tsbridge

import (
	"context"
	"encoding/json"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"
)

type peerNames struct {
	mu       sync.Mutex
	updated  time.Time
	byName   map[string]string
	prefixes []netip.Prefix // peer addresses and approved subnet routes
}

// resolveHost maps a bare peer name ("nas") or MagicDNS name to its tailnet
// IP so users can type short names regardless of the tailnet's search domain.
func (e *engine) resolveHost(host string) string {
	key := strings.ToLower(strings.TrimSuffix(host, "."))
	if _, err := netip.ParseAddr(strings.Trim(key, "[]")); err == nil {
		return host
	}
	e.names.mu.Lock()
	defer e.names.mu.Unlock()
	e.refreshNamesLocked()
	if ip, ok := e.names.byName[key]; ok {
		return ip
	}
	return host
}

func (e *engine) refreshNamesLocked() {
	if time.Since(e.names.updated) > 10*time.Second || e.names.byName == nil {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		st, err := e.lc.Status(ctx)
		cancel()
		if err == nil {
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
				dns := strings.ToLower(strings.TrimSuffix(p.DNSName, "."))
				if dns != "" {
					m[dns] = ip
					if label, _, ok := strings.Cut(dns, "."); ok {
						m[label] = ip
					}
				}
				if h := strings.ToLower(p.HostName); h != "" {
					if _, exists := m[h]; !exists {
						m[h] = ip
					}
				}
			}
			e.names.byName = m
			e.names.prefixes = prefixes
			e.names.updated = time.Now()
		}
	}
}

func (e *engine) inTailnet(ip netip.Addr) bool {
	ip = ip.Unmap()
	e.names.mu.Lock()
	defer e.names.mu.Unlock()
	e.refreshNamesLocked()
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

// Probe tries TCP connects to the comma-separated ports on host and returns
// a JSON array of the open ones.
func Probe(host, ports string) (string, error) {
	e, err := current()
	if err != nil {
		return "", err
	}
	ip := e.resolveHost(host)
	var (
		mu   sync.Mutex
		open = []int{}
		wg   sync.WaitGroup
		sem  = make(chan struct{}, 16)
	)
	for _, s := range strings.Split(ports, ",") {
		port, err := strconv.Atoi(strings.TrimSpace(s))
		if err != nil || port <= 0 || port > 65535 {
			continue
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			c, err := e.srv.Dial(ctx, "tcp", net.JoinHostPort(ip, strconv.Itoa(port)))
			if err != nil {
				return
			}
			c.Close()
			mu.Lock()
			open = append(open, port)
			mu.Unlock()
		}()
	}
	wg.Wait()
	b, err := json.Marshal(open)
	return string(b), err
}
