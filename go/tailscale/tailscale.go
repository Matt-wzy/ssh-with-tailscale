// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Matt <matt@mattsgateway.dpdns.org>
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version. See LICENSE for the full text.

// Package tailscale is the gomobile binding that embeds a Tailscale node
// directly inside an Android app.
//
// It runs Tailscale in "userspace" (netstack) mode: no TUN device is created,
// the Android VpnService is NOT requested, and no root is needed. Instead the
// node exposes a local SOCKS5 proxy (default 127.0.0.1:1055). Any app traffic
// routed through that proxy is transparently sent over the Tailscale WireGuard
// tunnel to the target device's 100.x.x.x address.
//
// This is what lets an SSH client reach tailnet devices while another app
// (e.g. a corporate VPN) keeps holding the single system VpnService slot.
package tailscale

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/netip"
	"os"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"

	socks5 "github.com/things-go/go-socks5"
	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

var (
	mu       sync.Mutex
	srv      *tsnet.Server
	socksSrv *socks5.Server
	ln       net.Listener
	cancel   context.CancelFunc

	// up reports that srv.Up() has succeeded. Until then tsnet's internals
	// (s.lb) are nil, and calling TailscaleIPs() would dereference nil and
	// abort the whole process with SIGSEGV/SIGABRT.
	up bool
	// lastErr holds the most recent error text, so the UI can show why the
	// node failed to come up (e.g. the Android netlink/SELinux denial).
	lastErr string
)

var (
	// authMu guards authURL alone.
	//
	// Deliberately NOT the main `mu`: authURL is written from tsnet's logger
	// callback, which runs on whatever goroutine tailscale happens to be on --
	// including a synchronous log emitted *while* another function already
	// holds `mu` (Stop() holds it across srv.Close()). Sharing `mu` there would
	// deadlock the app.
	authMu  sync.Mutex
	authURL string
)

// handlePanic records a recovered panic.
//
// IMPORTANT: recover() only returns non-nil when it is called *directly* by a
// deferred function. Calling it from inside a helper (e.g. a `guard()` wrapper
// invoked by the deferred closure) always returns nil, which silently defeats
// the whole barrier. So every exported function uses the literal pattern:
//
//	defer func() { if r := recover(); r != nil { handlePanic("Name", r) } }()
func handlePanic(fnName string, r any) {
	msg := fmt.Sprintf("%s panic: %v", fnName, r)
	log.Printf("tailscale: %s", msg)
	mu.Lock()
	lastErr = msg
	mu.Unlock()
}

// urlRe extracts the first URL from a log line.
var urlRe = regexp.MustCompile(`https?://\S+`)

// captureLogf is installed as tsnet's logger.
//
// When tsnet cannot authenticate headlessly -- an expired or revoked authkey, or
// a node that has been logged out -- it does not fail: Up() blocks waiting for
// someone to complete an interactive login, and prints the login URL to its
// logger. Without grabbing it here that URL only ever reaches logcat and the
// user watches "starting up" forever with no idea what to do.
//
// Every message still goes to log.Printf unchanged, so existing diagnostics are
// unaffected. This must not take the main `mu` (see authMu).
func captureLogf(format string, args ...any) {
	msg := fmt.Sprintf(format, args...)
	log.Printf("%s", msg)

	if strings.Contains(msg, "restart with TS_AUTHKEY") ||
		strings.Contains(msg, "To start this tsnet server") {
		if u := urlRe.FindString(msg); u != "" {
			authMu.Lock()
			authURL = strings.TrimRight(u, ".,)")
			authMu.Unlock()
			log.Printf("tailscale: node needs interactive login, %s", authURL)
		}
	}
}

// AuthURL returns the login URL tsnet is waiting on, or "" when the node did
// not ask for an interactive login.
//
// The UI should surface this (and ideally link to it) whenever the node is
// running but not yet up.
func AuthURL() string {
	authMu.Lock()
	defer authMu.Unlock()
	return authURL
}

