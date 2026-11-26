package tsbridge

import (
	"context"
	"encoding/json"
	"fmt"
	"net/netip"
	"strings"
	"sync"
	"time"

	"github.com/sagernet/tailscale/ipn/ipnstate"
	"github.com/sagernet/tailscale/tailcfg"
)

// pingTimeout bounds one disco ping. Ten seconds was long enough for Android to
// declare the app unresponsive when the peer was offline.
const pingTimeout = 5 * time.Second

// pinger is the part of local.Client the ping path uses, so tests can drive
// timeouts and cancellation without a control server.
type pinger interface {
	Ping(ctx context.Context, ip netip.Addr, pingType tailcfg.PingType) (*ipnstate.PingResult, error)
}

type pingCall struct {
	ctx    context.Context
	cancel context.CancelFunc
}

var (
	pingMu    sync.Mutex
	pingCalls = map[string]*pingCall{}
)

// Ping sends a disco ping to ip and returns JSON {latencyMs, endpoint, derp,
// peerRelay, error}. A ping already in flight to the same peer is cancelled
// first, so a retry does not wait for the previous attempt.
func Ping(ip string) (string, error) {
	e, err := current()
	if err != nil {
		return "", err
	}
	addr, err := netip.ParseAddr(strings.TrimSpace(ip))
	if err != nil {
		return "", fmt.Errorf("ping %q: not an IP address", ip)
	}
	call := beginPing(addr.String())
	defer endPing(addr.String(), call)
	return pingWith(call.ctx, e.lc, addr, pingTimeout)
}

// CancelPing stops the ping in flight to ip and reports whether one was
// running. It is safe to call when the page that started the ping is gone.
func CancelPing(ip string) bool {
	key := strings.TrimSpace(ip)
	if addr, err := netip.ParseAddr(key); err == nil {
		key = addr.String()
	}
	pingMu.Lock()
	call := pingCalls[key]
	delete(pingCalls, key)
	pingMu.Unlock()
	if call == nil {
		return false
	}
	call.cancel()
	return true
}

func beginPing(key string) *pingCall {
	ctx, cancel := context.WithTimeout(context.Background(), pingTimeout)
	call := &pingCall{ctx: ctx, cancel: cancel}
	pingMu.Lock()
	prev := pingCalls[key]
	pingCalls[key] = call
	pingMu.Unlock()
	if prev != nil {
		prev.cancel()
	}
	return call
}

func endPing(key string, call *pingCall) {
	pingMu.Lock()
	if pingCalls[key] == call {
		delete(pingCalls, key)
	}
	pingMu.Unlock()
	call.cancel()
}

func pingWith(ctx context.Context, p pinger, addr netip.Addr, limit time.Duration) (string, error) {
	res, err := p.Ping(ctx, addr, tailcfg.PingDisco)
	if err != nil {
		switch ctx.Err() {
		case context.DeadlineExceeded:
			return "", fmt.Errorf("ping %s timed out after %s: the peer did not answer", addr, limit)
		case context.Canceled:
			return "", fmt.Errorf("ping %s cancelled", addr)
		}
		return "", fmt.Errorf("ping %s: %w", addr, err)
	}
	b, err := json.Marshal(map[string]any{
		"latencyMs": res.LatencySeconds * 1000,
		"endpoint":  res.Endpoint,
		"derp":      res.DERPRegionCode,
		"peerRelay": res.PeerRelay,
		"error":     res.Err,
	})
	return string(b), err
}
