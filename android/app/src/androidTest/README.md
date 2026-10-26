# Instrumented tests

**These do not run automatically. Nothing triggers them, and no runner this
project has measured can.**

That is a measured conclusion, not an omission, and it is written here because a
populated `androidTest/` directory otherwise reads as coverage.

The wording was once narrower than "nothing can", because only two Linux runner
families had been measured and that is not every runner GitHub offers. The third
has since been measured too, and it fails for the same underlying reason. See
*The route that has now been measured* below.

Because nothing schedules them, the cadence is a person's. `CONTRIBUTING.md`
names the moments; the one that belongs to this directory is **after touching
MainActivity, SplashActivity, NodeService, ProcessManager or FirstRunSetup**, and
before tagging a release.

## Why CI cannot run them

The app is `arm64-v8a` only (`abiFilters += "arm64-v8a"`), so the emulator has
to be arm64 as well: an x86_64 image cannot load the bundled `.so` files.
Measured on both runner families:

| Runner | Architecture | `/dev/kvm` |
|---|---|---|
| `ubuntu-latest` | x86_64 | present, but only accelerates x86_64 images |
| `ubuntu-24.04-arm` | aarch64 | absent, and no `vmx`/`svm` flag either |

So an arm64 image would run under pure software emulation, for a 340 MB APK
that boots a Node server. This is the same wall `scripts/device-test.sh`
documents for the same reason.

## The route that has now been measured

GitHub's `macos-14` / `macos-15` runners are Apple silicon, so an arm64 system
image there would in principle run under HVF rather than software emulation.
This section used to say nobody had tried it. Somebody has, on 2026-08-16, and
the answer is no.

| Runner | Architecture | Hardware acceleration |
|---|---|---|
| `macos-15` | arm64, reported as `Apple M1 (Virtual)` | `kern.hv_support` absent; `HVF error: HV_UNSUPPORTED` |

The runner is itself a virtual machine, and nested virtualisation is not exposed
to it, so QEMU cannot initialise HVF at all:

```
qemu-system-aarch64-headless: failed to initialize HVF: Invalid argument
```

The emulator never reaches adb, and the job ends in `Timeout waiting for
emulator to boot`. Measured on API 34 and API 36, identically, from a job that
built and installed nothing so that a dead route cost nothing to find.

So it is the same wall as `ubuntu-24.04-arm`, under a different name: no
accessible hardware virtualisation for an arm64 guest. Three runner images have now been
measured, one per family GitHub offers, and the sentence at the top of this file
no longer rests on a gap. It is images rather than every image: nothing here has
tried macos-14, and there is no reason to expect it to differ.

What that leaves, none of it tried here:

- self-hosted hardware, where the virtualisation is real;
- a physical device attached to a runner;
- nothing else. Software emulation of a 340 MB APK that boots a Node server was
  never a serious option, and it is the only thing these runners can offer.

One item from the old estimate is worth keeping, because it stays true and is
cheap: `build.yml`'s asset cache key is `assets-${{ env.NDK_VERSION }}-${{ hashFiles(...) }}` with no
`runner.os` in it, so any future non-Linux job would restore a tree built on
Linux and save its own forward under the same key. Verified by reading the
workflow. Another item from that estimate was wrong and is worth recording as
wrong: it assumed such a job would have to build the 874 MB asset tree on macOS,
when the APKs can be built on Linux and only installed on the other runner.

## What CI does instead

`build.yml` compiles them (`assembleDebugAndroidTest`), and `release.yml` runs
the same task before it signs anything, because a tag may name a commit that
reached neither of `build.yml`'s triggers. That is not a substitute for running
them and is not offered as one; it catches only the second way they were
rotting. Until it was added, nothing compiled them either, so they could drift
out of the app's API and stay broken indefinitely.

`lint.yml` and `release.yml` also run
`scripts/check-instrumented-inventory.py`, which is what keeps this file
truthful about the suite: the count below and the table of what each class
covers are the only account of coverage anywhere, and both had gone stale
against a directory nobody executes.

## How to run them

Needs a connected arm64 device or emulator:

```bash
cd android
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest
```

`scripts/device-test.sh --instrumented` is the same run with the preconditions
checked first, and it writes what happened to
`app/build/reports/device-run.txt`. Prefer it: the preconditions it checks both
present as a timeout rather than as an error.

The storage-pressure extraction case is skipped by default. The runner explicitly disables it. To run that case separately, provide `-Pandroid.testInstrumentationRunnerArguments.isolatedStoragePressureRoot=/absolute/path` to Gradle, selecting only `com.vscodroid.setup.ExtractionOnDeviceTest#aFetchedExtensionWhoseUnpackRanOutOfSpaceIsRetriedNotSkipped` with the standard `android.testInstrumentationRunnerArguments.class` property. The path must be app-writable and belong to a disposable filesystem separate from app data, with total capacity at most 128 MiB. A directory on the shared data partition is refused. Prepare and remove that filesystem outside this test; use a disposable emulator, never a personal device. The test neither mounts filesystems nor authorizes filling shared storage.

