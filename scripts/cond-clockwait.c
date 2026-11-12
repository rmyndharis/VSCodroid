/*
 * pthread_cond_clockwait for an addon that has to load on Android 10.
 *
 * The addons are compiled for API 30, and libc++'s headers decide from that
 * level how a timed wait is made: from 30 they call pthread_cond_clockwait on
 * the monotonic clock, below it they convert the deadline to wall-clock time
 * and call pthread_cond_timedwait. Compiling for 29 would therefore change the
 * wait on every release, not only the one that lacks the function: the file
 * watcher's debounce and libzmq's condition variable would follow the wall
 * clock, and a clock set backwards mid-wait would hold them for the difference.
 *
 * So the level stays, and this stands in front of the one call Android 10
 * cannot satisfy. Linked with --wrap=pthread_cond_clockwait, every reference
 * to that name lands here. A release that has the function runs it, unchanged.
 * On Android 10 the monotonic arm below is the call Bionic's own
 * pthread_cond_clockwait makes (pthread_cond.cpp at android-11.0.0_r1), and it
 * is the only clock libc++ passes. The wall-clock arm is the public spelling of
 * the other, the same for a condition variable with default attributes.
 *
 * The real one is looked up, not named. A weak reference to
 * __real_pthread_cond_clockwait is what a wrapper would ordinarily keep, and
 * NDK r30's lld leaves it weak, but r27's, which CI builds with, makes it a
 * strong import, and a strong import of this name is the thing Android 10
 * cannot load. Looked up once at load rather than on first use, so no lookup
 * ever happens with the caller's mutex held and the pointer never changes
 * under a waiter.
 */

#include <dlfcn.h>
#include <errno.h>
#include <pthread.h>
#include <time.h>

static int (*real_clockwait)(pthread_cond_t *, pthread_mutex_t *, clockid_t,
                             const struct timespec *);

__attribute__((constructor(101))) static void find_real_clockwait(void) {
    *(void **)&real_clockwait = dlsym(RTLD_DEFAULT, "pthread_cond_clockwait");
}

__attribute__((visibility("hidden")))
int __wrap_pthread_cond_clockwait(pthread_cond_t *cond, pthread_mutex_t *mutex, clockid_t clock,
                                  const struct timespec *deadline) {
    if (real_clockwait) return real_clockwait(cond, mutex, clock, deadline);
    switch (clock) {
        case CLOCK_MONOTONIC: return pthread_cond_timedwait_monotonic_np(cond, mutex, deadline);
        case CLOCK_REALTIME: return pthread_cond_timedwait(cond, mutex, deadline);
        default: return EINVAL;
    }
}
