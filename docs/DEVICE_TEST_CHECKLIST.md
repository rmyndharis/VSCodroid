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
| KB-4 | Hardware keyboard | Connect BT/USB keyboard, type | All keys work including modifiers | | |
| KB-5 | Extra Key Row — Tab | Press Tab in editor | Indentation inserted | | |
| KB-6 | Extra Key Row — Esc | Press Esc with menu open | Menu/dialog closes | | |
| KB-7 | Extra Key Row — Ctrl+S | Press Ctrl on EKR then S on keyboard | File saves (no error) | | |
| KB-8 | Extra Key Row — Ctrl+P | Press Ctrl on EKR then P | Quick Open dialog appears | | |
| KB-9 | Extra Key Row — arrows | Press arrow keys on EKR | Cursor moves in editor | | |
| KB-10 | Extra Key Row — brackets | Press {, }, (, ) on EKR | Characters inserted in editor | | |

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
| PF-1 | Cold start (first run) | Time from tap to editor visible | <15s extraction + <10s server | | | |
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

| ID | Scenario | Steps | Expected Result | Pass/Fail | Notes |
|----|----------|-------|-----------------|-----------|-------|
| TC-1 | Go install | Settings > Toolchains > Install Go | Downloads, extracts, `go version` works | | |
| TC-2 | Ruby install | Settings > Toolchains > Install Ruby | Downloads, extracts, `ruby --version` works | | |
| TC-3 | Java install | Settings > Toolchains > Install Java | Downloads, extracts, `java -version` works | | |
| TC-4 | Toolchain verify | Run hello world in each installed language | Compiles/runs correctly | | |
| TC-5 | Toolchain uninstall | Uninstall a toolchain from Settings | Files removed, command no longer in PATH | | |

## 11. Terminal & Tools

| ID | Scenario | Steps | Expected Result | Pass/Fail | Notes |
|----|----------|-------|-----------------|-----------|-------|
| TT-1 | bash interactive | Open terminal, run commands | Prompt works, history, tab completion | | |
| TT-2 | node | `node -e "console.log(1+1)"` | Prints 2 | | |
| TT-3 | git clone | `git clone https://github.com/user/repo` | Clones successfully with SSL | | |
| TT-4 | python3 | `python3 -c "print('hello')"` | Prints hello | | |
| TT-5 | npm init + install | `npm init -y && npm install express` | package.json created, express installed | | |
| TT-6 | SSH key gen | Command palette > Generate SSH Key | Key created, public key displayed | | |
| TT-7 | SSH key copy | Command palette > Copy SSH Public Key | Key copied to clipboard | | |
| TT-8 | tmux | `tmux new-session -d && tmux ls` | Session listed | | |
| TT-9 | ripgrep | `rg "pattern" .` | Search results shown | | |
| TT-10 | VS Code Search | Use Search sidebar (Ctrl+Shift+F) | Results appear, file navigation works | | |

## 12. SAF & External Files

| ID | Scenario | Steps | Expected Result | Pass/Fail | Notes |
|----|----------|-------|-----------------|-----------|-------|
| SF-1 | Open external folder | Command palette > Open Folder | SAF picker opens, folder syncs to mirror | | |
| SF-2 | Edit sync-back | Edit a file from SAF folder, save | Changes sync back to original location | | |
| SF-3 | Recent folders | Open a folder, close app, reopen, check recents | Previously opened folder appears in recents | | |

---

## Summary

| Category | Total | Pass | Fail | Skip |
|----------|-------|------|------|------|
| Device Matrix | 4 | | | |
| Android Versions | 4 | | | |
| Keyboard Input | 10 | | | |
| Screen & Orientation | 6 | | | |
| Editor Operations | 8 | | | |
| Extensions | 6 | | | |
| Background/Foreground | 6 | | | |
| Low Memory & Stress | 4 | | | |
| Performance | 10 | | | |
| Toolchains | 5 | | | |
| Terminal & Tools | 10 | | | |
| SAF & Files | 3 | | | |
| **Total** | **76** | | | |

**Overall Result**: [ ] PASS / [ ] FAIL

**Blockers / Critical Issues**:

**Notes**:
