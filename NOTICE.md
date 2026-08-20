# Third-Party Software Attribution

VSCodroid includes and/or distributes the following third-party software. We are grateful to the authors and communities behind these projects.

Versions are deliberately not listed unless pinned in this repository: most components come from the Termux or Alpine package index at build time, so the version a given release shipped is recorded by that release's build, not by this file.

## Core Components

| Software | License | Source |
|----------|---------|--------|
| Code - OSS (VS Code Server + Web) | MIT — `LICENSE.txt` and `ThirdPartyNotices.txt` ship inside the server tree | Built from https://github.com/microsoft/vscode at the commit pinned in `VSCODE_COMMIT`, with the patches in `patches/` and the branding in `branding/` applied before the build |
| GitHub Copilot Chat extension | See the license files inside `extensions/copilot/` in the server tree; its `@github/copilot` SDK dependency carries GitHub, Inc.'s own `LICENSE.md` | Produced by the same Code - OSS build |
| Node.js | MIT | Termux build of `nodejs-lts`, https://github.com/nodejs/node |
| npm | Artistic License 2.0 | https://github.com/npm/cli |
| Python | PSF License | Termux build, https://www.python.org |
| pip | MIT | Shipped in Python's `site-packages`; its own `LICENSE.txt` and `AUTHORS.txt` travel with it in `pip-*.dist-info/licenses/` |
| Git | GPL v2 | Termux build, https://git-scm.com |
| Bash | GPL v3 | Termux build, https://www.gnu.org/software/bash |
| ripgrep | MIT / Unlicense (dual) | Bundled by the Code - OSS build (`@vscode/ripgrep-universal`), https://github.com/BurntSushi/ripgrep |
| tmux | ISC | Termux build, https://github.com/tmux/tmux |
| GNU Make | GPL v3 | Termux build, https://www.gnu.org/software/make |
| OpenSSH | BSD | Termux build, https://www.openssh.com |
| node-pty | MIT | https://github.com/microsoft/node-pty — native addon rebuilt for Android/Bionic |
| @parcel/watcher | MIT | https://github.com/parcel-bundler/watcher — native addon rebuilt for Android/Bionic |
| @vscode/sqlite3 | BSD-3-Clause (SQLite itself is public domain) | https://github.com/microsoft/vscode-node-sqlite3, native addon rebuilt for Android/Bionic |
| @vscode/native-watchdog | MIT | https://github.com/microsoft/node-native-watchdog, bundled by the Code - OSS build |
| @vscode/deviceid | MIT | https://github.com/microsoft/vscode-deviceid, bundled by the Code - OSS build |
| @vscode/sandbox-runtime | Apache-2.0 | https://github.com/anthropic-experimental/sandbox-runtime, bundled by the Code - OSS build |
| kerberos (Node addon) | Apache-2.0 | https://github.com/mongodb-js/kerberos. The npm addon, not the MIT Kerberos 5 C libraries listed below |
| @microsoft/mxc-sdk | MIT | https://www.npmjs.com/package/@microsoft/mxc-sdk, bundled by the Code - OSS build |
| js-debug | MIT | https://github.com/microsoft/vscode-js-debug, the built-in JavaScript debugger produced by the Code - OSS build |
| vscode-js-profile-visualizer | MIT | https://github.com/microsoft/vscode-js-profile-visualizer, the built-in profile viewer produced by the Code - OSS build. Its tables are WebAssembly |
| @vscode/tree-sitter-wasm | MIT | https://github.com/microsoft/vscode-tree-sitter-wasm, bundled by the Code - OSS build. WebAssembly builds of tree-sitter (https://github.com/tree-sitter/tree-sitter) and its grammars |
| web-tree-sitter | MIT | https://github.com/tree-sitter/tree-sitter, the WebAssembly binding, bundled by the Copilot Chat extension |
| vscode-oniguruma | MIT | https://github.com/microsoft/vscode-oniguruma, bundled by the Code - OSS build. The regex engine it compiles to WebAssembly is Oniguruma (BSD-2-Clause, https://github.com/kkos/oniguruma) and its notice ships in the package |
| @github/blackbird-external-ingest-utils | MIT | https://www.npmjs.com/package/@github/blackbird-external-ingest-utils, a WebAssembly dependency of the Copilot Chat extension |
| PSReadLine | BSD-2-Clause | https://github.com/PowerShell/PSReadLine. Prebuilt .NET assemblies carried by the terminal's PowerShell shell integration; they have no use on Android and are listed because they are redistributed |
| distlib | PSF-2.0 | https://github.com/pypa/distlib, vendored inside the bundled pip. Ships six prebuilt Windows launchers, redistributed unused |
| @github/copilot (GitHub Copilot CLI) | GitHub Copilot CLI License, proprietary | Redistributed under GitHub, Inc.'s own terms as a dependency of the Copilot extension. The full text ships in the server tree; the reasoning is in `docs/LEGAL_NOTICES.md` |
| musl (dynamic loader) | MIT | Alpine Linux package, https://musl.libc.org — bundled so the Claude Code CLI can run; the CLI itself is installed by the user and is not redistributed here |

