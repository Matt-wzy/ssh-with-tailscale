// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Matt <matt@mattsgateway.dpdns.org>
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version. See LICENSE for the full text.

package tailscale

import (
	"os"
	"strings"
	"testing"

	"tailscale.com/net/netmon"
)

// reset returns the package to a clean state between tests.
func reset(t *testing.T) {
	t.Helper()
	mu.Lock()
	srv, socksSrv, ln, cancel = nil, nil, nil, nil
	up, lastErr = false, ""
	ifaces = nil
	mu.Unlock()
	// Drop any registered alternative interface provider.
	netmon.RegisterInterfaceGetter(nil)
}

// TestStatusBeforeStartDoesNotPanic is a regression test for a real crash:
// tsnet.Server.Up() can fail (on Android it fails because netmon cannot use
// RTNETLINK), and after that failure tsnet leaves s.lb == nil. Calling
// TailscaleIPs() then dereferences nil and takes the whole process down with
// SIGSEGV/SIGABRT. Status() must therefore refuse to ask for IPs until Up()
// has actually succeeded.
func TestStatusBeforeStartDoesNotPanic(t *testing.T) {
	reset(t)
	defer reset(t)

	if got := Status(); got != "" {
		t.Fatalf("Status() before start = %q, want empty", got)
	}
	if IsUp() {
		t.Fatal("IsUp() before start = true, want false")
	}
	if IsRunning() {
		t.Fatal("IsRunning() before start = true, want false")
	}
}

// TestPeersBeforeStart returns an empty JSON array and must not panic.
func TestPeersBeforeStart(t *testing.T) {
	reset(t)
	defer reset(t)

	got := Peers()
	if got != "[]" {
		t.Fatalf("Peers() before start = %q, want \"[]\"", got)
	}
}

// TestStopWithoutStart must be safe to call (the UI can hit Stop at any time).
func TestStopWithoutStart(t *testing.T) {
	reset(t)
	defer reset(t)

	if err := Stop(); err != nil {
		t.Fatalf("Stop() without Start() returned error: %v", err)
	}
}

// TestLastErrorBeforeStart is empty until something fails.
func TestLastErrorBeforeStart(t *testing.T) {
	reset(t)
	defer reset(t)

	if got := LastError(); got != "" {
		t.Fatalf("LastError() before start = %q, want empty", got)
	}
}

// TestPanicBarrierRecordsError verifies the panic barrier actually works.
//
// Without it, any Go panic in a gomobile binding aborts the whole Android app
// with SIGABRT and no Java stack trace. Note recover() must be called directly
// by the deferred function -- wrapping it in a helper silently breaks this,
// which is exactly the bug this test was written to catch.
func TestPanicBarrierRecordsError(t *testing.T) {
	reset(t)
	defer reset(t)

	// This must NOT propagate the panic out of the closure.
	func() {
		defer func() {
			if r := recover(); r != nil {
				handlePanic("unit", r)
			}
		}()
		panic("boom")
	}()

	if got := LastError(); !strings.Contains(got, "unit panic") {
		t.Fatalf("LastError() = %q, want it to record \"unit panic\"", got)
	}
}

// TestNoPanicLeavesLastErrorEmpty: the happy path must not record an error.
func TestNoPanicLeavesLastErrorEmpty(t *testing.T) {
	reset(t)
	defer reset(t)

	func() {
		defer func() {
			if r := recover(); r != nil {
				handlePanic("unit", r)
			}
		}()
		// no panic
	}()

	if got := LastError(); got != "" {
		t.Fatalf("LastError() = %q, want empty on the happy path", got)
	}
}

