# Architecture Design Document (ADD)

**Project**: VSCodroid
**Version**: 1.0-draft
**Date**: 2026-02-10
**Standard Reference**: IEEE 1016 (adapted), C4 Model

---

## 1. Architecture Overview

VSCodroid follows a **local client-server architecture** where both the client (VS Code Web UI) and server (VS Code Server + Node.js) run on the same Android device, communicating over localhost.

```mermaid
flowchart TD
  subgraph DEVICE["Android Device"]
    subgraph SHELL["Kotlin Native Shell"]
      WV["WebView (vscode-web)"]
      UI["Monaco / File Explorer / Panels / Extensions UI"]
      NODE["Node.js Process (VS Code Server, vscode-reh)"]
      SRV["Extension Host / Terminal (tmux) / File System / Search"]
      EXTRA["Extra Key Row (Native Android View)"]
      WV --> UI
      NODE --> SRV
      EXTRA --> WV
    end
    FG["Foreground Service (keeps alive)"]
    BIN["Bundled Binaries (.so): node, python, git, bash, tmux"]
  end

  WV <--> LOCAL["localhost:PORT (HTTP + WebSocket)"]
  LOCAL <--> NODE
  FG --> NODE
  BIN --> NODE
```

---

## 2. Architecture Principles

| Principle | Rationale |
|-----------|-----------|
| **Minimal modification to VS Code** | Less maintenance burden, easier to rebase on upstream updates |
| **Process isolation** | WebView renderer and Node.js are separate processes; one crashing doesn't kill the other |
| **Localhost-only communication** | No network exposure, no authentication needed, simplifies security |
| **Native shell for Android integration** | Kotlin handles platform-specific concerns (keyboard, clipboard, lifecycle) |
| **Lazy resource loading** | Language servers, extensions, toolchains loaded on-demand to minimize resource usage |
| **Graceful degradation** | App remains functional even if some components fail (e.g., extension marketplace offline) |

---

## 3. C4 Model

### 3.1 Level 1 — System Context

```mermaid
flowchart TD
  USER["Developer (User)"] --> APP["VSCodroid (Android App)"]
  APP --> OVSX["Open VSX (Extensions Registry)"]
  APP --> GIT["GitHub/GitLab remotes"]
  APP --> TERMUX["Termux Repositories (Packages)"]
```

**External Systems:**
- **Open VSX**: Extension search, download, update (HTTPS)
- **GitHub/GitLab**: Remote git operations (HTTPS/SSH)
- **Termux Package Repository**: Additional tool downloads (sideload version only; Play Store version uses on-demand asset packs)

### 3.2 Level 2 — Container Diagram

```mermaid
flowchart TD
  subgraph APP["VSCodroid App"]
    subgraph SHELL["Kotlin Native Shell"]
      subgraph UI["UI Container"]
        WEBVIEW["WebView (vscode-web HTML/JS/CSS)"]
        EXTRAROW["Extra Key Row (Native Android View)"]
      end

      subgraph SERVER["Server Container"]
        VSS["VS Code Server (Node.js, vscode-reh)<br/>Extension Host / Terminal Backend / File Service / Search Service"]
        CHILD["Child Processes<br/>tmux -> bash sessions<br/>Language Servers (lazy)"]
      end

      subgraph SERVICES["Android Services"]
        FGSVC["ForegroundService"]
        PM["ProcessManager"]
        BRIDGE["BridgeInterface"]
      end
    end

    LIBS["Native Libraries (jniLibs)<br/>libnode.so, libpython.so, libgit.so,<br/>libbash.so, libtmux.so, libnode_pty.so,<br/>libc++_shared.so, libmake.so"]
  end

  EXTRAROW --> WEBVIEW
  WEBVIEW <-->|"HTTP + WebSocket"| VSS
  VSS --> CHILD
  FGSVC --> VSS
  PM --> VSS
  BRIDGE --> WEBVIEW
  LIBS --> VSS
```

### 3.3 Level 3 — Component Diagram (Server Container)

