// The one file on PATH that can start a downloaded toolchain command.
//
// SELinux denies `execute_no_trans` on `app_data_file`, so the kernel refuses to
// execve anything whose inode is under filesDir, which is every byte of a
// downloaded toolchain. It refuses identically through a symlink, because the
// check is on the resolved inode's label rather than on the path, and chmod
// cannot reach it. Handing the same file to a loader that lives somewhere the
// app MAY execute from does run it: `/system/bin/linker64 <payload>` mmaps the
// payload, which the `execute` permission the app does hold covers.
//
// That indirection already existed here, but only as a bash function written
// into `toolchain-env.sh`. A shell function is reachable from one shell family,
// and only once it has been told to read that file. Nothing reached it from a
// direct execve with no shell at all (`spawn("ruby", args)` from an extension or
// a language server), from `/system/bin/sh` (mksh, which has never heard of
// BASH_ENV and which `patch-default-shell.py` deliberately made make's recipe
// shell and Node's `exec()` shell), from bash invoked as `sh`, or from a
// `"type": "process"` task. Every one of those resolves a bare name against PATH
// and execve's what it finds, so what was missing was a real, executable file on
// PATH that performs the loader indirection. This is that file.
//
// It is installed once, in nativeLibraryDir, and reached through one symlink per
// command name in `usr/libexec/tcbin`. Two properties of that arrangement decide
// the whole design, both measured on emulator-5554 (API 33, aarch64) with an
// NDK-built probe that printed its own argv and readlink("/proc/self/exe"):
//
//   * Executing a symlink found on PATH gives argv[0] as the bare name the
//     caller asked for ("ruby"), while /proc/self/exe resolves all the way
//     through to the single shared binary. So the trampoline can dispatch on
//     basename(argv[0]) and can NEVER learn which symlink invoked it. Dispatch
//     is therefore on argv[0], and the table location comes from the
//     environment.
//   * Under `/system/bin/linker64 <payload> a 'b c'` the payload's own argv[0]
//     is its path, argument quoting survives, and its exit status comes back
//     intact (42 in, 42 out). Its /proc/self/exe, however, reads
//     /apex/com.android.runtime/bin/linker64, because there is no execve of the
//     payload at all. A program that self-locates that way is misled. That is a
//     property of the only mechanism available, and it is already true of the
//     shell functions, so it is a ceiling rather than a regression.
//
// Deliberately absent, each for a reason:
//
//   * execvp() is never called. The target is an absolute path taken from a
//     table only the app writes. Searching PATH here would let a poisoned PATH
//     redirect a toolchain command, a privilege the shell functions never had.
//   * No shell is invoked, so nothing here re-parses arguments the caller
//     already quoted.
//   * /proc/self/exe is never read, for the reason above.
//   * Nothing is printed on the success path. Every `$(...)` in this app runs a
//     command whose stdout is captured, so a chatty trampoline would land in the
//     output of whatever ran the command.
//
// What it does not fix, and cannot: an absolute-path invocation such as
// $JAVA_HOME/bin/java is not a PATH lookup and never reaches here; a toolchain
// forking its own helper by absolute path (the JDK's lib/jspawnhelper) is the
// same; and a shebang script under filesDir is refused on its own inode before
// its interpreter is ever consulted, which is why the table carries a two-field
// form naming the interpreter explicitly.

#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

// The loader the app is allowed to execute. Hardcoded rather than read from the
// table, so a table an attacker could write still cannot name a different
// program to run; and it is the same constant `ToolchainManager.systemLoader`
// writes into the shell functions, so the two mechanisms produce identical
// process trees.
static const char *kLoader = "/system/bin/linker64";

// Two absolute paths (a target and an optional script argument), their tab, a
// command name, and slack. A line longer than this is skipped rather than
// truncated and used: a truncated path is a command that fails naming a path the
// user never chose.
#define MAX_LINE (2 * PATH_MAX + 64)

// The exit status a shell reports for "command not found". Used for every way
// this cannot identify a program to run, so the caller sees the same status it
// would have seen had the name never been on PATH at all.
#define EXIT_NOT_FOUND 127
// "found but could not be executed", which is what a failed execv is.
#define EXIT_CANNOT_EXEC 126

