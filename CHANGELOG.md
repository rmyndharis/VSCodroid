# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- Contributing guide's repository map matches the shipped binaries again: the Python runtime never lived in `jniLibs` (and its version is not pinned), while git's HTTPS helper and the musl loader do live there and were missing
- README brought back in line with how the project actually builds and installs: local builds fetch the prebuilt server rather than needing Node and Yarn to build VS Code, SSH ships as the bundled OpenSSH client and `ssh-keygen` rather than a command-palette flow, toolchains install on sideloaded devices too (direct download) rather than Play-only, and the size table now carries figures measured from the release AAB
- Contributing guide: review findings that are not fixed in the same pull request now get an issue, and rejected ones a stated reason, so nothing is left referenced only by its position in a discussion
- **The VS Code server is now built from the MIT-licensed Code - OSS source** instead of downloading Microsoft's proprietary pre-built server, which could not legally be modified and redistributed inside an APK. The build applies this repository's patches and branding to readable source, is verified for tree shape, architecture and branding before it ships, and is published once per VS Code version
- VS Code upgraded 1.96.4 → 1.133.0
- Node.js runtime upgraded to 24.18.0, now taken from Termux's `nodejs-lts` package — the previous hand-cross-compiled 20.18.1 segfaulted inside several CLI tools
- Every bundled executable and shared library — the shell, git, tmux, make, ssh and the libraries they load — is now checked before packaging for the right architecture, dependencies that are actually present, and the page alignment Android 16 requires. Previously only the Node runtime and the native addons were, so a tool whose dependency had gone missing produced a successful build and an install where the terminal would not start
- The Python bundling step now fails the build when the interpreter's runtime library is absent, installs that runtime under the exact name the launcher links against, and runs the same architecture, dependency and page-alignment check as every other bundled binary — the one it did not yet cover. It previously printed a note and carried on, which could ship a build where `python` failed on first use with a missing-library error
- The downloadable Go and Ruby toolchains are now checked the same way before they are packaged: every binary they ship must be the right architecture, find the libraries it links, and carry the page alignment Android 16 requires. They reach devices through two channels and nothing had examined them, so a broken binary would have been packaged, uploaded and installed before anyone noticed it could not start
- The Java toolchain is now checked the same way, completing the set — every downloadable toolchain is examined before it is packaged

### Added
- **GitHub Copilot Chat now works on device**: the bundled extension's platform packages are aliased under the name Android resolves, its SDK entry ships again, and `@vscode/sqlite3` is rebuilt for Bionic so model selection completes end to end
- **Claude Code extension support**: the marketplace serves its musl build, the CLI starts through the bundled musl loader, and a loopback DNS proxy gives musl binaries working name resolution
- A glibc compatibility shim: prebuilt glibc-only native addons (spdlog, sqlite3 and friends) now load against Bionic through versioned forwarder stubs instead of dying at `dlopen`. It supplies what Bionic has no equivalent for — the `__isoc99_` scanf family, the `tolower`/`toupper` character tables, and `copy_file_range` on devices below Android 14 — and translates what the two libraries number differently, so `getaddrinfo` and `getnameinfo` answer the question the addon actually asked instead of a differently-numbered one
- On-demand toolchain downloads, the server tarball, npm, extensions and every bundled tool are now verified against the strongest digest their source publishes, and a missing or wrong digest fails the build instead of shipping unverified bytes

### Security
- The loopback DNS proxy that gives musl binaries working name resolution now requires a per-boot token. Binding to `127.0.0.1` is not access control on Android — loopback is not isolated per app — so any installed app could previously have used it as an open forwarder for arbitrary outbound connections attributed to VSCodroid
- A rejected tunnel request through that proxy no longer leaves a connection pinned open. Any app on the device could previously open them in a loop and hold file descriptors in VSCodroid's server for as long as it kept running — without a token, without reaching the network, and without leaving a trace in the log

