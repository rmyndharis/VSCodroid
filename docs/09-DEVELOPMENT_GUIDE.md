# Development Guide

**Project**: VSCodroid
**Version**: 1.0-draft
**Date**: 2026-02-10

> **Superseded. Build and contribution instructions live in [`CONTRIBUTING.md`](../CONTRIBUTING.md).**

This document was written on 2026-02-10 and described the build the project expected to
have: a code-server fork checked out as a git submodule, `yarn gulp vscode-web-min` run
against it, patches applied as inline Python replacements inside
`scripts/download-vscode-server.sh`, feature branches merged into a `develop` branch. None
of that is how this repository works. There is no submodule and no `.gitmodules`, no
`develop` branch, no `scripts/download-vscode-server.sh`, no `server/lib/`, and no script
under `scripts/` or workflow under `.github/` invokes yarn. Older planning documents still
name it, and they are stale in the same way this one was. The server is built from MIT
Code - OSS source by `.github/workflows/build-vscode-oss.yml` with unified diffs from
`patches/`, and app builds fetch the result with `scripts/fetch-vscode-oss.sh`.

The instructions have been removed rather than rewritten. The rest of the planning suite is
kept as written, because a plan, an ADR or a risk score records what was thought at the time
and editing it destroys the record. This document was procedure rather than reasoning, and a
procedure whose commands have stopped working leaves no record worth preserving, only traps
for whoever copies one: a script that does not exist, a gulp task that was never the right
one, a launch command that starts `MainActivity` directly and so skips the first-run
extraction two lines after telling the reader to clear app data.

`CONTRIBUTING.md` is the prose kept current alongside the code, and it has a guard this file
could never have: `scripts/check-build-steps.py` fails the build when a shell script CI runs
is not mentioned there. That guard covers which scripts are named, not whether every command
in the file still runs, but it is what stops the documentation losing a build step outright,
which is what happened here.

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
