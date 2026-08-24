# Risk Assessment & Mitigation Plan

**Project**: VSCodroid
**Version**: 1.0-draft
**Date**: 2026-02-10

> Scores are a judgement, not a measurement. Where a risk touches something the build actually
> does, the code is what settles it: `CONTRIBUTING.md` and the sources it names.

---

## 1. Risk Scoring

**Probability**: 1 (Rare) → 5 (Almost Certain)
**Impact**: 1 (Negligible) → 5 (Critical)
**Risk Score**: Probability × Impact

| Score Range | Level | Action |
|-------------|-------|--------|
| 1-5 | Low | Accept and monitor |
| 6-12 | Medium | Mitigate with plan |
| 13-19 | High | Active mitigation required |
| 20-25 | Critical | Must resolve before proceeding |

---

## 2. Risk Register

### 2.1 Technical Risks

| ID | Risk | Prob | Impact | Score | Category |
|----|------|------|--------|-------|----------|
| T01 | **Phantom process killing** breaks terminal/extensions | 4 | 5 | **20** | Critical |
| T02 | **Node.js ARM64 build fails** or is unstable | 3 | 5 | **15** | High |
| T03 | **VS Code monthly updates break patches** | 4 | 4 | **16** | High |
| T04 | **WebView version fragmentation** causes UI issues | 3 | 3 | **9** | Medium |
| T05 | **Memory pressure** causes OOM on 4GB devices | 3 | 4 | **12** | Medium |
| T06 | **node-pty doesn't work** on Android (PTY allocation) | 2 | 5 | **10** | Medium |
| T07 | **16KB page alignment** issues on Android 16 | 3 | 3 | **9** | Medium |
| T08 | **Extension Host worker_thread** patch too complex | 3 | 4 | **12** | Medium |
| T09 | **On-demand asset pack download** requires internet for toolchains | 2 | 2 | **4** | Low |
| T10 | **Python packaging** fails or stdlib incomplete | 2 | 2 | **4** | Low |

### 2.2 Platform Risks

| ID | Risk | Prob | Impact | Score | Category |
|----|------|------|--------|-------|----------|
| P01 | **Play Store rejects** app (binary execution policy) | 3 | 5 | **15** | High |
| P02 | **Android future version** blocks binary execution entirely | 2 | 5 | **10** | Medium |
| P03 | **Google restricts .so trick** in future Android/Play Store | 2 | 5 | **10** | Medium |
| P04 | **Foreground Service restrictions** tighten further | 3 | 4 | **12** | Medium |
| P05 | **Scoped Storage** limits file access further | 2 | 3 | **6** | Medium |

### 2.3 Legal Risks

| ID | Risk | Prob | Impact | Score | Category |
|----|------|------|--------|-------|----------|
| L01 | **Microsoft trademark** claim against "VSCodroid" name | 2 | 4 | **8** | Medium |
| L02 | **Open VSX registry** goes down or changes API | 1 | 3 | **3** | Low |
| L03 | **License compliance** issue with bundled software | 1 | 4 | **4** | Low |

### 2.4 Project Risks

| ID | Risk | Prob | Impact | Score | Category |
|----|------|------|--------|-------|----------|
| R01 | **Scope creep** (too many features before stable core) | 3 | 4 | **12** | Medium |
| R02 | **Upstream source layout** changes force patch rewrites | 2 | 4 | **8** | Medium |
| R03 | **Solo developer burnout** (if single contributor) | 3 | 4 | **12** | Medium |
| R04 | **Testing on real devices** (limited device access) | 3 | 3 | **9** | Medium |

---

## 3. Detailed Mitigation Plans

### T01: Phantom Process Killing (Score: 20, Critical)

**Risk**: Android 12+ enforces a 32-process system-wide phantom process limit. Exceeding this causes SIGKILL to processes. VS Code architecture naturally spawns many child processes (extension host, terminals, language servers).

**Mitigation Strategy** (multi-layered):

