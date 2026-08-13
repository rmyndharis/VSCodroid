# Third-Party Software Attribution

VSCodroid includes and/or distributes the following third-party software. We are grateful to the authors and communities behind these projects.

Versions are deliberately not listed unless pinned in this repository: most components come from the Termux or Alpine package index at build time, so the version a given release shipped is recorded by that release's build, not by this file.

## Core Components

| Software | License | Source |
|----------|---------|--------|
| Code - OSS (VS Code Server + Web) | MIT — `LICENSE.txt` and `ThirdPartyNotices.txt` ship inside the server tree | Built from https://github.com/microsoft/vscode at the tag pinned in `VSCODE_VERSION`, with the patches in `patches/` and the branding in `branding/` applied before the build |
| GitHub Copilot Chat extension | See the license files inside `extensions/copilot/` in the server tree; its `@github/copilot` SDK dependency carries GitHub, Inc.'s own `LICENSE.md` | Produced by the same Code - OSS build |
| Node.js | MIT | Termux build of `nodejs-lts`, https://github.com/nodejs/node |
| npm | Artistic License 2.0 | https://github.com/npm/cli |
| Python | PSF License | Termux build, https://www.python.org |
| Git | GPL v2 | Termux build, https://git-scm.com |
| Bash | GPL v3 | Termux build, https://www.gnu.org/software/bash |
| ripgrep | MIT / Unlicense (dual) | Bundled by the Code - OSS build (`@vscode/ripgrep-universal`), https://github.com/BurntSushi/ripgrep |
| tmux | ISC | Termux build, https://github.com/tmux/tmux |
| GNU Make | GPL v3 | Termux build, https://www.gnu.org/software/make |
| OpenSSH | BSD | Termux build, https://www.openssh.com |
| node-pty | MIT | https://github.com/microsoft/node-pty — native addon rebuilt for Android/Bionic |
| @parcel/watcher | MIT | https://github.com/parcel-bundler/watcher — native addon rebuilt for Android/Bionic |
| musl (dynamic loader) | MIT | Alpine Linux package, https://musl.libc.org — bundled so the Claude Code CLI can run; the CLI itself is installed by the user and is not redistributed here |

## Shared Libraries (from Termux)

| Library | License | Purpose |
|---------|---------|---------|
| ICU (libicu*) | Unicode License | Internationalization for Node.js |
| c-ares | MIT | DNS resolution for Node.js |
| readline | GPL v3 | Line editing for Bash |
| ncurses | MIT | Terminal interface for Bash/tmux |
| libevent | BSD 3-Clause | Event loop for tmux |
| libedit | BSD 3-Clause | Line editing for OpenSSH |
| pcre2 | BSD 3-Clause | Regular expressions for Git |
| libcurl | MIT/X derivative | HTTP client for Git |
| openssl | Apache 2.0 | TLS for Git, OpenSSH, Python, npm |
| zlib | zlib License | Compression for Git, Python |
| libffi | MIT | Foreign function interface (Python, Ruby toolchain) |
| libbz2 | BSD-style | Compression for Python |
| liblzma | Public Domain | Compression for Python |
| libsqlite3 | Public Domain | Database for Python |
| libgdbm | GPL v3 | Database for Python |
| libcrypt | LGPL v2.1 | Cryptography for Python |
| libandroid-posix-semaphore | Apache 2.0 | POSIX semaphores for Python |
| libandroid-glob | BSD | Glob support for Make |
| libgmp | LGPL v3 | Arbitrary precision math (Ruby toolchain) |
| libyaml | MIT | YAML parsing (Ruby toolchain) |
| libandroid-shmem | BSD 3-Clause | Shared memory (Java toolchain) |
| libandroid-spawn | BSD 2-Clause | Process spawning (Java toolchain), built from source |
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

Downloaded from Open VSX at build time (`scripts/download-extensions.sh`):

| Extension | License |
|-----------|---------|
| Material Icon Theme | MIT |
| Prettier | MIT |
| Python (ms-python) | MIT |
| ESLint | MIT |
| Tailwind CSS IntelliSense | MIT |

VSCodroid's own bundled extensions (`vscodroid.*`) are covered by this repository's MIT `LICENSE`.

## Termux Project

Many bundled binaries and shared libraries are compiled by the [Termux](https://github.com/termux/termux-packages) project. Termux packages are built from upstream sources and maintain their original licenses. VSCodroid uses Termux's ARM64 builds as a convenience; we do not modify the Termux packaging system itself.

## Android NDK

The `libc++_shared.so` C++ standard library is distributed under the Apache 2.0 / MIT dual license as part of the [Android NDK](https://developer.android.com/ndk).

---

This file is provided for attribution purposes. For exact license texts, refer to the `LICENSE` files in each software's source repository. If you believe any attribution is missing or incorrect, please open an issue at https://github.com/rmyndharis/VSCodroid/issues.
