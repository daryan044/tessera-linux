// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.store

/**
 * Something a Debian app said it couldn't find as it stopped, which a package would bring: a
 * shared library, a program, a Python module or a GObject typelib. [pattern] is what apt-file
 * looks for (a Perl regex against the paths in Debian's file lists), [label] how the user is told.
 */
sealed interface Missing {
    val label: String
    val pattern: String

    /** "error while loading shared libraries: libfoo.so.1". */
    data class Library(val name: String) : Missing {
        override val label get() = name
        override val pattern get() = "/${quote(name)}$"
    }

    /** "foo: command not found", in bash's words or dash's. */
    data class Command(val name: String) : Missing {
        override val label get() = "the program $name"
        override val pattern get() = "(^|/)(usr/)?s?bin/${quote(name)}$"
    }

    /** "ModuleNotFoundError: No module named 'yaml'". */
    data class PythonModule(val name: String) : Missing {
        override val label get() = "the Python module $name"
        override val pattern get() = "usr/lib/python3/dist-packages/${quote(name)}(/__init__\\.py|\\.py|\\.[^/]*\\.so)$"
    }

    /** "Namespace Gtk not available for version 3.0": a typelib, from a gir1.2 package. */
    data class Typelib(val namespace: String, val version: String?) : Missing {
        override val label get() = listOfNotNull(namespace, version).joinToString(" ") + " (GObject introspection)"
        override val pattern get() = "/girepository-1\\.0/${quote(namespace)}-${version?.let(::quote) ?: "[0-9.]+"}\\.typelib$"
    }

    companion object {
        /** [s] with what a regex would read as more than itself escaped; names are checked first. */
        internal fun quote(s: String) = s.replace(Regex("[.+]")) { "\\" + it.value }
    }
}

object MissingPackages {
    private const val NAME = "[A-Za-z0-9][A-Za-z0-9._+-]*"
    private val LIBRARY = Regex("""error while loading shared libraries: ($NAME): cannot open shared object file""")
    // bash: "bash: foo: command not found", "/app/run.sh: line 3: foo: command not found";
    // dash: "sh: 1: foo: not found"; env: "env: 'foo': No such file or directory".
    private val COMMAND = listOf(
        Regex("""(?:^|[\s:])($NAME): command not found"""),
        Regex("""^\S*sh: \d+: ($NAME): not found""", RegexOption.MULTILINE),
        Regex("""env: '($NAME)': No such file or directory"""),
    )
    private val PYTHON = Regex("""ModuleNotFoundError: No module named '([A-Za-z_][A-Za-z0-9_]*)""")
    private val TYPELIB = Regex("""Namespace ([A-Za-z][A-Za-z0-9]*) not available(?: for version ([0-9][0-9.]*))?""")

    /** What [output] says is missing first, or null when it names nothing a package would bring. */
    fun find(output: String): Missing? = listOfNotNull(
        LIBRARY.find(output)?.let { it.range.first to Missing.Library(it.groupValues[1]) },
        COMMAND.mapNotNull { r -> r.find(output) }.minByOrNull { it.range.first }?.let { it.range.first to Missing.Command(it.groupValues[1]) },
        PYTHON.find(output)?.let { it.range.first to Missing.PythonModule(it.groupValues[1]) },
        TYPELIB.find(output)?.let { it.range.first to Missing.Typelib(it.groupValues[1], it.groupValues[2].ifEmpty { null }) },
    ).minByOrNull { it.first }?.second

    /** apt's word for "can't", which the install queue takes as the reason a job failed. */
    private const val NONE_FOUND = "E: No Debian package has"

    /**
     * Finds and installs the package that has [missing], as root: apt-file first (once), and
     * Debian's lists of every package's files (once a week at most), then the package itself. Its
     * name goes out on a line of its own ([PACKAGE_MARK] name), for the queue's log.
     */
    fun script(missing: Missing): String {
        val label = missing.label.replace("'", "")
        return "set -e; export DEBIAN_FRONTEND=noninteractive; " +
            "if ! command -v apt-file >/dev/null 2>&1; then echo 'Installing apt-file, which finds packages by their files'; " +
            "apt-get update; apt-get install -y --no-install-recommends apt-file; fi; " +
            "if [ -z \"\$(find /var/lib/apt/lists -maxdepth 1 -name '*Contents-*' -mtime -7 2>/dev/null | head -n 1)\" ]; then " +
            "echo \"Downloading Debian's lists of every package's files (tens of MB, once a week at most)\"; apt-file update; fi; " +
            "echo 'Looking for the package with $label'; " +
            "pkg=\$(apt-file search -x '${missing.pattern}' 2>/dev/null | cut -d: -f1 | grep -v -e '-dbg\$' -e '-dbgsym\$' | head -n 1); " +
            "[ -n \"\$pkg\" ] || { echo '$NONE_FOUND $label'; exit 1; }; " +
            "echo \"$PACKAGE_MARK \$pkg\"; echo \"Installing \$pkg\"; " +
            "apt-get install -y --no-install-recommends \"\$pkg\""
    }

    /** Starts the line that names the package found. */
    const val PACKAGE_MARK = "Found it in"
}
