// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import java.util.Locale
import java.util.TimeZone

/**
 * The phone's time zone and language, for Linux apps (#34), which otherwise ran on UTC and in
 * English. Set when a session starts, so a change on the phone reaches apps started after it.
 */
internal object GuestLocale {
    /** Read by the GUI wrapper, which picks what the guest can use (see [ContainerBackend]). */
    const val TZ_POSIX = "TESSERA_TZ_POSIX"
    const val LANG = "TESSERA_LANG"
    const val LANGUAGE = "TESSERA_LANGUAGE"

    fun env(tz: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault(), now: Long = System.currentTimeMillis()) = buildMap {
        // The tz database name ("Europe/Berlin"): what ICU (Firefox, Chromium) and Java want,
        // and what Flathub runtimes have the data for. Debian's slim images ship without
        // tzdata, where glibc would read it as UTC, so the same rules also go as a POSIX string.
        put("TZ", tz.id)
        put(TZ_POSIX, posixTz(tz, now))
        posixLocale(locale)?.let { (lang, language) ->
            put(LANG, lang)
            put(LANGUAGE, language)
        }
    }

    /**
     * [tz]'s rules for the year around [now] as a POSIX TZ string, which needs no tzdata:
     * "<+01>-1<+02>,87/2,297/3" for Berlin in 2026. The change dates are the year's own (the
     * zero-based "n" form), exact for that year; sessions started in a later year get its dates.
     */
    internal fun posixTz(tz: TimeZone, now: Long): String {
        val year = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = now
            set(get(java.util.Calendar.YEAR), java.util.Calendar.JANUARY, 1, 0, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val from = year.timeInMillis
        year.add(java.util.Calendar.YEAR, 1)
        val to = year.timeInMillis
        // Every change of offset in the year, to the minute: hourly steps, then a binary search.
        val changes = mutableListOf<Long>()
        var t = from
        while (t < to) {
            val next = minOf(t + HOUR, to)
            if (tz.getOffset(t) != tz.getOffset(next)) {
                var lo = t
                var hi = next
                while (hi - lo > MINUTE) {
                    val mid = lo + (hi - lo) / 2 / MINUTE * MINUTE
                    if (mid == lo) break
                    if (tz.getOffset(mid) == tz.getOffset(lo)) lo = mid else hi = mid
                }
                changes += hi
            }
            t = next
        }
        val current = tz.getOffset(now)
        if (changes.size != 2) return name(current) + offset(current)
        val (a, b) = changes
        val before = tz.getOffset(a - 1)
        val after = tz.getOffset(a)
        // The larger offset is summer time, whichever hemisphere: starts at its change into it.
        val std = minOf(before, after)
        val dst = maxOf(before, after)
        if (std == dst) return name(current) + offset(current)
        val (start, end) = if (after > before) a to b else b to a
        return name(std) + offset(std) + name(dst) + offset(dst) + "," + rule(start, std) + "," + rule(end, dst)
    }

    /** "<+0530>": a name that is just the offset, so no abbreviation table is needed. */
    private fun name(ms: Int): String {
        val m = ms / MINUTE.toInt()
        val sign = if (m < 0) '-' else '+'
        val h = kotlin.math.abs(m) / 60
        val min = kotlin.math.abs(m) % 60
        return "<" + sign + "%02d".format(h) + (if (min != 0) "%02d".format(min) else "") + ">"
    }

    /** POSIX counts west of Greenwich as positive: UTC+1 is "-1". */
    private fun offset(ms: Int): String {
        val m = -ms / MINUTE.toInt()
        val h = kotlin.math.abs(m) / 60
        val min = kotlin.math.abs(m) % 60
        return (if (m < 0) "-" else "") + h + (if (min != 0) ":%02d".format(min) else "")
    }

    /** The change at [at], in the wall-clock time in force before it ([offsetBefore]). */
    private fun rule(at: Long, offsetBefore: Int): String {
        val c = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = at + offsetBefore }
        val day = c.get(java.util.Calendar.DAY_OF_YEAR) - 1
        val h = c.get(java.util.Calendar.HOUR_OF_DAY)
        val m = c.get(java.util.Calendar.MINUTE)
        return "$day/$h" + if (m != 0) ":%02d".format(m) else ""
    }

    /**
     * The glibc locale for [locale] and the LANGUAGE list gettext walks: ("pt_BR.UTF-8",
     * "pt_BR:pt"). Null for English in the US and for a language Android doesn't name.
     */
    internal fun posixLocale(locale: Locale): Pair<String, String>? {
        val lang = locale.language.takeIf { it.length in 2..3 } ?: return null
        val country = locale.country.takeIf { it.length == 2 }
        if (lang == "en" && (country == null || country == "US")) return null
        // Serbian in Latin script is its own glibc locale.
        val modifier = if (lang == "sr" && locale.script == "Latn") "@latin" else ""
        val base = if (country != null) "${lang}_$country" else lang
        val glibc = if (country != null) "$base.UTF-8$modifier" else "$base$modifier"
        return glibc to listOf("$base$modifier", lang).distinct().joinToString(":")
    }

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
}