| Layer | Action | Impact |
|-------|--------|--------|
| 1. Extension Host | Runs as a worker_thread, not a `child_process.fork()` (`patches/0004`) | Costs no phantom process |
| 2. Terminal | ptyHost as worker_thread; each terminal spawns bash directly on a real PTY | Terminal host costs no phantom process |
| 3. Language Servers | Lazy start. Five minutes without a tick of CPU marks a server idle (`IDLE_THRESHOLD_MS`) in the process tree | Nothing is killed by the clock |
| 4. Reclaim | None. Killing an idle language server was measured to bring it back under a new pid within a second, so nothing signals one; the process tree marks idle servers and names disabling the owning extension as what frees a slot | No restart churn on a device short of memory |
| 5. Foreground Service | specialUse type, protects main process | Main app not killed |
| 6. Monitoring | Count phantoms, warn user if approaching limit | User can close terminals/extensions |
| 7. User guidance | In-app tips: "Close unused terminals to save resources" | User awareness |

**Measured**: 5 phantom processes with the app open and nothing happening, on API 33 and API 37
alike (`IDLE_BASELINE` in `process-monitor.js`, which names them: the bootstrap, the editor server,
the file watcher, the agent host, and the chat agent's model backend). The thresholds above are set
against that number, not against a target.

There is **no cap on concurrent language servers**. A "hard cap: max 2-3" was listed here as
mitigation layer 4 until 2026-08-23; nothing implemented it, and layer 4 now describes the reclaim
that does exist.

**Contingency**: If worker_thread patch proves too complex, fall back to child_process with aggressive process management (immediate kill of idle processes).

---

### T02: Node.js ARM64 Build (Score: 15, High)

**Risk**: The bundled Node.js has to be ARM64, Bionic-linked, 16KB page aligned, and the same Node line the VS Code server's native modules are built against. A binary that misses any of these fails late and obscurely.

**Mitigation**:
1. **Take Termux's build**: `scripts/download-node.sh` fetches the `nodejs-lts` package and installs `bin/node` as `jniLibs/arm64-v8a/libnode.so`. Termux has been building Node.js for ARM64 Android for years.
2. **Version is not a preference**: `remote/.npmrc` `target` at the pinned VS Code tag names the Node the server ships against, so the Termux package has to match it. Bumping one means checking the other.
3. **Gate every downloaded ELF**: `scripts/verify-android-elf.py` checks 16KB alignment and refuses any DT_NEEDED that neither Bionic provides nor we bundle. It runs on everything a download script places, and the `verifyBundledBinaries` Gradle task sweeps all of `jniLibs/` again at packaging time. The server tree's `.node` addons are not in its population: `verify-server-tree.py` reads their architecture and `gen-glibc-forwarders.py --scan` their `DT_NEEDED`, and nothing reads their alignment. See `docs/04-TECHNICAL_SPEC.md` section 1.2.
4. **Early validation**: M0 milestone is specifically designed to validate this risk first.

**Contingency**: Stay on the last known-good Termux package version until the newer one is understood.

---

### T03: VS Code Monthly Update Breaks Patches (Score: 16, High)

**Risk**: VS Code releases monthly. Each release may break our patches (conflicts, API changes, architectural changes).

**Mitigation**:
1. **Pin initial version**: Don't chase latest. Pick a VS Code version and stabilize.
2. **Monthly rebase cadence**: Allocate 5-10 days per month for patch maintenance.
3. **Automated patch testing**: CI job that attempts to apply patches to latest VS Code and reports failures.
4. **Modular patches**: Keep patches small and focused. Each patch touches minimal files.
5. **Upstream monitoring**: Watch VS Code release notes for changes to our patched areas.

**Contingency**: Skip a VS Code release if patches are too broken. Two-month gap is acceptable.

---

### P01: Play Store Rejection (Score: 15, High)

**Risk**: Google may reject the app because it executes bundled binaries (even though bundled as .so).

**Mitigation**:
1. **Precedent**: Termux and UserLAnd are on Play Store using same .so technique.
2. **All binaries via Play Store**: core binaries bundled as .so in the base APK, and the Ruby and Java 17 toolchains delivered as on-demand asset packs the user selects in the Language Picker, with Play handling the download. On a Play install nothing is fetched from a third party, which is the compliance story. A sideloaded install has no Play Asset Delivery, so it downloads the same toolchain ZIPs over HTTPS from this project's GitHub Releases, checked against a published sha256 manifest.
3. **Prepare justification**: Document for Play Store review as "Educational developer tool, all binaries bundled at build time, no remote code execution."
4. **specialUse service justification**: Clearly explain it as "Local development server for code editor."
5. **Content rating**: Properly categorize as Developer Tools.

**Contingency**: If rejected, appeal with detailed technical explanation. If still rejected, distribute as APK via GitHub Releases and F-Droid.

---

### T08: Extension Host worker_thread Patch (Score: 12, Medium)

**Risk**: Patching VS Code's Extension Host to run as worker_thread instead of child_process.fork() may be more complex than expected.

**Mitigation**:
1. **Research first**: Study `src/vs/workbench/api/node/extensionHostProcess.ts` thoroughly.
2. **Prototype early**: Build proof-of-concept before committing to this approach.
3. **code-server reference**: code-server has explored similar territory.

**Contingency**: Fall back to standard child_process.fork(). Accept 1 extra phantom process. More aggressively manage terminal and LSP processes to compensate.

---

### R01: Scope Creep (Score: 12, Medium)

**Risk**: Adding features before the core is stable. Trying to build M3 features before M1 works.

**Mitigation**:
1. **Strict milestone gates**: Must pass test gate before advancing to next milestone.
2. **M0 validation first**: If M0 fails, everything stops. Don't invest in M2+ features.
3. **Feature freeze per milestone**: No new features during milestone stabilization.
4. **PRD P0/P1/P2/P3 priorities**: Always work on P0 first.

---

### P04: Foreground Service Restrictions (Score: 12, Medium)

**Risk**: Future Android versions may further restrict foreground services, making it harder to keep Node.js alive.

**Mitigation**:
1. **specialUse type**: Currently the correct type for our use case.
2. **Justify to Google**: "Local development server" is a legitimate specialUse case.
3. **Monitor Android beta**: Track new Android developer previews for foreground service changes.

**Contingency**: Explore WorkManager with long-running work, or bound service with activity lifecycle.

---

### Additional Mitigation Plans (Remaining Risks)

These plans cover risks that did not yet have dedicated sections above.

| Risk | Mitigation Plan | Contingency |
|------|------------------|-------------|
| T04 (WebView fragmentation) | **Done:** a runtime check reads the installed WebView at launch and warns when it is below Chrome 105 (`MainActivity.checkWebViewVersion`). **Not done:** no compatibility matrix in CI or a device lab, and no release gate on WebView smoke tests | Deliberately a warning, not a blocking dialog: the floor is a tested one rather than a hard incompatibility, and an editor that degrades beats one that will not open |
| T05 (memory pressure/OOM) | Derive the V8 heap ceiling from device RAM (`ProcessManager.heapCeilingForDevice`, held between 256 MB and 768 MB), lazy-load extensions/LSP, add memory watchdog and pressure-based cleanup. **This bounds one number out of several and must not be read as bounding the app's memory.** See the note below | Auto-disable heavy extensions and reduce concurrent LSP to 1. A user-set ceiling disables itself after three `SIGKILL`s and says so |
| T06 (node-pty failure) | Build node-pty in CI for each Node.js bump, run PTY integration tests on physical device, keep pinned known-good node-pty version | Fallback terminal mode with reduced features until PTY patch is fixed |
| T07 (16KB page alignment) | Enforce linker flags in all native build scripts, validate with `readelf` checks in CI | Block release for API 36 target until all binaries pass alignment checks |
| T09 (asset pack download requires internet) | Keep the core toolchain offline-ready (Node/Python/Git), clear UI states for pending downloads, retry/backoff for flaky networks | A non-Play install fetches the same toolchain ZIPs over HTTPS from GitHub Releases, checked against a published sha256 manifest |
| T10 (Python packaging/stdlib gaps) | Take Python from the Termux package index at build time, smoke-test stdlib modules and pip in CI, and check every shared library the interpreter needs is bundled | Stay on the last known-good Termux python package |
| P02 (future Android binary restrictions) | Track Android previews quarterly, keep alternative architecture spikes (remote execution mode) in backlog | Shift distribution toward sideload/F-Droid while redesigning runtime model |
| P03 (restriction of .so execution model) | Keep policy documentation and precedent evidence updated, minimize dynamic execution surface, review Play policy each milestone | Prepare fast migration plan to policy-compliant delivery variant |
| P05 (scoped storage tightening) | Already mitigated by construction: the workspace is one of the app's own directories (`filesDir/projects` on a new install, or `getExternalFilesDir(null)/projects` on an install that already had it; neither needs a permission and scoped storage reaches neither) and every folder outside them arrives through SAF. `MANAGE_EXTERNAL_STORAGE` was never declared, so there is nothing to keep optional and nothing to document. What remains open is migration tooling for moved workspaces | Nothing to fall back to: SAF-only is what already ships |
| L01 (trademark risk) | Keep Code-OSS attribution + disclaimer visible, prepare backup branding set, run pre-launch legal checklist | Rename app and migrate package/display name with compatibility notes |
| L02 (Open VSX outage/API change) | Cache extension metadata locally, keep retry + graceful degradation in UI, monitor Open VSX status | Support manual VSIX install for critical workflows |
| L03 (license compliance drift) | Maintain SBOM/license inventory per release, automate NOTICE generation in CI, review bundled binaries/licenses at tag time | Pull non-compliant artifact from release and republish patched build |
| R02 (upstream source layout changes) | Pin `VSCODE_VERSION`, rebuild the server once per bump, and prove every patch reached the packaged bundle with `scripts/check-patch-fingerprints.py` against `patches/fingerprints.txt` | Stay on the pinned version until the diffs are reworked |
| R03 (burnout/single maintainer risk) | Enforce milestone scope limits, reserve buffer in each milestone, document key build/release runbooks | Freeze new features and run maintenance-only cycle |
| R04 (limited real-device access) | CI compiles the instrumented suite (`assembleDebugAndroidTest`); a person runs it on a physical device, because an arm64 emulator on hosted runners has no KVM. Define the minimum required test set per milestone | Delay milestone exit until the mandatory device matrix is met |

> **T05: what the heap ceiling does and does not bound.** The mitigation above has been read as
> "the app caps its own memory". It does not, in three separate ways, and each one has already
> been the basis of a wrong conclusion somewhere in this repository.
>
> 1. **The flag caps each V8 isolate, not all of them together.** `--max-old-space-size` reaches
>    the bootstrap, the editor server's main isolate, the Extension Host worker, the Pty Host
>    worker and the forked file watcher, and every one of them is capped at the same number
>    rather than sharing it. A ceiling of N authorises roughly 3N of old space in that family.
>    Raising the number is a larger step than it looks, which is why the user override is
>    clamped to a quarter of RAM and to 1536 MB, not to whatever the device could nominally
>    hold.
> 2. **It does not reach the largest V8 heap on the device.** `typescript-language-features`
>    passes `tsserver.maxMemory` as tsserver's own `--max-old-space-size`, defaulting to
>    3072 MB with no reference to device RAM. tsserver is forked by the Extension Host, so
>    nothing in this app is on its path. On a 4 GB phone the editor server is held to 462 MB
>    while one language server is authorised 3072 MB. **Open decision:** whether to clamp that
>    number to the device, pin it explicitly so it is at least deliberate, or leave it. Clamping
>    it would do more for stability on a small phone than any raise of the server ceiling does
>    for capability on a large tablet, and it is a behaviour change for existing installs.
> 3. **It caps the V8 heap, not process RSS.** Native memory, ICU data, loaded addons and the
>    Chromium renderer all sit outside it, and what ends the process is Android's low-memory
>    killer, which does not read flags.
>
> The user override (`vscodroid.server.heapCeilingMb`, NFR-RES-07a) narrows the mitigation
> further: on a device where it is set, the app no longer bounds this number on the user's
> behalf. The compensating control is that the value disables itself after three `SIGKILL`s and
> the user is told which setting was turned off. That over-reacts on purpose. The app cannot
> tell an out-of-memory kill from a phantom-process kill, since both arrive as the same exit
> code, so the direction is chosen: a false positive costs one notification, and a false
> negative is an app that crash-loops across every relaunch with no reachable way back in.

---

## 4. Risk Monitoring

### 4.1 Early Warning Indicators

| Indicator | Trigger | Action |
|-----------|---------|--------|
| Node.js build time > 2 hours | M0 build stage | Investigate build config, try Termux binary fallback |
| Phantom process count at or above `ERROR_BUDGET` (14) | M1 integration test, and the status bar item on a device | Review process management. The app already warns the user at that count and offers the idle-server sweep; five is the idle baseline, so a threshold below eight fires on an app that is doing nothing |
| Patch apply failure on new VS Code | CI monthly check | Pause upstream sync, fix patches |
| WebView crash rate > 5% | M2 testing | Profile memory, reduce WebView load |
| Play Store rejection | M5 submission | Prepare appeal, prepare alternative distribution |

### 4.2 Review Cadence

| Review | Frequency | Participants |
|--------|-----------|-------------|
| Risk register review | Each milestone boundary | All contributors |
| Technical risk check | Weekly during active development | Developer(s) |
| Platform risk check | When new Android version announced | Developer(s) |

---

## 5. Risk Heatmap

```mermaid
quadrantChart
  title Risk Heatmap (Probability vs Impact)
  x-axis Low Probability --> High Probability
  y-axis Low Impact --> High Impact
  quadrant-1 Monitor
  quadrant-2 Medium Mitigation
  quadrant-3 Accept
  quadrant-4 Active Mitigation
  T01: [0.8, 1.0]
  T02: [0.6, 1.0]
  T03: [0.8, 0.8]
  T04: [0.6, 0.6]
  T05: [0.6, 0.8]
  T06: [0.4, 1.0]
  T07: [0.6, 0.6]
  T08: [0.6, 0.8]
  T09: [0.4, 0.4]
  T10: [0.4, 0.4]
  P01: [0.6, 1.0]
  P02: [0.4, 1.0]
  P03: [0.4, 1.0]
  P04: [0.6, 0.8]
  P05: [0.4, 0.6]
  L01: [0.4, 0.8]
  L02: [0.2, 0.6]
  L03: [0.2, 0.8]
  R01: [0.6, 0.8]
  R02: [0.4, 0.8]
  R03: [0.6, 0.8]
  R04: [0.6, 0.6]
```

Legend:
- Critical (20-25): T01
- High (13-19): T02, T03, P01
- Medium (6-12): T04-T08, P02-P05, L01, R01-R04
- Low (1-5): T09, T10, L02, L03

---

## 6. Decision Log

The decisions these risks settled, and what each one buys:

| Decision | Risk Addressed | Rationale |
|----------|---------------|-----------|
| Node.js taken from Termux's `nodejs-lts` package, not nodejs-mobile | T02, T06 | nodejs-mobile has no `child_process.fork`, no worker threads and no node-pty, and the extension host and the terminal need them |
| Build Code - OSS from the MIT `microsoft/vscode` source | T03, R01 | The pre-built server on Microsoft's update CDN is under terms that do not permit modifying it and redistributing it inside an APK |
| Extension Host as a `worker_thread` (`patches/0004`) | T01 | A thread costs nothing against the phantom-process limit; a `child_process.fork` costs one |
| ptyHost as a `worker_thread` (`patches/0003`), one bash per terminal | T01 | Keeps the terminal host off the phantom count while every terminal keeps a real PTY |
| Core binaries shipped as `.so` in `jniLibs`, toolchains never in the APK | P01, P02 | Extraction with the execute bit is the only supported route, and SELinux refuses `execve` under the data directory |
| Open VSX, not Microsoft Marketplace | L01 | Microsoft's Marketplace terms do not permit third-party clients |
