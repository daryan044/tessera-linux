// Adapted from Samba's lib/replace/replace.c (rep_memset_explicit).
// Copyright (C) Andrew Tridgell and the Samba Team
// SPDX-License-Identifier: LGPL-3.0-or-later

/* The pieces of talloc's lib/replace that bionic (API 24) lacks and talloc.c actually uses.
 * Compiled into libtalloc.a instead of all of lib/replace/replace.c. */
#include <string.h>

void *rep_memset_explicit(void *block, int c, size_t size)
{
	void *ptr = memset(block, c, size);
	/* Keep the compiler from dropping the memset as a dead store. */
	__asm__ volatile("" : : "r"(block) : "memory");
	return ptr;
}
