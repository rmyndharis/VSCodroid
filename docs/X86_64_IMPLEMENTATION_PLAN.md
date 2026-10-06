# x86_64 implementation plan

## Target and build contract

Deliver architecture-selectable ARM64 and x86_64 builds, initially as separate
APKs. `VSCODROID_ABI=arm64-v8a|x86_64` selects scripts; Gradle uses
`-PvscodroidAbi=x86_64` (or the environment variable). ARM64 stays the default.
The shared `scripts/lib/android-target.sh` exports `ANDROID_ABI`, `TERMUX_ARCH`,
`NODE_ARCH`, `NDK_TARGET`, `ELF_MACHINE`, and `GLIBC_LOADER`.

Existing main asset paths remain a single-target staging area for this first
implementation. Stamp the staged target and reject mismatched packaging; do not
claim that adding ABI splits selects ordinary assets. Download and compilation
caches must be architecture-specific. Optional language downloads must identify
their ABI; reject unsupported/unpublished packs rather than installing ARM packs
on x86_64.

## Parallel work assignments

1. Luna downloads: shared target mapping; core Termux downloads; Python/Ruby/Java
   downloads; musl download; wheelhouse target handling. Preserve signatures,
   digests, relocation, licensing, and ARM defaults.
2. Luna native: ABI-aware ELF/server verification; native addon builders;
   execution, glibc, and Claude compatibility builders/shims. Audit x64 assembly
   and signal ABI explicitly. Compile both targets with local NDK and test
   wrong-ABI rejection. Fail clearly on any compatibility feature that cannot
   safely be implemented.
3. Luna app: Gradle ABI selection and verification paths; runtime setup aliases;
   optional toolchain architecture selection/validation; Kotlin tests.
4. Parent integration: VS Code build/fetch/package orchestration, staging stamp,
   CI entry point, cross-agent review, script/Kotlin/native checks, build attempt,
   and final delivery documentation.

## Acceptance and validation

- One explicit target consistently controls packages, compiler triples, runtime
  aliases, Gradle packaging, and validators. Invalid or mixed targets fail.
- Essential native modules target Bionic, with runtime-compatible Node headers.
- Native ELF checks reject the other CPU architecture and retain dependency,
  loader, and page alignment checks.
- ARM64 remains the default and passes existing meaningful checks.
- x86_64 helpers compile with the NDK; unit and script tests exercise both ABIs.
- Full APK build is attempted after preparation; missing external server
  artifacts or host limitations are reported precisely.
- Hardware acceptance requires the Googlebook, which is currently disconnected:
  clean setup, Node/server, PTY, watching/search/SQLite, Git/Python/npm, optional
  packs, extensions, keyboard/trackpad, resizing, suspend/resume, and preview.

Keep target SDK 36 for this port; API 37 migration is separate. Do not represent
host compilation or ADB-shell execution as successful app-domain device testing.

## Compatibility boundary

Termux is a source of Bionic-built packages, not a required device app. This APK
bundles its own runtime. npm packages and extensions that only publish Android
ARM native bindings still need an x86_64 port or a supported WASM fallback. Keep
Node's x64 architecture rather than aliasing those packages to ARM binaries.
The x64 wheelhouse and optional packs can be built locally; their public release
URLs require a separate publication before users can download them in the app.
