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
	mu      sync.Mutex
	updated time.Time
	byName  map[string]string
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
	if time.Since(e.names.updated) > 10*time.Second || e.names.byName == nil {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		st, err := e.lc.Status(ctx)
		cancel()
		if err == nil {
			m := map[string]string{}
			for _, p := range st.Peer {
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
			e.names.updated = time.Now()
		}
	}
	if ip, ok := e.names.byName[key]; ok {
		return ip
	}
	return host
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
