# VSCodroid Legal Notices

## Disclaimer

VSCodroid is built from the MIT-licensed **Code - OSS** source code (https://github.com/microsoft/vscode).

VSCodroid is **not affiliated with, endorsed by, or sponsored by Microsoft Corporation**.

"Visual Studio Code," "VS Code," and "Visual Studio" are trademarks of Microsoft Corporation. The use of these names in this document is solely for identification and reference purposes.

VSCodroid uses the **Open VSX** extension registry (https://open-vsx.org), not the Microsoft Visual Studio Code Marketplace. The Microsoft Visual Studio Code Marketplace is a separate service with its own terms of use, and is not used by this application.

---

## Open Source Licenses

VSCodroid incorporates the following open source projects. We are grateful to the developers and communities behind each of them.

Versions are given only where this repository actually pins one. Most bundled tools are installed from the Termux package index at build time, so the version a given release shipped is a property of that build, not of this file — stating a number here would only go stale silently. Where a version is pinned, the entry names the file that pins it.

### Code - OSS (VS Code)

- **Project**: https://github.com/microsoft/vscode
- **License**: MIT License
- **Copyright**: Copyright (c) 2015 - present Microsoft Corporation

```
MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### Node.js

- **Project**: https://nodejs.org
- **Version**: not pinned here — the `nodejs-lts` package from the Termux index, installed by `scripts/download-node.sh`. The line it must track is set by `remote/.npmrc` at the VS Code tag in `VSCODE_VERSION`, since that is what the server's native modules are built against
- **License**: MIT License (Node.js core), with additional licenses for bundled dependencies
- **Copyright**: Copyright Node.js contributors. All rights reserved.
- **Full license**: https://github.com/nodejs/node/blob/main/LICENSE

Node.js includes V8 (BSD-3-Clause), libuv (MIT), OpenSSL (Apache-2.0), ICU (Unicode License), llhttp (MIT), c-ares (MIT), and other components. See the Node.js LICENSE file for the complete list.

### Python

- **Project**: https://www.python.org
- **Version**: not pinned here — resolved from the Termux package index at build time by `scripts/download-python.sh`
- **License**: Python Software Foundation License (PSF-2.0)
- **Copyright**: Copyright (c) 2001-2024 Python Software Foundation. All rights reserved.
- **Full license**: https://docs.python.org/3/license.html

### Git

- **Project**: https://git-scm.com
- **License**: GNU General Public License v2.0 (GPL-2.0-only)
- **Copyright**: Copyright (c) Junio C Hamano and the Git contributors
- **Full license**: `licenses/COPYING.GPLv2`, which ships in the app at **About > Licenses > License Texts**; also https://github.com/git/git/blob/master/COPYING
- **Source availability**: The source code for the Git binary included in VSCodroid is available from the Termux packages repository at https://github.com/termux/termux-packages

### Bash

- **Project**: https://www.gnu.org/software/bash/
- **Version**: not pinned here — from the Termux package index at build time (`scripts/download-termux-tools.sh`)
- **License**: GNU General Public License v3.0 (GPL-3.0-or-later)
- **Copyright**: Copyright (c) Free Software Foundation, Inc.
- **Full license**: `licenses/COPYING.GPLv3`, which ships in the app at **About > Licenses > License Texts**; also https://www.gnu.org/licenses/gpl-3.0.html
- **Source availability**: The source code for the Bash binary included in VSCodroid is available from the Termux packages repository at https://github.com/termux/termux-packages

### OpenSSH

- **Project**: https://www.openssh.com
- **License**: BSD-style license
- **Copyright**: Copyright (c) 1995 Tatu Ylonen, Espoo, Finland. Portions copyright The OpenBSD Project.
- **Full license**: https://github.com/openssh/openssh-portable/blob/master/LICENCE

### tmux

- **Project**: https://github.com/tmux/tmux
- **Version**: not pinned here — from the Termux package index at build time (`scripts/download-termux-tools.sh`)
- **License**: ISC License
- **Copyright**: Copyright (c) Nicholas Marriott and contributors
- **Full license**: https://github.com/tmux/tmux/blob/master/COPYING

### GNU Make

- **Project**: https://www.gnu.org/software/make/
- **Version**: not pinned here — from the Termux package index at build time (`scripts/download-termux-tools.sh`)
- **License**: GNU General Public License v3.0 (GPL-3.0-or-later)
- **Copyright**: Copyright (c) Free Software Foundation, Inc.
- **Full license**: `licenses/COPYING.GPLv3`, which ships in the app at **About > Licenses > License Texts**; also https://www.gnu.org/licenses/gpl-3.0.html
- **Source availability**: The source code for the Make binary included in VSCodroid is available from the Termux packages repository at https://github.com/termux/termux-packages

### ripgrep

- **Project**: https://github.com/BurntSushi/ripgrep
- **License**: The Unlicense / MIT License (dual-licensed)
- **Copyright**: Copyright (c) Andrew Gallant
- **Bundled via**: the `@vscode/ripgrep-universal` npm package, whose own `repository` field names https://github.com/microsoft/vscode-ripgrep. The same `rg` is copied into `jniLibs` as `libripgrep.so` by `scripts/fetch-vscode-oss.sh`

### node-pty

- **Project**: https://github.com/microsoft/node-pty
- **Version**: matches the `node-pty` the server tree ships (see `PTY_VERSION` in `scripts/build-native-addons.sh`; cross-compiled for ARM64 Android)
- **License**: MIT License
- **Copyright**: Copyright (c) 2012-2015 Christopher J. Brody, 2016 Daniel Imms
- **Full license**: https://github.com/microsoft/node-pty/blob/main/LICENSE

### @parcel/watcher

- **Project**: https://github.com/parcel-bundler/watcher
- **Version**: matches the `@parcel/watcher` the server tree ships (see `WATCHER_VERSION` in `scripts/build-native-addons.sh`; cross-compiled for ARM64 Android)
- **License**: MIT License
- **Copyright**: Copyright (c) 2017-present Devon Govett
- **Full license**: https://github.com/parcel-bundler/watcher/blob/master/LICENSE

### @vscode/sqlite3

- **Project**: https://github.com/microsoft/vscode-node-sqlite3
- **Version**: matches the `@vscode/sqlite3` the server tree ships (rebuilt for ARM64 Android with the bundled SQLite amalgamation)
- **License**: BSD-3-Clause (fork of node-sqlite3, Copyright (c) MapBox); SQLite itself is public domain
- **Full license**: https://github.com/microsoft/vscode-node-sqlite3/blob/main/LICENSE

### @vscode/native-watchdog

- **Project**: https://github.com/microsoft/node-native-watchdog
- **Version**: whatever the server tree ships (`node_modules/@vscode/native-watchdog/package.json`)
- **License**: MIT License
- **Copyright**: Copyright (c) Microsoft Corporation. All rights reserved.
- **Ships**: `build/Release/watchdog.node`

### @vscode/deviceid

- **Project**: https://github.com/microsoft/vscode-deviceid
- **Version**: whatever the server tree ships (`node_modules/@vscode/deviceid/package.json`)
- **License**: MIT License
- **Copyright**: Copyright (c) Microsoft Corporation.
- **Ships**: `build/Release/windows.node`. The name is the package's own and is not a description of the platform; the file in this tree is an ARM64 ELF addon.

### @vscode/sandbox-runtime

- **Project**: https://github.com/anthropic-experimental/sandbox-runtime, as declared by the package's own `repository` field
- **Version**: whatever the server tree ships (`node_modules/@vscode/sandbox-runtime/package.json`)
- **License**: Apache License 2.0. The full text ships at `node_modules/@vscode/sandbox-runtime/LICENSE` and carries no filled-in copyright line.
- **Ships**: `vendor/seccomp/x64/apply-seccomp`, an x86-64 helper that cannot run on an ARM64 device. It is listed because it is redistributed, not because it is used.

### kerberos (Node addon)

The npm package, not the MIT Kerberos 5 C libraries listed further down. Different
project, different licence, nearly the same name.

- **Project**: https://github.com/mongodb-js/kerberos
- **Version**: whatever the server tree ships (`node_modules/kerberos/package.json`)
- **License**: Apache License 2.0. The full text ships at `node_modules/kerberos/LICENSE`.
- **Ships**: `build/Release/kerberos.node`, and a second copy under `build/Release/obj.target/`

### @microsoft/mxc-sdk

- **Project**: https://www.npmjs.com/package/@microsoft/mxc-sdk. The package declares no repository.
- **Version**: whatever the server tree ships (`node_modules/@microsoft/mxc-sdk/package.json`)
- **License**: MIT License
- **Copyright**: Copyright (c) Microsoft Corporation.
- **Ships**: eleven binaries in `bin/arm64/`, only two of which are ARM64 Linux executables (`linux-test-proxy`, `lxc-exec`). The rest are a Mach-O helper (`mxc-exec-mac`) and eight Windows PE files (`mxc-diagnostic-console.exe`, `winhttp-proxy-shim.exe`, `wslcsdk.dll`, `wxc-exec.exe`, `wxc-host-prep.exe`, `wxc-test-proxy.exe`, `wxc-windows-sandbox-daemon.exe`, `wxc-windows-sandbox-guest.exe`), which the directory name does not describe and which nothing here can run. They are listed because they are redistributed.

### js-debug

The built-in JavaScript and Node.js debugger extension, produced by the Code - OSS
build rather than downloaded from a gallery.

- **Project**: https://github.com/microsoft/vscode-js-debug
- **Version**: whatever the Code - OSS tag in `VSCODE_VERSION` pins
- **License**: MIT License. The full text and the extension's own `ThirdPartyNotices.txt` ship inside `extensions/ms-vscode.js-debug/`.
- **Copyright**: Copyright (c) Microsoft Corporation. All rights reserved.
- **Ships**: `src/chromehash_bg.wasm`, and two `win32-app-container-tokens.win32-*-msvc-*.node` addons. The addons are Windows PE binaries with no use on Android; they are listed because they are redistributed.

### vscode-js-profile-visualizer

The built-in viewer for CPU and heap profiles, produced by the Code - OSS build.

- **Project**: https://github.com/microsoft/vscode-js-profile-visualizer
- **Version**: whatever the Code - OSS tag in `VSCODE_VERSION` pins
- **License**: MIT License. The full text and the extension's own `ThirdPartyNotices.txt` ship inside `extensions/ms-vscode.vscode-js-profile-table/`.
- **Copyright**: Copyright (c) Microsoft Corporation. All rights reserved.
- **Ships**: two `*.module.wasm` table renderers under `out/`.

### @vscode/tree-sitter-wasm

The incremental parser behind syntax-aware editing, compiled to WebAssembly
rather than to a native addon. Two copies ship: the server's own, and the one the
Copilot Chat extension resolves.

- **Project**: https://github.com/microsoft/vscode-tree-sitter-wasm
- **Upstream**: https://github.com/tree-sitter/tree-sitter, MIT, recorded with its commit in the package's `cgmanifest.json`
- **License**: MIT License. The full text ships as `LICENSE` inside the package.
- **Ships**: `tree-sitter.wasm` and the grammar modules beside it (bash, css, ini, powershell, regex, typescript in the server copy).

### web-tree-sitter

tree-sitter's own WebAssembly binding, bundled by the Copilot Chat extension.

- **Project**: https://github.com/tree-sitter/tree-sitter
- **License**: MIT License. The full text ships as `LICENSE` inside the package.
- **Copyright**: Copyright (c) 2018-2024 Max Brunsfeld
- **Ships**: `tree-sitter.wasm`.

### vscode-oniguruma

The regex engine TextMate grammars are matched with, compiled to WebAssembly.

- **Project**: https://github.com/microsoft/vscode-oniguruma
- **Upstream**: Oniguruma, https://github.com/kkos/oniguruma, **BSD-2-Clause**. The wrapper's licence does not cover the engine inside it, so both are recorded; Oniguruma's own notice ships as `NOTICES.txt` in the package.
- **License**: MIT License for the wrapper. The full text ships as `LICENSE.txt` inside the package.
- **Copyright**: Copyright (c) Microsoft Corporation (wrapper); Copyright (c) 2002-2020 K. Kosako (Oniguruma)
- **Ships**: `release/onig.wasm`.

### @github/blackbird-external-ingest-utils

A WebAssembly helper the Copilot Chat extension uses for code indexing.

- **Project**: https://www.npmjs.com/package/@github/blackbird-external-ingest-utils. The package declares no repository.
- **Version**: whatever the Copilot Chat extension pins at the Code - OSS tag in `VSCODE_VERSION`
- **License**: MIT License, declared by the package
- **Ships**: `external_ingest_utils_bg.wasm`, both inside the package and inlined into the extension's `dist/`.

### GitHub Copilot Chat extension

The extension itself, which is open source and MIT, and a different component
from the proprietary `@github/copilot` CLI it depends on. Its `dist/` bundle
carries the tree-sitter grammars and the blackbird WebAssembly listed above,
inlined by its build.

- **Project**: https://github.com/microsoft/vscode-copilot-chat
- **Version**: whatever the Code - OSS tag in `VSCODE_VERSION` pins
- **License**: MIT License. The full text ships as `LICENSE.txt` inside `extensions/copilot/`.
- **Copyright**: Copyright (c) Microsoft Corporation. All rights reserved.
- **Ships**: `dist/tree-sitter*.wasm` and `dist/external_ingest_utils_bg.wasm`.

### PSReadLine

Prebuilt .NET assemblies carried by the terminal's PowerShell shell integration.
Nothing on Android can load them; they are inside the APK, which is the only fact
the licence turns on.

- **Project**: https://github.com/PowerShell/PSReadLine
- **Version**: whatever the Code - OSS tag in `VSCODE_VERSION` pins. The server tree's own `ThirdPartyNotices.txt` records it and reproduces the licence text.
- **License**: BSD-2-Clause License
- **Copyright**: Copyright (c) 2013, Jason Shirk
- **Ships**: `Microsoft.PowerShell.PSReadLine.dll`, `Microsoft.PowerShell.Pager.dll` and the two `Microsoft.PowerShell.PSReadLine.Polyfiller.dll` builds, under `out/vs/workbench/contrib/terminal/common/scripts/psreadline/`.

### distlib

Vendored inside the bundled pip. Listed separately from Python because the
licence is the same but the copyright is not, and PSF-2.0 is precisely the
licence that asks for the notice to travel with the copy.

- **Project**: https://github.com/pypa/distlib
- **Version**: recorded in `usr/lib/python*/site-packages/pip/_vendor/vendor.txt`
- **License**: Python Software Foundation License, Version 2. The full text ships as `LICENSE.txt` inside the package.
- **Copyright**: Copyright (c) 2012-2024 Vinay Sajip
- **Ships**: six prebuilt Windows launchers (`t32.exe`, `t64.exe`, `t64-arm.exe`, `w32.exe`, `w64.exe`, `w64-arm.exe`), redistributed unused.

### npm

- **Project**: https://www.npmjs.com
- **Version**: 11.16.0 — declared as `NPM_VERSION` in `scripts/download-npm.sh` and asserted against the nodejs.org tarball, which is the release the bundled Node runtime comes from
- **License**: Artistic License 2.0
- **Copyright**: Copyright (c) npm, Inc. and Contributors
- **Full license**: https://github.com/npm/cli/blob/latest/LICENSE

### libevent

- **Project**: https://libevent.org
- **License**: BSD-3-Clause License
- **Used by**: tmux

### readline

- **Project**: https://tiswww.case.edu/php/chet/readline/rltop.html
- **License**: GNU General Public License v3.0 (GPL-3.0-or-later)
- **Full license**: `licenses/COPYING.GPLv3`, which ships in the app at **About > Licenses > License Texts**
- **Used by**: bash, and Python's `readline` module

### ncurses

- **Project**: https://invisible-island.net/ncurses/
- **License**: MIT-style (X11) License
- **Used by**: tmux, libedit, readline, and Python's `curses` modules. bash reaches it through readline rather than directly

### libffi

- **Project**: https://sourceware.org/libffi/
- **License**: MIT License
- **Used by**: Python

### OpenSSL

- **Project**: https://www.openssl.org
- **License**: Apache License 2.0 (OpenSSL 3.x)
- **Ships**: `libcrypto.so.3` and `libssl.so.3`. BoringSSL is not bundled; the bundled Node links these
- **Used by**: Node.js, Python, Git, OpenSSH, ldns, libcrypt, libcurl, libssh2 and ngtcp2

Git is GPL-2.0 and reaches OpenSSL through libcurl, and the two licenses are
read as incompatible when a work combines them. The position taken here, stated
so that it is a recorded decision rather than an oversight:

- Nothing in this APK links Git against OpenSSL into one binary. They are
  separate executables shipped side by side, each loading its own dependencies
  at runtime, which is the same arrangement every Linux distribution ships and
  the one Termux packages these builds as.
- GPLv2's system-library exception is written for a system's own components and
  is not squarely on point for a library an application bundles itself, so it is
  not being relied on as the whole answer.
- If a copyright holder disagreed, the remedy is to build Git against a
  different TLS backend rather than to remove it. That is a build-time
  substitution in `scripts/download-termux-tools.sh`, not a redesign.

---

## Bundled Native Library Inventory

The shared libraries and executables that sit at the top level of
`assets/usr/lib` and `jniLibs/arm64-v8a`, with the licence each is distributed
under. The licence column is taken from Termux's own `TERMUX_PKG_LICENSE`, which
is what these packages are built from, rather than from upstream project pages
that may describe a different version. `scripts/check-termux-licenses.py` reads
that field back on every release and reports where this column and Termux
disagree, so the sentence before this one is measured rather than promised for
every package Termux states a licence for and that upstream answered for. Two
rows come from elsewhere and carry the licence of the package they do come from:
musl's loader is an Alpine package (`scripts/download-musl-loader.sh`), and
`libripgrep.so` is the `rg` from the Code - OSS server tree, copied into
`jniLibs` by `scripts/fetch-vscode-oss.sh`. That same gate prints both of them as
entries no Termux package accounts for, so the exception is named by the run and
not only here.

This is not every binary in the APK. Most of
them are not below: they live deeper in the asset tree and are attributed by the
sections above. Git's helper executables under `usr/lib/git-core/`, CPython's
extension modules under `usr/lib/python*/lib-dynload/`, pip's vendored launchers,
the server tree's native addons and bundled tools under `assets/vscode-reh/`, the
WebAssembly its editor services load, and the .NET assemblies its terminal
integration carries. 195 files against the 53 listed below, measured on the tree
that built this release, redistributed on identical terms.

That figure is a measurement rather than a fixed property, and the gate prints it
on every run: a Python module leaving `lib-dynload` or a package adding a grammar
moves it. What has to stay true is that every one of those files is attributed
somewhere, which is what the gate enforces rather than what this sentence claims.

`scripts/check-library-attribution.py` regenerates the basis for this table from the
files actually present in the build tree and fails when a shipped binary is absent
from it, or when a copyleft component is missing from the source offer below. It
walks the whole asset tree, recognising a binary by its magic number (ELF,
WebAssembly, PE or Mach-O) or a `.node` extension, and holds every component it
finds to both this file and `NOTICE.md`. Formats this device cannot execute are
counted too: a Windows launcher and a .NET assembly are redistributed on their
own terms whether or not anything here can load them, so testing ELF magic alone
would walk past most of what ships.
It runs on every pull request and every push to `main` (`.github/workflows/build.yml`),
and twice more at release time, once before the toolchain payloads are downloaded
and once after (`.github/workflows/release.yml`).

Not listed here, because they are not third-party code: `libglibc-shim.so` and the
stubs beside it (`libc.so.6`, `libm.so.6`, `libdl.so.2`, `libpthread.so.0`,
`librt.so.1`, `libutil.so.1`, `libresolv.so.2`, `libcrypt.so.1`, `libgcc_s.so.1`,
`ld-linux-aarch64.so.1`). They carry glibc's names so a glibc-linked binary can
resolve against them, but contain none of glibc's code — they are built from
`scripts/glibc-shim.c` and `scripts/gen-glibc-forwarders.py` in this repository and
are covered by the root `LICENSE`.

| Component | Licence | Copyleft | Files shipped |
|---|---|---|---|
| [Bash](https://www.gnu.org/software/bash/) | GPL-3.0 | **yes** | `libbash.so` |
| [bzip2](https://sourceware.org/bzip2/) | BSD-4-Clause | no | `libbz2.so.1.0` |
| [c-ares](https://c-ares.org) | MIT | no | `libcares.so` |
| [Expat](https://libexpat.github.io) | MIT | no | `libexpat.so.1` |
| [gdbm](https://www.gnu.org.ua/software/gdbm/) | GPL-3.0 | **yes** | `libgdbm.so`, `libgdbm_compat.so` |
| [Git](https://git-scm.com) | GPL-2.0 | **yes** | `libgit-remote-curl.so`, `libgit.so` |
| [GNU Make](https://www.gnu.org/software/make/) | GPL-3.0 | **yes** | `libmake.so` |
| [ICU](https://icu.unicode.org) | ICU | no | `libicudata.so.78`, `libicui18n.so.78`, `libicuuc.so.78` |
| [Kerberos 5](https://web.mit.edu/kerberos/) | MIT | no | `libcom_err.so.3`, `libgssapi_krb5.so.2`, `libk5crypto.so.3`, `libkrb5.so.3`, `libkrb5support.so.0` |
| [ldns](https://www.nlnetlabs.nl/projects/ldns/) | BSD-3-Clause | no | `libldns.so` |
| [libandroid-glob](https://github.com/termux/libandroid-glob) | BSD-3-Clause | no | `libandroid-glob.so` |
| [libandroid-posix-semaphore](https://github.com/termux/libandroid-posix-semaphore) | MIT | no | `libandroid-posix-semaphore.so` |
| [libandroid-support](https://github.com/termux/libandroid-support) | Apache-2.0, MIT | no | `libandroid-support.so` |
| [libc++](https://libcxx.llvm.org) | NCSA | no | `libc++_shared.so` |
| [libcrypt](http://michael.dipperstein.com/crypt/) | BSD-2-Clause | no | `libcrypt.so` |
| [libcurl](https://curl.se) | MIT | no | `libcurl.so` |
| [libedit](https://thrysoee.dk/editline/) | BSD-3-Clause | no | `libedit.so` |
| [libevent](https://libevent.org) | BSD-3-Clause | no | `libevent-2.1.so`, `libevent_core-2.1.so` |
| [libffi](https://sourceware.org/libffi/) | MIT | no | `libffi.so` |
| [libiconv](https://www.gnu.org/software/libiconv/) | LGPL-2.1, GPL-3.0 | **yes** | `libiconv.so` |
| [libresolv-wrapper](https://cwrap.org) | BSD-3-Clause | no | `libresolv_wrapper.so` |
| [libssh2](https://libssh2.org) | BSD-3-Clause | no | `libssh2.so` |
| [musl libc](https://musl.libc.org) | MIT | no | `libldmusl.so` |
| [ncurses](https://invisible-island.net/ncurses/) | MIT | no | `libncursesw.so.6`, `libpanelw.so.6` |
| [nghttp2](https://nghttp2.org) | MIT | no | `libnghttp2.so` |
| [nghttp3](https://github.com/ngtcp2/nghttp3) | MIT | no | `libnghttp3.so` |
| [ngtcp2](https://github.com/ngtcp2/ngtcp2) | MIT | no | `libngtcp2.so`, `libngtcp2_crypto_ossl.so` |
| [Node.js](https://nodejs.org) | MIT | no | `libnode.so` |
| [OpenSSH](https://www.openssh.com) | BSD | no | `libssh-keygen.so`, `libssh.so` |
| [OpenSSL](https://www.openssl.org) | Apache-2.0 | no | `libcrypto.so.3`, `libssl.so.3` |
| [PCRE2](https://www.pcre.org) | BSD-3-Clause | no | `libpcre2-8.so` |
| [Python](https://www.python.org) | PSF-2.0 | no | `libpython.so`, `libpython3.14.so` |
| [readline](https://tiswww.case.edu/php/chet/readline/rltop.html) | GPL-3.0 | **yes** | `libreadline.so.8` |
| [ripgrep](https://github.com/BurntSushi/ripgrep) | MIT | no | `libripgrep.so` |
| [SQLite](https://sqlite.org) | Public Domain | no | `libsqlite3.so` |
| [tmux](https://github.com/tmux/tmux) | ISC | no | `libtmux.so` |
| [xz / liblzma](https://tukaani.org/xz/) | LGPL-2.1, GPL-2.0, GPL-3.0 | **yes** | `liblzma.so.5` |
| [zlib](https://zlib.net) | Zlib | no | `libz.so.1` |
| [Zstandard](https://facebook.github.io/zstd/) | GPL-2.0 | **yes** | `libzstd.so.1` |

---

## Proprietary Redistributed Components

### @github/copilot (GitHub Copilot CLI)

The built-in GitHub Copilot Chat extension depends on GitHub's `@github/copilot` package. Unlike everything listed above, this component is **not open source**: it is redistributed under GitHub's own terms.

- **Publisher**: GitHub, Inc.
- **License**: GitHub Copilot CLI License — the full text ships with every copy in the server tree; read it at `extensions/copilot/node_modules/@github/copilot/LICENSE.md`. It is not reproduced here.
- **Versions**: whatever the Copilot extension pins at the Code - OSS tag in `VSCODE_VERSION`. These are not all the same number; see below.

**What is redistributed.** Three copies, at two versions, all produced by the Code - OSS build:

| Location in the server tree | Version | What it is |
|---|---|---|
| `node_modules/@github/copilot` | 1.0.79-6 | The three-file npm loader (`npm-loader.js`, `package.json`, `LICENSE.md`) |
| `node_modules/@github/copilot-linux-arm64` | 1.0.79-6 | The runtime itself — 104 files, ~175 MB |
| `extensions/copilot/node_modules/@github/copilot` | 1.0.73 | The SDK copy the extension resolves, 67 files |

The CLI **application** is among them: `index.js` is a `#!/usr/bin/env node` launcher and `app.js` is the 9 MB program it runs. What is *not* shipped is the standalone single-executable build — `build/lib/copilot.ts:212-213` excludes `copilot` and `copilot.exe`, and `:214-215` excludes the optional native payloads (`foundry-local-sdk`, `webview`, `clipboard`, `pvrecorder`) along with the non-target `prebuilds`. So the accurate statement is that VSCodroid ships the CLI as JavaScript executed by the bundled Node, not as a self-contained binary.

**Why we believe redistribution is permitted.** Section 1 of the license grants the right to reproduce and redistribute unmodified copies of the Software as part of an application or service, subject to the five conditions in Section 2. Our position on each:

| Condition (§2) | Assessment |
|---|---|
| Distributed only in unmodified form | **Judgment call — see below.** |
| Redistributed solely as part of an application providing material functionality beyond the Software | Met. VSCodroid is a full IDE; the Copilot runtime is a dependency of one built-in extension. |
| Not distributed standalone or as a primary product | Met. It is not separately installable, not advertised, and not reachable except through the extension — the standalone executable form is the one thing the build excludes. |
| A copy of the license is included and notices retained | Met. `LICENSE.md` travels with every copy in the tree (all copies byte-identical), and `NOTICE.md` attributes the component. |
| The application is licensed independently of the Software | Met. VSCodroid's own source is MIT; the root `LICENSE` covers only that. |

**The judgment call on "unmodified form".** The question is not whether the tree matches npm exactly — it does not — but what the differences actually are. Measured file by file against the upstream tarballs:

*The loader copy* (`@github/copilot@1.0.79-6`): byte-identical in all three files it ships. Only `README.md` is omitted.

*The runtime copy* (`@github/copilot-linux-arm64@1.0.79-6`): 99 of its 104 files are byte-identical to upstream. The remaining five — `app.js`, `sdk/index.js`, and the three `voice-*` workers — differ by exactly one thing: a trailing `//# sourceMappingURL=` comment has been removed. No `.map` files ship, so the removed lines pointed at files that are not there. The change is 32 to 41 bytes per file and alters no behavior.

*The SDK copy* (`@github/copilot@1.0.73`): 67 files, five of them re-rooted under `sdk/`. Its content differences from the upstream platform package are `package.json`, rewritten by the extension's own `postinstall` (renamed, given an `exports` map, platform constraints dropped), and the bundled `ripgrep` binary, replaced with the editor's own by the upstream packaging step `prepareBuiltInCopilotRipgrepShim`, which also writes a `shims.txt` marker. That replacement is byte-identical to `@vscode/ripgrep-universal`'s `rg`, sha256 `e152ea689d6e8420357e592f0d8253b96476c164118ca3e6e13074fa1705ddda`, measured in the shipped tree.

Pruning in both copies follows the upstream build's own `build/.moduleignore` and `build/lib/copilot.ts`.

Every one of these transformations is performed by GitHub's or Microsoft's own build tooling, which VSCodroid runs unmodified; none is a VSCodroid intervention. The single patch this project applies to that area, `patches/0010-moduleignore-keep-copilot-sdk-entry.patch`, *removes* a pruning rule, so the result is closer to the published package than a default build would produce, not further from it.

We read "unmodified form" as directed at the redistributor altering the Software, not at the vendor's own build tooling producing the embedded shape it was designed to produce. To be precise about which mode of that tooling is in play: Microsoft's own CI does **not** compile this extension — it downloads it as a VSIX from an internal feed, and `compile-copilot-extension-build` is described upstream as the path "used by non-CI local builds where copilot is not downloaded as a VSIX" (`gulpfile.extensions.ts:288`). That is the path this build takes, and it is a mode Microsoft ships for building from source; it is not the identical pipeline behind their released desktop binaries.

The weakest point in our reading is the `ripgrep` substitution, because a binary component is replaced rather than merely omitted. The `sourceMappingURL` stripping is the next weakest, though it is hard to characterise a dangling reference to an unshipped file as a modification of substance.

**This is a reasoned engineering position, not legal advice.** It records how the maintainers understand the terms and why the component is bundled. Anyone redistributing VSCodroid, or building a product on it, should reach their own conclusion and take their own advice.

---

## Termux Project Attribution

Many of the command-line tools bundled with VSCodroid (Node.js, Python, Bash, Git, tmux, Make, OpenSSH, and their dependencies) are built from recipes and patches maintained by the **Termux** project.

- **Project**: https://termux.dev
- **Repository**: https://github.com/termux/termux-packages
- **License**: Apache License 2.0 (build scripts and patches)
- **Copyright**: Copyright (c) 2015-2024 Fredrik Fornwall and Termux contributors

We thank the Termux community for their extensive work in porting these tools to Android. Without their efforts, projects like VSCodroid would be significantly more difficult to build.

## Open VSX Attribution

VSCodroid uses the **Open VSX** registry for its extension marketplace.

- **Project**: https://open-vsx.org
- **Operated by**: Eclipse Foundation
- **Repository**: https://github.com/eclipse/openvsx
- **License**: Eclipse Public License 2.0 (EPL-2.0)

Open VSX is an open, vendor-neutral alternative to the Microsoft Visual Studio Code Marketplace. Extensions available on Open VSX are published by their respective authors and may have their own licenses.

## Android SDK and NDK

VSCodroid is built using the Android SDK and NDK provided by Google.

- **Android SDK**: Subject to the Android SDK License Agreement
- **Android NDK**: Subject to the Android NDK License Agreement
- **Full terms**: https://developer.android.com/studio/terms

## Google Play Asset Delivery

On Play Store installs, on-demand toolchain packs (Ruby, Java) are delivered via Google Play Asset Delivery, a feature of Google Play. On installs that did not come from the Play Store, the same packs are downloaded as ZIPs from this project's GitHub Releases (https://github.com/rmyndharis/VSCodroid/releases), which the terms below do not govern.

- **Terms**: https://play.google.com/about/developer-distribution-agreement.html

---

## GPL Source Code Availability

VSCodroid bundles binaries licensed under the GNU General Public License (GPL). In compliance with the GPL, the complete corresponding source code for these binaries is available:

- **Bash** (GPL-3.0): Source available at https://github.com/termux/termux-packages (package: `bash`)
- **Git** (GPL-2.0): Source available at https://github.com/termux/termux-packages (package: `git`)
- **GNU Make** (GPL-3.0): Source available at https://github.com/termux/termux-packages (package: `make`)
- **readline** (GPL-3.0): Source available at https://github.com/termux/termux-packages (package: `readline`)
- **libiconv** (LGPL-2.1 / GPL-3.0): Source available at https://github.com/termux/termux-packages (package: `libiconv`) — linked by Bash and by every Git executable
- **gdbm** (GPL-3.0): Source available at https://github.com/termux/termux-packages (package: `gdbm`) — linked by Python's `dbm` and `gdbm` modules
- **xz / liblzma** (LGPL-2.1 / GPL-2.0 / GPL-3.0): Source available at https://github.com/termux/termux-packages (package: `liblzma`) — linked by Python's `lzma` module
- **Zstandard** (GPL-2.0 as packaged by Termux; dual-licensed BSD-3-Clause upstream): Source available at https://github.com/termux/termux-packages (package: `zstd`) — linked by Python's `zstd` module
- **GMP** (LGPL-3.0): Source available at https://github.com/termux/termux-packages (package: `libgmp`) — shipped inside the Ruby toolchain pack, not the base app, so it reaches only devices where Ruby was installed
- **OpenJDK 17** (GPL-2.0 with the Classpath Exception): Source available at https://github.com/termux/termux-packages (package: `openjdk-17`), built from https://github.com/openjdk/jdk17u. Shipped inside the Java toolchain pack, not the base app, so it reaches only devices where Java was installed. The Classpath Exception grants an additional permission and removes none of the obligations above.

Every entry after readline reaches the app as a dependency of something else
rather than as a tool of its own, and each is dynamically linked and shipped as
its own `.so`, so the LGPL's relinking condition is satisfied by replacing the
file; the written offer in this section applies to all of them regardless.

You may also request a copy of the source code by contacting us (see contact information below). Source code will be provided for a period of three years from the date of distribution of the corresponding binary, for a charge no more than the cost of physically performing the distribution.

---

## GPL and LGPL License Texts

The offer above is one obligation; a copy of the licence itself is the other. GPL-2.0 section 1, GPL-3.0 section 4 and LGPL-2.1 section 1 each require the licence to reach whoever receives the binary, and a link to gnu.org is not a copy of it, least of all on a device with no network. So the texts ship:

| Licence | Text | Components it covers |
|---|---|---|
| GPL-2.0 | `licenses/COPYING.GPLv2` | Git, `git-remote-curl`, Zstandard, xz / liblzma, Java (OpenJDK) |
| GPL-3.0 | `licenses/COPYING.GPLv3` | Bash, GNU Make, readline, gdbm, libiconv, xz / liblzma |
| LGPL-2.1 | `licenses/COPYING.LGPLv2.1` | libiconv, xz / liblzma |

These are the Free Software Foundation's texts as shipped in Termux's `liblzma` package, which is one of the packages this app redistributes, and they are verbatim. `NoticesTest` pins the sha256 of each one: a licence text that has been reflowed, re-wrapped or truncated is no longer the licence, so none of them may be edited.

They reach the device through the same `bundleNotices` task as this document, and are read straight out of the APK at **About > Licenses > License Texts**. They sit behind that chooser rather than inside the notices body because 78 KB of licence in front of the attribution and the source offer would bury the part a reader opened that screen for.

Java is the one component above that arrives in an on-demand pack rather than in the base app, and the text covering it ships in the base app, which every device installing that pack already holds. Its licence is GPL-2.0 with the Classpath Exception, an additional permission that grants rights rather than requiring a copy of anything to travel; the exception is stated at https://openjdk.org/legal/gplv2+ce.html.

OpenJDK's own notice set travels inside the Java pack, at `usr/lib/jvm/java-17-openjdk/legal`, beside the binaries it describes: the GPLv2 text it ships, the Assembly Exception, the Classpath Exception statement, and the third-party notices for the Apache, MPL, W3C, Unicode, ICU, BSD and MIT components inside it. Those components are not listed individually here, for the same reason the toolchain trees are not: the notices upstream wrote are what discharge their terms, and they ship. `scripts/download-java.sh` refuses to build the pack without them, and refuses one in which they arrived as symbolic links, because neither an asset pack nor the release ZIP can carry a link.

GMP, in the Ruby toolchain pack, is LGPL-3.0 and is the one copyleft component with no text here; its licence is named and its source offered above.

---

## Toolchain Libraries

Shipped inside an on-demand toolchain pack rather than the base app, so they reach
only devices where that toolchain was installed. The obligations are the same;
only the audience is smaller.

`scripts/check-library-attribution.py` reads these from the toolchain manifests in
`android/toolchain_*/src/main/assets/`, rather than from disk, because the packs
are built by CI and are absent from a working tree — a disk scan would find
nothing and report success.

| Component | Licence | Shipped with | Source |
|---|---|---|---|
| GMP | LGPL-3.0 | Ruby | https://github.com/termux/termux-packages (package: `libgmp`) |
| libyaml | MIT | Ruby | https://pyyaml.org/wiki/LibYAML |
| libruby | BSD-2-Clause | Ruby | https://www.ruby-lang.org |
| libandroid-execinfo | BSD-2-Clause | Ruby | https://github.com/termux/libandroid-execinfo |
| libandroid-shmem | BSD-3-Clause | Java | https://github.com/termux/libandroid-shmem |
| libandroid-spawn | BSD-2-Clause | Java | https://github.com/termux/libandroid-spawn |

GMP is the only copyleft entry here, and its written source offer sits with the
others under **GPL Source Code Availability** above.

---

## On-Demand Toolchain Licenses

The following toolchains are available as optional downloads and have their own licenses:

### Ruby

- **Project**: https://www.ruby-lang.org
- **License**: Ruby License / BSD-2-Clause (dual-licensed)
- **Copyright**: Copyright (c) Yukihiro Matsumoto and Ruby contributors
- **Full license**: https://www.ruby-lang.org/en/about/license.txt

### Java (OpenJDK)

- **Project**: https://openjdk.org
- **License**: GNU General Public License v2.0 with Classpath Exception (GPL-2.0 WITH Classpath-exception-2.0)
- **Copyright**: Copyright (c) Oracle and/or its affiliates
- **Full license**: `licenses/COPYING.GPLv2`, which ships in the app at **About > Licenses > License Texts**; the Classpath Exception that modifies it is stated at https://openjdk.org/legal/gplv2+ce.html

---

## Trademark Notices

- **Visual Studio Code**, **VS Code**, and **Visual Studio** are registered trademarks of Microsoft Corporation in the United States and/or other countries.
- **Android** is a trademark of Google LLC.
- **GitHub** is a trademark of GitHub, Inc.
- **Node.js** is a trademark of the OpenJS Foundation.
- **Python** is a trademark of the Python Software Foundation.
- **npm** is a trademark of npm, Inc.
- **Java** and **OpenJDK** are trademarks of Oracle Corporation.
- **Ruby** is a trademark of Yukihiro Matsumoto.
- **Google Play** is a trademark of Google LLC.

All other trademarks are the property of their respective owners. The use of these trademarks in this document is for identification purposes only and does not imply endorsement.

---

## Contact

For questions about licenses, trademarks, or legal notices:

- **Email**: yudhi@rmyndharis.com
- **GitHub**: https://github.com/rmyndharis/VSCodroid

---

_This document was last updated on August 21, 2026._
