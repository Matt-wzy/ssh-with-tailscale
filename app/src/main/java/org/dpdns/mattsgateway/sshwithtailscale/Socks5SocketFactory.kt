// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Matt <matt@mattsgateway.dpdns.org>
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version. See LICENSE for the full text.

package org.dpdns.mattsgateway.sshwithtailscale

import com.jcraft.jsch.SocketFactory
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A JSch [SocketFactory] whose sockets are dialled through the in-process
 * Tailscale SOCKS5 proxy instead of the phone's normal network.
 *
 * Needed for remote forwards (-R): the far side accepts a connection and hands
 * it to us, and *we* have to dial the target -- which may be another tailnet node
 * that only the Tailscale node can reach. JSch's default would use a plain
 * socket on the system network and fail.
 *
 * The hostname is passed through as a SOCKS5 domain (ATYP 0x03) so the far side
 * resolves it; tailnet MagicDNS names only resolve there.
 */
class Socks5SocketFactory(
    private val proxyHost: String,
    private val proxyPort: Int,
    /** JSch swallows everything thrown from here, so log before throwing. */
    private val onLog: ((String) -> Unit)? = null
) : SocketFactory {

    override fun createSocket(host: String, port: Int): Socket = createSocket(host, port, 0)

    // Plain overload (the JSch interface only has the two-argument form).
    fun createSocket(host: String, port: Int, timeout: Int): Socket {
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.connect(
            InetSocketAddress(proxyHost, proxyPort),
            if (timeout > 0) timeout else CONNECT_TIMEOUT_MS
        )
        if (timeout > 0) raw.soTimeout = timeout
        val input = raw.getInputStream()
        val output = raw.getOutputStream()

        try {
            onLog?.invoke("已连上代理 $proxyHost:$proxyPort，开始握手")
            // Greeting: offer "no authentication" only.
            output.write(byteArrayOf(0x05, 0x01, 0x00))
            output.flush()
            val greeting = ByteArray(2)
            readFully(input, greeting, 2)
            onLog?.invoke("代理回应 greetings: ver=${greeting[0]} method=${greeting[1]}")
            if (greeting[0] != 0x05.toByte()) throw IOException("SOCKS5: 不是 SOCKS5 代理")
            if (greeting[1] != 0x00.toByte()) throw IOException("SOCKS5: 代理要求认证")

            // CONNECT <host> <port>, host sent as a name.
            val hostBytes = host.toByteArray(Charsets.UTF_8)
            if (hostBytes.size > 255) throw IOException("SOCKS5: 主机名过长")
            val req = ByteArray(5 + hostBytes.size + 2)
            req[0] = 0x05; req[1] = 0x01 // VER, CMD=CONNECT
            req[2] = 0x00                // RSV
            req[3] = 0x03                // ATYP=domain
            req[4] = hostBytes.size.toByte()
            System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
            req[5 + hostBytes.size] = ((port shr 8) and 0xff).toByte()
            req[6 + hostBytes.size] = (port and 0xff).toByte()
            output.write(req)
            output.flush()

            val head = ByteArray(4)
            readFully(input, head, 4)
            onLog?.invoke("CONNECT 回应: ver=${head[0]} rep=${head[1]} atyp=${head[3]}  (目标 $host:$port)")
            if (head[1] != 0x00.toByte()) {
                throw IOException("SOCKS5: 连接被拒绝 (code ${head[1].toInt() and 0xff})")
            }
            // Consume the bound address that follows the reply header.
            when (head[3].toInt()) {
                0x01 -> readFully(input, ByteArray(6), 6)
                0x04 -> readFully(input, ByteArray(18), 18)
                0x03 -> {
                    val len = ByteArray(1)
                    readFully(input, len, 1)
                    readFully(input, ByteArray(len[0].toInt() + 2), len[0].toInt() + 2)
                }
                else -> throw IOException("SOCKS5: 回复地址类型非法")
            }
        } catch (e: Exception) {
            onLog?.invoke("握手失败: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { raw.close() }
            throw e
        }

        // JSch insists on a Socket, and it only ever uses the streams and close().
        return DelegateSocket(raw)
    }

    override fun getInputStream(socket: Socket?): InputStream? = socket?.getInputStream()

    override fun getOutputStream(socket: Socket?): OutputStream? = socket?.getOutputStream()

    private fun readFully(input: InputStream, buf: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw IOException("SOCKS5: 代理提前关闭连接")
            off += n
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
    }
}

/** Wraps an already-connected socket so it can be handed to JSch as a Socket. */
private class DelegateSocket(private val delegate: Socket) : Socket() {
    override fun getInputStream(): InputStream = delegate.getInputStream()
    override fun getOutputStream(): OutputStream = delegate.getOutputStream()
    override fun close() = delegate.close()
    override fun isConnected(): Boolean = delegate.isConnected
    override fun isClosed(): Boolean = delegate.isClosed
    override fun setSoTimeout(timeout: Int) {
        delegate.soTimeout = timeout
    }

    override fun getSoTimeout(): Int = delegate.soTimeout
}
