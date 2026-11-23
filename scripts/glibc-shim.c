/*
 * Bionic-side implementations of the glibc symbols prebuilt Linux addons expect.
 *
 * Some Node addons ship only a linux-arm64 build compiled against glibc. The
 * machine code is fine on Android -- same ISA, same calling convention -- but
 * the dynamic linker refuses them, because their DT_NEEDED names (libc.so.6,
 * libdl.so.2, ...) do not exist here and a handful of the symbols they import
 * are glibc's rather than POSIX's.
 *
 * Bionic already provides the overwhelming majority: memcpy, pthread_*, the
 * stdio family, everything ordinary. What is missing splits into three kinds,
 * and all three are small:
 *
 *   - renamed internals: __errno_location, __assert_fail, and the environment
 *     under glibc's three names, which the stubs export (see __shim_environ)
 *   - the stat family, which glibc exports as versioned __xstat wrappers
 *     carrying a struct version glibc uses and Bionic does not
 *   - a few glibc-only conveniences: gnu_get_libc_version, __res_init
 *
 * Built into libglibc-shim.so, which the stub libc.so.6 and friends depend on;
 * see build-glibc-shim.sh. Nothing here is clever, and that is the point -- each
 * function is the Bionic call the glibc name was always standing in for.
 */

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <spawn.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#if !defined(__ANDROID__) && !defined(__APPLE__) && !defined(__FreeBSD__) && !defined(__OpenBSD__) && !defined(__NetBSD__)
#include <sys/random.h>
#endif
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/types.h>
#include <unistd.h>

/* glibc keeps errno behind a function; Bionic exposes __errno(). */
int *__errno_location(void) { return __errno(); }

/*
 * The stat family. glibc's public stat() is a thin inline over __xstat(ver, ...),
 * where ver names the struct layout. Bionic has one layout and no versioning, so
 * the version argument is read and discarded -- addons built against modern
 * glibc always pass the current one.
 */
int __xstat(int ver, const char *path, struct stat *buf) { (void)ver; return stat(path, buf); }
int __xstat64(int ver, const char *path, struct stat *buf) { (void)ver; return stat(path, buf); }
int __lxstat(int ver, const char *path, struct stat *buf) { (void)ver; return lstat(path, buf); }
int __lxstat64(int ver, const char *path, struct stat *buf) { (void)ver; return lstat(path, buf); }
int __fxstat(int ver, int fd, struct stat *buf) { (void)ver; return fstat(fd, buf); }
int __fxstat64(int ver, int fd, struct stat *buf) { (void)ver; return fstat(fd, buf); }
int __fxstatat(int ver, int fd, const char *path, struct stat *buf, int flags) {
    (void)ver; return fstatat(fd, path, buf, flags);
}
int __fxstatat64(int ver, int fd, const char *path, struct stat *buf, int flags) {
    (void)ver; return fstatat(fd, path, buf, flags);
}

/* 64-bit by default on this ABI, so the suffixed names are the same calls. */
int fcntl64(int fd, int cmd, ...) {
    va_list ap;
    va_start(ap, cmd);
    void *arg = va_arg(ap, void *);
    va_end(ap);
    return fcntl(fd, cmd, arg);
}

/*
 * glibc's assert calls this; Bionic's is __assert2 with the same information in
 * a different argument order.
 */
void __assert2(const char *, int, const char *, const char *) __attribute__((noreturn));

void __assert_fail(const char *assertion, const char *file, unsigned line,
                   const char *function) {
    __assert2(file, (int)line, function, assertion);
}

/*
 * ctype tables. glibc returns a pointer to a pointer into a table offset by 128
 * so that EOF (-1) indexes validly. The table is built once with the C locale,
 * which is what these addons run under anyway.
 *
 * Each is built at load, not by whichever call comes first. A table published
 * by a plain flag can be read by a second thread that sees the flag ahead of
 * the entries, since arm64 does not order two plain stores. The release and
 * acquire that publish each looked-up pointer further down are not enough for
 * a table: two first callers would both fill it while a third reads it. A
 * constructor is one writer, and it has returned before the loader lets
 * anything that needs this library run.
 *
 * The bit values are glibc's _ISbit(): bits 0-7 are shifted up a byte, bits
 * 8-11 down a byte, so the "obvious" hex guesses are wrong in both directions.
 * The first version of this table guessed, and got 5 of the 12 classes wrong -
 * isalnum() false for everything, isprint() false, ispunct('A') true - which an
 * addon consumes through the __ctype_b_loc() macro expansion with no error to
 * see anywhere. Values below are copied from glibc's ctype.h, not derived.
 */
#define SHIM_ISupper  0x0100
#define SHIM_ISlower  0x0200
#define SHIM_ISalpha  0x0400
#define SHIM_ISdigit  0x0800
#define SHIM_ISxdigit 0x1000
#define SHIM_ISspace  0x2000
#define SHIM_ISprint  0x4000
#define SHIM_ISgraph  0x8000
#define SHIM_ISblank  0x0001
#define SHIM_IScntrl  0x0002
#define SHIM_ISpunct  0x0004
#define SHIM_ISalnum  0x0008

static unsigned short ctype_b_table[384];

__attribute__((constructor(101))) static void build_ctype_b_table(void) {
    for (int c = 0; c < 256; c++) {
        unsigned short f = 0;
        if (c >= 'A' && c <= 'Z') f |= SHIM_ISupper | SHIM_ISalpha;
        if (c >= 'a' && c <= 'z') f |= SHIM_ISlower | SHIM_ISalpha;
        if (c >= '0' && c <= '9') f |= SHIM_ISdigit;
        if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))
            f |= SHIM_ISxdigit;
        if (f & (SHIM_ISalpha | SHIM_ISdigit)) f |= SHIM_ISalnum;
        if (c == ' ' || (c >= '\t' && c <= '\r')) f |= SHIM_ISspace;
        if (c == ' ' || c == '\t') f |= SHIM_ISblank;
        if (c < 32 || c == 127) f |= SHIM_IScntrl;
        if (c >= 33 && c <= 126) {
            f |= SHIM_ISgraph;
            if (!(f & SHIM_ISalnum)) f |= SHIM_ISpunct;
        }
        if (c >= 32 && c <= 126) f |= SHIM_ISprint;
        ctype_b_table[c + 128] = f;
    }
}
const unsigned short **__ctype_b_loc(void) {
    static const unsigned short *p = ctype_b_table + 128;
    return &p;
}

