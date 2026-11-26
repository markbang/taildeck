package tsbridge

import (
	"context"
	"net"
	"strconv"
	"testing"
	"time"
)

func TestParsePorts(t *testing.T) {
	got := parsePorts(" 22, x, 0, 443 ,70000,80")
	if len(got) != 3 || got[0] != 22 || got[1] != 443 || got[2] != 80 {
		t.Fatalf("unexpected ports %v", got)
	}
}

func TestProbePortsFindsListener(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	open := ln.Addr().(*net.TCPAddr).Port
	closed := findClosedPort(t)
	got := probePorts(context.Background(), time.Second, 200*time.Millisecond, []int{open, closed},
		func(ctx context.Context, port int) (net.Conn, error) {
			var d net.Dialer
			return d.DialContext(ctx, "tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
		})
	if len(got) != 1 || got[0] != open {
		t.Fatalf("want only %d open, got %v", open, got)
	}
}

func TestProbePortsPerPortDeadline(t *testing.T) {
	start := time.Now()
	got := probePorts(context.Background(), 5*time.Second, 60*time.Millisecond, []int{1, 2},
		func(ctx context.Context, _ int) (net.Conn, error) {
			<-ctx.Done()
			return nil, ctx.Err()
		})
	if len(got) != 0 {
		t.Fatalf("want no open ports, got %v", got)
	}
	if elapsed := time.Since(start); elapsed > time.Second {
		t.Fatalf("per-port deadline not applied: %s", elapsed)
	}
}

func TestProbePortsOverallDeadline(t *testing.T) {
	start := time.Now()
	got := probePorts(context.Background(), 80*time.Millisecond, time.Second, []int{1, 2, 3},
		func(ctx context.Context, _ int) (net.Conn, error) {
			time.Sleep(5 * time.Second) // a dial that ignores its context
			return nil, ctx.Err()
		})
	if len(got) != 0 {
		t.Fatalf("want no open ports, got %v", got)
	}
	if elapsed := time.Since(start); elapsed > time.Second {
		t.Fatalf("overall deadline not applied: %s", elapsed)
	}
}

func findClosedPort(t *testing.T) int {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	ln.Close()
	return port
}
