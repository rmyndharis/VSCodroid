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
#define _GNU_SOURCE 1
#include <errno.h>
#include <linux/signal.h>
#include <signal.h>
#include <stddef.h>
#include <sys/epoll.h>
#include <time.h>
#include <ucontext.h>

#if defined(__aarch64__)
#define SYS_epoll_pwait   22
#define SYS_rt_sigaction  134
#elif defined(__x86_64__)
#define SYS_epoll_pwait   281
#define SYS_rt_sigaction  13
#else
#error "seccomp shim supports Android arm64-v8a and x86_64 only"
#endif

#ifndef __NR_epoll_pwait2
#define __NR_epoll_pwait2 441
#endif

static inline long sys6(long nr, long a, long b, long c, long d, long e, long f) {
#if defined(__aarch64__)
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
#else
    register long rax __asm__("rax") = nr;
    register long rdi __asm__("rdi") = a;
    register long rsi __asm__("rsi") = b;
    register long rdx __asm__("rdx") = c;
    register long r10 __asm__("r10") = d;
    register long r8 __asm__("r8") = e;
    register long r9 __asm__("r9") = f;
    __asm__ volatile("syscall"
                     : "+a"(rax)
                     : "D"(rdi), "S"(rsi), "d"(rdx), "r"(r10), "r"(r8), "r"(r9)
                     : "rcx", "r11", "memory", "cc");
    return rax;
#endif
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

#if defined(__x86_64__)
#define KERNEL_SA_RESTORER 0x04000000UL
__attribute__((visibility("hidden"))) void signal_restorer(void);
__asm__(".text\n"
        ".globl signal_restorer\n"
        ".hidden signal_restorer\n"
        ".align 16\n"
        "signal_restorer:\n"
        "mov $15, %rax\n"
        "syscall\n");
#endif

/**
 * Answers one refused epoll_pwait2 and lets the thread carry on.
 *
 * Anything else refused is answered ENOSYS, which is the one reply a runtime
 * probing for a syscall can fall back from, and the handler stays installed.
 *
 * What this replaces read better than it behaved: it put SIGSYS back to SIG_DFL
 * and returned, on the belief that the refusal would be raised again and end the
 * process the way the platform meant it to. It is not raised again. Read from
 * the kernel sources rather than measured here: for SECCOMP_RET_TRAP,
 * kernel/seccomp.c rolls the registers back, raises the signal and skips the
 * call, and the kernel restores the syscall's original first argument in the
 * return register (x0 on arm64, RAX on x86_64), so the instruction is not
 * retried and a bare return hands the caller its own first argument as the
 * syscall's result. The disposition change also disarmed this handler for
 * the rest of the process, so the next epoll_pwait2, the call this file exists
 * for, would kill the process instead of being answered.
 */
static void on_sigsys(int sig, siginfo_t *info, void *ctx) {
    (void)sig;
    ucontext_t *uc = (ucontext_t *)ctx;
#if defined(__aarch64__)
    unsigned long *regs = (unsigned long *)uc->uc_mcontext.regs;
#define REG(n) regs[n]
#define RETREG regs[0]
#else
    greg_t *regs = uc->uc_mcontext.gregs;
#define REG(n) regs[n]
#define RETREG regs[REG_RAX]
#endif

    if (info->si_syscall != __NR_epoll_pwait2) {
        // Not ours, and the call did not run. Answer it rather than leaving the
        // caller with whatever the rollback left in x0, and leave the handler in
        // place so the one call this file is for is still answered afterwards.
        RETREG = (unsigned long)-ENOSYS;
        return;
    }

#if defined(__aarch64__)
    int fd = (int)REG(0);
    struct epoll_event *events = (struct epoll_event *)REG(1);
    int maxevents = (int)REG(2);
    const struct timespec *ts = (const struct timespec *)REG(3);
    const void *sigmask = (const void *)REG(4);
    unsigned long setsize = (unsigned long)REG(5);
#else
    int fd = (int)uc->uc_mcontext.gregs[REG_RDI];
    struct epoll_event *events = (struct epoll_event *)uc->uc_mcontext.gregs[REG_RSI];
    int maxevents = (int)uc->uc_mcontext.gregs[REG_RDX];
    const struct timespec *ts = (const struct timespec *)uc->uc_mcontext.gregs[REG_R10];
    const void *sigmask = (const void *)uc->uc_mcontext.gregs[REG_R8];
    unsigned long setsize = (unsigned long)uc->uc_mcontext.gregs[REG_R9];
#endif

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
    RETREG = (unsigned long)r;   // already -errno on failure, which is the ABI
}

/**
 * Installs the handler before anything else in the process runs.
 *
 * SA_NODEFER so a refusal raised from inside the handler is not held back, and
 * SA_SIGINFO because si_syscall is the only thing that says which call was
 * refused. Arm64 returns through the vDSO; x86_64 requires SA_RESTORER and the
 * tiny rt_sigreturn restorer above.
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
#if defined(__x86_64__)
    sa.flags |= KERNEL_SA_RESTORER;
    sa.restorer = signal_restorer;
#endif
    sa.mask = 0;
    sys6(SYS_rt_sigaction, SIGSYS, (long)&sa, 0, 8, 0, 0);
}
