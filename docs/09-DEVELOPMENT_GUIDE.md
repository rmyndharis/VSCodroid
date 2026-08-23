# Development Guide

**Project**: VSCodroid
**Version**: 1.0-draft
**Date**: 2026-02-10

> **Build and contribution instructions live in [`CONTRIBUTING.md`](../CONTRIBUTING.md).** This
> file is a pointer at where each topic is covered; it carries no instructions of its own.

This document was written on 2026-02-10 and described the build the project expected to
have: a code-server fork checked out as a git submodule, `yarn gulp vscode-web-min` run
against it, patches applied as inline Python replacements inside
`scripts/download-vscode-server.sh`, feature branches merged into a `develop` branch. None
of that is how this repository works. There is no submodule and no `.gitmodules`, no
`develop` branch, no `scripts/download-vscode-server.sh`, no `server/lib/`, and no script
under `scripts/` or workflow under `.github/` invokes yarn. The server is built from MIT
Code - OSS source by `.github/workflows/build-vscode-oss.yml` with the unified diffs in
`patches/` applied by `git apply` (fifteen today, and the script globs the directory rather
than carrying a list), and app builds fetch the result with `scripts/fetch-vscode-oss.sh`.
The week-by-week schedule in
[`12-IMPLEMENTATION_PLAN.md`](./12-IMPLEMENTATION_PLAN.md) still describes that older build,
under a banner saying it records the plan of the day it was written.

No procedure is restated here. Elsewhere the planning suite is kept against the code:
01 to 08, 10 and 11 describe what the build does, and where one of them opens with a
banner it either names the code as what settles a disagreement (03, 04, 08) or fixes
the product scope to the date in its own header (01). Only 12 and §2 of 10, a schedule
and a pipeline sketch, are kept as dated records, because neither has a present-tense
reading. What stood here was procedure rather than reasoning, and a procedure whose
commands do not run leaves no record worth preserving, only traps for whoever copies
one: a script that does not exist, a gulp task that was never the right one, and a
launch command that starts `MainActivity` directly, and so skips the first-run
extraction, two lines from the one telling the reader to clear app data.

`CONTRIBUTING.md` is the prose kept current alongside the code, and it has a guard this file
could never have: `scripts/check-build-steps.py`, run by `lint.yml` and `release.yml`, fails
when a shell script a workflow invokes is not mentioned there. That guard covers which scripts
are named, not whether every command in the file still runs, but it is what stops the
documentation losing a build step outright.

| Looking for | Read instead |
| ------------------------------------------- | -------------------------------------------------------------------------------------- |
| Prerequisites, SDK and NDK versions | `CONTRIBUTING.md`, Development Setup |
| Clone, asset downloads, the order they run in | `CONTRIBUTING.md`, Preparing Assets |
| Debug, release and AAB builds, version bump | `CONTRIBUTING.md`, Building |
| Deploying, on-device debugging, health probe | `CONTRIBUTING.md`, Testing on Device |
| Writing and fingerprinting a patch | `CONTRIBUTING.md`, How to Add a New Patch, and `patches/` |
| Bundling a new tool | `CONTRIBUTING.md`, How to Add a New Bundled Tool |
| Kotlin, JavaScript and shell conventions | `CONTRIBUTING.md`, Code Style |
| Branching, commits, review, PR checklist | `CONTRIBUTING.md`, Pull Request Process |
| Repository layout | `CONTRIBUTING.md`, Project Structure |
| Manual on-device pass | [`DEVICE_TEST_CHECKLIST.md`](./DEVICE_TEST_CHECKLIST.md), plus `scripts/device-test.sh` |
| Release strategy and rollout | [`10-RELEASE_PLAN.md`](./10-RELEASE_PLAN.md) |
| What the code does | The code. Start at `scripts/build-all.sh` and `android/app/src/main/kotlin/com/vscodroid/` |
