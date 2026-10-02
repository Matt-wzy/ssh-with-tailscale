// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Matt <matt@mattsgateway.dpdns.org>
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version. See LICENSE for the full text.

package org.dpdns.mattsgateway.sshwithtailscale

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpProgressMonitor
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.Vector

/** A remote file or directory as returned by the SFTP `ls` command. */
data class RemoteEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long
) {
    /** Human-readable size; directories show nothing. */
    val displaySize: String get() = if (isDir) "" else humanSize(size)
}

/** Format [bytes] as e.g. "12.3 MB" (decimal-ish, base-1024). */
fun humanSize(bytes: Long): String {
    if (bytes < 0) return "-"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024.0 && i < units.lastIndex) {
        v /= 1024.0
        i++
    }
    return if (i == 0) "$bytes $units[0]" else String.format(Locale.US, "%.1f %s", v, units[i])
}

/** Join a parent path with a child name, tolerating a leading slash / ".". */
fun joinPath(parent: String, name: String): String {
    val p = parent.trimEnd('/')
    return if (p.isEmpty() || p == ".") name else "$p/$name"
}

/**
 * An SFTP session plus a single [ChannelSftp].
 *
 * For *browsing* the file manager reuses the live shell's transport
 * ([fromSession], [owned] = false) so listing costs no extra login. For *bulk
 * transfers* each worker opens its own connection ([connect]) -- several of
 * them run at once, which is what makes multi-file up/download truly parallel.
 */
class SftpLink private constructor(
    private val session: Session?,
    val channel: ChannelSftp,
    private val owned: Boolean
) : Closeable {
    companion object {
        /** Brand-new SSH session + SFTP channel. Caller owns both. */
        fun connect(cfg: SshConfig, timeoutMs: Int = 20_000): SftpLink {
            val s = SshConnector.session(cfg, timeoutMs)
            try {
                val ch = s.openChannel("sftp") as ChannelSftp
                ch.connect(timeoutMs)
                return SftpLink(s, ch, true)
            } catch (e: Exception) {
                runCatching { s.disconnect() }
                throw e
            }
        }

        /** Reuse an already-authenticated session (e.g. the running shell). */
        fun fromSession(session: Session, timeoutMs: Int = 20_000): SftpLink {
            val ch = session.openChannel("sftp") as ChannelSftp
            ch.connect(timeoutMs)
            return SftpLink(null, ch, false)
        }
    }

    /** Absolute path of the server-side working directory. */
    val pwd: String get() = channel.pwd()

    /** List a directory, sorted dirs-first then by name (case-insensitive). */
    fun ls(path: String): List<RemoteEntry> {
        val raw = channel.ls(path) as Vector<*>
        return raw.mapNotNull { e ->
            val ee = e as ChannelSftp.LsEntry
            val name = ee.filename
            if (name == "." || name == "..") return@mapNotNull null
            val a = ee.attrs
            val dir = a.isDir
            RemoteEntry(
                name = name,
                path = joinPath(path, name),
                isDir = dir,
                size = if (dir) 0L else a.getSize(),
                mtime = a.getMTime().toLong()
            )
        }.sortedWith(
            compareByDescending<RemoteEntry> { it.isDir }.thenBy { it.name.lowercase() }
        )
    }

    /** Stat a single path, or null if it does not exist. */
    fun stat(path: String): com.jcraft.jsch.SftpATTRS? =
        runCatching { channel.stat(path) }.getOrNull()

    /** (fileCount, byteCount) for [paths], recursing into directories. */
    fun du(vararg paths: String, limit: Int = 3000): Pair<Int, Long> {
        var files = 0
        var bytes = 0L
        val stack = ArrayList<String>(paths.toList())
        while (stack.isNotEmpty()) {
            val p = stack.removeAt(stack.lastIndex)
            val attr = stat(p) ?: continue
            if (attr.isDir) {
                if (files < limit) stack.addAll(ls(p).map { it.path })
            } else {
                files++
                bytes += attr.getSize()
                if (files > limit) break
            }
        }
        return files to bytes
    }

    fun mkdir(path: String) = channel.mkdir(path)
    fun rmdir(path: String) = channel.rmdir(path)
    fun rm(path: String) = channel.rm(path)
    fun rename(from: String, to: String) = channel.rename(from, to)

    /** Recursively delete [path] (a file or a directory tree). */
    fun rmRecursive(path: String) {
        val attr = stat(path) ?: return
        if (attr.isDir) {
            for (e in ls(path)) rmRecursive(e.path)
            runCatching { channel.rmdir(path) }
        } else {
            channel.rm(path)
        }
    }

    /**
     * Download [remotePath] to [dest]. [onProgress] reports cumulative bytes;
     * [cancelled] returning true aborts the transfer mid-stream.
     */
    fun download(
        remotePath: String,
        dest: File,
        onProgress: ((Long) -> Unit)? = null,
        cancelled: () -> Boolean = { false }
    ) {
        dest.parentFile?.mkdirs()
        FileOutputStream(dest).use { out -> download(remotePath, out, onProgress, cancelled) }
    }

    /**
     * Download [remotePath] into an already-open [out] stream (e.g. a SAF
     * `DocumentFile` opened against a user-chosen shared folder). This is what
     * lets the file manager write downloads to a configurable location instead
     * of a fixed app-private directory.
     */
    fun download(
        remotePath: String,
        out: OutputStream,
        onProgress: ((Long) -> Unit)? = null,
        cancelled: () -> Boolean = { false }
    ) {
        // JSch 0.1.55 has no 4-arg OutputStream get(); the 5-arg one takes a
        // byte offset ("skip") which is 0 for a fresh download.
        channel.get(remotePath, out, ProgressMonitor(onProgress, cancelled), ChannelSftp.OVERWRITE, 0L)
    }

    /**
     * Upload from [source] to [remotePath]. [onProgress] reports cumulative
     * bytes; [cancelled] returning true aborts the transfer mid-stream.
     */
    fun upload(
        source: InputStream,
        remotePath: String,
        onProgress: ((Long) -> Unit)? = null,
        cancelled: () -> Boolean = { false }
    ) {
        source.use {
            channel.put(it, remotePath, ProgressMonitor(onProgress, cancelled), ChannelSftp.OVERWRITE)
        }
    }

    override fun close() {
        runCatching { channel.disconnect() }
        if (owned) runCatching { session?.disconnect() }
    }
}

