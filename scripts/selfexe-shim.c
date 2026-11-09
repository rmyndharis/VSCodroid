// Two answers a program started on Android gets wrong, and that Codex cannot work
// without. Preloaded by the Codex launcher and by nothing else.
//
// 1. /proc/self/exe.
//
// `/system/bin/linker64 <file>` is the only way this app can start a binary
// under filesDir: SELinux refuses execve on the file itself, and the loader
// maps it instead. The kernel then reports the loader, not the file, as the
// process's executable, so a program that asks where it lives in order to find
// a helper beside itself is told the loader's directory. Codex looked for
// codex-code-mode-host in /apex/com.android.runtime/bin and its agent had no
// way to run a command.
//
// The exec interceptor (libtermux-exec.so) already exports the real path of
// whatever it starts through the loader, in TERMUX_EXEC__PROC_SELF_EXE, for
// the programs that know to read it. Codex does not. This library answers the
// one question such a program asks, readlink("/proc/self/exe"), with that path.
//
// Only when the kernel's own answer is the loader. A process that was executed
// directly keeps its answer, so a variable inherited from a parent that ran
// through the loader cannot misreport a child that did not. The answer must
// also be an absolute path, so a variable that was never set by the interceptor
// is not trusted.
//
// Hiding the link instead, so that the program falls back to its own search
// path, does not work for Codex: with no executable path at all it refuses to
// start ("Codex executable path is not configured").
//
// 2. /data/user/0.
//
// The primary user's data directory has two names, /data/data and
// /data/user/0, and in an app's mount namespace the second is a symlink to the
// first. The app's filesDir reports the second, so that is the form of every
// workspace path the editor hands the engine. Codex's patch tool opens
// each directory of a path with O_NOFOLLOW so that it never walks through a
// symlink, and the "0" of that form answers ENOTDIR: every edit of an existing
// file, and every new file written by a patch, failed until the model retried
// with the other spelling.
//
// The symlink is followed there and nowhere else: only a component named
// ALIAS_NAME, only when the directory it is opened in is ALIAS_PARENT, and only
// for a call that was refused with ENOTDIR for it. A symlink the user made,
// anywhere, is still refused.
//
// No dependency beyond Bionic's libc, and the raw system calls for the real
// answers, so nothing here can call back into itself.

// Before every include: with fortification on, the C library declares open
// functions as inline wrappers that a definition of the same name conflicts with.
#undef _FORTIFY_SOURCE
#define _FORTIFY_SOURCE 0
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/types.h>

// <unistd.h> is left out on purpose: it declares readlink with fortified
// overloads that a definition of the same name would conflict with.
extern long syscall(long number, ...);

#define PROC_SELF_EXE_VAR "TERMUX_EXEC__PROC_SELF_EXE"

// What the platform names, overridable only so the test can stand a directory of
// its own in for /data/user.
#ifndef ALIAS_PARENT
#define ALIAS_PARENT "/data/user"
#endif
#ifndef ALIAS_NAME
#define ALIAS_NAME "0"
#endif

static int is_self_exe(const char *path) {
    return path != NULL && strcmp(path, "/proc/self/exe") == 0;
}

static int names_loader(const char *answer, size_t length) {
    static const char suffix[] = "/linker64";
    const size_t n = sizeof(suffix) - 1;
    return length >= n && memcmp(answer + length - n, suffix, n) == 0;
}

static ssize_t resolve(int dirfd, const char *path, char *buf, size_t size) {
    if (!is_self_exe(path) || size == 0) {
        return (ssize_t)syscall(__NR_readlinkat, dirfd, path, buf, size);
    }
    // The kernel is asked with room for the whole path, whatever the caller's
    // buffer is: an answer cut short no longer ends in "/linker64".
    char kernel[PATH_MAX];
    ssize_t kernel_length = (ssize_t)syscall(__NR_readlinkat, dirfd, path, kernel, sizeof(kernel));
    if (kernel_length < 0) {
        return kernel_length;
    }
    const char *answer = kernel;
    size_t length = (size_t)kernel_length;
    const char *real = getenv(PROC_SELF_EXE_VAR);
    if (names_loader(kernel, length) && real != NULL && real[0] == '/') {
        answer = real;
        length = strlen(real);
    }
    // readlink truncates to the buffer and does not terminate, so neither does this.
    if (length > size) {
        length = size;
    }
    memcpy(buf, answer, length);
    return (ssize_t)length;
}

// Whether `dirfd` is the directory the alias lives in, asked of the kernel.
static int names_alias_parent(int dirfd) {
    if (dirfd < 0) {
        return 0;
    }
    char link[32] = "/proc/self/fd/";
    size_t at = strlen(link);
    char digits[12];
    size_t count = 0;
    for (unsigned int n = (unsigned int)dirfd; count == 0 || n > 0; n /= 10) {
        digits[count++] = (char)('0' + n % 10);
    }
    while (count > 0) {
        link[at++] = digits[--count];
    }
    link[at] = '\0';
    // One byte more than the name, so a longer target cannot pass for it.
    char target[sizeof(ALIAS_PARENT) + 1];
    ssize_t length = (ssize_t)syscall(__NR_readlinkat, AT_FDCWD, link, target, sizeof(target));
    return length == (ssize_t)(sizeof(ALIAS_PARENT) - 1) && memcmp(target, ALIAS_PARENT, (size_t)length) == 0;
}

static int open_at(int dirfd, const char *path, int flags, mode_t mode) {
    int fd = (int)syscall(__NR_openat, dirfd, path, flags, mode);
    if (fd >= 0 || errno != ENOTDIR) {
        return fd;
    }
    if ((flags & O_NOFOLLOW) == 0 || path == NULL || strcmp(path, ALIAS_NAME) != 0 ||
        !names_alias_parent(dirfd)) {
        errno = ENOTDIR;
        return -1;
    }
    return (int)syscall(__NR_openat, dirfd, path, flags & ~O_NOFOLLOW, mode);
}

// The build hides every symbol by default; these four are the interface.
#define EXPORT __attribute__((visibility("default")))

EXPORT ssize_t readlink(const char *path, char *buf, size_t size) {
    return resolve(AT_FDCWD, path, buf, size);
}

EXPORT ssize_t readlinkat(int dirfd, const char *path, char *buf, size_t size) {
    return resolve(dirfd, path, buf, size);
}

// The mode argument exists only for a call that can create a file.
#define NEEDS_MODE(flags) (((flags) & O_CREAT) != 0 || ((flags) & O_TMPFILE) == O_TMPFILE)

EXPORT int openat(int dirfd, const char *path, int flags, ...) {
    mode_t mode = 0;
    if (NEEDS_MODE(flags)) {
        va_list args;
        va_start(args, flags);
        mode = (mode_t)va_arg(args, int);
        va_end(args);
    }
    return open_at(dirfd, path, flags, mode);
}

EXPORT int openat64(int dirfd, const char *path, int flags, ...) {
    mode_t mode = 0;
    if (NEEDS_MODE(flags)) {
        va_list args;
        va_start(args, flags);
        mode = (mode_t)va_arg(args, int);
        va_end(args);
    }
    return open_at(dirfd, path, flags, mode);
}
