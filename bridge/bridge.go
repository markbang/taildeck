// Package tsbridge embeds a userspace Tailscale node (sagernet/tailscale tsnet)
// for the Android app via gomobile. It never touches the system VPN slot.
package tsbridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"sort"
	"strings"
	"sync"
	"time"

	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/tailscale/client/local"
	"github.com/sagernet/tailscale/ipn"
	"github.com/sagernet/tailscale/net/netmon"
	"github.com/sagernet/tailscale/tailcfg"
	"github.com/sagernet/tailscale/tsnet"
)

// Platform is implemented on the Kotlin side. Android 11+ blocks Go's
// net.Interfaces and has no /etc/resolv.conf, so both come from Java APIs.
type Platform interface {
	// Interfaces returns a JSON array of {name,index,mtu,up,loopback,addrs:["ip/bits"]}.
	Interfaces() string
	// DefaultInterface returns the name of the active network's interface.
	DefaultInterface() string
	// Lookup returns comma-separated IP addresses for host.
	Lookup(host string) string
}

type StateListener interface {
	OnState(state string, authURL string)
}

type engine struct {
	srv      *tsnet.Server
	lc       *local.Client
	cancel   context.CancelFunc
	gw       *gateway
	platform Platform
	dir      string
	names    peerNames
}

var (
	mu  sync.Mutex
	cur *engine
)

// Start brings up the node. dir must be app-private storage.
// controlURL and authKey may be empty; gatewayPort 0 picks a free port.
func Start(dir, hostname, controlURL, authKey string, gatewayPort int, platform Platform, listener StateListener) error {
	mu.Lock()
	defer mu.Unlock()
	if cur != nil {
		return nil
	}
	if platform == nil {
		return errors.New("platform is required")
	}
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		return parseInterfaces(platform.Interfaces())
	})
	srv := &tsnet.Server{
		Dir:        dir,
		Hostname:   hostname,
		ControlURL: controlURL,
		AuthKey:    authKey,
		Logf:       func(format string, args ...any) { logs.add(fmt.Sprintf(format, args...)) },
		UserLogf:   func(format string, args ...any) { logs.add(fmt.Sprintf(format, args...)) },
		Dialer:     &directDialer{platform: platform},
		LookupHook: func(ctx context.Context, host string) ([]netip.Addr, error) {
			return platformLookup(platform, host)
		},
		DefaultRouteFunc: func() (netmon.DefaultRouteDetails, error) {
			name := platform.DefaultInterface()
			if name == "" {
				return netmon.DefaultRouteDetails{}, nil
			}
			details := netmon.DefaultRouteDetails{InterfaceName: name}
			if ifs, err := parseInterfaces(platform.Interfaces()); err == nil {
				for _, it := range ifs {
					if it.Name == name {
						details.InterfaceIndex = it.Index
					}
				}
			}
			return details, nil
		},
	}
	if err := srv.Start(); err != nil {
		srv.Close()
		return fmt.Errorf("start tailscale: %w", err)
	}
	lc, err := srv.LocalClient()
	if err != nil {
		srv.Close()
		return fmt.Errorf("local client: %w", err)
	}
	e := &engine{srv: srv, lc: lc, platform: platform, dir: dir}
	gw, err := startGateway(e, gatewayPort)
	if err != nil {
		srv.Close()
		return fmt.Errorf("start gateway: %w", err)
	}
	e.gw = gw
	ctx, cancel := context.WithCancel(context.Background())
	e.cancel = cancel
	go e.watch(ctx, listener)
	cur = e
	return nil
}

func Stop() {
	mu.Lock()
	e := cur
	cur = nil
	mu.Unlock()
	if e == nil {
		return
	}
	e.cancel()
	e.gw.close()
	e.srv.Close()
}

// Logout deauthorizes this node and stops it; call Start again to log in.
func Logout() error {
	e, err := current()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	err = e.lc.Logout(ctx)
	Stop()
	return err
}

// NetworkChanged tells the engine to re-read interfaces and rebind sockets.
func NetworkChanged() {
	e, err := current()
	if err != nil {
		return
	}
	if m, ok := e.srv.Sys().NetMon.GetOK(); ok {
		m.InjectEvent()
	}
}