static const char *base_name(const char *path) {
    const char *slash = strrchr(path, '/');
    return slash ? slash + 1 : path;
}

// basename() from <libgen.h> is allowed to modify its argument, and argv[0] is
// not ours to modify, so the lookup above is written out instead of borrowing
// one. It also keeps this file free of a header whose POSIX and GNU versions
// disagree about exactly that.

int main(int argc, char **argv) {
    const char *name = base_name(argc > 0 && argv[0] ? argv[0] : "vscodroid");

    // The app exports VSCODROID_EXEC_TABLE into the server process, and every
    // terminal, extension host and task descends from that one process. PREFIX
    // is the fallback because it is exported by the same map and by every
    // bundled shell, so a child that kept one and lost the other still works.
    char derived[PATH_MAX];
    const char *table = getenv("VSCODROID_EXEC_TABLE");
    if (!table || !*table) {
        const char *prefix = getenv("PREFIX");
        if (!prefix || !*prefix) {
            // Loud, not silent. A caller that scrubbed the environment (env -i)
            // gets a sentence naming the cause rather than a command that
            // mysteriously does nothing.
            fprintf(stderr, "vscodroid: %s: no toolchain table in the environment\n", name);
            return EXIT_NOT_FOUND;
        }
        if (snprintf(derived, sizeof(derived), "%s/../home/.vscodroid/toolchain-exec.tsv",
                     prefix) >= (int)sizeof(derived)) {
            fprintf(stderr, "vscodroid: %s: toolchain table path is too long\n", name);
            return EXIT_NOT_FOUND;
        }
        table = derived;
    }

    FILE *f = fopen(table, "r");
    if (!f) {
        fprintf(stderr, "vscodroid: %s: cannot read %s: %s\n", name, table, strerror(errno));
        return EXIT_NOT_FOUND;
    }

    char line[MAX_LINE];
    while (fgets(line, sizeof(line), f)) {
        size_t len = strlen(line);
        if (len == 0) continue;
        if (line[len - 1] != '\n' && !feof(f)) {
            // The line did not fit. Drain the rest of it so the next fgets
            // starts on a real line rather than on this one's tail, which would
            // otherwise be read as a record of its own.
            int c;
            while ((c = fgetc(f)) != EOF && c != '\n') { }
            continue;
        }
        if (len > 0 && line[len - 1] == '\n') line[--len] = '\0';

        char *tab = strchr(line, '\t');
        if (!tab) continue;
        *tab = '\0';
        if (strcmp(line, name) != 0) continue;

        char *target = tab + 1;
        char *arg = strchr(target, '\t');
        if (arg) *arg++ = '\0';

        // Absolute only. The trampoline has no working directory it can trust:
        // it inherits whatever the caller had, so a relative target would name a
        // different file depending on where the command was run from.
        if (target[0] != '/') {
            fprintf(stderr, "vscodroid: %s: toolchain entry is not an absolute path\n", name);
            fclose(f);
            return EXIT_NOT_FOUND;
        }

        // loader, target, optional script argument, argv[1..], NULL.
        char **next = calloc((size_t)argc + 4, sizeof(char *));
        if (!next) {
            fprintf(stderr, "vscodroid: %s: out of memory\n", name);
            fclose(f);
            return EXIT_CANNOT_EXEC;
        }
        int n = 0;
        next[n++] = (char *)kLoader;
        next[n++] = target;
        if (arg) next[n++] = arg;
        for (int i = 1; i < argc; i++) next[n++] = argv[i];
        next[n] = NULL;

        // Closed before the exec, not left to it. Nothing here sets FD_CLOEXEC,
        // so an open descriptor on the table would be inherited by every
        // toolchain command this starts.
        fclose(f);
        execv(kLoader, next);
        fprintf(stderr, "vscodroid: %s: cannot run %s: %s\n", name, kLoader, strerror(errno));
        return EXIT_CANNOT_EXEC;
    }

    fclose(f);
    fprintf(stderr, "vscodroid: %s: no toolchain entry; reinstall the toolchain\n", name);
    return EXIT_NOT_FOUND;
}
