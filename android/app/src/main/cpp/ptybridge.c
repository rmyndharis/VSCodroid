/**
 * VSCodroid PTY Bridge
 *
 * Bridges pipe stdio from Node.js to a real PTY for child processes.
 * Android Bionic supports forkpty() since API 23.
 *
 * Usage: ptybridge [-c cols] [-r rows] <command> [args...]
 *
 * Node.js (pipeTerminal.js) spawns this with pipe stdio:
 *   stdin pipe  --> ptybridge --> PTY master --> child shell
 *   stdout pipe <-- ptybridge <-- PTY master <-- child output
 */

#include <errno.h>
#include <fcntl.h>
#include <pty.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/select.h>
#include <sys/wait.h>
#include <unistd.h>

static volatile pid_t g_child_pid = 0;
static volatile int g_master_fd = -1;
static char g_tmpdir[256] = "/tmp";  /* Cached at startup, safe for signal handler */
static char g_size_path[320];        /* Pre-computed in main(), used by signal handler */

static void handle_sigwinch(int sig) {
    (void)sig;
    if (g_master_fd < 0 || g_child_pid <= 0) return;

    /* All functions here are async-signal-safe per POSIX:
       open, read, close, unlink, ioctl — no stdio, no malloc. */
    int fd = open(g_size_path, O_RDONLY);
    if (fd < 0) return;

    char buf[32];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    unlink(g_size_path);
    if (n <= 0) return;
    buf[n] = '\0';

    /* Manual integer parse (strtol/sscanf are not async-signal-safe) */
    const char *p = buf;
    while (*p == ' ' || *p == '\t') p++;
    int cols = 0;
    while (*p >= '0' && *p <= '9') cols = cols * 10 + (*p++ - '0');
    while (*p == ' ' || *p == '\t') p++;
    int rows = 0;
    while (*p >= '0' && *p <= '9') rows = rows * 10 + (*p++ - '0');

    if (cols > 0 && rows > 0) {
        struct winsize ws = { .ws_row = rows, .ws_col = cols };
        ioctl(g_master_fd, TIOCSWINSZ, &ws);
    }
}

static void handle_forward(int sig) {
    if (g_child_pid > 0)
        kill(-g_child_pid, sig); /* Send to process group */
}

static void set_nonblock(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags >= 0) fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

static void write_all(int fd, const char *buf, ssize_t len) {
    ssize_t written = 0;
    while (written < len) {
        ssize_t n = write(fd, buf + written, len - written);
        if (n < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK) break;
            break;
        }
        written += n;
    }
}

/* Extract basename from .so path: "libbash.so" -> "bash" */
static const char *derive_argv0(const char *path) {
    const char *base = strrchr(path, '/');
    base = base ? base + 1 : path;
    if (strncmp(base, "lib", 3) == 0) {
        static char buf[64];
        const char *start = base + 3;
        const char *dot = strstr(start, ".so");
        if (dot && (size_t)(dot - start) < sizeof(buf)) {
            memcpy(buf, start, dot - start);
            buf[dot - start] = '\0';
            return buf;
        }
    }
    return path;
}