func GatewayPort() int {
	e, err := current()
	if err != nil {
		return 0
	}
	return e.gw.port
}

func Logs() string {
	return logs.String()
}

func current() (*engine, error) {
	mu.Lock()
	defer mu.Unlock()
	if cur == nil {
		return nil, errors.New("tailscale is not started")
	}
	return cur, nil
}

func (e *engine) watch(ctx context.Context, listener StateListener) {
	for ctx.Err() == nil {
		w, err := e.lc.WatchIPNBus(ctx, ipn.NotifyInitialState)
		if err == nil {
			for {
				n, err := w.Next()
				if err != nil {
					break
				}
				if n.State == nil && n.BrowseToURL == nil {
					continue
				}
				if listener != nil {
					st, err := e.lc.StatusWithoutPeers(ctx)
					if err == nil {
						listener.OnState(st.BackendState, st.AuthURL)
					}
				}
			}
			w.Close()
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(time.Second):
		}
	}
}

type nodeJSON struct {
	ID             string   `json:"id"`
	Name           string   `json:"name"`
	DNSName        string   `json:"dnsName"`
	OS             string   `json:"os"`
	IPs            []string `json:"ips"`
	Online         bool     `json:"online"`
	Active         bool     `json:"active"`
	LastSeen       string   `json:"lastSeen,omitempty"`
	KeyExpiry      string   `json:"keyExpiry,omitempty"`
	Tags           []string `json:"tags,omitempty"`
	Routes         []string `json:"routes,omitempty"`
	User           string   `json:"user,omitempty"`
	CurAddr        string   `json:"curAddr,omitempty"`
	Relay          string   `json:"relay,omitempty"`
	ExitNodeOption bool     `json:"exitNodeOption"`
	RxBytes        int64    `json:"rxBytes"`
	TxBytes        int64    `json:"txBytes"`
}

type statusJSON struct {
	State          string     `json:"state"`
	AuthURL        string     `json:"authUrl,omitempty"`
	Tailnet        string     `json:"tailnet,omitempty"`
	MagicDNSSuffix string     `json:"magicDnsSuffix,omitempty"`
	Health         []string   `json:"health,omitempty"`
	Self           *nodeJSON  `json:"self,omitempty"`
	Peers          []nodeJSON `json:"peers"`
}

// Status returns the backend state, this node and all visible peers as JSON.
func Status() (string, error) {
	e, err := current()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	st, err := e.lc.Status(ctx)
	if err != nil {
		return "", err
	}
	users := func(id tailcfg.UserID) string {
		if u, ok := st.User[id]; ok {
			return u.LoginName
		}
		return ""
	}
	out := statusJSON{
		State:          st.BackendState,
		AuthURL:        st.AuthURL,
		MagicDNSSuffix: st.MagicDNSSuffix,
		Health:         st.Health,
		Peers:          []nodeJSON{},
	}
	if st.CurrentTailnet != nil {
		out.Tailnet = st.CurrentTailnet.Name
	}
	if st.Self != nil {
		n := toNode(st.Self, users(st.Self.UserID))
		out.Self = &n
	}
	for _, p := range st.Peer {
		out.Peers = append(out.Peers, toNode(p, users(p.UserID)))
	}
	sort.Slice(out.Peers, func(i, j int) bool {
		a, b := out.Peers[i], out.Peers[j]
		if a.Online != b.Online {
			return a.Online
		}
		return strings.ToLower(a.Name) < strings.ToLower(b.Name)
	})
	b, err := json.Marshal(out)
	return string(b), err
}