### Fixed
- A file being unpacked from the app when something goes wrong — running out of storage, most likely — no longer leaves a half-written copy in place of the real one. The unpacking either completes or leaves what was there before, so a later start can retry instead of finding a file that looks present and is not usable
- Python stopped working after an app update in some cases: the interpreter ships inside the app and is replaced every time, while its runtime library and standard library are unpacked only when the version number changes, so the two could end up coming from different builds. The app now notices that at launch and repairs it, and clears out standard libraries left behind by earlier versions
- **Git over HTTPS now works.** Cloning, fetching and pulling from an `https://` remote failed on every device with "cannot exec 'git-remote-https'", because the helper git runs for HTTPS was installed where Android does not permit execution; it now ships beside the other bundled tools. Behind it a second failure waited — the bundled curl looked for its list of trusted certificate authorities at a path that does not exist here, so the connection was refused before any certificate was checked, and that list is now built from the device's own trust store
- **The Java toolchain could not start on devices with 16 KB memory pages.** A library the JDK core depends on was built for 4 KB pages, so `java` failed to load before it ran anything; it is now built correctly. Three further JDK libraries that could never load at all — their dependencies were never included — no longer ship
- **`go build` would have failed with a permission error in the next release.** The Go toolchain marks only the commands named in its manifest as executable, and the manifest named just `go` and `gofmt` — but `go` compiles nothing by itself, it runs the compiler, linker and assembler that ship beside it. Those arrived unrunnable. The manifest is now built from the toolchain itself, so every program it ships is installed ready to run
- **The Ruby toolchain was missing six of its commands**, `rake` among them — so `rake` and `bundle exec rake`, how most Ruby projects are built, answered "command not found", and the debugger `rdbg` was absent too. The toolchain shipped a list of commands fixed when it was written; it now installs whatever the Ruby release actually provides, so `rake`, `rdbg`, `rbs`, `racc`, `syntax_suggest` and `typeprof` are there, and anything added later arrives on its own
- Ruby's `fiddle` could never be loaded: the library it links was not part of the toolchain download, so anything reaching for it — directly or through a gem — failed at `require`. The library now ships with the toolchain
- Git subcommands pointed into a previous installation after the app was updated, and were repaired only on a fresh install
- An emergency port picked when the usual range is full is no longer remembered. It came from the range the kernel hands to outgoing connections, so a later launch would often find it taken and move the workbench to a new address — emptying secret storage and every extension's saved state, and never moving back once the congestion cleared
- Tunnelling to an IPv6 address through the local proxy failed: the target was split on every colon, so `[::1]:443` was read as a host named `[`. A malformed port in the same position could take the server down at startup instead of being refused
- Closing a tab or cancelling a download mid-transfer left the local proxy still pulling the rest of it from the network, with nothing on the other end to receive it. Each abandoned transfer held a connection open until the far side gave up
- The placeholder page shown before the editor is installed pointed at a build script that no longer exists
- Legal notices listed fixed versions for Node.js, Python, Bash, tmux and Make, none of which this repository pins — they come from the package index at build time, so the numbers had been wrong since the runtime changed
- Chat panels were unusable: the extra key row covered the bottom of the page — exactly where VS Code anchors the chat toolbar — so the model picker and Send button could be seen but never tapped
- Claude Code sign-in died with "Socket is closed": Node abandoned each connection attempt after 250 ms, which the API's handshake regularly exceeded from a phone
- Prebuilt glibc native addons could not load on Android 13 at all: the compatibility library referenced a symbol that does not exist before Android 14, so the loader rejected the library itself on the minimum supported version
- The glibc shim's ctype table misclassified five of twelve character classes, and its `environ`/`stdout`/`stderr` exports loaded as NULL
- Two app instances could run first-run setup concurrently; setup is now single-flight
- Bundled extensions updated by an app upgrade are visible again after the manifest is reconciled, and uninstalling one now sticks across upgrades
- The web walkthrough greets users with VSCodroid branding again, and the hamburger menu returned to touch-friendly sizing — both regressions from the build pivot
- Native terminal and file-watcher addons are built from the same versions as the JavaScript they ship beside, and the build now fails on any mismatch
- Terminal profile picker was empty, leaving no way to switch terminals ([#3](https://github.com/rmyndharis/VSCodroid/issues/3))
- App froze and had to be force-restarted after the server process was killed — automatic recovery never actually ran
- A server restart now returns to the folder you had open instead of the default projects directory
- A WebView rebuilt after a renderer crash no longer comes back without its Android bridge
- Launching no longer crashes outright if refreshing tool paths fails
- Comments and formatting in `settings.json` now survive the refresh of bundled tool paths
- Build and release workflows no longer fail when the runner's package index is out of date
- Cold start no longer crashes while the WebView still shows its placeholder URL — thanks [@4in4in](https://github.com/4in4in) for the fix ([#6](https://github.com/rmyndharis/VSCodroid/pull/6))

## [1.0.0] - 2026-04-21

### 🎉 First Production Release on Google Play Store!

VSCodroid is now publicly available on Google Play. This release represents the cumulative work across milestones M0–M6, bringing a full VS Code IDE experience to Android.

### Added
- CI/CD pipeline: test job in CI, tag-triggered release workflow, GitHub Pages deployment
- Privacy policy hosted on GitHub Pages
- "VSCodroid: About" command in command palette with version info and legal links
- Third-party attribution file (NOTICE.md)
- User guide documentation
- Full changelog with milestone history

### Fixed
- Edge-to-edge display: upgrade AGP 8.9.1 + Activity 1.12.4 + Core 1.16.0 for proper edge-to-edge support
- Material library updated to 1.14.0-alpha09 to resolve edge-to-edge warnings
- Remove deprecated edge-to-edge theme attributes and fitsSystemWindows from layouts

### Changed
- Google Play production access granted — app now publicly available

## [0.1.0-m0] - 2026-02-10

This release represents the cumulative work across milestones M0 through M5, bringing VSCodroid from initial project structure to a fully functional IDE on Android.

### M5: Quick Wins & Developer Experience
- SSH key management: generate ed25519 keys and copy public key from command palette
- "Open in Browser" command for previewing localhost dev servers (Vite, NestJS, etc.)
- Selective `platform-fix.js` preload for npm/node-gyp compatibility (no longer breaks Rollup/esbuild)
- Enhanced process monitor with tiered warnings, kill idle servers command, and storage display
- Bundled debug launch configurations (Attach to Node.js, NestJS Debug, Run Current File)
- `diffEditor.wordWrap` enabled by default
- `npm --prefer-offline` for faster installs

### M4: Polish & Stability
- On-demand toolchains via Play Asset Delivery (Go, Ruby, Java)
- Language Picker UI for first-run toolchain selection
- Toolchain settings screen for install/remove management
- npm 10.8.2 bundled with bash shell functions (noexec workaround)
- Python 3.12.12 bundled from Termux with full stdlib and pip
- Welcome walkthrough extension
- OAuth flow for GitHub authentication via Chrome Custom Tabs
- Storage management: breakdown display, cache clearing
- Crash reporter with bug report generation
- AAPT `ignoreAssetsPattern` fix for underscore-prefixed directories

### M3: SAF & Extensions
- SAF (Storage Access Framework) integration for opening device folders
- SAF two-way sync with file watcher for external storage
- Bundled extensions: One Dark Pro, ESLint, Prettier, Tailwind CSS, GitLens, Python
- Extension version pinning for VS Code 1.96.4 compatibility
- Process monitor extension with status bar indicator and phantom process tree

### M2: Terminal & Mobile UX
- Native node-pty (cross-compiled for ARM64 Android) replacing pipeTerminal.js shim
- Real PTY terminals via `/dev/pts/*` — vim, tmux, readline, colors, job control all work
- Extra Key Row with Ctrl, Alt, Tab, Esc, arrows, brackets, parens, semicolons
- Touch target enlargement CSS for phone-sized screens
- Safe area padding for round-corner devices and display cutouts
- WebView crash recovery with folder context restoration
- Back button navigation integration
- ptyHost as worker_thread (saves phantom process slot)
- Stale symlink detection and recreation on APK reinstall

### M1: Extension Host & Process Management
- Extension Host converted from child_process.fork() to worker_thread
- Phantom process monitor scanning by UID across all processes
- Memory pressure signal path: Kotlin onTrimMemory to process-monitor.js
- Idle language server cleanup (5-minute timeout)
- BroadcastChannel relay for browser extension access to AndroidBridge

### M0: Foundation
- VS Code 1.96.4 Web Client + Server running locally on Android
- Pre-built VS Code Server from Microsoft CDN with Android-specific patches
- Node.js 20.18.1 cross-compiled for ARM64 Android (48 MB libnode.so)
- vsda signing bypass (regex-replace signService.validate with Promise.resolve)
- Native module shims for spdlog and native-watchdog
- CDN URL interception in WebViewClient (rewrite vscode-cdn.net to localhost)
- Webview service worker disabled (Android WebView lifecycle incompatibility)
- Browser extension stubs for 17 built-in extensions
- Workspace Trust bypass for local remote connections
- process.platform "android" → "linux" patching (5 pattern types in minified code)
- product.json branding (VSCodroid, Open VSX marketplace)
- Foreground Service with specialUse for server persistence
- Bundled tools: Bash 5.3.9, Git 2.53.0, tmux 3.6a, Make 4.4.1, OpenSSH, ripgrep
- Open VSX extension marketplace integration
- SSL certificate configuration for HTTPS in Node.js
- Git path configuration for VS Code Git extension
- Health check polling for server readiness
- Android intent handling for "Open with VSCodroid"

[Unreleased]: https://github.com/rmyndharis/VSCodroid/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/rmyndharis/VSCodroid/compare/v0.1.0-m0...v1.0.0
[0.1.0-m0]: https://github.com/rmyndharis/VSCodroid/releases/tag/v0.1.0-m0
