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

`build.yml` compiles them (`assembleDebugAndroidTest`). That is not a
substitute for running them and is not offered as one; it catches only the
second way they were rotting. Until it was added, nothing compiled them either,
so they could drift out of the app's API and stay broken indefinitely.

## How to run them

Needs a connected arm64 device or emulator:

```bash
cd android
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest
```

Results land in `app/build/outputs/androidTest-results/connected/`. **Read that
XML rather than the exit code**: a run that fails before reaching the tests
writes no results at all, and its exit code is indistinguishable from a genuine
failure.

Read the times out of that XML rather than trusting a figure written here. The
one recorded run in this checkout (a Pixel 9 Pro XL AVD on API 36) reports
116.1 s of suite time for 22 tests, of which `firstRun_launchesWithoutCrash`
alone was 60.686 s. That test is gone: it slept a flat 60 seconds and asserted
nothing, so the only failure it could report was a throw out of `onCreate`,
which `firstRun_extractionSetsVersion` reports as well in 11.7 s, and that one
also catches a setup ending in `showSetupError()`.

That accounted for 21 tests and roughly 52 s of test time. This paragraph claimed
three minutes for a long time; the recorded run says otherwise, which is what
reading the XML is for.

**At HEAD there are 37 tests across eight classes**, counted from the sources rather
than from any run, with `grep -cE '^\s*@Test'` over this directory:
`KeyRowAccessibilityInstrumentedTest` arrived with the extra key row's accessibility
work and adds eleven, `SafWatchWiringTest` arrived with the per-directory watch work
and adds five, `ExtractionOnDeviceTest` arrived with the first-run setup work and
adds four, and the classes have moved since besides. No recorded run covers
this set, so there is no honest wall-clock figure to quote for it; the 52 s above
measured a different suite and is kept only as the reason not to say "three
minutes". Read the times out of your own run's XML.

## What is here, and what it is worth

| File | Covers |
|---|---|
| `ServerHealthTest` | The server becomes reachable, answers its readiness probe, and survives activity recreation. None of this is reachable from a JVM test. |
| `MainActivityTest` | WebView and ExtraKeyRow initial state; the About dialog's trademark disclaimer, which is a stated legal requirement rather than cosmetic. |
| `SplashActivityTest` | First-run extraction, and that a later launch skips it. The slow ones. |
| `FileObserverTreeSemanticsTest` | The platform behaviour the SAF write-back rests on: that a watch covers a directory and not a tree, that the path an event reports is the bare entry name, and that inotify's directory flag survives the trip through FileObserver. Needs no app state at all, so it has no precondition that a skip could hide. |
| `SafWatchWiringTest` | That those semantics are wired up: a save two directories down and a `.vscode/` settings file are both queued for write-back, a scratch file beside an ordinary one is not, deleting a watched directory releases its watch, and a skipped directory is never watched at all. The layer above `FileObserverTreeSemanticsTest`: that one proves the platform behaves as assumed, this one proves the assumption was used. |
| `ToolchainInsetsTest` | With edge-to-edge enforced, the Toolchains screen stays out of both system bars: toolbar below the status bar, grid above the navigation bar. The screen shipped drawing its title under the clock, so this is the regression the padding exists to prevent. |
| `ExtractionOnDeviceTest` | The parts of bundled-extension extraction a JVM cannot answer: that `AssetManager.list()` returns an empty array for a leaf, which is the basis on which `extractAssetDir` decides file-or-directory and which every unit test stubs; that `deleteRecursively()` succeeds on app-private storage, which the retry after a failed unpack depends on; and the abort-and-retry itself, driven by a real out-of-space condition. Redirects `getFilesDir()` through a `ContextWrapper` so the real AssetManager stays in play, so it needs no server tree and no first-run setup. |
| `KeyRowAccessibilityInstrumentedTest` | The extra key row and the trackpad as an accessibility service would find them: every key carries a content description, a latched modifier and the open alternates layer say so in their node state, a key with no alternates advertises no long click, each key clears 48dp on a mainstream phone, and the trackpad offers one action per arrow. It reads the `AccessibilityNodeInfo` and performs the actions it advertises by id rather than tapping. A `View` initialiser touches resources on its first line, so none of this is reachable from the JVM suite; the unit tests next door assert the wiring by reading the source instead. The view has to be inside a real window, because a detached one reports almost nothing. |

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
- These tests probe port 13337 by literal, while the app allocates through
  `PortFinder.getOrAllocatePort()` and moves off that port when something else
  holds it. The failure message says so, because on such a device the probe
  fails for a reason that has nothing to do with the server.

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
