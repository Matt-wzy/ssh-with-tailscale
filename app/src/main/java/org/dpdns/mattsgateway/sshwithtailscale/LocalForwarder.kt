// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Matt <matt@mattsgateway.dpdns.org>
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version. See LICENSE for the full text.

package org.dpdns.mattsgateway.sshwithtailscale

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.Session
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Local (-L) port forwarding, implemented here instead of with JSch's
 * `setPortForwardingL`.
 *
 * JSch's own watcher (`PortWatcher.java:168`) wraps its entire accept loop in a
 * single try block and swallows the exception:
 *
 * ```java
 * try { while (thread != null) { Socket socket = ss.accept(); ... } }
 * catch (Exception e) { //System.err.println("! " + e); }
 * delete();   // tears the whole forward down
 * ```
 *
 * So one failing connection kills the forward for good, silently. A browser --
 * which preconnects and abandons connections -- hits that easily; on the device
 * the listener simply vanished while the SSH session stayed healthy.
 *
 * Here every accepted connection gets its own thread and its own error handling,
 * failures are reported instead of swallowed, and the channel is opened exactly
 * the way JSch's own watcher does it (which is known to work).
 */
class LocalForwarder(
    private val session: Session,
    val bindHost: String,
    val bindPort: Int,
    val targetHost: String,
    val targetPort: Int,
    private val onError: (String) -> Unit
) {
    @Volatile
    private var running = true

    private val server = ServerSocket()

    /** Binds and starts accepting. Throws if the port cannot be bound. */
    fun start() {
        server.reuseAddress = true
        server.bind(InetSocketAddress(bindHost, bindPort))
        thread(name = "fwd-accept-$bindPort", isDaemon = true) {
            while (running) {
                val client = try {
                    server.accept()
                } catch (e: Exception) {
                    // A closed listener is the normal shutdown path.
                    if (running) onError("端口 $bindPort 接受连接失败：${e.message}")
                    break
                }
                // One thread per connection, so a failure cannot take the next
                // connection down with it.
                thread(name = "fwd-conn-$bindPort", isDaemon = true) { serve(client) }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { server.close() }
    }

    private fun serve(client: Socket) {
        var channel: ChannelDirectTCPIP? = null
        val deadline = System.currentTimeMillis() + MAX_LIFETIME_MS
        try {
            client.tcpNoDelay = true
            val ch = session.openChannel("direct-tcpip") as? ChannelDirectTCPIP
                ?: throw IllegalStateException("无法打开 direct-tcpip 通道")
            channel = ch
            ch.setHost(targetHost)
            ch.setPort(targetPort)
            ch.setOrgIPAddress(client.inetAddress?.hostAddress ?: "127.0.0.1")
            ch.setOrgPort(client.port)
            // JSch then pumps both directions itself.
            ch.setInputStream(client.getInputStream())
            ch.setOutputStream(client.getOutputStream())
            ch.connect(CONNECT_TIMEOUT_MS)

            // connect() is asynchronous -- it only sends the open request. Wait
            // for the confirmation (or a refusal) before treating it as ready,
            // or the connection would be torn down immediately.
            val openBy = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
            while (running && !ch.isConnected && !ch.isClosed &&
                System.currentTimeMillis() < openBy
            ) {
                Thread.sleep(20)
            }
            if (!ch.isConnected) {
                throw IllegalStateException("通道打开失败（服务端拒绝或超时）")
            }

            // Then wait for the transfer to end. JSch closes the channel once
            // both sides are done; the deadline is only a backstop so a
            // connection no one ever finishes cannot hold a thread and a socket
            // forever.
            while (running && ch.isConnected && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
        } catch (e: Exception) {
            onError("$bindHost:$bindPort → $targetHost:$targetPort：${e.message}")
        } finally {
            // Half-close first: slamming the socket shut would show the client a
            // reset instead of the end of the response.
            runCatching { client.shutdownOutput() }
            runCatching { channel?.disconnect() }
            runCatching { client.close() }
        }
    }

    private companion object {
        // Generous: on the device, over the Tailscale node, the channel-open
        // confirmation has been seen to take well over the usual second. Setting
        // this too low is worse than waiting -- it tears the client connection
        // down mid-transfer.
        const val CONNECT_TIMEOUT_MS = 60_000

        /** Backstop for connections that never finish on their own. */
        const val MAX_LIFETIME_MS = 60L * 60 * 1000
    }
}
