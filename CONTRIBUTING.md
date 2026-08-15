# Contributing to VSCodroid

Thank you for your interest in contributing to VSCodroid. This guide covers everything you need to set up a development environment, build the app, test on device, and submit changes.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Development Setup](#development-setup)
- [Project Structure](#project-structure)
- [Download Scripts](#download-scripts)
- [Building](#building)
- [The on-device test suite](#the-on-device-test-suite)
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

This is the order CI uses, and the order matters — each step below notes why.

```bash
# 1. Fetch the Code - OSS server tree built by the build-vscode-oss workflow.
#    This leaves it in server/, not in assets/.
./scripts/fetch-vscode-oss.sh

# 2. Copy that tree into assets/. Nothing else does this, and an APK built
#    without it installs and opens with no editor in it.
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
./scripts/download-go.sh
./scripts/download-ruby.sh
./scripts/download-java.sh
```

Alternatively, run steps 1–10 and the APK build in one go:

```bash
./scripts/build-all.sh
```

`scripts/check-build-steps.py` runs in CI and fails when this list, `build-all.sh`
and the workflows stop agreeing about which scripts a build needs.

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
│   │   │   │   └── libldmusl.so          # musl loader (runs the Claude Code CLI)
│   │   │   └── res/                   # Android resources, layouts
│   │   └── build.gradle.kts
│   ├── toolchain_go/              # Go on-demand asset pack
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
│   ├── download-go.sh                # Download Go toolchain
│   ├── download-ruby.sh              # Download Ruby toolchain
│   ├── download-java.sh              # Download Java (OpenJDK 17) toolchain
│   ├── build-all.sh                  # Run all download/build scripts
│   ├── deploy.sh                     # Build + install + launch on device
│   └── device-test.sh                # Run device tests
├── toolchains/                    # Work dir for the download scripts — gitignored,
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
| `SplashActivity.kt` | First-run asset extraction, progress UI, Language Picker |
| `VSCodroidApp.kt` | Application class, WebView pre-warm, CrashReporter init |
| `bridge/AndroidBridge.kt` | JS interface: clipboard, file picker, OAuth, SSH, storage, toolchains |
| `bridge/SecurityManager.kt` | The bridge session token: issues it, and validates it on every `@JavascriptInterface` call. It judges the caller, never the destination — there is no URL allow-list, and WebView navigation is decided in `VSCodroidWebViewClient.shouldOverrideUrlLoading` |
| `keyboard/ExtraKeyRow.kt` | Multi-page key bar with ViewPager2 and dot indicators |
| `keyboard/GestureTrackpad.kt` | 3-speed drag-to-cursor-navigate widget |
| `keyboard/KeyInjector.kt` | Injects KeyboardEvent into WebView via JS |
| `service/NodeService.kt` | Foreground Service (specialUse) to keep Node.js alive |
| `service/ProcessManager.kt` | Node.js process lifecycle, health check, auto-restart |
| `setup/FirstRunSetup.kt` | Asset extraction, symlink creation, settings, .bashrc |
| `setup/ToolchainManager.kt` | Toolchain install, uninstall and env vars over **two** delivery paths chosen at runtime by install source: Play Asset Delivery, and `downloadViaHttp()` fetching ZIPs from `releases/latest` for every non-Play install. Both converge on `installFromDirectory()`. Work only the Play path and you break sideload users, who are the ones testing |
| `util/Environment.kt` | PATH, HOME, LD_LIBRARY_PATH, all env vars for Node.js process |
| `webview/VSCodroidWebViewClient.kt` | CDN URL interception, vscode-resource serving, crash recovery |

## Download Scripts

Each script downloads pre-built binaries and places them in the correct location under `android/app/src/main/`.

**To find out which version of something ships, read the script — never the
assets tree.** `android/app/src/main/assets/` is build output, gitignored, and
filled by whichever run of these scripts happened last; a checkout can be weeks
stale and still hold a well-formed file that answers confidently. That has
produced a wrong number in a published issue: `node_modules/npm/package.json`
was read from a working tree, parsed cleanly, and reported a version the app had
already stopped shipping. The rule is not "read the assets tree carefully" — the
assets tree is never the answer, even on the days it happens to be right, and
two people reading it the same way got different results only because their
checkouts differed.

| Script | What it does | Output location |
| ------ | ------------ | --------------- |
| `fetch-vscode-oss.sh` | Downloads the Code - OSS server tree from the `server-<version>` release, verifies it, and installs ripgrep as `libripgrep.so` | `server/vscode-reh/`, `jniLibs/arm64-v8a/libripgrep.so` |
| `build-vscode-oss.sh` | Builds that tree from the MIT source with `patches/` and `branding/` applied. Run by the build-vscode-oss workflow on an arm64 runner, or locally in Docker; not needed for a normal build | a `.tar.gz` published as a release asset |
| `verify-server-tree.py` | Checks a server tree: required paths, no vsda, no bundled GNU/Linux node, every native binary aarch64, branding applied. Run by both scripts above on the tree they produce, and again by the `verifyServerTree` Gradle task on the copy in `assets/` that is actually packaged — which on a warm-cache build is the only run that happens | exit status |
| `download-termux-tools.sh` | Downloads bash, git, tmux, make, openssh and every shared library the bundled binaries link against, including Node's | `jniLibs/arm64-v8a/`, `assets/usr/` |
| `download-node.sh` | Installs Termux's `nodejs-lts` as `libnode.so`. Run after `download-termux-tools.sh`, which places the libraries it links against | `jniLibs/arm64-v8a/libnode.so` |
| `verify-android-elf.py` | Checks a binary can load on Android: aarch64, no unbundled dependency, 16 KB-aligned segments. Called by every script that installs a binary — the Termux, Node, Python, musl and toolchain downloads, the native-addon and shim builds, and `fetch-vscode-oss.sh` for ripgrep — each on the one file it just placed. `--dir` checks a whole directory instead, which is how the `verifyBundledBinaries` Gradle task re-examines all of `jniLibs/` at packaging time, including binaries restored from a cache that no download step re-ran | exit status |
| `verify-termux-index.sh` | Checks the Termux package index against the repository's signed `InRelease` before any digest is read out of it, so the filenames and checksums the download scripts trust rest on a signature rather than on one host. Called by every script above and below that reads the index; needs `gpg`. A cached index that has fallen behind is refetched once rather than refused, since callers keep one for an hour and upstream publishes daily | exit status |
| `download-npm.sh` | Extracts npm from Node.js linux-arm64 tarball | `assets/usr/lib/node_modules/npm/` |
| `download-python.sh` | Downloads Python + deps from Termux. The version is whatever the Termux index currently carries, detected at download time rather than pinned here | `jniLibs/arm64-v8a/`, `assets/usr/lib/python<major.minor>/` |
| `download-extensions.sh` | Downloads marketplace extensions from Open VSX. Every entry must be pinned as `publisher.name@version`, and the resolved version must equal the pin: the cleanup sweep names each directory from the pin while the extraction names it from what Open VSX returned, so a difference makes the sweep delete the tree on every run | `assets/extensions/` |
| `download-musl-loader.sh` | Extracts musl's dynamic loader from the Alpine package. The Claude Code CLI ships as a musl binary and Android has no loader for it. The version comes from the branch index at download time rather than being pinned here, so the run records which one it installed | `jniLibs/arm64-v8a/libldmusl.so`, `toolchains/musl/resolved-musl.tsv` |
| `build-native-addons.sh` | Cross-compiles node-pty, `@parcel/watcher` and `@vscode/sqlite3` for Bionic using the NDK, with 16 KB page alignment. Checks each `.node` against the JavaScript version shipped beside it | `assets/vscode-reh/node_modules/*/build/Release/*.node` |
| `build-glibc-shim.sh` | Scans the packaged tree for addons built against glibc and generates versioned stub libraries so Bionic's loader accepts them. Run last: `download-termux-tools.sh` wipes the directory the stubs live in | `assets/usr/lib/libglibc-shim.so` and per-soname stubs |
| `package-toolchains.sh` | Zips the toolchain asset-pack directories for the GitHub Release that non-Play installs download from | `toolchain-zips/toolchain_*.zip` |
| `download-go.sh` | Downloads Go toolchain from Termux | `toolchain_go/src/main/assets/` |
| `download-ruby.sh` | Downloads Ruby + deps from Termux | `toolchain_ruby/src/main/assets/` |
| `download-java.sh` | Downloads OpenJDK 17 + deps from Termux | `toolchain_java/src/main/assets/` |
| `check-build-steps.py` | Five checks, and the script prints one line per check so the count is readable from a run rather than from here. Three about shell scripts: the documented build sequence, `build-all.sh`, and the two build workflows all still name the same ones — the third pairs `build.yml` against `release.yml`, so a step dropped from the tag path alone is caught. Then every `scripts/test-*.js` runs in both `lint.yml` and `release.yml`, so a self-check cannot be added and then run by nothing; and every `scripts/check-*.py` is invoked by something, which is the answerable question for that family since several take arguments and run from a script or from Gradle. The self-check rule matches an invocation, not a mention — a script named only in a comment does not count. ⚠️ The shell rules match `bash scripts/*.sh` only, so a script a workflow runs with `python3`, or one called from inside another script, is still not covered — those are listed here by hand. The build-vs-release pairing is also one-directional: a script that runs only on the tag path can be dropped from it and nothing notices | exit status |
| `write-build-manifest.py` | Records what a build resolved — editor version and commit, server tarball digest, the musl loader's version and checksum, and the version and checksum of every Termux package — and the release workflow attaches it to the release. A record, not a lock: superseded packages are dropped upstream, so a pin would break the build on every routine update. `--compare` reports the differences against an earlier manifest and always exits 0 | `build-manifest.txt` |
| `check-langserver-patterns.py` | Checks the process monitor can recognise the language servers being packaged. A pattern matching nothing is invisible twice: the server keeps running, keeps counting against the phantom-process budget, and the idle-kill never sees it | exit status |
| `check-patch-fingerprints.py` | Checks a packaged tree carries every patch in `patches/`, using the expectations in `patches/fingerprints.txt`. Takes the tree as an argument, so the same check can run against a downloaded tarball | exit status |
| `check-welcome-claims.py` | Refuses a welcome screen that names a bundled tool's version, promises a toolchain as "coming soon", or puts an undeclared number in walkthrough prose or an illustration. Those runtimes come from the Termux index at build time, so a number written into the manifest is right until the next rebuild -- it was wrong for two releases, in the illustrations as well as the text | exit status |
| `check-bridge-api-spec.py` | Checks every `@JavascriptInterface` method in `AndroidBridge.kt` against `docs/05-API_SPEC.md` on name, parameter list and return type, both directions. The spec is what an extension author writes against and nothing had held it to the bridge: one pass found fourteen disagreements across twenty-eight methods, four of them invisible to a comparison of names because only the shape was wrong. ⚠️ Half the gate, and it reports ok on what it cannot see: a method whose annotation is spelled in a way its patterns miss (`@android.webkit.JavascriptInterface`, an aliased import), and any method the bridge **inherits** — its window is one file. `BridgeApiSpecParityTest` is the other half and settles which methods exist, by reflecting over the compiled class, so spelling and inheritance stop being categories. Return types, parameter names, order and nullability go the other way: they do not survive into bytecode, so this script checks them and the test cannot. Neither checks prose | exit status |
| `check-bundle-size.py` | Checks the release bundle against Play's per-module size caps before anything is published, rather than at upload | exit status |
| `check-local-network-permission.py` | Checks local network access survives the `targetSdk` in use | exit status |
| `test-dns-proxy.js` | Exercises the loopback DNS proxy's Basic-auth contract. Loopback on Android is not isolated per app, so that token is what stands between the proxy and every other app on the device | exit status |
| `test-process-monitor.js` | Points a scan at a fixture `/proc` and checks the snapshot: that the language servers that ship are recognised, that an unrelated user process carrying a server's name in its path is not, and that the count includes the process the monitor runs inside | exit status |
| `test-platform-fix.js` | Runs the platform override under a faked `process.platform` and checks it engages for node-gyp and for nothing that merely mentions it in a path or an argument | exit status |
| `test-server-bootstrap.js` | Boots the server bootstrap against a fixture tree and checks the `product.json` rewrite: overrides applied, a truncated file named rather than thrown, an unwritable directory leaving the existing file intact | exit status |
| `test-process-monitor-extension.js` | Drives the process monitor extension against two snapshots that differ in every count and checks its notifications read the same either way. A notification cannot be edited once open, so any number baked into one freezes while the status bar beside it keeps moving | exit status |
| `test-bridge-relay.js` | Extracts the bridge relay from the Kotlin raw string it lives in and runs it against a stub bridge, driving the real bundled extension, so what is asserted is the message a user is shown. Nothing else reads that script: it is neither compiled nor linted, so a bridge change can be reverted with every suite green. Also refuses a command an extension sends that the relay has no branch for, whose only symptom is a five-second timeout naming neither the command nor the cause | exit status |

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
modules that need a bundled library behind them.

**Nothing runs it automatically, and that is a measured conclusion rather than an
oversight.** GitHub's arm64 runners expose no `/dev/kvm`, so an emulator there
runs under full software emulation; and nine of the eleven bundled executables
request `/system/bin/linker64`, so running them under `qemu-user` needs Android's
Bionic from a system image — 2.1 GB, inside a partitioned disk image. Both routes
were attempted and measured; the issue tracker carries the evidence.

So it falls to a person. Two suites, one cadence — `device-test.sh` inspects what
shipped, `--instrumented` runs the app:

- before tagging a release — **both**, and the instrumented one first, because it
  is the only thing here that starts the app rather than reading what was packed
  into it;
- after changing anything under `scripts/download-*.sh` or `scripts/build-*.sh`,
  which decide what gets bundled — `device-test.sh`;
- after a Node, Python or VS Code version bump — `device-test.sh`;
- after touching `MainActivity`, `SplashActivity`, `NodeService`, `ProcessManager`
  or `FirstRunSetup` — `--instrumented`, the only thing that runs them on a
  device. The JVM suite reaches parts of all five; what it never does is start
  the app.

The last one is new because it was missing: the instrumented suite had a command
in the block below and no moment at which anyone owed it a run.

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
as `ANDROID_SERIAL` — Gradle would otherwise pick one for itself and not say
which, so a green run would not name the API level it was green on.

This suite once demanded Node `v20.x` for two releases after the runtime moved to
24.18.0, and the drift was found by reading it rather than by running it. The
versions it checks are now read from the build rather than written down, and
`--self-check` runs in CI to confirm those readings still resolve — but neither
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
reading was correct — right command, right tree, right moment — but it described
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

Patches are unified diffs in `patches/`, applied to the VS Code source with `git apply` before gulp
builds it. They used to be Python regex replacements against minified JS; that method matched on
generated identifiers, broke on every version bump, and printed `SKIP` on a miss while still exiting
0, so a patch could stop applying without failing anything.

### Steps

1. **Get a source tree.** `scripts/build-vscode-oss.sh` clones one into its work volume; the same
   clone is what you edit against.

2. **Make the change in readable source** and produce the diff:
   ```bash
   git -C /path/to/vscode diff > patches/NNNN-short-description.patch
   ```
   Number it after the last existing patch — run `ls patches/` rather than assuming, since the count
   moves. Order matters: they are applied in filename order.

3. **Leave a fingerprint.** The build's Verify stage greps the packaged bundles for a string from
   each patch, because a patch applying cleanly proves nothing about whether the file was in this
   target's graph. Add a row to `patches/fingerprints.txt` naming the bundle it must reach and a
   string that survives minification — an identifier or a literal, never a comment, which
   minification strips. Every patch needs a row: a patch with none fails the check rather than
   passing silently. Where no fingerprint is possible, the row says so and states how the patch is
   proven instead. `scripts/check-patch-fingerprints.py` runs these expectations, and it runs on
   three sides — against the tree the build produced, against the tarball the fetcher downloaded,
   and against `assets/` before Gradle packages it.

4. **Build and test:**
   ```bash
   # in CI: run the "Build Code - OSS server" workflow
   # locally: see the header of scripts/build-vscode-oss.sh for the docker invocation
   ./scripts/fetch-vscode-oss.sh
   cd android && ./gradlew assembleDebug
   ```

5. **Explain why in the patch's own commit message.** A diff shows what changed; the reason it is
   needed on Android is what the next person will not be able to reconstruct.


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

- **Fixed in the same PR** — nothing more to do.
- **Not fixed** — open an issue, and link it from the review thread. One issue per finding,
  titled so it can be found by name.
- **Rejected** — say so in the thread, with the reason. "Checked, does not apply because X"
  is a resolution; silence is not.

The rule is that no finding leaves review referenced only by something ephemeral — a position
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
