// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MissingPackagesTest {
    @Test fun `a missing shared library`() {
        val out = "mbpixbuf: unable to use XShm. DISPLAY remote?\n" +
            "/usr/bin/gimp: error while loading shared libraries: libbabl-0.1.so.0: cannot open shared object file: No such file or directory\n"
        val m = MissingPackages.find(out)
        assertEquals(Missing.Library("libbabl-0.1.so.0"), m)
        assertEquals("/libbabl-0\\.1\\.so\\.0$", m!!.pattern)
    }

    @Test fun `a missing program, in bash's words, dash's and env's`() {
        assertEquals(Missing.Command("java"), MissingPackages.find("/app/bin/aat.sh: line 3: java: command not found"))
        assertEquals(Missing.Command("zenity"), MissingPackages.find("bash: zenity: command not found"))
        assertEquals(Missing.Command("xdg-open"), MissingPackages.find("sh: 1: xdg-open: not found"))
        assertEquals(Missing.Command("python2"), MissingPackages.find("/usr/bin/env: 'python2': No such file or directory"))
        assertEquals("(^|/)(usr/)?s?bin/xdg-open$", Missing.Command("xdg-open").pattern)
    }

    @Test fun `a missing Python module, by its top level`() {
        val out = "Traceback (most recent call last):\n  File \"/usr/bin/tool\", line 4, in <module>\n    import yaml.loader\n" +
            "ModuleNotFoundError: No module named 'yaml'\n"
        assertEquals(Missing.PythonModule("yaml"), MissingPackages.find(out))
        assertEquals(Missing.PythonModule("PIL"), MissingPackages.find("ModuleNotFoundError: No module named 'PIL.Image'"))
    }

    @Test fun `a missing typelib, with its version when given`() {
        val m = MissingPackages.find("ValueError: Namespace Gtk not available for version 3.0")
        assertEquals(Missing.Typelib("Gtk", "3.0"), m)
        assertEquals("/girepository-1\\.0/Gtk-3\\.0\\.typelib$", m!!.pattern)
        assertEquals("/girepository-1\\.0/Handy-[0-9.]+\\.typelib$", MissingPackages.find("Namespace Handy not available")!!.pattern)
    }

    @Test fun `the first thing named wins, and noise names nothing`() {
        val out = "sh: 1: notify-send: not found\nerror while loading shared libraries: libfoo.so.1: cannot open shared object file"
        assertEquals(Missing.Command("notify-send"), MissingPackages.find(out))
        assertNull(MissingPackages.find("mbpixbuf: unable to use XShm. DISPLAY remote?\nGdk-Message: Error 71 (Protocol error) dispatching to Wayland display."))
        assertNull(MissingPackages.find("Segmentation fault"))
    }

    @Test fun `the script finds the package, then installs it`() {
        val s = MissingPackages.script(Missing.Library("libbabl-0.1.so.0"))
        assertTrue("apt-get install -y --no-install-recommends apt-file" in s)
        assertTrue("apt-file update" in s)
        assertTrue("apt-file search -x '/libbabl-0\\.1\\.so\\.0$'" in s)
        assertTrue("E: No Debian package has libbabl-0.1.so.0" in s)
        assertTrue(s.indexOf("apt-file search") < s.lastIndexOf("apt-get install -y --no-install-recommends \"\$pkg\""))
    }
}
