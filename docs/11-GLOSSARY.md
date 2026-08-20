# Glossary

**Project**: VSCodroid
**Version**: 1.0-draft
**Date**: 2026-02-10

---

## Terms

### A

**AAB (Android App Bundle)**
Google's publishing format for Android apps. Unlike APK, AAB lets Google Play generate optimized APKs for each device configuration (screen density, CPU architecture, language). Reduces download size.

**ABI (Application Binary Interface)**
Specifies how binary code interacts at the machine level. VSCodroid targets `arm64-v8a` ABI — the 64-bit ARM architecture used by modern Android devices.

**ADR (Architecture Decision Record)**
A document that captures an important architectural decision along with its context and consequences. See [Architecture § ADRs](./03-ARCHITECTURE.md#4-key-architecture-decisions-adrs).

**Asset Pack (Play Asset Delivery)**
A mechanism for delivering additional assets with an Android App Bundle (AAB). VSCodroid uses on-demand asset packs to deliver its language toolchains, **Ruby and Java 17, those two** (`ToolchainRegistry.available`, `android/settings.gradle.kts`). Packs are downloaded when the user selects them in the toolchain picker during first-run, or later from the app icon's **Manage toolchains** launcher shortcut, which is the only route to that screen. Play handles that download only for installs that came from Play; every other install (sideload, debug build, `adb install`) fetches the same toolchains as ZIPs over HTTPS from this project's GitHub Releases and checks each one against the `toolchains.sha256` manifest the release publishes before installing. Either way the payload lands in `filesDir`, never in the APK, so an installed toolchain survives an app update. A toolchain whose compiler forks its own assembler and linker cannot be delivered this way at all: SELinux refuses `execve` under the app's data directory, and those forks are refused however the driver command is reached.

**adjustResize**
Android `windowSoftInputMode` flag. When the soft keyboard appears, the app window resizes (shrinks) to fit. Essential for VS Code to remain usable with the keyboard open.

**Android Bridge**
The `@JavascriptInterface`-annotated Kotlin object exposed to WebView JavaScript. Provides clipboard, file picker, and navigation APIs. See [API Spec § Android Bridge](./05-API_SPEC.md#2-a-android-bridge-api).

### C

**code-server**
An open-source project by Coder that runs VS Code in the browser. VSCodroid does **not** fork it and carries none of its patches; it was evaluated as a base and not used. The server VSCodroid ships is vanilla Code - OSS built from source, see the Code-OSS entry.

**Code-OSS**
The open-source (MIT-licensed) version of VS Code, at `github.com/microsoft/vscode`. Microsoft's "Visual Studio Code" product adds proprietary branding, telemetry, and marketplace access on top of Code-OSS. This source is what VSCodroid builds: `scripts/build-vscode-oss.sh` checks out the tag in `VSCODE_VERSION` at the commit in `VSCODE_COMMIT`, applies the numbered unified diffs in `patches/` with `git apply` and the overlay in `branding/product.json`, then runs gulp to produce `vscode-reh-web-linux-arm64`; app builds fetch that result with `scripts/fetch-vscode-oss.sh`. The pre-built server on Microsoft's update CDN is a different artifact under different terms that do not permit modifying and redistributing it, so `scripts/verify-server-tree.py` fails any tree whose `LICENSE.txt` is not the MIT one or that carries `node_modules/vsda`.

**Chrome Custom Tabs**
An Android component that opens web content in a lightweight Chrome-powered tab while keeping app context. VSCodroid uses it for GitHub OAuth login and consent flow.

**Cross-compilation**
Compiling code on one platform (e.g., x86_64 Linux or macOS) to produce binaries for a different platform (e.g., ARM64 Android). VSCodroid cross-compiles the native Node addons it needs for Bionic (`scripts/build-native-addons.sh`). The runtimes and CLI tools are not cross-compiled here at all, Node, Python, git, bash and the rest are taken pre-built from Termux packages by `scripts/download-*.sh`.

### E

**Extension Host**
The VS Code process/thread that runs extensions. It provides the `vscode.*` API namespace and manages extension lifecycle (activation, deactivation). In VSCodroid, it runs as a `worker_thread` instead of a child process.

**Extra Key Row**
A native Android View displayed above the soft keyboard, across five swipeable pages. Provides keys not available on standard mobile keyboards: Tab, Esc, Ctrl, Alt, Shift, brackets, symbols, and F1 to F12 with Home, End, PageUp and PageDown. Cursor movement comes from a gesture trackpad rather than from arrow buttons, which the row does not have; the trackpad emits arrow keys as you drag, and it is the only way a touch user moves the caret (`KeyPageConfig.kt`, `GestureTrackpad`).

### F

**Firebase Test Lab**
Google Cloud service for running Android instrumentation tests on real and virtual devices. VSCodroid does not use it, and no CI job runs the instrumented tests at all, `android/app/src/androidTest/README.md` records why, and names the moments a person is expected to run them by hand on a device.

**Foreground Service**
An Android Service that runs with a persistent notification and higher priority than background processes. Used to keep the Node.js server alive when the app is not in the foreground.

### J

**jniLibs**
Directory in an Android project where native `.so` (shared object) libraries are placed. Android Package Manager extracts these with execute permission, which is exploited by the ".so trick" to bundle executable binaries.

### L

**Language Server (LS)**
A separate process that provides language intelligence (autocomplete, diagnostics, go-to-definition) for a specific programming language. Follows the Language Server Protocol (LSP). Examples: `tsserver` (TypeScript), `pylsp` (Python).

**LSP (Language Server Protocol)**
A JSON-RPC protocol between an editor and a Language Server. Standardized by Microsoft. Enables language features without per-editor reimplementation.

**localhost**
The network loopback address (127.0.0.1). In VSCodroid, the Node.js server binds to localhost only — meaning only processes on the same device can connect. This is a key security property.

### M

**Monaco Editor**
The code editor component used by VS Code. Provides syntax highlighting, IntelliSense, multi-cursor editing, code folding, and more. Runs entirely in the browser/WebView.

### N

**NDK (Native Development Kit)**
Android's toolchain for compiling C/C++ code that runs on Android devices. Includes Clang compiler, linker, and headers for the Android platform. VSCodroid uses NDK r27+ for cross-compilation.

**node-pty**
A Node.js library that creates pseudo-terminal (PTY) pairs. Required by VS Code's terminal to provide a proper terminal emulation (colors, cursor movement, interactive programs).

**nodejs-mobile**
A project that embeds Node.js as an in-process library (shared object loaded via JNI). **Not used by VSCodroid** because it lacks `child_process.fork()`, `worker_threads`, and `node-pty` support.

### O

**Open VSX**
An open-source extension registry for VS Code-compatible editors, hosted by the Eclipse Foundation at open-vsx.org. VSCodroid uses Open VSX instead of Microsoft's Marketplace (which prohibits third-party access).

### P

**Phantom Process**
An Android concept (Android 12+). Any child process spawned by an app that is not part of the app's main process group. Android limits the total system-wide count to 32 and may SIGKILL excess phantom processes.

**product.json**
VS Code's product configuration file. Controls branding (name, icon), marketplace URLs, telemetry settings, and feature flags. VSCodroid overrides this to set "VSCodroid" branding and Open VSX URLs.

**PTY (Pseudo-Terminal)**
A pair of virtual character devices (master and slave) that emulate a terminal. The terminal emulator writes to the master; the shell reads from the slave. Required for interactive terminal sessions.

### R

**RPC (Remote Procedure Call)**
A protocol where one process calls functions in another process over a communication channel. VS Code uses RPC over WebSocket between the web client and the server.

### S

**SAF (Storage Access Framework)**
Android's mechanism for accessing files outside an app's private directory. Uses system file picker UI. An alternative to broad storage permissions.

**Sideloading**
Installing an APK outside Google Play (for example from GitHub Releases). There is no separate sideload variant, `android/app/build.gradle.kts` declares no product flavors, and no build bundles a toolchain. What differs is how toolchains arrive, `ToolchainManager` reads the installing package name at runtime and falls back from Play Asset Delivery to an HTTPS download from GitHub Releases when the installer was not Play.

**.so (Shared Object)**
Linux/Android equivalent of a DLL. In VSCodroid, the ".so trick" refers to naming executable binaries with a `.so` extension so they're placed in `jniLibs/` and extracted with execute permission.

**specialUse**
A foreground service type introduced in Android 14 (API 34). For foreground services that don't fit standard categories. Requires justification in Play Store console.

### T

**Termux**
A popular Android terminal emulator and Linux environment. It pioneered the technique of bundling Linux binaries on Android. VSCodroid is a separate, standalone app, but it does more than borrow the idea: the `scripts/download-*.sh` scripts fetch Termux's own `.deb` packages and install the binaries out of them, so Node, Python, git, bash, tmux, make and ssh are Termux builds. Those downloads are verified against Termux's own repository signing chain, a pinned key signs `InRelease`, which carries the digest of the package index, which carries the digest of each `.deb` (`scripts/verify-termux-index.sh`).

**tmux**
A terminal multiplexer. Allows multiple terminal sessions to run within a single process. VSCodroid bundles it as a standalone tool for terminal users, but does **not** wrap its editor terminals in it: the default profile written by `FirstRunSetup` points at bash, and each terminal spawns bash directly through node-pty on a real PTY. Phantom processes are held down by running the Extension Host and ptyHost as `worker_thread`s instead.

### V

**vscode-reh (Remote Extension Host)**
A VS Code build target. Produces the server component that runs the Extension Host, terminal service, file system service, and search service. Communicates with the web client over WebSocket. Readiness is `GET /version`, which the server answers before it checks the connection token, and only a `200` counts.

**vscode-web**
A VS Code build target. Produces the web client (HTML, JavaScript, CSS) that renders the VS Code Workbench UI in a browser or WebView. VSCodroid builds the `reh-web` target, which carries the client inside the server tree, so the app ships one directory, `assets/vscode-reh/`, and there is no separate `vscode-web` to copy.

**VSIX**
The file format for VS Code extensions. A ZIP archive containing the extension's code, manifest (package.json), and assets.

### W

**W^X (Write XOR Execute)**
A security policy where memory pages cannot be both writable and executable. The related restriction that shapes VSCodroid is a different one and worth naming separately: SELinux denies `execute_no_trans` on `app_data_file` for targetSdk ≥ 29, so a binary written into the app's own `filesDir` cannot be `execve`'d no matter what its mode bits say. The .so trick sidesteps that by having Android's package manager extract binaries into `nativeLibraryDir` at install time. `dlopen()` is not denied, which is why a `.node` addon can still be loaded out of `filesDir`.

**WebView**
Android's browser component. A View that displays web content within an app. Based on Chromium, updated via Google Play. VSCodroid uses WebView to render VS Code's web UI.

**worker_thread**
A Node.js feature that allows running JavaScript in separate threads within the same process. Unlike `child_process.fork()`, worker threads don't create new processes and therefore don't count as phantom processes on Android.

### X

**XSS (Cross-Site Scripting)**
A security vulnerability where malicious scripts are injected into web content. Mitigated in VSCodroid by VS Code's Content Security Policy (CSP) and WebView sandboxing.

---

## Acronyms Quick Reference

| Acronym | Full Form |
|---------|-----------|
| AAB | Android App Bundle |
| ABI | Application Binary Interface |
| ADD | Architecture Design Document |
| ADR | Architecture Decision Record |
| API | Application Programming Interface |
| APK | Android Package Kit |
| CI/CD | Continuous Integration / Continuous Delivery |
| CSP | Content Security Policy |
| LSP | Language Server Protocol |
| NDK | Native Development Kit |
| OOM | Out of Memory |
| PRD | Product Requirements Document |
| PTY | Pseudo-Terminal |
| RPC | Remote Procedure Call |
| SAF | Storage Access Framework |
| SDK | Software Development Kit |
| SRS | Software Requirements Specification |
| TLS | Transport Layer Security |
| UX | User Experience |
| VSIX | VS Code Extension Package |
| W^X | Write XOR Execute |
| WS | WebSocket |