/*
 * The two case-conversion tables, in the same shape and with the same 128-entry
 * offset. Entries are int rather than unsigned short because each holds a
 * character value that must still be able to be EOF.
 *
 * The negative half is neither padding nor a mirror of the positive half, which
 * is the part worth getting from a measurement instead of from reasoning. Dumped
 * from glibc 2.36 on aarch64 in the C locale: indices -128..-2 hold i + 256 --
 * the index reinterpreted as an unsigned char -- and -1 holds -1, so
 * tolower(EOF) is EOF rather than 255. Every one of the 384 entries in both
 * tables was compared against that dump and the rule below reproduces all of
 * them. None of 128..255 is a letter, so the case tests never fire there.
 */
static int ctype_lower_table[384], ctype_upper_table[384];

__attribute__((constructor(101))) static void build_ctype_case_tables(void) {
    for (int i = -128; i < 256; i++) {
        int v = (i < -1) ? i + 256 : i;
        ctype_lower_table[i + 128] = (v >= 'A' && v <= 'Z') ? v + 32 : v;
        ctype_upper_table[i + 128] = (v >= 'a' && v <= 'z') ? v - 32 : v;
    }
}

const int **__ctype_tolower_loc(void) {
    static const int *p = ctype_lower_table + 128;
    return &p;
}

const int **__ctype_toupper_loc(void) {
    static const int *p = ctype_upper_table + 128;
    return &p;
}

/* Version strings addons occasionally log or gate on. Nothing branches on the
 * exact value in practice; a modern one keeps any comparison happy. */
const char *gnu_get_libc_version(void) { return "2.31"; }
const char *gnu_get_libc_release(void) { return "stable"; }

/* Resolver reinitialisation. Bionic re-reads its configuration per query, so
 * there is nothing to do and success is the honest answer. */
int __res_init(void) { return 0; }

/* Linux/AArch64 leaves slots 41 and 58 unused in its errno number range. */
static int glibc_errno_known(int code) {
    return code >= 0 && code <= EHWPOISON && code != 41 && code != 58;
}

/* GNU keeps a full message for known errors even when the supplied buffer is short. */
char *__shim_strerror_r(int code, char *buf, size_t len) {
    int saved_errno = errno;
    char *result = glibc_errno_known(code) ? strerror(code) : strerror_r(code, buf, len);
    errno = saved_errno;
    return result;
}

/* glibc's internal atfork registration. Bionic's public one covers the same
 * three handlers; the DSO handle glibc passes is not needed here. */
int __register_atfork(void (*prepare)(void), void (*parent)(void),
                      void (*child)(void), void *dso) {
    (void)dso;
    return pthread_atfork(prepare, parent, child);
}

/*
 * __libc_current_sigrtmin and __libc_current_sigrtmax are deliberately absent.
 * Bionic already defines both, and SIGRTMIN/SIGRTMAX are macros that call them
 * -- writing the obvious wrapper here produces a function that calls itself.
 */

/* jemalloc's sized deallocation. Bionic's allocator does not expose it, and the
 * size is only a hint -- free() is always correct, just less informed. */
void sdallocx(void *ptr, size_t size, int flags) { (void)size; (void)flags; free(ptr); }

/*
 * glibc's heap trim. The agent host's Copilot runtime.node imports it from
 * VS Code 1.138 on, strongly and at GLIBC_2.17, and Bionic has no malloc_trim
 * at any API level, so without this its forwarder aborts on the first call.
 * The Bionic call it stands for is mallopt(M_PURGE), which hands unused pages
 * back to the kernel. The pad argument has no counterpart there and is ignored.
 *
 * The same object imports mallopt too, and that one is forwarded unchanged on
 * purpose: glibc numbers its options 1..4 and -1..-8, Bionic from -100 down,
 * so a glibc tuning request names no Bionic option and is refused with 0,
 * which a glibc caller already reads as failure.
 */
#include <malloc.h>

int malloc_trim(size_t pad) { (void)pad; return mallopt(M_PURGE, 0); }

/*
 * Symbol resolution for the generated stubs.
 *
 * It lives here, and that is the whole point. Those stubs export glibc names --
 * dlopen@GLIBC_2.17 among them -- so a call to dlopen from inside one of them
 * binds to its own trampoline, whose pointer is not filled yet. It aborts on the
 * first thing it tries to resolve. This file exports none of those names, so the
 * same call reaches Bionic.
 *
 * libdl is consulted separately because Bionic's dl family is provided by the
 * dynamic linker rather than by libc, and taking its address here is the only
 * way to get the real one.
 */
#include <dlfcn.h>
#include <link.h>

static _Atomic(void *) shim_libc;
static _Atomic(void *) shim_libdl;
static _Atomic(void *) shim_self;

__attribute__((constructor(101))) static void shim_open_handles(void) {
    atomic_store_explicit(&shim_libc, dlopen("libc.so", RTLD_LAZY | RTLD_NOLOAD), memory_order_release);
    atomic_store_explicit(&shim_libdl, dlopen("libdl.so", RTLD_LAZY | RTLD_NOLOAD), memory_order_release);
    atomic_store_explicit(&shim_self, dlopen("libglibc-shim.so", RTLD_LAZY | RTLD_NOLOAD), memory_order_release);
}

void *__shim_resolve(const char *name) {
    void *libc_handle = atomic_load_explicit(&shim_libc, memory_order_acquire);
    if (!libc_handle) {
        shim_open_handles();
        libc_handle = atomic_load_explicit(&shim_libc, memory_order_acquire);
    }

    /* The linker owns these; dlsym does not find them by name. */
    if (!__builtin_strcmp(name, "dlopen")) return (void *)&dlopen;
    if (!__builtin_strcmp(name, "dlsym")) return (void *)&dlsym;
    if (!__builtin_strcmp(name, "dlclose")) return (void *)&dlclose;
    if (!__builtin_strcmp(name, "dlerror")) return (void *)&dlerror;
    if (!__builtin_strcmp(name, "dladdr")) return (void *)&dladdr;
    if (!__builtin_strcmp(name, "dl_iterate_phdr")) return (void *)&dl_iterate_phdr;

    if (!__builtin_strcmp(name, "strerror_r")) return (void *)&__shim_strerror_r;

    void *p = libc_handle ? dlsym(libc_handle, name) : 0;
    void *libdl_handle = atomic_load_explicit(&shim_libdl, memory_order_acquire);
    if (!p && libdl_handle) p = dlsym(libdl_handle, name);
    /* This library's own definitions, and they have to be asked for by handle.
     * RTLD_NEXT begins after the caller, and the caller is this file, so the
     * twelve symbols that exist only here (__errno_location, __ctype_b_loc,
     * the __xstat family, bcmp, fcntl64 and the rest) resolved to nothing and
     * left their trampolines null. Measured, not deduced: instrumenting this
     * function showed every one of them taking the NONE branch while the addon
     * ran correctly anyway, because the addon binds to this library directly and
     * never reaches the trampoline. That made the null a landmine rather than a
     * failure: it fires the moment anything does reach the trampoline. Asking
     * ourselves last of the three keeps Bionic's implementation preferred
     * wherever one exists. */
    void *self_handle = atomic_load_explicit(&shim_self, memory_order_acquire);
    if (!p && self_handle) p = dlsym(self_handle, name);
    if (!p) p = dlsym(RTLD_NEXT, name);
    return p;
}

