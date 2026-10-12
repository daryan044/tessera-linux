// Copyright (C) 2026 Daryan
// SPDX-License-Identifier: GPL-3.0-or-later

/* Stand-in for the config.h that talloc's waf configure step generates. Describes Android's
 * bionic libc at API 24; only the macros lib/replace/replace.h and talloc.c look at. */
#ifndef TALLOC_ANDROID_CONFIG_H
#define TALLOC_ANDROID_CONFIG_H

#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 5
#define TALLOC_BUILD_VERSION_RELEASE 0

#define HAVE_INTTYPES_H 1
#define HAVE_STDINT_H 1
#define HAVE_STDBOOL_H 1
#define HAVE_STDDEF_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_UNISTD_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_SYS_SYSMACROS_H 1
#define HAVE_LIMITS_H 1
#define HAVE_SYS_PARAM_H 1
#define HAVE_MALLOC_H 1
#define HAVE_DLFCN_H 1
#define HAVE_LINUX_TYPES_H 1
#define HAVE_SYS_AUXV_H 1

#define HAVE_BOOL 1
#define HAVE__BOOL 1
#define HAVE_INTPTR_T 1
#define HAVE_UINTPTR_T 1
#define HAVE_PTRDIFF_T 1
#define HAVE_VA_COPY 1
#define HAVE_C99_VSNPRINTF 1
#define HAVE_VARIADIC_MACROS 1
#define HAVE___VA_ARGS__ 1
#define HAVE_FUNCTION_MACRO 1
#define HAVE_FUNCTION_ATTRIBUTE_DESTRUCTOR 1
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE_DESTRUCTOR_ATTRIBUTE 1
#define HAVE___ATTRIBUTE__ 1
#define HAVE_BUILTIN_EXPECT 1
#define HAVE___BUILTIN_EXPECT 1

#define HAVE_STRDUP 1
#define HAVE_STRNDUP 1
#define HAVE_STRNLEN 1
#define HAVE_STRLCPY 1
#define HAVE_STRLCAT 1
#define HAVE_MEMMOVE 1
#define HAVE_MEMMEM 1
#define HAVE_STRCASESTR 1
#define HAVE_STRTOK_R 1
#define HAVE_STRTOLL 1
#define HAVE_STRTOULL 1
#define HAVE_SETENV 1
#define HAVE_UNSETENV 1
#define HAVE_VSNPRINTF 1
#define HAVE_SNPRINTF 1
#define HAVE_ASPRINTF 1
#define HAVE_VASPRINTF 1
#define HAVE_GETAUXVAL 1
#define HAVE_MKDTEMP 1
#define HAVE_DLOPEN 1
#define HAVE_DLSYM 1
#define HAVE_DLERROR 1
#define HAVE_DLCLOSE 1
#define HAVE_GETPAGESIZE 1
#define HAVE_USLEEP 1

#endif
