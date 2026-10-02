// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Matt <matt@mattsgateway.dpdns.org>
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version. See LICENSE for the full text.

package org.dpdns.mattsgateway.sshwithtailscale

import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.webkit.MimeTypeMap
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale
import kotlin.concurrent.thread

/**
 * SFTP file manager: browse the remote host, upload (multi-select via SAF),
 * download (multi-file, parallel; fragmented selections can be tarballed on the
 * server first), new dir, delete, rename.
 *
 * It reuses the connection the user already established in [MainActivity] --
 * host/user/password come from [ActiveConnection], and browsing rides on the
 * live shell's SSH transport so there is no second login. Bulk up/download
 * spin up several independent SSH sessions that run at once.
 *
 * Recent fixes:
 *  - The path no longer snaps back to `~` after every action: [refresh] keeps
 *    the absolute directory we navigated to instead of overwriting it with the
 *    SFTP channel's cwd (which `ls(path)` never changes).
 *  - Upload and download can now run at the same time (a list of live transfers
 *    instead of a single slot), and the progress panel aggregates them.
 *  - The download destination is configurable via a system folder picker
 *    (SAF), persisted across launches -- no storage permission needed.
 *  - Progress now shows a live transfer speed.
 */
class FileManagerActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var pathText: TextView
    private lateinit var progressArea: android.view.View
    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var progressText: TextView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var adapter: EntryAdapter

    private var cfg: SshConfig? = null
    private var entries: List<RemoteEntry> = emptyList()
    private val selected = LinkedHashSet<String>()

    /** Directory we are currently viewing, always stored as an absolute path. */
    @Volatile private var currentPath = "."
    /** Resolved home directory (absolute), captured once from the SFTP channel. */
    private var homePath: String? = null
    /**
     * The directory the manager opened at. Back stops here (rather than at "/")
     * so a second press is needed to leave the screen.
     */
    private var initialPath: String? = null
    /** Timestamp of the last back press while already at [initialPath]. */
    private var lastBackPress = 0L
    private var browseLink: SftpLink? = null

    /** How the listing is ordered. Directories always stay on top. */
    private enum class SortKey { NAME, SIZE, TIME }
    private var sortKey = SortKey.NAME
    private var sortAsc = true

    private val scope = CoroutineScope(Dispatchers.Main.immediate + Job())

    /** Live transfers; more than one may run at once (e.g. upload + download). */
    private val transfers = Collections.synchronizedList(mutableListOf<Rep>())

    /** Number of concurrent transfers. */
    private val PARALLEL = 4

    /** Below these thresholds a selection looks like "fragmented" small files. */
    private val SMALL_FILE_MIN = 8
    private val SMALL_FILE_MAX_AVG = 256L * 1024

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    /** Where downloads go. Null => app-private Download dir (see [localTargetFor]). */
    private val dlPrefs: SharedPreferences by lazy { getSharedPreferences("fm", MODE_PRIVATE) }
    private var downloadTreeUri: Uri? = null

    private val pickLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) uploadUris(uris)
    }

    /** Lets the user pick a shared folder (e.g. Download/sshdownload) to download into. */
    private val dirPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> onPickDownloadDir(uri) }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_files)

        // In case this screen is the first thing after a process restore.
        KnownHosts.init(this)

        listView = findViewById(R.id.list)
        pathText = findViewById(R.id.pathText)
        progressArea = findViewById(R.id.progressArea)
        progressBar = findViewById(R.id.progressBar)
        progressText = findViewById(R.id.progressText)
        swipe = findViewById(R.id.swipe)
        swipe.setOnRefreshListener { refresh(showSpinner = true) }

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        findViewById<android.view.View>(R.id.btnUp).setOnClickListener { goUp() }
        findViewById<android.view.View>(R.id.btnHome).setOnClickListener { goHome() }
        findViewById<android.view.View>(R.id.btnDlDir).setOnClickListener { dirPickerLauncher.launch(null) }
        findViewById<android.view.View>(R.id.btnDlDir).setOnLongClickListener {
            downloadTreeUri = null
            dlPrefs.edit().remove(KEY_DL_TREE).apply()
            toast("已恢复默认下载目录（应用私有 Download/sshdownload）")
            true
        }
        findViewById<android.view.View>(R.id.btnUpload).setOnClickListener { pickLauncher.launch(arrayOf("*/*")) }
        findViewById<android.view.View>(R.id.btnDownload).setOnClickListener { startDownload() }
        findViewById<android.view.View>(R.id.btnMkdir).setOnClickListener { promptMkdir() }
        findViewById<android.view.View>(R.id.btnDelete).setOnClickListener { deleteSelected() }
        findViewById<android.view.View>(R.id.btnSelectAll).setOnClickListener { toggleSelectAll() }
        findViewById<android.view.View>(R.id.btnCancel).setOnClickListener { transfers.forEach { it.cancel() } }

        // Back gesture: step up one directory; at the starting directory a
        // second press within three seconds leaves the file manager.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val init = initialPath
                val atRoot = init == null || currentPath == "/" || currentPath == init
                if (!atRoot) {
                    goUp()
                } else if (SystemClock.elapsedRealtime() - lastBackPress < 3000) {
                    finish()
                } else {
                    lastBackPress = SystemClock.elapsedRealtime()
                    toast(getString(R.string.fm_back_hint))
                }
            }
        })

        adapter = EntryAdapter()
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, i, _ ->
            val e = entries[i]
            if (e.isDir) { currentPath = e.path; refresh() } else toggle(e)
        }
        listView.setOnItemLongClickListener { _, _, i, _ ->
            showItemMenu(entries[i]); true
        }

        downloadTreeUri = dlPrefs.getString(KEY_DL_TREE, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        sortKey = runCatching { SortKey.valueOf(dlPrefs.getString(KEY_SORT_KEY, SortKey.NAME.name)!!) }
            .getOrDefault(SortKey.NAME)
        sortAsc = dlPrefs.getBoolean(KEY_SORT_ASC, true)

        if (!TailscaleManager.isRunning()) {
            Toast.makeText(this, "请先在首页启动 Tailscale", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val live = ActiveConnection.config
        if (live != null && live.usable) {
            cfg = live
            toolbar.subtitle = live.user + "@" + live.host
            refresh()
        } else {
            val saved = ConnectionPrefs.load(this)
            if (saved.host.isBlank() || saved.user.isBlank()) {
                Toast.makeText(this, "请先在 SSH 页面连接一次，文件管理会自动复用该连接", Toast.LENGTH_LONG).show()
                finish()
                return
            }
            showPasswordDialog(saved.host, saved.port, saved.user)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        val link = browseLink
        browseLink = null
        if (link != null) thread { runCatching { link.close() } }
    }

    // ----------------------------------------------------------- navigation

    private fun goUp() {
        val p = currentPath
        currentPath = if (p == "/" || p.isEmpty()) "/" else p.substringBeforeLast('/', "/")
        refresh()
    }

    private fun goHome() {
        currentPath = "."
        refresh()
    }

    /** Reload the current directory.
     *
     *  [currentPath] is the directory we asked to list, kept verbatim across
     *  refreshes so the path bar does not snap back to `~`. We only use
     *  [SftpLink.pwd] once, to resolve the initial "." into an absolute home
     *  path. `ls(path)` never changes the channel's cwd, so re-reading `pwd`
     *  every time would wrongly reset us to home. */
    private fun refresh(showSpinner: Boolean = false) {
        if (showSpinner) swipe.isRefreshing = true
        scope.launch {
            withLink { link ->
                val home = homePath ?: link.pwd.also { homePath = it }
                val abs = if (currentPath == "." || currentPath.isEmpty()) home else currentPath
                val list = link.ls(abs)
                abs to list
            }.onSuccess { (abs, list) ->
                currentPath = abs
                if (initialPath == null) initialPath = abs
                pathText.text = abs
                entries = sortList(list)
                selected.retainAll(entries.map { it.path }.toSet())
                adapter.notifyDataSetChanged()
            }.onFailure { e -> showError("浏览失败", e.message ?: e.toString()) }
            swipe.isRefreshing = false
        }
    }

    /** Order a listing: directories always first, then by the chosen key/direction. */
    private fun sortList(list: List<RemoteEntry>): List<RemoteEntry> {
        val byName = compareBy<RemoteEntry> { it.name.lowercase(Locale.getDefault()) }
        val byKey: Comparator<RemoteEntry> = when (sortKey) {
            SortKey.NAME -> byName
            SortKey.SIZE -> compareBy { it.size }
            SortKey.TIME -> compareBy { it.mtime }
        }
        val ordered = if (sortAsc) byKey.then(byName) else byKey.reversed().then(byName)
        return list.sortedWith(compareByDescending<RemoteEntry> { it.isDir }.then(ordered))
    }

    // ------------------------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.files, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_sort -> { showSortDialog(); true }
        R.id.action_refresh -> { refresh(showSpinner = true); true }
        else -> super.onOptionsItemSelected(item)
    }

    /** Choose sort field and direction in a single tap (six combinations). */
    private fun showSortDialog() {
        val labels = arrayOf("名称 ↑", "名称 ↓", "大小 ↑", "大小 ↓", "修改时间 ↑", "修改时间 ↓")
        val keys = arrayOf(SortKey.NAME, SortKey.NAME, SortKey.SIZE, SortKey.SIZE, SortKey.TIME, SortKey.TIME)
        val checked = when (sortKey) {
            SortKey.NAME -> if (sortAsc) 0 else 1
            SortKey.SIZE -> if (sortAsc) 2 else 3
            SortKey.TIME -> if (sortAsc) 4 else 5
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fm_sort_title)
            .setSingleChoiceItems(labels, checked) { dlg, which ->
                sortKey = keys[which]
                sortAsc = which % 2 == 0
                dlPrefs.edit()
                    .putString(KEY_SORT_KEY, sortKey.name)
                    .putBoolean(KEY_SORT_ASC, sortAsc)
                    .apply()
                entries = sortList(entries)
                adapter.notifyDataSetChanged()
                dlg.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------- selection

    private fun toggle(e: RemoteEntry) {
        if (selected.contains(e.path)) selected.remove(e.path) else selected.add(e.path)
        adapter.notifyDataSetChanged()
    }

    private fun toggleSelectAll() {
        if (selected.size == entries.size) selected.clear()
        else selected.addAll(entries.map { it.path })
        adapter.notifyDataSetChanged()
    }

    // --------------------------------------------------------- credentials

    private fun showPasswordDialog(host: String, port: String, user: String) {
        val ctx = this
        val layout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val pwEdit = android.widget.EditText(ctx).apply {
            hint = "密码"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(pwEdit)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("连接 $user@$host（$port）")
            .setMessage("本页需要 SSH 凭据。填入密码后即可浏览文件，后续不再重复输入。")
            .setView(layout)
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ -> finish() }
            .setPositiveButton("连接") { _, _ ->
                val cfg = SshConfig(host, port.toIntOrNull() ?: 22, user, pwEdit.text.toString())
                this.cfg = cfg
                val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
                toolbar.subtitle = "$user@$host"
                refresh()
            }
            .show()
    }

    // ------------------------------------------------------------- browsing

    /** Run [block] on a browse link, reconnecting once on failure. */
    private suspend fun <T> withLink(block: (SftpLink) -> T): Result<T> = withContext(Dispatchers.IO) {
        val c = cfg ?: return@withContext Result.failure(IllegalStateException("未配置连接"))
        var link = browseLink
        if (link == null) { link = openLink(c); browseLink = link }
        try {
            Result.success(block(link))
        } catch (e: Exception) {
            runCatching { browseLink?.close() }
            browseLink = null
            try {
                val fresh = openLink(c)
                browseLink = fresh
                Result.success(block(fresh))
            } catch (e2: Exception) {
                runCatching { browseLink?.close() }
                browseLink = null
                Result.failure(e2)
            }
        }
    }

    private fun openLink(cfg: SshConfig): SftpLink {
        val live = ActiveConnection.liveSession
        return if (live != null && live.isConnected) {
            SftpLink.fromSession(live)
        } else {
            SftpLink.connect(cfg)
        }
    }

    // ------------------------------------------------------- download flow

    private fun startDownload() {
        val dir = currentPath
        val names = entries.filter { selected.contains(it.path) }.map { it.name }
        if (names.isEmpty()) { toast("先勾选要下载的文件 / 目录"); return }
        if (!TailscaleManager.isRunning()) { toast("Tailscale 未运行"); return }
        scope.launch {
            val (count, bytes) = withLink { link ->
                link.du(*names.map { n -> joinPath(dir, n) }.toTypedArray())
            }.getOrElse { 0 to 0L }
            withContext(Dispatchers.Main) { askDownloadMode(dir, names, count, bytes) }
        }
    }

    private fun askDownloadMode(dir: String, names: List<String>, count: Int, bytes: Long) {
        val target = localTargetFor(dir)
        val avg = if (count > 0) bytes / count else 0
        val fragmented = count >= SMALL_FILE_MIN && avg <= SMALL_FILE_MAX_AVG
        val builder = MaterialAlertDialogBuilder(this).setTitle("下载")
        if (fragmented) {
            builder.setMessage(
                "检测到 $count 个文件 · 共 ${humanSize(bytes)}（平均 ${humanSize(avg)}）。\n" +
                    "碎文件逐个下载每个都要一次往返，通常让服务器先打包成一个 tar.gz 再下载会快很多。\n" +
                    "保存到：${target.label}"
            )
            builder.setPositiveButton("打包下载（推荐）") { _, _ ->
                runTransfer("下载") { rep -> archiveDownload(dir, names, target, rep) }
            }
            builder.setNegativeButton("逐个并行下载") { _, _ ->
                runTransfer("下载") { rep -> parallelDownload(dir, names, target, rep) }
            }
            builder.setNeutralButton("取消", null)
        } else {
            builder.setMessage("下载 ${names.size} 项到：\n${target.label}")
            builder.setPositiveButton("下载") { _, _ ->
                runTransfer("下载") { rep -> parallelDownload(dir, names, target, rep) }
            }
            builder.setNegativeButton("取消", null)
        }
        builder.show()
    }

    private suspend fun parallelDownload(dir: String, names: List<String>, target: LocalTarget, rep: Rep): String {
        val c = cfg ?: return "未配置连接"
        val plan = withContext(Dispatchers.IO) { SftpLink.connect(c).use { buildPlan(it, dir, names) } }
        if (target is LocalTarget.FileDir) { target.base.mkdirs(); for (d in plan.dirs) File(target.base, d).mkdirs() }
        if (plan.files.isEmpty()) return "没有可下载的文件"

        val totalBytes = plan.files.sumOf { it.size }
        rep.prepare(plan.files.size, totalBytes)
        val failures = Collections.synchronizedList(mutableListOf<String>())
        val cursor = java.util.concurrent.atomic.AtomicInteger(0)

        withContext(Dispatchers.IO) {
            val workers = PARALLEL.coerceAtMost(maxOf(1, plan.files.size))
            kotlinx.coroutines.coroutineScope {
                repeat(workers) {
                    launch(Dispatchers.IO) {
                        SftpLink.connect(c).use { link ->
                            try {
                                while (!rep.cancelled) {
                                    val i = cursor.getAndIncrement()
                                    if (i >= plan.files.size) break
                                    val job = plan.files[i]
                                    try {
                                        // Stage into ".part" and only rename into
                                        // place on success: a cancelled or failed
                                        // download must not leave a truncated file
                                        // that looks complete.
                                        val staged = target.staged(job.localRel)
                                        staged.out.use { out ->
                                            var last = 0L
                                            link.download(job.remotePath, out,
                                                onProgress = { cur ->
                                                    val delta = cur - last
                                                    if (delta > 0) { last = cur; rep.addBytes(delta) }
                                                }) { rep.cancelled }
                                        }
                                        staged.commit()
                                        rep.itemDone()
                                    } catch (e: Exception) {
                                        if (rep.cancelled) return@launch
                                        failures.add("${job.localRel}: ${e.message}")
                                    }
                                }
                            } finally { link.close() }
                        }
                    }
                }
            }
        }
        val ok = plan.files.size - failures.size
        val transferred = rep.doneBytes.get()
        val sb = StringBuilder("已下载 $ok 个文件（${humanSize(transferred)}）→ ${target.label}")
        if (failures.isNotEmpty()) sb.append("\n失败 ${failures.size} 个：\n").append(failures.joinToString("\n") { "· $it" })
        return sb.toString()
    }

    /** Stream a tarball from the server and unpack it locally. */
    private suspend fun archiveDownload(dir: String, names: List<String>, target: LocalTarget, rep: Rep): String {
        val c = cfg ?: return "未配置连接"
        val tmp = File(cacheDir, ".bundle-${System.currentTimeMillis()}.tar.gz")
        rep.prepare(0, -1)
        var seen = 0L
        val result = runCatching {
            RemoteArchive.download(c, dir, names, tmp,
                onBytes = { b -> rep.addBytes(b - seen).also { seen = b } }) { rep.cancelled }
            val n = when (target) {
                is LocalTarget.FileDir -> {
                    target.base.mkdirs()
                    TarExtractor.extract(tmp, target.base)
                }
                is LocalTarget.Tree -> TarExtractor.extractTo(tmp) { rel -> target.open(rel) }
            }
            tmp.delete()
            n
        }
        if (result.isFailure) tmp.delete()
        return result.fold(
            onSuccess = { "已打包下载并解压 $it 个文件 → ${target.label}" },
            onFailure = { throw it }
        )
    }

    // --------------------------------------------------------- upload flow

    private fun uploadUris(uris: List<Uri>) {
        val c = cfg ?: run { toast("未配置连接"); return }
        val jobs = uris.mapNotNull { uri ->
            describeUri(uri)?.let { (name, size) -> Triple(uri, name, size) }
        }
        if (jobs.isEmpty()) { toast("无法读取所选文件"); return }
        runTransfer("上传") { rep ->
            val destDir = currentPath
            rep.prepare(jobs.size, jobs.sumOf { it.third })
            val failures = Collections.synchronizedList(mutableListOf<String>())
            val cursor = java.util.concurrent.atomic.AtomicInteger(0)
            withContext(Dispatchers.IO) {
                val workers = PARALLEL.coerceAtMost(maxOf(1, jobs.size))
                kotlinx.coroutines.coroutineScope {
                    repeat(workers) {
                        launch(Dispatchers.IO) {
                            SftpLink.connect(c).use { link ->
                                try {
                                    while (!rep.cancelled) {
                                        val i = cursor.getAndIncrement()
                                        if (i >= jobs.size) break
                                        val (uri, name, _) = jobs[i]
                                        val remote = joinPath(destDir, name)
                                        try {
                                            contentResolver.openInputStream(uri)?.use { ins ->
                                                var last = 0L
                                                link.upload(ins, remote,
                                                    onProgress = { cur ->
                                                        val delta = cur - last
                                                        if (delta > 0) { last = cur; rep.addBytes(delta) }
                                                    }) { rep.cancelled }
                                            } ?: throw IOException("无法打开 $name")
                                            rep.itemDone()
                                        } catch (e: Exception) {
                                            if (rep.cancelled) return@launch
                                            failures.add("$name: ${e.message}")
                                        }
                                    }
                                } finally { link.close() }
                            }
                        }
                    }
                }
            }
            val ok = jobs.size - failures.size
            val sb = StringBuilder("已上传 $ok 个文件 → $destDir")
            if (failures.isNotEmpty()) sb.append("\n失败 ${failures.size} 个：\n").append(failures.joinToString("\n") { "· $it" })
            sb.toString()
        }
    }

    private fun describeUri(uri: Uri): Pair<String, Long>? {
        var name: String? = null
        var size = -1L
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        size = c.getLong(1)
                    }
                }
        }
        return name?.let { it to if (size < 0) 0L else size }
    }

    // --------------------------------------------------- create / delete / rename

    private fun promptMkdir() {
        val ctx = this
        val edit = android.widget.EditText(ctx).apply { hint = "新目录名" }
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(edit)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("新建目录")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("创建") { _, _ ->
                val name = edit.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                scope.launch {
                    withLink { it.mkdir(joinPath(currentPath, name)) }
                        .onSuccess { refresh() }
                        .onFailure { e -> showError("创建失败", e.message ?: e.toString()) }
                }
            }
            .show()
    }

    private fun deleteSelected() {
        val paths = entries.filter { selected.contains(it.path) }.map { it.path }
        if (paths.isEmpty()) { toast("先勾选要删除的项"); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("删除 ${paths.size} 项")
            .setMessage("确定要删除选中的 ${paths.size} 项（含其下所有内容）吗？此操作不可恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                scope.launch {
                    withLink { link ->
                        for (p in paths) link.rmRecursive(p)
                    }.onSuccess {
                        selected.clear()
                        refresh()
                        toast("已删除")
                    }.onFailure { e -> showError("删除失败", e.message ?: e.toString()) }
                }
            }
            .show()
    }

    private fun showItemMenu(e: RemoteEntry) {
        val actions = mutableListOf<String>().apply {
            add("重命名")
            add("删除")
            if (!e.isDir) add("下载（选中它）")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(e.name)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "重命名" -> promptRename(e)
                    "删除" -> {
                        selected.add(e.path)
                        deleteSelected()
                    }
                    "下载（选中它）" -> {
                        selected.clear(); selected.add(e.path)
                        startDownload()
                    }
                }
            }
            .show()
    }

    private fun promptRename(e: RemoteEntry) {
        val ctx = this
        val edit = android.widget.EditText(ctx).apply { setText(e.name) }
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(edit)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("重命名")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                val newName = edit.text.toString().trim()
                if (newName.isEmpty() || newName == e.name) return@setPositiveButton
                val newPath = joinPath(e.path.substringBeforeLast('/', ""), newName)
                scope.launch {
                    withLink { it.rename(e.path, newPath) }
                        .onSuccess { selected.remove(e.path); refresh() }
                        .onFailure { err -> showError("重命名失败", err.message ?: err.toString()) }
                }
            }
            .show()
    }

    // ----------------------------------------------------------- transfer host

    /**
     * Start a transfer. Unlike before, several may run at once (an upload and a
     * download can proceed in parallel). Each gets its own [Rep]; the progress
     * panel aggregates them via [updateTransferUI].
     */
    private fun runTransfer(label: String, work: suspend (Rep) -> String) {
        val rep = Rep(label) { scope.launch(Dispatchers.Main.immediate) { updateTransferUI() } }
        transfers.add(rep)
        progressArea.visibility = android.view.View.VISIBLE
        progressBar.isIndeterminate = true
        progressText.text = "$label：准备中…"
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { work(rep) } }
            transfers.remove(rep)
            if (transfers.isEmpty()) progressArea.visibility = android.view.View.GONE
            else updateTransferUI()
            result.onSuccess { msg -> toast(msg); refresh() }
                .onFailure { e ->
                    val cancelled = rep.cancelled
                    if (!cancelled) showError("传输失败", e.message ?: e.toString())
                    toast(if (cancelled) "已取消" else "传输失败：${e.message ?: e}")
                }
        }
    }

    /** Recompute the aggregated progress text/bar across all live transfers. */
    private fun updateTransferUI() {
        val reps = transfers.toList()
        if (reps.isEmpty()) return
        var total = 0L
        var done = 0L
        var anyIndeterminate = false
        for (r in reps) {
            if (r.totalBytes > 0) total += r.totalBytes else anyIndeterminate = true
            done += r.doneBytes.get()
        }
        progressBar.isIndeterminate = anyIndeterminate || total <= 0
        if (!progressBar.isIndeterminate && total > 0) {
            progressBar.progress = ((done.toDouble() / total) * 100).toInt().coerceIn(0, 100)
        }
        progressText.text = reps.joinToString("    ") { it.line() }
    }

    /** Tracks progress for a single transfer: items, bytes, and live speed. */
    private inner class Rep(val label: String, private val onUi: () -> Unit) {
        @Volatile var cancelled: Boolean = false
        private val totalItems = java.util.concurrent.atomic.AtomicInteger(0)
        private val doneItems = java.util.concurrent.atomic.AtomicInteger(0)
        val doneBytes = java.util.concurrent.atomic.AtomicLong(0)
        @Volatile var totalBytes: Long = 0
        @Volatile var indeterminate: Boolean = true
        @Volatile var speedBps: Long = 0
        private var windowBytes = 0L
        private var windowStart = SystemClock.elapsedRealtime()

        fun prepare(items: Int, bytes: Long) {
            totalItems.set(items)
            totalBytes = bytes
            indeterminate = bytes <= 0
            post()
        }

        fun itemDone() {
            doneItems.incrementAndGet()
            post()
        }

        /** Feed cumulative bytes; derives a rolling transfer speed. */
        fun addBytes(delta: Long) {
            if (delta <= 0) return
            doneBytes.addAndGet(delta)
            windowBytes += delta
            val now = SystemClock.elapsedRealtime()
            val dt = now - windowStart
            if (dt >= 300) {
                speedBps = (windowBytes * 1000L / dt).coerceAtLeast(0)
                windowBytes = 0
                windowStart = now
            }
            if (now - lastPost > 150) { lastPost = now; post() }
        }

        fun cancel() { cancelled = true }

        fun line(): String = "$label：${statusText()}"

        private fun statusText(): String {
            val speed = if (speedBps > 0) " · ${humanSize(speedBps)}/s" else ""
            return if (indeterminate) {
                "已接收 ${humanSize(doneBytes.get())}$speed"
            } else {
                "文件 ${doneItems.get()}/$totalItems · ${humanSize(doneBytes.get())}/${humanSize(totalBytes)}$speed"
            }
        }

        private fun post() { onUi() }

        private var lastPost = 0L
    }

    // --------------------------------------------------- download destination

    /** Remember the user's chosen shared folder (or null for the default). */
    private fun onPickDownloadDir(uri: Uri?) {
        if (uri == null) { toast("未选择目录"); return }
        runCatching {
            // Persist so future launches can write there without re-picking.
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        downloadTreeUri = uri
        dlPrefs.edit().putString(KEY_DL_TREE, uri.toString()).apply()
        toast("下载目录已设为：${treeName(this, uri)}")
    }

    /**
     * Resolve where a download from remote [dir] should land. If the user picked
     * a folder via SAF we return a [LocalTarget.Tree]; otherwise we fall back to
     * the app-private `Download/<host>/...` directory (no permission needed).
     */
    private fun localTargetFor(dir: String): LocalTarget {
        val tree = downloadTreeUri
        return if (tree != null) {
            LocalTarget.Tree(this, tree)
        } else {
            val safeHost = cfg!!.host.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val rel = dir.trimStart('/').replace('/', File.separatorChar)
            LocalTarget.FileDir(File(File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!, safeHost), rel))
        }
    }

    /** A download destination that can open an output stream for a relative path. */
    private sealed class LocalTarget {
        /** Human-readable destination shown in dialogs / toasts. */
        abstract val label: String
        /** Open an output stream for [rel] (relative to the chosen dir). */
        abstract fun open(rel: String): OutputStream

        /**
         * Open a temporary output for [rel]; call [StagedOut.commit] only after
         * the stream closed successfully. Downloading straight into the final
         * name left a truncated file that looked complete.
         */
        abstract fun staged(rel: String): StagedOut

        /** A staged write: an open stream plus the rename-into-place step. */
        class StagedOut(val out: OutputStream, val commit: () -> Unit)

        /** Default: a directory under the app's private external storage. */
        class FileDir(val base: File) : LocalTarget() {
            override val label: String get() = base.absolutePath
            override fun open(rel: String): OutputStream {
                val f = File(base, rel)
                f.parentFile?.mkdirs()
                return FileOutputStream(f)
            }

            override fun staged(rel: String): StagedOut {
                val f = File(base, rel)
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, f.name + ".part")
                return StagedOut(FileOutputStream(tmp)) {
                    // renameTo can fail across mounts or on a rogue name; then
                    // the .part stays behind rather than destroying the file.
                    if (!tmp.renameTo(f)) tmp.delete()
                }
            }
        }

        /**
         * User-chosen shared folder reached through SAF. Parent directories are
         * created on demand; entries are scoped to the tree, so a malicious
         * archive cannot escape it.
         */
        class Tree(
            private val ctx: android.content.Context,
            private val uri: Uri
        ) : LocalTarget() {
            override val label: String get() = "已选文件夹：${treeName(ctx, uri)}"

            /** Walk/create the parent directories of [rel]; returns (dir, name). */
            private fun resolve(rel: String): Pair<DocumentFile, String> {
                val parts = rel.split('/').filter { it.isNotEmpty() }
                var d = DocumentFile.fromTreeUri(ctx, uri)
                    ?: throw IOException("无法访问所选下载目录")
                for (p in parts.dropLast(1)) {
                    d = d.findFile(p) ?: d.createDirectory(p) ?: throw IOException("无法创建目录：$p")
                }
                val name = parts.lastOrNull() ?: throw IOException("空文件名")
                return d to name
            }

            override fun open(rel: String): OutputStream {
                val (d, name) = resolve(rel)
                val file = d.findFile(name) ?: d.createFile(guessMime(name), name)
                    ?: throw IOException("无法创建文件：$name")
                return ctx.contentResolver.openOutputStream(file.uri, "wt")
                    ?: throw IOException("无法写入：$name")
            }

            override fun staged(rel: String): StagedOut {
                val (d, name) = resolve(rel)
                val tmpName = "$name.part"
                val tmp = d.findFile(tmpName) ?: d.createFile(guessMime(name), tmpName)
                    ?: throw IOException("无法创建文件：$tmpName")
                val out = ctx.contentResolver.openOutputStream(tmp.uri, "wt")
                    ?: throw IOException("无法写入：$tmpName")
                return StagedOut(out) {
                    // SAF rename keeps the .part behind when the provider refuses
                    // (e.g. the name already exists and cannot be replaced).
                    if (!tmp.renameTo(name)) {
                        runCatching { tmp.delete() }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- helpers

    private fun showError(title: String, msg: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(msg.ifBlank { "未知错误" })
            .setPositiveButton("好", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun formatTime(mtime: Long): String =
        if (mtime <= 0) "" else dateFmt.format(Date(mtime * 1000))

    // ------------------------------------------------------------- adapters

    private inner class EntryAdapter : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(i: Int) = entries[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_file, parent, false)
            val e = entries[i]
            val icon = view.findViewById<ImageView>(R.id.fileIcon)
            val name = view.findViewById<TextView>(R.id.fileName)
            val meta = view.findViewById<TextView>(R.id.fileMeta)
            val check = view.findViewById<CheckBox>(R.id.fileCheck)
            if (e.isDir) {
                icon.setImageResource(R.drawable.ic_folder)
                icon.imageTintList = ColorStateList.valueOf(
                    MaterialColors.getColor(icon, com.google.android.material.R.attr.colorPrimary)
                )
            } else {
                icon.setImageResource(R.drawable.ic_file)
                icon.imageTintList = ColorStateList.valueOf(
                    MaterialColors.getColor(icon, com.google.android.material.R.attr.colorOnSurfaceVariant)
                )
            }
            name.text = if (e.isDir) "${e.name}/" else e.name
            meta.text = if (e.isDir) "目录 · ${formatTime(e.mtime)}" else "${e.displaySize} · ${formatTime(e.mtime)}"
            check.isChecked = selected.contains(e.path)
            check.setOnClickListener { toggle(e) }
            return view
        }
    }

    companion object {
        private const val KEY_DL_TREE = "dl_tree_uri"
        private const val KEY_SORT_KEY = "sort_key"
        private const val KEY_SORT_ASC = "sort_asc"
    }
}

/** Friendly name for a SAF tree URI (used in the download-dir picker toast). */
private fun treeName(ctx: android.content.Context, uri: Uri): String {
    val d = DocumentFile.fromTreeUri(ctx, uri)
    return d?.name ?: uri.lastPathSegment ?: uri.toString()
}

/** Best-effort MIME type for a file name, falling back to octet-stream. */
private fun guessMime(name: String): String {
    val dot = name.lastIndexOf('.')
    if (dot >= 0) {
        val ext = name.substring(dot + 1).lowercase(Locale.US)
        val m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        if (m != null) return m
    }
    return "application/octet-stream"
}
