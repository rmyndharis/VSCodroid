// Starts the Claude Code CLI with the seccomp shim already in place.
//
// `claudeCode.claudeProcessWrapper` names one executable and the extension hands
// it the CLI as the first argument, which is musl's loader's own calling
// convention -- that is why the loader could be named there directly. It cannot
// be named directly any more, because the CLI needs `LD_PRELOAD` pointing at
// libseccomp-shim.so before the loader starts it, and a setting holds a path
// rather than an environment. So this sits in between: it puts the shim in the
// environment and execs the loader with the arguments it was given.
//
// Why not set LD_PRELOAD once, in the server's environment: every child of the
// server would inherit it, and most of them are Bionic rather than musl. The
// shim interposes `sigaction` against musl's structure layout, which is not
// Bionic's, so a Node process that picked it up would translate signal
// dispositions wrongly. It belongs to this one process tree and no other.
//
// Everything is resolved from this program's own directory, which is
// `nativeLibraryDir`: the loader and the shim are installed beside it, and that
// path changes on every reinstall, so nothing here may hardcode it.
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static const char kLoader[] = "libldmusl.so";
static const char kShim[] = "libseccomp-shim.so";

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <claude-binary> [args...]\n", argv[0]);
        return 2;
    }

    // argv[0] is this program's path, and the two files it needs are its
    // neighbours. readlink on /proc/self/exe rather than argv[0], which a caller
    // is free to set to anything.
    char self[PATH_MAX];
    ssize_t n = readlink("/proc/self/exe", self, sizeof(self) - 1);
    if (n <= 0) {
        fprintf(stderr, "claude-launch: cannot resolve my own path: %s\n", strerror(errno));
        return 1;
    }
    self[n] = '\0';
    char *slash = strrchr(self, '/');
    if (!slash) {
        fprintf(stderr, "claude-launch: %s has no directory\n", self);
        return 1;
    }
    *slash = '\0';

    char loader[PATH_MAX], shim[PATH_MAX];
    if (snprintf(loader, sizeof(loader), "%s/%s", self, kLoader) >= (int)sizeof(loader) ||
        snprintf(shim, sizeof(shim), "%s/%s", self, kShim) >= (int)sizeof(shim)) {
        fprintf(stderr, "claude-launch: path too long\n");
        return 1;
    }

    // Appended rather than assigned, so a preload the caller set for its own
    // reasons is kept. The shim goes last; order does not matter to the loader,
    // and keeping the caller's first leaves their intent visible in the value.
    const char *existing = getenv("LD_PRELOAD");
    char preload[PATH_MAX * 2];
    if (existing && *existing) {
        snprintf(preload, sizeof(preload), "%s:%s", existing, shim);
    } else {
        snprintf(preload, sizeof(preload), "%s", shim);
    }
    if (setenv("LD_PRELOAD", preload, 1) != 0) {
        fprintf(stderr, "claude-launch: cannot set LD_PRELOAD: %s\n", strerror(errno));
        return 1;
    }

    // loader, then everything this was called with from argv[1] on, which is the
    // CLI path followed by its own arguments.
    char **next = calloc((size_t)argc + 1, sizeof(char *));
    if (!next) {
        fprintf(stderr, "claude-launch: out of memory\n");
        return 1;
    }
    next[0] = loader;
    for (int i = 1; i < argc; i++) next[i] = argv[i];
    next[argc] = NULL;

    execv(loader, next);
    fprintf(stderr, "claude-launch: cannot run %s: %s\n", loader, strerror(errno));
    return 1;
}
