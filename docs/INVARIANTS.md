# Load-bearing invariants

These are not obvious from reading any single file, and violating them breaks
things **silently**. Verify each against the cited symbol before changing nearby
code.

This is the one place the full table lives, and `CLAUDE.md` imports it rather
than keeping a second copy, because a duplicated table goes stale on one side
without anyone noticing which. `CONTRIBUTING.md` deliberately keeps a four-row
excerpt under "Things that break quietly", so a contributor meets the most
common ones in the path they are already reading.

**No line numbers here, deliberately.** They rot within a day in this
repository, and this file exists to stop documents misleading readers rather than
to add another that does. Every row names a symbol; navigate by that.

## The table

| Invariant | Where | Why it is load-bearing |
|---|---|---|
| The port is allocated once and reused across every server restart | `ProcessManager.kt` | The WebView's loaded URL and the `VSCodroidWebViewClient` are both bound to it, and neither is rebuilt on restart: `initBridge()` guards on `bridgeInitialized` (`MainActivity.kt`), so the client keeps the port it was constructed with, and that port is what CDN interception and the localhost check read (`VSCodroidWebViewClient.kt`). This named the bridge's allowed-origin check as the second binding until 2026-08-14; that check had no callers and was removed |
| Restart guard tests `isRunning()`, never `serverProcess == null` | `ProcessManager.kt` | The crash path leaves the dead `Process` referenced; a null check refused every automatic restart (issue #3) |
| **Port ownership is read from our own record: the port holder is never asked, and never sent the token** | `ProcessManager.portHeldByOurEditorServer()`, the pid note written by `assets/server.js`, `AdoptionTest` | Adoption exists because a SIGKILLed `server.js` leaves its child holding the socket, and reusing that server keeps the open editor and its IndexedDB. The obvious ownership test, asking the holder whether it accepts our connection token, hands the credential to whoever holds the port, and binding a loopback port on Android needs no permission, so the one party the test exists to identify gets the secret first. It also judged by "anything that is not a 403", which 200, 302, 404 and 500 all satisfy. `server.js` therefore writes `{pid, port}` into `files/server/editor-server.pid` and this reads it back, checking `/proc/<pid>/cmdline` still names `server-main.js`. ⚠️ `/proc/net/tcp` cannot close the remaining gap: SELinux denies an app any read of it (measured: `untrusted_app_34` → `proc_net_tcp_udp:file` = 0), so nothing can map the port back to a pid. One `AdoptionTest` case asserts at the socket that the holder is never contacted |
| **Anything deciding whether to navigate the WebView asks `isServerReady()`**, never process liveness | `ProcessManager.isReady()`, `MainActivity.setupServiceCallbacks()`, guarded by `ServerReadinessCallSiteTest` | The two questions differ for seconds on every cold start and for the whole of a post-crash restart: `isRunning()` is `Process.isAlive`, true from spawn, while the port is not bound yet. Navigating on it yields a connection-refused page, and `onReceivedError` only logs, so nothing clears it. The real probe is HTTP and cannot run on the main thread, which is what made the wrong question attractive; `isReady()` reports what that probe already found at no cost. `NodeService`'s `isServerRunning()`/`isServerHealthy()` wrappers were **removed** (`84a56b6`) so the tempting name is no longer reachable from an activity |
| `ServiceWorkerClient` is registered before `loadUrl` | `MainActivity.kt`, `loadUrl` | Service-worker script fetches bypass `WebViewClient.shouldInterceptRequest` entirely |
| The WebView URL is the only truthful record of the open workspace | `MainActivity.folderFromUrl()` | VS Code switches folders by navigating the same WebView without going through Kotlin |
| `initBridge()` runs once per WebView, not per folder switch | `MainActivity.initBridge()` | Re-registering the JS interface and clients resets the session token. The one intended re-registration is after a renderer crash: `recreateWebView()` clears the guard for a WebView that no longer has the interface |
| `isFirstRun()` gates on **versionName**; `runMigrations()` thresholds on **versionCode** | `FirstRunSetup.isFirstRun()`, `runMigrations()` | Both are persisted together in `markSetupComplete()`; they must move together every release |
| Tool symlinks are rebuilt on **every** launch, not just first run | `SplashActivity.kt`, `FirstRunSetup.setupToolSymlinks()` | Android assigns a new `nativeLibraryDir` path on every reinstall, dangling every absolute symlink. `File.exists()` follows links and returns false, so staleness is detected with `Os.lstat()` |
| `server.js` rewrites `product.json` on every start with a **shallow** `Object.assign` | `server.js` `productOverrides` and its `Object.assign` | Nested objects are replaced wholesale, so `extensionsGallery` must list every field it needs (`controlUrl`, `nlsBaseUrl`) or they are lost |
| SAF folders are **hash-named local mirrors**, not a FUSE mount | `Environment.kt` | Node.js only ever sees a POSIX path under `filesDir/saf-mirrors/<sha256[0:6]>`; `SafSyncEngine` reconciles it with the content URI |
| Toolchains have **two** delivery paths chosen at runtime by install source | `ToolchainManager.shouldUseHttpFallback()`, `installFromDirectory()` | Play installs use Asset Delivery; everything else downloads ZIPs from `releases/latest`. Both converge on `installFromDirectory()`, which copies into `filesDir` and then calls `removePack()`, so toolchains survive app updates |
| `/data` refuses direct `execve()`, but **not** `dlopen()` | `FirstRunSetup.createNpmWrappers()`, `.bashrc` generation | SELinux denies `execute_no_trans` on `app_data_file` for targetSdk ≥ 29, so a shebang script in `filesDir` cannot be exec'd. That is why binaries ship as `lib*.so` in `nativeLibraryDir` and why `npm`/`npx` are **bash functions**, not scripts. The `execute` permission itself is still granted, so loading a `.node` addon from `filesDir` is fine, and `pty.node` depends on it. This row said "mounted `noexec`" until 2026-08-11, which wrongly implied the addon strategy was impossible |
| **Claude Code is not bundled.** The user installs the extension; the CLI rides inside it | `patches/0009`, `Environment.getMuslLoaderPath()`, `assets/dns-proxy.js` | Three separate walls, each measured on an API 36 emulator, and all three have to stay solved together. (1) The CLI is a per-platform native binary and the **glibc** build cannot start: its `__tls_init_tp` calls `set_robust_list` and `rseq`, both rejected by Android's app seccomp filter, so it dies with SIGSYS before `main()`. The `rseq` tunable does not help, `set_robust_list` has none. Patch 0009 makes the marketplace serve the **musl** build instead, which makes neither call. (2) That binary lands under `filesDir`, which SELinux will not `execve`, so `claudeCode.claudeProcessWrapper` names musl's loader in `nativeLibraryDir` and lets it `mmap` the payload, which the same policy does allow. It works with no shim because `resolveClaudeBinary()` passes the binary path as the wrapper's first argument, which is already the loader's calling convention. (3) musl resolves names through `/etc/resolv.conf`, which Android does not have, so `dns-proxy.js` runs a loopback proxy in Node (Bionic, working DNS) and hands the child `HTTPS_PROXY`. Nothing here is redistributed, so the version is the user's to update and the licence question does not arise |
| **Patch 0001 is not cosmetic: patch 0009 appears to depend on it** | `patches/0001`, the `u8()` marketplace-target gate in the shipped bundle | The row above rests on 0009 serving the musl build. That selector tests `isLinux` **before** `isAndroid`: `if(!qe) return !1; if(N1){ …alpine… }`, where `qe` is `isLinux`, and on Android `isLinux` is false unless 0001 widens it. So dropping 0001 as "just platform detection" would leave the CLI served the glibc build, dying with SIGSYS before `main()`, three layers from the cause. ⚠️ Read from the gate, **not measured**: nobody has built a tree without 0001 to confirm it. Note the mechanism is the `isLinux` boolean, not the `Platform` enum. 0001 widens both, but the enum only feeds `getTargetPlatform` |
| The release APK/AAB is the only build that runs R8 | `app/build.gradle.kts` release block, `.github/workflows/build.yml` (`assembleDebug`) | PR CI never exercises minification, so R8 behavior first appears at tag time |
| `build-vscode-oss.sh` runs two ways, and every path in it resolves from the script's own location | `scripts/build-vscode-oss.sh` (`REPO_ROOT`), `.github/workflows/build-vscode-oss.yml` | CI invokes it bare on the runner; only local runs mount `/patches` and `/branding`. Hardcoding the mount paths made CI build an unpatched, unbranded tree while printing "building unadapted" and carrying on. Missing patches or branding is now fatal unless `ALLOW_UNADAPTED=1` |
| A reused Docker work volume can make a build pass that a clean one fails | `docker volume vscodroid-codeoss` | It keeps the previous run's `node_modules`, `.build/extensions/` and output tree, so a hand-run task from an unrelated session can satisfy a stage the script never performs. That is exactly how the missing Copilot compile went unnoticed. Before trusting a local green, `docker volume rm vscodroid-codeoss` |

## Documents that have already misled readers

Every document in this repository describes intent at the moment it was written.
Implementation moved on; documents did not always follow. The rows below were each
wrong in a document someone trusted, and are kept so the same wrong turn is not
taken twice.


| What the doc said | What the code does |
|---|---|
| "Downloads a pre-built VS Code Server and patches it with in-place Python/regex edits" | True until 2026-08-12, and the reason for the pivot: that artifact is Microsoft-licensed and could not be modified and redistributed. Code - OSS is now built from MIT source by `.github/workflows/build-vscode-oss.yml`, with real diffs in `patches/` applied before the build. `scripts/download-vscode-server.sh` is deleted. Builds fetch the result with `scripts/fetch-vscode-oss.sh`. |
| "GitHub Releases (sideloading): all toolchains bundled directly in APK" | Toolchains are **never** in the APK. Non-Play installs download ZIPs over HTTP from `releases/latest` (`ToolchainRegistry.kt`, `ToolchainManager.downloadViaHttp()`, chosen by `install()`). |
| "Poll `localhost:PORT/healthz` until ready" | Polls `GET /version` and accepts **only** `200` (`ProcessManager.kt`). VS Code Server has no `/healthz`. This row itself said `GET /` and `200..499` until 2026-08-14: that was true until the server began requiring a connection token, after which `/` answers 403 and a probe accepting it reports a healthy start for a server that serves nothing but Forbidden. `/version` is answered before the token check, so it stays a pure liveness probe. |
| Binary sizes and the set of bundled `.so` files | Several sizes were off by 2 to 5 times, and two listed files do not exist. Run `ls -la android/app/src/main/jniLibs/arm64-v8a/` instead of trusting any list. |
| "The patches cover … Workspace Trust" | No trust patch exists and none ever did. `0007` is secret persistence (`head patches/0007-persist-secrets.patch`). Workspace Trust is handled at runtime; the application-scoped *setting* has never worked here, only the CLI flag. Wrong in this document until 2026-08-13. |

## How to verify quickly

```bash
ls -la android/app/src/main/jniLibs/arm64-v8a/   # what is actually bundled
grep -n "toolchain_" android/settings.gradle.kts # which asset packs actually exist
grep -rn "gradlew\|scripts/" .github/workflows/  # which scripts actually run in CI
find android/app/src/main/kotlin -name "*.kt" | xargs wc -l | sort -rn  # where the logic lives
head -2 android/app/src/main/assets/vscode-reh/LICENSE.txt  # MIT, or Microsoft's terms?
```
