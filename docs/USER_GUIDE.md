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

1. **Install**. Download from the [Play Store](#) or [GitHub Releases](https://github.com/rmyndharis/VSCodroid/releases). The core download is approximately 135 MB, and you need about 875 MB free for the extraction that follows.
2. **Binary extraction** -- On first launch, VSCodroid extracts bundled tools (Node.js, Python, Git, Bash, and others) to internal storage. That is the same ~875 MB named above, unpacked one file at a time behind a progress bar, so allow minutes rather than seconds on a slower device. It only happens once.
3. **Language Picker** -- A prompt asks "What do you code in?" with options for Go, Ruby, and Java. This is the only time you are *asked*, but not your only chance to choose: touch and hold the app icon and pick **Manage toolchains** to add or remove them later. Whatever you select downloads there on the setup screen, one at a time; a download that fails is skipped and the rest continue. Skip goes straight to the editor.
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

Open the Command Palette (**Ctrl+Shift+P**) and run `Preferences: Open Settings (UI)`.
VSCodroid stores settings in `~/.vscodroid/`. Key defaults:

- Word wrap is enabled in the diff editor.
- Git path is preconfigured to the bundled Git binary.
- The terminal profile points to the bundled Bash.

To edit settings as JSON, use the Command Palette: `Preferences: Open User Settings (JSON)`.

### Extra Key Row

When the soft keyboard is visible, a row of extra keys appears above it. The row
holds five pages. Swipe it left or right to change page; the dots underneath show
which page you are on.

**Page 1, essential coding keys:**

| Key | Purpose |
|-----|---------|
| **Tab** | Indentation, accept autocomplete |
| **Esc** | Close menus, cancel operations |
| **Ctrl** | Modifier for shortcuts (Ctrl+S, Ctrl+Z, etc.) |
| **Alt** | Modifier for shortcuts (Alt+Up/Down to move lines) |
| **Shift** | Modifier for selections and uppercase |
| **trackpad** | The wide pad. Drag to move the cursor; see below |
| **{}** | Opening curly brace |
| **()** | Opening parenthesis |

`{}` and `()` insert only the opening character. The editor closes the pair for
you and leaves the cursor between the two.

**Page 2, common symbols:** `;` `:` `"` `/` `|` `` ` `` `&` `_`

**Page 3, brackets and operators:** `[` `]` `<` `>` `=` `!` `#` `@`

**Page 4, function keys:** `F1` through `F8`

**Page 5, the rest of the function keys and navigation:** `F9` through `F12`,
`Home`, `End`, `PgUp`, `PgDn`

Pages 4 and 5 are the only place a touch user can reach any of those keys: no
other page carries a function key, and the trackpad sends arrows only. Any
shortcut the editor or an extension binds to one is now a tap away. They are
split over two pages rather than crowded onto one because the row divides its
width evenly among whatever a page holds, and sixteen keys on one page would put
every one of them below the size a finger can reliably hit.

#### The trackpad

The wide pad on page 1 stands in for the four arrow keys. Drag it and the cursor
moves. It sends real arrow keys, so it works anywhere an arrow key does, the
terminal included, and a diagonal drag moves on both axes at once.

It has three gears, and which one you are in depends on how far your finger has
travelled since the drag began, not on how fast you are moving it. A short drag
steps character by character. Keep going in the same stroke and the same amount
of finger travel starts buying more movement, twice over, so one long drag
crosses lines and then whole screens. Lift your finger and the next drag starts
in the first gear again.

#### Long press

Touch and hold a key that has alternates and a small popup offers them:

| Key | Alternates |
|-----|------------|
| `{}` | `[` and `<` |
| `()` | `]` and `>` |
| `"` | `'` and `` ` `` |
| `/` | `\` |
| `` ` `` | `~` |

Every other key, Tab, Esc and the three modifiers included, sends one press on a
long hold, the same press a tap sends. Nothing on this row repeats.

#### Modifiers

**Ctrl, Alt, and Shift are sticky** -- tap once to activate for the next keypress. Tap again to deactivate. They highlight when active.

Two things behave differently from "the next keypress". Shift stays held for a
whole trackpad drag, so dragging with Shift on selects text rather than moving
the cursor once. And all three clear by themselves when the soft keyboard hides.

The keys on each page are defined in
`android/app/src/main/kotlin/com/vscodroid/keyboard/KeyPageConfig.kt`, and the
trackpad's gears in `TrackpadGesture.kt` beside it.

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
node -v           # Node.js 24.x
npm -v            # npm 11.x
python3 --version # Python 3.14.x
pip --version     # pip (bundled with Python)
git --version     # Git 2.55.x
bash --version    # Bash 5.3.x
tmux -V           # tmux 3.7.x
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

- **Material Icon Theme** -- file and folder icons
- **ESLint** -- JavaScript/TypeScript linting
- **Prettier** -- code formatting
- **Tailwind CSS IntelliSense** -- Tailwind autocomplete
- **Python** -- Python language support

VSCodroid also ships four of its own, which do not appear in the marketplace:
the Get Started walkthrough, the device-folder bridge, **Serve on Network**, and
the process monitor in the status bar.

Themes are not bundled. VSCodroid opens on the editor's own dark theme; install
whichever you prefer from the marketplace.

### Recommended Extensions to Install

| Extension | What It Does |
|-----------|--------------|
| **Error Lens** | Show errors inline in the editor |
| **Auto Rename Tag** | Rename paired HTML/XML tags |
| **Path Intellisense** | Autocomplete file paths |
| **REST Client** | Send HTTP requests from the editor |

### Extension Webviews

Extensions that use webview panels (such as theme configurators, documentation viewers, and AI assistants) render correctly in VSCodroid.

### What Is Not Available

Some extensions are exclusive to the Microsoft Marketplace and not published on Open VSX. Notable examples include Microsoft's C/C++ extension and GitHub Copilot. For most cases, open-source alternatives exist on Open VSX.

---

## SSH and Git

### Generating an SSH Key

Generate the key from the terminal, naming the output file explicitly:

```bash
ssh-keygen -t ed25519 -C "your@email.com" -f ~/.ssh/id_ed25519
```

Press Enter twice at the passphrase prompts to leave the passphrase empty.

Name the output path with `-f` rather than accepting the default. OpenSSH derives its
default key path from the system user database, which an Android app sandbox does not
provide, so the default can resolve somewhere unwritable and fail with
`Saving key "..." failed: No such file or directory`.

### Copying Your Public Key

Print the public key in the terminal, then select the output and copy it:

```bash
cat ~/.ssh/id_ed25519.pub
```

Paste it into your GitHub, GitLab, or Bitbucket account under Settings > SSH Keys.

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

When running a local dev server (Vite, Next.js, Express, Flask, etc.), you can preview it
**inside the editor**, in a tab beside your code:

1. Start the dev server in the terminal:
   ```bash
   npm run dev
   # Output: Local: http://localhost:5173/
   ```
2. Open the Command Palette (**Ctrl+Shift+P**) and run `Simple Browser: Show`
3. Enter the URL, for example `http://127.0.0.1:5173/`

The page opens in an editor tab with back, forward and reload buttons, and an icon to
hand the page to your device's browser if you would rather see it full screen. Edit,
save, tap reload — without leaving the app.

Any loopback port works, whatever port your dev server picked.

#### Opening in the device's browser instead

If you would rather use the device browser, the terminal route still works:

1. Open the Command Palette and run `Terminal: Open Last URL Link`
2. The page opens in your device's browser

The terminal underlines the URL, but tapping it does nothing: VS Code only follows a
terminal link on Ctrl+click, and a touch tap carries no Ctrl. The command above exists
for exactly this case. `Terminal: Open Detected Link...` lets you pick from every link
currently on screen instead of just the last one.


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

The Language Picker appears on first launch. Select the languages you want and they
download in the background.

Toolchains are never bundled inside the APK. Play Store installs fetch them as
on-demand asset packs; sideloaded installs download them over HTTP from the
[latest GitHub Release](https://github.com/rmyndharis/VSCodroid/releases/latest).
Either way they land in the app's own storage and survive app updates.

### Installing After Setup

The Language Picker is shown only once, but the screen it offers stays reachable.
**Touch and hold the VSCodroid icon** — on the home screen or in the app drawer — and
choose **Manage toolchains**. Installing and removing work exactly as they do during
setup, so a language you skipped is not lost.

The shortcut deliberately does not go through the editor. Reaching this screen matters
most when the editor is the part that will not start, so the way in does not depend on
it.

### Using Installed Toolchains

New terminals automatically pick up toolchain PATH changes. No app restart is needed.

```bash
# Go: the toolchain installs and runs, but it cannot compile on Android.
# `go build` and `go run` are refused. See Known Limitations below.
go version
go env GOROOT
mkdir hello && cd hello
go mod init hello

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

Removing an installed toolchain is not reachable from the editor yet — see
[Installing After Setup](#installing-after-setup).

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

### Toolchains Run Only Through bash, and Go Cannot Compile

Android refuses to execute any file inside an app's data directory, which is
where installed toolchains live. VSCodroid works around this by defining a shell
function for each toolchain binary that hands the file to the system loader, so
typing `go`, `ruby` or `javac` in a terminal works.

The same definitions reach bash when it is not your terminal, so a VS Code task,
an npm lifecycle script and `bash -c "..."` find them too. `npm` and `npx` are
the same kind of function and are reached the same way.

What a function cannot reach is anything that never gets to bash. Those starts
receive the file rather than the function, and Android refuses it:

- `make`, whose recipes run under `/bin/sh` rather than the bash VSCodroid sets up
- processes an extension starts, including language servers
- a compiler that starts its own sub-tools
- a script run by its own path instead of through bash

That third case is why **Go cannot compile on VSCodroid**. `go build` and
`go run` start the compiler, assembler and linker as separate programs, and
those starts do not go through the shell function. `go version`, `go env` and
`go mod` work; building does not.

Ruby and Java are reached the same way and hit the same wall whenever something
starts them without a shell, an extension or a Makefile recipe for example.
Typing `ruby` or `javac`, or running either from a task, goes through the shell
function and works.

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

### Microsoft-only Extensions

Extensions exclusive to the Microsoft Marketplace (such as Microsoft C/C++ and some other Microsoft-published extensions) are not available on Open VSX. Check Open VSX for community-maintained alternatives. GitHub Copilot Chat is not affected: it ships built in and works on device.

### Extensions That Bundle a Compiled Program

Extensions written in JavaScript, TypeScript or WebAssembly work. An extension
that carries a program compiled for desktop Linux may not, and when it does not,
the way it fails is the real problem: it installs, it shows as enabled in the
Extensions panel, and then its features are simply absent. No error, no
notification, nothing on screen. That silence is the editor's own behaviour, not
a fault in VSCodroid: a release build writes an extension's startup failure to a
log and deliberately raises no notification for it.

Two walls stand behind this, and they are not the same wall. The first is that
Android's C library is not the one desktop Linux distributions build against, so
a whole program compiled for them cannot start here at all. An add-on loaded
into the editor is the softer case: VSCodroid ships a compatibility layer that
lets add-ons built for desktop Linux load anyway, and the ones inside the app
depend on it. That layer is generated from the add-ons the app itself carries,
supplying the library names and the exact functions those ask for, so an add-on
you install later loads only if what it asks for happens to fall inside that
same set. An add-on built against GNU's C++ standard library is outside it
altogether, and even a load that succeeds is not a promise: the two C libraries
lay some structures out differently, so an add-on can start and then misbehave.

The second wall is that Android refuses to execute any file inside an app's own
storage, which is exactly where an installed extension lives, so even a
correctly built program has to be handed to a loader by something else.
VSCodroid asks the marketplace for the musl build wherever an extension
publishes one, which is what makes that route possible at all, but the extension
itself then has to offer a setting that lets its command be prefixed. Almost
none do.

**How to recognise it.** The extension is installed and enabled, its commands are
missing from the Command Palette or do nothing when run, and the Problems panel
and status bar stay empty. Run **Developer: Show Logs...** from the Command
Palette and read the extension host log; the reason is there and nowhere else.

**One variant is loud rather than silent.** An extension that publishes a
separate build per platform, with no musl build and no platform-independent one,
refuses to install at all, with a dialog reading `The 'publisher.name' extension
is not available in VSCodroid for the Alpine ARM 64 platform.` Alpine is not what
your phone is running; it is the build VSCodroid asks for, for the reason above.
Read that message as "this extension publishes nothing that can run here".

**What to install instead.** Prefer language support written in JavaScript or
TypeScript, or compiled to WebAssembly. On an extension's Open VSX page, a
download list naming several operating systems and processors is certain to be
shipping a compiled program. A single download covering all platforms is not
proof of the opposite: some extensions carry a compiled helper inside that one
package, and the helper is the part that can fail.

### The Interface Is English Only

Menus, commands, settings descriptions and dialogs are in English, and no setting
changes that. A language pack from Open VSX installs and enables normally,
**Configure Display Language** lists it, and choosing it offers to reload. The
editor comes back in English.

The translated text is not on the device, and there is nowhere to fetch it from.
The editor loads its interface strings from two places at startup: an English
bundle that ships inside the app, and a translated bundle downloaded from an
address held in the editor's product configuration. The open-source editor source
carries no such address and VSCodroid adds none, so the download URL is empty and
only the English bundle ever loads. The editor's server side is started with
English fixed as well, so an extension's own commands and settings stay English
too.

Nothing reports any of this. The language pack shows as installed and enabled;
the only sign is that the words do not change. If you have already picked a
language, **Clear Display Language Preference** from the Command Palette puts the
setting back.

### No Multi-window

VS Code's web client runs as a single window. You cannot open multiple VS Code windows side by side. However, you can use Android's split-screen mode to pair VSCodroid with another app (like a browser for previewing).

### Storage

Core installation extracts approximately 810 MB to internal storage. With all three toolchains installed, expect around 1.15 GB. Setup needs about 875 MB free before it starts, which is more than it ends up occupying because extraction needs room to work; the app quotes that figure if it refuses to start. Beyond it, keep a few hundred MB free for node_modules, build artifacts and caches.

---

## Troubleshooting

### White Screen on Launch

If the app shows a white screen after opening:

1. Wait 10-15 seconds -- the Node.js server may still be starting.
2. If it persists, force-close the app and reopen it.
3. If the issue continues, clearing app data forces a fresh extraction. **Read the
   warning below before you do it.**

> **Clearing app data deletes every project in `~/projects/`.**
>
> Android's Clear Data removes the app's external files directory as well as its
> internal storage, and `~/projects/` lives in the first of those. Nothing is
> backed up, and on Android 11 and later that directory is not reachable from
> most file managers, so the files cannot be recovered afterwards.

Rescue anything unsaved first. Which route is open to you depends on whether the
editor still works, and on a white screen it does not:

**If the editor will not open**, the only routes are from a computer, because
every in-app route needs the editor. With USB debugging on:

```
adb pull /storage/emulated/0/Android/data/com.vscodroid/files/projects
```

Some devices also expose that path over MTP when plugged in, so a file manager on
the computer can copy it. Both work while the app is unusable, which is the
situation this section is about.

**If the editor does open** and you are clearing data for some other reason, two
in-app routes exist:

- Push to a remote, if the project is a git repository. This is the only route
  that preserves history.
- Or run **VSCodroid: Open Folder from Device** from the Command Palette
  (**Ctrl+Shift+P**), choose a folder outside the app such as Documents, and copy
  your work there. A folder opened that way lives outside the app's storage, so
  Clear Data does not touch it. **It does not carry `.git` or `.env`**, along
  with `node_modules`, `.gradle`, `.idea`, `venv` and `__pycache__`: the
  device-folder sync skips those directories, so this rescues your files but not
  your repository history and not your local configuration.

Then clear app data from Settings > Apps > VSCodroid > Clear Data and relaunch.

### Terminal Commands Not Found

If `node`, `python3`, `git`, or other tools show "command not found":

1. Open a new terminal tab. PATH is set up when a new bash session starts.
2. Verify the tool exists: `ls -la $(which node)` (should point to the bundled binary).
3. If the issue persists, close the app completely and reopen.

### Extensions Not Installing

1. Check your internet connection -- extension search and download require connectivity.
2. Search directly on [open-vsx.org](https://open-vsx.org) to verify the extension exists there.
3. Some extensions require a newer editor than the one you have. Run **About** from the Command Palette (`Ctrl+Shift+P`) to see which version VSCodroid is built on, compare it with the extension's requirement on open-vsx.org, and try an older version of the extension if it asks for more. An extension that needs a newer editor does not report an error -- it installs, never activates, and logs nothing, so this is worth checking whenever a freshly installed extension appears to do nothing.

### npm Install Fails

If `npm install` fails with errors:

- **EACCES / permission errors** -- make sure you are working inside `~/projects/` or your home directory, not in a system path.
- **node-gyp / compilation errors** -- the package requires native compilation. Use a pure JS alternative (see [Known Limitations](#native-npm-packages)).
- **Network timeout** -- check your internet connection. npm uses `--prefer-offline` by default, so cached packages install without network.

### Git Push/Pull Fails

- **Permission denied (publickey)** -- generate an SSH key with `ssh-keygen -t ed25519` in the terminal and add it to your GitHub/GitLab account.
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

To remove an installed toolchain, touch and hold the app icon and choose
**Manage toolchains**. It is not reachable from inside the editor, deliberately —
see [Installing After Setup](#installing-after-setup).

### App Crashes or Restarts Unexpectedly

This is usually caused by Android's memory management killing background processes:

1. Close other apps to free RAM.
2. Reduce the number of open terminal tabs.
3. Check the process monitor in the status bar -- if phantom count is high, close unused terminals.
4. On devices with 4 GB RAM or less, consider keeping only one project open at a time.

### Dev Server Not Accessible in Browser

If the browser opens but the page does not load:

1. Verify the server is running in the terminal (check for errors).
2. Make sure you are using `http://localhost:PORT`, not `http://127.0.0.1:PORT` or `http://0.0.0.0:PORT`.
3. Some frameworks bind to `127.0.0.1` by default. Try starting with `--host 0.0.0.0` or `--host localhost`.

### WebView Crash Recovery

If the editor UI crashes but the app stays open, VSCodroid automatically recovers the WebView and reconnects to the running server. Your terminal sessions and unsaved work in the editor state are preserved. If auto-recovery fails, force-close and reopen the app.

---

*VSCodroid is built from the MIT-licensed Code - OSS source code. Not affiliated with or endorsed by Microsoft Corporation. "Visual Studio Code" and "VS Code" are trademarks of Microsoft. Uses Open VSX extension registry, not Microsoft Marketplace.*