int main(int argc, char *argv[]) {
    int cols = 80, rows = 24;
    int opt;

    while ((opt = getopt(argc, argv, "c:r:")) != -1) {
        switch (opt) {
            case 'c': cols = atoi(optarg); break;
            case 'r': rows = atoi(optarg); break;
            default:
                fprintf(stderr, "Usage: ptybridge [-c cols] [-r rows] cmd [args...]\n");
                return 1;
        }
    }

    if (optind >= argc) {
        fprintf(stderr, "ptybridge: no command specified\n");
        return 1;
    }

    /* Cache TMPDIR before fork — signal handlers can't call getenv() */
    const char *tmpdir = getenv("TMPDIR");
    if (tmpdir && strlen(tmpdir) < sizeof(g_tmpdir))
        strncpy(g_tmpdir, tmpdir, sizeof(g_tmpdir) - 1);

    /* Pre-compute size-file path for the signal handler (async-signal-safe) */
    snprintf(g_size_path, sizeof(g_size_path), "%s/.pty-size-%d", g_tmpdir, getpid());

    char *cmd = argv[optind];
    char **cmd_argv = &argv[optind];

    /* Derive argv[0] from .so filename */
    const char *argv0 = derive_argv0(cmd);
    cmd_argv[0] = (char *)argv0;

    struct winsize ws = { .ws_row = rows, .ws_col = cols };
    int master_fd;
    pid_t pid = forkpty(&master_fd, NULL, NULL, &ws);

    if (pid < 0) {
        perror("ptybridge: forkpty");
        return 1;
    }

    if (pid == 0) {
        /* Child: exec the command */
        setenv("TERM", "xterm-256color", 1);
        execvp(cmd, cmd_argv);
        perror("ptybridge: execvp");
        _exit(127);
    }

    /* Parent: relay between stdin/stdout pipes and PTY master */
    g_child_pid = pid;
    g_master_fd = master_fd;

    /* Signal handlers */
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));

    sa.sa_handler = handle_sigwinch;
    sa.sa_flags = SA_RESTART;
    sigaction(SIGWINCH, &sa, NULL);

    sa.sa_handler = handle_forward;
    sigaction(SIGHUP, &sa, NULL);
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);

    set_nonblock(STDIN_FILENO);
    set_nonblock(master_fd);

    char buf[4096];
    int child_exited = 0;
    int exit_status = 0;

    while (1) {
        fd_set rfds;
        FD_ZERO(&rfds);

        if (!child_exited)
            FD_SET(STDIN_FILENO, &rfds);
        FD_SET(master_fd, &rfds);

        int nfds = (master_fd > STDIN_FILENO ? master_fd : STDIN_FILENO) + 1;

        /* Use a timeout so we can check for child exit */
        struct timeval tv = { .tv_sec = 0, .tv_usec = 100000 }; /* 100ms */
        int ret = select(nfds, &rfds, NULL, NULL, &tv);

        if (ret < 0) {
            if (errno == EINTR) continue;
            break;
        }

        /* stdin -> PTY master */
        if (FD_ISSET(STDIN_FILENO, &rfds)) {
            ssize_t n = read(STDIN_FILENO, buf, sizeof(buf));
            if (n > 0) {
                write_all(master_fd, buf, n);
            } else if (n == 0) {
                /* Node.js closed stdin — don't exit yet, wait for child */
            }
        }

        /* PTY master -> stdout */
        if (FD_ISSET(master_fd, &rfds)) {
            ssize_t n = read(master_fd, buf, sizeof(buf));
            if (n > 0) {
                write_all(STDOUT_FILENO, buf, n);
            } else if (n <= 0 && errno != EAGAIN && errno != EINTR) {
                /* PTY closed — child likely exited */
                break;
            }
        }

        /* Check for child exit */
        if (!child_exited) {
            int status;
            pid_t w = waitpid(pid, &status, WNOHANG);
            if (w > 0) {
                child_exited = 1;
                if (WIFEXITED(status))
                    exit_status = WEXITSTATUS(status);
                else if (WIFSIGNALED(status))
                    exit_status = 128 + WTERMSIG(status);
                /* Don't break yet — drain remaining PTY output */
            }
        } else {
            /* Child exited, drain remaining output then exit */
            ssize_t n = read(master_fd, buf, sizeof(buf));
            if (n > 0) {
                write_all(STDOUT_FILENO, buf, n);
            } else if (errno != EAGAIN && errno != EINTR) {
                break; /* EOF or real error — stop draining */
            }
            /* EAGAIN: continue select loop to drain remaining data */
        }
    }

    close(master_fd);

    /* Clean up size file */
    unlink(g_size_path);

    return exit_status;
}
