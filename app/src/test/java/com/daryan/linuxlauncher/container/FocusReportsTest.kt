// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.container

import org.junit.Assert.assertEquals
import org.junit.Test

class FocusReportsTest {
    @Test fun `latest complete report wins`() {
        assertEquals(true, FocusReports.parse("text\n"))
        assertEquals(false, FocusReports.parse("text\nnone\n"))
        assertEquals(true, FocusReports.parse("none\ntext\nte"))
        assertEquals(null, FocusReports.parse(""))
    }
}
