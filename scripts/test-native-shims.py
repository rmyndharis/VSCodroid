#!/usr/bin/env python3
"""Compile native compatibility helpers with local fixtures."""

from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


def function(path, start, end):
    source = (ROOT / path).read_text()
    offset = source.index(start)
    return source[offset:source.index(end, offset)]


def compile_and_run(source, flags=(), args=()):
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        file = root / "case.c"
        binary = root / "case"
        file.write_text(source)
        subprocess.run([
            "cc", "-Wall", "-Wextra", "-Werror", "-fsanitize=undefined",
            "-fno-sanitize-recover=all", str(file), "-o", str(binary), *flags,
        ], check=True, timeout=30)
        subprocess.run([str(binary), *args], cwd=root, check=True, timeout=10)


def strerror_fixture():
    source = (ROOT / "scripts/glibc-shim.c").read_text()
    helpers = ""
    for marker in ("static int glibc_errno_known(", "char *__shim_strerror_r("):
        if marker in source:
            helpers += function("scripts/glibc-shim.c", marker, "\n}\n") + "\n}\n"
    resolver = function("scripts/glibc-shim.c", "void *__shim_resolve(", "\n}\n") + "\n}\n"
    xpg = function("scripts/glibc-shim.c", "int __xpg_strerror_r(", "\n}\n") + "\n}\n"
    return r'''
#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#ifndef EHWPOISON
#define EHWPOISON 133
#endif
static int api, provider_known;
static const struct { int code, known; } errors[] = {
    {0, 1}, {1, 1}, {5, 1}, {34, 1}, {35, 1}, {40, 1}, {42, 1}, {57, 1}, {59, 1}, {133, 1},
    {-1, 0}, {41, 0}, {58, 0}, {134, 0}, {INT_MIN, 0}, {INT_MAX, 0}
};
static char *platform_strerror(int code) {
    static char known[] = "dummy error", unknown[64];
    if (provider_known) return known;
    snprintf(unknown, sizeof(unknown), "Unknown error %d", code);
    return unknown;
}
static int posix_strerror(int code, char *buf, size_t len) {
    int saved = errno;
    size_t needed = (size_t)snprintf(buf, len, "%s", platform_strerror(code));
    if (needed >= len) {
        errno = api == 30 ? ERANGE : saved;
        return api == 30 ? -1 : ERANGE;
    }
    errno = saved;
    return 0;
}
static char *gnu_strerror(int code, char *buf, size_t len) {
    int saved = errno;
    (void)posix_strerror(code, buf, len);
    errno = saved;
    return buf;
}
static void untouched(void) {}
static void *shim_libc = (void *)1;
static void *shim_libdl;
static void *shim_self;
static void shim_open_handles(void) {}
static void dlopen(void) {}
static void dlclose(void) {}
static void dlerror(void) {}
static void dladdr(void) {}
static void dl_iterate_phdr(void) {}
static void *lookup(void *handle, const char *name) {
    (void)handle;
    if (!strcmp(name, "strerror_r")) return (void *)&posix_strerror;
    if (!strcmp(name, "untouched")) return (void *)&untouched;
    return NULL;
}
#define RTLD_NEXT ((void *)4)
#define dlsym lookup
#define strerror_r gnu_strerror
#define strerror platform_strerror
HELPERS
RESOLVER
XPG
'''.replace("HELPERS", helpers).replace("RESOLVER", resolver).replace("XPG", xpg)


def sigaction_fixture():
    source = (ROOT / "scripts/seccomp-shim.c").read_text()
    structures = "\n".join(
        function("scripts/seccomp-shim.c", f"struct {name} {{", "\n};") + "\n};"
        for name in ("kernel_sigaction", "musl_sigaction")
    )
    start = source.index("int shim_sigaction(", source.index('__asm__("sigaction")'))
    body = source[start:source.index('\n__attribute__((constructor))', start)]
    return r'''
#include <assert.h>
#include <errno.h>
#include <string.h>
#define SIGSYS 31
#define SYS_rt_sigaction 134
typedef struct { int code; } siginfo_t;
STRUCTURES
static long result;
static int calls, signal_seen, has_action;
static struct kernel_sigaction action_seen;
static void handler(int sig, siginfo_t *info, void *context) {
    (void)sig; (void)info; (void)context;
}
static void restorer(void) {}
int *fixture_errno_location(void) { return &errno; }
#define __errno_location fixture_errno_location
static long sys6(long nr, long sig, long act, long old, long size, long e, long f) {
    assert(nr == SYS_rt_sigaction && size == 8 && e == 0 && f == 0);
    calls++; signal_seen = (int)sig;
    has_action = act != 0;
    if (act) action_seen = *(struct kernel_sigaction *)act;
    if (old && result == 0) {
        struct kernel_sigaction *saved = (void *)old;
        saved->handler = handler; saved->flags = 4; saved->mask = 16;
        saved->restorer = restorer;
    }
    return result;
}
BODY
'''.replace("STRUCTURES", structures).replace("BODY", body)


