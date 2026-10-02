// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Matt <matt@mattsgateway.dpdns.org>
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version. See LICENSE for the full text.

package org.dpdns.mattsgateway.sshwithtailscale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardRuleTest {

    @Test
    fun jsonRoundTripKeepsEveryField() {
        val rule = ForwardRule(
            remote = true,
            bindHost = "0.0.0.0",
            bindPort = 9000,
            targetHost = "127.0.0.1",
            targetPort = 8080
        )
        assertEquals(rule, ForwardRule.fromJson(rule.toJson()))
    }

    @Test
    fun labelSpellsOutTheDirection() {
        val local = ForwardRule(false, "127.0.0.1", 8080, "10.0.0.5", 80)
        val remote = ForwardRule(true, "127.0.0.1", 9000, "127.0.0.1", 8000)
        assertTrue(local.label().contains("本地 -L"))
        assertFalse(local.label().contains("-R"))
        assertTrue(remote.label().contains("远程 -R"))
        assertTrue(local.label().contains("127.0.0.1:8080"))
        assertTrue(local.label().contains("10.0.0.5:80"))
    }

    @Test
    fun missingFieldsFallBackToSafeDefaults() {
        val parsed = ForwardRule.fromJson(org.json.JSONObject())
        assertEquals(false, parsed.remote)
        assertEquals("127.0.0.1", parsed.bindHost)
        assertEquals(0, parsed.bindPort)
    }
}
