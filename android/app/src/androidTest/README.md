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
cheap: `build.yml`'s asset cache key is `assets-${{ hashFiles(...) }}` with no
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

Results land in `app/build/outputs/androidTest-results/connected/`. **Read that
XML rather than the exit code**: a run that fails before reaching the tests
writes no results at all, and its exit code is indistinguishable from a genuine
failure. It is also the only place the skip count appears, and a skipped test
prints nothing in Gradle's console output.

**At HEAD there are 52 tests across eleven classes.** That figure is not kept by
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
| `ServerHealthTest` | The server becomes reachable, answers its readiness probe, and survives activity recreation. None of this is reachable from a JVM test. |
| `MainActivityTest` | WebView and ExtraKeyRow initial state; the About dialog's trademark disclaimer, which is a stated legal requirement rather than cosmetic. |
| `SplashActivityTest` | First-run extraction, and that a later launch skips it. The slow ones. |
| `FileObserverTreeSemanticsTest` | The platform behaviour the SAF write-back rests on: that a watch covers a directory and not a tree, that the path an event reports is the bare entry name, and that inotify's directory flag survives the trip through FileObserver. Needs no app state at all, so it has no precondition that a skip could hide. |
| `SafWatchWiringTest` | That those semantics are wired up: a save two directories down and a `.vscode/` settings file are both queued for write-back, a scratch file beside an ordinary one is not, deleting a watched directory releases its watch, and a skipped directory is never watched at all. The layer above `FileObserverTreeSemanticsTest`: that one proves the platform behaves as assumed, this one proves the assumption was used. |
| `ToolchainInsetsTest` | With edge-to-edge enforced, the Toolchains screen stays out of both system bars: toolbar below the status bar, grid above the navigation bar. The screen shipped drawing its title under the clock, so this is the regression the padding exists to prevent. |
| `ExecTrampolineOnDeviceTest` | The one claim no JVM test can settle: that the trampoline starts a program the app is otherwise forbidden to run. SELinux denies `execute_no_trans` on `app_data_file`, so nothing under `filesDir` can be execve'd whatever its mode, and only a test running as the app itself can ask that -- `adb shell run-as` runs in a domain that is allowed to execute files this one may not, so it reports success for a binary that fails on the device. The control is asserted first and is not optional: if a direct execve of the copied payload SUCCEEDS here, nothing was being denied on this device, and the case that runs it through the trampoline would pass for a reason that has nothing to do with the trampoline. The whole toolchain design rests on this. |
| `ExtractionOnDeviceTest` | The parts of bundled-extension extraction a JVM cannot answer: that `AssetManager.list()` returns an empty array for a leaf, which is the basis on which `extractAssetDir` decides file-or-directory and which every unit test stubs; that `deleteRecursively()` succeeds on app-private storage, which the retry after a failed unpack depends on; and the abort-and-retry itself, driven by a real out-of-space condition. Redirects `getFilesDir()` through a `ContextWrapper` so the real AssetManager stays in play, so it needs no server tree and no first-run setup. |
| `TextEntryInstrumentedTest` | That the row actually types what it says: every character it can produce, taken from the row itself rather than from a list here, resolves to key presses that enter it, a shifted character is pressed with Shift held, and each press carries the layout it was resolved from and a current timestamp. `KeyCharacterMap` and `KeyEvent` are android.jar stubs that throw off a device, so every JVM case injects a fake and the real lookup is only exercised here. The failure that leaves has shipped once: `{` and `(` inserting nothing at all, with every JVM case green. |
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
