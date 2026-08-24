// Lets a program that calls epoll_pwait2 run on an Android that does not allow
// it, by answering the refusal instead of dying on it.
//
// The wall: an app may make only the system calls bionic exposes in
// SYSCALLS.TXT, and epoll_pwait2 (441) appears there in android15. On the
// android13 and android14 branches it is absent, so a program that calls it is
// refused -- and the refusal is not an error it can recover from. That is what
// kills the Claude Code CLI, whose runtime reaches for it as soon as its event
// loop starts. See the Claude Code row in the invariants table.
//
// What makes a shim possible at all is that Android refuses with
// SECCOMP_RET_TRAP rather than a kill: the syscall does not run, SIGSYS is
// delivered to the calling thread, and a handler that returns resumes the thread
// after the trapping instruction with whatever it left in x0. Measured on an API
// 33 emulator inside this app before any of this was written: the signal is
// catchable and the emulated call answered 0. So the handler below emulates
// epoll_pwait2 with epoll_pwait, which every Android does expose, and hands the
// result back as the syscall's own return value.
//
// Freestanding on purpose. It is preloaded into a musl process by musl's own
// loader, so it must not drag bionic's libc in behind it: there are no library
// calls here, only `svc #0`, and it is linked with -nostdlib. The kernel ABI
// structures it does use (siginfo, ucontext, epoll_event, timespec) are the same
// for either libc, which is why the NDK headers can describe them.
//
// The one behaviour it does not preserve is resolution. epoll_pwait2 takes a
// timespec and epoll_pwait takes whole milliseconds, so a timeout is rounded up
// to the next millisecond rather than truncated: a caller polling with a
// sub-millisecond timeout gets a slightly longer wait, where truncation to zero
// would turn its wait into a spin.
#include <errno.h>
#include <linux/signal.h>
#include <signal.h>
#include <stddef.h>
#include <sys/epoll.h>
#include <time.h>
#include <ucontext.h>

#define SYS_epoll_pwait   22
#define SYS_rt_sigaction  134

#ifndef __NR_epoll_pwait2
#define __NR_epoll_pwait2 441
#endif

static inline long sys6(long nr, long a, long b, long c, long d, long e, long f) {
    register long x8 __asm__("x8") = nr;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x4 __asm__("x4") = e;
    register long x5 __asm__("x5") = f;
    __asm__ volatile("svc #0"
                     : "+r"(x0)
                     : "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5), "r"(x8)
                     : "memory", "cc");
    return x0;
}

/**
 * What the kernel wants from rt_sigaction, which is not what libc's sigaction
 * takes: the mask is the kernel's own 64-bit set and its size is passed
 * separately. Declared here because a freestanding object has no libc to ask.
 */
struct kernel_sigaction {
    void (*handler)(int, siginfo_t *, void *);
    unsigned long flags;
    void (*restorer)(void);
    unsigned long mask;
};

/**
 * Answers one refused epoll_pwait2 and lets the thread carry on.
 *
 * Anything that is not epoll_pwait2 is left alone: the handler restores the
 * default disposition and returns, so the kill the platform intended still
 * happens for every other refused call rather than being swallowed here.
 */