Allocation stops at 64 MiB or a 10-second deadline checked between 64 KiB writes. A filesystem with more than 68 MiB free is refused before allocating filler. Missing prerequisites or failure to reach pressure within those bounds is a skip, visible in the result XML. The real failure-and-retry assertions run only after pressure is created. Cleanup removes the filler in `finally` and the fixture tree in `@After`; disposal of the isolated filesystem remains necessary after an interrupted run.

Results land in `app/build/outputs/androidTest-results/connected/`. **Read that
XML rather than the exit code**: a run that fails before reaching the tests
writes no results at all, and its exit code is indistinguishable from a genuine
failure. It is also the only place the skip count appears, and a skipped test
prints nothing in Gradle's console output.

**At HEAD there are 109 tests across twenty classes.** That figure is not kept by
hand: `scripts/check-instrumented-inventory.py` counts the sources with
`grep -cE '^\s*@Test'` and fails the build when this file disagrees with them,
because it had said 22 and then 37 while the suite was neither, and a stale
count in a document whose subject is coverage is worse than no count.

The recorded run in this checkout is a Pixel 7 Pro AVD on API 33, 2026-08-23:
50 tests, no failures, no skips, **66.8 s** of suite time. Where it goes is
lopsided and worth knowing before you wait on it: `SplashActivityTest` 21.4 s
and `ServerHealthTest` 14.2 s are two thirds of it, both of them waiting on real
extraction and a real server; `TextEntryInstrumentedTest` and
`GestureTrackpadTouchInstrumentedTest` together cost 0.03 s. Read your own run's
XML rather than trusting this paragraph, which is what it is here to encourage.

## What is here, and what it is worth