def environment_code(path):
    """Every top-level declaration in a C file that names the environment variable.

    Chosen by what it names rather than by where it sits, so the case below reads
    whatever the file defines for the environment and whatever reads it. Comments and
    preprocessor lines are dropped first; the rest splits where a declaration or a
    function body ends at file scope.
    """
    literal = r'"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])*\''
    text = re.sub(r"/\*.*?\*/|//[^\n]*|(" + literal + ")", lambda m: m.group(1) or " ",
                  (ROOT / path).read_text(), flags=re.S)
    text = re.sub(r"^[ \t]*#(?:[^\n]*\\\n)*[^\n]*", "", text, flags=re.M)
    chunks, start, depth = [], 0, 0
    for match in re.finditer(literal + r"|[{};]", text):
        token = match.group()
        depth += {"{": 1, "}": -1}.get(token, 0)
        if depth == 0 and token in ("}", ";"):
            chunks.append(text[start:match.end()])
            start = match.end()
    return [chunk for chunk in chunks if re.search(r"\b_{0,2}environ\b", chunk)]


def sigsys_fixture():
    """seccomp-shim.c's SIGSYS handler, with the epoll_pwait it makes recorded instead."""
    body = function("scripts/seccomp-shim.c", "static void on_sigsys(", "\n/**")
    return r'''
#include <assert.h>
#include <errno.h>
#include <limits.h>
#include <time.h>
#define __NR_epoll_pwait2 441
#define SYS_epoll_pwait 22
#define SYS_SECCOMP 1
typedef struct { int si_code; int si_syscall; } siginfo_t;
typedef struct { struct { unsigned long regs[31]; } uc_mcontext; } ucontext_t;
static long timeout_seen;
static int calls;
static long sys6(long nr, long fd, long events, long maxevents, long ms, long mask, long size) {
    assert(nr == SYS_epoll_pwait && fd == 7 && events == 8 && maxevents == 9);
    assert(mask == 10 && size == 8);
    timeout_seen = ms; calls++;
    return 3;
}
BODY
'''.replace("BODY", body)


def statx_fixture():
    """The statx fallback, as Android 10 runs it, against the kernel's own statx.

    Android 10's libc has no statx, so the shim answers from fstatat. Here the shim's
    handles are absent, which is the same path, and glibc's statx is the reference.
    """
    source = (ROOT / "scripts/glibc-shim.c").read_text()
    head = "int statx(int dirfd, const char *path, int flags, unsigned mask, struct statx *out) {"
    offset = source.index(head)
    body = source[offset:source.index("\n}\n", offset)] + "\n}\n"
    return r"""
#define _GNU_SOURCE
#include <assert.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <unistd.h>

static void *shim_libc;
static void shim_open_handles(void) {}
BODY

static int failures;

static void same(const char *what, int dirfd, const char *path, int flags) {
    unsigned mask = STATX_BASIC_STATS | STATX_BTIME;
    struct statx real, shim;
    memset(&real, 0xAA, sizeof real);
    memset(&shim, 0x55, sizeof shim);
    errno = 0;
    int real_result = statx(dirfd, path, flags, mask, &real);
    int real_errno = errno;
    errno = 0;
    int shim_result = shim_statx(dirfd, path, flags, mask, &shim);
    int shim_errno = errno;
    if (real_result != shim_result || (real_result != 0 && real_errno != shim_errno)) {
        printf("FAIL %s: real %d/%d shim %d/%d\n", what, real_result, real_errno, shim_result, shim_errno);
        failures++;
        return;
    }
    if (real_result != 0) return;
#define SAME(field) if (real.field != shim.field) { \
        printf("FAIL %s: %s differs\n", what, #field); failures++; }
    SAME(stx_blksize) SAME(stx_nlink) SAME(stx_uid) SAME(stx_gid) SAME(stx_mode)
    SAME(stx_ino) SAME(stx_size) SAME(stx_blocks)
    SAME(stx_atime.tv_sec) SAME(stx_atime.tv_nsec) SAME(stx_mtime.tv_sec) SAME(stx_mtime.tv_nsec)
    SAME(stx_ctime.tv_sec) SAME(stx_ctime.tv_nsec)
    SAME(stx_rdev_major) SAME(stx_rdev_minor) SAME(stx_dev_major) SAME(stx_dev_minor)
    /* The mask says what is supplied: the basic set, and no birth time. */
    if ((shim.stx_mask & STATX_BASIC_STATS) != STATX_BASIC_STATS || (shim.stx_mask & STATX_BTIME)) {
        printf("FAIL %s: mask %x\n", what, shim.stx_mask);
        failures++;
    }
    if (shim.stx_attributes || shim.stx_attributes_mask || shim.stx_btime.tv_sec) {
        printf("FAIL %s: fields the fallback cannot know are not zero\n", what);
        failures++;
    }
}

int main(void) {
    int fd = open("file", O_CREAT | O_RDWR | O_TRUNC, 0640);
    assert(fd >= 0 && write(fd, "hello", 5) == 5);
    /* Distinct access and modification times, so a swapped field cannot hide behind equal ones. */
    struct timespec times[2] = {{1000, 500000000}, {2000, 250000000}};
    assert(futimens(fd, times) == 0);
    /* An owner that is not the one running the test, where it may be set, so a dropped field shows. */
    if (fchown(fd, 12345, 12346) != 0) { /* not allowed: the file keeps its owner, which is not root */ }
    assert(symlink("file", "link") == 0);
    assert(mkdir("dir", 0700) == 0);
    int directory = open(".", O_RDONLY | O_DIRECTORY);
    assert(directory >= 0);

    same("regular file", AT_FDCWD, "file", 0);
    same("directory", AT_FDCWD, "dir", 0);
    same("link followed", AT_FDCWD, "link", 0);
    same("link not followed", AT_FDCWD, "link", AT_SYMLINK_NOFOLLOW);
    same("device numbers", AT_FDCWD, "/dev/null", 0);
    same("an open descriptor", fd, "", AT_EMPTY_PATH);
    same("relative to a directory descriptor", directory, "file", 0);
    same("sync bits fstatat would refuse", AT_FDCWD, "file", AT_STATX_FORCE_SYNC);
    same("no sync", AT_FDCWD, "file", AT_STATX_DONT_SYNC);
    same("missing path", AT_FDCWD, "nowhere/x", 0);
    same("a file used as a directory", AT_FDCWD, "file/x", 0);
    same("bad descriptor", 9999, "file", 0);
    same("empty path without the flag", AT_FDCWD, "", 0);

    /* Rust's availability probe passes no path and no buffer, and expects EFAULT. */
    errno = 0;
    int probe = shim_statx(0, NULL, 0, STATX_BASIC_STATS | STATX_BTIME, NULL);
    if (probe != -1 || errno != EFAULT) {
        printf("FAIL probe: %d errno %d\n", probe, errno);
        failures++;
    }
    return failures != 0;
}
""".replace("BODY", body.replace("int statx(", "int shim_statx(", 1))


