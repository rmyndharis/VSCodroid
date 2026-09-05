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
7. [Running and Debugging](#running-and-debugging)
8. [On-demand Toolchains](#on-demand-toolchains)
9. [Tips and Tricks](#tips-and-tricks)
10. [Known Limitations](#known-limitations)
11. [Troubleshooting](#troubleshooting)

---

## First Launch

### What Happens on First Open

1. **Install**. Download from the [Play Store](#) or [GitHub Releases](https://github.com/rmyndharis/VSCodroid/releases). The core download is roughly 270 MB, and you need about 905 MB free for the extraction that follows.
2. **Binary extraction** -- On first launch, VSCodroid extracts bundled tools (Node.js, Python, Git, Bash, and others) to internal storage. About 805 MB lands on disk, unpacked one file at a time behind a progress bar, so allow minutes rather than seconds on a slower device. The ~905 MB above is that payload plus the working room setup insists on before it will start. It happens on the first launch and again after every app update, because the extraction is keyed on the app version rather than on what is already unpacked. An update needs far less free space than a fresh install (the app credits what it already holds, so roughly 220 MB rather than 905 MB), but it does re-copy the files and it does take minutes. A first run that is interrupted and retried on the same version is the one case that does not start over: files already the right size are left alone.
3. **Language Picker** -- A prompt asks "What do you code in?" with options for Ruby and Java. This is the only time you are *asked*, but not your only chance to choose: touch and hold the app icon and pick **Manage toolchains** to add or remove them later. Whatever you select downloads there on the setup screen, one at a time; a download that fails is skipped and the rest continue. Skip goes straight to the editor.
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

- Use **File > Open Folder** or the Explorer sidebar for projects inside the app.
  That dialog browses the app's own storage and cannot reach Documents, Downloads
  or an SD card.
- For a folder anywhere else on the device, tap the remote indicator at the left
  end of the status bar and choose **VSCodroid: Open Recent Folder**. The first
  time there are no recent folders, so it offers **Open Folder**, which opens
  Android's folder picker; after that the same command lists the folders you have
  granted, with **Browse device...** at the end to add another. Android grants
  access one folder at a time, and the folder is kept in sync both ways for as
  long as it is open.
- A `.code-workspace` file opens as a multi-root workspace: open the file and
  choose **Open Workspace**. On a device folder its roots have to sit inside the
  folder you granted, because nothing outside that folder is reachable.
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

- Word wrap is on, in the editor and in the diff editor.
- Git path is preconfigured to the bundled Git binary.
- The terminal profile points to the bundled Bash.

To edit settings as JSON, use the Command Palette: `Preferences: Open User Settings (JSON)`.

### Extra Key Row

When the soft keyboard is visible, a row of extra keys appears above it. Swipe it
left or right to change page; the dots underneath show how many pages there are
and which one you are on.

How many there are depends on how wide your phone is. The row divides its width
evenly among the keys on a page, so on a narrower screen it carries fewer keys per
page and spreads them over more: five pages on a 411dp phone and wider, six at
360dp, seven at 320dp. The keys and their order never change, only where the page
breaks fall. The tables below are the five-page layout; on a narrower phone read
them as one list that is cut in more places.

**Page 1, essential coding keys:**

| Key | Purpose |
|-----|---------|
| **Tab** | Indentation, accept autocomplete |
| **Esc** | Close menus, cancel operations |
| **Ctrl** | Modifier for shortcuts (Ctrl+S, Ctrl+Z, etc.) |
| **Alt** | Modifier for shortcuts (Alt+Up/Down to move lines) |
| **Shift** | Modifier for selections, for the row's own keys (Shift+Tab, Shift+F12), and for Ctrl or Alt chords typed on the soft keyboard (Ctrl+Shift+P) |
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

The last two pages are the only place a touch user can reach any of those keys: no
other page carries a function key, and the trackpad sends arrows only. Any
shortcut the editor or an extension binds to one is a tap away.

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
| `()` | `)`, `]` and `>` |
| `"` | `'` and `` ` `` |
| `/` | `\` |
| `` ` `` | `~` |

Long press is the only route to `)`, `'` and `\`: no page carries them, and a
latched Shift does not reach them either. The closing parenthesis matters most,
because auto-closing brackets usually supply it and leave you with no way to type
one when they do not. `~` is in the popup too, but it is not stranded there: the
row carries `` ` `` as a key of its own, and a latched Shift over it types `~`.

Every other key, Tab, Esc and the three modifiers included, sends one press on a
long hold, the same press a tap sends. Nothing on this row repeats.

#### Modifiers

**Ctrl, Alt, and Shift are sticky** -- tap once to activate for the next keypress. Tap again to deactivate. They highlight when active.

Three things behave differently from "the next keypress". Shift stays held for a
whole trackpad drag, so dragging with Shift on selects text rather than moving
the cursor once. All three clear by themselves when the soft keyboard hides. And
Shift on its own is not applied to what you type on the soft keyboard, so for a
capital letter hold the soft keyboard's own Shift; latch Ctrl or Alt as well and
the row's Shift is carried into that chord, which is how Ctrl+Shift+P is typed.

Enter, Backspace and Delete take a latched modifier as well, even though the soft
keyboard reports all three as an edit rather than as a key. So Ctrl+Enter and
Ctrl+Backspace arrive as chords: Ctrl+Enter opens a line below without splitting
the one you are on, rather than typing a plain newline.

Under a screen reader, the dots below the row are one item that reads "Key page 1
of 5", with any latched modifier named after it ("Key page 1 of 5, Ctrl+Shift
held"). It is spoken again each time you swipe to another page, which is the only
announcement that the keys under your finger have changed.

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

Open the terminal with **Ctrl+`** or from the menu bar. VSCodroid includes a full terminal with real PTY support, so full-screen and interactive programs work natively: tmux, bash line editing, and the Node and Python REPLs.

### Bundled Tools

All tools are available immediately with no installation or setup:

```
node -v           # Node.js 24.x
npm -v            # npm 11.x
python3 --version # Python 3.14.x
python3 -m pip --version   # pip, bundled inside Python's site-packages
git --version     # Git 2.55.x
bash --version    # Bash 5.3.x
tmux -V           # tmux 3.7c
make --version    # GNU Make 4.4.1
ssh -V            # OpenSSH 10.5p1 client
rg --version      # ripgrep (powers VS Code search)
```

### Using the Extra Key Row in Terminal

The Extra Key Row is especially useful in the terminal:

- **Ctrl+C** -- interrupt a running process (tap Ctrl, then tap C on keyboard)
- **Ctrl+D** -- send EOF / exit the shell
- **Ctrl+L** -- clear the terminal screen
- **Tab** -- autocomplete file and directory names
- **Esc** -- cancel a prompt, or leave copy mode in tmux
- **Arrow keys** -- navigate command history (Up/Down) and cursor (Left/Right). On a touch device
  these come from the trackpad on page 1 of the key row, not from buttons; drag it up or down to
  walk back through history

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

### Installing from a VSIX File

Run **Extensions: Install from VSIX...** from the Command Palette
(**Ctrl+Shift+P**) to install an extension you already hold as a `.vsix` file.

The picker it opens is the editor's own, not Android's. It starts in your home
folder, lists only files ending in `.vsix`, and reaches only what VSCodroid can
see: not Downloads, not Documents, not an SD card, for the same reason **File >
Open Folder** cannot. So bring the file inside first:

1. Download the `.vsix` with the phone's browser.
2. Run **VSCodroid: Open Folder from Device** and grant the folder it landed in;
   the file appears in the Explorer. A device folder is copied into the app for
   as long as it is open, so pick a folder holding little else rather than a
   Downloads folder with a year of files in it. Files over 50 MB are not carried
   in at all, so a very large VSIX has to arrive another way.
3. Open a terminal (**Ctrl+`**), which starts in that folder, and copy it
   across: `cp name.vsix ~/`.
4. Run **Extensions: Install from VSIX...**, pick the file, and choose **Reload
   Now** when the notification offers it.

Signature checking plays no part in this. `extensions.verifySignature` decides
downloads from Open VSX and nothing else; a VSIX is never checked for a
signature whatever that setting says. What you do give up is the platform choice
the marketplace makes for you: VSCodroid asks Open VSX for the Alpine ARM 64
build of an extension that publishes one per platform, and a file you pick
yourself gets no such help, so take the `alpine-arm64` one where the extension
offers it (see [Extensions That Bundle a Compiled
Program](#extensions-that-bundle-a-compiled-program)). Nothing checks that
either: a VSIX built for the wrong platform installs, shows as enabled, and does
nothing. One built for a newer editor is refused outright, with a message naming
the version.

### Pre-installed Extensions

These extensions come bundled with VSCodroid:

- **ESLint** -- JavaScript/TypeScript linting
- **Prettier** -- code formatting
- **Tailwind CSS IntelliSense** -- Tailwind autocomplete
- **Python** -- Python language support

VSCodroid also ships four of its own, which do not appear in the marketplace:
the Get Started walkthrough, the Android bridge (device folders, the device
browser, SSH keys and storage), **Serve on Network**, and the process monitor
in the status bar.

VSCodroid opens on the editor's own dark theme, and it is not the only one installed. Nineteen
colour themes ship with it: the Dark and Light defaults with their Modern and high-contrast
variants, plus Abyss, Kimbie Dark, Monokai, Monokai Dimmed, Quiet Light, Red, Solarized Dark,
Solarized Light and Tomorrow Night Blue. Switch with **Preferences: Color Theme** in the Command
Palette, no download needed. Three file-icon themes ship too, Seti among them. Anything beyond
these comes from the marketplace.

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

Some extensions are exclusive to the Microsoft Marketplace and not published on Open VSX. Notable examples include Microsoft's C/C++ extension. For most cases, open-source alternatives exist on Open VSX. GitHub Copilot Chat is the exception that needs no marketplace: it ships bundled.

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
save, tap reload, all without leaving the app.

Any loopback port works, whatever port your dev server picked.

#### If your dev server is on https

A preview served over https with a self-signed or private certificate is refused, and
the editor now names the host it blocked and says why. Before, the tab simply came up
empty and there was nothing to tell that apart from a server that was not running.

Plain `http://` is the answer for a local preview: cleartext is permitted here precisely
because that is what dev servers speak. Installing your own CA through Android Settings
does not help for a page, and this is the one place where it makes a difference which
part of the app is asking. Pages are rendered by the system WebView, which trusts the
device's system roots and nothing else. git does read a CA you installed, because the
app builds git's certificate bundle from both halves of the device trust store. So the
same certificate can clone fine in the terminal and still be refused in a preview tab.

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
python3 -m pip install flask
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

## Running and Debugging

VSCodroid ships one debug adapter: **js-debug**, the same one desktop VS Code
uses for JavaScript and TypeScript.

### The Configurations That Are Already There

Three launch configurations are written when the app first sets itself up. They
belong to VSCodroid rather than to a project, so they are offered for every
folder you open:

| Configuration | What it is for |
|---------------|----------------|
| **Node.js: Run Current File** | Runs the file in the active editor under the debugger |
| **Attach to Node.js** | Attaches to a Node process you started yourself with `--inspect`, on port 9229 |
| **NestJS: Debug** | Runs `src/main.ts` through `ts-node`, for a project that already has `ts-node` and `tsconfig-paths` installed |

The shortest route on a touch screen is **Debug: Select and Start Debugging**
from the Command Palette (**Ctrl+Shift+P**): it lists all three and starts the
one you pick. The **Run and Debug** icon in the activity bar shows the same
three in a dropdown with a start button beside it.

Set a breakpoint by tapping the gutter to the left of a line number.

To give a project configurations of its own, open **Debug: Select and Start
Debugging** and choose **Add Configuration...** at the bottom of the list. That
creates `.vscode/launch.json` in the folder, and what you put there appears in
the same list beside VSCodroid's three.

### Attaching to a Server You Started

`Attach to Node.js` is the one to reach for with a dev server, because it leaves
the server alone:

```bash
node --inspect server.js
```

Node prints `Debugger listening on ws://127.0.0.1:9229/...`; start **Attach to
Node.js** and it connects, and the process itself reports `Debugger attached.`
The configuration sets `restart`, so it reconnects when a watcher restarts the
process.

### What Has No Debugger Here At All

- **Python.** The bundled Python extension carries no debugger of its own; it
  points at Microsoft's separate `ms-python.debugpy`, which VSCodroid does not
  bundle.
- **Browsers.** js-debug drives Chrome or Edge through a companion that has to
  run on the machine you are sitting at, and that companion cannot run here.
  Debug the server side, and view the page in the preview tab or the device
  browser.
- **Everything else.** Ruby and Java are toolchains, not debuggers, and neither
  ships an adapter. A debug extension written in JavaScript and published on
  Open VSX may work; one that ships a program compiled for desktop Linux will
  not (see [Extensions That Bundle a Compiled
  Program](#extensions-that-bundle-a-compiled-program)).

---

## On-demand Toolchains

Beyond the bundled tools (Node.js, Python, Git, Bash), VSCodroid offers additional languages as on-demand downloads.

### Available Toolchains

| Language | Download Size | Installed Size | Includes |
|----------|--------------|----------------|----------|
| Ruby 3.4 | 9.9 MB | 36 MB | ruby, gem, irb, bundler, rake |
| Java 17 (OpenJDK) | 55.4 MB | 156 MB | java, javac, jar, jshell |

### Installing During First Run

The Language Picker appears on first launch. Select the languages you want and they
download in the background.

Toolchains are never bundled inside the APK. Play Store installs fetch them as
on-demand asset packs; sideloaded installs download them over HTTPS from the
[latest GitHub Release](https://github.com/rmyndharis/VSCodroid/releases/latest).
Either way they land in the app's own storage and survive app updates.

### Installing After Setup

The Language Picker is shown only once, but the screen it offers stays reachable, by
two routes. From the editor, run **VSCodroid: Manage Toolchains** from the Command
Palette (**Ctrl+Shift+P**). From outside it, **touch and hold the VSCodroid icon** (on
the home screen or in the app drawer) and choose **Manage toolchains**. Both open the
same screen, and installing and removing work exactly as they do during setup, so a
language you skipped is not lost.

The launcher shortcut is there because reaching this screen matters most when the
editor is the part that will not start, so at least one way in does not depend on it.

### Using Installed Toolchains

New terminals automatically pick up toolchain PATH changes. No app restart is needed.

```bash
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

Open the Toolchains screen by either route in [Installing After
Setup](#installing-after-setup) and remove it there.

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
- An idle session costs 5, before you open anything. Add 1 per terminal tab and 1 per running language server.
- At 8 the monitor warns you and at 14 it reports an error; both offer **Show Details**, which marks the language servers that have sat idle for five minutes or more.

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
- An idle language server is not killed, by a timer or by hand: its extension restarts it within a second. Disabling the extension that starts it is what frees the slot; **VSCodroid: Show Process Tree** marks the idle ones.
- Avoid leaving dev servers running in the background when not in use.

### Keyboard Tips for Touch

- Connect a Bluetooth keyboard for the best experience with complex editing.
- Without an external keyboard, rely heavily on the Command Palette (**Ctrl+Shift+P**) and the Extra Key Row.
- Pinch-to-zoom is disabled to prevent layout issues. Use **Ctrl+= / Ctrl+-** to change font size.

---

## Known Limitations

### Native npm Packages

Packages that require C/C++ compilation (node-gyp) fail on VSCodroid because there is no C compiler on the device. This affects packages like `better-sqlite3`, `bcrypt`, `sharp`, `canvas`, and `node-sass`. Pure JavaScript or WASM alternatives exist for most of them (see the [Web Development](#package-compatibility) section).

### Packages With Prebuilt Binaries

Some packages compile nothing. They download a ready-made binary chosen by
platform name, from a fixed list their own installer carries, and Android is not
on those lists. The install then stops with a message naming the platform it did
not recognise, such as `Unsupported platform: android arm64 LE`. `workerd`, which
Cloudflare Workers projects pull in through Wrangler, is one of these.

Nothing on the device changes that. The Android build is not published, and
taking the Linux one instead would fail later rather than sooner: it is built
against a different C library, and an app may not execute a file from its own
data directory.

`npm install --ignore-scripts` installs the rest of the tree, so everything that
does not need that particular binary works. What needs it does not run.

This is not every package with a native part. Rollup and esbuild publish Android
builds and install normally, which is why VSCodroid reports the platform it
actually is rather than pretending to be Linux.

### Toolchains Must Be Started by Name

Android refuses to execute any file inside an app's data directory, which is where
installed toolchains live. VSCodroid works around it by handing the file to the
system loader instead, and it does that two ways: a bash function per command, and
a small program on `PATH` that every other kind of start finds.

So a toolchain command works when it is started by its bare name, whoever starts
it: typing `ruby` in a terminal, `bash -c`, `sh -c`, a `make` recipe, an npm
lifecycle script, a VS Code task of either kind, and a process an extension or a
language server starts directly.

What still fails is a start that names a path instead of a command:

- an absolute path such as `$JAVA_HOME/bin/java`, which is not a `PATH` lookup at all
- a toolchain that forks its own helper by absolute path, which is what the JDK's `lib/jspawnhelper` does
- a script under the app's storage run by its own path: Android refuses the script file itself, before its `#!` line is ever read. Run it as `ruby script.rb` instead

`npm` and `npx` are bash functions and nothing else, so those two are still
reachable only from bash. `sh -c 'npm -v'` fails where `bash -c 'npm -v'` works.

### Android Phantom Process Limit

Android 12 and later enforce a system-wide limit of 32 phantom processes (background processes spawned by apps). VSCodroid minimizes its footprint:

| Component | Phantom Processes |
|-----------|-------------------|
| Bootstrap | 1 |
| Node.js server | 1 |
| File watcher | 1 |
| Chat agent host and its model backend | 2 |
| Extension Host | 0 (runs as worker thread) |
| ptyHost | 0 (runs as worker thread) |
| Each terminal tab | 1 (bash) |
| Each language server | 1 |

That is 5 on a cold start with nothing open, which is what the status bar shows
before you do anything. Nothing sheds a language server automatically: a killed one
is restarted by its extension within a second. If you hit the limit (other apps
compete for the same 32 slots), close unused terminals and disable the extensions
whose language servers **VSCodroid: Show Process Tree** marks idle.

### Memory Usage

VSCodroid typically uses 400-700 MB of RAM. On devices with 4 GB or less, you may experience occasional restarts under memory pressure. Tips:

- Close browser tabs and other apps to free RAM.
- Limit concurrent terminals to 1-2.
- Language servers are the biggest memory consumers. Nothing sheds them automatically, because their extensions restart them; disable the extensions you are not using. **VSCodroid: Show Process Tree** marks the idle ones.

### os.cpus() Returns Empty

`os.cpus()` returns an empty array on Android. This is cosmetic -- tools that display CPU core counts may show 0, but actual performance is unaffected.

### Microsoft-only Extensions

Extensions exclusive to the Microsoft Marketplace (such as Microsoft C/C++ and some other Microsoft-published extensions) are not available on Open VSX. Check Open VSX for community-maintained alternatives. GitHub Copilot Chat is not affected: it ships built in and works on device.

### Claude Code Reports "terminated by signal SIGSYS"

An Android app may only make the system calls the platform's C library exposes,
and a call outside that list is not an error a program can recover from: the
kernel stops it there. The Claude Code extension carries its own program, whose
runtime asks for `epoll_pwait2`, and that call is on the list only from Android
15 onward.

VSCodroid answers that one call itself, so sign-in and everyday use work on
Android 13 and 14 as well. If the panel still reports `Claude Code process
terminated by signal SIGSYS`, or `claude` in a terminal prints `Bad system
call`, a newer release of the extension is asking for a call VSCodroid does not
answer yet. Please report it with your Android version and the extension
version; there is nothing to change on your side.

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

### The Interface Follows Your Phone's Language

Menus, commands, settings descriptions and dialogs come up in the language the
phone is set to. There is nothing to turn on and nothing to install: change the
language in Android's Settings, then start VSCodroid.

Thirteen languages ship inside the app: Chinese (Simplified and Traditional),
Czech, French, German, Italian, Japanese, Korean, Polish, Portuguese (Brazil),
Russian, Spanish and Turkish. A phone set to Portuguese gets the Brazilian
translation wherever it is, because that is the only Portuguese the editor has
been translated into. Any other language leaves the interface in English.

The translations are the ones the desktop editor uses, built into the app from
Microsoft's `vscode-loc` packs. A display-language pack from Open VSX is neither
needed nor used, and installing one adds no language to the list above.

VSCodroid's own screens follow the same list: the setup progress, the toolchain
picker, and the notifications and dialogs the app puts on screen are translated
into those thirteen languages too. On Android 13 and later they can be set
separately from the phone, under Settings, Apps, VSCodroid, Language, and the
editor follows that choice as well.

VSCodroid's own commands, settings and the Get Started walkthrough are
translated into the same thirteen languages. An extension you install from Open
VSX carries its own translations if its author wrote any, and English if not.

One thing stays English whatever the phone is set to: the occasional string the
translation packs do not cover, roughly one in fifty.

Changing the language while VSCodroid is running takes effect on the spot: the
editor reloads in the new one. What does not change is anything already written,
including the terminal's output and the app's own notifications from before the
change.

### No Multi-window

VS Code's web client runs as a single window. You cannot open multiple VS Code windows side by side. However, you can use Android's split-screen mode to pair VSCodroid with another app (like a browser for previewing).

### Storage

Core installation extracts approximately 805 MB to internal storage. With both toolchains installed, expect around 996 MB. Setup needs about 905 MB free before it starts, which is more than it ends up occupying because extraction needs room to work; the app quotes that figure if it refuses to start. Beyond it, keep a few hundred MB free for node_modules, build artifacts and caches.

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
> On a new install `~/projects/` is inside the app's internal storage, which
> Clear Data wipes along with everything else, and nothing is backed up. An
> install from before 1.2.0 that already had a projects directory in the app's
> area of shared storage (`Android/data/com.vscodroid/files/projects`) keeps
> using it, and Clear Data wipes that too. New installs stopped using that
> location because shared storage cannot hold a symbolic link, so `npm install`
> failed there on the first package that ships an executable.

Rescue anything unsaved first. Which route is open to you depends on whether the
editor still works, and on a white screen it does not:

**If the editor will not open**, nothing outside the app reaches a new install's
projects: internal storage is not exposed over USB or MTP, and `adb pull` cannot
read it from a release build. What is left:

- A debug build is readable with `adb shell run-as com.vscodroid.debug`, and its
  projects can be copied out from there. The release builds refuse `run-as`.
- An install that still keeps its projects on shared storage can be read as
  before, with USB debugging on:

  ```
  adb pull /storage/emulated/0/Android/data/com.vscodroid/files/projects
  ```

  Some devices also expose that path over MTP when plugged in.

Everything else needs the editor, which is why the routes below are worth taking
before a screen goes white rather than after.

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
- **Unsupported platform errors** come from a package whose prebuilt binaries have no Android build, so there is nothing to install (see [Known Limitations](#packages-with-prebuilt-binaries)).
- **Network timeout** -- check your internet connection. npm uses `--prefer-offline` by default, so cached packages install without network.

### Git Push/Pull Fails

- **Permission denied (publickey)** -- generate an SSH key with `ssh-keygen -t ed25519 -f ~/.ssh/id_ed25519` in the terminal and add it to your GitHub/GitLab account. The `-f` is required; see [Generating an SSH Key](#generating-an-ssh-key) for why.
- **SSL certificate error** -- the CA bundle git uses is built from your device's own trust store: the system roots, plus any certificate authority you installed yourself through Android Settings, under the CA-certificate flow in the device's security settings. So a private or corporate CA does work for git, from the next time you open the app -- the bundle is rebuilt at launch, not while the app is running. If you would rather not install a CA on the device, an SSH remote (`git@`) instead of `https://` is still the shortest route to an internal host. Two things that bundle does not reach: npm, which has its own trust store, and pages loaded inside the editor, which use the system roots only.

### App Uses Too Much Storage

To reclaim space:

```bash
# Clear npm cache
npm cache clean --force

# Remove node_modules from old projects
rm -rf ~/projects/old-project/node_modules

# Clear pip cache
python3 -m pip cache purge

# Check disk usage
du -sh ~/projects/*
du -sh ~/.vscodroid/extensions/*
```

To remove an installed toolchain, run **VSCodroid: Manage Toolchains** from the
Command Palette, or touch and hold the app icon and choose **Manage toolchains**;
see [Installing After Setup](#installing-after-setup).

### App Crashes or Restarts Unexpectedly

This is usually caused by Android's memory management killing background processes:

1. Close other apps to free RAM.
2. Reduce the number of open terminal tabs.
3. Check the process monitor in the status bar -- if phantom count is high, close unused terminals.
4. On devices with 4 GB RAM or less, consider keeping only one project open at a time.

### Dev Server Not Accessible in Browser

If the preview tab or the device browser opens but the page does not load:

1. Verify the server is running in the terminal (check for errors).
2. Use the host and port the dev server printed. `http://localhost:PORT` and
   `http://127.0.0.1:PORT` reach the same loopback server, and either works.
   `0.0.0.0` is an address to bind to, not one to browse to.
3. To reach the server from **another device** on the same network, restart it
   bound to every interface (`--host 0.0.0.0` for Vite, `-H 0.0.0.0` for
   Next.js, `--bind 0.0.0.0` for Python) and run **VSCodroid: Serve on Network**
   from the Command Palette for the address the other device should use.

### WebView Crash Recovery

If the editor UI crashes but the app stays open, VSCodroid automatically recovers the WebView and reconnects to the running server. Your terminal sessions and unsaved work in the editor state are preserved.

Recovery is bounded, because reloading a page that is itself the cause only repeats the crash. Three crashes inside a minute are recovered from as normal; a fourth stops the automatic reload and puts up a page saying so, with a **Try again** button that reloads the editor when you are ready. The server keeps running behind it either way, so nothing needs force-closing.

---

*VSCodroid is built from the MIT-licensed Code - OSS source code. Not affiliated with or endorsed by Microsoft Corporation. "Visual Studio Code" and "VS Code" are trademarks of Microsoft. Uses Open VSX extension registry, not Microsoft Marketplace.*
