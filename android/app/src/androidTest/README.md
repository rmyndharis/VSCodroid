# Instrumented tests

**These do not run automatically. Nothing triggers them, and no runner this
project has measured can.**

That is a measured conclusion, not an omission, and it is written here because a
populated `androidTest/` directory otherwise reads as coverage. The wording is
deliberately narrower than "nothing can": what was measured is two Linux runner
families, which is not every runner GitHub offers. See *The route nobody has
measured* below.

Because nothing schedules them, the cadence is a person's. `CONTRIBUTING.md`
names the moments; the one that belongs to this directory is **after touching
MainActivity, SplashActivity, NodeService, ProcessManager or FirstRunSetup**, and
before tagging a release.

## Why CI cannot run them

The app is `arm64-v8a` only (`abiFilters += "arm64-v8a"`), so the emulator has
to be arm64 as well — an x86_64 image cannot load the bundled `.so` files.
Measured on both runner families:

| Runner | Architecture | `/dev/kvm` |
|---|---|---|
| `ubuntu-latest` | x86_64 | present — but only accelerates x86_64 images |
| `ubuntu-24.04-arm` | aarch64 | absent, and no `vmx`/`svm` flag either |

So an arm64 image would run under pure software emulation, for a 340 MB APK
that boots a Node server. This is the same wall `scripts/device-test.sh`
documents for the same reason.

## The route nobody has measured

GitHub's `macos-14` / `macos-15` runners are Apple silicon, so an arm64 system
image there would run under HVF rather than software emulation. **Nobody has
tried it here**, and this section exists to price it honestly rather than to
recommend it, because "nothing can" was overstating a measurement of two Linux
runners.

What such a job would have to solve first, one item of which is checkable
without a runner and is true today:

- the assets cache key in `build.yml` is `assets-${{ hashFiles(...) }}` with no
  `runner.os` in it, so a macOS job would restore a tree built on Linux and save
  its own forward under the same key — verified by reading the workflow;
- the 874 MB asset tree would have to build on macOS, where `sed -i` is not the
  GNU one (`scripts/download-*.sh` already use `python3` for in-place edits for
  exactly this reason);
- installing a 340 MB APK over the emulator's adb, then two `SplashActivityTest`
  cases that wait on first-run extraction.

None of that says it will not work. It says the cost is a day, not an
afternoon, and that nobody should quote this section as evidence either way.

## What CI does instead

`build.yml` compiles them (`assembleDebugAndroidTest`). That is not a
substitute for running them and is not offered as one — it catches only the
second way they were rotting. Until it was added, nothing compiled them either,
so they could drift out of the app's API and stay broken indefinitely.

## How to run them

Needs a connected arm64 device or emulator:

```bash
cd android
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest
```

Results land in `app/build/outputs/androidTest-results/connected/`. **Read that
XML rather than the exit code** — a run that fails before reaching the tests
writes no results at all, and its exit code is indistinguishable from a genuine
failure.

Read the times out of that XML rather than trusting a figure written here. The
one recorded run in this checkout — a Pixel 9 Pro XL AVD on API 36 — reports
116.1 s of suite time for 22 tests, of which `firstRun_launchesWithoutCrash`
alone was 60.686 s. That test is gone: it slept a flat 60 seconds and asserted
nothing, so the only failure it could report was a throw out of `onCreate`,
which `firstRun_extractionSetsVersion` reports as well in 11.7 s — and that one
also catches a setup ending in `showSetupError()`.

That accounted for 21 tests and roughly 52 s of test time. This paragraph claimed
three minutes for a long time; the recorded run says otherwise, which is what
reading the XML is for.

**At HEAD there are 27 tests across seven classes**, counted from the sources rather
than from any run: `SafWatchWiringTest` arrived with the per-directory watch work
and adds five, `ExtractionOnDeviceTest` arrived with the first-run setup work and
adds four, and the classes have moved since besides. No recorded run covers
this set, so there is no honest wall-clock figure to quote for it — the 52 s above
measured a different suite and is kept only as the reason not to say "three
minutes". Read the times out of your own run's XML.

## What is here, and what it is worth

| File | Covers |
|---|---|
| `ServerHealthTest` | The server becomes reachable, answers its readiness probe, and survives activity recreation. None of this is reachable from a JVM test. |
| `MainActivityTest` | WebView and ExtraKeyRow initial state; the About dialog's trademark disclaimer, which is a stated legal requirement rather than cosmetic. |
| `SplashActivityTest` | First-run extraction, and that a later launch skips it. The slow ones. |
| `FileObserverTreeSemanticsTest` | The platform behaviour the SAF write-back rests on: that a watch covers a directory and not a tree, that the path an event reports is the bare entry name, and that inotify's directory flag survives the trip through FileObserver. Needs no app state at all, so it has no precondition that a skip could hide. |
| `SafWatchWiringTest` | That those semantics are wired up: a save two directories down and a `.vscode/` settings file are both queued for write-back, a scratch file beside an ordinary one is not, deleting a watched directory releases its watch, and a skipped directory is never watched at all. The layer above `FileObserverTreeSemanticsTest` — that one proves the platform behaves as assumed, this one proves the assumption was used. |
| `ToolchainInsetsTest` | With edge-to-edge enforced, the Toolchains screen stays out of both system bars: toolbar below the status bar, grid above the navigation bar. The screen shipped drawing its title under the clock, so this is the regression the padding exists to prevent. |
| `ExtractionOnDeviceTest` | The parts of bundled-extension extraction a JVM cannot answer: that `AssetManager.list()` returns an empty array for a leaf, which is the basis on which `extractAssetDir` decides file-or-directory and which every unit test stubs; that `deleteRecursively()` succeeds on app-private storage, which the retry after a failed unpack depends on; and the abort-and-retry itself, driven by a real out-of-space condition. Redirects `getFilesDir()` through a `ContextWrapper` so the real AssetManager stays in play, so it needs no server tree and no first-run setup. |

## A green run is not necessarily a run

`ServerHealthTest` used to **silently not run on a clean install.** Classes
execute alphabetically, so it goes before `SplashActivityTest` — and
`SplashActivityTest` is what triggers the extraction that puts `server-main.js`
in `filesDir`. Its `assumeTrue` then skipped all three cases.

`connectedAndroidTest` installs over an existing app, which keeps `filesDir`, and
uninstalls afterwards. So the first run on a device someone else set up inherits
their assets and passes; the next run starts bare. Measured both ways — 1.6s /
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