def unwind_fixture():
    """The unwinder entry points, with and without a unwinder behind them."""
    source = (ROOT / "scripts/glibc-shim.c").read_text()
    offset = source.index("#define SHIM_URC_END_OF_STACK ")
    body = source[offset:source.index("\n/*\n * A stack-protector canary", offset)]
    names = ["_Unwind_Backtrace", "_Unwind_FindEnclosingFunction", "_Unwind_GetCFA",
             "_Unwind_GetDataRelBase", "_Unwind_GetIP", "_Unwind_GetIPInfo",
             "_Unwind_GetLanguageSpecificData", "_Unwind_GetRegionStart",
             "_Unwind_GetTextRelBase", "_Unwind_Resume", "_Unwind_SetGR", "_Unwind_SetIP"]
    for name in names:
        assert "SHIM_UNWINDER(" + name + "," in body, name + " is no longer defined by the shim"
    renames = "\n".join("#define %s shim%s" % (n, n) for n in names)
    return r"""
#define _GNU_SOURCE
#include <assert.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#define ANDROID_LOG_FATAL 7
static int __android_log_print(int priority, const char *tag, const char *format, ...) {
    (void)priority; (void)tag; (void)format;
    return 0;
}
static void *shim_libc;
static int with_unwinder;
/* A libc that has an unwinder stands for Android 11 and later; none stands for Android 10. */
static void shim_open_handles(void) {
    if (with_unwinder) shim_libc = dlopen("libgcc_s.so.1", RTLD_NOW);
}
/* The shim's definitions, renamed so the program can also use the real unwinder. */
RENAMES
BODY

static int frames, nonzero;
static uintptr_t visit(uintptr_t context, uintptr_t argument) {
    (*(int *)argument)++;
    /* Forwarded with two words the real function ignores, as a caller built for the shape would. */
    if (shim_Unwind_GetIP(context, 0x1234, 0x5678)) __atomic_add_fetch(&nonzero, 1, __ATOMIC_RELAXED);
    return 0;
}
static void *walk(void *unused) {
    (void)unused;
    int count = 0;
    uintptr_t result = shim_Unwind_Backtrace((uintptr_t)visit, (uintptr_t)&count, 0);
    __atomic_add_fetch(&frames, count, __ATOMIC_RELAXED);
    return (void *)result;
}

int main(int argc, char **argv) {
    (void)argv;
    with_unwinder = argc > 1;
    if (!with_unwinder) {
        /* Android 10: an empty stack, 5 being _URC_END_OF_STACK, and no call into a missing unwinder. */
        int count = 0;
        uintptr_t result = shim_Unwind_Backtrace((uintptr_t)visit, (uintptr_t)&count, 0);
        uintptr_t found = shim_Unwind_FindEnclosingFunction((uintptr_t)main, 0, 0);
        printf("backtrace %lu (want 5), calls %d (want 0), enclosing %lu (want 0)\n",
               (unsigned long)result, count, (unsigned long)found);
        return !(result == 5 && count == 0 && found == 0);
    }
    /* Android 11 and later: every call reaches the real unwinder, from racing first calls too. */
    pthread_t threads[8];
    for (int i = 0; i < 8; i++) pthread_create(&threads[i], 0, walk, 0);
    int wrong = 0;
    for (int i = 0; i < 8; i++) {
        void *result;
        pthread_join(threads[i], &result);
        if ((uintptr_t)result != 5) wrong++;
    }
    printf("%d frames, %d with an address, %d results other than end of stack\n", frames, nonzero, wrong);
    return !(frames >= 16 && nonzero >= 16 && wrong == 0);
}
""".replace("RENAMES", renames).replace("BODY", body)


