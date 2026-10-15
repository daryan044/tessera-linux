// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlatpakTest {
    // As installed on the test phone (trimmed).
    private val files = mapOf(
        "/var/lib/flatpak/app/io.mpv.Mpv/current/active/metadata" to """
            [Application]
            name=io.mpv.Mpv
            runtime=org.freedesktop.Platform/aarch64/25.08
            sdk=org.freedesktop.Sdk/aarch64/25.08
            command=mpv

            [Environment]
            XCURSOR_PATH=/run/host/user-share/icons:/run/host/share/icons
            LC_NUMERIC=C
        """.trimIndent(),
        "/var/lib/flatpak/runtime/org.freedesktop.Platform/aarch64/25.08/active/metadata" to """
            [Runtime]
            name = org.freedesktop.Platform

            [Environment]
            GI_TYPELIB_PATH = /app/lib/girepository-1.0
            XDG_DATA_DIRS = /app/share:/usr/share:/usr/share/runtime/share:/run/host/user-share:/run/host/share

            [Extension org.freedesktop.Platform.GL]
            versions = 25.08;25.08-extra;1.4
            version = 1.4
            directory = lib/aarch64-linux-gnu/GL
        """.trimIndent(),
        "/var/lib/flatpak/runtime/org.freedesktop.Platform.GL.default/aarch64/25.08/active/files/.ref" to "",
    )

    private fun resolve(id: String = "io.mpv.Mpv", have: Map<String, String> = files) = runBlocking { Flatpak.resolve(id) { have[it] } }

    @Test fun `resolves the app, its runtime and the runtime's GL extension`() {
        val ref = resolve()!!
        assertEquals("/var/lib/flatpak/app/io.mpv.Mpv/current/active/files", ref.appDir)
        assertEquals("/var/lib/flatpak/runtime/org.freedesktop.Platform/aarch64/25.08/active/files", ref.runtimeDir)
        assertEquals("/var/lib/flatpak/runtime/org.freedesktop.Platform.GL.default/aarch64/25.08/active/files", ref.glDir)
        assertEquals("/usr/lib/aarch64-linux-gnu/GL/default", ref.glMount)
        assertEquals("mpv", ref.command)
        assertEquals("C", ref.env["LC_NUMERIC"])
        assertEquals("/app/lib/girepository-1.0", ref.env["GI_TYPELIB_PATH"])
    }

    @Test fun `no GL extension installed means none mounted`() {
        val ref = resolve(have = files.filterKeys { !it.endsWith(".ref") })!!
        assertNull(ref.glDir)
        assertNull(ref.glMount)
    }

    @Test fun `missing app or runtime resolves to nothing`() {
        assertNull(resolve("org.example.Missing"))
        assertNull(resolve(have = files.filterKeys { "runtime/org.freedesktop.Platform/" !in it }))
    }

    @Test fun `the app's environment goes under the session's, with the theme's data dir first`() {
        val ref = resolve()!!
        val session = mapOf("XCURSOR_PATH" to "/opt/tessera-theme/share/icons", "XDG_DATA_DIRS" to "/tmp/.X11-unix/tessera-theme/share:/usr/local/share:/usr/share")
        val env = Flatpak.env(ref, gpu = false, sessionEnv = session)
        assertFalse("XCURSOR_PATH" in env)
        assertEquals("/tmp/.X11-unix/tessera-theme/share:/app/share:/usr/share:/usr/share/runtime/share", env["XDG_DATA_DIRS"])
        assertEquals("/app/lib:/usr/lib/aarch64-linux-gnu/GL/default/lib", env["LD_LIBRARY_PATH"])
        assertEquals("/usr/lib/aarch64-linux-gnu/GL/default/share/glvnd/egl_vendor.d", env["__EGL_VENDOR_LIBRARY_DIRS"])
        val gpu = Flatpak.env(ref, gpu = true, sessionEnv = session)
        assertEquals("/opt/tessera-gpu/lib:/app/lib", gpu["LD_LIBRARY_PATH"])
        assertFalse("__EGL_VENDOR_LIBRARY_DIRS" in gpu)
    }

    @Test fun `setup and install scripts`() {
        assertTrue(Flatpak.isSetUp("[core]\nrepo_version=1\n\n[remote \"flathub\"]\nurl=https://dl.flathub.org/repo/\n"))
        assertFalse(Flatpak.isSetUp("[core]\n"))
        assertFalse(Flatpak.isSetUp(null))
        assertTrue("flatpak install -y --noninteractive --arch=aarch64 flathub io.mpv.Mpv;" in Flatpak.installScript("io.mpv.Mpv"))
        assertFalse(Flatpak.validId("io.mpv.Mpv; rm -rf /"))
        assertFalse(Flatpak.validId("mpv"))
    }
}
