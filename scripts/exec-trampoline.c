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
// The table carries each toolchain's environment as well as its commands, and
// that is not a convenience. RUBYLIB, GEM_PATH and JAVA_HOME reach a non-bash
// caller only through the server process, whose environment is composed once,
// at start; bash re-reads `toolchain-env.sh` in every shell and never notices
// the difference. Measured on an API 37 emulator with Ruby installed from the
// Toolchains screen while the server ran: a `"type": "process"` task found
// `ruby` through this file and then got RUBYLIB=nil, a load path pointing into
// Termux's prefix, and `LoadError` on `require "json"`, while the same probe
// through bash printed the variables and loaded the library. So an environment
// row is applied with setenv before the exec, and only for a variable the
// caller does not already have: a parent that set GEM_HOME on purpose, Bundler
// for a vendored bundle or a person in a terminal, keeps its value, and what
// the table supplies is what the server was started too early to know.
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
// its interpreter is ever consulted, which is why a row can carry arguments after
// the target: the script for an interpreter, the VM options for a JDK launcher
// (a JVM of 20 or later warns on every start under the app's seccomp filter
// unless -XX:UseSVE=0 is given, and takes its home and temporary directories
// from Termux's prefix unless told otherwise). They go between the target and
// the caller's own arguments, in the order the row lists them.

#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <unistd.h>

// The loader the app is allowed to execute. Hardcoded rather than read from the
// table, so a table an attacker could write still cannot name a different
// program to run; and it is the same constant `ToolchainManager.systemLoader`
// writes into the shell functions, so the two mechanisms produce identical
// process trees.
static const char *kLoader = "/system/bin/linker64";

// How many arguments a row may carry after its target. Eight covers the longest
// row written today (a JDK launcher's three VM options) with room; a longer one
// is refused whole rather than cut, since a row run without some of its
// arguments is a command the table never named.
#define MAX_ROW_ARGS 8

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

    // The matched row, kept until the whole table has been read. An environment
    // row may sit anywhere in the file, so the exec waits for the last line
    // rather than firing on the first match, and a writer that orders the file
    // differently from this one costs nothing. Copies, because the buffer below
    // is reused by every read.
    char *target = NULL;
    char *args[MAX_ROW_ARGS];
    int nargs = 0;

    // One whole record per read, however long. A fixed buffer had to choose
    // between cutting a long record, which hands the loader a path the table
    // never named and reads the rest as a record of its own, and dropping it,
    // which loses a row the app wrote without a word; getline does neither.
    char *line = NULL;
    size_t capacity = 0;
    ssize_t got;
    while ((got = getline(&line, &capacity, f)) != -1) {
        size_t len = (size_t)got;
        // A NUL inside the record, wherever it sits and whether or not a newline
        // ends it: strlen stops there, so every string function below would see
        // the record cut at that byte, and an absolute path cut short is still
        // absolute. It would reach the loader as a real request to run
        // something the table never named, so the record is skipped instead.
        if (strlen(line) != len) {
            fprintf(stderr, "vscodroid: %s: skipping a damaged toolchain entry\n", name);
            continue;
        }
        if (len > 0 && line[len - 1] == '\n') line[--len] = '\0';

        char *tab = strchr(line, '\t');
        if (!tab) continue;
        *tab = '\0';

        // An environment row: an empty command field, a variable name, its
        // value, which keeps every tab after the second. The empty field is
        // the whole of the shape: a basename is never empty, so a trampoline
        // built before these rows existed reads one as a command nobody asked
        // for and walks past it, and this one cannot mistake a row for the
        // command `env`, which a toolchain is free to ship. Not overwriting is
        // the other half of the design, stated at the top of this file.
        if (line[0] == '\0') {
            char *key = tab + 1;
            char *value = strchr(key, '\t');
            if (!value || value == key) continue;
            *value++ = '\0';
            setenv(key, value, 0);
            continue;
        }

        if (target || strcmp(line, name) != 0) continue;

        // The target ends at the next tab and each argument at the one after it.
        char *found = tab + 1;
        char *found_args[MAX_ROW_ARGS];
        int found_nargs = 0;
        for (char *rest = strchr(found, '\t'); rest; rest = strchr(rest, '\t')) {
            *rest++ = '\0';
            if (found_nargs == MAX_ROW_ARGS) {
                fprintf(stderr, "vscodroid: %s: toolchain entry has too many arguments\n", name);
                fclose(f);
                return EXIT_NOT_FOUND;
            }
            found_args[found_nargs++] = rest;
        }

        // Absolute only. The trampoline has no working directory it can trust:
        // it inherits whatever the caller had, so a relative target would name a
        // different file depending on where the command was run from.
        if (found[0] != '/') {
            fprintf(stderr, "vscodroid: %s: toolchain entry is not an absolute path\n", name);
            fclose(f);
            return EXIT_NOT_FOUND;
        }

        target = strdup(found);
        for (nargs = 0; target && nargs < found_nargs; nargs++) {
            args[nargs] = strdup(found_args[nargs]);
            if (!args[nargs]) target = NULL;
        }
        if (!target) {
            fprintf(stderr, "vscodroid: %s: out of memory\n", name);
            fclose(f);
            return EXIT_CANNOT_EXEC;
        }
    }

    // Closed before the exec, not left to it. Nothing here sets FD_CLOEXEC,
    // so an open descriptor on the table would be inherited by every
    // toolchain command this starts. The matched row was copied out of the
    // line buffer, so the buffer can go with it.
    free(line);
    fclose(f);

    if (!target) {
        fprintf(stderr, "vscodroid: %s: no toolchain entry; reinstall the toolchain\n", name);
        return EXIT_NOT_FOUND;
    }

    // loader, target, the row's arguments, argv[1..], NULL.
    char **next = calloc((size_t)argc + MAX_ROW_ARGS + 3, sizeof(char *));
    if (!next) {
        fprintf(stderr, "vscodroid: %s: out of memory\n", name);
        return EXIT_CANNOT_EXEC;
    }
    int n = 0;
    next[n++] = (char *)kLoader;
    next[n++] = target;
    for (int i = 0; i < nargs; i++) next[n++] = args[i];
    for (int i = 1; i < argc; i++) next[n++] = argv[i];
    next[n] = NULL;

    execv(kLoader, next);
    fprintf(stderr, "vscodroid: %s: cannot run %s: %s\n", name, kLoader, strerror(errno));
    return EXIT_CANNOT_EXEC;
}
