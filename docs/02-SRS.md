# Software Requirements Specification (SRS)

**Project**: VSCodroid
**Version**: 1.0-draft
**Date**: 2026-02-10
**Standard Reference**: IEEE 830 (adapted)

---

## 1. Introduction

### 1.1 Purpose

This document specifies the functional and non-functional requirements for VSCodroid, a port of Visual Studio Code to Android. It serves as the contract between product vision and implementation.

### 1.2 Scope

VSCodroid is a standalone Android application that runs VS Code locally on Android devices. It consists of a Kotlin native shell, a WebView-based VS Code frontend, and a Node.js-based VS Code server backend.

### 1.3 Definitions

See [Glossary](./11-GLOSSARY.md).

---

## 2. Overall Description

### 2.1 Product Perspective

VSCodroid is a standalone Android application that brings VS Code to mobile devices. It is built by forking [code-server](https://github.com/coder/code-server), which patches [Code-OSS](https://github.com/microsoft/vscode) (the MIT-licensed VS Code source) to run as a web server. VSCodroid adds a native Android shell (Kotlin) that hosts the VS Code web client in a WebView and manages a bundled Node.js process that runs the VS Code server — all on localhost.

VSCodroid is NOT a cloud IDE, a Termux wrapper, or a custom editor. It is the actual VS Code codebase running locally on the device.

### 2.2 Product Functions (High-Level)

1. **Code Editing** — Full VS Code Workbench with Monaco Editor
2. **Extension Support** — Install and run extensions from Open VSX
3. **Integrated Terminal** — Bash shell with Node.js, Python, and Git
4. **Mobile UX** — Extra Key Row, clipboard bridge, touch optimization
5. **Dev Environment** — Bundled toolchains + on-demand downloads
6. **Source Control** — Git integration via SCM panel and CLI

### 2.3 User Characteristics

- **Primary users**: Software developers who code on Android devices (phone or tablet)
- **Technical level**: Intermediate to advanced (familiar with VS Code, terminal, git)
- **Usage context**: Commuting, traveling, or using Android as primary dev device
- See [PRD § User Personas](./01-PRD.md#5-user-personas) for detailed profiles

### 2.4 Constraints

- ARM64 Android only (API 33+)
- All binaries must be bundled as .so in APK (Android W^X enforcement)
- Open VSX only — Microsoft Marketplace ToS prohibits third-party access
- Android phantom process limit (32 system-wide) constrains child process count
- See [Section 5: System Constraints](#5-system-constraints) for detailed list

### 2.5 Assumptions and Dependencies

| Assumption | Impact if Wrong |
|-----------|----------------|
| Android WebView (Chrome 105+) supports all VS Code UI features | May need to bundle Chromium (~100MB more) |
| Termux Node.js patches apply to current LTS | May need to create patches from scratch |
| Open VSX has sufficient extension coverage | Users may be frustrated by missing extensions |
| code-server patch system remains stable across VS Code updates | Rebase effort increases significantly |
| 4GB RAM devices can run VS Code server + WebView | May need to raise minimum requirement |
| Google Play Store allows .so-bundled binaries | Need alternative distribution (GitHub, F-Droid) |

---

## 3. Functional Requirements

### 3.1 Core Editor (FR-EDIT)

| ID | Requirement | Priority | Milestone |
|----|------------|----------|-----------|
| FR-EDIT-01 | System SHALL render VS Code Workbench UI in Android WebView | P0 | M1 |
| FR-EDIT-02 | System SHALL support Monaco Editor features: syntax highlighting, multi-cursor, auto-complete, code folding | P0 | M1 |
| FR-EDIT-03 | System SHALL support opening, editing, and saving text files | P0 | M1 |
| FR-EDIT-04 | System SHALL support multiple editor tabs with tab management | P0 | M1 |
| FR-EDIT-05 | System SHALL support Command Palette (Ctrl+Shift+P) | P0 | M1 |
| FR-EDIT-06 | System SHALL support Quick Open (Ctrl+P) file navigation | P0 | M1 |
| FR-EDIT-07 | System SHALL support Find & Replace with regex support | P0 | M1 |
| FR-EDIT-08 | System SHALL support file encoding detection and conversion (UTF-8 default) | P1 | M1 |
| FR-EDIT-09 | System SHALL support split editor panes | P2 | M2 |

### 3.2 File System (FR-FS)

| ID | Requirement | Priority | Milestone |
|----|------------|----------|-----------|
| FR-FS-01 | System SHALL provide a file explorer panel showing workspace files | P0 | M1 |
| FR-FS-02 | System SHALL support create, rename, delete, move operations on files and folders | P0 | M1 |
| FR-FS-03 | System SHALL use app-private storage as default workspace directory | P0 | M0 |
| FR-FS-04 | System SHALL support opening folders as workspace root | P0 | M1 |
| FR-FS-05 | System SHALL support multi-root workspaces | P2 | M2 |
| FR-FS-06 | System SHOULD support accessing files from external storage via SAF | P2 | M4 |
| FR-FS-07 | System SHALL handle file watching for external changes | P1 | M1 |

### 3.3 Terminal (FR-TERM)

| ID | Requirement | Priority | Milestone |
|----|------------|----------|-----------|
| FR-TERM-01 | System SHALL provide integrated terminal with bash shell | P0 | M1 |
| FR-TERM-02 | System SHALL support multiple terminal sessions (via tmux) | P0 | M1 |
| FR-TERM-03 | System SHALL support terminal input/output with ANSI colors | P0 | M1 |
| FR-TERM-04 | System SHALL provide Node.js accessible from terminal | P0 | M1 |
| FR-TERM-05 | System SHALL provide Python 3 accessible from terminal | P1 | M3 |
| FR-TERM-06 | System SHALL provide Git accessible from terminal | P0 | M1 |
| FR-TERM-07 | Terminal SHALL support copy/paste via Android clipboard | P1 | M2 |
| FR-TERM-08 | Terminal SHALL support resizing when screen orientation changes | P1 | M2 |

### 3.4 Extensions (FR-EXT)

| ID | Requirement | Priority | Milestone |
|----|------------|----------|-----------|
| FR-EXT-01 | System SHALL connect to Open VSX marketplace for extension discovery | P0 | M1 |
| FR-EXT-02 | System SHALL support installing extensions from Open VSX | P0 | M1 |
| FR-EXT-03 | System SHALL support extension activation and lifecycle management | P0 | M1 |
| FR-EXT-04 | System SHALL run Extension Host via child_process.fork() initially (M1), migrating to worker_thread in M4 for phantom process optimization | P0 | M1 (fork), M4 (worker_thread) |
| FR-EXT-05 | System SHALL support theme extensions (color themes, icon themes) | P0 | M1 |
| FR-EXT-06 | System SHALL support language extensions (syntax, snippets, LSP) | P0 | M1 |
| FR-EXT-07 | System SHALL support extension settings and configuration | P1 | M1 |
| FR-EXT-08 | System SHALL bundle essential extensions for offline use | P2 | M3 |
| FR-EXT-09 | System SHALL limit concurrent Language Servers to 2-3 | P1 | M4 |

### 3.5 Source Control (FR-SCM)

| ID | Requirement | Priority | Milestone |
|----|------------|----------|-----------|
| FR-SCM-01 | System SHALL display git status in SCM panel | P0 | M1 |
| FR-SCM-02 | System SHALL support stage, commit, and branch operations via SCM panel | P1 | M1 |
| FR-SCM-03 | System SHALL support git diff inline in editor | P1 | M1 |
| FR-SCM-04 | System SHOULD support GitHub OAuth for push/pull | P2 | M4 |
| FR-SCM-05 | System SHOULD support SSH key generation and management | P2 | M4 |

### 3.6 Mobile UX (FR-MUX)

| ID | Requirement | Priority | Milestone |
|----|------------|----------|-----------|
| FR-MUX-01 | System SHALL display Extra Key Row above soft keyboard with: Tab, Esc, Ctrl, Alt, arrows, brackets | P1 | M2 |
| FR-MUX-02 | Extra Key Row Ctrl/Alt keys SHALL act as toggles (tap to activate, tap again to deactivate) | P1 | M2 |
| FR-MUX-03 | System SHALL inject key events from Extra Key Row into WebView | P1 | M2 |
| FR-MUX-04 | Extra Key Row SHALL show/hide based on soft keyboard visibility | P1 | M2 |
| FR-MUX-05 | System SHALL bridge Android clipboard to VS Code clipboard service | P1 | M2 |
| FR-MUX-06 | System SHALL handle Android back button: close panels → close dialogs → minimize app | P1 | M2 |
| FR-MUX-07 | System SHALL support portrait and landscape orientations | P1 | M2 |
| FR-MUX-08 | System SHALL support split-screen / multi-window mode | P2 | M2 |
| FR-MUX-09 | System SHALL disable WebView zoom (prevent accidental pinch-zoom) | P0 | M2 |
| FR-MUX-10 | System SHALL register intent filter for common code file types (limited to app-private storage in M2; full external storage via SAF in M4) | P1 | M2 |

### 3.7 Dev Environment (FR-DEV)

| ID | Requirement | Priority | Milestone |
|----|------------|----------|-----------|
| FR-DEV-01 | System SHALL bundle Node.js + npm in APK | P0 | M0 |
| FR-DEV-02 | System SHALL bundle Python 3 + pip in APK | P1 | M3 |
| FR-DEV-03 | System SHALL bundle Git in APK | P0 | M1 |
| FR-DEV-04 | System SHALL bundle bash in APK | P0 | M1 |
| FR-DEV-04a | System SHALL bundle tmux in APK for terminal multiplexing | P1 | M1 |
| FR-DEV-05 | System SHALL deliver Go, Rust, Java, C/C++, Ruby toolchains as on-demand asset packs via Play Store | P2 | M3 |
| FR-DEV-06 | System SHALL provide package manager CLI (`vscodroid pkg`) | P2 | M3 |
| FR-DEV-07 | System SHALL detect file types and prompt to download relevant toolchain if not installed | P3 | M3 |
| FR-DEV-08 | System SHALL provide Language Picker UI during first-run for selecting toolchains to install | P1 | M3 |
| FR-DEV-09 | System SHALL allow installing additional toolchains later via Settings > Toolchains | P2 | M3 |

### 3.8 Application Lifecycle (FR-LIFE)

| ID | Requirement | Priority | Milestone |
|----|------------|----------|-----------|
| FR-LIFE-01 | System SHALL run Node.js server via Foreground Service | P0 | M0 |
| FR-LIFE-02 | System SHALL auto-restart Node.js if process dies | P0 | M0 |
| FR-LIFE-03 | System SHALL recover from WebView renderer crash | P1 | M2 |
| FR-LIFE-04 | System SHALL preserve editor state across app restarts | P1 | M2 |
| FR-LIFE-05 | System SHALL handle low-memory signals from Android | P1 | M4 |
| FR-LIFE-06 | System SHALL show first-run setup progress | P1 | M3 |
| FR-LIFE-07 | System SHALL handle configuration changes (rotation) without losing state | P1 | M2 |
| FR-LIFE-08 | System SHALL monitor phantom process count and warn user when approaching limits | P1 | M4 |
| FR-LIFE-09 | System SHALL provide in-app resource guidance (e.g., "Close unused terminals to save resources") | P2 | M4 |

---

## 4. Non-Functional Requirements

### 4.1 Performance (NFR-PERF)

| ID | Requirement | Target | Priority |
|----|------------|--------|----------|
| NFR-PERF-01 | Cold start to editor ready | < 5 seconds (mid-range device) | P1 |
| NFR-PERF-02 | Warm start (resume from background) | < 2 seconds | P1 |
| NFR-PERF-03 | Keystroke latency in editor | < 50ms | P0 |
| NFR-PERF-04 | File open (< 1MB file) | < 1 second | P0 |
| NFR-PERF-05 | Extension install + activate | < 30 seconds | P1 |
| NFR-PERF-06 | Terminal command response | < 100ms input echo | P0 |
| NFR-PERF-07 | First-run binary extraction | < 15 seconds | P1 |

### 4.2 Resource Usage (NFR-RES)

| ID | Requirement | Target | Priority |
|----|------------|--------|----------|
| NFR-RES-01 | RAM usage (typical coding session) | < 700 MB | P1 |
| NFR-RES-02 | RAM usage (4GB device minimum) | Functional without OOM | P0 |
| NFR-RES-03 | Phantom process count | ≤ 5 in typical use | P0 |
| NFR-RES-04 | AAB base download size | < 200 MB (core); toolchains 20-100 MB each (on-demand) | P1 |
| NFR-RES-05 | Runtime storage (core extracted) | < 400 MB; up to ~800 MB with all toolchains | P1 |
| NFR-RES-06 | Battery drain during active session | < 15% per hour | P2 |
| NFR-RES-06a | Battery drain during idle session (foreground, no input) | < 5% per hour | P2 |
| NFR-RES-07 | V8 heap limit | max-old-space-size=512MB | P1 |

### 4.3 Reliability (NFR-REL)

| ID | Requirement | Target | Priority |
|----|------------|--------|----------|
| NFR-REL-01 | Crash-free sessions | ≥ 95% | P0 |
| NFR-REL-02 | No data loss on crash | 100% (auto-save) | P0 |
| NFR-REL-03 | Node.js recovery from kill | < 3 seconds auto-restart | P0 |
| NFR-REL-04 | WebView crash recovery | < 5 seconds | P1 |
| NFR-REL-05 | Continuous use without crash | ≥ 2 hours | P0 |

### 4.4 Compatibility (NFR-COMPAT)

| ID | Requirement | Target | Priority |
|----|------------|--------|----------|
| NFR-COMPAT-01 | Minimum Android version | 13 (API 33) | P0 |
| NFR-COMPAT-02 | Target Android version | 16 (API 36) | P0 |
| NFR-COMPAT-03 | Architecture | arm64-v8a only | P0 |
| NFR-COMPAT-04 | Minimum WebView version | Chrome 105+ | P0 |
| NFR-COMPAT-05 | Device compatibility | Pixel, Samsung, Xiaomi, OnePlus tested | P1 |
| NFR-COMPAT-06 | Screen sizes | Phone (5-7"), Tablet (8-13") | P1 |
| NFR-COMPAT-07 | Input methods | Soft keyboard, hardware keyboard, Extra Key Row | P1 |

### 4.5 Security (NFR-SEC)

| ID | Requirement | Target | Priority |
|----|------------|--------|----------|
| NFR-SEC-01 | All binaries signed in APK | Verified at install | P0 |
| NFR-SEC-02 | No telemetry sent to external services | Microsoft telemetry stripped | P0 |
| NFR-SEC-03 | Server listens on localhost only | No external network exposure | P0 |
| NFR-SEC-04 | App-private storage for workspace | Android sandbox enforced | P0 |
| NFR-SEC-05 | All binaries delivered via Play Store (Play Store version) | Core as .so in base APK; toolchains as on-demand asset packs via Language Picker. Sideload version additionally supports vscodroid pkg from Termux repos | P0 |
| NFR-SEC-06 | Extension sandbox | Extensions run in Extension Host only | P1 |

### 4.6 Usability (NFR-USE)

| ID | Requirement | Target | Priority |
|----|------------|--------|----------|
| NFR-USE-01 | Time to first edit (new user) | < 30 seconds from app open | P1 |
| NFR-USE-02 | Discoverable Extra Key Row | Visible and intuitive without tutorial | P1 |
| NFR-USE-03 | Standard VS Code keybindings | All common shortcuts work via Extra Key Row | P1 |
| NFR-USE-04 | Consistent with desktop VS Code behavior | Familiar to VS Code users | P0 |

### 4.7 Legal & Compliance (NFR-LEGAL)

| ID | Requirement | Priority |
|----|------------|----------|
| NFR-LEGAL-01 | MIT license compliance for Code-OSS source | P0 |
| NFR-LEGAL-02 | No Microsoft trademarks in app name or icon | P0 |
| NFR-LEGAL-03 | Disclaimer in About screen and Play Store listing | P0 |
| NFR-LEGAL-04 | Privacy policy published and linked in app | P0 |
| NFR-LEGAL-05 | Open VSX only (not Microsoft Marketplace) | P0 |
| NFR-LEGAL-06 | Google Play Store policy compliance (binary execution) | P0 |

### 4.8 Accessibility (NFR-A11Y)

| ID | Requirement | Target | Priority |
|----|------------|--------|----------|
| NFR-A11Y-01 | Native UI elements (Extra Key Row, dialogs) SHALL have content descriptions | TalkBack compatible | P2 |
| NFR-A11Y-02 | Touch targets SHALL meet minimum size | 48dp x 48dp (Android guideline) | P2 |
| NFR-A11Y-03 | App SHALL support system font scaling for native UI | Respect Android display settings | P2 |

---

## 5. System Constraints

### 5.1 Android Platform Constraints

| Constraint | Details |
|-----------|---------|
| W^X enforcement (API 29+) | Cannot write then execute files. Must use .so bundling trick |
| Phantom process limit (API 31+) | Max 32 system-wide. Must minimize child processes |
| Foreground Service restrictions (API 34+) | Must declare specialUse type with justification |
| 16KB page alignment (API 36) | All native binaries must be compiled with 16KB page support |
| Scoped storage (API 30+) | App has limited access to external storage without SAF or permissions |

### 5.2 WebView Constraints

| Constraint | Details |
|-----------|---------|
| System WebView version | Depends on user's device, minimum Chrome 105 |
| No SharedWorker support | Some VS Code features may be limited |
| localStorage quota | ~10MB per origin, sufficient for settings |
| WebView renderer crashes | Independent of app process, must handle recovery |

### 5.3 Resource Constraints

| Constraint | Details |
|-----------|---------|
| RAM on low-end devices | 4GB devices must work without OOM |
| Storage on 64GB devices | Total installation < 500MB for core |
| CPU throttling | Android may throttle background processes |
| Battery optimization | Doze mode may affect background server |

---

## 6. Interface Requirements

### 6.1 User Interfaces

Detailed in [API Spec § Android Bridge API](./05-API_SPEC.md#2-android-bridge-api).

- Android native: Extra Key Row, status bar, Foreground Service notification
- WebView: VS Code Workbench (as-is from code-server build)

### 6.2 Software Interfaces

| Interface | Type | Description |
|-----------|------|-------------|
| WebView ↔ VS Code Server | HTTP + WebSocket | Localhost-only, standard VS Code remote protocol |
| Kotlin ↔ WebView | JavascriptInterface | Bridge for clipboard, file picker, navigation |
| Kotlin ↔ Node.js | Process stdin/stdout + HTTP | Process management and health check |
| VS Code ↔ Open VSX | HTTPS REST API | Extension search, download, metadata |
| VS Code ↔ GitHub | HTTPS + OAuth | Source control push/pull |

### 6.3 Hardware Interfaces

| Interface | Description |
|-----------|-------------|
| Touchscreen | Primary input method |
| Soft keyboard | Text input with Extra Key Row integration |
| Hardware keyboard | Full keyboard support (Bluetooth, USB) |
| Display | Phone (5-7") and tablet (8-13") screens |

---

## 7. Data Requirements

### 7.1 Persistent Data

| Data | Location | Backup |
|------|----------|--------|
| User workspace files | App-private storage or SAF-accessed external | User responsibility |
| VS Code settings | App-private storage / .vscodroid/ | Sync via Settings Sync extension |
| Installed extensions | App-private storage | Re-downloadable |
| Git config & SSH keys | App-private storage ~/ | User responsibility |
| On-demand toolchains | App-private storage | Re-downloadable |

### 7.2 Cache Data

| Data | Location | Clearable |
|------|----------|-----------|
| WebView cache | WebView data directory | Yes |
| Extension marketplace cache | App cache directory | Yes |
| Node.js module cache | App-private node_modules | Yes |

---

## 8. Acceptance Criteria

### M0 (POC) Acceptance

- [ ] Node.js ARM64 binary executes on Android device
- [ ] Express server responds on localhost from Node.js
- [ ] WebView loads and renders content from localhost
- [ ] WebSocket bidirectional communication works
- [ ] Foreground Service keeps Node.js alive when app is backgrounded

### M1 (Core) Acceptance

- [ ] VS Code Workbench UI renders completely in WebView
- [ ] Can type code with syntax highlighting
- [ ] Can install an extension from Open VSX
- [ ] Extension activates and functions (theme applies, linter runs)
- [ ] Terminal opens with bash, `node --version` works
- [ ] tmux terminal multiplexing works (multiple sessions via single process)
- [ ] File explorer shows files, create/edit/save works
- [ ] Git status displays in SCM panel

### M2 (Mobile UX) Acceptance

- [ ] Extra Key Row visible above soft keyboard
- [ ] Ctrl+S, Ctrl+P, Ctrl+Shift+P work via Extra Key Row
- [ ] Copy/paste works between VSCodroid and other apps
- [ ] App works in portrait, landscape, split-screen
- [ ] App recovers from WebView crash within 5 seconds
- [ ] Android back button navigates correctly

### M3 (Dev Env) Acceptance

- [ ] `python3 --version` and `pip install requests` work in terminal
- [ ] `git clone` and `git push` work in terminal
- [ ] On-demand toolchain install works (test: Go)
- [ ] Pre-bundled extensions available without internet

### M4 (Polish) Acceptance

- [ ] Cold start < 5 seconds on Pixel 7
- [ ] No crash in 2 hours continuous use
- [ ] Works on 4GB RAM device
- [ ] Phantom processes ≤ 5 in typical use
- [ ] Extension Host migrated to worker_thread (reduces phantom count by 1)
- [ ] Phantom process monitoring UI warns user when approaching limits
- [ ] GitHub OAuth push/pull works
- [ ] Tested on 4 device models (see Testing Strategy §7 compatibility matrix)

### M5 (Release) Acceptance

- [ ] Published on Play Store
- [ ] Passes Play Store policy review
- [ ] AAB download < 200MB
- [ ] No critical bugs in 48-hour beta
