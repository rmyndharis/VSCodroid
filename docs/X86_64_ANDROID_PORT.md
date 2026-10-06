# Native x86_64 Android port investigation

Research date: 2026-10-06. Target: user's Lenovo Googlebook running Android 17.

## Conclusion

A native x86_64 port is feasible. The Kotlin/WebView app architecture stayed;
the native server runtime, addons, tools, compatibility code, and packaging
are architecture-aware. Installing the Termux app is unnecessary:
VSCodroid bundles selected Termux-built Android binaries in its own APK.

A host x86_64 debug APK has been assembled. It has not been run on a device:
ADB reported no devices, and device testing was deferred. Confirm the device's
reported ABI before installation; the exact model remains unconfirmed. Lenovo's
published Googlebook 15 specification names Intel Core Ultra 5, but hardware
specifications alone do not establish the app runtime ABI. What that host build
produced is in [x86_64 build validation](X86_64_BUILD_VALIDATION.md).

## Restrictions this port started from

These limits were in the checkout before the port. The table is that record,
not the current build.

| Area | Restriction found | Change made |
| --- | --- | --- |
| Android Gradle | `app/build.gradle.kts` filters to `arm64-v8a`; multiple gates hardcode its JNI directory | Select ABI and matching staged assets together |
| Package downloads | `download-node.sh`, `lib/termux-packages.sh`, and tool scripts use ARM package indexes/paths | Resolve x86_64 packages with all dependencies and existing signature/digest verification |
| Native addons | `build-native-addons.sh` selects `aarch64-linux-android` and ARM64 CMake ABI | Compile node-pty, watcher, SQLite, and ZeroMQ against x86_64 Bionic and the bundled Node headers |
| Server | Build/fetch scripts expose `ARCH`, defaulting to `arm64`, but verification, ripgrep paths, and workflows remain ARM-specific | Build patched `vscode-reh-web-linux-x64` on a Linux x64 host; replace Linux runtime/addons with Android builds |
| Runtime setup | `FirstRunSetup.kt` creates `android-arm64` aliases and references `linux-arm64` and Copilot ARM packages | Derive Node package architecture (`x64`) from the packaged build and update aliases/paths |
| Python | `wheelhouse.json` and `build-wheelhouse.py` contain ARM64 native wheels/paths | Retain pure Python wheels; source/build x86_64 native wheels and verify matching CPython tags |
| Optional languages | Ruby uses `aarch64-linux-android` module paths; Java builds an ARM spawn helper | Create x86_64 packs and architecture-specific manifests/download URLs |
| Verification/CI | ELF checks expect `EM_AARCH64`; server checks and caches are ARM-oriented | Explicit expected machine per target, isolated caches, full dependency checks, x64 CI and device tests |

The live official Termux x86_64 Packages index was inspected. It listed
`nodejs-lts` 24.18.0-1, Python 3.14.6-1, Bash 5.3.20, Git 2.56.0,
OpenSSH 10.5p1, tmux 3.7c-1, make 4.4.1-1, ripgrep 15.2.0,
Ruby 4.0.7, OpenJDK 17.0.20, and libc++. Node matches the current
addon script's default 24.18.0 headers. This confirms package availability,
not successful relocation or execution inside VSCodroid; production downloads
must still use the repository's authenticated index verification.

## Main engineering risks

`seccomp-shim.c` is explicitly ARM assembly: registers x0 to x8, `svc #0`,
ARM syscall numbers, ARM ucontext access, and signal return assumptions. Changing
the compiler target alone cannot port it. An x86_64 implementation must use its
own syscall convention, register context, syscall constants, and signal restorer
behavior. Android 17 may permit the syscall that originally motivated the shim;
measure first, then decide whether the x86_64 launcher needs it. Do not assume
an ARM-tested trap handler works on Intel.

`glibc-shim.c` and `gen-glibc-forwarders.py` contain libc structure and symbol
assumptions measured on ARM64. Audit signal, stat, directory, and other translated
layouts against x86_64 glibc and Bionic. Change the loader alias to the appropriate
x64 name where needed. Prefer rebuilt Bionic addons for essential editor features;
expand support for third-party Linux addons only after dedicated ABI tests.

The musl loader comes from Alpine's aarch64 repository today. Use its x86_64
counterpart, adjust extraction paths and digest records, and validate native
extension subprocesses. The marketplace patch's Alpine selection is potentially
reusable with Node `process.arch === 'x64'`, but extension-specific package names,
setup aliases, and compatibility must be tested.

Adding both JNI ABIs to one APK does not select the corresponding files in ordinary
assets. Server addons, shared libraries in `assets/usr`, Python modules, and
toolchain assets need explicit per-target staging or runtime selection. Start
with separate APKs, then design multi-ABI delivery if needed. Separate all build
workspaces, package indexes, resolved records, and caches by architecture to avoid
silently combining ARM and x86 payloads.