## Bundled Native Components

Licences here are Termux's own `TERMUX_PKG_LICENSE` for the package each binary
comes from, and the "Linked by" column is read out of the shipped ELF headers
rather than written by hand. Two rows arrive from somewhere else and carry their
own package's licence: musl's loader is an Alpine package
(`scripts/download-musl-loader.sh`), and `libripgrep.so` is the `rg` the Code - OSS
server tree ships, copied into `jniLibs` by `scripts/fetch-vscode-oss.sh`.
`scripts/check-library-attribution.py` fails the build
when a shipped binary is attributed nowhere in this file, when it is missing from
`docs/LEGAL_NOTICES.md`, or when a copyleft component is missing from the source
offer that file carries. Both documents are read on every run, so a component has
to be named in each.

That the licences here are Termux's own is checked rather than asserted,
wherever Termux states one and upstream can be read.
`scripts/check-termux-licenses.py` reads `TERMUX_PKG_LICENSE` back from Termux
for each package, reports where the two disagree, and names the entries no Termux
package accounts for. It fails on one difference
only: a component recorded permissive where Termux declares copyleft, which is
what would leave a binary shipping with no offer of source.

This table covers the Termux libraries and executables at the top level of
`assets/usr/lib` and `jniLibs/arm64-v8a`. The binaries that sit deeper in the
asset tree are attributed under **Core Components** above, and the gate holds
them to the same rules: it walks the tree to its leaves, recognising a binary by
its magic number (ELF, WebAssembly, PE or Mach-O) or a `.node` extension. The
formats this device cannot execute are counted too. A Windows launcher or a .NET
assembly is redistributed inside the APK on its own terms whether or not
anything here can run it.

Excluded as first-party: `libglibc-shim.so` and its companion stubs, which carry
glibc's soname but are built from this repository's own source.

The copyleft rows below need more than a name. GPL-2.0, GPL-3.0 and LGPL-2.1 each
require a copy of the licence to reach whoever receives the binary, so the three
texts are in `licenses/` and ship in the app at **About > Licenses > License
Texts**, verbatim; `docs/LEGAL_NOTICES.md` records which text covers which
component.