// Start brings up a Tailscale node in userspace mode and starts a local SOCKS5
// proxy listening on socksAddr (for example "127.0.0.1:1055").
//
// stateDir must be a private, writable app directory used to persist the node
// machine key (e.g. Context.getFilesDir() + "/tailscale").
//
// authKey is a Tailscale/Headscale authkey. Pass "" to reuse an already
// authenticated stateDir. The node authenticates headlessly (no browser), which
// is what makes this usable on a phone.
//
// Returns nil if the proxy is already running. Use LastError() to see why a
// node failed to come up.
func Start(stateDir, socksAddr, authKey string) (err error) {
	defer func() { if r := recover(); r != nil { handlePanic("Start", r) } }()

	mu.Lock()
	defer mu.Unlock()

	if srv != nil {
		return nil // already running
	}
	up = false
	lastErr = ""
	authMu.Lock()
	authURL = ""
	authMu.Unlock()

	ctx, stop := context.WithCancel(context.Background())
	cancel = stop

	srv = &tsnet.Server{
		Dir:      stateDir,
		Hostname: "android-ssh",
		AuthKey:  authKey,
		// Own logger so the interactive-login URL can be captured instead of
		// being lost in logcat; see captureLogf.
		Logf: captureLogf,
		// Ephemeral: true, // uncomment to auto-remove this node from the tailnet on Stop()
	}

	// Bring the node up in the background; Up blocks until the node is
	// registered and running. It returns (*ipnstate.Status, error).
	// s.lb is only initialised on success -- hence the `up` flag.
	go func() {
		defer func() {
			if r := recover(); r != nil {
				log.Printf("tailscale: Up goroutine panic: %v", r)
				mu.Lock()
				up, lastErr = false, fmt.Sprintf("Up panic: %v", r)
				mu.Unlock()
			}
		}()
		_, err := srv.Up(ctx)
		mu.Lock()
		if err != nil {
			if ctx.Err() == nil { // ignore errors caused by Stop()
				log.Printf("tailscale: Up error: %v", err)
				lastErr = err.Error()
			}
			up = false
		} else {
			up = true
			lastErr = ""
		}
		mu.Unlock()
	}()

	// Route every SOCKS dial through the Tailscale netstack so the connection
	// reaches 100.x.x.x (and approved subnet routes).
	socksSrv = socks5.NewServer(
		socks5.WithDial(func(ctx context.Context, network, addr string) (net.Conn, error) {
			return srv.Dial(ctx, network, addr)
		}),
	)

	// We own the listener so Stop() can close it (ListenAndServe would not
	// expose it).
	ln, err = net.Listen("tcp", socksAddr)
	if err != nil {
		stop()
		srv, socksSrv, cancel = nil, nil, nil
		lastErr = err.Error()
		return err
	}

	go func() {
		if err := socksSrv.Serve(ln); err != nil {
			log.Printf("tailscale: socks5 stopped: %v", err)
		}
	}()
	return nil
}

// Stop tears down the SOCKS listener and the Tailscale node.
func Stop() error {
	defer func() { if r := recover(); r != nil { handlePanic("Stop", r) } }()

	mu.Lock()
	defer mu.Unlock()

	if cancel != nil {
		cancel()
	}
	if ln != nil {
		_ = ln.Close()
	}
	if srv != nil {
		_ = srv.Close()
	}
	stopAllPublished()
	srv, socksSrv, ln, cancel = nil, nil, nil, nil
	up = false
	authMu.Lock()
	authURL = ""
	authMu.Unlock()
	return nil
}

// Status returns the node's Tailscale IPs (e.g. "100.64.1.2") once it is
// connected, or an empty string otherwise.
//
// It deliberately does not call Up(): that blocks until the node registers.
// It also refuses to call TailscaleIPs() before Up() succeeded, because tsnet
// leaves s.lb nil on failure and TailscaleIPs() would then dereference nil and
// abort the process.
func Status() string {
	defer func() { if r := recover(); r != nil { handlePanic("Status", r) } }()

	mu.Lock()
	defer mu.Unlock()

	if srv == nil || !up {
		return ""
	}
	ip4, ip6 := srv.TailscaleIPs()
	parts := make([]string, 0, 2)
	if ip4.IsValid() {
		parts = append(parts, ip4.String())
	}
	if ip6.IsValid() {
		parts = append(parts, ip6.String())
	}
	return strings.Join(parts, ", ")
}

// IsRunning reports whether a node is currently active (not necessarily
// connected yet -- use Status()/IsUp() for that).
func IsRunning() bool {
	defer func() { if r := recover(); r != nil { handlePanic("IsRunning", r) } }()

	mu.Lock()
	defer mu.Unlock()
	return srv != nil
}

// IsUp reports whether the node has successfully joined the tailnet.
func IsUp() bool {
	defer func() { if r := recover(); r != nil { handlePanic("IsUp", r) } }()

	mu.Lock()
	defer mu.Unlock()
	return up
}

// LastError returns the most recent error text (empty if none), so the UI can
// show why the node failed to come up.
func LastError() string {
	mu.Lock()
	defer mu.Unlock()
	return lastErr
}

// ---------------------------------------------------------------- interfaces

// ifaceJSON is one entry of the JSON array passed to SetInterfaces by the
// Android layer.
type ifaceJSON struct {
	Name         string   `json:"name"`
	Index        int      `json:"index"`
	MTU          int      `json:"mtu"`
	Up           bool     `json:"up"`
	Loopback     bool     `json:"loopback"`
	PointToPoint bool     `json:"pointToPoint"`
	Virtual      bool     `json:"virtual"`
	HwAddr       string   `json:"hw"`
	Addrs        []string `json:"addrs"` // CIDR strings, e.g. "192.168.1.5/24"
}

// ifaces holds the interface list last supplied by the Android layer.
var ifaces []netmon.Interface

// SetInterfaces is called from Kotlin with the network interfaces as reported by
// java.net.NetworkInterface, as a JSON array (see ifaceJSON).
//
// Why this is necessary: on Android, Go's net.Interfaces() needs an RTNETLINK
// socket, which SELinux denies to untrusted apps, so it fails with
// "route ip+net: netlinkrib: permission denied" and Tailscale never comes up.
// Tailscale's own Android app solves this by registering an alternate interface
// getter; netmon.RegisterInterfaceGetter is the corresponding public hook.
// Java's NetworkInterface has no such restriction, so we proxy through it.
func SetInterfaces(jsonStr string) (err error) {
	defer func() { if r := recover(); r != nil { handlePanic("SetInterfaces", r) } }()

	var in []ifaceJSON
	if err := json.Unmarshal([]byte(jsonStr), &in); err != nil {
		mu.Lock()
		lastErr = "SetInterfaces: " + err.Error()
		mu.Unlock()
		return err
	}

	built := make([]netmon.Interface, 0, len(in))
	for _, d := range in {
		if d.Name == "" {
			continue
		}
		ni := &net.Interface{Name: d.Name, Index: d.Index, MTU: d.MTU}
		if d.HwAddr != "" {
			if hw, err := net.ParseMAC(d.HwAddr); err == nil {
				ni.HardwareAddr = hw
			}
		}
		// netmon derives IsUp()/IsLoopback() purely from these flags, so they
		// must be supplied accurately -- they are our only source of truth.
		var fl net.Flags
		if d.Up {
			fl |= net.FlagUp | net.FlagRunning
		}
		if d.Loopback {
			fl |= net.FlagLoopback
		}
		if d.PointToPoint {
			fl |= net.FlagPointToPoint
		}
		if !d.Loopback {
			fl |= net.FlagBroadcast | net.FlagMulticast
		}
		ni.Flags = fl

		// AltAddrs must be set: otherwise netmon falls back to
		// net.Interface.Addrs(), which hits the same blocked netlink path.
		addrs := make([]net.Addr, 0, len(d.Addrs))
		for _, s := range d.Addrs {
			p, err := netip.ParsePrefix(s)
			if err != nil {
				continue
			}
			addrs = append(addrs, &net.IPNet{
				IP:   p.Addr().AsSlice(),
				Mask: net.CIDRMask(p.Bits(), p.Addr().BitLen()),
			})
		}
		built = append(built, netmon.Interface{Interface: ni, AltAddrs: addrs})
	}

	mu.Lock()
	ifaces = built
	mu.Unlock()

	if len(built) > 0 {
		netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
			mu.Lock()
			defer mu.Unlock()
			if len(ifaces) == 0 {
				return nil, fmt.Errorf("no interfaces supplied by the Android layer")
			}
			return ifaces, nil
		})
	}
	return nil
}

