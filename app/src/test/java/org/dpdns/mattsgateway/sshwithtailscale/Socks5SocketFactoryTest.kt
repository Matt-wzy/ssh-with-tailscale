// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Matt <matt@mattsgateway.dpdns.org>
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version. See LICENSE for the full text.

package org.dpdns.mattsgateway.sshwithtailscale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The SOCKS5 handshake is the riskiest part of remote forwarding, and it needs no
 * Android APIs -- so it can be checked here against a fake proxy instead of
 * waiting for a device.
 */
class Socks5SocketFactoryTest {

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw IOException("eof")
            off += n
        }
    }

    @Test
    fun sendsTheTargetAsADomainNameAndReturnsAUsableSocket() {
        val socks = ServerSocket(0)
        val captured = AtomicReference<String>()
        val failure = AtomicReference<Throwable?>()

        val server = thread(name = "fake-socks") {
            try {
                socks.accept().use { s ->
                    val input = s.getInputStream()
                    val output = s.getOutputStream()

                    val greeting = ByteArray(3)
                    readFully(input, greeting)
                    assertEquals("offers no-auth only", listOf(0x05, 0x01, 0x00),
                        greeting.map { it.toInt() and 0xff })
                    output.write(byteArrayOf(0x05, 0x00)) // no auth accepted
                    output.flush()

                    // CONNECT request: VER, CMD, RSV, ATYP=domain, LEN, name, port.
                    val head = ByteArray(4)
                    readFully(input, head)
                    val lenByte = ByteArray(1)
                    readFully(input, lenByte)
                    val len = lenByte[0].toInt() and 0xff
                    val tail = ByteArray(len + 2)
                    readFully(input, tail)
                    val host = String(tail, 0, len, Charsets.UTF_8)
                    val port = ((tail[len].toInt() and 0xff) shl 8) or (tail[len + 1].toInt() and 0xff)
                    captured.set("${head[0]},${head[1]},${head[3]}|$host:$port")

                    // Success, with a dummy IPv4 bound address.
                    output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    output.write(0x42) // a byte we can read back
                    output.flush()
                }
            } catch (e: Throwable) {
                failure.set(e)
            }
        }

        val factory = Socks5SocketFactory("127.0.0.1", socks.localPort)
        val socket = factory.createSocket("nas.tailnet.ts.net", 8080)
        try {
            // Proves the returned Socket really carries the tunnelled streams.
            assertEquals(0x42, socket.getInputStream().read())
            // VER=5, CMD=CONNECT, ATYP=domain, name passed through for the far
            // side to resolve (tailnet MagicDNS only resolves there).
            assertEquals("5,1,3|nas.tailnet.ts.net:8080", captured.get())
            assertNull(failure.get())
        } finally {
            socket.close()
            server.join(2000)
            socks.close()
        }
    }

    @Test
    fun reportsAProxyThatRefusesTheHandshake() {
        val socks = ServerSocket(0)
        val server = thread(name = "fake-socks-refuse") {
            runCatching {
                socks.accept().use { s ->
                    val greeting = ByteArray(3)
                    readFully(s.getInputStream(), greeting)
                    s.getOutputStream().write(byteArrayOf(0x05, 0x02)) // username/password only
                    s.getOutputStream().flush()
                }
            }
        }

        val factory = Socks5SocketFactory("127.0.0.1", socks.localPort)
        try {
            factory.createSocket("host", 1)
            fail("should have thrown")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("认证"))
        } finally {
            server.join(2000)
            socks.close()
        }
    }

    @Test
    fun reportsARejectedConnect() {
        val socks = ServerSocket(0)
        val server = thread(name = "fake-socks-deny") {
            runCatching {
                socks.accept().use { s ->
                    val input = s.getInputStream()
                    val output = s.getOutputStream()
                    readFully(input, ByteArray(3))
                    output.write(byteArrayOf(0x05, 0x00)); output.flush()
                    val head = ByteArray(4)
                    readFully(input, head)
                    val lenByte = ByteArray(1)
                    readFully(input, lenByte)
                    readFully(input, ByteArray((lenByte[0].toInt() and 0xff) + 2))
                    output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0)) // 0x05 = refused
                    output.flush()
                }
            }
        }

        val factory = Socks5SocketFactory("127.0.0.1", socks.localPort)
        try {
            factory.createSocket("host", 1)
            fail("should have thrown")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("连接被拒绝"))
        } finally {
            server.join(2000)
            socks.close()
        }
    }
}