/* glibc's XPG entry returns error numbers; older Bionic uses -1 and errno. */
int __xpg_strerror_r(int code, char *buf, size_t len) {
    int saved_errno = errno;
    void *libc_handle = atomic_load_explicit(&shim_libc, memory_order_acquire);
    if (!libc_handle) {
        shim_open_handles();
        libc_handle = atomic_load_explicit(&shim_libc, memory_order_acquire);
    }
    int (*posix_strerror)(int, char *, size_t) = libc_handle
        ? (int (*)(int, char *, size_t))dlsym(libc_handle, "strerror_r") : 0;
    int result = posix_strerror ? posix_strerror(code, buf, len) : ENOSYS;
    if (posix_strerror && !glibc_errno_known(code)) result = EINVAL;
    else if (result < 0) result = errno;
    errno = saved_errno;
    return result;
}

/*
 * Accessors for the data symbols the stubs re-export. The stubs cannot read
 * `environ` or `stdout` by name: they define those very names (versioned, at
 * default visibility), so the reference would bind to their own zeroed storage
 * and the constructor would copy NULL over NULL; measured with readelf, the
 * GLOB_DAT entries pointed at the stub's own .bss. This library defines none of
 * those names, so from here the references reach Bionic's real ones.
 *
 * It must not define the environment's names either: Bionic's libc exports
 * only `environ`, at its own LIBC version, and the stubs export all three at
 * GLIBC_2.17. A definition here is preemptible, so a store to it goes through
 * a GOT entry the loader binds to the first definition it finds, and Bionic's
 * libc, loaded with the program, is searched before anything dlopen brings in
 * (glibc searches in the same order, which is where test-native-shims.py shows
 * the overwrite). An earlier version defined all three and filled them from
 * /proc/self/environ at load, which wrote the environment the process was
 * started with over the live one the first time a glibc addon loaded: every
 * variable set since exec was gone and every one removed was back.
 * build-glibc-shim.sh refuses a build that relocates any of the three by a
 * bare name.
 */
FILE *__shim_stdin(void)  { return stdin; }
FILE *__shim_stdout(void) { return stdout; }
FILE *__shim_stderr(void) { return stderr; }

/*
 * The stubs are handed a copy of the environment, not Bionic's pointer. Each
 * keeps what it is handed for the life of the process, and Bionic's setenv
 * grows its array with reallocarray when a name is added (Android 10's libc.so
 * does, read in its disassembly), which can free the block a kept pointer
 * names. The strings are copied as well, so nothing a stub keeps depends on
 * how Bionic manages its own memory.
 *
 * So an addon reading glibc's `environ` sees the environment as it was when
 * its stub loaded, while getenv and setenv, forwarded to Bionic, see the live
 * one. Reading the array races a concurrent setenv exactly as getenv does; the
 * copy stops at the count it allocated for, or at the first NULL, whichever
 * comes first, and a failed allocation leaves it shorter rather than unended.
 */
char **__shim_environ(void) {
    size_t count = 0;
    while (environ && environ[count]) count++;
    char **copy = calloc(count + 1, sizeof(*copy));
    if (!copy) return environ;
    for (size_t i = 0; i < count && environ && environ[i]; i++) {
        if (!(copy[i] = strdup(environ[i]))) break;
    }
    return copy;
}

/*
 * The last few glibc keeps and Bionic does not.
 *
 * bcmp is the BSD spelling of memcmp with only its zero/non-zero result
 * meaningful, which is exactly what memcmp already promises.
 */
int bcmp(const void *a, const void *b, size_t n) { return memcmp(a, b, n); }

/*
 * The scanf family under the names glibc actually links against.
 *
 * glibc's stdio.h redirects sscanf, fscanf, scanf and their v- forms to
 * __isoc99_ spellings whenever C99 conversion rules apply, which is every build
 * that has not asked for the older ones. So an addon whose source says sscanf
 * imports a name Bionic has never had at any API level, and the forwarder for it
 * aborts on first call. Compiling all six calls against glibc 2.36 on aarch64
 * emits exactly these six undefined symbols, which is where the list comes from.
 *
 * The C99 behaviour the redirect selects -- %a as a conversion rather than the
 * GNU allocation modifier -- is already what Bionic implements, so each of these
 * is the plain function and nothing more.
 */
int __isoc99_sscanf(const char *s, const char *fmt, ...) {
    va_list ap; va_start(ap, fmt);
    int rc = vsscanf(s, fmt, ap);
    va_end(ap);
    return rc;
}
int __isoc99_fscanf(FILE *f, const char *fmt, ...) {
    va_list ap; va_start(ap, fmt);
    int rc = vfscanf(f, fmt, ap);
    va_end(ap);
    return rc;
}
int __isoc99_scanf(const char *fmt, ...) {
    va_list ap; va_start(ap, fmt);
    int rc = vfscanf(stdin, fmt, ap);
    va_end(ap);
    return rc;
}
int __isoc99_vsscanf(const char *s, const char *fmt, va_list ap) { return vsscanf(s, fmt, ap); }
int __isoc99_vfscanf(FILE *f, const char *fmt, va_list ap) { return vfscanf(f, fmt, ap); }
int __isoc99_vscanf(const char *fmt, va_list ap) { return vfscanf(stdin, fmt, ap); }

/*
 * pidfd helpers, glibc 2.36 and later. Bionic has the syscalls but not these
 * wrappers. Reporting "not implemented" is honest and is a case callers of these
 * already handle -- they exist precisely because older kernels lack them.
 */
int pidfd_getpid(int fd) { (void)fd; errno = ENOSYS; return -1; }
int pidfd_spawnp(int *pidfd, const char *file, const void *facts,
                 const void *attrp, char *const argv[], char *const envp[]) {
    (void)pidfd; (void)file; (void)facts; (void)attrp; (void)argv; (void)envp;
    errno = ENOSYS;
    return ENOSYS;
}

