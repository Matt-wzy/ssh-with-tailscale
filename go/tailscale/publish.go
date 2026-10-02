// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Matt <matt@mattsgateway.dpdns.org>
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version. See LICENSE for the full text.

package tailscale

// Reverse publishing: turning the phone into a first-class server on the tailnet.
//
// srv.Listen() opens a listener on the tailnet interface (not on the phone's
// normal network), so anything on the tailnet can connect to it. Each accepted
// connection is then dialled onwards from the phone's own network -- which is
// what makes the phone a small "reverse subnet router": one port published this
// way gives the whole tailnet reach into the phone's LAN, with no VpnService,
// no SSH and no admin approval.
//
// Two modes:
//   - relay: every connection is forwarded to one fixed target.
//   - proxy: an HTTP proxy (CONNECT for https, absolute-URI GET for http), so a
//     tailnet device can browse through the phone.

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"
)

const (
	// Mode of a published port.
	modeRelay = "relay"
	modeProxy = "proxy"

	dialTimeout = 10 * time.Second

	// proxyHandshakeTimeout bounds waiting for the request line on a freshly
	// accepted proxy connection. It is not an idle timeout.
	proxyHandshakeTimeout = 30 * time.Second
)

// published is one listener we have opened on the tailnet.
type published struct {
	addr   string // address we listen on, e.g. ":8080"
	mode   string
	target string // relay mode only

	ln net.Listener
}

var (
	pubMu sync.Mutex
	pubs  = map[string]*published{}
)

// Publish starts listening on the tailnet at addr (for example ":8080") and
// relays every accepted connection to target (for example "192.168.1.50:80"),
// dialled from the phone's own network.
func Publish(addr, target string) error {
	defer func() {
		if r := recover(); r != nil {
			handlePanic("Publish", r)
		}
	}()
	if strings.TrimSpace(target) == "" {
		return fmt.Errorf("目标地址不能为空")
	}
	return publish(addr, modeRelay, target)
}

// PublishProxy starts listening on the tailnet at addr and serves an HTTP proxy
// (CONNECT plus plain absolute-URI requests) that dials out through the phone's
// own network.
func PublishProxy(addr string) error {
	defer func() {
		if r := recover(); r != nil {
			handlePanic("PublishProxy", r)
		}
	}()
	return publish(addr, modeProxy, "")
}

func publish(addr, mode, target string) error {
	if !strings.Contains(addr, ":") {
		return fmt.Errorf("地址必须带端口，例如 :8080")
	}

	mu.Lock()
	node := srv
	ready := up
	mu.Unlock()
	if node == nil {
		return fmt.Errorf("Tailscale 未启动")
	}
	if !ready {
		return fmt.Errorf("Tailscale 还没连上，请稍候再试")
	}

	ln, err := node.Listen("tcp", addr)
	if err != nil {
		return fmt.Errorf("监听 %s 失败: %w", addr, err)
	}

	p := &published{addr: addr, mode: mode, target: target, ln: ln}
	pubMu.Lock()
	if old, ok := pubs[addr]; ok {
		_ = old.ln.Close()
	}
	pubs[addr] = p
	pubMu.Unlock()

	go p.serve()
	log.Printf("tailscale: published %s (%s) target=%q", addr, mode, target)
	return nil
}

func (p *published) serve() {
	for {
		conn, err := p.ln.Accept()
		if err != nil {
			// Closed by Unpublish or Stop.
			return
		}
		go func(c net.Conn) {
			defer func() {
				if r := recover(); r != nil {
					handlePanic("publishedConn", r)
				}
			}()
			if p.mode == modeProxy {
				serveProxyConn(c)
			} else {
				relayTo(c, p.target)
			}
		}(conn)
	}
}

// relayTo forwards one tailnet connection to target on the phone's network.
func relayTo(incoming net.Conn, target string) {
	defer incoming.Close()
	up, err := dialUpstream(target)
	if err != nil {
		log.Printf("tailscale: relay to %s failed: %v", target, err)
		return
	}
	defer up.Close()
	pump(incoming, up)
}

// dialUpstream opens the onward connection from the phone's own network.
func dialUpstream(target string) (net.Conn, error) {
	d := &net.Dialer{Timeout: dialTimeout}
	c, err := d.Dial("tcp", target)
	if err != nil {
		return nil, err
	}
	// Interactive traffic tunnelled through here (SSH or a shell over the proxy)
	// is hurt by Nagle coalescing far more than it benefits from it, so let
	// small writes go out straight away.
	if tc, ok := c.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}
	return c, nil
}

// lingerAfterHalfClose bounds how long the surviving direction may keep
// draining after the other side has half-closed. A well-behaved peer reacts to
// the FIN and finishes immediately, so this only exists to stop a stuck peer
// from leaking a goroutine and a socket for the life of the app.
const lingerAfterHalfClose = 15 * time.Second

// pump copies in both directions until the pair is finished.
//
// It half-closes instead of tearing the pair down at the first EOF: the side
// that read the EOF issues CloseWrite so its peer sees a FIN, and the opposite
// copy keeps running to deliver whatever that peer already sent. Plain HTTP,
// TLS close_notify and SMTP's "." terminator all finish by hanging up after
// writing, so closing both ends at the first EOF silently truncates the tail
// of the response.
func pump(a, b net.Conn) {
	defer func() {
		_ = a.Close()
		_ = b.Close()
	}()

	done := make(chan struct{}, 2)
	// a -> b, then FIN to b.
	go func() {
		defer func() { done <- struct{}{} }()
		_, _ = io.Copy(b, a)
		halfClose(b)
	}()
	// b -> a, then FIN to a.
	go func() {
		defer func() { done <- struct{}{} }()
		_, _ = io.Copy(a, b)
		halfClose(a)
	}()

	// No time limit here: a long-lived tunnel is allowed to run as long as it
	// likes. The grace period only starts once one direction has ended.
	<-done

	timer := time.NewTimer(lingerAfterHalfClose)
	defer timer.Stop()
	select {
	case <-done: // both directions finished cleanly
	case <-timer.C: // peer never drained; drop it
	}
}

