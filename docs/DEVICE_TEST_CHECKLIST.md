# VSCodroid Device Test Checklist

> Manual testing checklist for device matrix validation.
> Run after automated tests (`scripts/device-test.sh`) and instrumented tests pass.

## Session Info

| Field | Value |
|-------|-------|
| **App Version** | |
| **Date** | |
| **Tester** | |
| **Device** | |
| **Android Version** | |
| **WebView Version** | |

---

## 1. Device Matrix

| ID | Scenario | Steps | Expected Result | Pass/Fail | Notes |
|----|----------|-------|-----------------|-----------|-------|
| DM-1 | Pixel device (reference) | Install + full test on Pixel 7/8/9 | All features work | | |
| DM-2 | Samsung device | Install + full test on Galaxy S/A series | All features work, Samsung keyboard compatible | | |
| DM-3 | Budget device (4GB RAM) | Install + open editor + terminal | App runs without OOM, <700MB RAM | | |
| DM-4 | Tablet | Install + test landscape/split-screen | Layout adapts, no cropped UI | | |

## 2. Android Versions

| ID | Scenario | Steps | Expected Result | Pass/Fail | Notes |
|----|----------|-------|-----------------|-----------|-------|
| AV-1 | Android 13 (API 33) | Full install + test | All features work (minimum supported) | | |
| AV-2 | Android 14 (API 34) | Full install + test | All features work, .so extraction OK | | |
| AV-3 | Android 15 (API 35) | Full install + test | All features work | | |
| AV-4 | Android 16 (API 36) | Full install + test | All features work, 16KB pages OK | | |

## 3. Keyboard Input

| ID | Scenario | Steps | Expected Result | Pass/Fail | Notes |
|----|----------|-------|-----------------|-----------|-------|
| KB-1 | GBoard typing | Open file, type code with GBoard | Characters appear correctly, no duplication | | |
| KB-2 | Samsung keyboard | Open file, type code with Samsung KB | Characters appear correctly | | |
| KB-3 | SwiftKey | Open file, type with SwiftKey | Characters appear correctly | | |
| KB-4 | Hardware keyboard | Connect BT/USB keyboard, type | All keys work including modifiers. Only hardware answers this row: the automated suite's `hardware_key_chord_reaches_workbench` injects a virtual device, which exercises dispatch but not pairing, layout mapping or how a real HID keyboard reports its modifiers | | |
| KB-5 | Extra Key Row — Tab | Press Tab in editor | Indentation inserted | | |
| KB-6 | Extra Key Row — Esc | Press Esc with menu open | Menu/dialog closes | | |
| KB-7 | Extra Key Row — Ctrl+S | Press Ctrl on EKR then S on keyboard | File saves (no error) | | |
| KB-8 | Extra Key Row — Ctrl+P | Press Ctrl on EKR then P | Quick Open dialog appears | | |
| KB-9 | Extra Key Row trackpad | Drag on the trackpad: slowly first, then keep going without lifting, then diagonally | Cursor steps character by character at first and speeds up the longer the drag gets; a diagonal drag moves on both axes. There are no arrow buttons to press: the trackpad replaced them, so a drag is the only route for a finger. KB-13 covers the other route, the pad's four accessibility actions | | |
| KB-10 | Extra Key Row, brackets on the textarea edit path | On a device whose WebView is older than 121, open a file and press {, }, (, ) on the key row. Confirm the path first in remote debugging: `document.querySelectorAll("textarea.inputarea").length` is 1 | Each character is inserted, and Monaco auto-closes the pair | | |
| KB-11 | Extra Key Row, brackets on the EditContext edit path | On a device whose WebView is 121 or newer, same presses. Confirm the path first: `document.querySelectorAll("textarea.inputarea").length` is 0 and `document.querySelectorAll("div.native-edit-context").length` is 2 | Each character is inserted. This is the path where anything written to a textarea is inert, so a pass here and a fail on KB-10 means the fix went to the wrong layer | | |
| KB-12 | Extra Key Row keys under a screen reader | Turn TalkBack on, open a file, swipe to a key on the row until it is announced, then double tap to activate it. **`adb shell input tap` cannot answer this row**: it injects below touch exploration, so a single tap types the character and the run looks like a pass whatever the code does. Drive it by hand, or from a test that performs ACTION_CLICK on the node | The character is inserted, exactly once. A modifier announces and latches, and the next key carries it | | |
| KB-13 | Trackpad arrows under a screen reader | With TalkBack on, swipe to the trackpad, open its actions menu and choose each of Move cursor left, right, up and down. Same caveat as KB-12: an injected tap or swipe proves nothing here, because a drag is what touch exploration consumes | The caret moves one step in the chosen direction. A drag is the only other route and a screen reader cannot perform one, so a failure here leaves no way to move the caret at all | | |

