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

VSCodroid is a standalone Android application that brings VS Code to mobile devices. It is built from [Code - OSS](https://github.com/microsoft/vscode) (the MIT-licensed VS Code source) directly: `scripts/build-vscode-oss.sh` clones that source at the commit pinned in `VSCODE_COMMIT` (the tag it belonged to is recorded in `VSCODE_VERSION`), applies the unified diffs in `patches/`, and builds the `vscode-reh-web-linux-arm64` target, which serves the workbench over HTTP and WebSocket. VSCodroid adds a native Android shell (Kotlin) that hosts the VS Code web client in a WebView and manages a bundled Node.js process that runs the VS Code server, all on localhost.

Building from source rather than adapting a pre-built server is a licence constraint, not a preference: the server artifact on Microsoft's update CDN is published under terms that do not permit modifying and redistributing it, so `scripts/verify-server-tree.py` fails any tree whose `LICENSE.txt` is not the MIT one. [code-server](https://github.com/coder/code-server) was evaluated as a base and is not used; none of its patches are carried here.

VSCodroid is NOT a cloud IDE, a Termux wrapper, or a custom editor. It is the actual VS Code codebase running locally on the device.

### 2.2 Product Functions (High-Level)

1. **Code Editing**: Full VS Code Workbench with Monaco Editor
2. **Extension Support**: Install and run extensions from Open VSX
3. **Integrated Terminal**: Bash shell with Node.js, Python, and Git
4. **Mobile UX**: Extra Key Row, clipboard bridge, touch optimization
5. **Dev Environment**: Bundled toolchains + on-demand downloads
6. **Source Control**: Git integration via SCM panel and CLI

### 2.3 User Characteristics

- **Primary users**: Software developers who code on Android devices (phone or tablet)
- **Technical level**: Intermediate to advanced (familiar with VS Code, terminal, git)
- **Usage context**: Commuting, traveling, or using Android as primary dev device
- See [PRD § User Personas](./01-PRD.md#5-user-personas) for detailed profiles

### 2.4 Constraints

- ARM64 Android only (API 33+)
- All binaries must be bundled as .so in APK (Android W^X enforcement)
- Open VSX only: Microsoft Marketplace ToS prohibits third-party access
- Android phantom process limit (32 system-wide) constrains child process count
- See [Section 5: System Constraints](#5-system-constraints) for detailed list

### 2.5 Assumptions and Dependencies

| Assumption | Impact if Wrong |
|-----------|----------------|
| Android WebView (Chrome 105+) supports all VS Code UI features | May need to bundle Chromium (~100MB more) |
| Termux Node.js patches apply to current LTS | May need to create patches from scratch |
| Open VSX has sufficient extension coverage | Users may be frustrated by missing extensions |
| The diffs in `patches/` keep applying across VS Code updates | Rebase effort increases significantly |
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
| FR-FS-03 | System SHALL default the workspace to `filesDir/projects`, internal storage, on a new install, and SHALL keep `getExternalFilesDir(null)/projects` for an install that already has that directory or whose `~/projects` link points there (`Environment.getProjectsDir`). Shared storage cannot hold a symbolic link, which broke `npm install` there. Both are app-private in the sense that matters here, no storage permission reaches either; both are wiped by Clear Data, and the shared-storage one is reachable over MTP on some devices | P0 | M0 |
| FR-FS-04 | System SHALL support opening folders as workspace root | P0 | M1 |
| FR-FS-05 | System SHALL support multi-root workspaces. A `.code-workspace` opens, survives a relaunch and is written back when it sits in a device folder (`workbenchTarget`, `workbenchUrl`, `MainActivity`). Two limits are deliberate and neither is a defect to file: the Android picker cannot select one, because it is `ACTION_OPEN_DOCUMENT_TREE` and that returns a directory only; and roots spanning two device folders are not synchronised, because exactly one folder is watched at a time (`watchedSafFolder`, `SafSyncEngine.startWatching`). No guard is written for the second, and that is measured rather than assumed: a root the workspace lists outside the granted folder does not resolve, and the workbench draws it in the warning colour with a `!`, so the failure is on screen rather than silent. This row read as unqualified until 2026-08-30, when the shell could not name a workspace at all | P2 | M2 |
| FR-FS-06 | System SHOULD support accessing files from external storage via SAF | P2 | M4 |
| FR-FS-07 | System SHALL handle file watching for external changes | P1 | M1 |

### 3.3 Terminal (FR-TERM)

| ID | Requirement | Priority | Milestone |
|----|------------|----------|-----------|
| FR-TERM-01 | System SHALL provide integrated terminal with bash shell | P0 | M1 |
| FR-TERM-02 | System SHALL support multiple terminal sessions, one bash per session, each on its own PTY through node-pty. See FR-DEV-04a | P0 | M1 |
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
| FR-EXT-04 | System SHALL run the Extension Host as a `worker_thread` rather than a `child_process.fork()`, so it costs no phantom process. `patches/0004` makes the change and `patches/fingerprints.txt` proves it reached the packaged bundle | P0 | M4 |
| FR-EXT-05 | System SHALL support theme extensions (color themes, icon themes) | P0 | M1 |
| FR-EXT-06 | System SHALL support language extensions (syntax, snippets, LSP) | P0 | M1 |
| FR-EXT-07 | System SHALL support extension settings and configuration | P1 | M1 |
| FR-EXT-08 | System SHALL bundle essential extensions for offline use | P2 | M3 |
| FR-EXT-09 | A hard cap on concurrent Language Servers is not implemented, and nothing in the app counts them. What ships instead is visibility: `assets/process-monitor.js` marks a language server idle after five minutes without a tick of CPU and the process tree shows the mark. Nothing kills one, because the owning extension restarts it within a second (measured), so disabling that extension is the one way to free a slot | n/a | M4 |

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
| FR-MUX-01 | System SHALL display Extra Key Row above the soft keyboard, paged so that no key is narrower than the 48dp touch target. The page count is therefore a function of the device: `KeyPages.forSmallestWidthDp` repacks the five default pages against `smallestScreenWidthDp`, giving five pages at 411dp and wider, six at 360dp and seven at 320dp. Order is preserved, so the sequence is always Tab, Esc, Ctrl, Alt, Shift, a gesture trackpad, `{}`, `()`, then the symbols, then F1 to F12 with Home, End, PageUp and PageDown; a narrow phone splits that same sequence over more pages, moving `()` off page 1 at 360dp and both bracket keys off it at 320dp. There are **no discrete arrow buttons**: the trackpad emits arrow keys as a finger drags, and carries one accessibility action per direction for an assistive input that cannot drag (`KeyPageConfig.kt`, `TrackpadGesture.accumulate`, `ARROW_ACTIONS`) | P1 | M2 |
| FR-MUX-02 | Extra Key Row Ctrl/Alt keys SHALL act as toggles (tap to activate, tap again to deactivate) | P1 | M2 |
| FR-MUX-03 | System SHALL inject key events from Extra Key Row into WebView | P1 | M2 |
| FR-MUX-04 | Extra Key Row SHALL show/hide based on soft keyboard visibility | P1 | M2 |
| FR-MUX-05 | System SHALL bridge Android clipboard to VS Code clipboard service | P1 | M2 |
| FR-MUX-06 | Android back sends the app to the background, and nothing else. It does not close a panel or a dialog first: `MainActivity.setupBackNavigation` asks the page nothing, because no patch, bundled extension or injected script ever installed a page-side handler to answer. Esc on the extra key row is what dismisses editor UI | P1 | M2 |
| FR-MUX-07 | System SHALL support portrait and landscape orientations | P1 | M2 |
| FR-MUX-08 | System SHALL support split-screen / multi-window mode | P2 | M2 |
| FR-MUX-09 | System SHALL disable WebView zoom (prevent accidental pinch-zoom) | P0 | M2 |
| FR-MUX-10 | Intent filters for common code file types are out of scope. A `content://` URI has no POSIX path and the server only ever sees POSIX paths, so the file would have to be materialised locally and every save would write to that copy. `AndroidManifest.xml` records the constraint. Folders open through SAF, which has the sync engine that makes write-back work | n/a | M2 |

### 3.7 Dev Environment (FR-DEV)

| ID | Requirement | Priority | Milestone |
|----|------------|----------|-----------|
| FR-DEV-01 | System SHALL bundle Node.js + npm in APK | P0 | M0 |
| FR-DEV-02 | System SHALL bundle Python 3 + pip in APK | P1 | M3 |
| FR-DEV-03 | System SHALL bundle Git in APK | P0 | M1 |
| FR-DEV-04 | System SHALL bundle bash in APK | P0 | M1 |
| FR-DEV-04a | System SHALL bundle tmux in APK as a standalone tool. The editor's terminals are not wrapped in it: the default profile `FirstRunSetup` writes points at bash, and each terminal spawns bash directly through node-pty on a real PTY. The phantom-process saving comes from running the Extension Host and ptyHost as `worker_thread`s | P1 | M1 |
| FR-DEV-05 | System SHALL deliver Ruby and Java 17 toolchains as on-demand asset packs. Delivery is not Play-only: `ToolchainManager` reads the installing package name and, for any install that did not come from Play, downloads the same toolchains as ZIPs over HTTPS from this project's GitHub Releases, verified against a published sha256 manifest | P2 | M3 |
| FR-DEV-06 | A package manager CLI (`vscodroid pkg`) is not built. No such command exists in the app or on the device; additional packages are the user's own business through the terminal. The design it would start from is `docs/04-TECHNICAL_SPEC.md` §8, and it sits on the post-release roadmap | P2 | Post-release |
| FR-DEV-07 | Prompting for a toolchain by file type is out of scope: nothing watches which files are opened. Discovery is unprompted instead, the welcome walkthrough names the two toolchains and points at the picker, which is also reachable from the app icon's **Manage toolchains** shortcut and from the **VSCodroid: Manage Toolchains** command | P3 | M3 |
| FR-DEV-08 | System SHALL provide Language Picker UI during first-run for selecting toolchains to install | P1 | M3 |
| FR-DEV-09 | System SHALL allow installing additional toolchains later from the app icon's **Manage toolchains** launcher shortcut (`SplashActivity.publishToolchainShortcut`), matching FR-DEV-07 above, or from the Command Palette entry **VSCodroid: Manage Toolchains**, which the bundled SAF bridge extension registers and which sends the `openToolchainSettings` bridge command. There is still no Settings entry, and `ToolchainActivity` is not exported | P2 | M3 |

### 3.8 Application Lifecycle (FR-LIFE)

| ID | Requirement | Priority | Milestone |
|----|------------|----------|-----------|
| FR-LIFE-01 | System SHALL run Node.js server via Foreground Service | P0 | M0 |
| FR-LIFE-02 | System SHALL auto-restart Node.js if process dies | P0 | M0 |
| FR-LIFE-03 | System SHALL recover from WebView renderer crash, and SHALL stop recovering when recovery has become a loop: more than three crashes in 60 seconds (`CRASH_LOOP_CRASHES`, `CRASH_LOOP_WINDOW_MS`) rebuilds the WebView but does not reload the editor, showing a page whose control reloads it instead. The server is left running either way | P1 | M2 |
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
| NFR-PERF-07 | First-run binary extraction | Progress reported throughout, no time target. About 770 MiB across roughly 23,600 files, unpacked one at a time | P1 |

### 4.2 Resource Usage (NFR-RES)

| ID | Requirement | Target | Priority |
|----|------------|--------|----------|
| NFR-RES-01 | RAM usage (typical coding session) | < 700 MB | P1 |
| NFR-RES-02 | RAM usage (4GB device minimum) | Functional without OOM | P0 |
| NFR-RES-03 | Phantom process count | 5 with nothing open and 8 with a terminal and two language servers, the `IDLE_BASELINE` and `SOFT_BUDGET` of `assets/process-monitor.js`; the monitor warns at 8 and calls it a problem at 14, against Android's 32; nothing sheds a process | P0 |
| NFR-RES-04 | AAB base module, compressed download | Under Play's 500 MB cap, which `scripts/check-bundle-size.py` refuses a bundle over. Last measured at 270.7 MiB, before the workbench internal bundles were pruned from the server tree, so re-measure from the AAB rather than quoting this. **200 MB is not a cap**: it is the size above which a mobile-data user sees a large-download dialog. The on-demand toolchain ZIPs are 9.9 MB for Ruby and 55.4 MB for Java 17, per `ToolchainRegistry.available`, and draw on Play's separate on-demand budget rather than this one | P1 |
| NFR-RES-05 | Runtime storage (core extracted) | About 770 MiB, the asset tree that `BuildConfig.EXTRACTED_ASSET_BYTES` is computed from at build time; about 950 MiB with both toolchains installed | P1 |
| NFR-RES-06 | Battery drain during active session | < 15% per hour | P2 |
| NFR-RES-06a | Battery drain during idle session (foreground, no input) | < 5% per hour | P2 |
| NFR-RES-07 | V8 heap limit (default) | An eighth of device RAM, held between 256 MB and 768 MB | P1 |
| NFR-RES-07a | V8 heap limit (user override) | Optional, set as `vscodroid.server.heapCeilingMb`; clamped to `min(RAM / 4, 1536 MB)` and never below 256 MB | P2 |

> NFR-RES-03, the second half of NFR-RES-04, NFR-RES-05 and NFR-RES-07 carry measured
> figures, not targets. Verify them against `assets/process-monitor.js`, the AAB itself,
> `BuildConfig.EXTRACTED_ASSET_BYTES` and `ProcessManager.heapCeilingForDevice` rather than
> this table: the extracted tree moves with every VS Code bump, and the heap ceiling is
> derived per device because a flat cap leaves 3-4 GB phones nothing to work with.

> **NFR-RES-07a replaced a guarantee with a user responsibility, and that is the point of
> stating it separately.** NFR-RES-07 alone read as a promise that this app bounds its own
> memory, and it is no longer one for a device where the key has been set. Three things keep
> that from becoming a way to break an install, and all three are requirements rather than
> implementation detail:
>
> - The clamp is computed from live `totalMem` on every start, never stored. A settings file
>   carried to a smaller device is bounded by the device it is running on.
> - A device the manufacturer flagged as low-RAM, and a device whose total cannot be read,
>   ignore the key entirely. The clamp is the only protection and it is derived from the
>   total, so with no trustworthy total there is nothing to clamp against.
> - The value disables itself after three `SIGKILL`s and the user is told. Without that, its
>   only editing surface is inside the editor it can prevent from starting, and the recovery
>   of last resort is clearing app data, which destroys the user's projects.
>
> Two limits worth knowing before quoting either row. The flag caps EACH V8 isolate in the
> server, not all of them together, so a ceiling of N authorises roughly 3N of old space
> across the server process family. And neither row bounds the largest V8 heap the device
> actually runs: `tsserver.maxMemory` defaults to 3072 MB with no reference to device RAM,
> and nothing in this app reaches it.

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
| NFR-SEC-05 | All binaries delivered via Play Store (Play install) | Core as .so in base APK; toolchains as on-demand asset packs via Language Picker. A non-Play install fetches the same toolchain ZIPs over HTTPS from GitHub Releases, against a published sha256 manifest | P0 |
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
| Storage on 64GB devices | About 770 MiB extracted for core, and about 865 MiB free to unpack it: the tree plus the 96 MiB of slack `FirstRunSetup.requiredExtractionBytes` adds for per-file block rounding |
| CPU throttling | Android may throttle background processes |
| Battery optimization | Doze mode may affect background server |

---

## 6. Interface Requirements

### 6.1 User Interfaces

Detailed in [API Spec § Android Bridge API](./05-API_SPEC.md#2-a-android-bridge-api).

- Android native: Extra Key Row, status bar, Foreground Service notification
- WebView: VS Code Workbench (as built from the Code - OSS source, with the diffs in `patches/`)

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
- [ ] Multiple terminal sessions work, one bash per session on its own PTY (see FR-DEV-04a)
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
- [ ] On-demand toolchain install works (test: Ruby)
- [ ] Pre-bundled extensions available without internet

### M4 (Polish) Acceptance

- [ ] Cold start < 5 seconds on Pixel 7
- [ ] No crash in 2 hours continuous use
- [ ] Works on 4GB RAM device
- [ ] Phantom processes at 5 with nothing open, and under 14 in a working session
- [ ] Extension Host migrated to worker_thread (reduces phantom count by 1)
- [ ] Phantom process monitoring UI warns user when approaching limits
- [ ] GitHub OAuth push/pull works
- [ ] Tested on 4 device models (see Testing Strategy §3.5 Compatibility Tests and §4.3 Reference Devices)

### M5 (Release) Acceptance

- [ ] Published on Play Store
- [ ] Passes Play Store policy review
- [ ] Base module under Play's 500 MB compressed cap (`scripts/check-bundle-size.py`)
- [ ] No critical bugs in 48-hour beta
