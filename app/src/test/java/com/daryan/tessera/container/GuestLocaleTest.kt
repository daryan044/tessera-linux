// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class GuestLocaleTest {
    /** 2026-07-01 12:00 UTC. */
    private val july2026 = 1782907200000L

    private fun posix(id: String) = GuestLocale.posixTz(TimeZone.getTimeZone(id), july2026)

    @Test fun `zones with summer time get both offsets and the year's change dates`() {
        // Last Sunday of March (day 87, 0-based) at 02:00 CET; last of October (297) at 03:00 CEST.
        assertEquals("<+01>-1<+02>-2,87/2,297/3", posix("Europe/Berlin"))
        // Second Sunday of March (66) at 02:00, first of November (304) at 02:00.
        assertEquals("<-05>5<-04>4,66/2,304/2", posix("America/New_York"))
        // Southern hemisphere: summer starts late in the year (first Sunday of October, 276)
        // and ends early (first of April, 94), both at local 02:00 / 03:00.
        assertEquals("<+10>-10<+11>-11,276/2,94/3", posix("Australia/Sydney"))
    }

    @Test fun `zones without summer time get their offset, half hours included`() {
        assertEquals("<+03>-3", posix("Asia/Baghdad"))
        assertEquals("<+0530>-5:30", posix("Asia/Kolkata"))
        assertEquals("<-03>3", posix("America/Sao_Paulo"))
        assertEquals("<+00>0", posix("UTC"))
    }

    @Test fun `env carries the zone name and the fallback`() {
        val env = GuestLocale.env(TimeZone.getTimeZone("Europe/Berlin"), Locale.GERMANY, july2026)
        assertEquals("Europe/Berlin", env["TZ"])
        assertEquals("<+01>-1<+02>-2,87/2,297/3", env[GuestLocale.TZ_POSIX])
        assertEquals("de_DE.UTF-8", env[GuestLocale.LANG])
        assertEquals("de_DE:de", env[GuestLocale.LANGUAGE])
    }

    @Test fun `phone languages map to glibc locales`() {
        assertEquals("pt_BR.UTF-8" to "pt_BR:pt", GuestLocale.posixLocale(Locale("pt", "BR")))
        assertEquals("ar_IQ.UTF-8" to "ar_IQ:ar", GuestLocale.posixLocale(Locale("ar", "IQ")))
        assertEquals("en_GB.UTF-8" to "en_GB:en", GuestLocale.posixLocale(Locale.UK))
        assertEquals("sr_RS.UTF-8@latin" to "sr_RS@latin:sr", GuestLocale.posixLocale(Locale.forLanguageTag("sr-Latn-RS")))
        assertEquals("fr" to "fr", GuestLocale.posixLocale(Locale("fr")))
        // US English is what apps already speak.
        assertNull(GuestLocale.posixLocale(Locale.US))
        assertNull(GuestLocale.posixLocale(Locale.ROOT))
    }
}