## 4. Screen & Orientation

| ID | Scenario | Steps | Expected Result | Pass/Fail | Notes |
|----|----------|-------|-----------------|-----------|-------|
| SC-1 | Portrait mode | Open app in portrait | Full UI visible, no overflow | | |
| SC-2 | Landscape mode | Rotate to landscape | UI reflows, editor uses full width | | |
| SC-3 | Rotation mid-edit | Type in editor, rotate device | No data loss, cursor position preserved | | |
| SC-4 | Split-screen | Enter split-screen with another app | VSCodroid resizes correctly | | |
| SC-5 | Display cutout | Test on device with notch/punch-hole | Safe area padding applied, no content clipped | | |
| SC-6 | Foldable (if available) | Fold/unfold device | UI adapts to new dimensions | | |

## 5. Editor Operations

| ID | Scenario | Steps | Expected Result | Pass/Fail | Notes |
|----|----------|-------|-----------------|-----------|-------|
| ED-1 | Create new file | File > New File, type content, Ctrl+S | File saved, visible in explorer | | |
| ED-2 | Open existing file | Click file in explorer | File opens in editor tab | | |
| ED-3 | Large file (10k lines) | Open a 10,000+ line file | File loads, scrolling smooth | | |
| ED-4 | Find & Replace | Ctrl+H, search + replace text | Matches highlighted, replacement works | | |
| ED-5 | Multiple tabs | Open 5+ files in tabs | All tabs accessible, switch works | | |
| ED-6 | Copy/Paste (system) | Copy from external app, paste in editor | Text pastes correctly | | |
| ED-7 | Undo/Redo | Make edits, Ctrl+Z, Ctrl+Shift+Z | Undo and redo work correctly | | |
| ED-8 | Format document | Open JS file, run Format Document (Prettier) | File formatted, no errors | | |
| ED-9 | Application Menu with the keyboard up | Tap a text field so the keyboard rises, then tap the menubar button. Watch it for a few seconds rather than glancing: the failure this catches lasted about 40ms and left the button looking dead | The menu opens and stays open, listing File, Edit, Selection, View, Go and Run. Tapping outside still closes it, and tapping File still opens its submenu. Do not look for Esc here: the key row that carries it is hidden the moment the keyboard drops, which is the very event under test | | |
| ED-10 | Application Menu across a rotation | Open the Application Menu, tap File so its submenu opens, then rotate the device | Both menus close. They must not stay open: the submenu would be anchored where it no longer fits and would be clipped off the edge | | |

## 6. Extensions

| ID | Scenario | Steps | Expected Result | Pass/Fail | Notes |
|----|----------|-------|-----------------|-----------|-------|
| EX-1 | Search marketplace | Open Extensions, search "python" | Results from Open VSX appear | | |
| EX-2 | Install extension | Install any extension from search | Downloads, installs, shows in sidebar | | |
| EX-3 | Extension webview | Open Claude Code or theme picker | Webview renders, interactive | | |
| EX-4 | Persist across restart | Install extension, kill + relaunch app | Extension still installed and active | | |
| EX-5 | Bundled extensions | Check Extensions sidebar after first run | Process Monitor + themes visible | | |
| EX-6 | Uninstall extension | Uninstall a previously installed extension | Removed cleanly, no errors | | |

## 7. Background / Foreground

| ID | Scenario | Steps | Expected Result | Pass/Fail | Notes |
|----|----------|-------|-----------------|-----------|-------|
| BG-1 | Short background (30s) | Press Home, wait 30s, return | Editor state preserved, no reload | | |
| BG-2 | Medium background (5min) | Press Home, wait 5min, return | Health check runs, reconnects if needed | | |
| BG-3 | Long background (30min) | Press Home, wait 30min, return | Page reloads, server still running | | |
| BG-4 | Server process killed | `adb shell kill <node PID>` | Server auto-restarts, notification shows | | |
| BG-5 | Foreground notification | Check notification shade while app runs | "VSCodroid running" notification visible | | |
| BG-6 | Return after screen off | Lock screen, wait 2min, unlock | App resumes without crash | | |
| BG-7 | Adopted session says its network is gone | `adb shell ps -A \| grep libnode` shows two processes; **`kill -9` the parent** (the lower PID, the one the other lists as its PPID), then relaunch the app. It must be SIGKILL: the bootstrap handles SIGTERM and kills its child on the way out, so a plain `kill` leaves nothing to adopt. `ps` shows `libnode` rather than `server.js` because that is argv[0] | Editor loads against the surviving server, and the foreground notification reads "No network for extensions, git or npm". Confirm the claim: the marketplace and `npm view express` both fail in that session, and both work again after a full restart | | |
| BG-8 | A server that will not come back says so | With the editor open, `adb shell kill -9` the `libnode` process repeatedly until the notification reads "Server crashed repeatedly" | The page stops reading "Starting server..." and states that the server could not be restarted, that files are safe, and offers **Try again**. Tapping it returns to the loading page and starts a new attempt; nothing requires force-stopping the app | | |

