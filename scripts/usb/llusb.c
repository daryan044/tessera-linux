// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The guest half of the USB bridge for PRoot mode, loaded into every process of an app through
// LD_PRELOAD. Android doesn't let apps open /dev/bus/usb nodes; the launcher instead opens each
// device the app was granted through Android's USB permission and hands the open files down
// (inherited fds, listed in LL_USB_FDS as "/dev/bus/usb/001/002=7;..."). Opening one of those
// paths here returns a copy of its fd, so libusb, and programs that walk usbfs themselves, work
// unchanged. The launcher also puts empty files at those paths so directory scans find them.

#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define MAX_DEVICES 32

static struct { char path[32]; int fd; } devices[MAX_DEVICES];
static int device_count;

__attribute__((constructor)) static void load(void) {
    const char *list = getenv("LL_USB_FDS");
    while (list && *list && device_count < MAX_DEVICES) {
        const char *end = strchr(list, ';');
        size_t len = end ? (size_t) (end - list) : strlen(list);
        const char *eq = memchr(list, '=', len);
        if (eq && (size_t) (eq - list) < sizeof(devices[0].path)) {
            memcpy(devices[device_count].path, list, eq - list);
            devices[device_count].path[eq - list] = 0;
            devices[device_count].fd = atoi(eq + 1);
            device_count++;
        }
        list = end ? end + 1 : NULL;
    }
}

/** A new fd for [path] when it's a bridged device, -2 when it isn't one. */
static int bridged(const char *path, int flags) {
    if (!path) return -2;
    for (int i = 0; i < device_count; i++) {
        if (strcmp(path, devices[i].path) != 0) continue;
        int fd = fcntl(devices[i].fd, (flags & O_CLOEXEC) ? F_DUPFD_CLOEXEC : F_DUPFD, 0);
        // Copies share the file position, and reading a usbfs node returns the device's
        // descriptors from it: start each open at the beginning, as a fresh open() would.
        if (fd >= 0) lseek(fd, 0, SEEK_SET);
        return fd;
    }
    return -2;
}

static mode_t mode_arg(int flags, va_list ap) {
    return (flags & O_CREAT) || (flags & O_TMPFILE) == O_TMPFILE ? va_arg(ap, mode_t) : 0;
}

#define REAL(name, ...) \
    static typeof(name) *real; \
    if (!real) real = (typeof(name) *) dlsym(RTLD_NEXT, #name); \
    if (!real) { errno = ENOSYS; return -1; } \
    return real(__VA_ARGS__)

int open(const char *path, int flags, ...) {
    va_list ap; va_start(ap, flags); mode_t mode = mode_arg(flags, ap); va_end(ap);
    int fd = bridged(path, flags);
    if (fd != -2) return fd;
    REAL(open, path, flags, mode);
}

int open64(const char *path, int flags, ...) {
    va_list ap; va_start(ap, flags); mode_t mode = mode_arg(flags, ap); va_end(ap);
    int fd = bridged(path, flags);
    if (fd != -2) return fd;
    REAL(open64, path, flags, mode);
}

int openat(int dir, const char *path, int flags, ...) {
    va_list ap; va_start(ap, flags); mode_t mode = mode_arg(flags, ap); va_end(ap);
    int fd = path && path[0] == '/' ? bridged(path, flags) : -2;
    if (fd != -2) return fd;
    REAL(openat, dir, path, flags, mode);
}

int openat64(int dir, const char *path, int flags, ...) {
    va_list ap; va_start(ap, flags); mode_t mode = mode_arg(flags, ap); va_end(ap);
    int fd = path && path[0] == '/' ? bridged(path, flags) : -2;
    if (fd != -2) return fd;
    REAL(openat64, dir, path, flags, mode);
}

// What open() compiles to with _FORTIFY_SOURCE when the flags aren't known at compile time.
int __open_2(const char *path, int flags) {
    int fd = bridged(path, flags);
    if (fd != -2) return fd;
    REAL(__open_2, path, flags);
}

int __open64_2(const char *path, int flags) {
    int fd = bridged(path, flags);
    if (fd != -2) return fd;
    REAL(__open64_2, path, flags);
}
