# Third-Party Software Attribution

VSCodroid includes and/or distributes the following third-party software. We are grateful to the authors and communities behind these projects.

## Core Components

| Software | Version | License | URL |
|----------|---------|---------|-----|
| Code - OSS (VS Code) | 1.96.4 | MIT | https://github.com/microsoft/vscode |
| Node.js | 20.18.1 | MIT | https://github.com/nodejs/node |
| npm | 10.8.2 | Artistic License 2.0 | https://github.com/npm/cli |
| Python | 3.12.12 | PSF License | https://www.python.org |
| Git | 2.53.0 | GPL v2 | https://git-scm.com |
| Bash | 5.3.9 | GPL v3 | https://www.gnu.org/software/bash |
| ripgrep | 14.x | MIT / Unlicense (dual) | https://github.com/BurntSushi/ripgrep |
| tmux | 3.6a | ISC | https://github.com/tmux/tmux |
| GNU Make | 4.4.1 | GPL v3 | https://www.gnu.org/software/make |
| OpenSSH | latest | BSD | https://www.openssh.com |
| node-pty | 1.1.0-beta22 | MIT | https://github.com/nickcernis/nickcernis-node-pty |

## Shared Libraries (from Termux)

| Library | License | Purpose |
|---------|---------|---------|
| readline | GPL v3 | Line editing for Bash |
| ncurses | MIT | Terminal interface for Bash/tmux |
| libevent | BSD 3-Clause | Event loop for tmux |
| libedit | BSD 3-Clause | Line editing for OpenSSH |
| pcre2 | BSD 3-Clause | Regular expressions for Git |
| libcurl | MIT/X derivative | HTTP client for Git |
| openssl | Apache 2.0 | TLS for Git, OpenSSH, Python, npm |
| zlib | zlib License | Compression for Git, Python |
| libffi | MIT | Foreign function interface for Python |
| libbz2 | BSD-style | Compression for Python |
| liblzma | Public Domain | Compression for Python |
| libsqlite3 | Public Domain | Database for Python |
| libgdbm | GPL v3 | Database for Python |
| libcrypt | LGPL v2.1 | Cryptography for Python |
| libandroid-posix-semaphore | Apache 2.0 | POSIX semaphores for Python |
| libandroid-glob | BSD | Glob support for Make |
| libgmp | LGPL v3 | Arbitrary precision math (Ruby toolchain) |
| libyaml | MIT | YAML parsing (Ruby toolchain) |
| libandroid-shmem | Apache 2.0 | Shared memory (Java toolchain) |
| libandroid-spawn | Apache 2.0 | Process spawning (Java toolchain) |
| libc++_shared | Apache 2.0 / MIT | C++ standard library (NDK) |

## On-Demand Toolchains

| Software | License | URL |
|----------|---------|-----|
| Go | BSD 3-Clause | https://go.dev |
| Ruby | BSD 2-Clause | https://www.ruby-lang.org |
| OpenJDK | GPL v2 + Classpath | https://openjdk.org |

## Extension Marketplace

| Service | License | URL |
|---------|---------|-----|
| Open VSX Registry | EPL v2 | https://open-vsx.org |

## Bundled VS Code Extensions

| Extension | License |
|-----------|---------|
| One Dark Pro | MIT |
| ESLint | MIT |
| Prettier | MIT |
| Tailwind CSS IntelliSense | MIT |
| GitLens | EUL (free tier) |
| Python (ms-python) | MIT |

## Termux Project

Many bundled binaries and shared libraries are compiled by the [Termux](https://github.com/termux/termux-packages) project. Termux packages are built from upstream sources and maintain their original licenses. VSCodroid uses Termux's ARM64 builds as a convenience; we do not modify the Termux packaging system itself.

## Android NDK

The `libc++_shared.so` C++ standard library is distributed under the Apache 2.0 / MIT dual license as part of the [Android NDK](https://developer.android.com/ndk).

---

This file is provided for attribution purposes. For exact license texts, refer to the `LICENSE` files in each software's source repository. If you believe any attribution is missing or incorrect, please open an issue at https://github.com/rmyndharis/VSCodroid/issues.
