// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Matt <matt@mattsgateway.dpdns.org>
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version. See LICENSE for the full text.

package org.dpdns.mattsgateway.sshwithtailscale

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One "reverse export" rule: turn a port on the phone into a first-class
 * service on the tailnet.
 *
 * The Go side calls [tailscale.Server.Listen] on the tailnet interface, so any
 * device on the tailnet can reach it. Two modes:
 *  - "relay": every tailnet connection is dialled onwards from the phone's own
 *    network to (targetHost:targetPort) -- the phone becomes a one-port
 *    "reverse subnet router" into its LAN.
 *  - "proxy": an HTTP proxy (CONNECT + absolute-URI) so a tailnet device can
 *    browse *through* the phone.
 *
 * Unlike SSH port forwarding this needs no SSH session, no VpnService and no
 * admin approval -- it is peer-to-peer and stays up as long as Tailscale is.
 */
data class PublishRule(
    val mode: String,        // "relay" | "proxy"
    val listenPort: Int,
    val targetHost: String,  // relay only
    val targetPort: Int      // relay only
) {
    val isProxy: Boolean get() = mode == "proxy"

    /** e.g. "中继 :8080 → 192.168.1.50:80" or "代理 :3128" */
    fun label(): String = when {
        isProxy -> "代理  :$listenPort"
        else -> "中继  :$listenPort  →  $targetHost:$targetPort"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", mode)
        put("listenPort", listenPort)
        put("targetHost", targetHost)
        put("targetPort", targetPort)
    }

    fun addr(): String = ":$listenPort"

    companion object {
        const val MODE_RELAY = "relay"
        const val MODE_PROXY = "proxy"

        private const val FILE = "publishes"
        private const val KEY = "rules"

        fun fromJson(o: JSONObject) = PublishRule(
            mode = o.optString("mode", MODE_RELAY),
            listenPort = o.optInt("listenPort", 0),
            targetHost = o.optString("targetHost", ""),
            targetPort = o.optInt("targetPort", 0)
        )

        fun load(context: Context): MutableList<PublishRule> {
            val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getString(KEY, null) ?: return mutableListOf()
            return runCatching {
                val arr = JSONArray(raw)
                MutableList(arr.length()) { fromJson(arr.getJSONObject(it)) }
            }.getOrElse { mutableListOf() }
        }

        fun save(context: Context, rules: List<PublishRule>) {
            val arr = JSONArray()
            rules.forEach { arr.put(it.toJson()) }
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString(KEY, arr.toString())
                .apply()
        }
    }
}