/*
 * posix_spawn's chdir action. Bionic has it under the _np name POSIX had not yet
 * standardised when it was added -- but only from API 34, the same dividing line
 * copy_file_range sits on.
 *
 * This used to declare the _np name by hand and call it directly, on the
 * reasoning that the header's API guard was the only thing in the way. The
 * guard was not the obstacle; it was the warning. Declaring a symbol does not
 * make it exist, it only moves the failure from compile time to load time, and
 * load time is far worse here: the reference was a strong undefined symbol, so
 * on an API 33 device Bionic's loader failed to bind it and dlopen of
 * libglibc-shim.so failed outright -- taking every stub and therefore every
 * glibc addon with it, on exactly the devices the minimum supports. Nothing
 * caught it because every test ran on an API 36 emulator, where the symbol is
 * present. It was the only undefined symbol in this library that Bionic could
 * not supply.
 *
 * Resolved at runtime instead, like copy_file_range, so the library loads
 * everywhere and only this one action is unavailable below 34. The
 * posix_spawn_file_actions_* family reports errors by return value rather than
 * through errno, so ENOSYS is returned directly.
 */
int posix_spawn_file_actions_addchdir(posix_spawn_file_actions_t *acts,
                                      const char *path) {
    typedef int (*addchdir_fn)(posix_spawn_file_actions_t *, const char *);
    /* 0 = unresolved, 1 = absent in Bionic, otherwise the resolved function address. */
    static _Atomic(uintptr_t) cached = 0;

    uintptr_t addr = atomic_load_explicit(&cached, memory_order_acquire);
    if (addr == 0) {
        void *libc_handle = atomic_load_explicit(&shim_libc, memory_order_acquire);
        if (!libc_handle) {
            shim_open_handles();
            libc_handle = atomic_load_explicit(&shim_libc, memory_order_acquire);
        }
        void *sym = libc_handle ? dlsym(libc_handle, "posix_spawn_file_actions_addchdir_np") : 0;
        addr = sym ? (uintptr_t)sym : (uintptr_t)1;
        atomic_store_explicit(&cached, addr, memory_order_release);
    }

    if (addr == (uintptr_t)1) return ENOSYS;
    return ((addchdir_fn)addr)(acts, path);
}

/*
 * copy_file_range, which Bionic gained at API 34.
 *
 * This one is not a symbol Bionic lacks, it is a symbol Bionic lacks *below the
 * minimum this app supports*. minSdk is 29, and the NDK's stub libc for 29, 30,
 * 33, 34 and 35 puts the dividing line at 34: absent at 29, 30 and 33, present
 * at 34.
 * An addon in the shipped tree imports it, so on an Android 13 device, and on
 * every older one the app supports, the forwarder resolved to NULL and aborted
 * the process the first time a file was copied -- while every emulator running
 * 34 or later resolves it and shows nothing wrong. That asymmetry is why this
 * needs the NDK's per-API answer and not a device test.
 *
 * The real one is preferred wherever it exists; it is a kernel-side copy and
 * this is not. Resolution goes straight to Bionic's libc rather than through
 * __shim_resolve, which consults this library too and would find its way back
 * here.
 *
 * The fallback keeps the offset semantics, which are the part worth getting
 * right: a NULL offset pointer reads or writes at the descriptor's own position
 * and advances it, while a non-NULL one starts there, leaves the descriptor
 * alone, and is advanced by the number of bytes copied. Short counts are a
 * result, not an error -- callers loop -- and an error after partial progress
 * reports the progress.
 */
static ssize_t copy_file_range_fallback(int fd_in, off64_t *off_in, int fd_out,
                                        off64_t *off_out, size_t len, unsigned flags) {
    if (flags) { errno = EINVAL; return -1; }

    struct stat input_stat, output_stat;
    if (fstat(fd_in, &input_stat) || fstat(fd_out, &output_stat)) return -1;
    if (input_stat.st_dev == output_stat.st_dev && input_stat.st_ino == output_stat.st_ino) {
        off64_t input = off_in ? *off_in : lseek(fd_in, 0, SEEK_CUR);
        off64_t output = off_out ? *off_out : lseek(fd_out, 0, SEEK_CUR);
        if (input < 0 || output < 0) { errno = EINVAL; return -1; }
        uint64_t available = input < input_stat.st_size ? (uint64_t)(input_stat.st_size - input) : 0;
        uint64_t count = len < available ? len : available;
        uint64_t distance = input <= output ? (uint64_t)(output - input) : (uint64_t)(input - output);
        if (distance < count) { errno = EINVAL; return -1; }
        /* The kernel copies what the file held when the call began. Looping to `len`
         * would re-read bytes this call has just appended. */
        len = (size_t)count;
    }

    /* 16 KB rather than the 64 KB a copy loop would normally take: this runs on
     * whatever thread the addon calls from, including Node worker threads, and a
     * shim has no business putting 64 KB on a stack it did not size. At this
     * scale the syscall overhead is already amortised. */
    char buf[16384];
    size_t total = 0;
    int failed = 0;

    while (total < len) {
        size_t want = len - total;
        if (want > sizeof(buf)) want = sizeof(buf);

        ssize_t got = off_in ? pread(fd_in, buf, want, *off_in + (off64_t)total)
                             : read(fd_in, buf, want);
        if (got < 0) { failed = 1; break; }
        if (got == 0) break;                    /* end of input */

        ssize_t put = 0;
        while (put < got) {
            ssize_t n = off_out
                ? pwrite(fd_out, buf + put, (size_t)(got - put),
                         *off_out + (off64_t)(total + (size_t)put))
                : write(fd_out, buf + put, (size_t)(got - put));
            if (n <= 0) {
                if (n == 0) errno = EIO;
                failed = 1;
                break;
            }
            put += n;
        }
        total += (size_t)put;
        if (put < got) {
            /* read() took `got` bytes from the descriptor's own position but
             * only `put` were copied, so fd_in now points past the data still
             * owed. The real call leaves it advanced by exactly what it copied,
             * and this function's own contract tells callers to loop on a short
             * count, so without the rewind that loop resumes past the gap and
             * the difference is data silently dropped. Measured against the
             * kernel with a write ceiling: it left fd_in at 20000, this left it
             * at 32768. Only the read() path needs it; pread() never moved the
             * descriptor. Save and restore errno so a failing rewind cannot
             * mask the write error that stopped the copy. */
            if (!off_in) {
                int saved_errno = errno;
                (void)lseek(fd_in, -(off_t)(got - put), SEEK_CUR);
                errno = saved_errno;
            }
            break;
        }
        if (failed) break;
    }

    if (off_in) *off_in += (off64_t)total;
    if (off_out) *off_out += (off64_t)total;
    if (total == 0 && failed) return -1;
    return (ssize_t)total;
}