BG-7 is the one case where the editor being healthy is the problem. An adopted
server outlived the bootstrap that forked it, and the DNS proxy died with that
bootstrap while the survivor kept the dead address in its environment. Nothing in
the editor looks wrong; only the card says so. If the card reads "Local
development server active" here, the degradation is silent again.

## 8. Low Memory & Stress

| ID | Scenario | Steps | Expected Result | Pass/Fail | Notes |
|----|----------|-------|-----------------|-----------|-------|
| ST-1 | Trim memory signal | `adb shell am send-trim-memory <PID> RUNNING_CRITICAL` | Process monitor kills idle LS, no crash | | |
| ST-2 | Many terminals | Open 10 terminal tabs | Bash spawns for each, process count reported | | |
| ST-3 | OOM recovery | Force WebView OOM (open huge file + extensions) | onRenderProcessGone fires, WebView recreated | | |
| ST-4 | Storage nearly full | Fill device storage to <100MB free | Warning toast shown, app still functional | | |

## 9. Performance Benchmarks

| ID | Metric | Steps | Target | Actual | Pass/Fail | Notes |
|----|--------|-------|--------|--------|-----------|-------|
| PF-1 | Cold start (first run) | Time from tap to editor visible. Record the number rather than pass/fail: no target has ever been measured, and extraction unpacks about 810 MiB across 23,494 files one at a time | Progress advances throughout and the editor opens; write the elapsed time in Notes | | | |
| PF-2 | Cold start (subsequent) | Kill app, re-launch, time to editor | <5s | | | |
| PF-3 | Warm start | Home → return to app | <2s | | | |
| PF-4 | Memory (idle) | Open app, check `dumpsys meminfo` | <400MB | | | |
| PF-5 | Memory (active editing) | Edit file + terminal open | <700MB | | | |
| PF-6 | Battery (1hr session) | Use normally for 1hr, check battery usage | <15% | | | |
| PF-7 | npm install (cached) | Run `npm install` on cached project | <5s | | | |
| PF-8 | npm install (fresh) | Run `npm install` on new project | <60s | | | |
| PF-9 | Vite dev server start | Run `npx vite` | <500ms | | | |
| PF-10 | File open (small) | Open a <100 line file | <1s | | | |

## 10. Toolchains (On-Demand)

There is no Settings entry for this screen. Touch and hold the app icon on the
home screen and pick **Manage toolchains** — `strings.xml` calls that the only
entry point there is, and every row below starts from it.

Run each command in the app's own terminal. `adb shell run-as` will not answer
these: it runs in a different SELinux domain, one that is allowed to execute
files the app itself may not, so it reports success for a binary that fails on
the device.

| ID | Scenario | Steps | Expected Result | Pass/Fail | Notes |
|----|----------|-------|-----------------|-----------|-------|
| TC-2 | Ruby install | Long-press app icon > Manage toolchains > Install Ruby | Downloads, extracts, `ruby --version` prints a version | | |
| TC-3 | Java install | Long-press app icon > Manage toolchains > Install Java | Downloads, extracts, `java -version` prints a version | | |
| TC-4 | Ruby and Java run | `ruby -e 'puts 1+1'`; write and run a `Hello.java` with `java Hello.java` | Both print their output | | |
| TC-6 | Toolchain uninstall | Manage toolchains > uninstall one | Files removed, command no longer found in a new terminal | | |
| TC-7 | A sideloaded install pins one release | Install any toolchain on a build that is NOT from Play, with `adb logcat -s VSCodroid.ToolchainManager` running (Logger prefixes every tag, so the bare name matches nothing) | One line reading `Pinned this install to .../releases/download/<tag>`, naming a concrete tag rather than `latest`, and the install completes. A `Falling back to the unpinned release URL` line instead is not a failure, but record it: it means the resolve did not work on this network | | |
| TC-8 | A retired toolchain is reclaimed | On a device with Go installed from an earlier build, update and launch once, then open Manage toolchains | Go is gone from the list, `go` is not found in a new terminal, and its files are off the disk. `adb logcat -s VSCodroid.ToolchainManager` shows `Removing go: this build no longer offers it` | | |

Go was here as TC-1 and TC-5, the second of them recording `go build` as an
expected failure. It is no longer offered. `go` starts its compiler and linker as
separate programs from the app's own storage, and Android refuses to execute
anything stored there, a limit no packaging change reaches and one that
`-toolexec` cannot route around: it governs how `go` runs its tools, and `go`
itself is what fails to start. An install that still carries it is removed on the
first launch of a build that has this line, so the row to run instead is TC-8.

