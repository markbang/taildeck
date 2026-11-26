package tsbridge

import (
	"context"
	"encoding/json"
	"net/netip"
	"strings"
	"testing"
	"time"

	"github.com/sagernet/tailscale/ipn/ipnstate"
	"github.com/sagernet/tailscale/tailcfg"
)

type fakePinger func(context.Context, netip.Addr, tailcfg.PingType) (*ipnstate.PingResult, error)

func (f fakePinger) Ping(ctx context.Context, ip netip.Addr, kind tailcfg.PingType) (*ipnstate.PingResult, error) {
	return f(ctx, ip, kind)
}

func TestPingWithoutNode(t *testing.T) {
	_, err := Ping("100.64.0.7")
	if err == nil || !strings.Contains(err.Error(), "not started") {
		t.Fatalf("want a clean not-started error, got %v", err)
	}
}

func TestPingTimesOut(t *testing.T) {
	peer := netip.MustParseAddr("100.64.0.7")
	silent := fakePinger(func(ctx context.Context, _ netip.Addr, _ tailcfg.PingType) (*ipnstate.PingResult, error) {
		<-ctx.Done()
		return nil, ctx.Err()
	})
	ctx, cancel := context.WithTimeout(context.Background(), 80*time.Millisecond)
	defer cancel()
	start := time.Now()
	_, err := pingWith(ctx, silent, peer, 80*time.Millisecond)
	elapsed := time.Since(start)
	if err == nil || !strings.Contains(err.Error(), "timed out") {
		t.Fatalf("want a timeout error, got %v", err)
	}
	if elapsed > time.Second {
		t.Fatalf("ping did not return at its deadline: %s", elapsed)
	}
}

func TestPingCancel(t *testing.T) {
	peer := netip.MustParseAddr("100.64.0.7")
	call := beginPing(peer.String())
	defer endPing(peer.String(), call)
	done := make(chan error, 1)
	go func() {
		_, err := pingWith(call.ctx, fakePinger(func(ctx context.Context, _ netip.Addr, _ tailcfg.PingType) (*ipnstate.PingResult, error) {
			<-ctx.Done()
			return nil, ctx.Err()
		}), peer, pingTimeout)
		done <- err
	}()
	if !CancelPing(" 100.64.0.7 ") {
		t.Fatal("CancelPing did not find the ping in flight")
	}
	select {
	case err := <-done:
		if err == nil || !strings.Contains(err.Error(), "cancelled") {
			t.Fatalf("want a cancelled error, got %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("cancelled ping did not return")
	}
	if CancelPing(peer.String()) {
		t.Fatal("CancelPing found a ping that already returned")
	}
}

func TestPingReplacesPrevious(t *testing.T) {
	peer := netip.MustParseAddr("100.64.0.7")
	first := beginPing(peer.String())
	second := beginPing(peer.String())
	defer endPing(peer.String(), second)
	if first.ctx.Err() == nil {
		t.Fatal("a new ping must cancel the one in flight")
	}
	endPing(peer.String(), first)
	if second.ctx.Err() != nil {
		t.Fatal("endPing of the replaced call cancelled the current one")
	}
}

func TestPingResultJSON(t *testing.T) {
	peer := netip.MustParseAddr("100.64.0.7")
	raw, err := pingWith(context.Background(), fakePinger(func(context.Context, netip.Addr, tailcfg.PingType) (*ipnstate.PingResult, error) {
		return &ipnstate.PingResult{LatencySeconds: 0.0125, Endpoint: "192.0.2.9:41641", PeerRelay: "192.0.2.9:41641:vni:7"}, nil
	}), peer, pingTimeout)
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal([]byte(raw), &got); err != nil {
		t.Fatal(err)
	}
	if got["latencyMs"] != 12.5 || got["endpoint"] != "192.0.2.9:41641" || got["error"] != "" {
		t.Fatalf("unexpected payload %s", raw)
	}
}
