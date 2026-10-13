// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.store

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SystemAppsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun desktop(root: File, name: String, text: String) =
        File(root, "usr/share/applications/$name").apply { parentFile?.mkdirs() }.writeText(text)

    @Test fun `finds menu apps, skips hidden ones and duplicates`() = runBlocking {
        val root = tmp.newFolder("rootfs")
        val vscode = """
            [Desktop Entry]
            Type=Application
            Name=Visual Studio Code
            Comment=Code Editing. Redefined.
            Icon=vscode
            Exec=vscode %F
            Categories=Development;IDE;TextEditor;
        """.trimIndent()
        desktop(root, "code.desktop", vscode)
        desktop(root, "com.microsoft.VSCode.desktop", vscode.replace("Exec=vscode %F", "Exec=/usr/share/code/code %F"))
        desktop(root, "url-handler.desktop", "[Desktop Entry]\nName=Handler\nExec=vscode --open-url %U\nNoDisplay=true\n")
        desktop(root, "htop.desktop", "[Desktop Entry]\nType=Application\nName=Htop\nExec=htop\nTerminal=true\nCategories=System;Monitor;\n")
        desktop(root, "gnome-only.desktop", "[Desktop Entry]\nName=Only GNOME\nExec=x\nOnlyShowIn=GNOME;\n")
        File(root, "usr/share/icons/hicolor/128x128/apps/vscode.png").apply { parentFile?.mkdirs() }.writeText("png")

        val apps = SystemApps().apply { scan(root) }.apps.value

        assertEquals(listOf("Visual Studio Code", "Htop"), apps.map { it.name })
        val code = apps.first()
        assertEquals("system-code", code.id)
        assertEquals(listOf("vscode"), code.exec)
        assertEquals("Development", code.category)
        assertEquals(true, code.gui && code.permissions.network && code.permissions.display)
        assertEquals(File(root, "usr/share/icons/hicolor/128x128/apps/vscode.png").path, code.iconFile)
        assertEquals(false, apps[1].gui)
        assertEquals(emptyList<String>(), code.validate())
    }

    @Test fun `finds the package a desktop file came from`() = runBlocking {
        val root = tmp.newFolder("rootfs")
        desktop(root, "code.desktop", "[Desktop Entry]\nName=Code\nExec=vscode\n")
        File(root, "var/lib/dpkg/info").mkdirs()
        File(root, "var/lib/dpkg/info/code.list").writeText("/usr/share/applications/code.desktop\n/usr/bin/vscode\n")
        File(root, "var/lib/dpkg/info/other:arm64.list").writeText("/usr/bin/other\n")
        val apps = SystemApps().apply { scan(root) }
        assertEquals("code", apps.packageOf(apps.apps.value.single(), root))
    }
}
