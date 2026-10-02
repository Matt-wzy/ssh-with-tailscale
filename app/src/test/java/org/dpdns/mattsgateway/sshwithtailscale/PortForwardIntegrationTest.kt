// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Matt <matt@mattsgateway.dpdns.org>
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version. See LICENSE for the full text.

package org.dpdns.mattsgateway.sshwithtailscale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * End-to-end check of the port-forwarding code paths against a real sshd,
 * driving the production [SshSession] rather than a copy of its calls.
 *
 * Opt-in: set `SSH_TEST_PW` (plus optionally `SSH_TEST_USER` / `SSH_TEST_HOST` /
 * `SSH_TEST_PORT`) and the tests run; otherwise they are skipped, so a plain
 * `./gradlew test` needs no SSH server anywhere.
 *
 * A minimal SOCKS5 proxy stands in for the in-process Tailscale node, so the
 * remote-forward path -- which dials its target through that proxy -- is
 * exercised too.
 */
class PortForwardIntegrationTest {

    private val sshHost = System.getenv("SSH_TEST_HOST") ?: "127.0.0.1"
    private val sshPort = (System.getenv("SSH_TEST_PORT") ?: "22").toInt()
    private val sshUser = System.getenv("SSH_TEST_USER") ?: "matt"
    private val sshPassword = System.getenv("SSH_TEST_PW")

    @Before
    fun requiresARealSshServer() {
        Assume.assumeTrue("SSH_TEST_PW 未设置，跳过端口转发集成测试", !sshPassword.isNullOrBlank())
    }

    // --------------------------------------------------------------- fixtures

    /**
     * A service on the far side. Whatever it receives it sends straight back, so
     * a round trip proves the tunnel really reached it.
     */
    private fun startEchoServer(): ServerSocket {
        val server = ServerSocket(0)
        thread(isDaemon = true, name = "echo") {
            while (true) {
                val c = runCatching { server.accept() }.getOrNull() ?: return@thread
                thread(isDaemon = true) {
                    runCatching {
                        c.use { s ->
                            val buf = ByteArray(256)
                            while (true) {
                                val n = s.getInputStream().read(buf)
                                if (n < 0) break
                                s.getOutputStream().write(buf, 0, n)
                                s.getOutputStream().flush()
                            }
                        }
                    }
                }
            }
        }
        return server
    }

    /**
     * Stand-in for the Tailscale SOCKS5 proxy, bound where the app expects it.
     * Resolves the target itself and relays, which is all the app needs.
     */
    private fun startSocksProxy(port: Int): ServerSocket {
        val server = ServerSocket(port)
        thread(isDaemon = true, name = "fake-tsnet-socks") {
            while (true) {
                val c = runCatching { server.accept() }.getOrNull() ?: return@thread
                thread(isDaemon = true) {
                    runCatching { serveSocks(c) }
                }
            }
        }
        return server
    }

    private fun serveSocks(client: Socket) {
        client.use { c ->
            val input = c.getInputStream()
            val output = c.getOutputStream()
            // Greeting.
            val greet = ByteArray(2)
            readFully(input, greet)
            readFully(input, ByteArray(greet[1].toInt() and 0xff))
            output.write(byteArrayOf(0x05, 0x00)) // no auth
            output.flush()
            // Request: VER, CMD, RSV, ATYP, [addr], port.
            val head = ByteArray(4)
            readFully(input, head)
            val host = when (head[3].toInt()) {
                0x01 -> {
                    val v = ByteArray(4)
                    readFully(input, v)
                    v.joinToString(".") { (it.toInt() and 0xff).toString() }
                }
                0x03 -> {
                    val len = ByteArray(1)
                    readFully(input, len)
                    val h = ByteArray(len[0].toInt() and 0xff)
                    readFully(input, h)
                    String(h, Charsets.UTF_8)
                }
                else -> return
            }
            val p = ByteArray(2)
            readFully(input, p)
            val targetPort = ((p[0].toInt() and 0xff) shl 8) or (p[1].toInt() and 0xff)

            val target = Socket(host, targetPort)
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()

            val back = thread(isDaemon = true) {
                runCatching { target.getInputStream().copyTo(output) }
                runCatching { c.shutdownOutput() }
            }
            runCatching { input.copyTo(target.getOutputStream()) }
            runCatching { target.close() }
            back.join(500)
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw IllegalStateException("eof")
            off += n
        }
    }

