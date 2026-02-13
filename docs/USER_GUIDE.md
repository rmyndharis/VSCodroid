# VSCodroid User Guide

A practical guide to using VSCodroid -- the full VS Code IDE running natively on your Android device.

---

## Table of Contents

1. [First Launch](#first-launch)
2. [Editor Basics](#editor-basics)
3. [Terminal](#terminal)
4. [Extensions](#extensions)
5. [SSH and Git](#ssh-and-git)
6. [Web Development](#web-development)
7. [On-demand Toolchains](#on-demand-toolchains)
8. [Tips and Tricks](#tips-and-tricks)
9. [Known Limitations](#known-limitations)
10. [Troubleshooting](#troubleshooting)

---

## First Launch

### What Happens on First Open

1. **Install** -- Download from the [Play Store](#) or [GitHub Releases](https://github.com/rmyndharis/VSCodroid/releases). The core download is approximately 150-200 MB.
2. **Binary extraction** -- On first launch, VSCodroid extracts bundled tools (Node.js, Python, Git, Bash, and others) to internal storage. This takes 5-10 seconds and only happens once.
3. **Language Picker** -- A prompt asks "What do you code in?" with options for Go, Ruby, and Java. Select any you want now, or skip and add them later. Selected toolchains download automatically via the Play Store.
4. **Ready** -- The VS Code editor loads with terminal, file explorer, and all bundled tools available immediately.

### Default File Locations

| Item | Path |
|------|------|
| Projects folder | `~/projects/` |
| Settings and data | `~/.vscodroid/` |
| SSH keys | `~/.ssh/` |

All files are stored in the app's private sandbox. No root access is required.

---

## Editor Basics

VSCodroid is VS Code. If you have used VS Code on desktop, everything works the same way.

### Opening Files and Folders

- Use **File > Open Folder** or the Explorer sidebar to navigate your projects.
- Create new files with **Ctrl+N** or by right-clicking in the Explorer.
- The default workspace is `~/projects/`. Create subdirectories there for each project.

### Tabs

- Open files appear as tabs at the top of the editor.
- **Ctrl+W** closes the current tab.
- **Ctrl+Tab** switches between open tabs.
- Drag tabs to reorder them.

### Command Palette

The Command Palette is the fastest way to access any feature. Open it with **Ctrl+Shift+P** and start typing what you want to do.

Common commands:

- `Format Document` -- auto-format the current file
- `Change Language Mode` -- set syntax highlighting for a file
- `Preferences: Open Settings (UI)` -- open the settings editor
- `Preferences: Open Keyboard Shortcuts` -- view and customize shortcuts

### Settings

Settings are accessed through **Ctrl+,** or the Command Palette. VSCodroid stores settings in `~/.vscodroid/`. Key defaults:

- Word wrap is enabled in the diff editor.
- Git path is preconfigured to the bundled Git binary.
- The terminal profile points to the bundled Bash.

To edit settings as JSON, use the Command Palette: `Preferences: Open User Settings (JSON)`.

### Extra Key Row

When the soft keyboard is visible, a row of extra keys appears above it:

| Key | Purpose |
|-----|---------|
| **Tab** | Indentation, accept autocomplete |
| **Esc** | Close menus, cancel operations |
| **Ctrl** | Modifier for shortcuts (Ctrl+S, Ctrl+Z, etc.) |
| **Alt** | Modifier for shortcuts (Alt+Up/Down to move lines) |
| **Shift** | Modifier for selections and uppercase |
| **+** | Plus sign |
| **{ }** | Curly braces |
| **( )** | Parentheses |

**Ctrl, Alt, and Shift are sticky** -- tap once to activate for the next keypress. Tap again to deactivate. They highlight when active.

### Common Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| Ctrl+P | Quick Open (search files by name) |
| Ctrl+Shift+P | Command Palette |
| Ctrl+S | Save file |
| Ctrl+Z | Undo |
| Ctrl+Shift+Z | Redo |
| Ctrl+/ | Toggle line comment |
| Ctrl+D | Select next occurrence |
| Ctrl+Shift+K | Delete entire line |
| Alt+Up / Alt+Down | Move line up/down |
| Ctrl+` | Toggle terminal |
| Ctrl+B | Toggle sidebar |
| Ctrl+Shift+E | Focus file explorer |
| Ctrl+Shift+F | Search across files |
| Ctrl+Shift+X | Open extensions panel |

---

## Terminal

Open the terminal with **Ctrl+`** or from the menu bar. VSCodroid includes a full terminal with real PTY support -- interactive programs like vim, tmux, and readline all work natively.

### Bundled Tools

All tools are available immediately with no installation or setup:

```
node -v           # Node.js 20.18.1
npm -v            # npm 10.8.2
python3 --version # Python 3.12.12
pip --version     # pip (bundled with Python)
git --version     # Git 2.53.0
bash --version    # Bash 5.3.9
tmux -V           # tmux 3.6a
make --version    # GNU Make 4.4.1
ssh -V            # OpenSSH (bundled client)
rg --version      # ripgrep (powers VS Code search)
```

### Using the Extra Key Row in Terminal

The Extra Key Row is especially useful in the terminal:

- **Ctrl+C** -- interrupt a running process (tap Ctrl, then tap C on keyboard)
- **Ctrl+D** -- send EOF / exit the shell
- **Ctrl+L** -- clear the terminal screen
- **Tab** -- autocomplete file and directory names
- **Esc** -- switch to normal mode in vim
- **Arrow keys** -- navigate command history (Up/Down) and cursor (Left/Right)

### Multiple Terminals

- Click the **+** icon in the terminal panel to open a new terminal.
- Click the dropdown to switch between terminals.
- Each terminal is an independent bash session with its own working directory.

### Running Code

```bash
# Run a JavaScript file
node app.js

# Run a Python script
python3 script.py

# Start a Node.js project
mkdir my-app && cd my-app
npm init -y
npm install express
node index.js
```

---

## Extensions

VSCodroid uses the [Open VSX](https://open-vsx.org) extension registry. This is a free, open alternative to the Microsoft Marketplace. Most popular extensions are available.

### Searching and Installing

1. Open the Extensions panel: **Ctrl+Shift+X** or click the Extensions icon in the sidebar.
2. Type the extension name in the search box.
3. Click **Install** on the extension you want.

Extensions are downloaded from Open VSX and persist across app restarts.

### Pre-installed Extensions

These extensions come bundled with VSCodroid:

- **One Dark Pro** -- dark theme
- **ESLint** -- JavaScript/TypeScript linting
- **Prettier** -- code formatting
- **Tailwind CSS IntelliSense** -- Tailwind autocomplete
- **GitLens** -- Git annotations and history
- **Python** -- Python language support

### Recommended Extensions to Install

| Extension | What It Does |
|-----------|--------------|
| **Error Lens** | Show errors inline in the editor |
| **Auto Rename Tag** | Rename paired HTML/XML tags |
| **Path Intellisense** | Autocomplete file paths |
| **Material Icon Theme** | Better file icons |
| **REST Client** | Send HTTP requests from the editor |

### Extension Webviews

Extensions that use webview panels (such as theme configurators, documentation viewers, and AI assistants) render correctly in VSCodroid.

### What Is Not Available

Some extensions are exclusive to the Microsoft Marketplace and not published on Open VSX. Notable examples include Microsoft's C/C++ extension and GitHub Copilot. For most cases, open-source alternatives exist on Open VSX.

---

## SSH and Git

### Generating an SSH Key

1. Open the Command Palette: **Ctrl+Shift+P**
2. Type: **VSCodroid: Generate SSH Key**
3. An ed25519 key pair is created at `~/.ssh/id_ed25519`
4. The public key is displayed in a notification

### Copying Your Public Key

1. Open the Command Palette: **Ctrl+Shift+P**
2. Type: **VSCodroid: Copy SSH Public Key**
3. The public key is copied to your clipboard
4. Paste it into your GitHub, GitLab, or Bitbucket account under Settings > SSH Keys

Alternatively, view it from the terminal:

```bash
cat ~/.ssh/id_ed25519.pub
```

### Configuring Git

Set your identity before making commits:

```bash
git config --global user.name "Your Name"
git config --global user.email "you@example.com"
```

### Cloning a Repository

```bash
# SSH (after adding your key to GitHub)
git clone git@github.com:username/repo.git

# HTTPS
git clone https://github.com/username/repo.git
```

### Common Git Operations

```bash
git status                    # See changed files
git add .                     # Stage all changes
git commit -m "Fix bug"       # Commit
git push origin main          # Push to remote
git pull                      # Pull latest changes
git log --oneline -10         # Recent commit history
git branch feature-x          # Create a branch
git checkout feature-x        # Switch to branch
```

VS Code's built-in Source Control panel (Ctrl+Shift+G) also works for staging, committing, and viewing diffs.

### SSH Configuration

VSCodroid creates a default SSH config at `~/.ssh/config` on first launch with sensible defaults:

- `StrictHostKeyChecking accept-new` -- auto-accept new host keys on first connection
- ed25519 identity file configured
- Keepalive enabled

You can edit `~/.ssh/config` to add custom hosts:

```
Host myserver
    HostName 192.168.1.100
    User deploy
    IdentityFile ~/.ssh/id_ed25519
```

---

## Web Development

### Creating a New Project

```bash
# React with Vite
mkdir my-react-app && cd my-react-app
npm init vite@latest . -- --template react
npm install
npm run dev

# Express API
mkdir my-api && cd my-api
npm init -y
npm install express
```

### Dev Server Preview

When running a local dev server (Vite, Next.js, Express, Flask, etc.), you can preview it in your device's browser:

1. Start the dev server in the terminal:
   ```bash
   npm run dev
   # Output: Local: http://localhost:5173/
   ```
2. Open the Command Palette: **Ctrl+Shift+P**
3. Type: **VSCodroid: Open in Browser**
4. Enter the URL (e.g., `http://localhost:5173`)
5. The page opens in your default browser

You can also use the shortcut **Ctrl+Shift+B** to open the browser prompt directly.

### npm and npx

npm and npx work as expected. A few notes specific to VSCodroid:

```bash
npm init -y                    # Create package.json
npm install express            # Install a package
npm run dev                    # Run a script from package.json
npx create-react-app my-app   # Use npx to scaffold projects
```

npm uses `--prefer-offline` by default to speed up installs by using cached packages when available.

### Python Web Development

```bash
mkdir flask-app && cd flask-app
python3 -m venv venv
source venv/bin/activate
pip install flask
python3 app.py
```

### Package Compatibility

Some npm packages require C/C++ compilation and will not install because there is no compiler on the device. Use these alternatives:

| Package | Alternative | Notes |
|---------|------------|-------|
| `better-sqlite3` | `sql.js` | SQLite compiled to WASM |
| `bcrypt` | `bcryptjs` | Pure JavaScript |
| `sharp` | `jimp` | Pure JS image processing |
| `node-sass` | `sass` | Dart Sass, pure JS |
| `canvas` | `@napi-rs/canvas` or `pureimage` | Check Open VSX availability |

See the [Known Limitations](#known-limitations) section for more details.

---

## On-demand Toolchains

Beyond the bundled tools (Node.js, Python, Git, Bash), VSCodroid offers additional languages as on-demand downloads.

### Available Toolchains

| Language | Download Size | Installed Size | Includes |
|----------|--------------|----------------|----------|
| Go | On-demand | ~179 MB | go, gofmt |
| Ruby | On-demand | ~34 MB | ruby, gem, irb |
| Java (OpenJDK) | On-demand | ~146 MB | java, javac, jar |

### Installing During First Run

The Language Picker appears on first launch. Select the languages you want and they download automatically via the Play Store.

### Installing After Setup

1. Open the Command Palette: **Ctrl+Shift+P**
2. Type: **VSCodroid: Open Toolchain Settings**
3. Browse available toolchains, click **Install** on the ones you want

From GitHub Releases builds (sideloaded APK), all toolchains are bundled directly in the APK.

### Using Installed Toolchains

New terminals automatically pick up toolchain PATH changes. No app restart is needed.

```bash
# Go
go version
mkdir hello && cd hello
go mod init hello
# edit main.go
go run main.go

# Ruby
ruby -v
gem install sinatra
ruby app.rb

# Java
javac -version
javac Main.java
java Main
```

### Removing Toolchains

Open Toolchain Settings from the Command Palette and click **Remove** next to installed toolchains to free up storage.

---

## Tips and Tricks

### tmux for Persistent Sessions

tmux is bundled and works with real PTY support. Use it for long-running tasks that you want to survive terminal tab closes:

```bash
tmux new-session -s build       # Start a named session
# Run your long build...
# Ctrl+B then D to detach (session keeps running)
tmux attach -t build            # Reattach later
tmux list-sessions              # See all sessions
tmux kill-session -t build      # End a session
```

Note: tmux is a standalone tool, not integrated with VS Code's terminal tabs.

### Process Monitor

The status bar shows a phantom process count. This tells you how many background processes VSCodroid is using.

- Click the process count to see a detailed process tree in the Output panel.
- Typical count: 2 (server + file watcher), plus 1 per open terminal tab.
- If the count gets high (8+), close unused terminals and the monitor will warn you.

### Quick File Navigation

- **Ctrl+P** then start typing a filename -- the fastest way to open files in large projects.
- **Ctrl+G** to go to a specific line number.
- **Ctrl+Shift+O** to jump to a symbol (function, class) in the current file.

### Multi-cursor Editing

- **Ctrl+D** -- select the next occurrence of the current selection.
- **Ctrl+Shift+L** -- select all occurrences.
- Hold **Alt** and tap to place additional cursors (on external keyboard).

### Zen Mode

**Ctrl+K Z** enters Zen Mode -- a distraction-free fullscreen editing experience. Press **Esc Esc** (double Esc) to exit.

### Saving Battery

- Close terminals you are not using. Each open terminal is a separate bash process.
- Language servers auto-kill after 5 minutes of idle time.
- Avoid leaving dev servers running in the background when not in use.

### Keyboard Tips for Touch

- Connect a Bluetooth keyboard for the best experience with complex editing.
- Without an external keyboard, rely heavily on the Command Palette (**Ctrl+Shift+P**) and the Extra Key Row.
- Pinch-to-zoom is disabled to prevent layout issues. Use **Ctrl+= / Ctrl+-** to change font size.

---

## Known Limitations

### Native npm Packages

Packages that require C/C++ compilation (node-gyp) fail on VSCodroid because there is no C compiler on the device. This affects packages like `better-sqlite3`, `bcrypt`, `sharp`, `canvas`, and `node-sass`. Pure JavaScript or WASM alternatives exist for most of them (see the [Web Development](#package-compatibility) section).

### Android Phantom Process Limit

Android 12 and later enforce a system-wide limit of 32 phantom processes (background processes spawned by apps). VSCodroid minimizes its footprint:

| Component | Phantom Processes |
|-----------|-------------------|
| Node.js server | 1 |
| File watcher | 1 |
| Extension Host | 0 (runs as worker thread) |
| ptyHost | 0 (runs as worker thread) |
| Each terminal tab | 1 (bash) |
| Each language server | 1 (idle-killed after 5 min) |

Typical usage: 2-3 phantom processes. If you hit the limit (other apps compete for the same 32 slots), close unused terminals and check the process monitor.

### Memory Usage

VSCodroid typically uses 400-700 MB of RAM. On devices with 4 GB or less, you may experience occasional restarts under memory pressure. Tips:

- Close browser tabs and other apps to free RAM.
- Limit concurrent terminals to 1-2.
- Language servers are the biggest memory consumers and are killed when idle.

### os.cpus() Returns Empty

`os.cpus()` returns an empty array on Android. This is cosmetic -- tools that display CPU core counts may show 0, but actual performance is unaffected.

### @parcel/watcher Error

You may see a warning about `@parcel/watcher` not having a native build for Android. This is harmless. VS Code falls back to a JavaScript-based file watcher automatically.

### Microsoft-only Extensions

Extensions exclusive to the Microsoft Marketplace (such as GitHub Copilot, Microsoft C/C++, and some Microsoft-published extensions) are not available on Open VSX. Check Open VSX for community-maintained alternatives.

### No Multi-window

VS Code's web client runs as a single window. You cannot open multiple VS Code windows side by side. However, you can use Android's split-screen mode to pair VSCodroid with another app (like a browser for previewing).

### Storage

Core installation uses approximately 300-400 MB. With all toolchains installed, expect 600-800 MB. Keep at least 500 MB free for comfortable use (node_modules, build artifacts, caches).

---

## Troubleshooting

### White Screen on Launch

If the app shows a white screen after opening:

1. Wait 10-15 seconds -- the Node.js server may still be starting.
2. If it persists, force-close the app and reopen it.
3. If the issue continues, clear app data (Settings > Apps > VSCodroid > Clear Data) and relaunch. This triggers a fresh extraction.

### Terminal Commands Not Found

If `node`, `python3`, `git`, or other tools show "command not found":

1. Open a new terminal tab. PATH is set up when a new bash session starts.
2. Verify the tool exists: `ls -la $(which node)` (should point to the bundled binary).
3. If the issue persists, close the app completely and reopen.

### Extensions Not Installing

1. Check your internet connection -- extension search and download require connectivity.
2. Search directly on [open-vsx.org](https://open-vsx.org) to verify the extension exists there.
3. Some extensions may not be compatible with VS Code 1.96.4. Try an older version of the extension if available.

### npm Install Fails

If `npm install` fails with errors:

- **EACCES / permission errors** -- make sure you are working inside `~/projects/` or your home directory, not in a system path.
- **node-gyp / compilation errors** -- the package requires native compilation. Use a pure JS alternative (see [Known Limitations](#native-npm-packages)).
- **Network timeout** -- check your internet connection. npm uses `--prefer-offline` by default, so cached packages install without network.

### Git Push/Pull Fails

- **Permission denied (publickey)** -- generate an SSH key (Command Palette > "VSCodroid: Generate SSH Key") and add it to your GitHub/GitLab account.
- **SSL certificate error** -- this should not occur with bundled certificates. If it does, check that you are using `git@` (SSH) URLs instead of `https://`.

### App Uses Too Much Storage

To reclaim space:

```bash
# Clear npm cache
npm cache clean --force

# Remove node_modules from old projects
rm -rf ~/projects/old-project/node_modules

# Clear pip cache
pip cache purge

# Check disk usage
du -sh ~/projects/*
du -sh ~/.vscodroid/extensions/*
```

You can also remove on-demand toolchains you no longer need via Toolchain Settings.

### App Crashes or Restarts Unexpectedly

This is usually caused by Android's memory management killing background processes:

1. Close other apps to free RAM.
2. Reduce the number of open terminal tabs.
3. Check the process monitor in the status bar -- if phantom count is high, close unused terminals.
4. On devices with 4 GB RAM or less, consider keeping only one project open at a time.

### Dev Server Not Accessible in Browser

If "VSCodroid: Open in Browser" opens but the page does not load:

1. Verify the server is running in the terminal (check for errors).
2. Make sure you are using `http://localhost:PORT`, not `http://127.0.0.1:PORT` or `http://0.0.0.0:PORT`.
3. Some frameworks bind to `127.0.0.1` by default. Try starting with `--host 0.0.0.0` or `--host localhost`.

### WebView Crash Recovery

If the editor UI crashes but the app stays open, VSCodroid automatically recovers the WebView and reconnects to the running server. Your terminal sessions and unsaved work in the editor state are preserved. If auto-recovery fails, force-close and reopen the app.

---

*VSCodroid is built from the MIT-licensed Code - OSS source code. Not affiliated with or endorsed by Microsoft Corporation. "Visual Studio Code" and "VS Code" are trademarks of Microsoft. Uses Open VSX extension registry, not Microsoft Marketplace.*
