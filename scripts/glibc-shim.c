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
 *   - renamed internals: __errno_location, __environ, __assert_fail
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
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

/* glibc keeps errno behind a function; Bionic exposes __errno(). */
int *__errno_location(void) { return __errno(); }

/*
 * The environment, under all three names glibc exports.
 *
 * Bionic keeps `environ` internal to libc rather than exporting it for other
 * shared objects to bind against, so a glibc addon that reads it fails to link
 * even though the data is right there. These are re-exported copies, filled from
 * getenv's view at load time.
 *
 * The ceiling: they are copies of the pointer, so an addon reading them after
 * the process has called setenv() sees the array as it was. Bionic reallocates
 * that array when it grows, and nothing here would notice. Fine for reading
 * configuration at startup, which is what these addons do; not a general
 * substitute for the real thing.
 */
char **environ = 0;
char **__environ = 0;
char **_environ = 0;

/*
 * Bionic keeps its environment array private -- it is not in libc's dynamic
 * symbol table, so a glibc addon that binds to `environ` fails to load even
 * though the data exists. These copies are built at load time from
 * /proc/self/environ, which the kernel maintains for every process and which is
 * the only view of it reachable from a shared object here.
 *
 * The ceiling: it is a snapshot. An addon reading it after the process calls
 * setenv() sees the environment as it was at load. That covers reading
 * configuration during initialisation, which is what these addons do with it,
 * and nothing more. Raising it would mean intercepting setenv, which is a much
 * larger promise than this file should make.
 */
__attribute__((constructor)) static void init_environ(void) {
    FILE *f = fopen("/proc/self/environ", "rb");
    if (!f) return;

    static char buf[64 * 1024];
    size_t n = fread(buf, 1, sizeof(buf) - 1, f);
    fclose(f);
    if (n == 0) return;
    buf[n] = '\0';

    size_t count = 0;
    for (size_t i = 0; i < n; i++) {
        if (buf[i] == '\0') count++;
    }

    static char *slots[1024];
    size_t used = 0;
    for (size_t i = 0; i < n && used < (sizeof(slots) / sizeof(*slots)) - 1; ) {
        slots[used++] = &buf[i];
        while (i < n && buf[i] != '\0') i++;
        i++;
    }
    slots[used] = 0;

    environ = slots;
    __environ = slots;
    _environ = slots;
}

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

