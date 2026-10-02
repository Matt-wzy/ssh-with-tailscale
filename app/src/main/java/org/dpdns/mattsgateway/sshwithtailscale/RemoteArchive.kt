// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Matt <matt@mattsgateway.dpdns.org>
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version. See LICENSE for the full text.

package org.dpdns.mattsgateway.sshwithtailscale

import android.util.Log
import com.jcraft.jsch.ChannelExec
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.concurrent.thread

/**
 * Downloads many small files at once by having the *remote server* roll them
 * into a single gzip tarball, then streaming that archive to the phone.
 *
 * Each file fetched over SFTP is its own round-trip; a directory of a few
 * hundred tiny files is therefore orders of magnitude slower than shipping one
 * tarball. `tar` is essentially universal on Linux servers, so we lean on it
 * and decompress locally (see [TarExtractor]).
 */
object RemoteArchive {

    private const val TAG = "RemoteArchive"

    /**
     * Stream `tar -czf - -C <dir> -- <names>` from the server into [archive].
     *
     * Tries `tar -czf` first; if the server lacks gzip it falls back to
     * `tar -cf ... | gzip`. Throws (with the captured stderr) if every attempt
     * fails or produces no data -- callers should then fall back to per-file
     * downloads.
     */
    @Throws(Exception::class)
    fun download(
        cfg: SshConfig,
        dir: String,
        names: List<String>,
        archive: File,
        onBytes: (Long) -> Unit = {},
        cancelled: () -> Boolean = { false }
    ): Long {
        val dirQ = SshConnector.shellQuote(dir)
        val namesQ = names.joinToString(" ") { SshConnector.shellQuote(it) }
        val attempts = listOf(
            "tar -czf - -C $dirQ -- $namesQ",
            "tar -cf - -C $dirQ -- $namesQ | gzip -9"
        )
        var lastErr = ""
        for (cmd in attempts) {
            runCatching {
                val bytes = streamCommand(cfg, cmd, archive, onBytes, cancelled)
                if (bytes > 0) return bytes
                lastErr = "远程命令未返回任何数据"
            }.onFailure { e -> lastErr = e.message ?: e.toString() }
        }
        throw IllegalStateException(
            "服务端打包失败（可能未安装 tar/gzip）：\n$lastErr"
        )
    }

    private fun streamCommand(
        cfg: SshConfig,
        command: String,
        archive: File,
        onBytes: (Long) -> Unit,
        cancelled: () -> Boolean
    ): Long {
        val session = SshConnector.session(cfg)
        var total = 0L
        try {
            val ch = session.openChannel("exec") as ChannelExec
            ch.setCommand(command)
            val stdout: InputStream = ch.inputStream
            // Drain stderr on its own thread: if we never read it and it fills
            // the channel's pipe, the whole exec stalls.
            val err = StringBuilder()
            val drain = thread(name = "tar-stderr") {
                runCatching {
                    ch.errStream.bufferedReader().use { r -> var line: String?
                        while (r.readLine().also { line = it } != null) {
                            if (err.length < 4000) err.append(line).append('\n')
                        }
                    }
                }
            }
            ch.connect(20_000)

            archive.parentFile?.mkdirs()
            FileOutputStream(archive).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (cancelled()) break
                    val n = stdout.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    out.write(buf, 0, n)
                    total += n
                    onBytes(total)
                }
            }

            // Give the channel time to finish and report its exit status.
            var waited = 0
            while (!ch.isClosed && waited < 10_000) {
                Thread.sleep(50)
                waited += 50
            }
            val status = ch.getExitStatus()
            runCatching { ch.disconnect() }
            runCatching { drain.join(2000) }

            if (status != 0) {
                throw IllegalStateException(err.toString().trim().ifBlank { "tar 退出码 $status" })
            }
            return total
        } finally {
            runCatching { session.disconnect() }
        }
    }
}