/** Bridges JSch's byte counting into our callbacks, and honours cancellation. */
private class ProgressMonitor(
    private val onProgress: ((Long) -> Unit)?,
    private val cancelled: () -> Boolean
) : SftpProgressMonitor {
    private var done: Long = 0
    @Volatile private var stopped = false

    override fun init(op: Int, src: String?, dst: String?, max: Long) {}
    override fun count(bytes: Long): Boolean {
        if (stopped) return false
        if (cancelled()) {
            stopped = true
            return false
        }
        done += bytes
        onProgress?.invoke(done)
        return true
    }

    override fun end() {}
}

/** A single download/upload job derived from a remote (file) selection. */
data class FileJob(val remotePath: String, val localRel: String, val size: Long)

/** The expansion of a multi-selection into concrete directories and files. */
data class TransferPlan(val dirs: List<String>, val files: List<FileJob>)

/**
 * Expand a multi-selection ([names] relative to [dir]) into the list of
 * directories to create and files to transfer, recursing into directories.
 *
 * [localRel] keeps each entry's position relative to [dir], so a selection of
 * `logs` and `a.txt` under `/tmp/data` becomes `logs/...` and `a.txt` locally.
 * [limit] caps the number of files so a giant tree cannot OOM the phone.
 */
fun buildPlan(link: SftpLink, dir: String, names: List<String>, limit: Int = 5000): TransferPlan {
    val dirs = ArrayList<String>()
    val files = ArrayList<FileJob>()
    val stack = ArrayDeque<Pair<String, String>>() // (remotePath, localRel)
    for (n in names) stack.addLast(joinPath(dir, n) to n)
    while (stack.isNotEmpty() && files.size < limit) {
        val (rp, rel) = stack.removeLast()
        val attr = link.stat(rp) ?: continue
        if (attr.isDir) {
            dirs.add(rel)
            for (e in (runCatching { link.ls(rp) }.getOrElse { emptyList() })) {
                stack.addLast(e.path to "$rel/${e.name}")
            }
        } else {
            files.add(FileJob(rp, rel, attr.getSize()))
        }
    }
    return TransferPlan(dirs, files)
}
