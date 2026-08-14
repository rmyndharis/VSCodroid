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
- **Copyright**: Copyright (c) 2015-2024 Microsoft Corporation

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
- **Full license**: https://github.com/git/git/blob/master/COPYING
- **Source availability**: The source code for the Git binary included in VSCodroid is available from the Termux packages repository at https://github.com/termux/termux-packages

### Bash

- **Project**: https://www.gnu.org/software/bash/
- **Version**: not pinned here — from the Termux package index at build time (`scripts/download-termux-tools.sh`)
- **License**: GNU General Public License v3.0 (GPL-3.0-or-later)
- **Copyright**: Copyright (c) Free Software Foundation, Inc.
- **Full license**: https://www.gnu.org/licenses/gpl-3.0.html
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
- **Full license**: https://www.gnu.org/licenses/gpl-3.0.html
- **Source availability**: The source code for the Make binary included in VSCodroid is available from the Termux packages repository at https://github.com/termux/termux-packages

### ripgrep

- **Project**: https://github.com/BurntSushi/ripgrep
- **License**: The Unlicense / MIT License (dual-licensed)
- **Copyright**: Copyright (c) Andrew Gallant
- **Bundled via**: `@vscode/ripgrep` npm package (https://github.com/microsoft/vscode-ripgrep)

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
- **Used by**: bash

### ncurses

- **Project**: https://invisible-island.net/ncurses/
- **License**: MIT-style (X11) License
- **Used by**: bash, tmux

### libffi

- **Project**: https://sourceware.org/libffi/
- **License**: MIT License
- **Used by**: Python

### OpenSSL / BoringSSL

- **Project**: https://www.openssl.org
- **License**: Apache License 2.0 (OpenSSL 3.x)
- **Used by**: Node.js, Python, Git, OpenSSH

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
| `extensions/copilot/node_modules/@github/copilot` | 1.0.73 | The SDK copy the extension resolves — 98 files |

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

*The SDK copy* (`@github/copilot@1.0.73`): 95 of its 98 files are byte-identical to the upstream platform package, 35 of them re-rooted under `sdk/`. The differences are `package.json`, rewritten by the extension's own `postinstall` (renamed, given an `exports` map, platform constraints dropped), and the bundled `ripgrep` binary, replaced with the editor's own — verified byte-identical to `@vscode/ripgrep-universal` — by the upstream packaging step `prepareBuiltInCopilotRipgrepShim`, which also writes a `shims.txt` marker.

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

On-demand toolchain packs (Go, Ruby, Java) are delivered via Google Play Asset Delivery, a feature of Google Play.

- **Terms**: https://play.google.com/about/developer-distribution-agreement.html

---

## GPL Source Code Availability

VSCodroid bundles binaries licensed under the GNU General Public License (GPL). In compliance with the GPL, the complete corresponding source code for these binaries is available:

- **Bash** (GPL-3.0): Source available at https://github.com/termux/termux-packages (package: `bash`)
- **Git** (GPL-2.0): Source available at https://github.com/termux/termux-packages (package: `git`)
- **GNU Make** (GPL-3.0): Source available at https://github.com/termux/termux-packages (package: `make`)
- **readline** (GPL-3.0): Source available at https://github.com/termux/termux-packages (package: `readline`)

You may also request a copy of the source code by contacting us (see contact information below). Source code will be provided for a period of three years from the date of distribution of the corresponding binary, for a charge no more than the cost of physically performing the distribution.

---

## On-Demand Toolchain Licenses

The following toolchains are available as optional downloads and have their own licenses:

### Go

- **Project**: https://go.dev
- **License**: BSD-3-Clause License
- **Copyright**: Copyright (c) 2009-2024 The Go Authors
- **Full license**: https://go.dev/LICENSE

### Ruby

- **Project**: https://www.ruby-lang.org
- **License**: Ruby License / BSD-2-Clause (dual-licensed)
- **Copyright**: Copyright (c) Yukihiro Matsumoto and Ruby contributors
- **Full license**: https://www.ruby-lang.org/en/about/license.txt

### Java (OpenJDK)

- **Project**: https://openjdk.org
- **License**: GNU General Public License v2.0 with Classpath Exception (GPL-2.0 WITH Classpath-exception-2.0)
- **Copyright**: Copyright (c) Oracle and/or its affiliates
- **Full license**: https://openjdk.org/legal/gplv2+ce.html

---

## Trademark Notices

- **Visual Studio Code**, **VS Code**, and **Visual Studio** are registered trademarks of Microsoft Corporation in the United States and/or other countries.
- **Android** is a trademark of Google LLC.
- **GitHub** is a trademark of GitHub, Inc.
- **Node.js** is a trademark of the OpenJS Foundation.
- **Python** is a trademark of the Python Software Foundation.
- **npm** is a trademark of npm, Inc.
- **Java** and **OpenJDK** are trademarks of Oracle Corporation.
- **Go** and the Go Gopher are trademarks of Google LLC.
- **Ruby** is a trademark of Yukihiro Matsumoto.
- **Google Play** is a trademark of Google LLC.

All other trademarks are the property of their respective owners. The use of these trademarks in this document is for identification purposes only and does not imply endorsement.

---

## Contact

For questions about licenses, trademarks, or legal notices:

- **Email**: yudhi@rmyndharis.com
- **GitHub**: https://github.com/anthropics/vscodroid

---

_This document was last updated on February 13, 2026._
