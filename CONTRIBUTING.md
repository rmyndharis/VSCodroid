# Contributing to VSCodroid

Thank you for your interest in contributing to VSCodroid. This guide covers everything you need to set up a development environment, build the app, test on device, and submit changes.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Development Setup](#development-setup)
- [Project Structure](#project-structure)
- [Download Scripts](#download-scripts)
- [The on-device test suite](#the-on-device-test-suite)
- [Building](#building)
- [Continuous Integration](#continuous-integration)
- [Testing on Device](#testing-on-device)
- [How to Add a New Bundled Tool](#how-to-add-a-new-bundled-tool)
- [How to Add a New Patch](#how-to-add-a-new-patch)
- [Things that break quietly](#things-that-break-quietly)
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
| Node.js           | 20 or newer                    | `setup.sh` refuses anything older. The Code - OSS server build does not use this one: it takes its Node from upstream's `.nvmrc` at the pinned commit |
| Python            | 3.x                            | For node-gyp, and for every `scripts/*.py` the build and the Gradle verification tasks run |
| Git               | Any recent version             | -                                            |
| GnuPG (`gpg`)     | Any recent version             | `brew install gnupg` / `apt-get install gnupg`. The bundled-tool download scripts verify the Termux package index against its signature and refuse to run without it |
| adb               | Via Android SDK platform-tools | For deploying to device                      |
| ARM64 device or emulator | Android 13+ (API 33+)   | The bundled binaries are arm64-only, so an x86_64 emulator will not work; an arm64 emulator (the default on Apple Silicon) works fine |

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

This is the order CI uses, and the order matters: each step below notes why.

```bash
# 0. Prerequisites. Checks node, git and python3, and exits on a missing
#    ANDROID_NDK_HOME rather than letting steps 9 and 10 discover it after
#    twenty minutes of downloading. REQUIRE_NDK=0 skips that one check.
./scripts/setup.sh

# 1. Fetch the Code - OSS server tree built by the build-vscode-oss workflow.
#    This leaves it in server/, not in assets/.
./scripts/fetch-vscode-oss.sh

# 2. Copy that tree into assets/. No other step in this list does it, and an APK
#    built without it installs and opens with no editor in it. build.yml and
#    release.yml perform the same copy inline rather than calling this script.
./scripts/package-assets.sh

# 3. Termux tools (bash, git, tmux, make, openssh) and the shared libraries the
#    bundled binaries link against. Wipes and repopulates assets/usr/lib.
./scripts/download-termux-tools.sh

# 4. npm
./scripts/download-npm.sh

# 5. Python 3
./scripts/download-python.sh

# 6. Pre-bundled extensions
./scripts/download-extensions.sh

# 7. musl's loader. Without it the Claude Code CLI cannot start: its binary is
#    musl-linked and Android has no loader for it.
./scripts/download-musl-loader.sh

# 8. The Node runtime. After step 3, which places the libraries it links against
./scripts/download-node.sh

# 9. Bionic native addons (requires NDK). After step 8, so the build can check
#    each addon against the runtime it will load in.
./scripts/build-native-addons.sh

# 10. The glibc compatibility shim. Last, because step 3 wipes assets/usr/lib
#     and the stubs it generates live there. Without it the prebuilt native
#     addons fail to load at runtime.
./scripts/build-glibc-shim.sh \
    --scan android/app/src/main/assets/vscode-reh \
    --scan android/app/src/main/assets/extensions

# 11. (Optional) On-demand toolchains
./scripts/download-ruby.sh
./scripts/download-java.sh
```

Alternatively, run steps 0 to 10 and the APK build in one go:

```bash
./scripts/build-all.sh
```

`scripts/check-build-steps.py` runs in CI and fails when this list, `build-all.sh`
and the workflows stop agreeing about which scripts a build needs.

**Tip:** Termux mirrors can be slow. Set `TERMUX_MIRROR` for faster downloads:

```bash
export TERMUX_MIRROR=https://mirror.mwt.me/termux/main
```

It has to be a mirror of `termux-main`. The signed index names the repository it
belongs to and one naming another is refused, since the same key signs all of
Termux's.

**Rebuilding with no network:** set `TERMUX_OFFLINE=1`. The Termux steps then
reuse the index and the `.deb` files already under `toolchains/termux-packages/`,
and check that index against the `InRelease` stored beside it instead of
downloading one. One earlier run with a network has to have put both there. The
stored `InRelease` goes through the same signature, pinned-key, repository and
30-day freshness checks, so an offline rebuild accepts nothing an online one
would have refused, and the run prints which copy it used.

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
│   │   │   │   ├── SplashActivity.kt     # First-run extraction, toolchain picker
│   │   │   │   ├── ToolchainActivity.kt  # Toolchains screen (launcher long-press shortcut)
│   │   │   │   ├── VSCodroidApp.kt       # Application class, WebView pre-warm
│   │   │   │   ├── bridge/               # AndroidBridge, ClipboardBridge, SecurityManager
│   │   │   │   ├── keyboard/             # ExtraKeyRow, GestureTrackpad, KeyInjector
│   │   │   │   ├── service/              # NodeService (foreground), ProcessManager
│   │   │   │   ├── setup/                # FirstRunSetup, ToolchainManager/Registry
│   │   │   │   ├── storage/              # SAF storage bridge, sync engine
│   │   │   │   ├── util/                 # Environment, Logger, CrashReporter, StorageManager
│   │   │   │   └── webview/              # WebView, WebViewClient, WebChromeClient
│   │   │   ├── assets/                # Hand-written JS is in git; the trees below are not
│   │   │   │   ├── vscode-reh/           # VS Code Server (Remote Extension Host), downloaded
│   │   │   │   ├── server.js             # Node.js server bootstrap
│   │   │   │   ├── process-monitor.js    # Phantom process monitor
│   │   │   │   ├── platform-fix.js       # Selective platform override for npm
│   │   │   │   ├── dns-proxy.js          # Loopback HTTP/CONNECT proxy giving musl DNS;
│   │   │   │   │                         #   `--require`d into the editor server, not the bootstrap
│   │   │   │   ├── usr/                  # Shared libraries, Python stdlib, npm; downloaded
│   │   │   │   └── extensions/           # vscodroid.* in git, Open VSX ones downloaded
│   │   │   ├── jniLibs/arm64-v8a/     # Native binaries (.so trick for exec permission)
│   │   │   │   ├── libnode.so            # Node.js
│   │   │   │   ├── libpython.so          # Python launcher (its libpython3.x.so
│   │   │   │   │                         #   runtime lives in assets/usr/lib)
│   │   │   │   ├── libgit.so             # Git
│   │   │   │   ├── libgit-remote-curl.so # Git's HTTPS transport helper
│   │   │   │   ├── libbash.so            # Bash
│   │   │   │   ├── libtmux.so            # tmux
│   │   │   │   ├── libmake.so            # make
│   │   │   │   ├── libssh.so             # OpenSSH client
│   │   │   │   ├── libssh-keygen.so      # ssh-keygen
│   │   │   │   ├── libripgrep.so         # ripgrep (for VS Code Search)
│   │   │   │   ├── libldmusl.so          # musl loader (runs the Claude Code CLI)
│   │   │   │   └── libexec-trampoline.so # Starts a toolchain command (SELinux)
│   │   │   └── res/                   # Android resources, layouts
│   │   └── build.gradle.kts
│   ├── toolchain_ruby/            # Ruby on-demand asset pack
│   ├── toolchain_java/            # Java on-demand asset pack
│   └── settings.gradle.kts
├── scripts/                       # Download and build scripts
│   ├── fetch-vscode-oss.sh           # Fetch the built Code - OSS server tree
│   ├── build-vscode-oss.sh           # Build it from source (CI / Docker only)
│   ├── download-termux-tools.sh      # Download bash, git, tmux, make, openssh + libs
│   ├── download-node.sh              # Termux nodejs-lts -> libnode.so
│   ├── download-npm.sh               # Download npm from Node.js tarball
│   ├── download-python.sh            # Download Python 3 from Termux
│   ├── download-extensions.sh        # Download pre-bundled extensions
│   ├── build-native-addons.sh        # Cross-compile node-pty + @parcel/watcher for Bionic
│   ├── download-ruby.sh              # Download Ruby toolchain
│   ├── download-java.sh              # Download Java (OpenJDK 17) toolchain
│   ├── build-all.sh                  # Run all download/build scripts
│   ├── deploy.sh                     # Build + install + launch on device
│   ├── device-test.sh                # Run device tests
│   ├── device-launch.sh              # Launch on device, dismissing first-run dialogs
│   ├── build-aab.sh                  # Build a signed AAB outside CI
│   └── generate-branding-icons.py    # Regenerate the web client's PWA icons
├── toolchains/                    # Work dir for the download scripts, gitignored,
│                                  #   cached by CI, safe to delete (costs a re-download)
├── patches/                       # Unified diffs applied to the VS Code source
│   ├── NNNN-<description>.patch      # Flat, applied in filename order, before gulp
│   └── fingerprints.txt              # How each patch is proven to have reached the package
├── docs/                          # Project documentation
├── MILESTONES.md                  # Development milestones M0-M6
├── NOTICE.md                      # Third-party attribution
└── README.md                      # Project overview
```

### Key Kotlin Source Files

| File | Purpose |
| ---- | ------- |
| `MainActivity.kt` | WebView setup, JS bridge registration, intent handling, OAuth callbacks |
| `SplashActivity.kt` | First-run asset extraction, progress UI, the toolchain picker, and the per-launch repairs that run on every start |
| `VSCodroidApp.kt` | Application class, WebView pre-warm, CrashReporter init |
| `bridge/AndroidBridge.kt` | JS interface: clipboard, file picker, OAuth, SSH, storage, toolchains |
| `bridge/SecurityManager.kt` | The bridge session token: issues it, and validates it on every `@JavascriptInterface` call. It judges the caller, never the destination: there is no URL allow-list, and WebView navigation is decided in `VSCodroidWebViewClient.shouldOverrideUrlLoading`. That callback is not one rule: an external main-frame navigation is handed to the system, an http or https subframe is rendered in place instead, and a subframe naming any other scheme needs a user gesture behind it or nothing leaves |
| `keyboard/ExtraKeyRow.kt` | Multi-page key bar with ViewPager2 and dot indicators |
| `keyboard/GestureTrackpad.kt` | 3-speed drag-to-cursor-navigate widget: touch plumbing and drawing |
| `keyboard/TrackpadGesture.kt` | The gears and the arrows a drag earns, with no Android types, so it can be tested on the JVM |
| `keyboard/KeyInjector.kt` | Types characters as real key presses; sends non-text keys and chords as KeyboardEvents via JS |
| `service/NodeService.kt` | Foreground Service (specialUse) to keep Node.js alive |
| `service/ProcessManager.kt` | Node.js process lifecycle, health check, auto-restart |
| `setup/FirstRunSetup.kt` | Asset extraction, symlink creation, settings, .bashrc |
| `setup/ToolchainManager.kt` | Toolchain install, uninstall and env vars over **two** delivery paths chosen at runtime by install source: Play Asset Delivery, and `downloadViaHttp()` fetching ZIPs from `releases/latest` for every non-Play install. Both converge on `installFromDirectory()`. Work only the Play path and you break sideload users, who are the ones testing |
| `util/Environment.kt` | PATH, HOME, LD_LIBRARY_PATH, all env vars for Node.js process |
| `webview/VSCodroidWebViewClient.kt` | CDN URL interception, vscode-resource serving, crash recovery |

## Download Scripts

The download scripts place pre-built binaries under `android/app/src/main/`. Listed with them are the checkers, patchers and self-checks that run beside those downloads, from a workflow, from another script, or from a Gradle verification task. The scripts a contributor invokes directly are in [Preparing Assets](#preparing-assets), [The on-device test suite](#the-on-device-test-suite) and [Quick Deploy Script](#quick-deploy-script).

**To find out which version of something ships, read the script, never the
assets tree.** The downloaded trees under `android/app/src/main/assets/`, which are
`vscode-reh/`, `usr/` and the marketplace extensions, are gitignored build output
filled by whichever run of these scripts happened last; a checkout can be weeks
stale and still hold a well-formed file that answers confidently. That has
produced a wrong number in a published issue: `node_modules/npm/package.json`
was read from a working tree, parsed cleanly, and reported a version the app had
already stopped shipping. The rule is not "read the assets tree carefully"; the
assets tree is never the answer, even on the days it happens to be right, and
two people reading it the same way got different results only because their
checkouts differed.

| Script | What it does | Output location |
| ------ | ------------ | --------------- |
| `fetch-vscode-oss.sh` | Downloads the Code - OSS server tree from the `server-<version>` release, verifies it, and installs ripgrep as `libripgrep.so` | `server/vscode-reh/`, `jniLibs/arm64-v8a/libripgrep.so` |
| `build-vscode-oss.sh` | Builds that tree from the MIT source with `patches/` and `branding/` applied. Run by the build-vscode-oss workflow on an arm64 runner, or locally in Docker; not needed for a normal build | a `.tar.gz` published as a release asset |
| `generate-branding-icons.py` | Renders the web client's PWA icon set from the Android launcher icon. Run **by hand** when the launcher icon changes, never by CI, and its outputs are committed: `build-vscode-oss.sh` copies them into the server tree, so the build consumes the committed files and not this script. Load-bearing rather than cosmetic, because upstream ships Microsoft's VS Code icon there and that cannot travel with this app. Needs Pillow | `branding/server/{code-192.png,code-512.png,favicon.ico}` |
| `device-launch.sh` | Launches the app on a connected device and clears what blocks a first run, matching the POST_NOTIFICATIONS prompt and the toolchain picker by their on-screen text through `uiautomator` rather than by fixed coordinates. Worth knowing why it exists: both dialogs take focus, so the app is backgrounded and its process is gone, which through `ps` and `logcat` reads exactly like a crash. Run by hand, not by CI | the app running on the device |
| `build-aab.sh` | Builds a signed release bundle outside CI, reading the keystore from the gitignored `android/signing.properties` or the `VSCODROID_*` environment variables. Run by hand; `release.yml` builds the published bundle itself and does not call this. It builds and signs only: it prepares no assets and downloads nothing, so it packages whatever the last preparation left in `assets/` and `jniLibs/`. Run `scripts/build-all.sh` first for a bundle whose contents match a release. It is not the only build that reaches R8: `release.yml` runs `bundleRelease` on the tag, and the `Shrinker` workflow (`r8.yml`) runs `:app:optimizeReleaseResources :app:lintVitalRelease`, which pulls `minifyReleaseWithR8` in without needing the keystore or the asset tree | `app/build/outputs/bundle/release/app-release.aab` |
| `verify-server-tree.py` | Checks a server tree: required paths, no vsda, no bundled GNU/Linux node, every native binary aarch64, branding applied, the OAuth callback page stripped of Microsoft's product name and embedded logo, eight onboarding phrases renamed in `out/nls.messages.js`, and no `main.vscode-cdn.net` anywhere in the tree outside the Copilot extension, which is exempted by name because fetching its own configuration from there is what it does. The embedded logo and that CDN sweep are redistribution claims that hold for a tree of any age, so they run unconditionally, ahead of the gate. The callback page's product name and the eight nls phrases describe what patches 0006 and 0011 do, so a tree carrying no `vscodroid-patches.json` skips those two and is reported as stale instead. Run by both scripts above on the tree they produce, and again by the `verifyServerTree` Gradle task on the copy in `assets/` that is actually packaged, which on a warm-cache build is the only run that happens | exit status |
| `download-termux-tools.sh` | Downloads bash, git, tmux, make, openssh and every shared library the bundled binaries link against, including Node's | `jniLibs/arm64-v8a/`, `assets/usr/` |
| `download-node.sh` | Installs Termux's `nodejs-lts` as `libnode.so`. Run after `download-termux-tools.sh`, which places the libraries it links against | `jniLibs/arm64-v8a/libnode.so` |
| `patch-default-shell.py` | Repoints a bundled file's compiled-in default shell from Termux's own prefix, a directory inside another application that this one cannot read, to `/system/bin/sh`. Called by `download-node.sh` on `libnode.so`, by `download-termux-tools.sh` on git, git-remote-curl, tmux, make and ssh, by `download-python.sh` on the stdlib's `subprocess.py`, and by `download-ruby.sh` on `libruby.so`, the pty extension and `mkmf.rb`, each on the file it has just installed and before the ELF gate. Four spellings are handled. The two that sit inside an ELF keep the file's length, a C string constant padded with NULs and Node's JavaScript source padded with spaces; the two standalone text files have no fixed length and are rewritten plainly, which matters for `mkmf.rb` because its line is copied verbatim into every generated `Makefile` and a make variable keeps its trailing whitespace. Each call fails unless the path is there exactly once, so an upstream change stops the build rather than shipping a file whose shell nobody has established. A file that already names that shell is reported as such and left alone, so an already-placed one can be handed to it to find out where it stands. `--check <dir>` rewrites nothing and fails if any regular file under the directory, at any depth, still names Termux's prefix. That is how the `verifyBundledShellPaths` Gradle task answers for the whole of `jniLibs/` at packaging time, including binaries restored from a cache that no download step re-ran, and how `download-ruby.sh` and the `verifyRubyPackShellPaths` Gradle task answer for the whole Ruby asset pack rather than only the three files that script names | the file, rewritten in place; or exit status under `--check` |
| `verify-android-elf.py` | Checks a binary can load on Android: aarch64, no unbundled dependency, 16 KB-aligned segments. Called by every script that installs a binary (the Termux, Node, Python, musl and toolchain downloads, the native-addon and shim builds, and `fetch-vscode-oss.sh` for ripgrep), each on the one file it just placed. `--dir` checks a whole directory instead, which is how the `verifyBundledBinaries` Gradle task re-examines all of `jniLibs/` at packaging time, including binaries restored from a cache that no download step re-ran | exit status |
| `verify-termux-index.sh` | Checks the Termux package index against the repository's signed `InRelease` before any digest is read out of it, so the filenames and checksums the download scripts trust rest on a signature rather than on one host. Called by every script above and below that reads the index; needs `gpg`. A cached index that has fallen behind is refetched once rather than refused, since callers keep one for an hour and upstream publishes daily. The signed file also has to name the repository the caller is reading packages from, since one key signs all of Termux's. A run that accepts a downloaded `InRelease` keeps it beside the index it covers, and `TERMUX_OFFLINE=1` verifies against that stored copy instead of downloading one. The stored copy is re-checked in full, signature, pinned fingerprint, repository and age alike, and the refetch above is switched off, since it would replace the index while its signature stayed the stored one | exit status |
| `lib/termux-packages.sh` | Sourced, never run. The index fetch, the signature check, package resolution and the per-`.deb` digest check, shared by the four scripts that take packages from Termux: `download-termux-tools.sh`, `download-python.sh`, `download-ruby.sh` and `download-java.sh`. Each of those carried its own copy, so a correction to any of it had to be made four times and was worth nothing until it had been. What a caller still owns is its package list, where the files go, and which of them are checked as ELF objects. ⚠️ `download-node.sh` deliberately keeps its own: it resolves one package and writes its record after the ELF gate rather than at resolve time | functions, no output of its own |
| `download-npm.sh` | Extracts npm from Node.js linux-arm64 tarball | `assets/usr/lib/node_modules/npm/` |
| `download-python.sh` | Downloads Python + deps from Termux. The version is whatever the Termux index currently carries, detected at download time rather than pinned here | `jniLibs/arm64-v8a/`, `assets/usr/lib/python<major.minor>/` |
| `download-extensions.sh` | Downloads marketplace extensions from Open VSX. Every entry must be pinned as `publisher.name@version#sha256`, and the resolved version must equal the pin: the cleanup sweep names each directory from the pin while the extraction names it from what Open VSX returned, so a difference makes the sweep delete the tree on every run. The digest is the one the VSIX must hash to, recorded here rather than fetched from the registry that also serves the bytes; the published `files.sha256` is still read, and a disagreement under a fixed version fails the build | `assets/extensions/` |
| `download-musl-loader.sh` | Extracts musl's dynamic loader from the Alpine package. The Claude Code CLI ships as a musl binary and Android has no loader for it. The version comes from the branch index at download time rather than being pinned here, so the run records which one it installed. `ALPINE_BRANCH` must name a branch Alpine still supports: an unsupported one keeps serving a correctly signed index for years, so the signature check alone cannot notice. The index is also refused when it is more than 30 days old (`ALPINE_INDEX_MAX_AGE_DAYS`), read from the tar member time inside the signed bytes | `jniLibs/arm64-v8a/libldmusl.so`, `toolchains/musl/resolved-musl.tsv` |
| `build-native-addons.sh` | Cross-compiles node-pty, `@parcel/watcher` and `@vscode/sqlite3` for Bionic using the NDK, with 16 KB page alignment. Checks each `.node` against the JavaScript version shipped beside it | `assets/vscode-reh/node_modules/*/build/Release/*.node` |
| `build-glibc-shim.sh` | Scans the packaged tree for addons built against glibc and generates versioned stub libraries so Bionic's loader accepts them. Run last: `download-termux-tools.sh` wipes the directory the stubs live in | `assets/usr/lib/libglibc-shim.so` and per-soname stubs |
| `build-exec-trampoline.sh` | Cross-compiles `exec-trampoline.c` with the NDK, 16 KB-aligned, as `libexec-trampoline.so`. One symlink per toolchain command points at it from `usr/libexec/tcbin`, which sits ahead of `usr/bin` on PATH, so a bare-name lookup reaches a file the app may execute instead of a payload SELinux refuses. It reads `toolchain-exec.tsv` and hands the named binary to `/system/bin/linker64`. Without it a toolchain command works only from bash, since the loader indirection exists nowhere else | `jniLibs/arm64-v8a/libexec-trampoline.so` |
| `package-toolchains.sh` | Zips the toolchain asset-pack directories for the GitHub Release that non-Play installs download from. It takes the list from `ToolchainRegistry.kt` rather than carrying one, refuses a pack whose tree is larger than the `estimatedSize` recorded for it (in 4 KiB blocks, the unit that KDoc's `du -sk` reports, since both install pre-flights reserve against that figure), and a full run first deletes any ZIP the registry names no toolchain for, so a withdrawn one is not published beside the current ones | `toolchain-zips/toolchain_*.zip` |
| `download-ruby.sh` | Downloads Ruby + deps from Termux | `toolchain_ruby/src/main/assets/` |
| `download-java.sh` | Downloads OpenJDK 17 + deps from Termux | `toolchain_java/src/main/assets/` |
| `check-build-steps.py` | Five checks, and the script prints one line per check so the count is readable from a run rather than from here. Three about shell scripts: the documented build sequence, `build-all.sh`, and the two build workflows all still name the same ones, the third pairs `build.yml` against `release.yml`, so a step dropped from the tag path alone is caught. Then every `scripts/test-*.js` runs in both `lint.yml` and `release.yml`, so a self-check cannot be added and then run by nothing; and every `scripts/check-*.py` is invoked by something, which is the answerable question for that family since several take arguments and run from a script or from Gradle. The self-check rule matches an invocation, not a mention, a script named only in a comment does not count. ⚠️ The shell rules match `bash scripts/*.sh` only, so a script a workflow runs with `python3`, or one called from inside another script, is still not covered, those are listed here by hand. The build-vs-release pairing is also one-directional: a script that runs only on the tag path can be dropped from it and nothing notices | exit status |
| `write-build-manifest.py` | Records what a build resolved: the app's own version, versionCode and commit, the editor version and commit, the server tarball digest, the musl loader's version and checksum, and the version and checksum of every Termux package. The release workflow attaches it to the release, so a published artifact can be traced to the build that produced it without asking the Actions API for a run that will outlive neither. A record, not a lock: superseded packages are dropped upstream, so a pin would break the build on every routine update. `--compare` reports the differences against an earlier manifest and always exits 0 | `build-manifest.txt` |
| `check-langserver-patterns.py` | Checks the process monitor can recognise the language servers being packaged. A pattern matching nothing is invisible twice: the server keeps running, keeps counting against the phantom-process budget, and the idle-kill never sees it | exit status |
| `check-patch-fingerprints.py` | Checks a packaged tree carries every patch in `patches/`, using the expectations in `patches/fingerprints.txt`. It also reads back `vscodroid-patches.json`, which `build-vscode-oss.sh` writes into the tree with `--write-manifest`, and compares the sha256 of each patch's diff against this checkout's, so a tree built from different patch text is refused rather than passing on a pattern that still matches. That verdict is reported first, before the rows. Takes the tree as an argument, so the same check can run against a downloaded tarball | exit status |
| `check-patches-apply.sh` | Fetches the newest stable upstream VS Code tag and applies every patch in `patches/` to it, cumulatively and in glob order, the way `build-vscode-oss.sh` does. Answers what the build cannot: how much of `patches/` survives the NEXT bump, rather than whether it fits the pinned commit. Run weekly by `patch-drift.yml`, and on demand with a tag argument. Applied cumulatively rather than one at a time because two patches touch `remoteExtensionHostAgentServer.ts`, so an independent `--check` would judge the second against source the first was to have changed. ⚠️ Expected to fail between an upstream move and the bump that answers it, so it is not a gate and must not be added to branch protection. Stops at the first failing patch: a later one applying to a tree that never came to exist proves nothing | exit status |
| `check-welcome-claims.py` | Refuses a welcome screen that names a bundled tool's version, promises a toolchain as "coming soon", or puts an undeclared number in walkthrough prose or an illustration. Those runtimes come from the Termux index at build time, so a number written into the manifest is right until the next rebuild -- it was wrong for two releases, in the illustrations as well as the text | exit status |
| `check-bridge-api-spec.py` | Checks every `@JavascriptInterface` method in `AndroidBridge.kt` against `docs/05-API_SPEC.md` on name, parameter list and return type, both directions. The spec is what an extension author writes against and nothing had held it to the bridge: one pass found fourteen disagreements across twenty-eight methods, four of them invisible to a comparison of names because only the shape was wrong. ⚠️ Half the gate, and it reports ok on what it cannot see: a method whose annotation is spelled in a way its patterns miss (`@android.webkit.JavascriptInterface`, an aliased import), and any method the bridge **inherits**: its window is one file. `BridgeApiSpecParityTest` is the other half and settles which methods exist, by reflecting over the compiled class, so spelling and inheritance stop being categories. Return types, parameter names, order and nullability go the other way: they do not survive into bytecode, so this script checks them and the test cannot. Neither checks prose | exit status |
| `check-bundle-size.py` | Checks the release bundle against Play's per-module size caps before anything is published, rather than at upload | exit status |
| `check-pack-overlap.py` | Refuses an asset entry the base module and a toolchain pack both carry. Bundletool answers such a bundle by refusing the AAB outright, and the two trees are filled by different download scripts, so nothing compares them until the bundle exists. `release.yml` runs it right after the downloads, the one place both trees are populated, and the `checkPackOverlap` Gradle task runs it again so a local `./gradlew bundleRelease` is covered too; that task is armed by the Ruby pack holding a payload, since comparing an empty pack against the base module reports no overlap for the wrong reason. A pack needing a library the base runtime already ships relies on the base copy, which is extracted before any toolchain installs | exit status |
| `check-local-network-permission.py` | Checks local network access survives the `targetSdk` in use | exit status |
| `check-permission-claims.py` | Holds `docs/PRIVACY_POLICY.md` to the permissions the installed app really declares. Two halves, and it prints which ran: the committed source manifest, always, and the merged manifest a build leaves under `android/app/build/intermediates/`, which is the only half that can see a permission a library adds. The policy makes a closed statement, and it said four while the merged manifest declared six, for at least one release, because the two extras appear in no committed file. Names are compared on their last dot-separated segment, since the app-defined receiver permission is named after the applicationId and so differs between debug and release. The merged half judges the release variant and prints which one it read: AGP merges a manifest for the instrumentation APK too, and that one declares `REORDER_TASKS` and none of ours, so taking the newest file on disk failed on a developer machine that had just built it and asked for the privacy policy to be rewritten. Run from `lint.yml` and `release.yml` for the committed half, and from the `checkPermissionClaims` Gradle task on `processReleaseMainManifest` for the merged one | exit status |
| `check-lint-baseline.py` | Refuses a lint baseline that has grown past the count `android/app/build.gradle.kts` states, or that carries a location naming one machine. `./gradlew updateLintBaseline` is the documented way to edit the file and rewrites all of it: measured, 17 entries became 91, 45 of them rooted at `$HOME`, and 10 issue types nobody chose to hide went quiet. Nothing else notices, since `LintBaselineFixed` is Information severity and cannot fail a build. Reads the committed XML, never the count lint prints in its report: that number is 91 in the checkout that generated the file and 46 in any other, so it reads green exactly where the accident happens. Editing the baseline means deleting entries by hand and correcting the count in the comment | exit status |
| `check-plain-punctuation.py` | Refuses any tracked file that writes U+2014, U+2013 or U+2015 in place of ordinary punctuation. 857 had accumulated across 118 files before anyone counted, because each arrives one edit at a time from an editor that substitutes them. Verbatim licence text under `licenses/` and the body of a patch are exempt, since both are reproduced rather than written. The ASCII hyphen is not its subject. Run from `lint.yml` and `release.yml`: a tag may name a commit that reached main through neither trigger lint fires on | exit status |
| `check-bundled-extensions.py` | Pairs `download-extensions.sh`'s `EXTENSIONS` list with the `Bundled VS Code Extensions` table in `NOTICE.md`, matching on the extension id, and refuses a recorded licence this project cannot redistribute. Where the extracted trees exist it also checks each `package.json` declares that same licence and the tree carries the licence text. Run three times: from `lint.yml` for the committed half, because the extracted trees are gitignored and no lint job builds them; from `download-extensions.sh` once it has extracted them; and unconditionally from `build.yml`, whose download step is skipped on an asset cache hit while the restored trees stay on disk to be read. Scoped to the list, never a walk of the extensions directory: the four `vscodroid.*` trees have neither field and are covered by the root `LICENSE` | exit status |
| `check-translatable-strings.py` | Refuses a user-facing string written into Kotlin rather than `strings.xml`, where no translation can reach it. A predicate over call shapes, not a list of files: it tokenises each source, then reads the argument list of every `Toast`, dialog, view, notification, shortcut and accessibility-action sink and flags a string literal sitting in one. Tokenising is what makes it see the cases a grep does not: five of `MainActivity`'s toasts carry their message on the line after `Toast.makeText(`, and blanking raw strings keeps the injected JavaScript from being read as Kotlin. ⚠️ Half the gate. Lint's `HardcodedText` is the other half and covers layouts, which this never opens. Neither sees a string put in a variable or returned by a helper first, nor one held as data, so a pass means "no literal was written at a recognised sink", never "the app is translatable" | exit status |
| `check-workflow-steps.py` | Reads every `.yml` and `.yaml` under `.github/workflows/` and refuses a step GitHub Actions would reject at dispatch: one carrying neither `uses` nor `run`, one carrying both, and a `uses` that is not pinned to a full 40-character commit SHA. Valid YAML is a different question, and it is the wrong one: a duplicated bare `- name:` loads without complaint and dies at dispatch, on a workflow that runs once per VS Code bump. Needs PyYAML, the one Python dependency here that nothing installs: CI runs on images that already carry it, so a local run may need `apt-get install python3-yaml` or `pip install pyyaml`. Run from `lint.yml` and `release.yml` | exit status |
| `check-toolchain-claims.py` | Refuses a document that offers a toolchain `ToolchainRegistry.available` does not install. The picker is generated from that list and follows a withdrawal at once; the README, the user guide, the privacy policy, both attribution documents, the design documents and the Get Started walkthrough name the toolchains in prose and do not. Files whose job is to record what happened, the CHANGELOG and the version history among them, are outside its list on purpose. Run from `lint.yml` and `release.yml` | exit status |
| `check-checklist-totals.py` | Refuses a `docs/DEVICE_TEST_CHECKLIST.md` whose per-section counts or grand total disagree with the rows present in it. The total is checked against the sum and against the file, separately, because a total maintained by hand can be right about section counts that are themselves wrong. Run from `lint.yml` | exit status |
| `check-document-dates.py` | Refuses `docs/LEGAL_NOTICES.md` or `docs/PRIVACY_POLICY.md` claiming a date earlier than the last commit that changed it; later is fine, since a policy can be dated for the release it ships in. The privacy policy promises in its own body that the date moves whenever the policy does. Run from `lint.yml` and `release.yml`, the two workflows that check out with `fetch-depth: 0`; a shallow clone is refused rather than skipped, so a third placement would fail loudly rather than pass without looking. It ran on the tag alone until a documentation sweep restaled both dates and nothing said so until the release job aborted on a tag that was already public | exit status |
| `check-library-attribution.py` | Walks `assets/usr/lib`, `jniLibs/arm64-v8a` and `assets/vscode-reh` to their leaves and refuses a shipped binary nobody has attributed, or a copyleft one with no offer of source. Recognises payloads by magic number rather than by ELF alone: tree-sitter's grammars are WebAssembly, PSReadLine is .NET, and pip vendors Windows launchers, none of which can run here and all of which are still redistributed. Run from `build.yml` and `release.yml`, where the libraries exist; `lint.yml` writes stubs and has nothing to attribute | exit status |
| `check-termux-licenses.py` | Reads `TERMUX_PKG_LICENSE` from each package's `build.sh` in termux-packages and compares it with the licence recorded by hand, which `check-library-attribution.py`'s copyleft rule and two published documents all rest on. The three places that look like they should carry the field were measured and none does: the signed index has no `License:` line, the `.deb` control member has none, and the payload's `copyright` file is a document rather than a field. Run from `release.yml` | exit status |
| `check-extension.py` | Checks one extracted extension against the `VSCODE_VERSION` this build ships and against Bionic. Both failures are quiet: an `engines.vscode` floor above the server leaves the extension registered and never activated, and a glibc native payload loads on a desktop and throws on first use here. Called by `download-extensions.sh` on each tree it extracts | exit status |
| `patch-python-platform.py` | Rewrites the bundled Python extension's own platform detection so Android answers `OSType.Linux` instead of `OSType.Unknown`. The extension never consults VS Code's detection and Termux's Node reports `android`, which left `getEnvironmentActivationShellCommands` returning early, so selecting a virtual environment never activated it. Called by `download-extensions.sh`; `--check` is what the `verifyPythonPlatform` Gradle task runs, so a tree assembled from an older extraction cannot ship without the rewrite | the extension, rewritten in place; or exit status under `--check` |
| `gen-glibc-forwarders.py` | For each glibc library a prebuilt addon names, emits a stub carrying that soname and exporting exactly the versioned symbols the addon asks for, each one a tail branch through a pointer resolved against Bionic's libc by name. The symbols are read out of the addons rather than listed here, since what a binary imports is a property of how it was built. Called by `build-glibc-shim.sh` to generate them, and with `--verify-against` by both that script and the `verifyNativeAddons` Gradle task | `.c` and version-script files under the shim's work dir; or exit status |
| `build-docs-site.py` | Renders `docs/USER_GUIDE.md` and `docs/PRIVACY_POLICY.md` into the published site, so neither exists twice with nothing holding the copies together. Names its two inputs one at a time rather than walking `docs/`: the requirements, architecture, risk and plan documents are working documents and are deliberately not published. Run by `pages.yml` | `docs/site/` |
| `test-dns-proxy.js` | Exercises the loopback DNS proxy's Basic-auth contract. Loopback on Android is not isolated per app, so that token is what stands between the proxy and every other app on the device | exit status |
| `test-process-monitor.js` | Points a scan at a fixture `/proc` and checks the snapshot: that the language servers that ship are recognised, that an unrelated user process carrying a server's name in its path is not, and that the count includes the process the monitor runs inside | exit status |
| `test-platform-fix.js` | Runs the platform override under a faked `process.platform` and checks it engages for node-gyp and for nothing that merely mentions it in a path or an argument | exit status |
| `test-server-bootstrap.js` | Boots the server bootstrap against a fixture tree and checks the `product.json` rewrite: overrides applied, a truncated file named rather than thrown, an unwritable directory leaving the existing file intact | exit status |
| `test-process-monitor-extension.js` | Drives the process monitor extension against two snapshots that differ in every count and checks its notifications read the same either way. A notification cannot be edited once open, so any number baked into one freezes while the status bar beside it keeps moving | exit status |
| `test-bridge-relay.js` | Extracts the bridge relay from the Kotlin raw string it lives in and runs it against a stub bridge, driving the real bundled extension, so what is asserted is the message a user is shown. Nothing else reads that script: it is neither compiled nor linted, so a bridge change can be reverted with every suite green. Also refuses a command an extension sends that the relay has no branch for, whose only symptom is a five-second timeout naming neither the command nor the cause | exit status |
| `test-download-capture.js` | Exercises the download-capture script, which is JavaScript inside a Kotlin raw string handed to `evaluateJavascript`, so nothing compiles or lints it and no Kotlin test reaches past the bridge methods it calls. What it pins is the deferred `revokeObjectURL`: the workbench revokes a `blob:` URL on the next task, and choosing a destination takes seconds, so without the deferral every save finds nothing to write while the Kotlin suite, lint and the build all stay green | exit status |
| `test-serve-network.js` | Exercises the Serve on Network port scan and its reachable versus local-only split, both halves of which cost the user something when wrong: a loopback-only server called reachable hands out an address that refuses, and a reachable one called local-only sends them to restart a working server. The classification is inferred from two probes because an app process cannot read `/proc/net/tcp` at all, and inference is what needs a test. `scanPorts` takes its connector, so nothing here opens a socket | exit status |

**Important notes:**
- Scripts are designed for macOS and Linux (macOS uses `bsdtar` for `.deb` extraction).
- `fetch-vscode-oss.sh` needs the `gh` CLI authenticated, or `VSCODE_OSS_URL` pointing at a tarball. This is now enforced rather than assumed: a cached tarball is checked against the digest its release carries, and when `gh` cannot report that digest the script stops instead of building bytes nothing verified. `VSCODE_OSS_SHA256` gives the `VSCODE_OSS_URL` path a digest to check against, which is the way to work offline against a tarball you already trust.
- The Node.js binary (`libnode.so`) is Termux's `nodejs-lts` package, installed by `download-node.sh`. It is not cross-compiled here and not checked in. An earlier hand-cross-compiled build was abandoned for segfaulting inside several CLI tools; its scripts and Termux-derived node patches were removed on 2026-08-14 and are recoverable with `git log --diff-filter=D -- toolchains/` if that fallback is ever needed.

## The on-device test suite

**Before testing an APK built in a worktree, copy the artifact trees into it.**

```bash
MAIN=/path/to/the/main/checkout
for d in jniLibs assets/vscode-reh assets/usr; do
    cp -R "$MAIN/android/app/src/main/$d" android/app/src/main/$d
done
```

`jniLibs/`, `assets/vscode-reh` and `assets/usr` are gitignored build output that
the download scripts fill in. A fresh worktree has none of them, and nothing
fails: the build is green, the APK is written, `adb install` succeeds, the splash
screen appears. Then the screen goes white and stays white, because the APK
contains **zero** native libraries and the server cannot start:

```
Cannot run program ".../lib/arm64/libnode.so": error=2, No such file or directory
```

`error=2` is the string to search for, because nothing else points here. A white
screen after a successful setup reads as a WebView or server problem, and every
visible clue leads away from an APK that is structurally complete and empty of
content. The same hole has also produced an APK carrying no server files at all,
noticed only because its size looked wrong rather than because anything
reported it.

A worktree has one other missing-file trap, and the contrast is the point: without
`local.properties` the build dies in well under a second, loudly and at the right
place. This one costs more precisely because it passes every gate first.


`scripts/device-test.sh` installs the APK and checks that what shipped actually
runs: the server answers, every bundled tool starts, and Python imports the ten
modules that need a bundled library behind them. It also reads what a toolchain
install left behind and how the terminal is configured to start.

Two limits are worth knowing before reading a green run. Almost everything goes
through `run-as`, which is a different SELinux domain than the app and may
execute files the app itself is refused, so no result there says a command works
in the app's own terminal. And a default run clears app data, which removes any
installed toolchain: the toolchain checks then report skipped, and only
`--skip-install` on a device that has one makes them measure anything.

**Nothing runs it automatically, and that is a measured conclusion rather than an
oversight.** GitHub's arm64 runners expose no `/dev/kvm`, so an emulator there
runs under full software emulation; and nine of the eleven bundled executables
request `/system/bin/linker64`, so running them under `qemu-user` needs Android's
Bionic from a system image: 2.1 GB, inside a partitioned disk image. Both routes
were attempted and measured; the issue tracker carries the evidence.

So it falls to a person. Two suites, one cadence (`device-test.sh` inspects what
shipped, `--instrumented` runs the app):

- before tagging a release: **both**, and the instrumented one first, because it
  is the only thing here that starts the app rather than reading what was packed
  into it;
- after changing anything under `scripts/download-*.sh` or `scripts/build-*.sh`,
  which decide what gets bundled: `device-test.sh`;
- after a Node, Python or VS Code version bump: `device-test.sh`;
- after touching `MainActivity`, `SplashActivity`, `NodeService`, `ProcessManager`
  or `FirstRunSetup`: `--instrumented`, the only thing that runs them on a
  device. The JVM suite reaches parts of all five; what it never does is start
  the app.

`--instrumented` writes what it did to
`android/app/build/reports/device-run.txt`: the time, the commit, the device
fingerprint, Gradle's exit status and the counts read out of the XML that run
wrote. **Before tagging a release, put that record in the release notes or the
pull request that prepares the tag.** No check enforces it, and none can while
no runner this project has measured is able to start the suite. What the record
changes is that "was it run, on what, and when" has an answer that is a file
rather than a recollection, and that a suite which never started says so instead
of reporting zero failures.

```bash
bash scripts/device-test.sh                 # build, install, and test
bash scripts/device-test.sh --skip-build    # test an APK you already built
bash scripts/device-test.sh --self-check    # no device: just check the suite reads its own expectations
bash scripts/device-test.sh --instrumented  # run the androidTest suite on a booted arm64 emulator
```

`--instrumented` checks its preconditions before handing off to
`./gradlew connectedDebugAndroidTest`, because both things that go wrong here go
wrong as a timeout rather than as a message: an x86_64 emulator accepts the
install and then has no arm64 library to load, and the gitignored asset tree
being absent produces an APK that builds, installs, opens, and dies. It names
every missing precondition rather than stopping at the first. With more than one
emulator attached it refuses and asks for `--device SERIAL`, which it passes on
as `ANDROID_SERIAL`; Gradle would otherwise pick one for itself and not say
which, so a green run would not name the API level it was green on.

This suite once demanded Node `v20.x` for two releases after the runtime moved to
24.18.0, and the drift was found by reading it rather than by running it. The
versions it checks are now read from the build rather than written down, and
`--self-check` runs in CI to confirm those readings still resolve, but neither
replaces running it on a device.

### Test the wire, not only the predicate

Pulling a decision into a pure function makes it easy to test and easy to leave
disconnected. Two of these shipped: `heapCeilingMb` and the port-reuse branch in
`PortFinder` were both well covered, and both could be cut out of the code that
runs with every one of their tests still green. The tests named the right
behaviour and never checked that anything used it.

Three habits catch it, and they are cheap:

**Assert at the far end of the wire.** Something downstream can usually already
observe the value. `ProcessManagerTest` spawns `/bin/echo` and merges stderr
into stdout, so the process prints its own arguments and `onServerOutput`
receives them -- the command line was observable before anyone tried to assert
on it. Look for the seam that exists before adding one.

**Pick a fixture value the broken path cannot also produce.** This is what let
both of them through. A 4 GB device derives a 512 MB ceiling, which is exactly
the literal that the regression restores, so the obvious fixture agrees with the
bug; 3 GB derives 384 and the test bites. `findAvailablePort()` scans upward
from a fixed default, so the first remembered port and a fresh scan return the
same number, and a test comparing two calls to each other moves with the bug
instead of catching it -- holding the first port forces the paths apart.

**Before asserting that something did not change, prove the code that would have
changed it actually ran.** "Unchanged" is the same result for "correctly guarded"
and "never reached", and nothing in the run distinguishes them. `settings.json` is
written through `writeAtomically` so a failed write leaves the user's file intact
-- but the method returns early when there is nothing to refresh, several lines
above the write. A test asserting the file survived a failed write passes just as
happily when the refresh decided there was nothing to do and no write was ever
attempted. The fix is to make the success case assert the file *did* change, which
establishes that the write path is reachable with that fixture; only then does the
failure case mean anything. The same reasoning applies to any `verify(exactly = 0)`
and to every "still exists", "was not deleted", "was left alone".

When the thing being judged is a guard rather than a test, measure the tree it
will guard, not the tree that caused it to be written. A rule for the welcome
screen was scored against the content the fix replaced, and every number in that
reading was correct (right command, right tree, right moment), but it described
what the rule was written to remove rather than what would have to pass it
afterwards. Nothing marks that mistake, because the data comes out of the very
file the change edits.

Confirm it rather than assuming: delete the line under test, run the suite, and
check that something red points at the deletion. A test that stays green has not
been proven wrong, only proven silent.

One case does not yield to a better fixture. When two situations are
indistinguishable in the data the code can see, no assertion separates them: a
copy interrupted partway through and an unsaved local edit both present as
"the mirror is newer and a different length", and nothing else is recorded. The
answer there was to remove one of the states rather than to test harder --
a copy now lands beside its target and is moved into place once it completes, so
an interrupted one leaves nothing behind at all. With that state impossible, the
rule could go back to preferring the newer file and lose nothing. If a fixture
cannot be written that tells two cases apart, that is worth reading as a
statement about the code rather than about the test.

And some methods cannot be called at all. A lifecycle callback needs an Activity,
an Activity cannot be built in a plain JVM test, and mockk cannot intercept the
`super` call inside it -- `onTrimMemory` gives "Method onTrimMemory in
android.app.Activity not mocked" however it is approached. Extraction cannot make
a line in there reachable. What it can do is **empty the method until nothing
worth testing is left inside it**: move the decision out with the data it needs,
and leave behind only `super`, one call, and the effects that genuinely need the
platform. `applyMemoryPressure` came out that way, and the comparison bug it
guards against went from surviving the whole suite to dying against one test.

The boundary matters, because this reads like permission to split anything.
It applies where a test cannot invoke the method: lifecycle callbacks, platform
`super` calls. Everywhere else the six call sites before it needed no production
change at all -- the seam already existed and was idle. Reach for this only after
looking for that, and say which one you found.

## Building

### Debug Build

```bash
cd android && ./gradlew assembleDebug
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk` (debug package name: `com.vscodroid.debug`)

### Unit Tests

```bash
cd android && ./gradlew testDebugUnitTest
```

This is the JVM suite, and it needs no device and no asset tree: `build.yml`'s test job runs it
against stub assets. Results land as XML in `android/app/build/test-results/testDebugUnitTest/`
and as HTML in `android/app/build/reports/tests/testDebugUnitTest/`. The instrumented suite is a
separate thing, covered under [The on-device test suite](#the-on-device-test-suite); CI compiles
it and never runs it.

### Version Bump

`versionCode` and `versionName` both live in `android/app/build.gradle.kts`, and both have
to move. They are not interchangeable. `FirstRunSetup.setupIsStale()` compares both, so either one
moving re-extracts the assets, and the versionCode is what makes that reliable: Play
refuses a repeated versionCode, while nothing stops two builds declaring one versionName.
`getPreviousVersionCode()` and the migrations it feeds threshold on `versionCode` alone.
`markSetupComplete()` stores the pair together.

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

This produces an AAB at `android/app/build/outputs/bundle/release/app-release.aab` carrying the
Ruby and Java toolchains as asset packs. Both declare `deliveryType = "on-demand"`, so neither is
downloaded with the app and neither counts against the base module's size cap.

## Continuous Integration

Seven workflows, and what triggers each is worth knowing before assuming a change was checked.
Read the triggers from `.github/workflows/` rather than from here if the answer matters.

| Workflow | Runs on | What it does |
| -------- | ------- | ------------ |
| `build.yml` | pull requests to `main`, and pushes to `main` | Prepares the assets and builds the debug APK, then a separate job runs `testDebugUnitTest` and compiles the instrumented suite with `assembleDebugAndroidTest`. Compiling it is all that happens: nothing runs it on a device |
| `lint.yml` | pull requests to `main`, and pushes to `main` | Android Lint, the `check-*.py` gates that read committed sources, `device-test.sh --self-check`, and every `scripts/test-*.js` |
| `r8.yml` (`Shrinker`) | a Monday 03:00 UTC cron, `workflow_dispatch`, pull requests to `main` touching seven configuration paths, and pushes to `main` touching those or the app Kotlin, resources and manifest | Runs the three release-only gates, R8, the resource shrinker and lintVital, through `:app:optimizeReleaseResources :app:lintVitalRelease`. The pull request list names what *configures* the shrinkers, so a pull request that only adds Kotlin does not trigger it. The push list also names the sources they run over, so such a change reaches a minified build when it lands on main, which every tag passes through; the cron covers a week with no push at all |
| `release.yml` | a `v*` tag | Builds and signs the APK and AAB, packages the toolchain ZIPs, writes the build manifest and publishes the release |
| `build-vscode-oss.yml` | `workflow_dispatch` only | Builds Code - OSS from source on an arm64 runner, around half an hour, once per VS Code version, and publishes the tarball every app build fetches |
| `patch-drift.yml` | a Monday 04:00 UTC cron, and `workflow_dispatch` | Applies `patches/` to the newest upstream stable tag. Expected to fail between an upstream move and the bump that answers it, so it is not a gate and must not be added to branch protection |
| `pages.yml` | pushes to `main` touching `docs/site/**`, `docs/USER_GUIDE.md`, `docs/PRIVACY_POLICY.md`, `scripts/build-docs-site.py` or the workflow itself, and `workflow_dispatch` | Builds and deploys the documentation site |

Two consequences of the push triggers. Most commits here land straight on `main` rather than
through a pull request, which is why `build.yml`, `lint.yml` and `r8.yml` all carry one: a gate
that only ran on pull requests would see a minority of what ships. And `r8.yml` is deliberately
absent from branch protection, because a `pull_request` trigger with a paths filter creates no
check run at all on a pull request matching none of them, so a required check would wait forever.

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

This installs the debug APK and launches SplashActivity, building the APK first only
when it is not already there. It does not clear app data, so first-run setup will not
re-run on an install that has already completed it; use the `pm clear` above for that.
It starts `com.vscodroid.debug`, which is what `assembleDebug` produces; set `PKG` to
launch another package. SplashActivity rather than MainActivity, because MainActivity
bypasses first-run setup and would open against a tree that was never extracted.

### What to Test

After deploying, verify these core flows:

1. **First-run extraction** completes with progress bar (SplashActivity)
2. **Editor** opens, can create/edit/save files
3. **Terminal** opens with bash, `node --version` and `python3 --version` work
4. **Extensions** can be searched and installed from Open VSX
5. **Git** works in terminal and SCM panel
6. **Extra Key Row** appears when keyboard is open, Ctrl+S / Ctrl+P work
7. **Crash recovery** -- kill Node.js process (`adb shell kill <PID>`), app auto-restarts

### Debugging on Device

```bash
# The app's own log lines, by component
adb logcat -s VSCodroid.MainActivity VSCodroid.NodeService VSCodroid.ProcessManager VSCodroid.ToolchainManager

# Everything the app process writes
adb logcat --pid=$(adb shell pidof com.vscodroid.debug)

# Memory
adb shell dumpsys meminfo com.vscodroid.debug

# The Node process the server runs in
adb shell ps -A | grep libnode
```

The Node process's stdout and stderr are merged and forwarded line by line to logcat by
`ProcessManager`, under `VSCodroid.ProcessManager` with a `[node]` prefix. That goes through
`Logger.d`, which emits only when the package is debuggable, so the lines are in a debug
build and not in a release one. The same reader also appends every line to `server.log`
through `ServerLog`, in every build, which is what `CrashReporter.generateBugReport` reads
its last 200 lines from. That file sits beside `remoteagent.log`, which the editor server
writes itself, in the directory `ProcessManager` hands it as `--logsPath`
(`Environment.getLogsDir`):

```bash
adb shell run-as com.vscodroid.debug ls files/home/.vscodroid/data/logs
```

Those `[node]` lines come from a reader `ProcessManager` attaches to the process it spawned.
A server it adopted instead, which is what happens when the app process is killed and the
editor server it forked survives holding the port, has no such process behind it and
produces none. That is the state a post-crash session starts in, so read silence under this
tag with the `Port ... already served by a server of ours; adopting it` line above it as an
adopted server rather than a dead one.

To probe the server yourself, take the port from logcat (`Server is ready on port NNNN`,
logged by `NodeService` at info level, so it is there in both build types) and reach it
through a forward. On a session that has been up for hours the line has usually rolled out
of the ring buffer; `assets/server.js` writes the same port down beside the pid of the
server it forked, and removes it when that server exits, so the file answers whenever there
is a server to probe:

```bash
# The port again, when the log line is gone
adb shell run-as com.vscodroid.debug cat files/server/editor-server.pid

adb forward tcp:$PORT tcp:$PORT
curl -s -o /dev/null -w '%{http_code}\n' "http://127.0.0.1:$PORT/version"
adb forward --remove tcp:$PORT
```

`/version` specifically, and only a `200` counts. There is no `/healthz`. `/` is not a
substitute: the server requires a connection token on every route but `/version`,
`/delay-shutdown` and `/callback`, so an unauthenticated `/` answers 403, and a check that
accepts anything below 500 calls a server healthy while it refuses every request it gets.
`scripts/device-test.sh` probes it exactly this way.

## How to Add a New Bundled Tool

To bundle a new tool (e.g., a new CLI binary from Termux):

### 1. Create or modify a download script

Add the package to `REQUIRED_PACKAGES` in `scripts/download-termux-tools.sh`, or create a new script in `scripts/` that sources `scripts/lib/termux-packages.sh`. Do not hand-roll the download: the library owns the index fetch, the signature check on that index, package resolution and the per-`.deb` digest check, so a `curl` straight at a `.deb` is the one payload in the tree whose bytes are never measured against anything signed. Nothing in CI will tell you, because `check-build-steps.py` checks that scripts are documented and invoked, not how they fetch.

```bash
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
WORK_DIR="$ROOT_DIR/toolchains/termux-packages"   # required; the library refuses to act without it

# The index fetch, its signature check, resolution and the digest check on each
# .deb. It also picks the mirror; TERMUX_MIRROR still overrides it.
. "$SCRIPT_DIR/lib/termux-packages.sh"

REQUIRED_PACKAGES=(tool libdep)

termux_fetch_index
termux_resolve_packages resolved-tool.tsv "${REQUIRED_PACKAGES[@]}"
termux_download_packages "${REQUIRED_PACKAGES[@]}"
termux_extract_packages "${REQUIRED_PACKAGES[@]}"

# What the caller still owns: where the files go, and which are ELF objects.
# Each package unpacks to $WORK_DIR/extracted/<package>/data/data/com.termux/files/usr
JNI_DIR="$ROOT_DIR/android/app/src/main/jniLibs/arm64-v8a"
LIB_DIR="$ROOT_DIR/android/app/src/main/assets/usr/lib"
TERMUX_USR="$WORK_DIR/extracted/tool/data/data/com.termux/files/usr"

# Binary into jniLibs, renamed to lib<name>.so for the .so trick
cp "$TERMUX_USR/bin/tool" "$JNI_DIR/libtool.so"

# Shared library dependencies into assets/usr/lib/ (if any)
cp "$WORK_DIR/extracted/libdep/data/data/com.termux/files/usr/lib/libdep.so" "$LIB_DIR/"

# If the tool runs commands through a shell, repoint the one Termux compiled
# in: that path is under Termux's own prefix and this app cannot reach it.
# The packaging gate fails on a binary that still names it. Call this only for
# the tools that have one, since it also fails on a binary naming no shell at
# all, which is the check that catches a package moving its default.
python3 "$SCRIPT_DIR/patch-default-shell.py" "$JNI_DIR/libtool.so"

# Every binary this places has to pass the load gate before it ships
python3 "$SCRIPT_DIR/verify-android-elf.py" "$JNI_DIR/libtool.so"
```

`termux_pkg_version <pkg>` gives the version the index resolved, for a path or a manifest. If a workflow is going to run the new script with `bash scripts/...`, `check-build-steps.py` requires this document to name it; the script table above is where it goes. If the PR build's asset-preparation job runs it, `build-all.sh` and `release.yml`'s build-release job have to run it as well.

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

Patches are unified diffs in `patches/`, applied to the VS Code source with `git apply` before gulp
builds it. `scripts/build-vscode-oss.sh` resets the tree, then applies every
`patches/*.patch` in glob order under `set -euo pipefail`, so a patch whose context has shifted
fails the build instead of being skipped.

### Steps

1. **Get a source tree.** `scripts/build-vscode-oss.sh` clones one into its work volume; the same
   clone is what you edit against.

2. **Make the change in readable source** and produce the diff:
   ```bash
   git -C /path/to/vscode diff > patches/NNNN-short-description.patch
   ```
   Number it after the last existing patch: run `ls patches/` rather than assuming, since the count
   moves. Order matters: they are applied in filename order.

3. **Leave a fingerprint.** The build's Verify stage greps the packaged bundles for a string from
   each patch, because a patch applying cleanly proves nothing about whether the file was in this
   target's graph. Add a row to `patches/fingerprints.txt` naming the bundle it must reach and a
   string minification cannot rewrite: a string literal, an HTML attribute, a comparison against
   a literal. Never a variable name, which is mangled, and never a comment, which is stripped.
   The pattern must also occur in what the patch itself ADDS, which `introduced_by` reads out of
   the diff, so a string lifted from surrounding context is refused rather than accepted as
   evidence. Matching is tolerant of quote style and whitespace, so a row written `case"android"`
   still matches a bundle emitting `case 'android'`. Every patch needs a row: a patch with none
   fails the check rather than passing silently. Where no fingerprint is possible, the row says
   so and states how the patch is proven instead. `scripts/check-patch-fingerprints.py` runs
   these expectations, and it runs on three sides: against the tree the build produced, against
   the tarball the fetcher downloaded, and against `assets/` before Gradle packages it.

   A row proves the patch ARRIVED, not which revision of it did. Which revision is answered by
   `vscodroid-patches.json`, the sha256 of every patch's diff that `build-vscode-oss.sh` writes
   into the tree, so a row no longer has to move on every edit. Move one where the revision adds
   something durable to point at. Patch 0001's row names the `isLinux` widening for that reason:
   the patch adds two things that compare against `'android'`, and a pattern matching either would
   still pass on a patch narrowed back to the plain `'linux'` test, which leaves patch 0009's
   branch unreachable with every row printing ok.

   Only the diff body is hashed, from the first `diff --git` down, so the prose above a patch is
   free to rewrite. Editing a line inside the diff means the server has to be built and refetched
   before any APK can be packaged, because until then the manifest in the tree names the old text
   and the check says so.

4. **Build and test:**
   ```bash
   # in CI: run the "Build Code - OSS server" workflow
   # locally: see the header of scripts/build-vscode-oss.sh for the docker invocation
   ./scripts/fetch-vscode-oss.sh
   cd android && ./gradlew assembleDebug
   ```

5. **Explain why in the patch's own commit message.** A diff shows what changed; the reason it is
   needed on Android is what the next person will not be able to reconstruct.


## Things that break quietly

Four rules that are not obvious from reading any single file, and that no test
enforces. Breaking one costs nothing at build time and shows up on a device.

**`initBridge()` runs once per WebView, not once per folder.** It returns early
on `bridgeInitialized` (`MainActivity.kt`). Re-registering the JavaScript
interface issues a new session token, and the page is still holding the old one,
so every bridge call from that page starts failing. The one intended
re-registration is after a renderer crash, where `recreateWebView()` clears the
flag for a view that no longer has the interface.

**Setup staleness compares `versionName` and `versionCode` together;
migrations threshold on `versionCode` alone.** `setupIsStale()` is true when
either differs from what `markSetupComplete()` stored, so either one moving
re-extracts the assets. `runPreExtractionMigrations()` and `runMigrations()` are
both fed by `getPreviousVersionCode()`, which reads the stored code and nothing
else (`FirstRunSetup.kt`). Move the name and leave the code alone and the
extraction happens while the migration that was supposed to accompany it never
runs.

**Tool symlinks are rebuilt on every launch, not only on first run**
(`SplashActivity.kt` calls `setupToolSymlinks()` unconditionally). Android
assigns a new `nativeLibraryDir` on every reinstall, which dangles every
absolute symlink pointing into it. `File.exists()` follows links and answers
false for a dangling one, so staleness is detected with `Os.lstat()`.

**`server.js` rewrites `product.json` on every start with a shallow
`Object.assign`.** Nested objects are replaced whole, not merged, so
`extensionsGallery` has to carry every field it needs. Dropping one from the
override silently removes it from the running product.

## Code Style

### Kotlin

- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html).
- No auto-formatter is enforced yet. Keep formatting consistent with surrounding code.
- Prefer `val` over `var`.
- Use meaningful names. Add KDoc for public APIs.

### JavaScript / Node.js

- `assets/server.js`, `assets/process-monitor.js`, `assets/platform-fix.js`, `assets/dns-proxy.js` and the four `assets/extensions/vscodroid.*` trees are hand-written JavaScript (not minified) and are the only parts of `assets/` in git. Keep them readable.
- Use `const`/`let`, not `var`.
- No TypeScript -- these run directly on the bundled Node.js.

### Shell Scripts (scripts/)

- Must work on macOS bash 3.2 (the default shell on macOS) -- no bash 4+ features.
- Use `set -euo pipefail` at the top of scripts.
- Use `bsdtar` for `.deb` extraction (macOS compatibility).
- Use `python3` for in-place edits, not `sed` (`sed -i` takes different arguments on macOS and Linux).
- Quote all variable expansions.
- A pipeline's exit status is its **last** command's, so `cmd | sed || echo` binds the `||` to the
  pipeline rather than to `cmd`, and a `grep` that matches nothing inside one reports success. Test
  the result, not the pipeline. This has produced silently-passing checks here more than once.

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

### Review Findings

A review often turns up more than the change can carry: something adjacent, something
pre-existing, something real but out of scope. Give every one of those a home before the
PR merges.

- **Fixed in the same PR**: nothing more to do.
- **Not fixed**: open an issue, and link it from the review thread. One issue per finding,
  titled so it can be found by name.
- **Rejected**: say so in the thread, with the reason. "Checked, does not apply because X"
  is a resolution; silence is not.

The rule is that no finding leaves review referenced only by something ephemeral: a position
in a list, "the second one", a number that exists nowhere in this repository. Those references
stop meaning anything the moment the discussion scrolls away, and the finding is then either
rediscovered at full cost or quietly assumed to be handled.

An issue is cheap. Re-deriving a defect someone already found is not.

### PR Checklist

- [ ] Tested on physical ARM64 device (if applicable)
- [ ] No unrelated changes included
- [ ] Commit messages follow Conventional Commits format
- [ ] Documentation updated (if behavior changes)
- [ ] Review findings left unfixed have issues, and rejected ones have a reason in the thread
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