// halfClose sends a FIN without tearing the connection down, so the peer can
// still send us the bytes it has already written.
func halfClose(c net.Conn) {
	if tc, ok := c.(*net.TCPConn); ok {
		_ = tc.CloseWrite()
	}
}

// serveProxyConn speaks just enough HTTP to be usable as a proxy: CONNECT for
// https, and absolute-URI requests for plain http.
func serveProxyConn(client net.Conn) {
	defer client.Close()

	br := bufio.NewReader(client)
	// Bound just the handshake: a client that connects and never sends a request
	// line would otherwise hold a socket and a goroutine indefinitely. This is
	// cleared the moment the request head is parsed, so established tunnels keep
	// no deadline at all -- an idle-but-legitimate SSH-over-proxy session must
	// never be cut off by us.
	_ = client.SetReadDeadline(time.Now().Add(proxyHandshakeTimeout))
	req, err := http.ReadRequest(br)
	if err != nil {
		return
	}
	_ = client.SetReadDeadline(time.Time{})

	host := req.URL.Host
	if req.Method == http.MethodConnect {
		host = withPort(host, "443")
		upstream, err := dialUpstream(host)
		if err != nil {
			log.Printf("tailscale: proxy CONNECT %s failed: %v", host, err)
			_, _ = io.WriteString(client, "HTTP/1.1 502 Bad Gateway\r\n\r\n")
			return
		}
		defer upstream.Close()
		if _, err := io.WriteString(client, "HTTP/1.1 200 Connection established\r\n\r\n"); err != nil {
			return
		}
		pump(client, upstream)
		return
	}

	// Plain HTTP: the client sends an absolute URI. Strip it back to a normal
	// origin-form request before forwarding.
	if !req.URL.IsAbs() {
		_, _ = io.WriteString(client, "HTTP/1.1 400 Bad Request\r\n\r\n")
		return
	}
	upstream, err := dialUpstream(withPort(host, "80"))
	if err != nil {
		log.Printf("tailscale: proxy %s failed: %v", host, err)
		_, _ = io.WriteString(client, "HTTP/1.1 502 Bad Gateway\r\n\r\n")
		return
	}
	defer upstream.Close()

	req.URL.Scheme = ""
	req.URL.Host = ""
	if err := req.Write(upstream); err != nil {
		return
	}
	// Anything the client already sent past the head (a request body).
	if br.Buffered() > 0 {
		if _, err := io.CopyN(upstream, br, int64(br.Buffered())); err != nil {
			return
		}
	}
	pump(client, upstream)
}

func withPort(host, fallback string) string {
	if _, _, err := net.SplitHostPort(host); err != nil {
		return net.JoinHostPort(host, fallback)
	}
	return host
}

// Unpublish stops listening on addr.
func Unpublish(addr string) error {
	defer func() {
		if r := recover(); r != nil {
			handlePanic("Unpublish", r)
		}
	}()
	pubMu.Lock()
	p, ok := pubs[addr]
	if ok {
		delete(pubs, addr)
	}
	pubMu.Unlock()
	if !ok {
		return nil
	}
	return p.ln.Close()
}

// Published describes one published port for the UI.
type Published struct {
	Addr   string `json:"addr"`
	Mode   string `json:"mode"`
	Target string `json:"target"`
	// Tailnet is the address other tailnet devices should connect to.
	Tailnet string `json:"tailnet"`
}

// PublishedList returns a JSON array of the ports currently published.
func PublishedList() string {
	defer func() {
		if r := recover(); r != nil {
			handlePanic("PublishedList", r)
		}
	}()

	mu.Lock()
	node := srv
	ready := up
	mu.Unlock()
	// Same guard as Status(): tsnet leaves s.lb nil until Up() succeeds, and
	// TailscaleIPs() would dereference it and take the whole process down.
	// The UI only checks IsRunning() before opening the list, so reaching this
	// from a half-started node is a real possibility, not a theoretical one.
	prefix := ""
	if node != nil && ready {
		if ip4, ip6 := node.TailscaleIPs(); ip4.IsValid() {
			prefix = ip4.String()
		} else if ip6.IsValid() {
			prefix = ip6.String()
		}
	}

	pubMu.Lock()
	defer pubMu.Unlock()
	out := make([]Published, 0, len(pubs))
	addrs := make([]string, 0, len(pubs))
	for a := range pubs {
		addrs = append(addrs, a)
	}
	sort.Strings(addrs)
	for _, a := range addrs {
		p := pubs[a]
		tailnet := ""
		if prefix != "" {
			_, port, err := net.SplitHostPort(p.ln.Addr().String())
			if err == nil {
				tailnet = net.JoinHostPort(prefix, port)
			}
		}
		out = append(out, Published{Addr: a, Mode: p.mode, Target: p.target, Tailnet: tailnet})
	}
	b, err := json.Marshal(out)
	if err != nil {
		return "[]"
	}
	return string(b)
}

// stopAllPublished closes every published listener. Called from Stop().
func stopAllPublished() {
	pubMu.Lock()
	list := make([]*published, 0, len(pubs))
	for _, p := range pubs {
		list = append(list, p)
	}
	pubs = map[string]*published{}
	pubMu.Unlock()
	for _, p := range list {
		_ = p.ln.Close()
	}
}