// TestSetInterfacesInstallsProvider is the regression test for the Android
// startup failure. On Android, Go's net.Interfaces() is blocked by SELinux
// ("route ip+net: netlinkrib: permission denied"), so the Android layer supplies
// the interface list and we register it via netmon.RegisterInterfaceGetter.
// This test asserts that GetInterfaceList -- the function that would otherwise
// hit netlink -- now returns our data instead.
func TestSetInterfacesInstallsProvider(t *testing.T) {
	reset(t)
	defer reset(t)

	const payload = `[
	  {"name":"wlan0","index":2,"mtu":1500,"up":true,"loopback":false,"addrs":["192.168.1.5/24","fe80::1/64"]},
	  {"name":"lo","index":1,"mtu":65536,"up":true,"loopback":true,"addrs":["127.0.0.1/8"]}
	]`
	if err := SetInterfaces(payload); err != nil {
		t.Fatalf("SetInterfaces() error: %v", err)
	}
	if got := InterfaceCount(); got != 2 {
		t.Fatalf("InterfaceCount() = %d, want 2", got)
	}

	list, err := netmon.GetInterfaceList()
	if err != nil {
		t.Fatalf("GetInterfaceList() error: %v", err)
	}
	if len(list) != 2 {
		t.Fatalf("GetInterfaceList() len = %d, want 2", len(list))
	}

	var wlan *netmon.Interface
	for i := range list {
		if list[i].Name == "wlan0" {
			wlan = &list[i]
		}
	}
	if wlan == nil {
		t.Fatal("wlan0 missing from the interface list")
	}
	if !wlan.IsUp() {
		t.Error("wlan0 should be up")
	}
	if wlan.IsLoopback() {
		t.Error("wlan0 should not be loopback")
	}
	// AltAddrs must be populated, otherwise netmon would call
	// net.Interface.Addrs() and hit the blocked netlink path again.
	addrs, err := wlan.Addrs()
	if err != nil {
		t.Fatalf("wlan0.Addrs() error: %v", err)
	}
	if len(addrs) != 2 {
		t.Fatalf("wlan0.Addrs() len = %d, want 2", len(addrs))
	}
	if !strings.HasPrefix(addrs[0].String(), "192.168.1.5/24") {
		t.Errorf("first addr = %q, want it to start with 192.168.1.5/24", addrs[0].String())
	}
}

func TestSetInterfacesRejectsBadJSON(t *testing.T) {
	reset(t)
	defer reset(t)

	if err := SetInterfaces("{not json}"); err == nil {
		t.Fatal("SetInterfaces() accepted invalid JSON")
	}
}

func TestSetInterfacesSkipsNamelessEntries(t *testing.T) {
	reset(t)
	defer reset(t)

	const payload = `[{"name":"","index":1},{"name":"eth0","index":3,"up":true,"addrs":["10.0.0.2/24"]}]`
	if err := SetInterfaces(payload); err != nil {
		t.Fatalf("SetInterfaces() error: %v", err)
	}
	if got := InterfaceCount(); got != 1 {
		t.Fatalf("InterfaceCount() = %d, want 1 (nameless entry dropped)", got)
	}
}

// TestSetInterfacesEmptyDoesNotRegister: an empty list must not replace the
// provider with one that always errors, which would mask the real problem.
func TestSetInterfacesEmptyDoesNotRegister(t *testing.T) {
	reset(t)
	defer reset(t)

	if err := SetInterfaces("[]"); err != nil {
		t.Fatalf("SetInterfaces(\"[]\") error: %v", err)
	}
	if got := InterfaceCount(); got != 0 {
		t.Fatalf("InterfaceCount() = %d, want 0", got)
	}
}

// TestSetStorageDirsMakesUserCacheDirUsable is the regression test for the
// Android panic "no safe place found to store log state": logpolicy asks
// os.UserCacheDir() for a writable directory, and on Android that fails unless
// we point it at the app's own cache dir.
func TestSetStorageDirsMakesUserCacheDirUsable(t *testing.T) {
	oldCache, oldTmp := os.Getenv("XDG_CACHE_HOME"), os.Getenv("TMPDIR")
	defer os.Setenv("XDG_CACHE_HOME", oldCache)
	defer os.Setenv("TMPDIR", oldTmp)

	dir := t.TempDir()
	if err := SetStorageDirs(dir, dir); err != nil {
		t.Fatalf("SetStorageDirs() error: %v", err)
	}
	if got := os.Getenv("XDG_CACHE_HOME"); got != dir {
		t.Fatalf("XDG_CACHE_HOME = %q, want %q", got, dir)
	}
	if got := os.Getenv("TMPDIR"); got != dir {
		t.Fatalf("TMPDIR = %q, want %q", got, dir)
	}
	// This is the call logpolicy actually makes.
	uc, err := os.UserCacheDir()
	if err != nil {
		t.Fatalf("os.UserCacheDir() failed after SetStorageDirs: %v", err)
	}
	if uc != dir {
		t.Fatalf("os.UserCacheDir() = %q, want %q", uc, dir)
	}
	// And os.MkdirTemp("") -- the last resort before the panic.
	if _, err := os.MkdirTemp("", "tailscaled-log-*"); err != nil {
		t.Fatalf("os.MkdirTemp failed after SetStorageDirs: %v", err)
	}
}
