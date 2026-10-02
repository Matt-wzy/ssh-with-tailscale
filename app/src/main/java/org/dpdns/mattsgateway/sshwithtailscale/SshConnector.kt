// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Matt <matt@mattsgateway.dpdns.org>
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version. See LICENSE for the full text.

package org.dpdns.mattsgateway.sshwithtailscale

import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.ProxySOCKS5
import com.jcraft.jsch.Session

/**
 * Opens an SSH transport through the in-process Tailscale SOCKS5 proxy.
 *
 * This is the single place that knows how to authenticate (password or PEM),
 * point JSch at [TailscaleManager.SOCKS_HOST]/[TailscaleManager.SOCKS_PORT], and
 * tune keepalives. Both the shell (SshSession) and the file manager go through
 * here so the two never drift apart.
 */
object SshConnector {

    /**
     * Establish a fresh SSH session. The caller owns it and must [Session.disconnect].
     */
    fun session(cfg: SshConfig, timeoutMs: Int = 20_000): Session {
        val jsch = JSch()
        if (cfg.privateKeyPem.isNotBlank()) {
            // The nulls are public-key and passphrase-less key material; matching
            // SshSession.connect(), which loads keys the same way.
            jsch.addIdentity("android-key", cfg.privateKeyPem.toByteArray(), null, null)
        }
        // TOFU host key checking once KnownHosts is initialised (accept-all in
        // the JVM unit tests, where it never is).
        KnownHosts.apply(jsch)
        val s = jsch.getSession(cfg.user, cfg.host, cfg.port)
        // Everything goes through the in-process Tailscale node, exactly like the
        // interactive shell does.
        s.setProxy(ProxySOCKS5(TailscaleManager.SOCKS_HOST, TailscaleManager.SOCKS_PORT))
        s.setConfig("StrictHostKeyChecking", KnownHosts.strictness())
        if (cfg.password.isNotBlank()) s.setPassword(cfg.password)
        // See SshSession for why these two are coupled: setServerAliveInterval()
        // also sets the socket read timeout to the same value, so do NOT assign
        // s.timeout afterwards.
        s.setServerAliveInterval(30_000)
        s.setServerAliveCountMax(3)
        try {
            s.connect(timeoutMs)
        } catch (e: JSchException) {
            throw hostKeyFailure(cfg.host, e)
        }
        return s
    }

    /**
     * A TOFU host key mismatch surfaces as JSch's cryptic "reject HostKey: x".
     * Translate it so the terminal tells the user what happened and how to
     * recover (a reinstall or an impostor; clear the fingerprint if it is legit).
     */
    fun hostKeyFailure(host: String, e: JSchException): JSchException =
        if (e.message?.contains("reject HostKey") == true) {
            JSchException(
                "主机密钥校验失败：$host 的密钥与首次连接时记录的不一致" +
                    "（服务器可能重装过，也可能存在中间人）。" +
                    "确认无误后，可在“SSH 密钥”管理中清除主机指纹再重试。"
            )
        } else e

    /** Single-quote [s] so it survives the remote shell untouched. */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