```mermaid
flowchart TD
  subgraph NODEPROC["Node.js Process"]
    subgraph VSS["VS Code Server (vscode-reh)"]
      HTTP["HTTP Server (Static Assets)"]
      WS["WebSocket Server (RPC Channel)"]
      RA["Remote Authority Connection Handler"]
      REG["Service Registry"]
      FS["File System Service (Node fs)"]
      SEARCH["Search Service (ripgrep)"]
      TERM["Terminal Service (node-pty -> tmux -> bash)"]
      EXTHOST["Extension Host (worker_thread)"]
      DEBUG["Debug Service"]
      TASK["Task Service"]
      HTTP --> REG
      WS --> REG
      RA --> REG
      REG --> FS
      REG --> SEARCH
      REG --> TERM
      REG --> EXTHOST
      REG --> DEBUG
      REG --> TASK
    end

    EXTHOST --> THREAD["Extension Host Thread<br/>Extension A (active)<br/>Extension B (active)<br/>Extension C (idle)"]
    TERM --> TMUX["tmux server [phantom #2]"]
    TMUX --> BASH["bash sessions (N)"]
    EXTHOST -. lazy start .-> LS["Language Servers (0-3) [phantom #3-5]<br/>tsserver, pylsp (idle-killed after 5 min)"]
  end
```

---

## 4. Key Architecture Decisions (ADRs)

### ADR-001: Fork code-server, not VS Code directly

**Status**: Accepted

**Context**: We need VS Code running as a web server on Android. VS Code itself doesn't include a web-serving layer.

**Decision**: Fork code-server which already patches VS Code to serve via HTTP/WebSocket.

**Rationale**:
- code-server maintains ~15-20 patches for web serving, marketplace, branding
- Saves 3-4 weeks of development
- Active upstream maintenance
- Well-documented patch system

**Trade-off**: Dependency on code-server's patch compatibility with VS Code updates.

---

### ADR-002: Termux-style Node.js binary, not nodejs-mobile

**Status**: Accepted

**Context**: Need Node.js on Android. Options: (a) nodejs-mobile (in-process), (b) Termux-style separate binary.

**Decision**: Use Termux-style separate ARM64 binary.

**Rationale**:
- nodejs-mobile lacks child_process.fork(), node-pty, worker_threads
- VS Code Extension Host requires child_process.fork() or worker_threads
- node-pty required for terminal (PTY allocation)
- Termux has proven this approach works for years

**Consequence**: Binary must be bundled as .so for Android W^X compliance. Adds ~50MB to APK.

---

### ADR-003: Open VSX instead of Microsoft Marketplace

**Status**: Accepted (legal requirement)

**Context**: VS Code extensions need a marketplace. Microsoft Marketplace ToS prohibits third-party access.

**Decision**: Use Open VSX (open-vsx.org).

**Rationale**:
- Microsoft Marketplace ToS Section 4: "only for use within Visual Studio products and services"
- Open VSX is open-source, Eclipse Foundation backed
- Most popular extensions available (some Microsoft-exclusive ones missing)
- Simple integration: just change product.json extensionsGallery URLs
- VSCodium uses the same approach since 2019

**Consequence**: Some Microsoft-exclusive extensions (e.g., Remote SSH, Live Share) won't be available.

---

### ADR-004: Extension Host as worker_thread

**Status**: Accepted

**Context**: VS Code Extension Host normally runs as a child process via child_process.fork(). Each child process counts toward Android's 32-process phantom limit.

**Decision**: Patch Extension Host to run as a worker_thread inside the main Node.js process. Phased rollout: child_process.fork() in M1-M3, migrate to worker_thread in M4.

**Rationale**:
- worker_thread = same process = not a phantom process
- Saves 1 phantom process slot (significant given 32 system limit)
- worker_threads have access to most Node.js APIs
- code-server already explored this direction

**Trade-off**: Extension Host crash could bring down the main server process. Mitigation: watchdog that restarts server if Extension Host thread crashes.