ssize_t copy_file_range(int fd_in, off64_t *off_in, int fd_out, off64_t *off_out,
                        size_t len, unsigned flags) {
    typedef ssize_t (*copy_file_range_fn)(int, off64_t *, int, off64_t *, size_t, unsigned);
    /* 0 = unresolved, 1 = absent in Bionic, otherwise the resolved function address. */
    static _Atomic(uintptr_t) cached = 0;

    uintptr_t addr = atomic_load_explicit(&cached, memory_order_acquire);
    if (addr == 0) {
        void *libc_handle = atomic_load_explicit(&shim_libc, memory_order_acquire);
        if (!libc_handle) {
            shim_open_handles();
            libc_handle = atomic_load_explicit(&shim_libc, memory_order_acquire);
        }
        void *sym = libc_handle ? dlsym(libc_handle, "copy_file_range") : 0;
        addr = sym ? (uintptr_t)sym : (uintptr_t)1;
        atomic_store_explicit(&cached, addr, memory_order_release);
    }

    if (addr != (uintptr_t)1) return ((copy_file_range_fn)addr)(fd_in, off_in, fd_out, off_out, len, flags);
    return copy_file_range_fallback(fd_in, off_in, fd_out, off_out, len, flags);
}

/*
 * statx, which Bionic gained at API 30.
 *
 * The real one is used wherever it exists, looked up in Bionic's libc the way
 * copy_file_range is above, so Android 11 and later get exactly the call they
 * always got however an addon came to bind this name.
 *
 * On Android 10 there is nothing to forward to: libc has no such symbol, and
 * the lists that release builds its app seccomp filter from do not name the
 * system call either (read in bionic at android-10.0.0_r1, not run), so a raw
 * syscall is not a fallback. The answer is built from fstatat, which is what
 * glibc does on a kernel without statx.
 *
 * Birth time is the one field fstatat cannot supply, and stx_mask says so by
 * naming the basic set only; callers are required to read the mask.
 */
int statx(int dirfd, const char *path, int flags, unsigned mask, struct statx *out) {
    typedef int (*statx_fn)(int, const char *, int, unsigned, struct statx *);
    /* 0 = unresolved, 1 = absent in Bionic, otherwise the resolved function address. */
    static _Atomic(uintptr_t) cached = 0;

    uintptr_t addr = atomic_load_explicit(&cached, memory_order_acquire);
    if (addr == 0) {
        void *libc_handle = atomic_load_explicit(&shim_libc, memory_order_acquire);
        if (!libc_handle) {
            shim_open_handles();
            libc_handle = atomic_load_explicit(&shim_libc, memory_order_acquire);
        }
        void *sym = libc_handle ? dlsym(libc_handle, "statx") : 0;
        addr = sym ? (uintptr_t)sym : (uintptr_t)1;
        atomic_store_explicit(&cached, addr, memory_order_release);
    }
    if (addr != (uintptr_t)1) return ((statx_fn)addr)(dirfd, path, flags, mask, out);

    struct stat st;
    /* The sync bits steer a network filesystem and mean nothing to fstatat,
     * which rejects flags it does not know. */
    if (fstatat(dirfd, path, &st, flags & ~AT_STATX_SYNC_TYPE) != 0) return -1;

    memset(out, 0, sizeof(*out));
    out->stx_mask = STATX_BASIC_STATS;
    out->stx_blksize = (uint32_t)st.st_blksize;
    out->stx_nlink = (uint32_t)st.st_nlink;
    out->stx_uid = st.st_uid;
    out->stx_gid = st.st_gid;
    out->stx_mode = (uint16_t)st.st_mode;
    out->stx_ino = st.st_ino;
    out->stx_size = (uint64_t)st.st_size;
    out->stx_blocks = (uint64_t)st.st_blocks;
    out->stx_atime.tv_sec = st.st_atim.tv_sec;
    out->stx_atime.tv_nsec = (uint32_t)st.st_atim.tv_nsec;
    out->stx_mtime.tv_sec = st.st_mtim.tv_sec;
    out->stx_mtime.tv_nsec = (uint32_t)st.st_mtim.tv_nsec;
    out->stx_ctime.tv_sec = st.st_ctim.tv_sec;
    out->stx_ctime.tv_nsec = (uint32_t)st.st_ctim.tv_nsec;
    out->stx_rdev_major = major(st.st_rdev);
    out->stx_rdev_minor = minor(st.st_rdev);
    out->stx_dev_major = major(st.st_dev);
    out->stx_dev_minor = minor(st.st_dev);
    return 0;
}

/* ------------------------------------------------------------------------
 * Translating wrappers.
 *
 * Everything above is a symbol Bionic simply lacks. What follows is different
 * and more dangerous: symbols Bionic HAS, whose arguments it lays out
 * differently from glibc. Forwarding one of these does not fail -- it writes
 * the right bytes to the wrong offsets and the program carries on. There is no
 * crash to find, and the damage surfaces somewhere else entirely.
 *
 * Each wrapper below converts at the boundary. The generator points the
 * versioned export at these instead of at Bionic, and refuses to forward any
 * other symbol on its known-incompatible list -- so a struct we have not
 * translated yet aborts by name rather than corrupting quietly.
 * ------------------------------------------------------------------------ */

#include <android/log.h>
#include <netdb.h>
#include <sys/socket.h>

/*
 * struct sigaction, and the two libcs could hardly disagree more on arm64.
 *
 *   Bionic (32 bytes)   int sa_flags; union{handler}; sigset_t sa_mask (8);
 *                       void (*sa_restorer)(void);
 *   glibc  (152 bytes)  union{handler}; sigset_t sa_mask (128); int sa_flags;
 *                       void (*sa_restorer)(void);
 *
 * Handed one glibc struct unconverted, Bionic reads the low half of the handler
 * pointer as sa_flags and takes the handler itself out of the first word of the
 * mask. It then installs that as a signal handler. Nothing reports an error.
 *
 * Only the low 64 bits of glibc's mask carry anything: there are 64 signals and
 * the kernel's own sigset is 64 bits wide. The remaining 120 bytes exist so the
 * structure can outlive that limit, and are copied as zero.
 */
struct glibc_sigaction {
    void *handler;
    unsigned long mask[16];
    int flags;
    void (*restorer)(void);
};

static void from_glibc_sigaction(const struct glibc_sigaction *g, struct sigaction *b) {
    b->sa_flags = g->flags;
    b->sa_handler = (sighandler_t)g->handler;
    memset(&b->sa_mask, 0, sizeof(b->sa_mask));
    b->sa_mask.sig[0] = g->mask[0];
    b->sa_restorer = g->restorer;
}

