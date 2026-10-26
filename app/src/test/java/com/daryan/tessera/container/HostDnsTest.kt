// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HostDnsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `uses the network's servers, public ones only when there are none`() {
        assertEquals("${HostDns.AUTO_MARKER}\nnameserver 10.0.0.1\nnameserver fe80::1%wlan0\n", HostDns.resolvConf(listOf("10.0.0.1", "fe80::1%wlan0")))
        assertEquals("${HostDns.AUTO_MARKER}\nnameserver 1.1.1.1\nnameserver 8.8.8.8\n", HostDns.resolvConf(emptyList()))
        assertEquals(3, HostDns.resolvConf(listOf("a", "b", "c", "d")).lines().count { it.startsWith("nameserver") })
    }

    @Test fun `rewrites automatic and old default files, keeps the user's`() {
        val f = tmp.newFile("resolv.conf")
        f.writeText(HostDns.LEGACY)
        HostDns.refresh(f, listOf("192.168.1.1"))
        assertEquals(HostDns.resolvConf(listOf("192.168.1.1")), f.readText())
        HostDns.refresh(f, listOf("10.1.1.1"))
        assertTrue(f.readText().contains("10.1.1.1"))
        f.writeText("nameserver 9.9.9.9\n")
        HostDns.refresh(f, listOf("10.1.1.1"))
        assertEquals("nameserver 9.9.9.9\n", f.readText())
    }

    @Test fun `saving from the editor makes the file the user's`() {
        val saved = HostDns.userOwned(HostDns.resolvConf(listOf("10.0.0.1")))
        assertFalse(HostDns.isAutomatic(saved))
        assertEquals("nameserver 10.0.0.1\n", saved)
    }
}
