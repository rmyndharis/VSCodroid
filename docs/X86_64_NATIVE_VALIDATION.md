# Native x86_64 port validation

Validation was run on 2026-10-06 with NDK r27.3.13750724 from a macOS arm64 host. This covers host-side cross compilation and ELF/layout checks. It does not establish that these binaries run in the VSCodroid Android app domain or on the Googlebook.

## ABI findings

The target is selected by `VSCODROID_ABI` and maps to the Android ABI, NDK triple, ELF machine, Node architecture, and glibc loader name through `scripts/lib/android-target.sh`. Native output and build caches are isolated by target.

The x86_64 glibc compatibility audit compared glibc 2.36 headers in the x86_64 Debian build image with NDK r27.3.13750724's Android x86_64 sysroot:

| Type | glibc x86_64 | Bionic x86_64 | Port handling |
| --- | --- | --- | --- |
| `struct sigaction` | 152 bytes; mask at 8, flags at 136, restorer at 144 | 32 bytes; flags at 0, handler at 8, mask at 16, restorer at 24 | Translated by the shim |
| `sigset_t` | 128 bytes | 8-byte unsigned long | Translated as the 64-bit kernel mask |
| `struct addrinfo` | 48 bytes; `ai_addr` at 24, `ai_canonname` at 32 | 48 bytes; `ai_canonname` at 24, `ai_addr` at 32 | Converted by the shim |
| `struct stat` | 144 bytes | 144 bytes | Same size and field offsets |
| `struct statfs` | 120 bytes | 120 bytes | Same size and field offsets |
| `struct dirent` | 280 bytes | 280 bytes | Same size and field offsets |
| `struct msghdr` | 56 bytes | 56 bytes | Same size and field offsets |
| `struct termios` | 60 bytes, speed fields at 52 and 56 | 36-byte shared kernel prefix | Bionic uses the common prefix; same behavior already verified for arm64 |
| `struct rlimit`, `sockaddr`, `passwd`, `utsname` | 16, 16, 48, and 390 bytes | Same sizes and field offsets | Forwarded directly |

The measured differences are handled before forwarding; unmodified structures match the x86_64 Linux ABI. The glibc shim now accounts for Bionic's scalar x86_64 `sigset_t` as well as arm64's union representation.

The seccomp shim has separate syscall conventions and signal register mappings for each target. x86_64 uses syscall 281 for `epoll_pwait`, syscall 13 for `rt_sigaction`, and an explicit `SA_RESTORER` stub for `rt_sigreturn`. arm64 retains its `x8`/`x0` syscall path and vDSO signal return.

## Host-side checks completed

- Built the exec trampoline for x86_64 into `jniLibs/x86_64` and for arm64 into a temporary output directory. Both passed ELF machine, dependency, loader, and 16 KB alignment checks.
- Built the Claude launcher and freestanding seccomp shim for both targets. Both builds passed ELF checks; the shim has no dependencies and no Android RELR relocations.
- Built the glibc shim and all soname stubs for both targets. The x86_64 target uses `ld-linux-x86-64.so.2`; arm64 retains `ld-linux-aarch64.so.1`.
- Generated an x86_64 versioned `malloc` forwarder from a glibc-built sample object. The NDK linked it, and the forwarder verification found its target in API 33 libraries.
- Built node-pty, @parcel/watcher, SQLite, and ZeroMQ for both targets into temporary trees. The builds passed the ABI, dependency, loader, and 16 KB checks; the spdlog JavaScript replacement check also passed. Package version fixtures matched the builder's pinned addon versions. x86_64 was paired with the staged Node v24.18.0 runtime; the arm64 compile check used a minimal arm64 placeholder `libnode.so` carrying the same Node version marker, so it verifies target compilation and headers but not runtime compatibility.
- Ran the Android ELF and server-tree validator self-tests for both ABIs. They verify foreign loader rejection, wrong-machine rejection, and wrong-ABI rejection beneath an x64-specific path.
- Verified the current x86_64 main-stage JNI directory: 13 binaries passed. Verified `assets/usr`: 128 x86_64 ELF files passed; one Python relocatable object was skipped as expected.
- Python syntax compilation passed for `verify-android-elf.py`, `verify-server-tree.py`, and `gen-glibc-forwarders.py`.

The seccomp shim received a host-side Linux test attempt in an Alpine x86_64 container, but container execution was inconclusive: the restricted run could not install its seccomp filter, and the unconfined emulation run stalled. No behavior claim is made from that attempt. The shim still needs app-domain device testing, including repeated trapped calls and signal-handler interaction.

## Remaining native checks

Those addon builds were then applied to the staged x86_64 server tree. The packaged `pty.node` is the Bionic build (441,592 bytes, `libc.so` rather than `libc.so.6`). The x86_64 glibc stubs were regenerated against that tree so `watchdog.node` can resolve `__stack_chk_fail`, `exit`, `kill`, and `sleep`. Loading those addons from the selected Node runtime still has to be confirmed on a device.

No Android device is connected. Native loading, Node startup, PTY, filesystem watching, SQLite, and runtime seccomp behavior must be confirmed in VSCodroid on an x86_64 Android emulator and the target Googlebook before claiming device support.