static void to_glibc_sigaction(const struct sigaction *b, struct glibc_sigaction *g) {
    memset(g, 0, sizeof(*g));
    g->handler = (void *)b->sa_handler;
    g->mask[0] = b->sa_mask.sig[0];
    g->flags = b->sa_flags;
    g->restorer = b->sa_restorer;
}

int __shim_sigaction(int signum, const struct glibc_sigaction *act,
                     struct glibc_sigaction *oldact) {
    struct sigaction b_act, b_old;
    int rc = sigaction(signum, act ? (from_glibc_sigaction(act, &b_act), &b_act) : 0,
                       oldact ? &b_old : 0);
    if (rc == 0 && oldact) to_glibc_sigaction(&b_old, oldact);
    return rc;
}

/*
 * Signal sets. glibc's is 128 bytes, Bionic's is 8, and only the first 8 mean
 * anything. Bionic's sigemptyset would leave the other 120 as whatever was on
 * the caller's stack -- harmless while every reader is one of these wrappers,
 * and not worth relying on. They are cleared.
 */
int __shim_sigemptyset(unsigned long *set) {
    memset(set, 0, 128);
    return 0;
}

int __shim_sigfillset(unsigned long *set) {
    memset(set, 0, 128);
    set[0] = ~0UL;
    return 0;
}

int __shim_sigaddset(unsigned long *set, int signum) {
    if (signum < 1 || signum > 64) { errno = EINVAL; return -1; }
    set[0] |= 1UL << (signum - 1);
    return 0;
}

int __shim_sigdelset(unsigned long *set, int signum) {
    if (signum < 1 || signum > 64) { errno = EINVAL; return -1; }
    set[0] &= ~(1UL << (signum - 1));
    return 0;
}

int __shim_sigismember(const unsigned long *set, int signum) {
    if (signum < 1 || signum > 64) { errno = EINVAL; return -1; }
    return (set[0] >> (signum - 1)) & 1;
}

int __shim_sigprocmask(int how, const unsigned long *set, unsigned long *oldset) {
    sigset_t b_set, b_old;
    if (set) { memset(&b_set, 0, sizeof(b_set)); b_set.sig[0] = set[0]; }
    int rc = sigprocmask(how, set ? &b_set : 0, oldset ? &b_old : 0);
    if (rc == 0 && oldset) {
        memset(oldset, 0, 128);
        oldset[0] = b_old.sig[0];
    }
    return rc;
}

int __shim_pthread_sigmask(int how, const unsigned long *set, unsigned long *oldset) {
    sigset_t b_set, b_old;
    if (set) { memset(&b_set, 0, sizeof(b_set)); b_set.sig[0] = set[0]; }
    int rc = pthread_sigmask(how, set ? &b_set : 0, oldset ? &b_old : 0);
    if (rc == 0 && oldset) {
        memset(oldset, 0, 128);
        oldset[0] = b_old.sig[0];
    }
    return rc;
}

/*
 * struct addrinfo. Same size and same fields, but Bionic orders them
 * ai_canonname then ai_addr, where glibc has ai_addr then ai_canonname
 * (netdb.h in both). Forwarded unconverted, a caller reads the canonical name
 * as a socket address and connects to whatever that memory happens to hold.
 *
 * The list is converted in place on the way out, which is safe because both
 * layouts are the same size and we own the allocation until freeaddrinfo.
 */
struct glibc_addrinfo {
    int ai_flags, ai_family, ai_socktype, ai_protocol;
    socklen_t ai_addrlen;
    struct sockaddr *ai_addr;
    char *ai_canonname;
    struct glibc_addrinfo *ai_next;
};

/*
 * The AI_ and EAI_ constants, which the two libcs number differently. Swapping
 * the struct fields was only half the boundary: the flag word going in and the
 * error code coming out are both plain ints that mean different things on each
 * side, and forwarding them unchanged is not a near miss.
 *
 * The flags cross-collide rather than merely disagree. glibc's AI_V4MAPPED is
 * 8, which is Bionic's AI_NUMERICSERV; glibc's AI_NUMERICSERV is 1024, which is
 * Bionic's AI_ADDRCONFIG. glibc's AI_ALL (16) and AI_ADDRCONFIG (32) are bits
 * Bionic assigns to nothing, and Bionic rejects a flag word carrying an unknown
 * bit outright. So the common idiom AI_V4MAPPED|AI_ADDRCONFIG -- 8|32 -- arrived
 * as "numeric service, plus one undefined bit" and the call failed with
 * EAI_BADFLAGS, measured on device before this translation existed.
 *
 * Mapping each name to Bionic's number for the same name is still not enough,
 * and this is the part a header cannot tell you. Bionic defines AI_ALL and
 * AI_V4MAPPED but its getaddrinfo refuses them: AI_MASK is 1039, covering only
 * PASSIVE, CANONNAME, NUMERICHOST, NUMERICSERV and ADDRCONFIG, and anything else
 * is EAI_BADFLAGS. Offered to the device one at a time, those two are the two
 * that fail. They are therefore dropped rather than translated -- the request
 * loses a preference and still answers, where forwarding it fails outright.
 *
 * The error codes have no overlap at all: glibc's are negative, Bionic's are
 * 1..14. An addon testing rc == EAI_AGAIN (-3) against Bionic's 2 never matched,
 * so a retryable DNS failure read as an unrecognised one.
 *
 * Values on both sides were measured -- glibc 2.36 aarch64 under Debian, Bionic
 * on an arm64 device -- rather than recalled.
 *
 * The ceiling: dropping AI_V4MAPPED and AI_ALL means an addon asking for
 * IPv4-mapped results on an IPv6-only network gets Bionic's ordinary answer
 * instead. AI_IDN and AI_CANONIDN (glibc 0x40, 0x80) go the same way, there
 * being no IDN support here to ask for. And each returned node's ai_flags is
 * left in Bionic's numbering: both libcs echo the hint word into every node, and
 * translating it back would cost a second walk to serve a field POSIX leaves
 * unspecified and callers do not read.
 */
#define G_AI_PASSIVE     0x0001
#define G_AI_CANONNAME   0x0002
#define G_AI_NUMERICHOST 0x0004
#define G_AI_V4MAPPED    0x0008
#define G_AI_ALL         0x0010
#define G_AI_ADDRCONFIG  0x0020
#define G_AI_NUMERICSERV 0x0400