**Implementation note**: Patch details (target files, fork→worker mapping, restart semantics, validation tests) are specified in [Technical Spec §6.3 Extension Host worker_thread Migration](./04-TECHNICAL_SPEC.md#63-extension-host-worker_thread-migration).

---

### ADR-005: tmux for terminal multiplexing

**Status**: Accepted

**Context**: VS Code supports multiple terminal tabs. Each terminal would normally be a separate process (phantom).

**Decision**: Use tmux as a single process managing all terminal sessions.

**Rationale**:
- 1 tmux process + N sessions = only 1 phantom process
- Without tmux: N terminals = N bash processes = N phantoms
- tmux also provides session persistence (survive server restart)
- Proven technology, small binary (~500KB)

**Trade-off**: Added complexity in terminal management. Must map VS Code terminal tabs to tmux sessions.

---

### ADR-006: .so bundling for Android binary execution

**Status**: Accepted (platform requirement)

**Context**: Android 10+ enforces W^X (write-xor-execute). Cannot download and execute arbitrary binaries.

**Decision**: Bundle all executables as .so files in jniLibs/arm64-v8a/ directory.

**Rationale**:
- Android Package Manager extracts .so files with execute permission
- Only officially supported way to bundle executables since Android 10
- Termux, UserLAnd, and other apps use this approach
- Requires: Gradle `packagingOptions { jniLibs { useLegacyPackaging = true } }`

**Consequence**: All core binaries (Node.js, Python, Git, bash, tmux) bundled as .so files in the base APK. Additional toolchains (Go, Rust, Java, C/C++, Ruby) delivered as on-demand asset packs via Play Store — user selects desired languages during first-run Language Picker, Play Store downloads automatically. For sideloading (GitHub Releases APK), all toolchains are bundled directly.

---

### ADR-007: Standard Android WebView, not embedded Chromium

**Status**: Accepted

**Context**: Need a browser engine to render VS Code UI. Options: (a) System WebView, (b) Embedded Chromium (via Chrome Custom Tabs or bundled).

**Decision**: Use standard Android WebView.

**Rationale**:
- System WebView updates automatically via Play Store
- No additional binary size (Chromium adds 100MB+)
- WebView on Android is Chromium-based, supports all VS Code needs
- Minimum WebView 105+ covers all required APIs on Android 13+ baseline

**Trade-off**: Dependent on user's WebView version. Mitigation: runtime version check, graceful error if too old.

---

### ADR-008: Foreground Service with specialUse type

**Status**: Accepted

**Context**: Node.js server must keep running when app is backgrounded. Android aggressively kills background processes.

**Decision**: Use Foreground Service with `specialUse` type (Android 14+ requirement).

**Rationale**:
- Foreground Service prevents process killing
- `specialUse` type is correct for our use case (local dev server)
- Must provide justification to Play Store review: "Runs local development server for code editor"
- Shows persistent notification (expected for dev tool)

**Trade-off**: Notification always visible when server running. Acceptable for developer tool.

---

### ADR-009: Install-time Asset Packs for toolchains

**Status**: Accepted

**Context**: VSCodroid bundles multiple language toolchains (Go, Rust, Java, C/C++, Ruby). Bundling all in the base APK would make it very large. Options: (a) On-demand download from CDN, (b) Play Store install-time asset packs, (c) Play Store on-demand asset packs.

**Decision**: Use Play Store on-demand asset packs for toolchain delivery, with a Language Picker UI during first-run.

**Rationale**:
- On-demand packs keep base APK small (~150-200MB) for fast initial install
- User selects needed languages during first-run — only downloads what they need
- Play Store handles download/install automatically (no manual steps for user)
- Play Store optimizes delivery per device (only arm64 assets delivered)
- No custom CDN infrastructure needed for toolchain hosting
- All binaries delivered via Play Store, simplifying policy compliance
- Additional languages can be added later via Settings > Toolchains
- For sideloading (GitHub Releases), all toolchains bundled directly in APK

**Trade-off**: Requires internet for toolchain download after initial install. Core functionality (Node.js, Python, Git) works fully offline.

---

## 5. Communication Patterns

### 5.1 WebView ↔ VS Code Server

```mermaid
sequenceDiagram
  participant W as WebView (vscode-web)
  participant S as VS Code Server (vscode-reh)
  W->>S: GET /index.html
  S-->>W: index.html (initial page load)
  W->>S: GET /static/*
  S-->>W: JS, CSS, fonts
  W->>S: Connect WebSocket /ws
  S-->>W: RPC stream (file operations, terminal I/O, extension messages, diagnostics)
```

**Protocol**: VS Code's built-in `IExtHostRpcProtocol` over WebSocket. Binary frames for efficiency.

### 5.2 Kotlin ↔ WebView

```mermaid
sequenceDiagram
  participant K as Kotlin Native Shell
  participant W as WebView
  K->>W: injectBridgeToken() to trusted workbench context
  K->>W: evaluateJavascript() (inject key events)
  W->>K: @JavascriptInterface: copyToClipboard()
  W->>K: @JavascriptInterface: openFilePicker()
  W->>K: @JavascriptInterface: onBackPressed()
  K-->>W: Result/ack response
```

### 5.3 Kotlin ↔ Node.js

```mermaid
sequenceDiagram
  participant K as Kotlin Native Shell
  participant N as Node.js Process
  K->>N: ProcessBuilder.start() (launch)
  K->>N: HTTP GET /healthz (polling)
  K->>N: Process.destroy() (graceful shutdown)
  N-->>K: Process.exitValue() (death detection)
```

---

## 6. Data Flow

### 6.1 User Types Code → File Saved

```mermaid
flowchart TD
  A["1. User taps key on soft keyboard / Extra Key Row"] --> B["2. Android dispatches KeyEvent to WebView<br/>or evaluateJavascript for Extra Key Row"]
  B --> C["3. Monaco Editor handles keypress<br/>updates internal model"]
  C --> D["4. VS Code auto-save or Ctrl+S triggers save"]
  D --> E["5. WebSocket: FileService.writeFile(uri, content)"]
  E --> F["6. Node.js fs.writeFile() to app-private storage"]
  F --> G["7. File watcher detects change<br/>updates File Explorer UI"]
```

### 6.2 Extension Installation

```mermaid
flowchart TD
  A["1. User searches extension in Extensions panel"] --> B["2. VS Code UI sends HTTP request to Open VSX API"]
  B --> C["3. User clicks Install"]
  C --> D["4. VS Code downloads .vsix from Open VSX CDN"]
  D --> E["5. VS Code extracts to extensions directory"]
  E --> F["6. Extension Host loads extension module"]
  F --> G["7. Extension activates<br/>registers commands/providers/etc."]
```

### 6.3 Terminal Command Execution

```mermaid
flowchart TD
  A["1. User types command in terminal panel"] --> B["2. WebSocket: Terminal.input(sessionId, data)"]
  B --> C["3. Node.js -> node-pty writes to PTY master fd"]
  C --> D["4. tmux session receives input -> forwards to bash"]
  D --> E["5. bash executes command"]
  E --> F["6. Output path:<br/>bash -> tmux -> PTY slave -> node-pty -> WebSocket -> terminal panel"]
```

---

## 7. Deployment Architecture

### 7.1 APK/AAB Structure

```mermaid
flowchart TD
  A["app.aab"] --> B["base/"]
  B --> C["dex/ (Kotlin compiled code)"]
  B --> D["lib/arm64-v8a/ (native binaries as .so)"]
  D --> D1["libnode.so (~50MB)"]
  D --> D2["libpython.so (~30MB)"]
  D --> D3["libgit.so (~15MB)"]
  D --> D4["libbash.so (~1.5MB)"]
  D --> D5["libtmux.so (~500KB)"]
  D --> D6["libmake.so (~500KB)"]
  D --> D7["libnode_pty.so (~300KB)"]
  D --> D8["libc++_shared.so (~1.2MB)"]
  B --> E["assets/"]
  E --> E1["vscode-reh/ (VS Code server files)"]
  E --> E2["vscode-web/ (VS Code web client files)"]
  E --> E3["python-stdlib/ (Python standard library)"]
  E --> E4["node-modules/ (required node_modules, incl. @vscode/ripgrep)"]
  E --> E5["extensions/ (pre-bundled extensions)"]
  B --> F["res/ (Android resources)"]
  B --> G["AndroidManifest.xml"]
```

`ripgrep` delivery model: bundled inside VS Code server dependencies (`node_modules/@vscode/ripgrep`), extracted together with `vscode-reh`. It is not shipped as a separate `lib*.so` binary.

### 7.2 Runtime File Layout

```mermaid
flowchart TD
  A["/data/data/com.vscodroid/"] --> B["files/"]
  B --> B1["home/ ($HOME)"]
  B1 --> B1a[".vscodroid/ (VS Code data folder)"]
  B1a --> B1a1["extensions/ (installed extensions)"]
  B1a --> B1a2["User/ (user settings)"]
  B1a --> B1a3["logs/ (server logs)"]
  B1 --> B1b[".gitconfig"]
  B1 --> B1c[".ssh/ (SSH keys)"]
  B --> B2["usr/ (Unix-like layout)"]
  B2 --> B2a["bin/ (symlinks to binaries)"]
  B2 --> B2b["lib/ (shared libraries)"]
  B2 --> B2c["lib/python3/ (Python stdlib)"]
  B2 --> B2d["share/ (terminfo, etc.)"]
  B --> B3["workspace/ (default workspace)"]
  B --> B4["tmp/ (temporary files)"]
  B --> B5["server/ (VS Code extracted)"]
  B5 --> B5a["vscode-reh/"]
  B5 --> B5b["vscode-web/"]
  A --> C["lib/ (nativeLibraryDir, read-only)"]
  C --> C1["libnode.so"]
  C --> C2["libpython.so"]
  C --> C3["..."]
  A --> D["cache/ (clearable)"]
  D --> D1["webview/"]
```

---

## 8. Cross-Cutting Concerns

### 8.1 Error Handling Strategy

| Layer | Strategy |
|-------|----------|
| Kotlin shell | Try-catch with user-facing error dialogs. Crash reporting. |
| WebView | onRenderProcessGone → recreate WebView, reload server URL |
| Node.js server | Process death → Kotlin detects via pid monitor → auto-restart |
| Extension Host | worker_thread crash → restart thread, reload extensions |
| Terminal | tmux session persistence → survive server restart |
| File operations | Node.js fs errors → propagate to VS Code UI as notifications |

### 8.2 Logging

| Component | Log Destination | Level |
|-----------|----------------|-------|
| Kotlin | Android Logcat (tag: VSCodroid) | INFO (release), DEBUG (debug) |
| Node.js server | File: ~/.vscodroid/logs/server.log | INFO |
| Extension Host | File: ~/.vscodroid/logs/exthost.log | WARN |
| WebView | Chrome DevTools (debug builds only) | ALL |

### 8.3 Configuration

| Config | Location | Format |
|--------|----------|--------|
| VS Code settings | ~/.vscodroid/User/settings.json | JSON |
| product.json | Built into vscode-web and vscode-reh assets | JSON |
| Environment variables | Set by Kotlin ProcessBuilder | Shell |
| App preferences | Android SharedPreferences | XML |

---

## 9. Technology Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| Android app | Kotlin | 2.0+ |
| Build system | Gradle (Kotlin DSL) | 8.x |
| UI framework | Android View + WebView | API 33-36 |
| Node.js | Node.js LTS | 20.x (pinned at M0 start) |
| VS Code | Code-OSS (via code-server fork) | 1.96.x (pinned at M0 start) |
| Extension Host | VS Code Extension Host (child_process.fork initially, worker_thread in M4) | Same as VS Code |
| Terminal | node-pty + tmux + bash | Latest |
| Package manager | Custom (Termux repo compatible) | v1.0 |
| SCM | Git | 2.40+ |
| Python | Python 3 | 3.11+ |
| C++ stdlib | libc++ from NDK | r27 |