def clockwait_fixture():
    """cond-clockwait.c whole, with its lookup and both of Bionic's waits recorded.

    The lookup made at load finds nothing, which is Android 10. The same lookup made
    again with a function to find stands for every later release.
    """
    return r"""
#define _GNU_SOURCE
#include <assert.h>
#include <dlfcn.h>
#include <errno.h>
#include <pthread.h>
#include <string.h>
#include <time.h>

static pthread_cond_t cond;
static pthread_mutex_t mutex;
static const struct timespec deadline = {5, 6};
static void *found;
static int lookups, calls, reached, clock_reached, answer;
static void *lookup(void *handle, const char *name) {
    assert(handle == RTLD_DEFAULT && !strcmp(name, "pthread_cond_clockwait"));
    lookups++;
    return found;
}
static int reach(int which, pthread_cond_t *c, pthread_mutex_t *m, const struct timespec *d) {
    assert(c == &cond && m == &mutex && d == &deadline);
    calls++; reached = which;
    return answer;
}
static int pthread_cond_timedwait_monotonic_np(pthread_cond_t *c, pthread_mutex_t *m,
                                               const struct timespec *d) {
    return reach('m', c, m, d);
}
static int wall_clock_wait(pthread_cond_t *c, pthread_mutex_t *m, const struct timespec *d) {
    return reach('w', c, m, d);
}
static int real_wait(pthread_cond_t *c, pthread_mutex_t *m, clockid_t clock,
                     const struct timespec *d) {
    clock_reached = (int)clock;
    return reach('r', c, m, d);
}
#define dlsym lookup
#define pthread_cond_timedwait wall_clock_wait
BODY
static int wait_on(clockid_t clock) {
    return __wrap_pthread_cond_clockwait(&cond, &mutex, clock, &deadline);
}
""".replace("BODY", (ROOT / "scripts/cond-clockwait.c").read_text())


def ctype_fixture():
    """The ctype tables and their three entry points, beside the host's own ctype.

    The shim's entry points are renamed, so glibc's tables stay reachable where the host
    has them and are then the reference for every entry. Any C library answers for the
    ASCII half. The tables are read in a process that has made no call, which is where
    tables built by their first caller were empty.
    """
    body = function("scripts/glibc-shim.c", "#define SHIM_ISupper ", "\n/* Version strings")
    names = ["__ctype_b_loc", "__ctype_tolower_loc", "__ctype_toupper_loc"]
    for name in names:
        assert name + "(void) {" in body, name + " is no longer defined by the shim"
    renames = "\n".join("#define %s shim%s" % (n, n) for n in names)
    originals = "\n".join("#undef " + n for n in names)
    return r"""
#include <assert.h>
#include <ctype.h>
#include <stdio.h>
#include <sys/wait.h>
#include <unistd.h>

RENAMES
BODY
ORIGINALS

static int wrong(const unsigned short *b, const int *lower, const int *upper) {
    int count = 0;
    for (int i = -128; i < 256; i++) {
#ifdef __GLIBC__
        count += b[i] != (*__ctype_b_loc())[i];
        count += lower[i] != (*__ctype_tolower_loc())[i];
        count += upper[i] != (*__ctype_toupper_loc())[i];
#endif
        if (i != EOF && (i < 0 || i > 127)) continue;
#define CLASS(bit, test) count += !(b[i] & bit) != !test(i);
        CLASS(SHIM_ISupper, isupper) CLASS(SHIM_ISlower, islower) CLASS(SHIM_ISalpha, isalpha)
        CLASS(SHIM_ISdigit, isdigit) CLASS(SHIM_ISxdigit, isxdigit) CLASS(SHIM_ISspace, isspace)
        CLASS(SHIM_ISprint, isprint) CLASS(SHIM_ISgraph, isgraph) CLASS(SHIM_ISblank, isblank)
        CLASS(SHIM_IScntrl, iscntrl) CLASS(SHIM_ISpunct, ispunct) CLASS(SHIM_ISalnum, isalnum)
        count += lower[i] != tolower(i);
        count += upper[i] != toupper(i);
    }
    return count;
}

/* The tables as a process holds them before its first call. A child makes the calls and
 * says where the tables are; this process has made none, and reads its own copy there. */
static int wrong_before_the_first_call(void) {
    int ends[2], status;
    const void *tables[3];
    assert(pipe(ends) == 0);
    pid_t child = fork();
    assert(child >= 0);
    if (child == 0) {
        tables[0] = *shim__ctype_b_loc();
        tables[1] = *shim__ctype_tolower_loc();
        tables[2] = *shim__ctype_toupper_loc();
        _exit(write(ends[1], tables, sizeof(tables)) != (ssize_t)sizeof(tables));
    }
    close(ends[1]);
    assert(read(ends[0], tables, sizeof(tables)) == (ssize_t)sizeof(tables));
    assert(waitpid(child, &status, 0) == child && status == 0);
    return wrong(tables[0], tables[1], tables[2]);
}

int main(void) {
    int before = wrong_before_the_first_call();
    printf("%d wrong entries before the first call\n", before);
    return before != 0;
}
""".replace("RENAMES", renames).replace("BODY", body).replace("ORIGINALS", originals)


