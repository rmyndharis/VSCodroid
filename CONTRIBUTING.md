# Contributing to VSCodroid

Thank you for your interest in contributing to VSCodroid. This guide covers everything you need to set up a development environment, build the app, test on device, and submit changes.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Development Setup](#development-setup)
- [Project Structure](#project-structure)
- [Download Scripts](#download-scripts)
- [Building](#building)
- [Testing on Device](#testing-on-device)
- [How to Add a New Bundled Tool](#how-to-add-a-new-bundled-tool)
- [How to Add a New Patch](#how-to-add-a-new-patch)
- [Code Style](#code-style)
- [Pull Request Process](#pull-request-process)
- [Reporting Bugs](#reporting-bugs)
- [Suggesting Features](#suggesting-features)

## Code of Conduct

This project follows the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md). By participating, you agree to uphold this code.

## Development Setup

### Prerequisites

| Tool              | Version                        | Notes                                        |
| ----------------- | ------------------------------ | -------------------------------------------- |
| macOS or Linux    | -                              | Windows is not supported for building        |
| Android Studio    | Latest stable                  | With Android API 36 SDK                      |
| Android NDK       | r27+                           | For cross-compiling native modules           |
| JDK               | 17+                            | Required by Gradle                           |
| Node.js           | 20 LTS                         | For VS Code build tooling                    |
| Python            | 3.x                            | For node-gyp (native module compilation)     |
| Git               | Any recent version             | -                                            |
| adb               | Via Android SDK platform-tools | For deploying to device                      |
| Physical ARM64 device | Android 13+ (API 33+)      | Emulators will not work (ARM64 binaries)     |

### Clone and Initial Setup

```bash
# Fork the repository on GitHub, then:
git clone https://github.com/<your-username>/VSCodroid.git
cd VSCodroid

# Add upstream remote
git remote add upstream https://github.com/rmyndharis/VSCodroid.git
```

### Preparing Assets

Before you can build the APK, the `android/app/src/main/assets/` and `android/app/src/main/jniLibs/` directories need to be populated with VS Code Server, Node.js, and bundled tools. These are not checked into git due to their size.

Run the download scripts in this order:

```bash
# 1. Download VS Code Server (vscode-reh + vscode-web) and apply patches
./scripts/download-vscode-server.sh

# 2. Download Termux tools (bash, git, tmux, make, openssh)
./scripts/download-termux-tools.sh

# 3. Download npm
./scripts/download-npm.sh

# 4. Download Python 3
./scripts/download-python.sh

# 5. Download pre-bundled extensions
./scripts/download-extensions.sh

# 6. Build node-pty native module (requires NDK)
./scripts/build-node-pty.sh

# 7. (Optional) Download on-demand toolchains
./scripts/download-go.sh
./scripts/download-ruby.sh
./scripts/download-java.sh
```

Alternatively, run them all at once:

```bash
./scripts/build-all.sh
```

**Tip:** Termux mirrors can be slow. Set `TERMUX_MIRROR` for faster downloads:

```bash
export TERMUX_MIRROR=https://mirror.mwt.me/termux/main
```

### Build the APK

```bash
cd android && ./gradlew assembleDebug
```

The debug APK is output to `android/app/build/outputs/apk/debug/app-debug.apk`.

## Project Structure

```
VSCodroid/
├── android/                          # Android application (Gradle project)
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── kotlin/com/vscodroid/  # Kotlin source code
│   │   │   │   ├── MainActivity.kt       # Main activity, WebView setup, JS bridge
│   │   │   │   ├── SplashActivity.kt     # First-run extraction, Language Picker
│   │   │   │   ├── ToolchainActivity.kt  # Settings > Toolchains UI
│   │   │   │   ├── VSCodroidApp.kt       # Application class, WebView pre-warm
│   │   │   │   ├── bridge/               # AndroidBridge, ClipboardBridge, SecurityManager
│   │   │   │   ├── keyboard/             # ExtraKeyRow, GestureTrackpad, KeyInjector
│   │   │   │   ├── service/              # NodeService (foreground), ProcessManager
│   │   │   │   ├── setup/                # FirstRunSetup, ToolchainManager/Registry
│   │   │   │   ├── storage/              # SAF storage bridge, sync engine
│   │   │   │   ├── util/                 # Environment, Logger, CrashReporter, StorageManager
│   │   │   │   └── webview/              # WebView, WebViewClient, WebChromeClient
│   │   │   ├── assets/                # VS Code Server, tools, extensions (not in git)
│   │   │   │   ├── vscode-reh/           # VS Code Server (Remote Extension Host)
│   │   │   │   ├── server.js             # Node.js server bootstrap
│   │   │   │   ├── process-monitor.js    # Phantom process monitor
│   │   │   │   ├── platform-fix.js       # Selective platform override for npm
│   │   │   │   ├── usr/                  # Shared libraries, Python stdlib, npm
│   │   │   │   └── extensions/           # Pre-bundled extensions
│   │   │   ├── jniLibs/arm64-v8a/     # Native binaries (.so trick for exec permission)
│   │   │   │   ├── libnode.so            # Node.js (~48 MB)
│   │   │   │   ├── libpython.so          # Python launcher
│   │   │   │   ├── libpython3.12.so      # Python shared library
│   │   │   │   ├── libgit.so             # Git
│   │   │   │   ├── libbash.so            # Bash
│   │   │   │   ├── libtmux.so            # tmux
│   │   │   │   ├── libmake.so            # make
│   │   │   │   ├── libssh.so             # OpenSSH client
│   │   │   │   ├── libssh-keygen.so      # ssh-keygen
│   │   │   │   ├── libripgrep.so         # ripgrep (for VS Code Search)
│   │   │   │   └── libc++_shared.so      # NDK C++ stdlib
│   │   │   └── res/                   # Android resources, layouts
│   │   └── build.gradle.kts
│   ├── toolchain_go/              # Go on-demand asset pack
│   ├── toolchain_ruby/            # Ruby on-demand asset pack
│   ├── toolchain_java/            # Java on-demand asset pack
│   └── settings.gradle.kts
├── scripts/                       # Download and build scripts
│   ├── download-vscode-server.sh     # Download + patch VS Code Server
│   ├── download-termux-tools.sh      # Download bash, git, tmux, make, openssh
│   ├── download-npm.sh               # Download npm from Node.js tarball
│   ├── download-python.sh            # Download Python 3 from Termux
│   ├── download-extensions.sh        # Download pre-bundled extensions
│   ├── build-node-pty.sh             # Cross-compile node-pty for ARM64
│   ├── download-go.sh                # Download Go toolchain
│   ├── download-ruby.sh              # Download Ruby toolchain
│   ├── download-java.sh              # Download Java (OpenJDK 17) toolchain
│   ├── build-all.sh                  # Run all download/build scripts
│   ├── deploy.sh                     # Build + install + launch on device
│   └── device-test.sh                # Run device tests
├── patches/                       # VS Code patches
│   ├── code-server/                  # Patches inherited from code-server
│   └── vscodroid/                    # VSCodroid-specific patches
├── docs/                          # Project documentation
├── test/                          # Test suites and fixtures
├── CLAUDE.md                      # Architecture and technical decisions reference
├── MILESTONES.md                  # Development milestones M0-M6
├── NOTICE.md                      # Third-party attribution
└── README.md                      # Project overview
```

### Key Kotlin Source Files

| File | Purpose |
| ---- | ------- |
| `MainActivity.kt` | WebView setup, JS bridge registration, intent handling, OAuth callbacks |
| `SplashActivity.kt` | First-run asset extraction, progress UI, Language Picker |
| `VSCodroidApp.kt` | Application class, WebView pre-warm, CrashReporter init |
| `bridge/AndroidBridge.kt` | JS interface: clipboard, file picker, OAuth, SSH, storage, toolchains |
| `bridge/SecurityManager.kt` | URL allowlist for WebView navigation (localhost only) |
| `keyboard/ExtraKeyRow.kt` | Multi-page key bar with ViewPager2 and dot indicators |
| `keyboard/GestureTrackpad.kt` | 3-speed drag-to-cursor-navigate widget |
| `keyboard/KeyInjector.kt` | Injects KeyboardEvent into WebView via JS |
| `service/NodeService.kt` | Foreground Service (specialUse) to keep Node.js alive |
| `service/ProcessManager.kt` | Node.js process lifecycle, health check, auto-restart |
| `setup/FirstRunSetup.kt` | Asset extraction, symlink creation, settings, .bashrc |
| `setup/ToolchainManager.kt` | Play Asset Delivery: install, uninstall, env vars |
| `util/Environment.kt` | PATH, HOME, LD_LIBRARY_PATH, all env vars for Node.js process |
| `webview/VSCodroidWebViewClient.kt` | CDN URL interception, vscode-resource serving, crash recovery |

## Download Scripts

Each script downloads pre-built binaries and places them in the correct location under `android/app/src/main/`.

| Script | What it does | Output location |
| ------ | ------------ | --------------- |
| `download-vscode-server.sh` | Downloads VS Code Server (vscode-reh + vscode-web) from Microsoft CDN, applies branding patches, patches workbench.js (vsda bypass, platform fixes, workspace trust, etc.) | `assets/vscode-reh/`, `assets/vscode-web/` |
| `download-termux-tools.sh` | Downloads bash, git, tmux, make, openssh from Termux APT repo, extracts .deb packages | `jniLibs/arm64-v8a/`, `assets/usr/` |
| `download-npm.sh` | Extracts npm from Node.js linux-arm64 tarball | `assets/usr/lib/node_modules/npm/` |
| `download-python.sh` | Downloads Python 3.12 + deps from Termux | `jniLibs/arm64-v8a/`, `assets/usr/lib/python3.12/` |
| `download-extensions.sh` | Downloads marketplace extensions from Open VSX (supports `publisher.name@version` pinning) | `assets/extensions/` |
| `build-node-pty.sh` | Cross-compiles node-pty v1.1.0-beta22 for ARM64 using NDK (no node-gyp) | `assets/vscode-reh/node_modules/node-pty/build/Release/pty.node` |
| `download-go.sh` | Downloads Go toolchain from Termux | `toolchain_go/src/main/assets/` |
| `download-ruby.sh` | Downloads Ruby + deps from Termux | `toolchain_ruby/src/main/assets/` |
| `download-java.sh` | Downloads OpenJDK 17 + deps from Termux | `toolchain_java/src/main/assets/` |

**Important notes:**
- Scripts are designed for macOS and Linux (macOS uses `bsdtar` for `.deb` extraction).
- `download-vscode-server.sh` uses `python3` for in-place regex edits (`sed -i` is not cross-platform).
- The Node.js binary (`libnode.so`) is cross-compiled separately and checked in or provided as a release artifact. See CLAUDE.md for cross-compilation details.

## Building

### Debug Build

```bash
cd android && ./gradlew assembleDebug
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk` (debug package name: `com.vscodroid.debug`)

### Release Build

Release builds require a signing keystore configured via environment variables:

```bash
export VSCODROID_KEYSTORE_FILE=/path/to/keystore.jks
export VSCODROID_KEYSTORE_PASSWORD=...
export VSCODROID_KEY_ALIAS=...
export VSCODROID_KEY_PASSWORD=...

cd android && ./gradlew assembleRelease
```

### Android App Bundle (for Play Store)

```bash
cd android && ./gradlew bundleRelease
```

This produces an AAB at `android/app/build/outputs/bundle/release/app-release.aab` that includes on-demand asset packs for toolchains.

## Testing on Device

### Install and Launch

```bash
# Build and install (clears app data to ensure clean state)
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell pm clear com.vscodroid.debug

# Launch via SplashActivity (required after clearing data for first-run extraction)
adb shell am start -n com.vscodroid.debug/com.vscodroid.SplashActivity
```

**Important:** After clearing app data or a fresh install, you must launch via `SplashActivity` (not `MainActivity`) so that first-run asset extraction runs.

### Chrome DevTools (WebView debugging)

```bash
# Get the app PID
adb shell ps -A | grep vscodroid

# Forward DevTools port
adb forward tcp:9222 localabstract:webview_devtools_remote_<PID>
```

Then open `chrome://inspect` in Chrome. Note: the CDP WebSocket connection must be made without an Origin header (origin restriction blocks connections).

### Quick Deploy Script

```bash
./scripts/deploy.sh
```

This builds the debug APK, installs it, clears data, and launches SplashActivity.

### What to Test

After deploying, verify these core flows:

1. **First-run extraction** completes with progress bar (SplashActivity)
2. **Editor** opens, can create/edit/save files
3. **Terminal** opens with bash, `node --version` and `python3 --version` work
4. **Extensions** can be searched and installed from Open VSX
5. **Git** works in terminal and SCM panel
6. **Extra Key Row** appears when keyboard is open, Ctrl+S / Ctrl+P work
7. **Crash recovery** -- kill Node.js process (`adb shell kill <PID>`), app auto-restarts

## How to Add a New Bundled Tool

To bundle a new tool (e.g., a new CLI binary from Termux):

### 1. Create or modify a download script

Add the download logic to `scripts/download-termux-tools.sh` or create a new script in `scripts/`. The general pattern:

```bash
# Download .deb from Termux APT repo
curl -o tool.deb "https://packages.termux.dev/apt/termux-main/pool/main/t/tool/tool_VERSION_aarch64.deb"

# Extract (macOS uses bsdtar, Linux uses dpkg-deb)
bsdtar -xf tool.deb data.tar.xz
tar xf data.tar.xz

# Copy binary to jniLibs (rename to lib<name>.so for .so trick)
cp data/data/com.termux/files/usr/bin/tool android/app/src/main/jniLibs/arm64-v8a/libtool.so

# Copy shared library dependencies to assets/usr/lib/ (if any)
cp data/data/com.termux/files/usr/lib/libdep.so android/app/src/main/assets/usr/lib/
```

### 2. Register the binary symlink in FirstRunSetup.kt

In `android/app/src/main/kotlin/com/vscodroid/setup/FirstRunSetup.kt`, add the tool to `setupToolSymlinks()`:

```kotlin
// In setupToolSymlinks():
createSymlink("libtool.so", "tool")  // Creates usr/bin/tool -> nativeLibraryDir/libtool.so
```

### 3. Add to PATH (if needed)

Tools in `usr/bin/` are already on PATH (configured in `util/Environment.kt`). If the tool needs additional env vars, add them in `Environment.kt`.

### 4. Handle shared library dependencies

If the tool depends on shared libraries, place them in `assets/usr/lib/`. The `LD_LIBRARY_PATH` in `Environment.kt` already includes this directory. Verify the SONAME matches what the binary expects (some Termux libs have versioned sonames like `libreadline.so.8`).

### 5. Handle AAPT asset filtering

Gradle's AAPT ignores files starting with `_` by default. If your tool ships directories like `__generated__/`, the build will silently drop them. The project overrides `aaptOptions.ignoreAssetsPattern` in `build.gradle.kts` for this reason, but verify your files are included in the APK.

### 6. Update FirstRunSetup.kt for new assets

If you add new standalone files to `assets/` (not inside existing extracted directories), you must explicitly add them to the extraction list in `FirstRunSetup.kt`.

## How to Add a New Patch

VS Code Server's JavaScript is minified. Patches are applied by `scripts/download-vscode-server.sh` using Python regex replacements.

### Understanding the Patch System

The download script modifies three key files:
- `workbench.js` -- the VS Code Web Client (UI)
- `server-main.js` (or the server entry point) -- the VS Code Server
- `extensionHostProcess.js` -- the Extension Host

Patches are applied as Python one-liners or inline scripts. Example from the download script:

```python
python3 -c "
import re, sys
with open('path/to/workbench.js', 'r') as f:
    content = f.read()
content = re.sub(r'pattern_to_find', 'replacement', content)
with open('path/to/workbench.js', 'w') as f:
    f.write(content)
"
```

### Steps to Add a New Patch

1. **Identify the code to patch.** Use Chrome DevTools to inspect the minified source and find the pattern. Connect DevTools via `adb forward` (see Testing section above).

2. **Write a regex pattern** that matches the minified code. Minified variable names change between VS Code versions, so use `\w+` for variable names and match on structural patterns.

3. **Add the patch to `scripts/download-vscode-server.sh`** in the appropriate section (workbench patches, server patches, or extension host patches).

4. **Test the patch** by running the download script and deploying to device:
   ```bash
   ./scripts/download-vscode-server.sh
   cd android && ./gradlew assembleDebug
   # Install and test
   ```

5. **Document the patch** in the script with a comment explaining what it fixes and why.

### Tips for Patching Minified Code

- Variable names like `a`, `t`, `e` change between versions. Match on structure, not names.
- Boolean alias patterns (e.g., `Pt=me.platform==="linux"`) are high-impact -- fixing one variable fixes all downstream uses.
- Use `python3` for regex, not `sed` (`sed -i` behaves differently on macOS vs Linux).
- Always verify the regex matched by checking the file size or grepping for the replacement string.

## Code Style

### Kotlin

- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html).
- No auto-formatter is enforced yet. Keep formatting consistent with surrounding code.
- Prefer `val` over `var`.
- Use meaningful names. Add KDoc for public APIs.

### JavaScript / Node.js

- The `assets/server.js`, `assets/process-monitor.js`, and `assets/platform-fix.js` files are hand-written JavaScript (not minified). Keep them readable.
- Use `const`/`let`, not `var`.
- No TypeScript -- these run directly on the bundled Node.js.

### Shell Scripts (scripts/)

- Must work on macOS bash 3.2 (the default shell on macOS) -- no bash 4+ features.
- Use `set -euo pipefail` at the top of scripts.
- Use `bsdtar` for `.deb` extraction (macOS compatibility).
- Quote all variable expansions.

### General

- Keep functions small and focused.
- Comment non-obvious logic, especially patches and workarounds.
- Avoid unnecessary dependencies.

## Pull Request Process

### Before You Start

1. Check [existing issues](https://github.com/rmyndharis/VSCodroid/issues) and [PRs](https://github.com/rmyndharis/VSCodroid/pulls) to avoid duplicating work.
2. For large changes, open an issue first to discuss the approach.
3. Issues labeled [`good first issue`](https://github.com/rmyndharis/VSCodroid/labels/good%20first%20issue) or [`help wanted`](https://github.com/rmyndharis/VSCodroid/labels/help%20wanted) are good starting points.

### Making Changes

1. Create a branch from `main`:
   ```bash
   git fetch upstream
   git checkout -b feature/my-change upstream/main
   ```

2. Make your changes. Test on a physical device if possible.

3. Commit with a clear message following [Conventional Commits](https://www.conventionalcommits.org/):
   ```
   feat(terminal): add tmux session management
   fix(webview): resolve keyboard overlap on Android 14
   docs(readme): update build instructions
   ```

### Submitting

1. Push your branch and open a Pull Request against `main`.
2. Fill out the PR description:
   - What does this change do?
   - How was it tested? (device model, Android version)
   - Screenshots or recordings for UI changes.
   - Reference related issues (e.g., `Fixes #123`).
3. Address review feedback and push updates.

### PR Checklist

- [ ] Tested on physical ARM64 device (if applicable)
- [ ] No unrelated changes included
- [ ] Commit messages follow Conventional Commits format
- [ ] Documentation updated (if behavior changes)
- [ ] Download scripts still work (if assets changed)
- [ ] App builds without errors (`./gradlew assembleDebug`)

## Reporting Bugs

Before reporting, check [existing issues](https://github.com/rmyndharis/VSCodroid/issues).

Include in your bug report:
- **Device model** and **Android version**
- **WebView version** (Settings > Apps > Android System WebView)
- **Steps to reproduce**
- **Expected vs actual behavior**
- **Screenshots or screen recordings** if applicable
- **adb logcat** output if the app crashes

## Suggesting Features

Use the [Feature Request template](https://github.com/rmyndharis/VSCodroid/issues/new?template=feature_request.md). Describe:
- The problem your feature would solve
- Your proposed solution
- Alternatives you have considered

## Questions?

- [Discussions](https://github.com/rmyndharis/VSCodroid/discussions) -- for questions and general discussion
- [Issues](https://github.com/rmyndharis/VSCodroid/issues) -- for bugs and feature requests

---

Thank you for helping make VSCodroid better.