static void on_sigsys(int sig, siginfo_t *info, void *ctx) {
    (void)sig;
    ucontext_t *uc = (ucontext_t *)ctx;
    unsigned long *regs = (unsigned long *)uc->uc_mcontext.regs;

    if (info->si_syscall != __NR_epoll_pwait2) {
        // Not ours. Put SIGSYS back to its default and return, which re-raises
        // it against the same instruction and ends the process the way the
        // platform meant it to end.
        struct kernel_sigaction dfl = { 0 };
        dfl.handler = (void (*)(int, siginfo_t *, void *))0;  // SIG_DFL
        sys6(SYS_rt_sigaction, SIGSYS, (long)&dfl, 0, 8, 0, 0);
        return;
    }

    int fd = (int)regs[0];
    struct epoll_event *events = (struct epoll_event *)regs[1];
    int maxevents = (int)regs[2];
    const struct timespec *ts = (const struct timespec *)regs[3];
    const void *sigmask = (const void *)regs[4];
    unsigned long setsize = (unsigned long)regs[5];

    // A null timespec is "wait forever", which epoll_pwait spells -1. Rounded
    // up, for the reason the file header gives.
    int timeout_ms = -1;
    if (ts) {
        long ms = ts->tv_sec * 1000L + (ts->tv_nsec + 999999L) / 1000000L;
        if (ms < 0) ms = 0;
        if (ms > 0x7fffffffL) ms = 0x7fffffffL;
        timeout_ms = (int)ms;
    }

    long r = sys6(SYS_epoll_pwait, fd, (long)events, maxevents, timeout_ms,
                  (long)sigmask, (long)setsize);
    regs[0] = (unsigned long)r;   // already -errno on failure, which is the ABI
}

/**
 * Installs the handler before anything else in the process runs.
 *
 * SA_NODEFER so a refusal raised from inside the handler is not held back, and
 * SA_SIGINFO because si_syscall is the only thing that says which call was
 * refused. No restorer: arm64 returns from a signal through the vDSO.
 */
/**
 * musl's own `struct sigaction`, which is not the kernel's: the mask comes
 * second and is 128 bytes wide, where the kernel wants flags second and an
 * 8-byte mask last. Declared here because a freestanding object has no headers
 * for the libc it is being loaded beside.
 */
struct musl_sigaction {
    void *handler;
    unsigned long mask[16];
    int flags;
    void (*restorer)(void);
};

/**
 * Keeps the handler above in place.
 *
 * The runtime installs a SIGSYS handler of its own once it is up, and that one
 * treats a refused syscall as fatal: measured, the emulation answers three calls
 * and then the fourth reaches the runtime's handler, which re-raises through
 * kill(). Interposed here rather than fought elsewhere, because this is the only
 * point where the change is visible to us. Every other signal is passed through
 * with the layout translated, so nothing else about the process changes.
 */
__attribute__((visibility("default")))
int shim_sigaction(int sig, const struct musl_sigaction *act, struct musl_sigaction *oact)
    __asm__("sigaction");
int shim_sigaction(int sig, const struct musl_sigaction *act, struct musl_sigaction *oact) {
    if (sig == SIGSYS && act) {
        return 0;   // answered as done; ours stays
    }
    // Both directions are translated. The caller's buffer is musl's shape and
    // the kernel's is not, so handing the kernel `oact` directly would have it
    // write sixteen bytes of its own layout into a hundred and fifty-two byte
    // structure the caller then reads as its own: the handler lands where the
    // mask belongs, and the caller crashes somewhere unrelated. That is a fault
    // this shim would have caused rather than prevented.
    struct kernel_sigaction k = { 0 };
    struct kernel_sigaction old = { 0 };
    if (act) {
        k.handler = (void (*)(int, siginfo_t *, void *))act->handler;
        k.flags = (unsigned long)(unsigned int)act->flags;
        k.restorer = act->restorer;
        k.mask = act->mask[0];
    }
    long r = sys6(SYS_rt_sigaction, sig, act ? (long)&k : 0, oact ? (long)&old : 0, 8, 0, 0);
    if (oact && r == 0) {
        for (unsigned i = 0; i < 16; i++) oact->mask[i] = 0;
        oact->handler = (void *)old.handler;
        oact->mask[0] = old.mask;
        oact->flags = (int)old.flags;
        oact->restorer = old.restorer;
    }
    return (int)r;
}

__attribute__((constructor)) static void install(void) {
    struct kernel_sigaction sa = { 0 };
    sa.handler = on_sigsys;
    sa.flags = SA_SIGINFO | SA_NODEFER;
    sa.mask = 0;
    sys6(SYS_rt_sigaction, SIGSYS, (long)&sa, 0, 8, 0, 0);
}
