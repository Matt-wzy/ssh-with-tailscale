// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Matt <matt@mattsgateway.dpdns.org>
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version. See LICENSE for the full text.

package org.dpdns.mattsgateway.sshwithtailscale

import android.util.Log
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Logger
import com.jcraft.jsch.ProxySOCKS5
import com.jcraft.jsch.Session
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * An interactive SSH shell session (PTY), tunnelled through the Tailscale
 * SOCKS5 proxy, so it works without the Android VpnService.
 */
class SshSession(
    private val onData: (String) -> Unit,
    private val onClosed: (String?) -> Unit
) {
    // @Volatile: set on the connect thread, read/written from the reader,
    // writer and UI threads.
    @Volatile
    private var session: Session? = null
    @Volatile
    private var channel: ChannelShell? = null
    @Volatile
    private var out: OutputStream? = null

    @Volatile
    private var running = false

    /**
     * Set by [disconnect] so the reader thread does not fire [onClosed] for a
     * close the caller asked for. Without this, replacing a session (e.g. on
     * "Reconnect") would let the *old* reader callback clobber the new one.
     */
    @Volatile
    private var closedByUser = false

    /** Work for the writer thread: bytes to send, or a PTY resize. */
    private sealed interface Cmd {
        class Data(val bytes: ByteArray) : Cmd
        class Resize(val cols: Int, val rows: Int) : Cmd
    }

    /**
     * Every channel write is queued and drained by a dedicated thread.
     *
     * This is not an optimisation, it is a bug fix. Android's StrictMode aborts
     * socket writes made on the main thread with NetworkOnMainThreadException,
     * and both input paths run there: the IME callback (commitText) and the
     * modifier-key buttons. JSch's channel output stream reacts to any write
     * failure by *closing the channel*, so the very first keystroke destroyed
     * the session -- which then looked like a spontaneous disconnect a few
     * seconds later. Window changes (setPtySize) go through the same queue
     * because they write to the socket too and silently failed before, leaving
     * the server wrapping its output at a stale width.
     */
    private val queue = LinkedBlockingQueue<Cmd>()
    @Volatile
    private var writer: Thread? = null

    val isConnected: Boolean get() = running

    /**
     * The underlying JSch [Session], exposed so the file manager can open an
     * SFTP channel on the same authenticated transport (no second login).
     * Null once disconnected.
     */
    fun jschSession(): Session? = session

    fun connect(
        host: String,
        port: Int,
        user: String,
        password: String,
        privateKeyPem: String,
        cols: Int,
        rows: Int
    ) {
        closedByUser = false
        queue.clear()
        val jsch = JSch()
        if (BuildConfig.DEBUG) {
            // JSch's own logging stops once authentication is done, but the
            // forwarding work happens at the channel level -- which is exactly
            // where it got stuck in the field. Log everything while debugging.
            runCatching {
                JSch.setLogger(object : Logger {
                    override fun isEnabled(level: Int) = true
                    override fun log(level: Int, message: String) {
                        when (level) {
                            Logger.FATAL, Logger.ERROR -> Log.e("JSch", message)
                            Logger.WARN -> Log.w("JSch", message)
                            else -> Log.d("JSch", message)
                        }
                    }
                })
            }
        }
        if (privateKeyPem.isNotBlank()) {
            jsch.addIdentity("android-key", privateKeyPem.toByteArray(), null, null)
        }
        val s = jsch.getSession(user, host, port)
        // Everything goes through the in-process Tailscale node.
        s.setProxy(ProxySOCKS5(TailscaleManager.SOCKS_HOST, TailscaleManager.SOCKS_PORT))
        // TOFU host key checking once KnownHosts is initialised (accept-all in
        // the JVM unit tests, where it never is).
        KnownHosts.apply(jsch)
        s.setConfig("StrictHostKeyChecking", KnownHosts.strictness())
        if (password.isNotBlank()) s.setPassword(password)
        // JSch ties these two together: setServerAliveInterval() also calls
        // setTimeout(interval), i.e. it sets the socket read timeout to the same
        // value. So do NOT assign s.timeout afterwards. CountMax defaults to 1
        // (a single unanswered keepalive tears the session down), hence the
        // explicit 3.
        s.setServerAliveInterval(30_000)
        s.setServerAliveCountMax(3)
        try {
            s.connect(15_000)
        } catch (e: JSchException) {
            throw SshConnector.hostKeyFailure(host, e)
        }

        // From here the transport is up: if the channel setup fails, disconnect
        // it or the session leaks its socket and reader threads.
        try {
            val ch = s.openChannel("shell") as ChannelShell
            ch.setPty(true)
            // JSch defaults the PTY type to "vt100" (ChannelSession.ttype), which
            // makes the remote ncurses/readline use an ancient terminal description;
            // on the device that showed up as bytes that do not decode as UTF-8
            // arriving after every cursor move, which our parser then printed and
            // which pushed the cursor along. Ask for the same modern terminal a
            // normal SSH client asks for. (setPtyType only records the fields, so
            // setPty(true) above is still required.)
            ch.setPtyType("xterm-256color", cols, rows, 0, 0)
            val outs = ch.outputStream
            val ins = ch.inputStream
            ch.connect(15_000)

            session = s
            channel = ch
            out = outs
            running = true

            writer = thread(name = "ssh-writer") {
                while (true) {
                    val cmd = try {
                        queue.take()
                    } catch (e: InterruptedException) {
                        break
                    }
                    try {
                        when (cmd) {
                            is Cmd.Data -> {
                                out?.write(cmd.bytes)
                                out?.flush()
                            }
                            is Cmd.Resize -> channel?.setPtySize(cmd.cols, cmd.rows, 0, 0)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "channel I/O failed: ${e.message}")
                        break
                    }
                }
            }

            thread(name = "ssh-reader") {
                // UTF-8 is multibyte and a single read() can split a character in
                // half. Decode incrementally so a split sequence is carried over to
                // the next chunk (emitting U+FFFD otherwise) -- the MOTD and most
                // command output here contain CJK text.
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                val bytes = ByteBuffer.allocate(8192 + 8)
                val chars = CharBuffer.allocate(9000)
                val readBuf = ByteArray(8192)
                var reason: String? = null
                try {
                    while (running) {
                        val n = ins.read(readBuf)
                        if (n < 0) {
                            reason = "服务器关闭了连接"
                            Log.w(TAG, "ssh reader: EOF from the server")
                            break
                        }
                        if (n == 0) continue
                        if (BuildConfig.DEBUG && n <= 96) {
                            // Diagnostics: the exact bytes, so bytes that do not
                            // decode as UTF-8 can be identified.
                            Log.d("SshRxHex", readBuf.copyOfRange(0, n).joinToString(" ") { "%02x".format(it) })
                        }
                        bytes.put(readBuf, 0, n)
                        bytes.flip()
                        chars.clear()
                        decoder.decode(bytes, chars, false)
                        chars.flip()
                        if (chars.hasRemaining()) {
                            val s = chars.toString()
                            if (BuildConfig.DEBUG) {
                                // Diagnostics: whatever the server actually sent,
                                // with control bytes made visible. Capped so the
                                // MOTD cannot flood the log.
                                Log.d(
                                    "SshRx",
                                    s.take(400)
                                        .replace("\u001b", "\\e")
                                        .replace("\r", "\\r")
                                        .replace("\n", "\\n")
                                )
                            }
                            onData(s)
                        }
                        bytes.compact()
                    }
                } catch (e: Exception) {
                    reason = e.message ?: e.javaClass.simpleName
                    Log.w(TAG, "ssh reader ended: $reason", e)
                } finally {
                    running = false
                    // A server-side close used to leak the writer thread (blocked
                    // forever in queue.take(), since only disconnect() interrupts
                    // it) plus the JSch transport and any bound forward listeners:
                    // the UI only nulls its reference in onClosed, it never calls
                    // disconnect(). Tear everything down here so both close paths
                    // end in the same place.
                    clearForwards()
                    writer?.interrupt()
                    writer = null
                    runCatching { channel?.disconnect() }
                    runCatching { session?.disconnect() }
                    channel = null
                    session = null
                    out = null
                    // A user-initiated disconnect is already reflected in the UI;
                    // reporting it again would point at the wrong session.
                    if (!closedByUser) onClosed(reason)
                }
            }
        } catch (e: Exception) {
            runCatching { s.disconnect() }
            throw e
        }
    }

    /** Queue bytes for the writer thread. Safe to call from the main thread. */
    fun write(bytes: ByteArray) {
        if (!running) {
            Log.w(TAG, "write ignored: session is not running")
            return
        }
        queue.offer(Cmd.Data(bytes))
    }

    fun write(text: String) = write(text.toByteArray())

    /** Queue a PTY resize. Safe to call from the main thread. */
    fun resize(cols: Int, rows: Int) {
        if (!running) return
        queue.offer(Cmd.Resize(cols, rows))
    }

    // -------------------------------------------------------- port forwarding

    /** Rules that are currently installed, so they can be removed again. */
    private val installedForwards = mutableListOf<ForwardRule>()

    /** Local forwards run through our own forwarder, not JSch's watcher. */
    private val localForwarders = mutableListOf<LocalForwarder>()

    /** Remote forwards dial the target through the Tailscale node. */
    private val socksFactory = Socks5SocketFactory(
        TailscaleManager.SOCKS_HOST,
        TailscaleManager.SOCKS_PORT
    ) { message -> Log.w(TAG, "socks: $message") }

    /**
     * Replaces the live session's forwarding rules and reports one line per rule.
     *
     * Called after every connect (so rules survive a reconnect) and whenever the
     * rule list changes.
     */
    fun applyForwards(rules: List<ForwardRule>): List<String> {
        // Switched off -- see ForwardRule.ENABLED.
        if (!ForwardRule.ENABLED) return emptyList()
        val s = session ?: return listOf("[转发] 未连接，规则未生效")
        clearForwards()
        val report = mutableListOf<String>()
        for (r in rules) {
            try {
                if (r.remote) {
                    // The server listens; we dial the target, through Tailscale
                    // so tailnet addresses work as well as local ones.
                    s.setPortForwardingR(r.bindHost, r.bindPort, r.targetHost, r.targetPort, socksFactory)
                } else {
                    // Our own implementation -- see LocalForwarder for why JSch's
                    // setPortForwardingL is not used here.
                    val forwarder = LocalForwarder(
                        session = s,
                        bindHost = r.bindHost,
                        bindPort = r.bindPort,
                        targetHost = r.targetHost,
                        targetPort = r.targetPort,
                        onError = { message -> Log.w(TAG, "forward: $message") }
                    )
                    forwarder.start()
                    localForwarders.add(forwarder)
                }
                installedForwards.add(r)
                report.add("[转发] ${r.label()}  已开启")
            } catch (e: Exception) {
                report.add("[转发] ${r.label()}  失败：${e.message}")
            }
        }
        return report
    }

    private fun clearForwards() {
        localForwarders.forEach { it.stop() }
        localForwarders.clear()
        val s = session ?: return
        for (r in installedForwards) {
            runCatching {
                if (r.remote) s.delPortForwardingR(r.bindHost, r.bindPort)
                else s.delPortForwardingL(r.bindHost, r.bindPort)
            }
        }
        installedForwards.clear()
    }

    fun disconnect() {
        closedByUser = true
        running = false
        writer?.interrupt()
        writer = null
        queue.clear()
        clearForwards()
        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }
        channel = null
        session = null
        out = null
    }

    companion object {
        private const val TAG = "SshSession"

        init {
            // Route JSch's own logging to logcat. Without this a dropped
            // connection leaves no clue whether it was auth, a keepalive
            // timeout, or the server closing the channel.
            JSch.setLogger(object : com.jcraft.jsch.Logger {
                override fun isEnabled(level: Int) = level >= com.jcraft.jsch.Logger.INFO
                override fun log(level: Int, message: String) {
                    if (level >= com.jcraft.jsch.Logger.ERROR) Log.e("JSch", message)
                    else Log.w("JSch", message)
                }
            })
        }
    }
}
