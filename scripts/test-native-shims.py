#!/usr/bin/env python3
"""Compile native compatibility helpers with local fixtures."""

from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


def function(path, start, end):
    source = (ROOT / path).read_text()
    offset = source.index(start)
    return source[offset:source.index(end, offset)]


def compile_and_run(source):
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        file = root / "case.c"
        binary = root / "case"
        file.write_text(source)
        subprocess.run([
            "cc", "-Wall", "-Wextra", "-Werror", "-fsanitize=undefined",
            "-fno-sanitize-recover=all", str(file), "-o", str(binary),
        ], check=True, timeout=30)
        subprocess.run([str(binary)], cwd=root, check=True, timeout=10)


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


class NativeShimTest(unittest.TestCase):
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
    return 0;
}
'''.replace("BODY", body))

    def test_epoll_timeouts(self):
        body = function("scripts/seccomp-shim.c", "static void on_sigsys(", "\n/**")
        compile_and_run(r'''
#include <assert.h>
#include <errno.h>
#include <limits.h>
#include <time.h>
#define __NR_epoll_pwait2 441
#define SYS_epoll_pwait 22
typedef struct { int si_syscall; } siginfo_t;
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
static void check(const struct timespec *ts, long expected) {
    siginfo_t info = {__NR_epoll_pwait2};
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
int main(void) {
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
'''.replace("BODY", body))


if __name__ == "__main__":
    unittest.main()
