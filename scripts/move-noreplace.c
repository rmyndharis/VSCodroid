// Moves one entry of a directory to another name in the same directory, unless
// that name is taken.
//
//   libmove-noreplace.so <directory> <device> <inode> <from> <to>
//
// Exit status:
//   0   moved
//   73  <to> is taken, and nothing was moved
//   2   anything else, with the reason on stderr; nothing was moved
//
// Never 1: that is what the loader exits with when it cannot start a program, and
// the caller answers "taken" by trying the next name.
//
// Why this exists. The device-folder engine (SafSyncEngine.kt) keeps a copy of a
// file under a free name beside it, and puts a copy it took for deletion back
// under the copy's own name. rename() replaces whatever stands at the new name,
// so an entry made there between the engine's look at the name and its move was
// lost. renameat2() with RENAME_NOREPLACE refuses instead, in one step. Nothing a
// Kotlin program can call offers it: android.system.Os has rename and link by
// path only, java.nio moves with renameat, and a hard link, which also fails on
// a taken name, is denied to an app by SELinux. The system call itself is
// allowed to an app from Android 10 on, measured in the app's own terminal on
// API 29 and API 33, so one small program run by the app is the way to it.
//
// The call is made by number: Bionic gained the renameat2() wrapper in API 30
// and this is built for API 29.
//
// <device> and <inode>, in decimal, name the directory the caller holds open.
// The directory is opened here by path, and a path can be swapped between the
// caller's look and this open, so the move is made only in a directory with that
// identity. <from> and <to> are single names, never paths.

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef RENAME_NOREPLACE
#define RENAME_NOREPLACE 1
#endif

static int is_one_name(const char *name) {
    return name[0] != '\0' && strchr(name, '/') == NULL && strcmp(name, ".") != 0 && strcmp(name, "..") != 0;
}

static int read_number(const char *text, unsigned long long *value) {
    char *end = NULL;
    errno = 0;
    *value = strtoull(text, &end, 10);
    return errno == 0 && end != text && *end == '\0' && text[0] >= '0' && text[0] <= '9';
}

int main(int argc, char **argv) {
    unsigned long long device = 0, inode = 0;
    if (argc != 6 || !read_number(argv[2], &device) || !read_number(argv[3], &inode) ||
        !is_one_name(argv[4]) || !is_one_name(argv[5])) {
        fprintf(stderr, "usage: libmove-noreplace.so <directory> <device> <inode> <from> <to>\n");
        return 2;
    }
    int directory = open(argv[1], O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (directory < 0) {
        fprintf(stderr, "move-noreplace: cannot open the directory: %s\n", strerror(errno));
        return 2;
    }
    struct stat held;
    if (fstat(directory, &held) != 0 || (unsigned long long)held.st_dev != device ||
        (unsigned long long)held.st_ino != inode) {
        fprintf(stderr, "move-noreplace: the path does not lead to the directory that was named\n");
        return 2;
    }
    if (syscall(__NR_renameat2, directory, argv[4], directory, argv[5], RENAME_NOREPLACE) == 0) return 0;
    if (errno == EEXIST) return 73;
    fprintf(stderr, "move-noreplace: %s\n", strerror(errno));
    return 2;
}