| File | Covers |
|---|---|
| `BoundedStoragePressureTest` | Pure allocation-policy controls: shared or large volumes perform no writes, unchanged capacity cannot exceed the byte cap, the deadline stops allocation, and bounded simulated pressure can succeed. No real disk pressure is created. |
| `KeyRowDensityInstrumentedTest` | Rebuilds a real key row after a simulated density change, checks the new pager height and inset cost, and retains the selected page and latched Ctrl. Captures screenshots before and after the change. |
| `EditorShutdownOnDeviceTest` | Extracts an isolated copy of the packaged server and tools, starts the real native bootstrap without service retries, and verifies from the app's SELinux domain that Stop ends an editor surviving its bootstrap. Checks the real PID environment and socket, repeated Stop, a fresh run identity and cooperative shutdown. Saves phase metadata and screenshots in a separate cache directory; it changes no existing workspace or setup preferences and has no skip path. |
| `PipGuidanceOnDeviceTest` | Runs the generated interactive pip helpers and noninteractive `BASH_ENV` through the APK's `libbash.so` under the app uid. A substituted Python command produces pip error records without network access or package installation. Build failures retain stderr and exit status, receive accurate toolchain advice once per shell, and remove temporary records; missing packages receive no build advice and successful calls stay quiet. An older interactive helper is upgraded while preserving user content and avoiding repeated appends. This does not establish compatibility of third-party packages or wheels. |
| `ProfileExtensionPreservationOnDeviceTest` | Runs bundled-extension extraction and manifest reconciliation with real APK assets in isolated app-private storage. Named profiles retain an older copy of an extension the user installed, bytes and manifest, while the default profile advances, including absolute-path references and malformed or unreadable manifests. A profile's entry for one of the app's own extensions follows the update instead (also when it names the directory by path alone, and without listing the extension twice), and the older copy is removed. Unreferenced copies are removed, and removing a profile reference allows reclamation after reopening the setup context. A fetched extension's new version reaches the default profile even when only another profile lists a gallery copy of it. No first-run setup or running editor is required; these cases do not establish workbench activation. |
| `ServerHealthTest` | The server becomes reachable, answers its readiness probe, and survives activity recreation. None of this is reachable from a JVM test. |
| `MainActivityTest` | WebView and ExtraKeyRow initial state; the About dialog's trademark disclaimer, which is a stated legal requirement rather than cosmetic. |
| `SplashActivityTest` | First-run extraction, and that a later launch skips it. The slow ones. |
| `FileObserverTreeSemanticsTest` | The platform behaviour the SAF write-back rests on: that a watch covers a directory and not a tree, that the path an event reports is the bare entry name, and that inotify's directory flag survives the trip through FileObserver. Needs no app state at all, so it has no precondition that a skip could hide. |
| `SafWatchWiringTest` | That those semantics are wired up: a save two directories down and a `.vscode/` settings file are both queued for write-back, a persistent `.tmp` file is queued while an internal staging file ending in `.vscodroid-partial` is not, deleting a watched directory releases its watch, and a skipped directory is never watched at all. The layer above `FileObserverTreeSemanticsTest`: that one proves the platform behaves as assumed, this one proves the assumption was used. |
| `SafDataPreservationInstrumentedTest` | A file-backed DocumentsProvider in a separate process receives watched saves and file creations, returning the same bytes on reopening. A stable delivered save follows an external deletion through two reopens without recreating the document; an undelivered edit with the same length and timestamp survives that deletion. A failed upload-journal write must refuse the operation before creating or opening a provider document for writing, retain the local bytes and any existing device copy, and report the refusal. A blocked provider read overlaps a local edit with unchanged size and timestamp; both versions must survive the download and reopening. Directory deletes retain provider-only or changed children and refuse an unavailable child listing, with a known-content recursive delete as the control and a real watcher case for a provider-only child. The cleanup cases retain unrecorded workspace bytes and reclaim a fully vouched mirror. The fixture grants and revokes temporary tree access; these cases do not establish revocation of a persisted URI permission. Fixtures use only disposable private directories; a test-only receiver guarded by the shell's DUMP permission grants and revokes access to their fixed tree for the debug application. |
| `ToolchainInsetsTest` | With edge-to-edge enforced, the Toolchains screen stays out of both system bars: toolbar below the status bar, grid above the navigation bar. The screen shipped drawing its title under the clock, so this is the regression the padding exists to prevent. |
| `ExecTrampolineOnDeviceTest` | The one claim no JVM test can settle: that the trampoline starts a program the app is otherwise forbidden to run. SELinux denies `execute_no_trans` on `app_data_file`, so nothing under `filesDir` can be execve'd whatever its mode, and only a test running as the app itself can ask that -- `adb shell run-as` runs in a domain that is allowed to execute files this one may not, so it reports success for a binary that fails on the device. The control is asserted first and is not optional: if a direct execve of the copied payload SUCCEEDS here, nothing was being denied on this device, and the case that runs it through the trampoline would pass for a reason that has nothing to do with the trampoline. The whole toolchain design rests on this. |
| `ExecPreloadOnDeviceTest` | The same question asked of the exec interceptor: that a shell with `libtermux-exec.so` preloaded starts a program under `filesDir` that the app itself may not execve, by its absolute path and as `./make` from a directory the kernel reports as `/data/data/<package>`, and that the patches carried on that library hold on this device: a system binary keeps the preload for what it starts, a `#!/bin/sh` script finds the system shell, a symlink onto a system binary is started as itself rather than through the linker, a linker started by hand names its payload in `TERMUX_EXEC__PROC_SELF_EXE`, a process that lost the prefix still reaches `/system/bin/sh`, and an exec with no argv[0] goes to the kernel unchanged and is refused as it is without the preload. No shell can pass an empty argv, so that case makes the call from Java under `app_process`, after a control run of the same route with an ordinary argv. The library is copied out of the APK's assets at mode 0600, which is the mode the extraction leaves the real one in, so the suite needs no first-run state. The same direct-execve control is asserted first, for the same reason. One more case documents the failure shape a repair of the value has to respect: a preload naming a missing file kills the shell itself with `CANNOT LINK EXECUTABLE`, which is why the setting is only ever written once the file is there. Its control shares the trampoline suite's precondition: the instrumentation process must have no LD_PRELOAD of its own, or both controls fail and every subject case beside them passes for a reason that has nothing to do with the preload. |
| `ExtractionOnDeviceTest` | Checks the real asset leaf-listing contract, deletion of a partial extraction, and extraction of the bundled tree in app-private storage. The failure-and-retry case requires an explicitly selected disposable small filesystem and skips when its bounded pressure prerequisites are absent. |
| `TextEntryInstrumentedTest` | That the row actually types what it says: every character it can produce, taken from the row itself rather than from a list here, resolves to key presses that enter it, a shifted character is pressed with Shift held, and each press carries the layout it was resolved from and a current timestamp. Each navigation key is one press, down then up, with its key code, its scan code and a latched Shift, and the Shift then comes up. `KeyCharacterMap` and `KeyEvent` are android.jar stubs that throw off a device, so every JVM case injects a fake and the real lookup is only exercised here. The failure that leaves has shipped once: `{` and `(` inserting nothing at all, with every JVM case green. |
| `WebViewVisualArrowInstrumentedTest` | Compares native and guarded caret movement through the production WebView configuration and key injector on RTL input and textarea fields. Requires a fresh `visualCheckpointToken`, a selected `visualArrowCase`, and a viewed screenshot before each checkpoint acknowledgement; it is skipped in unattended runs. |
| `ExtraKeyButtonTouchInstrumentedTest` | What a finger does on one key: a finger that rests on a key without alternates past the long-press timeout and then swipes away presses nothing, a long hold presses once when the finger lifts, two quick taps are two presses, a key with alternates still opens them on a hold without pressing, a modifier held past the timeout latches before the finger lifts, and a swipe that starts slowly on a modifier leaves it latched or not as it was. With the row in a window, a second finger swiping the pager leaves a modifier held under the first latched while the same swipe by the finger on it puts it back, a slide of a held finger after the row stood down for the keyboard leaves the modifier as the stand-down cleared it, and a resize that repacks it under a held modifier leaves that modifier as it was before the touch on the row and on the page alike, and one under a trackpad drag spends the latch as any drag end does. The key decides through a `GestureDetector`, whose long press is a timer on the main Looper, so the JVM suite can only read how the key configures it. Before this was fixed a slow swipe across the row sent the key it started on and then turned the page, and the second of two taps about 200 ms apart was lost. The four cases with the row in a window need a WebView to stand in for the page, which records what the row pushes and answers its poll. |
| `GestureTrackpadTouchInstrumentedTest` | What a second finger does to a drag. The pad reads deltas off pointer index 0, and once the first finger lifts that index becomes the finger left behind, so one frame reported the gap between two fingers as a single delta and the accumulator paid it out as a burst of arrows. Real multi-pointer `MotionEvent`s against a `View` whose initialiser reaches colours, strings and display metrics, so none of it is reachable from a JVM test. |
| `KeyRowAccessibilityInstrumentedTest` | The extra key row and the trackpad as an accessibility service would find them: activating a key delivers its press and activating a modifier flips its state, a latched modifier says so on the node that speaks it, a plain key publishes none, a key with no alternates advertises no long click, a key and an alternate each call themselves a button, every key clears 48dp at each width the row is paged for, a resize repacks the row without spending a latched modifier or changing its height, the gap between keys is still drawn, and the trackpad offers one action per arrow and ends the drag when one is performed. It reads the `AccessibilityNodeInfo` and performs the actions it advertises by id rather than tapping. A `View` initialiser touches resources on its first line, so none of this is reachable from the JVM suite; the unit tests next door assert the wiring by reading the source instead. The view has to be inside a real window, because a detached one reports almost nothing. |

