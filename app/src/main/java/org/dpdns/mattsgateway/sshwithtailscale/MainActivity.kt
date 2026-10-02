// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Matt <matt@mattsgateway.dpdns.org>
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version. See LICENSE for the full text.

package org.dpdns.mattsgateway.sshwithtailscale

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import org.dpdns.mattsgateway.sshwithtailscale.terminal.TerminalView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class MainActivity : AppCompatActivity() {

    private lateinit var terminal: TerminalView

    private lateinit var toolbar: MaterialToolbar
    private lateinit var connectionPanel: View

    /** True while a shell is up. The setup panel hides itself while it is. */
    private var sshConnected = false

    /** Port-forwarding rules; persisted and re-installed on every connect. */
    private val forwardRules = mutableListOf<ForwardRule>()

    /** Reverse-export rules; persisted and re-applied once Tailscale is up. */
    private val publishRules = mutableListOf<PublishRule>()

    private lateinit var authKey: EditText
    private lateinit var btnTailscale: Button
    private lateinit var btnPeers: Button
    private lateinit var statusText: TextView

    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var user: EditText
    private lateinit var password: EditText
    private lateinit var keySpinner: Spinner
    private lateinit var btnConnect: Button

    private var ssh: SshSession? = null

    /** Keeps the Go side's interface list in sync with connectivity changes. */
    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null

    /** When armed, the next typed character is sent as its control code. */
    private var ctrlArmed = false
    private var altArmed = false
    private lateinit var btnCtrl: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // TOFU host key repository: from here on, every JSch session records and
        // verifies host fingerprints (see KnownHosts).
        KnownHosts.init(this)

        // An SSH session is no fun if the screen sleeps mid-command.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        terminal = findViewById(R.id.terminal)
        toolbar = findViewById(R.id.toolbar)
        connectionPanel = findViewById(R.id.connectionPanel)
        authKey = findViewById(R.id.authKey)
        btnTailscale = findViewById(R.id.btnTailscale)
        btnPeers = findViewById(R.id.btnPeers)
        statusText = findViewById(R.id.tailscaleStatus)
        host = findViewById(R.id.host)
        port = findViewById(R.id.port)
        user = findViewById(R.id.user)
        password = findViewById(R.id.password)
        keySpinner = findViewById(R.id.keySpinner)
        btnConnect = findViewById(R.id.btnConnect)

        terminal.onInput = { bytes -> ssh?.write(bytes) }
        // Typed characters go through sendToPty so the CTRL/ALT modifiers apply.
        terminal.onTextInput = { text -> sendToPty(text) }
        terminal.onFocusRequest = { showKeyboard() }
        // Keep the server's PTY size in sync, or its output wrapping no longer
        // matches what this view renders and the screen turns to soup.
        terminal.onResized = { cols, rows -> ssh?.resize(cols, rows) }
        // Pinch-zoom sets the font size; remember it for the next launch.
        terminal.onFontSizeChanged = { px ->
            getSharedPreferences(PREFS_TERMINAL, MODE_PRIVATE).edit()
                .putFloat(KEY_FONT_SIZE, px).apply()
        }
        terminal.textSizePx(
            getSharedPreferences(PREFS_TERMINAL, MODE_PRIVATE)
                .getFloat(KEY_FONT_SIZE, terminal.defaultFontSizePx())
        )

        // Long-press-to-select: show the copy/cancel bar while a selection is
        // active, and copy the chosen block to the clipboard on "复制".
        val selectionBar = findViewById<View>(R.id.selectionBar)
        terminal.onSelectionChanged = { active ->
            selectionBar.visibility = if (active) View.VISIBLE else View.GONE
        }
        findViewById<Button>(R.id.btnSelCopy).setOnClickListener {
            val text = terminal.getSelectedText()
            if (text.isNotEmpty()) {
                val clip = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clip.setPrimaryClip(android.content.ClipData.newPlainText("ssh", text))
                Toast.makeText(this, R.string.sel_copied, Toast.LENGTH_SHORT).show()
            }
            terminal.exitSelection()
        }
        findViewById<Button>(R.id.btnSelCancel).setOnClickListener {
            terminal.exitSelection()
        }

        btnTailscale.setOnClickListener { toggleTailscale() }
        btnPeers.setOnClickListener { showPeers() }
        btnConnect.setOnClickListener { connectSsh() }

        findViewById<Button>(R.id.btnKeys).setOnClickListener { showKeyManager() }
        findViewById<Button>(R.id.btnCollapse).setOnClickListener { showConnectionPanel(false) }

        // Port forwarding is switched off for now -- see ForwardRule.ENABLED.
        toolbar.menu.findItem(R.id.action_forwards)?.isVisible = ForwardRule.ENABLED

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                // The three-dot entry point asked for: bring the panel back when
                // you need to change endpoint, hide it again when you don't.
                R.id.action_connection -> {
                    showConnectionPanel(connectionPanel.visibility != View.VISIBLE)
                    true
                }
                R.id.action_reconnect -> {
                    showConnectionPanel(false)
                    connectSsh()
                    true
                }
                R.id.action_devices -> {
                    showPeers()
                    true
                }
                R.id.action_forwards -> {
                    showForwards()
                    true
                }
                R.id.action_publish -> {
                    showPublish()
                    true
                }
                R.id.action_clear -> {
                    terminal.clear()
                    true
                }
                R.id.action_about -> {
                    startActivity(android.content.Intent(this, AboutActivity::class.java))
                    true
                }
                R.id.action_files -> {
                    openFileManager()
                    true
                }
                else -> false
            }
        }

        setupModifierRow()
        reloadKeys()
        refreshStatus()

        // Nothing is connected on a cold start, so the setup panel is shown.
        showConnectionPanel(true)

        // Restore the last endpoint. Only host/port/user are kept -- the
        // password field always starts empty.
        val saved = ConnectionPrefs.load(this)
        if (saved.host.isNotEmpty()) host.setText(saved.host)
        port.setText(saved.port)
        if (saved.user.isNotEmpty()) user.setText(saved.user)

        forwardRules.addAll(ForwardRule.load(this))
        publishRules.addAll(PublishRule.load(this))

        // Re-supply interfaces when the network changes (wifi <-> cellular).
        netCallback = NetworkInterfaceProvider.registerConnectivityCallback(this) {
            if (TailscaleManager.isRunning()) {
                NetworkInterfaceProvider.sync()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        NetworkInterfaceProvider.unregisterConnectivityCallback(this, netCallback)
        netCallback = null
        ActiveConnection.clearSession()
        runCatching { ssh?.disconnect() }
    }

    // ------------------------------------------------------------- Tailscale

    private fun toggleTailscale() {
        if (TailscaleManager.isRunning()) {
            ssh?.disconnect()
            ssh = null
            sshConnected = false
            ActiveConnection.clearSession()
            TailscaleManager.stop()
            TailscaleService.setDesired(this, false)
            TailscaleService.stop(this)
            Toast.makeText(this, "Tailscale 已停止", Toast.LENGTH_SHORT).show()
        } else {
            val key = authKey.text.toString().trim()
            val ok = TailscaleManager.start(this, key)
            val n = TailscaleManager.interfaceCount()
            Toast.makeText(
                this,
                if (ok) "Tailscale 启动中…（$n 个网卡）"
                else "启动失败：${TailscaleManager.lastError()}",
                Toast.LENGTH_SHORT
            ).show()
            if (ok) {
                // The foreground service keeps the node (and every published
                // port) alive while the app is in the background.
                TailscaleService.setDesired(this, true)
                TailscaleService.start(this)
                requestNotificationPermissionIfNeeded()
                pollStatus()
            }
        }
        refreshStatus()
    }

    /**
     * Android 13+ hides the foreground service's notification without this
     * permission. The service runs either way; asking here just makes the
     * "node is running" status visible.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* service runs either way */ }

    private var statusPolling = false

    /**
     * Waits for the node to finish coming up, then re-issues saved rules.
     *
     * Interactive login decided the budget here: when the authkey is unusable
     * the user has to tap the link we show and approve it in a browser, which
     * comfortably takes longer than thirty seconds. The old 30s cap meant the
     * node could come up fine while this had already given up -- leaving the UI
     * reading "needs re-auth" and never applying the reverse-export rules.
     */
    private fun pollStatus() {
        if (statusPolling) return // onResume can call this repeatedly
        statusPolling = true
        // lifecycleScope: the old bare CoroutineScope kept polling (and touching
        // views) for the full 3-minute window even after the Activity was gone.
        lifecycleScope.launch {
            try {
                repeat(STATUS_POLL_ATTEMPTS) {
                    refreshStatus()
                    if (TailscaleManager.status().isNotEmpty()) {
                        // Tailscale just came up: re-issue any saved reverse-export
                        // rules so the phone is a tailnet server again.
                        applyPublishOnUp()
                        return@launch
                    }
                    kotlinx.coroutines.delay(1000)
                }
            } finally {
                statusPolling = false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The usual way interactive login completes: we sent them to a browser
        // and they come back afterwards. Re-check immediately instead of
        // waiting for the next poll tick.
        if (TailscaleManager.isRunning() && TailscaleManager.status().isEmpty()) {
            pollStatus()
        } else {
            refreshStatus()
        }
    }

    /**
     * Shows or hides the setup panel. It is hidden automatically once the shell
     * is up, and brought back from the toolbar's three-dot menu.
     */
    private fun showConnectionPanel(show: Boolean) {
        connectionPanel.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun refreshStatus() {
        val ip = TailscaleManager.status()
        val err = TailscaleManager.lastError()
        val running = TailscaleManager.isRunning()
        // When the authkey expires tsnet does NOT fail: Up() blocks waiting for
        // an interactive login. This URL is the only way out, so it has to take
        // precedence over the generic "starting up" wording.
        val auth = TailscaleManager.authURL()

        statusText.text = when {
            ip.isNotEmpty() -> "已连接 ($ip)"
            auth.isNotEmpty() -> getString(R.string.status_auth_required, auth)
            running && err.isNotEmpty() -> "启动中…\n错误：$err"
            running -> "启动中…"
            err.isNotEmpty() -> "已停止\n错误：$err"
            else -> "已停止"
        }
        if (auth.isNotEmpty()) {
            // Make the login URL tappable right from the status line.
            android.text.util.Linkify.addLinks(statusText, android.text.util.Linkify.WEB_URLS)
            statusText.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }

        btnTailscale.text = getString(if (running) R.string.btn_stop else R.string.btn_start)

        // Full diagnostics stay in the panel; the toolbar carries the gist.
        toolbar.subtitle = when {
            sshConnected -> "SSH ${user.text}@${host.text}"
            ip.isNotEmpty() -> getString(R.string.status_tailscale_on)
            auth.isNotEmpty() -> getString(R.string.status_need_auth)
            running -> getString(R.string.status_starting)
            else -> getString(R.string.status_stopped)
        }
        // With a login pending, the toolbar itself becomes the shortcut to it.
        toolbar.setOnClickListener { openTailscaleLogin() }
        // Reconnecting only makes sense once the tunnel is up.
        toolbar.menu.findItem(R.id.action_reconnect)?.isEnabled = running
    }

    /** Opens the pending Tailscale login URL, if there is one. */
    private fun openTailscaleLogin() {
        val url = TailscaleManager.authURL()
        if (url.isEmpty()) return
        runCatching {
            startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)
                )
            )
        }
    }

    private fun showPeers() {
        if (!TailscaleManager.isRunning()) {
            Toast.makeText(this, "请先启动 Tailscale", Toast.LENGTH_SHORT).show()
            return
        }
        // Peers() talks to the node's local API (it used to run right here on
        // the UI thread and could stall it); fetch off-thread, then show.
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) { TailscaleManager.peers() }
            val arr = runCatching { JSONArray(json) }.getOrElse { JSONArray() }
            if (arr.length() == 0) {
                Toast.makeText(this@MainActivity, "暂时看不到设备（节点可能还在启动）", Toast.LENGTH_LONG).show()
                return@launch
            }
            val items = mutableListOf<String>()
            val ips = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name").ifBlank { o.optString("dns") }
                val ip = o.optString("ip")
                val online = o.optBoolean("online", false)
                items.add("${if (online) "●" else "○"}  $name  ($ip)")
                ips.add(ip)
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Tailnet 设备")
                .setItems(items.toTypedArray()) { _, which ->
                    host.setText(ips[which])
                    Toast.makeText(this@MainActivity, "已填入 ${ips[which]}", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    // -------------------------------------------------------- port forwarding

    private fun showForwards() {
        if (forwardRules.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.fwd_title)
                .setMessage(R.string.fwd_empty)
                .setNegativeButton("关闭", null)
                .setPositiveButton(R.string.fwd_add) { _, _ -> showAddForward() }
                .show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fwd_title)
            .setItems(forwardRules.map { it.label() }.toTypedArray()) { _, which ->
                confirmDeleteForward(which)
            }
            .setNegativeButton("关闭", null)
            .setPositiveButton(R.string.fwd_add) { _, _ -> showAddForward() }
            .show()
    }

    private fun confirmDeleteForward(index: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle(forwardRules[index].label())
            .setItems(arrayOf(getString(R.string.fwd_delete))) { _, _ ->
                forwardRules.removeAt(index)
                persistAndApplyForwards()
            }
            .show()
    }

    private fun showAddForward() {
        val view = layoutInflater.inflate(R.layout.dialog_forward, null)
        val bindHost = view.findViewById<EditText>(R.id.fwdBindHost)
        val bindPort = view.findViewById<EditText>(R.id.fwdBindPort)
        val targetHost = view.findViewById<EditText>(R.id.fwdTargetHost)
        val targetPort = view.findViewById<EditText>(R.id.fwdTargetPort)
        val help = view.findViewById<TextView>(R.id.fwdHelp)
        val local = view.findViewById<MaterialRadioButton>(R.id.dirLocal)
        val remote = view.findViewById<MaterialRadioButton>(R.id.dirRemote)

        // Sensible default: a local forward of 8080 to something on the far side.
        bindHost.setText("127.0.0.1")
        bindPort.setText("8080")
        local.setOnCheckedChangeListener { _, checked ->
            if (checked) help.setText(R.string.fwd_help_local)
        }
        remote.setOnCheckedChangeListener { _, checked ->
            if (checked) help.setText(R.string.fwd_help_remote)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fwd_add)
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val bp = bindPort.text.toString().toIntOrNull() ?: 0
                val tp = targetPort.text.toString().toIntOrNull() ?: 0
                val th = targetHost.text.toString().trim()
                if (bp !in 1..65535 || tp !in 1..65535 || th.isEmpty()) {
                    Toast.makeText(this, "端口需在 1-65535，目标主机不能为空", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                forwardRules.add(
                    ForwardRule(
                        remote = remote.isChecked,
                        bindHost = bindHost.text.toString().trim().ifBlank { "127.0.0.1" },
                        bindPort = bp,
                        targetHost = th,
                        targetPort = tp
                    )
                )
                persistAndApplyForwards()
            }
            .show()
    }

    /** Saves the rules, and re-installs them when a session is live. */
    private fun persistAndApplyForwards() {
        ForwardRule.save(this, forwardRules)
        val session = ssh
        if (session == null) {
            Toast.makeText(this, "规则已保存，连上 SSH 后生效", Toast.LENGTH_SHORT).show()
            return
        }
        // Binding sockets and sending tcpip-forward requests is network work.
        lifecycleScope.launch(Dispatchers.IO) {
            val lines = session.applyForwards(forwardRules.toList())
            withContext(Dispatchers.Main) { lines.forEach { append("$it\r\n") } }
        }
    }

    private fun applyForwardsOnConnect() {
        if (forwardRules.isEmpty()) return
        val session = ssh ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val lines = session.applyForwards(forwardRules.toList())
            withContext(Dispatchers.Main) { lines.forEach { append("$it\r\n") } }
        }
    }

    // --------------------------------------------------- reverse export (publish)

    /**
     * Shows the currently-exported tailnet ports. Each row copies its tailnet
     * address (what other devices connect to); tapping it also offers to stop.
     */
    private fun showPublish() {
        if (!TailscaleManager.isRunning()) {
            Toast.makeText(this, "请先启动 Tailscale", Toast.LENGTH_SHORT).show()
            return
        }
        val arr = runCatching { JSONArray(TailscaleManager.publishedList()) }.getOrElse { JSONArray() }
        if (arr.length() == 0) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pub_title)
                .setMessage(R.string.pub_empty)
                .setNegativeButton("关闭", null)
                .setPositiveButton(R.string.pub_add) { _, _ -> showAddPublish() }
                .show()
            return
        }
        val items = mutableListOf<String>()
        val entries = mutableListOf<Pair<String, String>>() // (addr, tailnet)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val mode = o.optString("mode")
            val target = o.optString("target")
            val tailnet = o.optString("tailnet")
            val kind = if (mode == "proxy") "代理" else "中继"
            items.add("$kind  $tailnet" + if (target.isNotEmpty()) "  →  $target" else "")
            entries.add(o.optString("addr") to tailnet)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pub_title)
            .setItems(items.toTypedArray()) { _, which ->
                val (addr, tailnet) = entries[which]
                MaterialAlertDialogBuilder(this)
                    .setTitle(tailnet)
                    .setItems(arrayOf("复制 tailnet 地址", getString(R.string.pub_stop))) { _, act ->
                        when (act) {
                            0 -> {
                                val clip = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clip.setPrimaryClip(android.content.ClipData.newPlainText("tailnet", tailnet))
                                Toast.makeText(this, R.string.pub_copied, Toast.LENGTH_SHORT).show()
                            }
                            1 -> {
                                val err = TailscaleManager.unpublish(addr)
                                if (err.isNotEmpty()) {
                                    Toast.makeText(this, "停止失败：$err", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(this, "已停止 $addr", Toast.LENGTH_SHORT).show()
                                }
                                showPublish() // refresh the list
                            }
                        }
                    }
                    .show()
            }
            .setNegativeButton("关闭", null)
            .setPositiveButton(R.string.pub_add) { _, _ -> showAddPublish() }
            .show()
    }

    private fun showAddPublish() {
        val view = layoutInflater.inflate(R.layout.dialog_publish, null)
        val relay = view.findViewById<MaterialRadioButton>(R.id.pubRelay)
        val proxy = view.findViewById<MaterialRadioButton>(R.id.pubProxy)
        val listenPort = view.findViewById<EditText>(R.id.pubListenPort)
        val targetHost = view.findViewById<EditText>(R.id.pubTargetHost)
        val targetPort = view.findViewById<EditText>(R.id.pubTargetPort)
        val targetRow = view.findViewById<LinearLayout>(R.id.pubTargetRow)
        val help = view.findViewById<TextView>(R.id.pubHelp)

        listenPort.setText("8080")
        relay.setOnCheckedChangeListener { _, c ->
            if (c) { help.setText(R.string.pub_help_relay); targetRow.visibility = View.VISIBLE }
        }
        proxy.setOnCheckedChangeListener { _, c ->
            if (c) { help.setText(R.string.pub_help_proxy); targetRow.visibility = View.GONE }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pub_add)
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val port = listenPort.text.toString().toIntOrNull() ?: 0
                if (port !in 1..65535) {
                    Toast.makeText(this, "端口需在 1-65535", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (proxy.isChecked) {
                    val rule = PublishRule(PublishRule.MODE_PROXY, port, "", 0)
                    publishRules.add(rule)
                    PublishRule.save(this, publishRules)
                    applyOnePublish(rule)
                } else {
                    val th = targetHost.text.toString().trim()
                    val tp = targetPort.text.toString().toIntOrNull() ?: 0
                    if (th.isEmpty() || tp !in 1..65535) {
                        Toast.makeText(this, "目标主机不能为空，端口需在 1-65535", Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    val rule = PublishRule(PublishRule.MODE_RELAY, port, th, tp)
                    publishRules.add(rule)
                    PublishRule.save(this, publishRules)
                    applyOnePublish(rule)
                }
            }
            .show()
    }

    /** Applies a single saved rule right now if Tailscale is up. */
    private fun applyOnePublish(rule: PublishRule) {
        if (!TailscaleManager.isUp()) {
            Toast.makeText(this, "规则已保存，Tailscale 连上后自动生效", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val err = if (rule.isProxy) {
                TailscaleManager.publishProxy(rule.addr())
            } else {
                TailscaleManager.publish(rule.addr(), "${rule.targetHost}:${rule.targetPort}")
            }
            withContext(Dispatchers.Main) {
                if (err.isNotEmpty()) {
                    Toast.makeText(this@MainActivity, "导出失败：$err", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "已在 tailnet 上导出 :${rule.listenPort}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Re-issues every saved rule after Tailscale comes up (peer-to-peer, stays). */
    private fun applyPublishOnUp() {
        if (publishRules.isEmpty() || !TailscaleManager.isUp()) return
        lifecycleScope.launch(Dispatchers.IO) {
            for (r in publishRules) {
                val err = if (r.isProxy) {
                    TailscaleManager.publishProxy(r.addr())
                } else {
                    TailscaleManager.publish(r.addr(), "${r.targetHost}:${r.targetPort}")
                }
                if (err.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        append("反向导出 :${r.listenPort} 失败：$err\r\n")
                    }
                }
            }
            withContext(Dispatchers.Main) { refreshStatus() }
        }
    }

    // ------------------------------------------------------------------- SSH

    private fun connectSsh() {
        val h = host.text.toString().trim()
        val p = port.text.toString().toIntOrNull() ?: 22
        val u = user.text.toString().trim()
        if (h.isEmpty() || u.isEmpty()) {
            Toast.makeText(this, "请填写主机和用户名", Toast.LENGTH_SHORT).show()
            return
        }
        // Remember the endpoint (never the password).
        ConnectionPrefs.save(this, h, port.text.toString().trim().ifBlank { "22" }, u)
        if (!TailscaleManager.isRunning()) {
            Toast.makeText(this, "请先启动 Tailscale", Toast.LENGTH_SHORT).show()
            return
        }
        // Drop any previous session first. Otherwise its reader thread would
        // later fire onClosed and null out the *new* session's reference,
        // leaving the UI "disconnected" while the SSH link is actually alive.
        ssh?.disconnect()
        ssh = null
        ActiveConnection.clearSession()

        btnConnect.isEnabled = false
        terminal.clear()
        terminal.exitSelection()
        append("正在连接 $u@$h:$p（经 Tailscale SOCKS）…\r\n")

        val pw = password.text.toString()
        val keyName = keySpinner.selectedItem?.toString()
        val pem = if (keyName != null && keyName != NO_KEY) {
            KeyManager.load(this, keyName).orEmpty()
        } else ""

        lifecycleScope.launch(Dispatchers.IO) {
            val session = SshSession(
                onData = { data -> runOnUiThread { terminal.append(data) } },
                onClosed = { msg ->
                    runOnUiThread {
                        append("\r\n[已断开${msg?.let { "：$it" } ?: ""}]\r\n")
                        btnConnect.isEnabled = true
                        ssh = null
                        ActiveConnection.clearSession()
                        // Keep the panel hidden -- a network blip should not
                        // shove the settings back over the terminal. The toolbar
                        // says what happened and offers 重连.
                        sshConnected = false
                        refreshStatus()
                    }
                }
            )
            try {
                session.connect(h, p, u, pw, pem, terminal.cursorCols(), terminal.cursorRows())
                ssh = session
                // Remember the connection so the file manager reuses it instead
                // of prompting for the password a second time.
                ActiveConnection.set(
                    SshConfig(host = h, port = p, user = u, password = pw, privateKeyPem = pem),
                    session.jschSession()
                )
                withContext(Dispatchers.Main) {
                    btnConnect.isEnabled = true
                    btnConnect.text = getString(R.string.btn_reconnect)
                    sshConnected = true
                    // Setup is done: get the controls out of the way.
                    showConnectionPanel(false)
                    refreshStatus()
                    // Rules survive reconnects, so re-install them every time.
                    applyForwardsOnConnect()
                    showKeyboard()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    append("SSH 错误：${e.message}\r\n")
                    btnConnect.isEnabled = true
                    sshConnected = false
                    refreshStatus()
                }
            }
        }
    }

    private fun append(text: String) = terminal.append(text)

    // ----------------------------------------------------------------- input

    private fun sendToPty(text: String) {
        val s = ssh
        if (s == null) {
            // Nothing connected: show a hint instead of silently eating input.
            terminal.append("(not connected)\r\n")
            return
        }
        val bytes = if (ctrlArmed || altArmed) {
            val mapped = text.map { c ->
                when {
                    ctrlArmed -> controlCode(c)
                    altArmed -> c
                    else -> c
                }
            }
            val prefix = if (altArmed) "\u001b" else ""
            val out = prefix + mapped.joinToString("")
            ctrlArmed = false
            altArmed = false
            updateModifierButtons()
            out
        } else text
        s.write(bytes)
        terminal.scrollToBottom()
    }

    private fun controlCode(c: Char): Char {
        val upper = c.uppercaseChar()
        return when (upper) {
            in 'A'..'Z' -> ((upper.code - 'A'.code) + 1).toChar()
            '@' -> '\u0000'
            '[' -> '\u001b'
            '\\' -> '\u001c'
            ']' -> '\u001d'
            '^' -> '\u001e'
            '_' -> '\u001f'
            ' ' -> '\u0000'
            else -> c
        }
    }

    private fun setupModifierRow() {
        btnCtrl = findViewById(R.id.btnCtrl)
        val btnAlt = findViewById<Button>(R.id.btnAlt)

        btnCtrl.setOnClickListener {
            ctrlArmed = !ctrlArmed
            altArmed = false
            updateModifierButtons()
        }
        btnAlt.setOnClickListener {
            altArmed = !altArmed
            ctrlArmed = false
            updateModifierButtons()
        }

        sendOnClick(R.id.btnEsc, "\u001b")
        sendOnClick(R.id.btnTab, "\t")
        sendOnClick(R.id.btnUp, "\u001b[A")
        sendOnClick(R.id.btnDown, "\u001b[B")
        sendOnClick(R.id.btnLeft, "\u001b[D")
        sendOnClick(R.id.btnRight, "\u001b[C")
        sendOnClick(R.id.btnCtrlC, "\u0003")
        sendOnClick(R.id.btnCtrlD, "\u0004")
        sendOnClick(R.id.btnCtrlL, "\u000c")
        sendOnClick(R.id.btnCtrlZ, "\u001a")
    }

    private fun sendOnClick(id: Int, seq: String) {
        findViewById<Button>(id).setOnClickListener {
            // Logged so a "the cursor jumped" report can be matched against the
            // exact bytes the button sent.
            Log.d("TerminalKeys", "${resources.getResourceEntryName(id)} -> ${seq.length} byte(s)")
            ssh?.write(seq)
            terminal.scrollToBottom()
        }
    }

    private fun updateModifierButtons() {
        btnCtrl.text = if (ctrlArmed) "CTRL*" else "CTRL"
        findViewById<Button>(R.id.btnAlt).text = if (altArmed) "ALT*" else "ALT"
    }

    private fun showKeyboard() {
        // The terminal implements onCreateInputConnection, so giving it focus is
        // what makes the system show the IME. showSoftInput() is a belt-and-
        // braces call for ROMs that do not do it on their own; the second call
        // covers the case where the IME connection is not up yet.
        terminal.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val first = imm.showSoftInput(terminal, InputMethodManager.SHOW_IMPLICIT)
        Log.i("Ime", "showSoftInput(1)=$first active=${imm.isActive} " +
            "focused=${terminal.hasWindowFocus()}")
        terminal.postDelayed({
            val second = imm.showSoftInput(terminal, InputMethodManager.SHOW_IMPLICIT)
            Log.i("Ime", "showSoftInput(2)=$second")
        }, 100)
    }

    // ------------------------------------------------------------------ keys

    private fun reloadKeys() {
        val names = mutableListOf(NO_KEY).apply { addAll(KeyManager.list(this@MainActivity)) }
        names.add(ADD_KEY)
        keySpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, names
        )
    }

    private fun showKeyManager() {
        val saved = KeyManager.list(this)
        if (saved.isEmpty()) {
            promptAddKey()
            return
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle("SSH 密钥")
            .setItems(saved.toTypedArray()) { _, which ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(saved[which])
                    .setItems(arrayOf("用于本次连接", "删除")) { _, act ->
                        when (act) {
                            0 -> {
                                val idx = (keySpinner.adapter as ArrayAdapter<String>)
                                    .getPosition(saved[which])
                                keySpinner.setSelection(idx)
                            }
                            1 -> {
                                KeyManager.delete(this, saved[which])
                                reloadKeys()
                            }
                        }
                    }
                    .show()
            }
            .setPositiveButton("添加新密钥…") { _, _ -> promptAddKey() }
            .setNegativeButton("取消", null)
        // TOFU fingerprints live next to the keys; a reinstall on the server side
        // makes the user look for exactly this switch.
        if (KnownHosts.exists()) {
            builder.setNeutralButton(R.string.keys_clear_fingerprints) { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.keys_clear_fingerprints)
                    .setMessage(R.string.keys_clear_fingerprints_confirm)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("清除") { _, _ ->
                        KnownHosts.clear()
                        Toast.makeText(this, R.string.keys_fingerprints_cleared, Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
        }
        builder.show()
    }

    private fun promptAddKey() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val nameEdit = EditText(this).apply { hint = "密钥名称（如 id_rsa）" }
        val pemEdit = EditText(this).apply {
            hint = "粘贴 PEM 私钥"
            setSingleLine(false)
            minLines = 6
        }
        layout.addView(nameEdit)
        layout.addView(pemEdit)

        MaterialAlertDialogBuilder(this)
            .setTitle("添加 SSH 私钥")
            .setView(layout)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val name = nameEdit.text.toString().trim()
                val pem = pemEdit.text.toString()
                if (name.isBlank() || !KeyManager.looksLikePem(pem)) {
                    Toast.makeText(this, "需要名称和 PEM 格式私钥", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                KeyManager.save(this, name, pem)
                reloadKeys()
                Toast.makeText(this, "已保存 $name", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // --------------------------------------------------------- file manager

    /** Open the SFTP file manager; the connection is reused from ActiveConnection. */
    private fun openFileManager() {
        if (!TailscaleManager.isRunning()) {
            Toast.makeText(this, "请先在首页启动 Tailscale", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, FileManagerActivity::class.java))
    }

    companion object {
        private const val NO_KEY = "无密钥"
        private const val ADD_KEY = "添加新密钥…"

        /** Terminal font size persistence. */
        private const val PREFS_TERMINAL = "terminal"
        private const val KEY_FONT_SIZE = "font_px"

        /**
         * How many seconds to wait for the node to come up. Generous because
         * interactive login needs a human: tap the link, approve in a browser,
         * come back. A plain reconnect is instant; re-auth never is.
         */
        private const val STATUS_POLL_ATTEMPTS = 180
    }
}
