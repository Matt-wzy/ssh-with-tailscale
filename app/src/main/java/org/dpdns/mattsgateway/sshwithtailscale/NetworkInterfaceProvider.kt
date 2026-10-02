// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Matt <matt@mattsgateway.dpdns.org>
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version. See LICENSE for the full text.

package org.dpdns.mattsgateway.sshwithtailscale

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import org.json.JSONArray
import org.json.JSONObject
import tailscale.Tailscale
import java.net.NetworkInterface

/**
 * Supplies the network interface list to the embedded Tailscale node.
 *
 * Go's net.Interfaces() cannot work inside a normal Android app: it needs an
 * RTNETLINK socket, which SELinux denies to untrusted apps, so Tailscale fails
 * with "route ip+net: netlinkrib: permission denied". Java's NetworkInterface
 * has no such restriction, so we enumerate here and hand the result to Go via
 * Tailscale.setInterfaces(), which registers it as netmon's interface provider.
 */
object NetworkInterfaceProvider {

    /**
     * Last JSON we handed to Go. Android fires connectivity callbacks very
     * often (onCapabilitiesChanged alone fires on every signal/bandwidth
     * change), and every push re-registers netmon's interface getter, which
     * makes Tailscale's link monitor churn and can tear down live connections.
     * Comparing against this cache turns the redundant callbacks into no-ops.
     */
    private var lastJson: String? = null

    /**
     * Enumerate interfaces and push them to the Go side. Returns the count.
     *
     * Pass [force] = true for the initial push before the node starts; later
     * calls skip the push entirely when nothing actually changed.
     */
    fun sync(force: Boolean = false): Int {
        // Sort by name so the JSON is byte-identical across calls even if the
        // OS returns the interfaces (or their addresses) in a different order.
        val byName = sortedMapOf<String, JSONObject>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return 0
            for (ni in ifaces) {
                val name = ni.name ?: continue
                val o = JSONObject()
                o.put("name", name)
                o.put("index", ni.index)
                o.put("mtu", ni.mtu)
                o.put("up", ni.isUp)
                o.put("loopback", ni.isLoopback)
                o.put("pointToPoint", ni.isPointToPoint)
                o.put("virtual", ni.isVirtual)

                ni.hardwareAddress?.let { mac ->
                    o.put("hw", mac.joinToString(":") { "%02x".format(it) })
                }

                val addrs = sortedSetOf<String>()
                ni.interfaceAddresses?.forEach { ia ->
                    val host = ia.address?.hostAddress ?: return@forEach
                    // IPv6 link-local addresses carry a "%wlan0" zone suffix
                    // that netip.ParsePrefix rejects; strip it.
                    val clean = host.substringBefore('%')
                    addrs.add("$clean/${ia.networkPrefixLength}")
                }
                val addrArr = JSONArray()
                addrs.forEach { addrArr.put(it) }
                o.put("addrs", addrArr)
                byName[name] = o
            }
        } catch (e: Exception) {
            return 0
        }

        val arr = JSONArray()
        for ((_, o) in byName) arr.put(o)
        val json = arr.toString()

        if (!force && json == lastJson) return arr.length()
        lastJson = json
        runCatching { Tailscale.setInterfaces(json) }
        return arr.length()
    }

    /**
     * Re-sync whenever connectivity changes, and return the callback so the
     * caller can unregister it.
     *
     * All three callbacks are kept: onCapabilitiesChanged fires far too often,
     * but [sync] now ignores pushes that would not change anything, so the
     * extra callbacks are cheap no-ops rather than connection-disrupting churn.
     */
    fun registerConnectivityCallback(context: Context, onChange: () -> Unit): ConnectivityManager.NetworkCallback? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange()
            override fun onLost(network: Network) = onChange()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onChange()
        }
        return runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                cb
            )
            cb
        }.getOrNull()
    }

    fun unregisterConnectivityCallback(context: Context, cb: ConnectivityManager.NetworkCallback?) {
        if (cb == null) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
    }
}