    private fun newSession() = SshSession(onData = { }, onClosed = { })

    /** Writes [request] through [port] and returns what comes back. */
    private fun roundTrip(port: Int, request: String): String {
        Socket("127.0.0.1", port).use { c ->
            c.getOutputStream().write(request.toByteArray())
            c.getOutputStream().flush()
            val buf = ByteArray(request.length)
            readFully(c.getInputStream(), buf)
            return String(buf)
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun localForwardReachesAServiceOnlyTheFarSideCanSee() {
        val echo = startEchoServer()
        val socks = startSocksProxy(TailscaleManager.SOCKS_PORT)
        val session = newSession()
        try {
            session.connect(sshHost, sshPort, sshUser, sshPassword!!, "", 80, 24)

            val bindPort = 18080
            val report = session.applyForwards(
                listOf(ForwardRule(false, "127.0.0.1", bindPort, "127.0.0.1", echo.localPort))
            )
            assertEquals(report.toString(), 1, report.size)
            assertTrue(report.toString(), report[0].contains("已开启"))

            // The phone's port now tunnels to the echo server, dialled by sshd.
            assertEquals("ping-local", roundTrip(bindPort, "ping-local"))
        } finally {
            session.disconnect()
            socks.close()
            echo.close()
        }
    }

    @Test
    fun remoteForwardDialsItsTargetThroughTheProxy() {
        val echo = startEchoServer()
        val socks = startSocksProxy(TailscaleManager.SOCKS_PORT)
        val session = newSession()
        try {
            session.connect(sshHost, sshPort, sshUser, sshPassword!!, "", 80, 24)

            val remotePort = 18081
            val report = session.applyForwards(
                listOf(ForwardRule(true, "127.0.0.1", remotePort, "127.0.0.1", echo.localPort))
            )
            assertEquals(report.toString(), 1, report.size)
            assertTrue(report.toString(), report[0].contains("已开启"))

            // sshd is now listening on remotePort; a connection there comes back
            // to us and Socks5SocketFactory dials the echo server -- through the
            // proxy, which is what makes tailnet targets workable.
            assertEquals("ping-remote", roundTrip(remotePort, "ping-remote"))
        } finally {
            session.disconnect()
            socks.close()
            echo.close()
        }
    }

    /**
     * A browser does not open one connection: it preconnects, reconnects, opens
     * several sockets. Each one has to work, and a failure in one must not take
     * the whole forward down with it.
     */
    @Test
    fun severalSequentialConnectionsAllGetThrough() {
        val echo = startEchoServer()
        val socks = startSocksProxy(TailscaleManager.SOCKS_PORT)
        val session = newSession()
        try {
            session.connect(sshHost, sshPort, sshUser, sshPassword!!, "", 80, 24)
            val bindPort = 18083
            val report = session.applyForwards(
                listOf(ForwardRule(false, "127.0.0.1", bindPort, "127.0.0.1", echo.localPort))
            )
            assertTrue(report.toString(), report[0].contains("已开启"))

            repeat(5) { i ->
                assertEquals("第 ${i + 1} 条连接失败", "ping-$i", roundTrip(bindPort, "ping-$i"))
            }
        } finally {
            session.disconnect()
            socks.close()
            echo.close()
        }
    }

    @Test
    fun aFailedRuleIsReportedWithoutBreakingTheOthers() {
        val echo = startEchoServer()
        val socks = startSocksProxy(TailscaleManager.SOCKS_PORT)
        val session = newSession()
        try {
            session.connect(sshHost, sshPort, sshUser, sshPassword!!, "", 80, 24)

            val report = session.applyForwards(
                listOf(
                    // Occupied by this very test: the SSH session itself.
                    ForwardRule(false, "127.0.0.1", sshPort, "127.0.0.1", echo.localPort),
                    ForwardRule(false, "127.0.0.1", 18082, "127.0.0.1", echo.localPort)
                )
            )
            assertEquals(report.toString(), 2, report.size)
            assertTrue(report.toString(), report[0].contains("失败"))
            assertTrue(report.toString(), report[1].contains("已开启"))
            assertEquals("ping-mixed", roundTrip(18082, "ping-mixed"))
        } finally {
            session.disconnect()
            socks.close()
            echo.close()
        }
    }
}