Android's execution restrictions remain: packaged executables use the project's
`.so` naming convention under `nativeLibraryDir`; optional payloads use its loader
and trampoline mechanism. Root is not part of the proposed design. Successful
execution from ADB shell does not prove execution in the app's SELinux/seccomp
domain, so smoke tests must launch through VSCodroid itself.

## Implementation sequence and acceptance criteria

1. **Confirm target and define architecture configuration.** Collect ABI list,
   API level, page size, and WebView version. Define a single mapping:
   Android ABI `x86_64`, Termux architecture `x86_64`, NDK triple
   `x86_64-linux-android`, VS Code/Node architecture `x64`, ELF machine 62,
   Alpine architecture `x86_64`. Preserve the ARM64 mapping. Stage each target
   independently and add expected-ABI arguments to verification before downloads.
2. **Prove the native runtime.** Bundle x86_64 Node and its dependency closure,
   apply the existing Termux path relocation rules, and build the execution
   trampoline. Produce a minimal app-domain smoke test for Node, shell spawning,
   filesystem access, and a localhost HTTP/WebSocket connection. This resolves
   foundational runtime risk before building the full IDE.
3. **Make the core editor work.** Build the repository's pinned, patched server
   on Linux x64; fetch/package it under a target-specific artifact identity.
   Recompile Bionic addons, install the Android ripgrep binary, and update setup
   aliases. Verify first-run extraction, editor startup, PTY terminal, file
   watching, search, SQLite, extension host, and server restart.
4. **Complete bundled tools and Python.** Port tool downloads and shared library
   staging. Validate Git including HTTPS/SSH, Bash, tmux, Python/pip, npm/npx,
   and a representative Vite project. Native wheel availability is separate
   from interpreter availability: publish only wheels validated for x86_64.
5. **Port compatibility features and optional languages.** Audit/reimplement
   glibc and seccomp shims as required; validate the x86_64 musl loader with
   actual extension binaries. Build Ruby and Java packs, including the spawn
   helper. Add ABI metadata and fail before installation on mismatched packs.
   Replace generic release URLs with architecture-specific assets; never let
   an x86_64 installation download today's ARM64 optional packs.
6. **Validate on Android 17 and the Googlebook.** Test clean install and upgrade,
   offline startup, extension installation, native subprocesses, suspend/resume,
   background server behavior, physical keyboard shortcuts, mouse/trackpad,
   resizing, and dev-server preview. Add x86_64 emulator coverage on a host with
   hardware acceleration and retain an ARM64 regression build.
7. **Package reproducibly.** Produce a sideloadable x86_64 APK, provenance and
   digest records, licensing notices, target-specific optional packs, and CI
   caches/artifacts. Multi-ABI Play delivery is a later packaging milestone;
   measure download size and asset selection before adopting it.

Keep `targetSdk = 36` for the initial port, as this checkout already does;
Android 17 hardware does not require targeting API 37. The app already compiles
against API 37 and supports API 33+. Raising the target is a separate migration:
LAN communication needs declaration and runtime handling of
`ACCESS_LOCAL_NETWORK`. Android 17 also documents read-only requirements for
native files loaded via `System.load()` when targeting 37; audit actual loading
paths rather than assuming that Java API rule covers every Node `dlopen` path.
Preserve the existing 16 KB ELF alignment gates during the port and check the
actual device page size.

## Device information to collect

```sh
adb shell getprop ro.product.model
adb shell getprop ro.product.cpu.abilist
adb shell getprop ro.build.version.sdk
adb shell getconf PAGE_SIZE
adb shell dumpsys webviewupdate
```

## Sources

- [Android ABI support and APK library layout](https://developer.android.com/ndk/guides/abis)
- [NDK cross compilation and target triples](https://developer.android.com/ndk/guides/other_build_systems)
- [Termux supported build architectures](https://github.com/termux/termux-packages/wiki/Building-packages)
- [Official Termux x86_64 package index inspected](https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-x86_64/Packages)
- [Android app home execution restrictions](https://developer.android.com/about/versions/10/behavior-changes-10)
- [Android 16 KB page support](https://developer.android.com/guide/practices/page-sizes)
- [Android 17 target-specific behavior changes](https://developer.android.com/about/versions/17/behavior-changes-17)
- [Android local network permission](https://developer.android.com/privacy-and-security/local-network-permission)
- [Lenovo Googlebook 15 announcement/specifications](https://news.lenovo.com/pressroom/press-releases/first-googlebook-premium-ai-experiences-sleek-lightweight-design/)