## 11. Terminal & Tools

| ID | Scenario | Steps | Expected Result | Pass/Fail | Notes |
|----|----------|-------|-----------------|-----------|-------|
| TT-1 | bash interactive | Open terminal, run commands | Prompt works, history, tab completion | | |
| TT-2 | node | `node -e "console.log(1+1)"` | Prints 2 | | |
| TT-3 | git clone | `git clone https://github.com/user/repo` | Clones successfully with SSL | | |
| TT-4 | python3 | `python3 -c "print('hello')"` | Prints hello | | |
| TT-5 | npm init + install | `npm init -y && npm install express` | package.json created, express installed | | |
| TT-6 | SSH key gen | `ssh-keygen -t ed25519` in the terminal | Key pair created at `~/.ssh/id_ed25519` | | |
| TT-7 | SSH key read | `cat ~/.ssh/id_ed25519.pub` | Public key printed and selectable | | |
| TT-8 | tmux | `tmux new-session -d && tmux ls` | Session listed | | |
| TT-9 | ripgrep | `rg "pattern" .` | Search results shown | | |
| TT-10 | VS Code Search | Use Search sidebar (Ctrl+Shift+F) | Results appear, file navigation works | | |
| TT-11 | Commands outside the terminal | `bash -c 'type -t npm; type -t npx'`, then a `"type": "shell"` task running `npm -v` | Each reports `function`, and the task prints a version rather than "command not found". A toolchain command behaves the same once one is installed. `sh -c 'type npm'` still fails, which is the boundary, not a regression | | |

## 12. SAF & External Files

> The picker blocker ([#79](https://github.com/rmyndharis/VSCodroid/issues/79)) is
> fixed — the bridge extension declared `main`, so it loaded in the Node extension
> host where its `BroadcastChannel` reached nothing; it declares `browser` now and
> loads where its transport is. **These rows are executable and are the pre-release
> pass for the area that has changed most.**
>
> Run the whole section against **one** device folder that has at least one
> subdirectory and a `.vscode/` directory in it — several rows below depend on
> subdirectory contents, and a flat folder passes them without exercising anything.
>
> A failure here is usually silent by construction: the editor reports success and
> the device copy is what did not change. Verify from the **device** side (a file
> manager, or reopening the folder in another app), never from the editor's own view
> of the mirror.

| ID | Scenario | Steps | Expected Result | Pass/Fail | Notes |
|----|----------|-------|-----------------|-----------|-------|
| SF-1 | Open external folder | Command palette > VSCodroid: Open Folder from Device | System picker opens; after granting, the folder appears in Explorer with its contents | | |
| SF-2 | Edit sync-back, top level | Edit a file in the folder's root, save | Change is present in the file **on the device**, not only in the editor | | |
| SF-3 | Recent folders | Open a folder, close the app, reopen, run VSCodroid: Open Recent Folder | The folder is listed and reopens; its mirror was not deleted by the listing itself | | |
| SF-4 | Save inside a subfolder | Edit and save `<sub>/<file>`, two levels down if the folder allows | Change reaches the device. Watches are registered per directory, so a subfolder save is a different code path from SF-2 | | |
| SF-5 | Dotfiles both ways | Edit `.vscode/settings.json` (or `.gitignore`) and save | Change reaches the device. The write-back filter used to drop anything beginning with a dot while the walk copied it in, so this appeared to work and changed nothing | | |
| SF-6 | Rename a directory | Rename a subdirectory that has files in it, from the editor | Directory appears on the device under the new name **with its contents**. Renames arrive as an unpaired delete-then-create, so an empty new directory here means data loss | | |
| SF-7 | Device-side deletion sticks | Delete a file from the folder using a device file manager, then reopen the folder in the editor | The file stays deleted; it is not restored from the stale mirror | | |
| SF-8 | Revoked permission reclaimed | Revoke the folder's access in Android Settings > Apps > VSCodroid, relaunch | The mirror is reclaimed and the device copy is untouched. A folder still granted must **not** be emptied while open | | |

---

## Summary

| Category | Total | Pass | Fail | Skip |
|----------|-------|------|------|------|
| Device Matrix | 4 | | | |
| Android Versions | 4 | | | |
| Keyboard Input | 13 | | | |
| Screen & Orientation | 6 | | | |
| Editor Operations | 10 | | | |
| Extensions | 6 | | | |
| Background/Foreground | 8 | | | |
| Low Memory & Stress | 4 | | | |
| Performance | 10 | | | |
| Toolchains | 6 | | | |
| Terminal & Tools | 11 | | | |
| SAF & Files | 8 | | | |
| **Total** | **90** | | | |

**Overall Result**: [ ] PASS / [ ] FAIL

**Blockers / Critical Issues**:

**Notes**:
