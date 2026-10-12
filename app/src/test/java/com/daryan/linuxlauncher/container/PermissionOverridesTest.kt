// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PermissionOverridesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val app = PermissionManifest(
        id = "firefox-esr", name = "Firefox", exec = listOf("/usr/bin/firefox-esr"), gui = true,
        permissions = Permissions(network = true, display = true, audio = true),
    )

    @Test fun `overrides apply, persist, and drop when back to defaults`() {
        val file = File(tmp.root, "permissions.json")
        val store = PermissionOverrides(file)
        val muted = app.permissions.copy(audio = false, filesystem = listOf(FsGrant("/storage/emulated/0/Pictures", "/home/user/Pictures", readOnly = true)))

        assertTrue(store.set(app, muted, app.permissions).isEmpty())
        assertEquals(muted, store.apply(app).permissions)
        // Read back by a fresh store, as after a restart.
        assertEquals(muted, PermissionOverrides(file).apply(app).permissions)

        store.set(app, app.permissions, app.permissions)
        assertEquals(emptyMap<String, Permissions>(), store.overrides.value)
    }

    @Test fun `invalid permissions are refused`() {
        val store = PermissionOverrides(File(tmp.root, "permissions.json"))
        val bad = app.permissions.copy(filesystem = listOf(FsGrant("/sdcard", "/usr/share")))
        assertTrue(store.set(app, bad, app.permissions).isNotEmpty())
        assertEquals(app, store.apply(app))
    }
}
