// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

// Spawns a process on a fresh pseudo-terminal. Used for terminal sessions and for every
// container launch (proot or su/chroot), so the child always has a controlling tty.
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static char **to_cstrings(JNIEnv *env, jobjectArray arr) {
    jsize n = arr ? (*env)->GetArrayLength(env, arr) : 0;
    char **out = calloc((size_t)n + 1, sizeof(char *));
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, arr, i);
        const char *utf = (*env)->GetStringUTFChars(env, s, NULL);
        out[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, s, utf);
        (*env)->DeleteLocalRef(env, s);
    }
    return out;
}

static void free_cstrings(char **v) {
    if (!v) return;
    for (char **p = v; *p; p++) free(*p);
    free(v);
}

static int compare_ints(const void *a, const void *b) {
    return *(const int *)a - *(const int *)b;
}

// In the child: closes every descriptor above stderr but those in keep (sorted). Otherwise the
// child holds whatever the app had open, pipes to other children included: a root shell the
// task manager had finished with never saw its input end, and lived on as root.
static void close_inherited(const int *keep, int n) {
    unsigned int from = 3;
    for (int i = 0; i <= n; i++) {
        unsigned int to = i < n ? (unsigned int)keep[i] : ~0U;
        if (to < from) continue;
        if (to > from) {
            unsigned int last = to - 1;
#ifdef __NR_close_range
            if (syscall(__NR_close_range, from, last, 0) == 0) { from = to + 1; continue; }
#endif
            // Kernels before 5.9: one by one, up to the descriptor limit.
            struct rlimit rl;
            unsigned int max = getrlimit(RLIMIT_NOFILE, &rl) == 0 && rl.rlim_cur != RLIM_INFINITY
                ? (unsigned int)rl.rlim_cur : 65536;
            if (last >= max) last = max - 1;
            for (unsigned int fd = from; fd <= last; fd++) close((int)fd);
        }
        from = to + 1;
        if (to == ~0U) break;
    }
}

static void throw_io(JNIEnv *env, const char *what) {
    char msg[256];
    snprintf(msg, sizeof msg, "%s: %s", what, strerror(errno));
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), msg);
}

JNIEXPORT jint JNICALL
Java_com_daryan_tessera_container_Pty_spawn(JNIEnv *env, jclass clazz, jstring jpath,
                                                 jobjectArray jargv, jobjectArray jenvp,
                                                 jstring jcwd, jint rows, jint cols,
                                                 jintArray jpid, jintArray jkeep) {
    (void)clazz;
    int master = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (master < 0) { throw_io(env, "open /dev/ptmx"); return -1; }

    char slave_name[64];
    if (grantpt(master) || unlockpt(master) || ptsname_r(master, slave_name, sizeof slave_name)) {
        throw_io(env, "pty setup");
        close(master);
        return -1;
    }

    // UTF-8 aware line discipline, like a desktop terminal.
    struct termios tios;
    tcgetattr(master, &tios);
    tios.c_iflag |= IUTF8;
    tios.c_iflag &= ~(IXON | IXOFF);
    tcsetattr(master, TCSANOW, &tios);
    struct winsize ws = {.ws_row = (unsigned short)rows, .ws_col = (unsigned short)cols};
    ioctl(master, TIOCSWINSZ, &ws);

    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    const char *cwd = jcwd ? (*env)->GetStringUTFChars(env, jcwd, NULL) : NULL;
    char **argv = to_cstrings(env, jargv);
    char **envp = to_cstrings(env, jenvp);
    // Descriptors the child keeps (USB devices handed to it), sorted; read before forking.
    int nkeep = jkeep ? (*env)->GetArrayLength(env, jkeep) : 0;
    int *keep = calloc((size_t)nkeep + 1, sizeof(int));
    if (nkeep) (*env)->GetIntArrayRegion(env, jkeep, 0, nkeep, keep);
    qsort(keep, (size_t)nkeep, sizeof(int), compare_ints);

    pid_t pid = fork();
    if (pid == 0) {
        // Child: only async-signal-safe calls from here until exec.
        sigset_t all;
        sigfillset(&all);
        sigprocmask(SIG_UNBLOCK, &all, NULL);
        close(master);
        setsid();
        int slave = open(slave_name, O_RDWR);
        if (slave < 0) _exit(126);
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, 0);
        dup2(slave, 1);
        dup2(slave, 2);
        if (slave > 2) close(slave);
        close_inherited(keep, nkeep);
        if (cwd && chdir(cwd) != 0) (void)chdir("/");
        execve(path, argv, envp);
        _exit(127);
    }

    int saved_errno = errno;
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (cwd) (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
    free_cstrings(argv);
    free_cstrings(envp);
    free(keep);

    if (pid < 0) {
        errno = saved_errno;
        throw_io(env, "fork");
        close(master);
        return -1;
    }
    jint p = pid;
    (*env)->SetIntArrayRegion(env, jpid, 0, 1, &p);
    return master;
}

JNIEXPORT void JNICALL
Java_com_daryan_tessera_container_Pty_setWindowSize(JNIEnv *env, jclass clazz, jint fd,
                                                         jint rows, jint cols) {
    (void)env; (void)clazz;
    struct winsize ws = {.ws_row = (unsigned short)rows, .ws_col = (unsigned short)cols};
    ioctl(fd, TIOCSWINSZ, &ws);
}

// Blocks until the process exits. Returns the exit code, or -signal if it was killed.
JNIEXPORT jint JNICALL
Java_com_daryan_tessera_container_Pty_waitFor(JNIEnv *env, jclass clazz, jint pid) {
    (void)env; (void)clazz;
    int status;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) return -1;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_daryan_tessera_container_Pty_close(JNIEnv *env, jclass clazz, jint fd) {
    (void)env; (void)clazz;
    close(fd);
}

/*
 * fcntl(2) with an int argument. android.system.Os.fcntlInt is a hidden API on Android 10 and
 * older for apps targeting API 27+ (Tessera targets 28), so it fails there; this doesn't.
 * Returns fcntl's result, or -errno.
 */
JNIEXPORT jint JNICALL
Java_com_daryan_tessera_container_Pty_fcntlInt(JNIEnv *env, jclass clazz, jint fd, jint cmd, jint arg) {
    (void)env; (void)clazz;
    int r = fcntl(fd, cmd, arg);
    return r < 0 ? -errno : r;
}