| Component | License | Linked by |
|---|---|---|
| Bash | GPL-3.0 | bundled tool in its own right |
| bzip2 | BSD-4-Clause | Python |
| c-ares | MIT | Node.js |
| Expat | MIT | Git, Python |
| gdbm | GPL-3.0 | Python, gdbm |
| Git | GPL-2.0 | bundled tool in its own right |
| GNU Make | GPL-3.0 | bundled tool in its own right |
| ICU | ICU | ICU, Node.js |
| Kerberos 5 | MIT | Kerberos 5, OpenSSH |
| ldns | BSD-3-Clause | OpenSSH |
| libandroid-glob | BSD-3-Clause | Kerberos 5, tmux |
| libandroid-posix-semaphore | MIT | Python |
| libandroid-support | Apache-2.0, MIT | Bash, Kerberos 5, OpenSSH, Python, readline, tmux |
| libc++ | NCSA | ICU, Node.js |
| libcrypt | BSD-2-Clause | bundled tool in its own right |
| libcurl | MIT | Git |
| libedit | BSD-3-Clause | bundled tool in its own right |
| libevent | BSD-3-Clause | tmux |
| libffi | MIT | Python |
| libiconv | LGPL-2.1, GPL-3.0 | Bash, Git |
| libresolv-wrapper | BSD-3-Clause | Kerberos 5 |
| libssh2 | BSD-3-Clause | libcurl |
| musl libc | MIT | bundled tool in its own right |
| ncurses | MIT | Python, libedit, ncurses, readline, tmux |
| nghttp2 | MIT | libcurl |
| nghttp3 | MIT | libcurl |
| ngtcp2 | MIT | libcurl, ngtcp2 |
| Node.js | MIT | bundled tool in its own right |
| OpenSSH | BSD | bundled tool in its own right |
| OpenSSL | Apache-2.0 | Git, Node.js, OpenSSH, OpenSSL, Python, ldns, libcrypt, libcurl, libssh2, ngtcp2 |
| PCRE2 | BSD-3-Clause | Git |
| Python | PSF-2.0 | Python |
| readline | GPL-3.0 | Bash, Python |
| ripgrep | MIT | bundled tool in its own right |
| SQLite | Public Domain | Node.js, Python |
| tmux | ISC | bundled tool in its own right |
| xz / liblzma | LGPL-2.1, GPL-2.0, GPL-3.0 | Python |
| zlib | Zlib | Git, Node.js, OpenSSH, Python, SQLite, libcurl, libssh2 |
| Zstandard | GPL-2.0 | Python |

## Toolchain Libraries

Shipped inside the on-demand toolchain packs rather than the base app, so they
reach only devices where that toolchain was installed. Listed separately because
`scripts/check-library-attribution.py` reads the toolchain manifests for these,
and the base APK for the table above.

| Component | License | Shipped with |
|---|---|---|
| [GMP](https://gmplib.org) | LGPL-3.0 | Ruby |
| [libyaml](https://pyyaml.org/wiki/LibYAML) | MIT | Ruby |
| [libruby](https://www.ruby-lang.org) | BSD-2-Clause | Ruby |
| [libandroid-execinfo](https://github.com/termux/libandroid-execinfo) | BSD-2-Clause | Ruby |
| [libandroid-shmem](https://github.com/termux/libandroid-shmem) | BSD-3-Clause | Java |
| [libandroid-spawn](https://github.com/termux/libandroid-spawn) | BSD-2-Clause | Java |

GMP is copyleft; its source offer is in `docs/LEGAL_NOTICES.md` beside the rest.

## On-Demand Toolchains

| Software | License | URL |
|----------|---------|-----|
| Ruby | BSD 2-Clause | https://www.ruby-lang.org |
| OpenJDK | GPL v2 + Classpath | https://openjdk.org |

## Extension Marketplace

| Service | License | URL |
|---------|---------|-----|
| Open VSX Registry | EPL v2 | https://open-vsx.org |

## Bundled VS Code Extensions

Downloaded from Open VSX at build time (`scripts/download-extensions.sh`):

| Extension | ID | License |
|-----------|----|---------|
| Material Icon Theme | PKief.material-icon-theme | MIT |
| Prettier | esbenp.prettier-vscode | MIT |
| Python | ms-python.python | MIT |
| ESLint | dbaeumer.vscode-eslint | MIT |
| Tailwind CSS IntelliSense | bradlc.vscode-tailwindcss | MIT |

The ID column is what `scripts/check-bundled-extensions.py` matches this table
against the download script's list on, so a bundled extension cannot lose its
row here and a row cannot outlive the extension it describes.

VSCodroid's own bundled extensions (`vscodroid.*`) are covered by this repository's MIT `LICENSE`.

## Termux Project

Many bundled binaries and shared libraries are compiled by the [Termux](https://github.com/termux/termux-packages) project. Termux packages are built from upstream sources and maintain their original licenses. VSCodroid uses Termux's ARM64 builds as a convenience; we do not modify the Termux packaging system itself.

## Android NDK

The `libc++_shared.so` C++ standard library is distributed under the Apache 2.0 / MIT dual license as part of the [Android NDK](https://developer.android.com/ndk).

---

This file is provided for attribution purposes. For exact license texts, refer to the `LICENSE` files in each software's source repository. If you believe any attribution is missing or incorrect, please open an issue at https://github.com/rmyndharis/VSCodroid/issues.
