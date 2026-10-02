// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Matt <matt@mattsgateway.dpdns.org>
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version. See LICENSE for the full text.

package org.dpdns.mattsgateway.sshwithtailscale

import android.content.Context
import java.io.File
import tailscale.Tailscale

/**
 * Wraps the gomobile-generated Tailscale binding.
 *
 * The binding runs Tailscale as an in-process userspace (netstack) node and
 * exposes a SOCKS5 proxy on 127.0.0.1:1055. Because it does not create a TUN
 * device and never requests the Android VpnService, it coexists with a corporate
 * VPN that is holding the system VPN slot.
 */
object TailscaleManager {

    const val SOCKS_HOST = "127.0.0.1"
    const val SOCKS_PORT = 1055

    /**
     * Failure from the Kotlin side of [start] itself (JNI, directories, ...).
     * The Go side reports its own problems via lastError(); without this field
     * a Kotlin-side failure left the UI quoting a stale -- or empty -- reason.
     */
    @Volatile
    private var localError: String? = null

    /** Start the node. [authKey] may be empty to reuse an existing state dir. */
    fun start(context: Context, authKey: String): Boolean {
        localError = null
        return try {
            // Both must happen before the node starts.
            // 1. Tell Go which directories it may write to: without this,
            //    Tailscale's logpolicy finds no writable log dir on Android and
            //    panics with "no safe place found to store log state".
            context.cacheDir?.absolutePath?.let { cache ->
                Tailscale.setStorageDirs(cache, cache)
            }
            // 2. Supply the interface list, so Go never calls net.Interfaces()
            //    (blocked by SELinux: "route ip+net: netlinkrib: permission denied").
            //    force=true: this is the initial push and must always go through.
            NetworkInterfaceProvider.sync(force = true)

            val stateDir = File(context.filesDir, "tailscale").absolutePath
            Tailscale.start(stateDir, "$SOCKS_HOST:$SOCKS_PORT", authKey)
            true
        } catch (e: Throwable) {
            // Throwable, not Exception: a missing/incompatible libgojni.so comes
            // back as UnsatisfiedLinkError, and catching only Exception crashed
            // the app instead of telling the user why it would not start.
            localError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            false
        }
    }

    /** How many interfaces the Android layer has supplied to the Go side. */
    fun interfaceCount(): Int = runCatching { Tailscale.interfaceCount().toInt() }.getOrDefault(0)

    fun stop() {
        try {
            Tailscale.stop()
        } catch (_: Exception) {
        }
    }

    /**
     * The most recent error text: Go-side failures win, but a [start] that
     * failed before even reaching Go reports its own reason.
     */
    fun lastError(): String {
        localError?.let { return it }
        return runCatching { Tailscale.lastError() }.getOrDefault("")
    }

    /** The node's Tailscale IPs once connected, or "" while starting/stopped. */
    fun status(): String = runCatching { Tailscale.status() }.getOrDefault("")

    /**
     * Login URL the node is blocked on, or "" when no interactive login is
     * pending. Non-empty means the authkey expired / was revoked and Up() is
     * sitting there waiting -- the UI must say so instead of showing
     * "starting up" forever.
     */
    fun authURL(): String = runCatching { Tailscale.authURL() }.getOrDefault("")

    fun isRunning(): Boolean = runCatching { Tailscale.isRunning() }.getOrDefault(false)

    /** True once the node has actually joined the tailnet. */
    fun isUp(): Boolean = runCatching { Tailscale.isUp() }.getOrDefault(false)

    /** JSON array of visible tailnet peers: [{"name","ip","dns","os","online"}]. */
    fun peers(): String = runCatching { Tailscale.peers() }.getOrDefault("[]")

    // ---------------------------------------------- reverse export (publish)

    /**
     * Listen on the tailnet at [addr] (e.g. ":8080") and relay every connection
     * to [target] (e.g. "192.168.1.50:80"), dialled from the phone's network.
     * Returns "" on success or a Chinese error string.
     */
    fun publish(addr: String, target: String): String = try {
        Tailscale.publish(addr, target)
        ""
    } catch (e: Exception) {
        e.message ?: "发布失败"
    }

    /** Listen on the tailnet at [addr] and serve an HTTP proxy through the phone. */
    fun publishProxy(addr: String): String = try {
        Tailscale.publishProxy(addr)
        ""
    } catch (e: Exception) {
        e.message ?: "发布失败"
    }

    /** Stop listening on [addr]. Returns "" on success or an error string. */
    fun unpublish(addr: String): String = try {
        Tailscale.unpublish(addr)
        ""
    } catch (e: Exception) {
        e.message ?: "停止失败"
    }

    /** JSON array of currently published ports; see Published in publish.go. */
    fun publishedList(): String = runCatching { Tailscale.publishedList() }.getOrDefault("[]")
}