class NativeShimTest(unittest.TestCase):
    def test_reserved_and_out_of_range_signals_leave_the_action_unchanged(self):
        compile_and_run(sigaction_fixture() + r'''
#include <limits.h>
int main(void) {
    const int invalid[] = {32, 33, 34, 0, 65, INT_MIN, INT_MAX};
    for (unsigned i = 0; i < sizeof(invalid) / sizeof(invalid[0]); i++) {
        struct musl_sigaction old, before;
        memset(&old, 0x55, sizeof(old)); before = old;
        errno = E2BIG;
        assert(shim_sigaction(invalid[i], NULL, &old) == -1);
        assert(errno == EINVAL && memcmp(&old, &before, sizeof(old)) == 0);
        assert(calls == 0);
    }
    assert(shim_sigaction(35, NULL, NULL) == 0 && calls == 1);
    assert(shim_sigaction(64, NULL, NULL) == 0 && calls == 2);
    return 0;
}
''')

    def test_protected_signal_returns_the_installed_action(self):
        compile_and_run(sigaction_fixture() + r'''
int main(void) {
    struct musl_sigaction action = {0}, old;
    action.handler = (void *)1;
    memset(&old, 0x55, sizeof(old));
    errno = E2BIG;
    assert(shim_sigaction(SIGSYS, &action, &old) == 0);
    assert(calls == 1 && signal_seen == SIGSYS && !has_action);
    assert(old.handler == (void *)handler && old.flags == 4 && old.mask[0] == 16);
    assert(old.restorer == restorer && errno == E2BIG);
    for (int i = 1; i < 16; i++) assert(old.mask[i] == 0);
    return 0;
}
''')

    def test_signal_errors_set_errno_without_changing_the_old_action(self):
        compile_and_run(sigaction_fixture() + r'''
int main(void) {
    struct musl_sigaction action = {0}, old, before;
    action.handler = (void *)handler;
    memset(&old, 0x55, sizeof(old)); before = old;
    result = -EINVAL; errno = E2BIG;
    assert(shim_sigaction(9, &action, &old) == -1);
    assert(errno == EINVAL && memcmp(&old, &before, sizeof(old)) == 0);
    assert(calls == 1 && signal_seen == 9);
    return 0;
}
''')

    def test_ordinary_signal_preserves_the_kernel_action(self):
        compile_and_run(sigaction_fixture() + r'''
int main(void) {
    struct musl_sigaction action = {0}, old;
    action.handler = (void *)handler; action.mask[0] = 8;
    action.flags = (int)0x80000004U; action.restorer = restorer;
    errno = E2BIG;
    assert(shim_sigaction(10, &action, &old) == 0);
    assert(calls == 1 && signal_seen == 10 && errno == E2BIG);
    assert(action_seen.handler == handler && action_seen.flags == 0x80000004UL);
    assert(action_seen.mask == 8 && action_seen.restorer == restorer);
    assert(old.handler == (void *)handler && old.flags == 4 && old.mask[0] == 16);
    return 0;
}
''')

    def test_gnu_strerror_resolver(self):
        compile_and_run(strerror_fixture() + r'''
int main(void) {
    void *resolved = __shim_resolve("strerror_r");
    assert(resolved && resolved != (void *)&posix_strerror);
    char *(*gnu)(int, char *, size_t) = resolved;
    for (int endpoint = 0; endpoint < 2; endpoint++) {
        api = endpoint ? 36 : 30;
        for (size_t i = 0; i < sizeof(errors) / sizeof(errors[0]); i++) {
            provider_known = errors[i].known;
            const char *message = platform_strerror(errors[i].code);
            size_t length = strlen(message), sizes[] = {0, 1, length, length + 1};
            for (int n = 0; n < 4; n++) {
                char buf[64]; memset(buf, 'X', sizeof(buf));
                errno = E2BIG;
                char *result = gnu(errors[i].code, buf, sizes[n]);
                assert(errno == E2BIG && buf[sizes[n]] == 'X');
                if (errors[i].known) assert(result != buf && !strcmp(result, message) && buf[0] == 'X');
                else {
                    assert(result == buf);
                    if (sizes[n]) {
                        assert(!strncmp(buf, message, sizes[n] - 1));
                        assert(buf[sizes[n] - 1] == '\0');
                    }
                }
            }
        }
    }
    assert(__shim_resolve("untouched") == (void *)&untouched);
    return 0;
}
''')

    def test_xpg_strerror_buffers(self):
        compile_and_run(strerror_fixture() + r'''
int main(void) {
    for (int endpoint = 0; endpoint < 2; endpoint++) {
        api = endpoint ? 36 : 30;
        for (size_t i = 0; i < sizeof(errors) / sizeof(errors[0]); i++) {
            provider_known = errors[i].known;
            const char *message = platform_strerror(errors[i].code);
            size_t length = strlen(message), sizes[] = {0, 1, length, length + 1};
            for (int n = 0; n < 4; n++) {
                char buf[64]; memset(buf, 'X', sizeof(buf));
                errno = E2BIG;
                int result = __xpg_strerror_r(errors[i].code, buf, sizes[n]);
                assert(result == (!errors[i].known ? EINVAL : (n == 3 ? 0 : ERANGE)));
                assert(errno == E2BIG && buf[sizes[n]] == 'X');
                if (sizes[n]) {
                    assert(!strncmp(buf, message, sizes[n] - 1));
                    assert(buf[sizes[n] - 1] == '\0');
                }
            }
        }
    }
    shim_libc = NULL;
    errno = E2BIG;
    char buf[64];
    assert(__xpg_strerror_r(5, buf, sizeof(buf)) == ENOSYS);
    assert(errno == E2BIG);
    return 0;
}
''')

    def test_copy_ranges(self):
        body = function("scripts/glibc-shim.c", "static ssize_t copy_file_range_fallback(",
                        "\nssize_t copy_file_range(")
        compile_and_run(r'''
#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#ifdef __APPLE__
typedef off_t off64_t;
_Static_assert(sizeof(off64_t) == 8, "The fixture requires 64-bit offsets");
#endif
BODY
static int seed(const char *name) {
    int fd = open(name, O_RDWR | O_CREAT | O_TRUNC, 0600);
    assert(fd >= 0 && write(fd, "ABCDEFGH", 8) == 8);
    assert(lseek(fd, 0, SEEK_SET) == 0);
    return fd;
}
static void contents(int fd, const char *expected) {
    char bytes[9] = {0};
    assert(pread(fd, bytes, 8, 0) == 8);
    assert(strcmp(bytes, expected) == 0);
}
int main(void) {
    for (int implicit = 0; implicit < 2; implicit++) {
        for (int reverse = 0; reverse < 2; reverse++) {
            int in = seed("source");
            assert(link("source", "alias") == 0);
            int out = open("alias", O_RDWR);
            off64_t input = reverse ? 2 : 0, output = reverse ? 0 : 2;
            assert(lseek(in, input, SEEK_SET) == input);
            assert(lseek(out, output, SEEK_SET) == output);
            errno = 0;
            assert(copy_file_range_fallback(in, implicit ? NULL : &input,
                                           out, implicit ? NULL : &output, 4, 0) == -1);
            assert(errno == EINVAL);
            contents(in, "ABCDEFGH");
            assert(input == (reverse ? 2 : 0) && output == (reverse ? 0 : 2));
            assert(lseek(in, 0, SEEK_CUR) == input && lseek(out, 0, SEEK_CUR) == output);
            close(in); close(out); unlink("alias"); unlink("source");
        }
    }
    int in = seed("source"), out = seed("output");
    off64_t input = 0, output = 4;
    assert(copy_file_range_fallback(in, &input, out, &output, 4, 0) == 4);
    contents(in, "ABCDEFGH"); contents(out, "ABCDABCD");
    assert(input == 4 && output == 8);
    assert(lseek(in, 0, SEEK_CUR) == 0 && lseek(out, 0, SEEK_CUR) == 0);
    input = 0; output = 4;
    assert(copy_file_range_fallback(in, &input, in, &output, 4, 0) == 4);
    contents(in, "ABCDABCD");
    close(in); close(out);
    in = seed("source");
    input = 6; output = 0;
    assert(copy_file_range_fallback(in, &input, in, &output, 8, 0) == 2);
    contents(in, "GHCDEFGH");
    assert(input == 8 && output == 2);
    input = 8; output = 8;
    assert(copy_file_range_fallback(in, &input, in, &output, 4, 0) == 0);
    close(in);
    /* Writing at the end of the file it reads from: only what the file held counts. */
    in = seed("source");
    input = 0; output = 8;
    assert(copy_file_range_fallback(in, &input, in, &output, 100, 0) == 8);
    assert(input == 8 && output == 16);
    struct stat grown;
    assert(fstat(in, &grown) == 0 && grown.st_size == 16);
    close(in);
    return 0;
}
'''.replace("BODY", body))

    def test_a_short_write_leaves_the_input_after_the_last_byte_copied(self):
        body = function("scripts/glibc-shim.c", "static ssize_t copy_file_range_fallback(",
                        "\nssize_t copy_file_range(")
        compile_and_run(r'''
#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <unistd.h>
#ifdef __APPLE__
typedef off_t off64_t;
#endif
BODY
static char bytes[40000];
static void copied(int fd, size_t count) {
    static char back[sizeof(bytes)];
    struct stat st;
    assert(fstat(fd, &st) == 0 && st.st_size == (off_t)count);
    assert(pread(fd, back, count, 0) == (ssize_t)count && !memcmp(back, bytes, count));
}
int main(void) {
    for (size_t i = 0; i < sizeof(bytes); i++) bytes[i] = (char)(i * 7 + 1);
    int in = open("source", O_RDWR | O_CREAT | O_TRUNC, 0600);
    assert(in >= 0 && write(in, bytes, sizeof(bytes)) == (ssize_t)sizeof(bytes));
    /* A file size ceiling stops write() short inside the second 16 KB chunk. */
    signal(SIGXFSZ, SIG_IGN);
    struct rlimit ceiling = {20000, 20000};
    assert(setrlimit(RLIMIT_FSIZE, &ceiling) == 0);

    /* The descriptors' own positions: the input stops after the last byte copied,
     * which is where a caller looping on the short count resumes. */
    int out = open("output", O_RDWR | O_CREAT | O_TRUNC, 0600);
    assert(lseek(in, 0, SEEK_SET) == 0);
    assert(copy_file_range_fallback(in, NULL, out, NULL, sizeof(bytes), 0) == 20000);
    assert(lseek(in, 0, SEEK_CUR) == 20000 && lseek(out, 0, SEEK_CUR) == 20000);
    copied(out, 20000);

    /* Explicit offsets advance by what was copied and leave the descriptor alone. */
    int again = open("again", O_RDWR | O_CREAT | O_TRUNC, 0600);
    off64_t input = 0, output = 0;
    assert(lseek(in, 5, SEEK_SET) == 5);
    assert(copy_file_range_fallback(in, &input, again, &output, sizeof(bytes), 0) == 20000);
    assert(input == 20000 && output == 20000 && lseek(in, 0, SEEK_CUR) == 5);
    copied(again, 20000);
    return 0;
}
'''.replace("BODY", body))

    def test_epoll_timeouts(self):
        compile_and_run(sigsys_fixture() + r'''
static void check(const struct timespec *ts, long expected) {
    siginfo_t info = {SYS_SECCOMP, __NR_epoll_pwait2};
    ucontext_t context = {0};
    context.uc_mcontext.regs[0] = 7;
    context.uc_mcontext.regs[1] = 8;
    context.uc_mcontext.regs[2] = 9;
    context.uc_mcontext.regs[3] = (unsigned long)ts;
    context.uc_mcontext.regs[4] = 10;
    context.uc_mcontext.regs[5] = 8;
    int before = calls;
    on_sigsys(0, &info, &context);
    if (expected == -EINVAL) {
        assert(calls == before && (long)context.uc_mcontext.regs[0] == -EINVAL);
    } else {
        assert(calls == before + 1 && timeout_seen == expected);
        assert(context.uc_mcontext.regs[0] == 3);
    }
}
/* A SIGSYS sent with kill, tgkill or sigqueue: nothing was refused, so x0 is the caller's own. */
static void sent(int code, int syscall_field) {
    const unsigned long regs[6] = {7, 8, 9, 0, 10, 8};
    siginfo_t info = {code, syscall_field};
    ucontext_t context = {0};
    for (int i = 0; i < 6; i++) context.uc_mcontext.regs[i] = regs[i];
    int before = calls;
    on_sigsys(0, &info, &context);
    assert(calls == before);
    for (int i = 0; i < 6; i++) assert(context.uc_mcontext.regs[i] == regs[i]);
}
int main(void) {
    /* SI_USER (kill), SI_TKILL (tgkill), SI_QUEUE (sigqueue), and codes that are not SYS_SECCOMP. */
    const int codes[] = {0, -6, -1, 2, 7};
    for (unsigned i = 0; i < sizeof(codes) / sizeof(codes[0]); i++) {
        sent(codes[i], __NR_epoll_pwait2);
        sent(codes[i], 0);
        sent(codes[i], 129);
    }
    check(NULL, -1);
    check(&(struct timespec){0, 0}, 0);
    check(&(struct timespec){0, 1}, 1);
    check(&(struct timespec){0, 999999999}, 1000);
    check(&(struct timespec){2, 1}, 2001);
    check(&(struct timespec){INT_MAX / 1000, 646000000}, INT_MAX - 1);
    check(&(struct timespec){INT_MAX / 1000, 648000000}, INT_MAX);
    check(&(struct timespec){LONG_MAX, 0}, INT_MAX);
    check(&(struct timespec){-1, 0}, -EINVAL);
    check(&(struct timespec){0, -1}, -EINVAL);
    check(&(struct timespec){0, 1000000000}, -EINVAL);
    return 0;
}
''')

    def test_another_refused_call_is_answered_enosys_and_epoll_pwait2_still_is(self):
        compile_and_run(sigsys_fixture() + r'''
static const unsigned long arguments[6] = {7, 8, 9, 0, 10, 8};
static unsigned long refuse(int syscall) {
    siginfo_t info = {SYS_SECCOMP, syscall};
    ucontext_t context = {0};
    for (int i = 0; i < 6; i++) context.uc_mcontext.regs[i] = arguments[i];
    on_sigsys(0, &info, &context);
    for (int i = 1; i < 6; i++) assert(context.uc_mcontext.regs[i] == arguments[i]);
    return context.uc_mcontext.regs[0];
}
int main(void) {
    /* rseq, clone3, set_robust_list, and a number no kernel assigns. None of them ran, so
     * until the handler answers, x0 still holds the caller's own first argument. */
    const int others[] = {293, 435, 99, 4095};
    for (unsigned i = 0; i < sizeof(others) / sizeof(others[0]); i++) {
        calls = 0;
        assert((long)refuse(others[i]) == -ENOSYS && calls == 0);
        /* Answering it does not use the handler up: the next epoll_pwait2 is emulated. */
        assert(refuse(__NR_epoll_pwait2) == 3 && calls == 1 && timeout_seen == -1);
    }
    return 0;
}
''')

    def test_ctype_tables_are_whole_before_the_first_call(self):
        compile_and_run(ctype_fixture())

    def test_clockwait_takes_each_clock_to_its_own_wait_and_forwards_where_the_call_exists(self):
        compile_and_run(clockwait_fixture() + r'''
int main(void) {
    const clockid_t others[] = {CLOCK_MONOTONIC_RAW, CLOCK_PROCESS_CPUTIME_ID, CLOCK_THREAD_CPUTIME_ID};
    const clockid_t every[] = {CLOCK_MONOTONIC, CLOCK_REALTIME, CLOCK_MONOTONIC_RAW,
                               CLOCK_PROCESS_CPUTIME_ID};
    const int answers[] = {0, ETIMEDOUT};
    /* Android 10: looked up once, at load, and not there. */
    assert(lookups == 1);
    for (int n = 0; n < 2; n++) {
        answer = answers[n]; calls = 0;
        /* A wait answers with an error number and leaves errno alone. */
        errno = E2BIG;
        assert(wait_on(CLOCK_MONOTONIC) == answer && calls == 1 && reached == 'm');
        assert(wait_on(CLOCK_REALTIME) == answer && calls == 2 && reached == 'w');
        for (unsigned i = 0; i < sizeof(others) / sizeof(others[0]); i++) {
            assert(wait_on(others[i]) == EINVAL);
        }
        assert(calls == 2 && errno == E2BIG);
    }
    /* A release that has the function: every call goes to it, with the clock it was given. */
    found = (void *)real_wait;
    find_real_clockwait();
    for (int n = 0; n < 2; n++) {
        answer = answers[n];
        for (unsigned i = 0; i < sizeof(every) / sizeof(every[0]); i++) {
            calls = 0; clock_reached = -1;
            assert(wait_on(every[i]) == answer);
            assert(calls == 1 && reached == 'r' && clock_reached == (int)every[i]);
        }
    }
    /* No wait looks anything up: the caller holds its mutex by then. */
    assert(lookups == 2);
    return 0;
}
''')


    @unittest.skipUnless(sys.platform.startswith("linux"),
                         "needs ELF symbol preemption, which macOS's two-level namespace does not do")
    def test_loading_the_shim_leaves_the_process_environment_as_it_is(self):
        # The shim's environment code is built into a library of its own and loaded the
        # way Node loads an addon, after the process has changed its environment.
        library = r'''
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
''' + "\n".join(environment_code("scripts/glibc-shim.c"))
        with tempfile.TemporaryDirectory() as directory:
            source, shared = Path(directory) / "shim.c", Path(directory) / "libshim.so"
            source.write_text(library)
            subprocess.run(["cc", "-shared", "-fPIC", "-Wall", str(source), "-o", str(shared)],
                           check=True, timeout=30)
            compile_and_run(r'''
#define _GNU_SOURCE
#include <assert.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int lists(char **environment, const char *entry) {
    for (; environment && *environment; environment++) {
        if (!strcmp(*environment, entry)) return 1;
    }
    return 0;
}

int main(int argc, char **argv) {
    if (argc == 2) {
        /* Started again, with a variable in the environment it is executed with. */
        assert(setenv("REMOVED_AFTER_EXEC", "1", 1) == 0);
        execl(argv[0], argv[0], argv[1], "again", (char *)0);
        return 2;
    }
    /* What the editor server does before an extension host loads a glibc addon:
     * dns-proxy.js adds the proxy variables and deletes the one that asked for them. */
    assert(unsetenv("REMOVED_AFTER_EXEC") == 0 && setenv("SET_AFTER_EXEC", "1", 1) == 0);
    char **before = environ;
    void *shim = dlopen(argv[1], RTLD_LAZY | RTLD_LOCAL);
    if (!shim) { printf("%s\n", dlerror()); return 3; }
    printf("environ %s, SET_AFTER_EXEC %s, REMOVED_AFTER_EXEC %s\n",
           environ == before ? "kept" : "replaced", getenv("SET_AFTER_EXEC") ? "kept" : "gone",
           getenv("REMOVED_AFTER_EXEC") ? "back" : "gone");
    fflush(stdout);
    assert(environ == before && getenv("SET_AFTER_EXEC") && !getenv("REMOVED_AFTER_EXEC"));
    /* What a stub exports as glibc's environ shows both changes too. */
    char **(*handed_to_stubs)(void) = (char **(*)(void))dlsym(shim, "__shim_environ");
    assert(handed_to_stubs);
    char **handed = handed_to_stubs();
    assert(lists(handed, "SET_AFTER_EXEC=1") && !lists(handed, "REMOVED_AFTER_EXEC=1"));
    /* It is the environment as it was at load, owned by the stub. A pointer into the
     * process's own array would change under it here, and would be freed as that
     * array grows. */
    assert(setenv("SET_AFTER_EXEC", "2", 1) == 0 && !strcmp(getenv("SET_AFTER_EXEC"), "2"));
    assert(lists(handed, "SET_AFTER_EXEC=1") && !lists(handed, "SET_AFTER_EXEC=2"));
    for (int i = 0; i < 200; i++) {
        char name[32];
        snprintf(name, sizeof(name), "GROWN_%d", i);
        assert(setenv(name, "1", 1) == 0);
    }
    assert(getenv("GROWN_199") && lists(handed, "SET_AFTER_EXEC=1") && !lists(handed, "GROWN_0=1"));
    return 0;
}
''', flags=["-ldl"], args=[str(shared)])

    @unittest.skipUnless(sys.platform.startswith("linux"), "needs the kernel's own statx to compare with")
    def test_statx_fallback_matches_the_kernel(self):
        # The probe passes no path on purpose and the kernel answers EFAULT, which the
        # sanitizer would call a null argument to a function glibc declares never to take one.
        compile_and_run(statx_fixture(), flags=["-ldl", "-fno-sanitize=nonnull-attribute"])

    @unittest.skipUnless(sys.platform.startswith("linux"), "needs libgcc_s as the unwinder behind the shim")
    def test_unwinder_shims_answer_without_an_unwinder_and_forward_with_one(self):
        source = unwind_fixture()
        compile_and_run(source, flags=["-ldl", "-pthread"])
        compile_and_run(source, flags=["-ldl", "-pthread"], args=["forward"])


if __name__ == "__main__":
    unittest.main()