func toNode(p *ipnStatusPeer, user string) nodeJSON {
	n := nodeJSON{
		ID:             string(p.ID),
		Name:           p.HostName,
		DNSName:        strings.TrimSuffix(p.DNSName, "."),
		OS:             p.OS,
		Online:         p.Online,
		Active:         p.Active,
		User:           user,
		CurAddr:        p.CurAddr,
		Relay:          p.Relay,
		ExitNodeOption: p.ExitNodeOption,
		RxBytes:        p.RxBytes,
		TxBytes:        p.TxBytes,
	}
	if label, _, ok := strings.Cut(n.DNSName, "."); ok && label != "" {
		n.Name = label
	}
	for _, ip := range p.TailscaleIPs {
		n.IPs = append(n.IPs, ip.String())
	}
	if !p.LastSeen.IsZero() {
		n.LastSeen = p.LastSeen.Format(time.RFC3339)
	}
	if p.KeyExpiry != nil {
		n.KeyExpiry = p.KeyExpiry.Format(time.RFC3339)
	}
	if p.Tags != nil {
		n.Tags = p.Tags.AsSlice()
	}
	if p.PrimaryRoutes != nil {
		for _, r := range p.PrimaryRoutes.All() {
			n.Routes = append(n.Routes, r.String())
		}
	}
	return n
}

// Ping sends a disco ping and returns JSON {latencyMs, endpoint, derp, error}.
func Ping(ip string) (string, error) {
	e, err := current()
	if err != nil {
		return "", err
	}
	addr, err := netip.ParseAddr(ip)
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	res, err := e.lc.Ping(ctx, addr, tailcfg.PingDisco)
	if err != nil {
		return "", err
	}
	b, err := json.Marshal(map[string]any{
		"latencyMs": res.LatencySeconds * 1000,
		"endpoint":  res.Endpoint,
		"derp":      res.DERPRegionCode,
		"error":     res.Err,
	})
	return string(b), err
}

type ifaceJSON struct {
	Name     string   `json:"name"`
	Index    int      `json:"index"`
	MTU      int      `json:"mtu"`
	Up       bool     `json:"up"`
	Loopback bool     `json:"loopback"`
	Addrs    []string `json:"addrs"`
}

func parseInterfaces(raw string) ([]netmon.Interface, error) {
	var list []ifaceJSON
	if err := json.Unmarshal([]byte(raw), &list); err != nil {
		return nil, fmt.Errorf("parse interfaces: %w", err)
	}
	out := make([]netmon.Interface, 0, len(list))
	for _, it := range list {
		var flags net.Flags
		if it.Up {
			flags |= net.FlagUp | net.FlagRunning
		}
		if it.Loopback {
			flags |= net.FlagLoopback
		} else {
			flags |= net.FlagMulticast | net.FlagBroadcast
		}
		addrs := []net.Addr{}
		for _, a := range it.Addrs {
			p, err := netip.ParsePrefix(a)
			if err != nil {
				continue
			}
			addrs = append(addrs, &net.IPNet{
				IP:   p.Addr().AsSlice(),
				Mask: net.CIDRMask(p.Bits(), p.Addr().BitLen()),
			})
		}
		out = append(out, netmon.Interface{
			Interface: &net.Interface{Index: it.Index, MTU: it.MTU, Name: it.Name, Flags: flags},
			AltAddrs:  addrs,
		})
	}
	return out, nil
}

func platformLookup(platform Platform, host string) ([]netip.Addr, error) {
	if ip, err := netip.ParseAddr(host); err == nil {
		return []netip.Addr{ip}, nil
	}
	var out []netip.Addr
	for _, s := range strings.Split(platform.Lookup(host), ",") {
		if ip, err := netip.ParseAddr(strings.TrimSpace(s)); err == nil {
			out = append(out, ip.Unmap())
		}
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("lookup %s: no addresses", host)
	}
	return out, nil
}

// directDialer dials the physical network, resolving names through Android.
type directDialer struct {
	platform Platform
}

func (d *directDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	network = N.NetworkName(network)
	var dialer net.Dialer
	if !destination.IsFqdn() {
		return dialer.DialContext(ctx, network, destination.String())
	}
	addrs, err := platformLookup(d.platform, destination.Fqdn)
	if err != nil {
		return nil, err
	}
	var lastErr error
	for _, ip := range addrs {
		conn, err := dialer.DialContext(ctx, network, netip.AddrPortFrom(ip, destination.Port).String())
		if err == nil {
			return conn, nil
		}
		lastErr = err
	}
	return nil, lastErr
}

func (d *directDialer) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	var lc net.ListenConfig
	return lc.ListenPacket(ctx, "udp", "")
}