static int ai_flags_to_bionic(int g) {
    int b = 0;
    if (g & G_AI_PASSIVE)     b |= AI_PASSIVE;
    if (g & G_AI_CANONNAME)   b |= AI_CANONNAME;
    if (g & G_AI_NUMERICHOST) b |= AI_NUMERICHOST;
    if (g & G_AI_NUMERICSERV) b |= AI_NUMERICSERV;
    if (g & G_AI_ADDRCONFIG)  b |= AI_ADDRCONFIG;
    /* G_AI_V4MAPPED and G_AI_ALL are deliberately absent: Bionic's AI_MASK
     * refuses both, so translating them turns a working lookup into
     * EAI_BADFLAGS. Everything else glibc can set is dropped for the same
     * reason -- an unknown bit fails the whole call. */
    return b;
}

/* Bionic's codes run 1..14 with no gaps, so one table answers both directions.
 * Index 0 is never reached: 0 is success. */
static const int eai_glibc_for_bionic[] = {
    0,    /* unused          */
    -9,   /* EAI_ADDRFAMILY  */
    -3,   /* EAI_AGAIN       */
    -1,   /* EAI_BADFLAGS    */
    -4,   /* EAI_FAIL        */
    -6,   /* EAI_FAMILY      */
    -10,  /* EAI_MEMORY      */
    -5,   /* EAI_NODATA      */
    -2,   /* EAI_NONAME      */
    -8,   /* EAI_SERVICE     */
    -7,   /* EAI_SOCKTYPE    */
    -11,  /* EAI_SYSTEM      */
    -4,   /* EAI_BADHINTS -- no glibc spelling; a permanent failure either way */
    -4,   /* EAI_PROTOCOL -- likewise                                          */
    -12,  /* EAI_OVERFLOW    */
};
#define EAI_TABLE_LEN ((int)(sizeof(eai_glibc_for_bionic) / sizeof(*eai_glibc_for_bionic)))

static int eai_to_glibc(int rc) {
    if (rc < 1 || rc >= EAI_TABLE_LEN) return -4;  /* EAI_FAIL */
    return eai_glibc_for_bionic[rc];
}

/* The reverse, for handing a code back to Bionic's gai_strerror. -4 appears
 * three times above; the search finds EAI_FAIL first, which is the right one.
 *
 * Zero is passed through rather than searched for. Callers do print
 * gai_strerror(rc) unconditionally, and mapping success onto EAI_FAIL would have
 * this name a failure that did not happen. Bionic answers "Success" there;
 * glibc's own answer is "Unknown error", so passing through is if anything the
 * more useful of the two. A code that is neither zero nor in the table is
 * reported as EAI_FAIL, which is what an unrecognised permanent failure is. */
static int eai_to_bionic(int rc) {
    if (rc == 0) return 0;
    for (int i = 1; i < EAI_TABLE_LEN; i++)
        if (eai_glibc_for_bionic[i] == rc) return i;
    return EAI_FAIL;
}

/* An addon passes back the glibc-numbered code it was given, so it has to be
 * put into Bionic's numbering before Bionic can name it. Forwarded unchanged,
 * every code named the wrong error. */
const char *__shim_gai_strerror(int code) { return gai_strerror(eai_to_bionic(code)); }

int __shim_getaddrinfo(const char *node, const char *service,
                       const struct glibc_addrinfo *hints,
                       struct glibc_addrinfo **res) {
    struct addrinfo b_hints;
    struct addrinfo *b_res = 0;

    if (hints) {
        memset(&b_hints, 0, sizeof(b_hints));
        b_hints.ai_flags = ai_flags_to_bionic(hints->ai_flags);
        b_hints.ai_family = hints->ai_family;
        b_hints.ai_socktype = hints->ai_socktype;
        b_hints.ai_protocol = hints->ai_protocol;
    }

    int rc = getaddrinfo(node, service, hints ? &b_hints : 0, &b_res);
    if (rc != 0) return eai_to_glibc(rc);

    /* Same size, swapped middle: rewriting each node in place keeps the
     * allocation Bionic's, so freeaddrinfo still owns it. */
    for (struct addrinfo *n = b_res; n; n = n->ai_next) {
        struct glibc_addrinfo *g = (struct glibc_addrinfo *)n;
        char *canon = n->ai_canonname;
        struct sockaddr *addr = n->ai_addr;
        g->ai_addr = addr;
        g->ai_canonname = canon;
    }
    *res = (struct glibc_addrinfo *)b_res;
    return 0;
}

/*
 * getnameinfo, the same boundary and the quieter half of it.
 *
 * Its NI_ flags cross-collide exactly the way the AI_ ones do -- glibc's
 * NI_NUMERICHOST is 1, which is Bionic's NI_NOFQDN; glibc's NI_NUMERICSERV is 2,
 * which is Bionic's NI_NUMERICHOST; NOFQDN and NAMEREQD trade 4 and 8 the same
 * way -- but Bionic validates nothing here, so nothing fails and the call just
 * answers a different question. Measured on device against the loopback address:
 * asking for NI_NUMERICHOST alone, which is the flag that means "give me an
 * address, do not look anything up", returned "localhost" -- Bionic read bit 1
 * as NOFQDN and performed exactly the reverse lookup the caller had asked to
 * avoid. Asking for NI_NUMERICSERV returned "http" where the caller wanted "80".
 * Through the wrapper the same three calls answer 127.0.0.1 and 80.
 *
 * The lengths differ too, and only in type: glibc declares hostlen and servlen
 * socklen_t, Bionic size_t. AArch64 zeroes the upper half of an X register on
 * any W write, so in practice the value survives -- but converting here says so
 * rather than relying on it.
 *
 * Nothing in the tree imports this today. It is written now because the
 * getaddrinfo half of the same boundary was, and a wrapper for one of them
 * leaves the other looking checked.
 */
#define G_NI_NUMERICHOST 0x01
#define G_NI_NUMERICSERV 0x02
#define G_NI_NOFQDN      0x04
#define G_NI_NAMEREQD    0x08
#define G_NI_DGRAM       0x10

static int ni_flags_to_bionic(int g) {
    int b = 0;
    if (g & G_NI_NUMERICHOST) b |= NI_NUMERICHOST;
    if (g & G_NI_NUMERICSERV) b |= NI_NUMERICSERV;
    if (g & G_NI_NOFQDN)      b |= NI_NOFQDN;
    if (g & G_NI_NAMEREQD)    b |= NI_NAMEREQD;
    if (g & G_NI_DGRAM)       b |= NI_DGRAM;
    /* glibc's NI_IDN (32) and its companions are dropped: there is no IDN
     * support here to ask for. */
    return b;
}

int __shim_getnameinfo(const struct sockaddr *sa, socklen_t salen,
                       char *host, socklen_t hostlen,
                       char *serv, socklen_t servlen, int flags) {
    int rc = getnameinfo(sa, salen, host, (size_t)hostlen, serv, (size_t)servlen,
                         ni_flags_to_bionic(flags));
    return rc == 0 ? 0 : eai_to_glibc(rc);
}

