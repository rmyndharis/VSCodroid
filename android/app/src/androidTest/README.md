# Instrumented tests

**These do not run automatically. Nothing triggers them, and nothing can.**

That is a measured conclusion, not an omission, and it is written here because a
populated `androidTest/` directory otherwise reads as coverage.

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

Expect roughly three minutes. Two `SplashActivityTest` cases account for about
70% of it, sleeping 60 seconds each while first-run setup completes.

## What is here, and what it is worth

| File | Covers |
|---|---|
| `ServerHealthTest` | The server becomes reachable, answers its readiness probe, and survives activity recreation. None of this is reachable from a JVM test. |
| `MainActivityTest` | WebView and ExtraKeyRow initial state; the About dialog's trademark disclaimer, which is a stated legal requirement rather than cosmetic. |
| `SplashActivityTest` | First-run extraction, and that a later launch skips it. The slow ones. |
| `FileObserverTreeSemanticsTest` | The platform behaviour the SAF write-back rests on: that a watch covers a directory and not a tree, that the path an event reports is the bare entry name, and that inotify's directory flag survives the trip through FileObserver. Needs no app state at all, so unlike the rest of this directory it cannot be skipped into a false pass. |

## A green run is not necessarily a run

`ServerHealthTest` **silently does not run on a clean install.** Classes execute
alphabetically, so it goes before `SplashActivityTest` — and `SplashActivityTest`
is what triggers the extraction that puts `server-main.js` in `filesDir`. Its
`assumeTrue` then skips all three cases.

`connectedAndroidTest` installs over an existing app, which keeps `filesDir`, and
uninstalls afterwards. So the first run on a device someone else set up inherits
their assets and passes; the next run starts bare and skips. Measured both ways
— 1.6s / 0.9s / 5.3s in one run, and 0.008s / 0.003s / 0.002s in the next.

**Read the skip count, not just the failure count.** A skipped test reports zero
failures, and three tests that took two milliseconds did not measure anything.

An attempt to arrange the precondition inside `setUp` did not work and was not
shipped. Fixing it properly means either ordering the suite so extraction runs
first, or making the skip fail the run.

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
