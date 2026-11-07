// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChrootRemovalTest {
    private val script = ChrootSetup.removeScript("/data/local/linux/debian")

    @Test fun `nothing is deleted before the folder and the mounts are checked`() {
        val rm = script.indexOf("rm -rf")
        assertTrue(rm > 0)
        assertTrue(script.indexOf("/data/local/*/*") in 0 until rm)
        assertTrue(script.indexOf("/proc/self/mountinfo") in 0 until rm)
    }

    @Test fun `a mount at or under the system's folder stops it`() {
        assertTrue("\"\$P\"|\"\$P\"/*) echo \"Nothing was removed" in script)
        assertTrue("exit 3" in script)
    }

    @Test fun `it removes the system and what installs leave beside it, and only those`() {
        val rm = script.lines().single { it.trimStart().startsWith("rm ") }.trim()
        assertEquals("rm -rf -- \"\$R\" \"\$R.partial\" \"\$R\".old-*", rm)
    }

    @Test fun `the folder above it goes only if it's empty`() {
        assertTrue("rmdir \"\$P\"" in script)
        assertTrue(script.lines().none { it.contains("rm -rf") && it.contains("\$P") })
    }

    @Test fun `its path is quoted for the shell`() {
        assertTrue("R='/data/local/it'\\''s/debian'" in ChrootSetup.removeScript("/data/local/it's/debian"))
    }
}
