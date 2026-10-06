# x86_64 build validation

Validation on 2026-10-06 used a macOS ARM64 host, Android SDK 37, and NDK
27.3.13750724. The build selects `VSCODROID_ABI=x86_64` and Gradle
`-PvscodroidAbi=x86_64`. These are host build checks, not Android runtime results.

## Completed

- Android x86_64 unit suite: 399 suites, 2,650 tests, no failures.
- Default ARM64 unit suite: 399 suites, 2,650 tests, no failures.
- Target selection/staging/provenance tests and both ABI ELF/server validator
  self-tests passed.
- Actual x86_64 Node, npm, Python, shell/Git/tools, and signed Alpine musl
  downloads passed. The musl loader was rebuilt from the pinned, verified
  Alpine maintenance sources and passed the 16 KB ELF alignment check.
- Current JNI scan: 13 binaries; `assets/usr` scan: 128 x86_64 ELF files,
  zero rejected. Native shims also cross-compiled for ARM64 into temporary
  directories. See [native validation](X86_64_NATIVE_VALIDATION.md).
- x86_64 Python wheelhouse downloads and repacked psutil passed their pinned
  digests. Ruby/Java native downloads passed their ELF checks.
- Shell/Python syntax, workflow pinning/build-step coverage, punctuation,
  translation-string and local-network permission checks passed.

## Local server build constraints

Docker's VM has approximately 8 GB of memory and also runs another user's
container. The normal emulated x64 pipeline exceeded that memory ceiling.
The source type check was therefore completed separately with the exact
TypeScript 7.0.2 Darwin ARM64 compiler whose package integrity is pinned in
VS Code's lockfile, over a copy of the patched source and its type dependencies.
It returned zero errors.

The local build then temporarily replaces only that already-completed source
check in the Docker work volume and builds the requested web-server bundle with lower Go memory/concurrency
settings to reduce peak memory. This modification is confined to the build volume and is
restored afterward; it is not a committed patch or a disabled CI check. The
committed x64 workflow retains the normal upstream source-checking pipeline
and should run on a native Linux x64 runner with adequate memory (16 GB is
recommended). The local workaround does not count as an unmodified clean CI
build.

## APK assembly

The clean 16 GB rebuild produced
`vscode-reh-web-linux-x64-1.139.1.tar.gz` with digest
`26f295c17af78a4adfdcbfe14c29a7a5ef4ccd387dbcb85ece3d97c18d89e14e`, the same
bytes as the staged tree. `product.json` in the APK names commit
`04c0d99f4fb0d8afe6ce4f0c58e31e183ac3e4b1` and version 1.139.1.

`assembleDebug -PvscodroidAbi=x86_64` succeeded on 2026-10-06. The debug APK
is 198 MB at `android/app/build/outputs/apk/debug/app-debug.apk`. It contains
14 `lib/x86_64` libraries and no `lib/arm64-v8a` entries. Packaging checks
passed, including the staged ABI, server tree, bundled binaries, native
addons, and 16 KB alignment (137 x86-64 ELF files, none rejected).

Before that build, three host-side gaps were closed on the staged tree:

- Optional Copilot CLI and MSAL broker natives were removed, and the
  extension bundles were switched to the Android paths, by
  `scripts/prepare-android-runtime.py`.
- `watchdog.node` imports `__stack_chk_fail`, `exit`, `kill`, and `sleep`
  from glibc. Regenerating the x86_64 stubs with `scripts/build-glibc-shim.sh`
  put those symbols in `libc.so.6`.
- `kerberos.node` (both copies), `watchdog.node`, and `deviceid`'s
  `windows.node` were 4 KB aligned. `scripts/align-android-pages.py` moved
  their data segments onto a 16 KB modulus without changing virtual
  addresses. Fetch and source builds now run that step.

The optional x64 packs and wheelhouse still require separate publication
before their public download URLs become available.

## Remaining acceptance

No Android device is connected. Check the Googlebook's ABI with
`adb shell getprop ro.product.cpu.abilist` and API level with
`adb shell getprop ro.build.version.sdk`, then run app-domain acceptance:
first-run setup, Node/server startup, PTY, watching, search, SQLite, Git,
Python/npm, optional packs, extensions, keyboard/trackpad, resizing,
suspend/resume, and preview. Termux is not required on the device.

The x64 upstream ripgrep binary has 4 KB alignment, so the fetch step uses a
signature- and digest-verified Termux Bionic ripgrep for x64, keeps the same
server path, and packages its notice and resolved version. The ELF gate remains
strict.