// SetStorageDirs tells the Go layer which directories it is allowed to write to.
//
// This is required on Android. Tailscale's logpolicy looks for a place to keep
// its log state and walks this list: the system state dir (/var/lib/tailscale),
// os.UserCacheDir(), the current working directory, then os.MkdirTemp. Inside an
// unprivileged Android app all of them fail (/var/lib isn't writable, $HOME is
// unset and the cwd is "/"), and logpolicy responds by *panicking* with
// "no safe place found to store log state" -- which aborts the whole app.
//
// Pointing os.UserCacheDir() and os.MkdirTemp() at the app's own private
// directories makes the lookup succeed on the second step.
func SetStorageDirs(cacheDir, tmpDir string) (err error) {
	defer func() { if r := recover(); r != nil { handlePanic("SetStorageDirs", r) } }()

	if cacheDir != "" {
		if err := os.Setenv("XDG_CACHE_HOME", cacheDir); err != nil {
			return err
		}
	}
	if tmpDir != "" {
		if err := os.Setenv("TMPDIR", tmpDir); err != nil {
			return err
		}
	}
	log.Printf("tailscale: storage dirs set (cache=%q tmp=%q)", cacheDir, tmpDir)
	return nil
}

// InterfaceCount reports how many interfaces the Android layer has supplied;
// the UI uses it as a sanity check before starting.
func InterfaceCount() int {
	defer func() { if r := recover(); r != nil { handlePanic("InterfaceCount", r) } }()

	mu.Lock()
	defer mu.Unlock()
	return len(ifaces)
}

// peerInfo is one entry of the JSON array returned by Peers().
type peerInfo struct {
	Name   string `json:"name"`
	IP     string `json:"ip"`
	DNS    string `json:"dns"`
	OS     string `json:"os"`
	Online bool   `json:"online"`
}

// Peers returns a JSON array of the machines currently visible in the tailnet,
// e.g. [{"name":"rpi","ip":"100.64.0.2","dns":"rpi.tailnet.ts.net","os":"linux","online":true}].
//
// Returns "[]" when the node is not up yet. The UI can use this to offer a
// pick-list of devices to connect to instead of typing 100.x.x.x by hand.
func Peers() string {
	defer func() { if r := recover(); r != nil { handlePanic("Peers", r) } }()

	// Copy the server pointer and release `mu` before doing any network work:
	// lc.Status() talks to the in-process API and can take a while, and holding
	// `mu` across it stalled Stop()/Start()/Status() (including UI-thread
	// callers) for up to the full 5-second timeout.
	mu.Lock()
	node := srv
	ready := up
	mu.Unlock()

	if node == nil || !ready {
		return "[]"
	}

	ctx, cancelFn := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancelFn()

	lc, err := node.LocalClient()
	if err != nil {
		mu.Lock()
		lastErr = err.Error()
		mu.Unlock()
		return "[]"
	}
	st, err := lc.Status(ctx)
	if err != nil {
		mu.Lock()
		lastErr = err.Error()
		mu.Unlock()
		return "[]"
	}

	out := make([]peerInfo, 0, len(st.Peer))
	for _, p := range st.Peer {
		if p == nil {
			continue
		}
		ip := ""
		for _, a := range p.TailscaleIPs {
			if a.Is4() {
				ip = a.String()
				break
			}
		}
		if ip == "" && len(p.TailscaleIPs) > 0 {
			ip = p.TailscaleIPs[0].String()
		}
		name := p.HostName
		if strings.TrimSuffix(name, ".") == "" {
			name = strings.TrimSuffix(p.DNSName, ".")
		}
		out = append(out, peerInfo{
			Name:   name,
			IP:     ip,
			DNS:    strings.TrimSuffix(p.DNSName, "."),
			OS:     p.OS,
			Online: p.Online,
		})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })

	b, err := json.Marshal(out)
	if err != nil {
		lastErr = err.Error()
		return "[]"
	}
	return string(b)
}
