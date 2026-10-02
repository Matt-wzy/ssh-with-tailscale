// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Matt <matt@mattsgateway.dpdns.org>
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version. See LICENSE for the full text.

package org.dpdns.mattsgateway.sshwithtailscale

import android.content.Context
import android.util.Base64
import android.util.Log
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.UserInfo
import java.io.File

/**
 * Trust-on-first-use host key verification.
 *
 * Until now every connection ran with StrictHostKeyChecking=no, so a compromised
 * tailnet node could impersonate an SSH server. A full known_hosts UX (prompt on
 * first connect, compare fingerprints) does not fit this app's flow, but TOFU
 * does: the first key seen for a host is recorded in a private file, later
 * connections must present the same key, and a *changed* key of the same type
 * fails the connection with a message that says how to recover.
 *
 * A host that later starts offering a *new* key type (e.g. the server admin adds
 * an ed25519 host key next to the RSA one) is recorded, not rejected -- same
 * behaviour as OpenSSH. Nothing here runs until [init] gives it a file, so the
 * JVM unit tests (no Android filesystem) keep the old accept-all behaviour.
 */
object KnownHosts {

    private const val TAG = "KnownHosts"

    @Volatile
    private var file: File? = null

    /** Point the repository at the app-private known_hosts file. */
    fun init(context: Context) {
        if (file == null) {
            file = File(context.filesDir, "known_hosts")
        }
    }

    /** True when at least one host fingerprint has been recorded. */
    fun exists(): Boolean = file?.let { it.exists() && it.length() > 0L } ?: false

    /** Forget every recorded fingerprint; the next connection records anew. */
    fun clear(): Boolean = file?.delete() ?: false

    /**
     * The StrictHostKeyChecking value to configure on a JSch session: strict
     * once the repository is available, accept-all otherwise (unit tests only).
     */
    fun strictness(): String = if (file != null) "yes" else "no"

    /** Install the TOFU repository on a fresh [JSch] instance. No-op un-initialised. */
    fun apply(jsch: JSch) {
        val f = file ?: return
        jsch.setHostKeyRepository(TofuRepository(f))
    }
}

/** One line per (host, key type, key blob), tab-separated. */
private class TofuRepository(private val file: File) : HostKeyRepository {

    private val tag = "KnownHosts"

    private fun readAll(): MutableMap<String, MutableList<Pair<String, String>>> {
        val map = HashMap<String, MutableList<Pair<String, String>>>()
        runCatching {
            if (file.exists()) {
                file.readLines().forEach { line ->
                    val parts = line.split('\t')
                    if (parts.size == 3) {
                        map.getOrPut(parts[0]) { mutableListOf() }.add(parts[1] to parts[2])
                    }
                }
            }
        }.onFailure { Log.w(tag, "known_hosts 读取失败：${it.message}") }
        return map
    }

    private fun persist(map: Map<String, List<Pair<String, String>>>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(map.entries.joinToString("\n") { (host, keys) ->
                keys.joinToString("\n") { (type, key) -> "$host\t$type\t$key" }
            })
        }.onFailure { Log.w(tag, "known_hosts 写入失败：${it.message}") }
    }

    override fun check(host: String?, key: ByteArray?): Int {
        if (host.isNullOrEmpty() || key == null) return HostKeyRepository.NOT_INCLUDED
        val hk = try {
            HostKey(host, key)
        } catch (e: JSchException) {
            // Cannot even parse the server's key blob: refuse rather than
            // silently accept something we cannot record and compare later.
            Log.w(tag, "主机密钥解析失败：${e.message}")
            return HostKeyRepository.NOT_INCLUDED
        }
        synchronized(this) {
            val all = readAll()
            val entries = all[host]
            return when {
                entries == null -> {
                    all[host] = mutableListOf(hk.type to hk.key)
                    persist(all)
                    Log.i(tag, "TOFU: 首次记录 $host 的 ${hk.type} 主机密钥")
                    HostKeyRepository.OK
                }
                entries.none { it.first == hk.type } -> {
                    entries.add(hk.type to hk.key)
                    persist(all)
                    Log.i(tag, "TOFU: $host 新增 ${hk.type} 主机密钥")
                    HostKeyRepository.OK
                }
                entries.any { it.first == hk.type && it.second == hk.key } -> HostKeyRepository.OK
                else -> {
                    Log.w(tag, "$host 的 ${hk.type} 主机密钥与记录不一致，拒绝连接")
                    HostKeyRepository.CHANGED
                }
            }
        }
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit

    override fun remove(host: String?, type: String?) {
        synchronized(this) {
            val all = readAll()
            if (all.remove(host) != null) persist(all)
        }
    }

    override fun remove(host: String?, type: String?, key: ByteArray?) {
        synchronized(this) {
            val all = readAll()
            val entries = all[host] ?: return
            val blob = key?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
            entries.removeAll { it.first == type && (blob == null || it.second == blob) }
            persist(all)
        }
    }

    override fun getKnownHostsRepositoryID(): String = file.path

    override fun getHostKey(): Array<HostKey> = getHostKey(null, null)

    override fun getHostKey(host: String?, type: String?): Array<HostKey> {
        synchronized(this) {
            val all = readAll()
            val out = ArrayList<HostKey>()
            for ((h, keys) in all) {
                if (host != null && h != host) continue
                for ((t, b64) in keys) {
                    if (type != null && t != type) continue
                    runCatching {
                        out.add(HostKey(h, Base64.decode(b64, Base64.NO_WRAP)))
                    }
                }
            }
            return out.toTypedArray()
        }
    }
}