## A green run is not necessarily a run

`ServerHealthTest` used to **silently not run on a clean install.** Classes
execute alphabetically, so it goes before `SplashActivityTest`, and
`SplashActivityTest` is what triggers the extraction that puts `server-main.js`
in `filesDir`. Its `assumeTrue` then skipped all three cases.

`connectedAndroidTest` installs over an existing app, which keeps `filesDir`, and
uninstalls afterwards. So the first run on a device someone else set up inherits
their assets and passes; the next run starts bare. Measured both ways: 1.6s /
0.9s / 5.3s in one run, and 0.008s / 0.003s / 0.002s in the next.

The instruction here used to be *read the skip count, not just the failure
count*. That is an instruction to a human, and it is the kind that holds until
the afternoon someone is in a hurry. The precondition is now an assertion: an
install without extracted assets produces three red tests naming what to do
about it, instead of three green ones that measured nothing.

Two consequences worth knowing before the first red run:

- On a freshly wiped device the fix is to **launch the app once** and re-run.
  Arranging that inside `setUp` was attempted, did not work, and was not shipped.
- These tests probe whichever port `PortFinder.getOrAllocatePort()` recorded,
  read back through `ServerReadyHelper.waitForServer`, so a device where 13337
  is held sends them to the port the app actually moved to. They probed 13337
  by literal once, and on such a device went red for a reason that had nothing
  to do with the server. The one case left is a scan range full end to end,
  where the app falls back to an ephemeral port it deliberately does not
  record; the failure message names it.

## Two things this directory has already taught us

Both were found the first time anyone ran it, which is the point.

`server_survivesActivityRecreation` **had never been able to pass.** It launched
`SplashActivity`, which carries `android:noHistory` and finishes itself, so
`recreate()` was called on an activity that was already gone and
`ActivityScenario` threw. The test guarding the one invariant nothing else
guards could not perform the action in its own name.

`IntentHandlingTest` was deleted rather than fixed. Its three cases launched
MainActivity with **explicit** intents, which bypass intent filters entirely, so
they never touched the surface they were named for; two of them asserted things
that were true regardless of what the code did. The surface itself was removed
separately, as a capability that had never been implemented.
