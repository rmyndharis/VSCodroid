# VSCodroid User Guide

A practical guide to using VSCodroid — the full VS Code IDE on your Android device.

## Table of Contents

1. [First-Run Experience](#first-run-experience)
2. [Keyboard Shortcuts & Extra Key Row](#keyboard-shortcuts--extra-key-row)
3. [Terminal](#terminal)
4. [Extensions](#extensions)
5. [SSH Key Setup](#ssh-key-setup)
6. [Toolchain Management](#toolchain-management)
7. [Opening Device Folders](#opening-device-folders)
8. [Opening Localhost in Browser](#opening-localhost-in-browser)
9. [Known Limitations & Workarounds](#known-limitations--workarounds)

---

## First-Run Experience

1. **Install** — Download from [Play Store](#) or [GitHub Releases](https://github.com/rmyndharis/VSCodroid/releases)
2. **First launch** — The app extracts core binaries (Node.js, Python, Git, Bash). This takes ~5-10 seconds and only happens once.
3. **Language Picker** — Optionally select additional languages (Go, Ruby, Java). These download via the Play Store as on-demand packs. You can skip this and add them later in Settings.
4. **Ready** — The VS Code editor loads with a terminal, file explorer, and all bundled tools ready to use.

Your default workspace is at `~/projects/`. All files are stored in the app's private sandbox.

## Keyboard Shortcuts & Extra Key Row

### Extra Key Row

When the soft keyboard is visible, an extra row of keys appears above it:

| Key | Purpose |
|-----|---------|
| **Tab** | Indentation, autocomplete accept |
| **Esc** | Close menus, cancel operations |
| **Ctrl** | Modifier for shortcuts (Ctrl+S, Ctrl+Z, etc.) |
| **Alt** | Modifier for shortcuts (Alt+Up/Down to move lines) |
| **Arrows** | Cursor navigation |
| **{ }** | Curly braces |
| **( )** | Parentheses |
| **;** | Semicolon |
| **:** | Colon |
| **"** | Double quote |
| **/** | Slash |

**Ctrl and Alt are sticky** — tap once to activate for the next keypress, tap again to deactivate. They highlight when active.

### Common Keyboard Shortcuts

These are standard VS Code shortcuts, all working in VSCodroid:

| Shortcut | Action |
|----------|--------|
| Ctrl+P | Quick Open (file search) |
| Ctrl+Shift+P | Command Palette |
| Ctrl+S | Save file |
| Ctrl+Z / Ctrl+Shift+Z | Undo / Redo |
| Ctrl+/ | Toggle line comment |
| Ctrl+D | Select next occurrence |
| Ctrl+Shift+K | Delete line |
| Alt+Up / Alt+Down | Move line up/down |
| Ctrl+` | Toggle terminal |
| Ctrl+B | Toggle sidebar |
| Ctrl+Shift+E | File explorer |
| Ctrl+Shift+F | Search across files |
| Ctrl+Shift+X | Extensions panel |

## Terminal

VSCodroid includes a full terminal with real PTY support. Open it with **Ctrl+`** or from the menu.

### Bundled Tools

All of these are available immediately, no installation needed:

```bash
node -v          # Node.js 20.18.1
npm -v           # npm 10.8.2
python3 --version # Python 3.12.12
pip --version    # pip (bundled with Python)
git --version    # Git 2.53.0
bash --version   # Bash 5.3.9
tmux -V          # tmux 3.6a
make --version   # GNU Make 4.4.1
ssh -V           # OpenSSH
rg --version     # ripgrep
```

### Creating a Project

```bash
# JavaScript/TypeScript
mkdir my-app && cd my-app
npm init -y
npm install express

# Python
mkdir my-project && cd my-project
python3 -m venv venv
source venv/bin/activate
pip install flask

# Git
git init
git add .
git commit -m "Initial commit"
```

### tmux for Advanced Users

tmux is bundled as a standalone tool. It's not integrated into VS Code's terminal system but is useful for advanced terminal management:

```bash
tmux new-session -s work      # Start a named session
tmux attach -t work           # Reattach to session
# Ctrl+B then D to detach
```

## Extensions

VSCodroid uses the [Open VSX](https://open-vsx.org) extension registry (not the Microsoft Marketplace).

### Installing Extensions

1. Open the Extensions panel: **Ctrl+Shift+X** or click the Extensions icon in the Activity Bar
2. Search for an extension by name
3. Click **Install**

Extensions persist across app restarts.

### Pre-Installed Extensions

VSCodroid bundles these extensions out of the box:

- **One Dark Pro** — Popular dark theme
- **ESLint** — JavaScript/TypeScript linting
- **Prettier** — Code formatting
- **Tailwind CSS IntelliSense** — Tailwind autocomplete
- **GitLens** — Git annotations and history
- **Python** — Python language support

### Extension Webviews

Extensions with webview UIs (like Claude Code, theme pickers, settings pages) work correctly in VSCodroid.

### What's Not Available

Some Microsoft-proprietary extensions are only on the Microsoft Marketplace and not on Open VSX. Common alternatives exist for most of them.

## SSH Key Setup

Generate SSH keys for Git authentication (GitHub, GitLab, Bitbucket) directly from VSCodroid.

### Generate a Key

1. Open Command Palette: **Ctrl+Shift+P**
2. Type: **VSCodroid: Generate SSH Key**
3. An ed25519 key pair is created in `~/.ssh/`
4. The public key is shown in a notification

### Copy Your Public Key

1. Open Command Palette: **Ctrl+Shift+P**
2. Type: **VSCodroid: Copy SSH Public Key**
3. The key is copied to your clipboard
4. Paste it into GitHub/GitLab Settings > SSH Keys

### Use SSH with Git

After adding your key to GitHub/GitLab:

```bash
git clone git@github.com:username/repo.git
git push origin main
```

SSH is configured automatically — `GIT_SSH_COMMAND` points to the bundled OpenSSH client.

## Toolchain Management

### During First Run

The Language Picker shows available toolchains. Select any you want and they'll download automatically.

### After Setup

Go to **Settings > Toolchains** (accessible via Command Palette: "VSCodroid: Open Toolchain Settings") to:
- Install new toolchains
- Remove installed ones
- See installed versions

### Available Toolchains

| Language | Size | Notes |
|----------|------|-------|
| Go | ~179 MB | CGO_ENABLED=0 by default |
| Ruby | ~34 MB | Includes gem |
| Java (OpenJDK) | ~146 MB | Includes javac, jar |

New terminals automatically pick up toolchain PATH changes — no restart needed.

## Opening Device Folders

VSCodroid can open folders from anywhere on your device using Android's Storage Access Framework (SAF).

### Open a Folder

1. Command Palette: **Ctrl+Shift+P** > search for folder picker, or use the BroadcastChannel bridge
2. Select a folder from the Android file picker
3. VSCodroid syncs the folder contents and opens it in the editor

### How It Works

Files from external storage are synced to a local mirror inside the app sandbox. Changes are synced back to the original location automatically via a file watcher. This is necessary because VS Code's file system APIs expect direct filesystem access, which Android's scoped storage doesn't allow.

### Recent Folders

Previously opened SAF folders are remembered and can be reopened quickly.

## Opening Localhost in Browser

When developing web apps, you can preview your dev server in the device's browser.

### Using the Command Palette

1. Start your dev server in the terminal (e.g., `npm run dev`)
2. **Ctrl+Shift+P** > **VSCodroid: Open in Browser**
3. Enter the URL (e.g., `http://localhost:5173`)
4. The URL opens in your device's default browser

### Keyboard Shortcut

**Ctrl+Shift+B** opens the "Open in Browser" prompt directly.

## Known Limitations & Workarounds

### Native npm Packages Don't Work

Packages that require C/C++ compilation (node-gyp) fail because there's no compiler on the device.

**Affected packages:** better-sqlite3, bcrypt, sharp, canvas, node-sass

**Workarounds:** Use pure JavaScript or WASM alternatives:
- `better-sqlite3` → `sql.js` (SQLite compiled to WASM)
- `bcrypt` → `bcryptjs` (pure JS)
- `sharp` → `jimp` (pure JS image processing)
- `node-sass` → `sass` (pure JS, Dart Sass)

### Process Limits (Android 12+)

Android limits "phantom processes" (background processes spawned by apps) to 32 system-wide. VSCodroid typically uses 2-3:

| Process | Count |
|---------|-------|
| Node.js server | 1 (ExtHost + ptyHost are worker_threads, not processes) |
| fileWatcher | 1 |
| bash (per terminal) | 1 each |
| Language servers | 1 each (idle-killed after 5 min) |

**If you hit limits:** Close unused terminals, reduce language servers, check the process monitor in the status bar.

### os.cpus().length Returns 0

On Android, `os.cpus()` returns an empty array. This is cosmetic and doesn't affect functionality — some tools may show 0 cores in diagnostics.

### @parcel/watcher Native Module Error

You may see an error about `@parcel/watcher` not having a native build for Android. This is harmless — VS Code automatically falls back to a JavaScript-based file watcher.

### Extension Marketplace Search

Searching the Open VSX marketplace requires an internet connection. Installed extensions work offline.

### No Multi-Window / Split Screen Editing

VS Code's web client is single-window. You can't open multiple VS Code windows side-by-side on Android. However, you can use Android's split-screen mode with another app.

### Storage Space

VSCodroid uses ~300-400 MB for core files. Keep at least 500 MB free for comfortable use. Clear caches via the process monitor or storage management commands if space is tight.