void __shim_freeaddrinfo(struct glibc_addrinfo *res) {
    /* Swap back before handing it to Bionic, which will read its own layout. */
    for (struct glibc_addrinfo *g = res; g; g = g->ai_next) {
        struct addrinfo *n = (struct addrinfo *)g;
        struct sockaddr *addr = g->ai_addr;
        char *canon = g->ai_canonname;
        n->ai_canonname = canon;
        n->ai_addr = addr;
    }
    freeaddrinfo((struct addrinfo *)res);
}

/*
 * Reached when a symbol on the known-incompatible list is called and no
 * translating wrapper has been written for it. Forwarding it would write the
 * right bytes to the wrong offsets and continue; this stops instead, and names
 * the wrapper that is owed.
 */
__attribute__((noreturn)) void __shim_untranslated(const char *name) {
    __android_log_print(ANDROID_LOG_FATAL, "glibc-shim",
                        "%s passes a struct glibc and Bionic lay out differently, "
                        "and has no translating wrapper yet", name);
    abort();
}

/*
 * The unwinder entry points an addon imports from libgcc_s.
 *
 * Bionic's libc exports them from API 30, and each of these hands straight to
 * Bionic's wherever it exists, so Android 11 and later run the unwinder they
 * always ran however an addon came to bind the name. Every one takes at most
 * three machine words and returns at most one, which on AArch64 is the same
 * call whatever the C types are, so one shape forwards them all.
 *
 * On Android 10 arm64 libc exports none, and these answer only as far as they
 * can be reached: no addon in the tree imports _Unwind_RaiseException, so
 * nothing starts an unwind, which leaves the backtrace walk, and that reports
 * an empty stack. The rest take a context only a walk or a raise can hand out,
 * and name themselves if that ever stops being true.
 *
 * The cost is that a glibc addon gets no backtrace on Android 10. The NDK's
 * libunwind belongs here once an addon raises or a trace from one is wanted.
 */
#define SHIM_URC_END_OF_STACK 5

typedef uintptr_t (*shim_unwind_fn)(uintptr_t, uintptr_t, uintptr_t);

static shim_unwind_fn shim_bionic_unwinder(const char *name) {
    void *libc_handle = atomic_load_explicit(&shim_libc, memory_order_acquire);
    if (!libc_handle) {
        shim_open_handles();
        libc_handle = atomic_load_explicit(&shim_libc, memory_order_acquire);
    }
    return libc_handle ? (shim_unwind_fn)dlsym(libc_handle, name) : 0;
}

__attribute__((noreturn)) static void shim_no_unwinder(const char *name) {
    __android_log_print(ANDROID_LOG_FATAL, "glibc-shim",
                        "%s was called, and Android 10 has no unwinder to answer it", name);
    abort();
}

/* 0 = unresolved, 1 = absent in Bionic, otherwise the resolved function address. */
#define SHIM_UNWINDER(name, without)                                           \
    uintptr_t name(uintptr_t a, uintptr_t b, uintptr_t c) {                    \
        static _Atomic(uintptr_t) cached = 0;                                  \
        uintptr_t addr = atomic_load_explicit(&cached, memory_order_acquire);  \
        if (addr == 0) {                                                       \
            shim_unwind_fn sym = shim_bionic_unwinder(#name);                  \
            addr = sym ? (uintptr_t)sym : (uintptr_t)1;                        \
            atomic_store_explicit(&cached, addr, memory_order_release);        \
        }                                                                      \
        if (addr != (uintptr_t)1) return ((shim_unwind_fn)addr)(a, b, c);      \
        without;                                                               \
    }
SHIM_UNWINDER(_Unwind_Backtrace, return SHIM_URC_END_OF_STACK)
SHIM_UNWINDER(_Unwind_FindEnclosingFunction, return 0)
SHIM_UNWINDER(_Unwind_GetCFA, shim_no_unwinder("_Unwind_GetCFA"))
SHIM_UNWINDER(_Unwind_GetDataRelBase, shim_no_unwinder("_Unwind_GetDataRelBase"))
SHIM_UNWINDER(_Unwind_GetIP, shim_no_unwinder("_Unwind_GetIP"))
SHIM_UNWINDER(_Unwind_GetIPInfo, shim_no_unwinder("_Unwind_GetIPInfo"))
SHIM_UNWINDER(_Unwind_GetLanguageSpecificData, shim_no_unwinder("_Unwind_GetLanguageSpecificData"))
SHIM_UNWINDER(_Unwind_GetRegionStart, shim_no_unwinder("_Unwind_GetRegionStart"))
SHIM_UNWINDER(_Unwind_GetTextRelBase, shim_no_unwinder("_Unwind_GetTextRelBase"))
SHIM_UNWINDER(_Unwind_Resume, shim_no_unwinder("_Unwind_Resume"))
SHIM_UNWINDER(_Unwind_SetGR, shim_no_unwinder("_Unwind_SetGR"))
SHIM_UNWINDER(_Unwind_SetIP, shim_no_unwinder("_Unwind_SetIP"))

/*
 * A stack-protector canary for objects that import one. Bionic keeps its own
 * private to libc, so this is a separate value; that is harmless, because the
 * check compares the prologue's copy against this same variable. Initialized
 * once via an atomic compare-exchange from OS entropy (never stdio fopen), with
 * byte 0 masked to 0x00 so C-string over-reads cannot leak the canary.
 */
unsigned long __shim_stack_guard(void) {
    static _Atomic(unsigned long) guard = 0;
    unsigned long current = atomic_load_explicit(&guard, memory_order_acquire);
    if (current != 0) return current;

    unsigned long candidate = 0;
#if defined(__ANDROID__) || defined(__APPLE__) || defined(__FreeBSD__) || defined(__OpenBSD__) || defined(__NetBSD__)
    arc4random_buf(&candidate, sizeof(candidate));
#else
    size_t got = 0;
    while (got < sizeof(candidate)) {
        ssize_t n = getrandom((unsigned char *)&candidate + got, sizeof(candidate) - got, 0);
        if (n < 0) {
            if (errno == EINTR) continue;
            candidate = 0;
            break;
        }
        if (n == 0) {
            candidate = 0;
            break;
        }
        got += (size_t)n;
    }
#endif
    candidate &= ~(unsigned long)0xFF;
    if (candidate == 0) candidate = 0xff0a000000000000UL;  /* glibc's own fallback shape */

    unsigned long expected = 0;
    if (atomic_compare_exchange_strong_explicit(
            &guard, &expected, candidate,
            memory_order_acq_rel, memory_order_acquire)) {
        return candidate;
    }
    return expected;
}
