// Starts the Claude Code CLI with the seccomp shim already in place.
//
// `claudeCode.claudeProcessWrapper` names one executable and the extension hands
// it the CLI as the first argument, which is musl's loader's own calling
// convention -- that is why the loader could be named there directly. It cannot
// be named directly any more, because the CLI needs libseccomp-shim.so loaded
// before its own code runs, and a setting holds a path rather than a loader
// option. So this sits in between and execs the loader as
// `libldmusl.so --preload=<shim> <cli> <args...>`.
//
// On the loader's command line and never in LD_PRELOAD. The shim interposes
// `sigaction` against musl's structure layout, which is not Bionic's, and an
// environment variable is inherited by every process the CLI starts: bash for
// its Bash tool, node and npx for MCP servers, git. Bionic's linker honours
// LD_PRELOAD too, so each of those loaded the shim and wrote a 152-byte musl
// structure into a 32-byte Bionic one. Measured on an API 33 emulator: a Bionic
// bash and node started under a launcher-started musl process both aborted with
// "stack corruption detected", and started the same way under `--preload=`
// both ran, with the shim still mapped in the musl process. The option loads it
// into this one process and nothing below it.
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

    // One argument, `--preload=<list>`, so the path is never read as the program
    // to run, and the list names the shim alone. The option replaces LD_PRELOAD
    // in musl's loader rather than adding to it, and an LD_PRELOAD found in the
    // environment is deliberately not carried into the list. It used to be, on
    // the premise that a caller-set preload is something musl can load. The one
    // value a terminal exports is a Bionic library, the exec interceptor, and
    // musl's loader cannot relocate a Bionic object: forwarded into the list, it
    // printed eleven "Error relocating ...: symbol not found" lines and exited
    // 127 before main(), so the CLI could not start from any terminal that had
    // the interceptor. Given `--preload=<shim>` alone, the loader ignores the
    // environment variable and the CLI runs while LD_PRELOAD still names the
    // Bionic library. Measured on API 33 and 36 emulators, 2026-09-22/23, with
    // CLI 2.1.216. So the forwarding gained nothing and broke the CLI whenever a
    // Bionic preload was exported. The variable itself is left in the
    // environment as it arrived, so the Bionic processes the CLI starts (bash,
    // node, git) still load it; an unsetenv() here would take that away from
    // them.
    char preload[PATH_MAX + sizeof("--preload=")];
    int len = snprintf(preload, sizeof(preload), "--preload=%s", shim);
    if (len < 0 || len >= (int)sizeof(preload)) {
        fprintf(stderr, "claude-launch: preload list too long\n");
        return 1;
    }

    // loader, the preload option, then everything this was called with from
    // argv[1] on, which is the CLI path followed by its own arguments.
    char **next = calloc((size_t)argc + 2, sizeof(char *));
    if (!next) {
        fprintf(stderr, "claude-launch: out of memory\n");
        return 1;
    }
    next[0] = loader;
    next[1] = preload;
    for (int i = 1; i < argc; i++) next[i + 1] = argv[i];
    next[argc + 1] = NULL;

    execv(loader, next);
    fprintf(stderr, "claude-launch: cannot run %s: %s\n", loader, strerror(errno));
    return 1;
}