static const unsigned short *ctype_b_table(void) {
    static unsigned short table[384];
    static const unsigned short *ptr;
    if (!ptr) {
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
            table[c + 128] = f;
        }
        ptr = table + 128;
    }
    return ptr;
}
const unsigned short **__ctype_b_loc(void) {
    static const unsigned short *p;
    p = ctype_b_table();
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
static const int *ctype_case_table(int want_upper) {
    static int lower[384], upper[384];
    static int built;
    if (!built) {
        for (int i = -128; i < 256; i++) {
            int v = (i < -1) ? i + 256 : i;
            lower[i + 128] = (v >= 'A' && v <= 'Z') ? v + 32 : v;
            upper[i + 128] = (v >= 'a' && v <= 'z') ? v - 32 : v;
        }
        built = 1;
    }
    return (want_upper ? upper : lower) + 128;
}

const int **__ctype_tolower_loc(void) {
    static const int *p;
    p = ctype_case_table(0);
    return &p;
}

const int **__ctype_toupper_loc(void) {
    static const int *p;
    p = ctype_case_table(1);
    return &p;
}

/* Version strings addons occasionally log or gate on. Nothing branches on the
 * exact value in practice; a modern one keeps any comparison happy. */
const char *gnu_get_libc_version(void) { return "2.31"; }
const char *gnu_get_libc_release(void) { return "stable"; }

/* Resolver reinitialisation. Bionic re-reads its configuration per query, so
 * there is nothing to do and success is the honest answer. */
int __res_init(void) { return 0; }

/*
 * The XPG variant returns int and always writes into the caller's buffer. Bionic
 * follows the GNU shape instead, returning a pointer that may be a static string
 * it never copied, so the copy has to happen here.
 */
int __xpg_strerror_r(int code, char *buf, size_t len) {
    const char *msg = strerror_r(code, buf, len);
    if (msg != buf) {
        if (len == 0) return 34; /* ERANGE */
        strncpy(buf, msg, len - 1);
        buf[len - 1] = '\0';
    }
    return 0;
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

static void *shim_libc;
static void *shim_libdl;
static void *shim_self;

__attribute__((constructor(101))) static void shim_open_handles(void) {
    shim_libc = dlopen("libc.so", RTLD_LAZY | RTLD_NOLOAD);
    shim_libdl = dlopen("libdl.so", RTLD_LAZY | RTLD_NOLOAD);
    shim_self = dlopen("libglibc-shim.so", RTLD_LAZY | RTLD_NOLOAD);
}

void *__shim_resolve(const char *name) {
    if (!shim_libc) shim_open_handles();

    /* The linker owns these; dlsym does not find them by name. */
    if (!__builtin_strcmp(name, "dlopen")) return (void *)&dlopen;
    if (!__builtin_strcmp(name, "dlsym")) return (void *)&dlsym;
    if (!__builtin_strcmp(name, "dlclose")) return (void *)&dlclose;
    if (!__builtin_strcmp(name, "dlerror")) return (void *)&dlerror;
    if (!__builtin_strcmp(name, "dladdr")) return (void *)&dladdr;
    if (!__builtin_strcmp(name, "dl_iterate_phdr")) return (void *)&dl_iterate_phdr;

    void *p = shim_libc ? dlsym(shim_libc, name) : 0;
    if (!p && shim_libdl) p = dlsym(shim_libdl, name);
    /* This library's own definitions, and they have to be asked for by handle.
     * RTLD_NEXT begins after the caller, and the caller is this file, so the
     * twelve symbols that exist only here -- __errno_location, __ctype_b_loc,
     * the __xstat family, bcmp, fcntl64 and the rest -- resolved to nothing and
     * left their trampolines null. Measured, not deduced: instrumenting this
     * function showed every one of them taking the NONE branch while the addon
     * ran correctly anyway, because the addon binds to this library directly and
     * never reaches the trampoline. That made the null a landmine rather than a
     * failure -- it fires the moment anything does reach the trampoline. Asking
     * ourselves last of the three keeps Bionic's implementation preferred
     * wherever one exists. */
    if (!p && shim_self) p = dlsym(shim_self, name);
    if (!p) p = dlsym(RTLD_NEXT, name);
    return p;
}

/*
 * Accessors for the data symbols the stubs re-export. The stubs cannot read
 * `environ` or `stdout` by name: they define those very names (versioned, at
 * default visibility), so the reference would bind to their own zeroed storage
 * and the constructor would copy NULL over NULL; measured with readelf, the
 * GLOB_DAT entries pointed at the stub's own .bss. This library defines
 * neither name, so from here the same references reach Bionic's real ones.
 */
char **__shim_environ(void) { return environ; }
FILE *__shim_stdin(void)  { return stdin; }
FILE *__shim_stdout(void) { return stdout; }
FILE *__shim_stderr(void) { return stderr; }

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
    static int (*real)(posix_spawn_file_actions_t *, const char *);
    static int looked_up;

    if (!looked_up) {
        if (!shim_libc) shim_open_handles();
        if (shim_libc) {
            *(void **)&real = dlsym(shim_libc, "posix_spawn_file_actions_addchdir_np");
        }
        looked_up = 1;
    }

    if (!real) return ENOSYS;
    return real(acts, path);
}

/*
 * copy_file_range, which Bionic gained at API 34.
 *
 * This one is not a symbol Bionic lacks, it is a symbol Bionic lacks *below the
 * minimum this app supports*. minSdk is 33, and the NDK's stub libc for 21, 33,
 * 34 and 35 puts the dividing line exactly there: absent at 33, present at 34.
 * An addon in the shipped tree imports it, so on an Android 13 device the
 * forwarder resolved to NULL and aborted the process the first time a file was
 * copied -- while every emulator running 34 or later resolves it and shows
 * nothing wrong. That asymmetry is why this needs the NDK's per-API answer and
 * not a device test.
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
            if (n < 0) { failed = 1; break; }
            put += n;
        }
        total += (size_t)put;
        if (put < got) {
            /* read() took `got` bytes from the descriptor's own position but
             * only `put` were copied, so fd_in now points past the data still
             * owed. The real call leaves it advanced by exactly what it copied,
             * and this function's own contract tells callers to loop on a short
             * count -- so without the rewind that loop resumes past the gap and
             * the difference is data silently dropped. Measured against the
             * kernel with a write ceiling: it left fd_in at 20000, this left it
             * at 32768. Only the read() path needs it; pread() never moved the
             * descriptor. */
            if (!off_in) lseek(fd_in, -(off_t)(got - put), SEEK_CUR);
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
    static ssize_t (*real)(int, off64_t *, int, off64_t *, size_t, unsigned);
    static int looked_up;

    if (!looked_up) {
        if (!shim_libc) shim_open_handles();
        if (shim_libc) {
            *(void **)&real = dlsym(shim_libc, "copy_file_range");
        }
        looked_up = 1;
    }

    if (real) return real(fd_in, off_in, fd_out, off_out, len, flags);
    return copy_file_range_fallback(fd_in, off_in, fd_out, off_out, len, flags);
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
 * A stack-protector canary for objects that import one. Bionic keeps its own
 * private to libc, so this is a separate value; that is harmless, because the
 * check compares the prologue's copy against this same variable. It only has to
 * be non-zero and not derivable from the file.
 */
unsigned long __shim_stack_guard(void) {
    static unsigned long value;
    if (!value) {
        FILE *f = fopen("/dev/urandom", "rb");
        if (f) {
            if (fread(&value, sizeof(value), 1, f) != 1) value = 0;
            fclose(f);
        }
        if (!value) value = 0xff0a000000000000UL;  /* glibc's own fallback shape */
    }
    return value;
}
