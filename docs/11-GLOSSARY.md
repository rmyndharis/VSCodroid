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
A mechanism for delivering additional assets with an Android App Bundle (AAB). VSCodroid uses on-demand asset packs to deliver language toolchains (Go, Rust, Java, C/C++, Ruby). On-demand packs are downloaded when the user selects them in the Language Picker during first-run or via Settings > Toolchains. Play Store handles the download automatically.

**adjustResize**
Android `windowSoftInputMode` flag. When the soft keyboard appears, the app window resizes (shrinks) to fit. Essential for VS Code to remain usable with the keyboard open.

**Android Bridge**
The `@JavascriptInterface`-annotated Kotlin object exposed to WebView JavaScript. Provides clipboard, file picker, and navigation APIs. See [API Spec § Android Bridge](./05-API_SPEC.md#2-a-android-bridge-api).

### C

**code-server**
An open-source project by Coder that runs VS Code in the browser. VSCodroid forks code-server to leverage its patch system and web-serving layer.

**Code-OSS**
The open-source (MIT-licensed) version of VS Code. Microsoft's "Visual Studio Code" product adds proprietary branding, telemetry, and marketplace access on top of Code-OSS.

**Chrome Custom Tabs**
An Android component that opens web content in a lightweight Chrome-powered tab while keeping app context. VSCodroid uses it for GitHub OAuth login and consent flow.

**Cross-compilation**
Compiling code on one platform (e.g., x86_64 Linux) to produce binaries for a different platform (e.g., ARM64 Android). Required because Android devices can't compile large projects like Node.js locally.

### E

**Extension Host**
The VS Code process/thread that runs extensions. It provides the `vscode.*` API namespace and manages extension lifecycle (activation, deactivation). In VSCodroid, it runs as a `worker_thread` instead of a child process.

**Extra Key Row**
A native Android View displayed above the soft keyboard. Provides keys not available on standard mobile keyboards: Tab, Esc, Ctrl, Alt, arrow keys, brackets, semicolons.

### F

**Firebase Test Lab**
Google Cloud service for running Android instrumentation tests on real and virtual devices. VSCodroid uses physical ARM64 device runs in CI for compatibility and regression checks.

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
Installing an APK outside Google Play (for example from GitHub Releases). In VSCodroid, sideload builds can bundle additional toolchains and may expose advanced package-manager workflows not available in the Play Store variant.

**.so (Shared Object)**
Linux/Android equivalent of a DLL. In VSCodroid, the ".so trick" refers to naming executable binaries with a `.so` extension so they're placed in `jniLibs/` and extracted with execute permission.

**specialUse**
A foreground service type introduced in Android 14 (API 34). For foreground services that don't fit standard categories. Requires justification in Play Store console.

### T

**Termux**
A popular Android terminal emulator and Linux environment. It pioneered the technique of bundling Linux binaries on Android. VSCodroid takes inspiration from Termux's approach (cross-compilation recipes, .so trick) but is a separate, standalone app.

**tmux**
A terminal multiplexer. Allows multiple terminal sessions to run within a single process. VSCodroid uses tmux to minimize phantom processes (1 tmux process instead of N bash processes).

### V

**vscode-reh (Remote Extension Host)**
A VS Code build target. Produces the server component that runs the Extension Host, terminal service, file system service, and search service. Communicates with the web client over WebSocket.

**vscode-web**
A VS Code build target. Produces the web client (HTML, JavaScript, CSS) that renders the VS Code Workbench UI in a browser or WebView.

**VSIX**
The file format for VS Code extensions. A ZIP archive containing the extension's code, manifest (package.json), and assets.

### W

**W^X (Write XOR Execute)**
A security policy where memory pages cannot be both writable and executable. Android 10+ enforces W^X, which means you cannot download a binary, write it to storage, and then execute it. The .so trick circumvents this by having Android's package manager extract binaries with execute permission at install time.

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
