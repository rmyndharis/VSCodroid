# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- The build manifest now records the app version, versionCode and commit, so a published artifact can be traced to the build that produced it.
- Release runs for one tag now queue rather than run concurrently, so two runs can no longer interleave asset uploads onto the same release.
- Every workflow step is pinned to a verified commit, and the release build no longer holds write access or a push token while running third-party code.
- Lint and the repository self-checks now run on pushes to main, not only on pull requests.
- **The VS Code server is now built from MIT-licensed Code - OSS source** instead of Microsoft's pre-built server, which could not legally be modified and redistributed inside an APK.
- VS Code upgraded 1.96.4 to 1.133.0.
- Node.js runtime upgraded to 24.18.0, taken from Termux's `nodejs-lts`. The previous hand-cross-compiled 20.18.1 segfaulted inside several CLI tools.
- npm upgraded 10.8.2 to 11.16.0, the version the bundled runtime actually ships with.
- Upgraded androidx.activity to 1.13.0 and Material Components to 1.14.0. Play's edge-to-edge warning comes from those libraries and remains; see `docs/PLAY_EDGE_TO_EDGE.md`.
- Extension signature verification is off by default. The editor build has no signature-checking component, so every marketplace install stopped on a warning that could never clear.
- The Accounts and Manage icons are back at the bottom of the activity bar. A build-time stylesheet had hidden them, leaving touch users no route to Settings or sign-in.
- Holding Ctrl, Alt or Shift on the key row now matches tapping it. Behaviour change: holding a modifier that is already on switches it off.
- When a previous editor server survives, the app serves that one instead of starting a second it cannot use. It still cannot stop a server it did not start, and now says so.
- Any address the editor asks to open now opens. Previously a LAN dev server was dropped or opened depending on which internal route the editor used, with nothing said either way.
- The Get Started screen no longer states bundled tool versions, which had been wrong for two releases, and no longer says Java and Ruby are coming; both install today.
- The Get Started terminal step shows command and output in the step text. The illustration carrying them is hidden on any phone under 950 CSS pixels wide.
- The Get Started screen names **Manage toolchains**, the label the launcher actually shows, instead of **Toolchains**.
- VSCodroid no longer offers itself in Android's "Open with" list for source files. It advertised twenty file types and opened none of them.
- Removed an origin check on the Android bridge that nothing called and nothing could satisfy. The session token gates that surface, and every bridge method now validates it.
- Asking the terminal host or extension host to stop immediately no longer gets the graceful path instead. Nothing escalates today, which is why this was invisible.
- Berkeley DB (AGPL-3.0-only) is no longer bundled. Nothing linked it. Four libraries that are used were added to the source offer, and `NOTICE.md` now lists all 39 components.
- Attribution is now checked against every binary in the asset tree, not just the top two directories: 195 more files, including the WebAssembly, Windows and macOS objects an ELF-only scan walks past.
- A binary that moves can no longer be recorded under the wrong licence in silence. An attribution entry that stops matching is reported even when the move took its own directory with it.
- Termux's licence field is now read back per package. When upstream answers, a release stops on a component recorded permissive but declared copyleft, which ships with no source offer.
- A release also stops when that comparison cannot be trusted: too few packages read upstream, or a download script no longer naming the ones it installs.
- The check that the licences screen's documents are packaged now runs when the build script changes, which is the edit that can drop them.
- `NOTICE.md` names each bundled extension by id, and the build fails when that list and the shipped set disagree, or an extension's licence is not the one recorded.
- The About dialog's Source Code link moved onto the new licenses screen, beside the offer of source it answers.

- Builds now verify the packaged server tree carries every patch this repository applies, not just the downloaded copy. The two trees had already diverged on a working checkout.
- The build refuses a server tree missing any Android adaptation, rather than checking only the ones someone listed.
- The server build refuses a pinned commit whose source declares a different VS Code version. Bumping the version without the commit previously shipped the old source under the new name.
- The server build selects source by commit rather than tag. `VSCODE_COMMIT` holds it; a moved tag can no longer change the tree.
- Patch fingerprints no longer depend on minifier formatting, and each must be text the patch itself introduces.
- Every bundled extension must name the version it ships, and the build stops when the marketplace serves a different one.
- Extensions dropped from the bundled set are removed from the build tree instead of shipping in development builds.
- The build fails when a self-check sits in the repository unrun. All JavaScript self-checks must run on both the pull-request and tag paths, and every `check-*.py` must be invoked by something.
- The build no longer treats a check it could not run as a check that passed. A `gh` that was absent or rate-limited previously skipped the tarball digest comparison silently.
- Lint fails the build on new issues. A baseline was configured alongside a setting that discarded the result, so the two cancelled out and hid 22 errors.
- The lint baseline keeps only the 17 issues it still suppresses. Of the 42 removed, 39 could match one developer's checkout alone; one could have re-hidden an unused resource.
- The build now fails when that baseline grows or names one developer's checkout, so regenerating it cannot quietly silence warnings lint is still reporting.
- The three release-only gates (code shrinker, resource shrinker, lintVital) now run weekly and when their inputs change, instead of first running on the day a release is tagged.
- The release checks the bundle against store size limits before publishing, rather than failing at upload with the download release already public.
- Every bundled executable and shared library is checked before packaging for architecture, resolvable dependencies and 16 KB page alignment. Previously only Node and the native addons were.
- The same check now covers the Ruby and Java toolchain downloads, the Python bundling step, and the glibc compatibility layer, each of which previously shipped unexamined.
- The Python bundling step fails when the interpreter's runtime library is absent, installs it under the name the launcher links against, and removes standard libraries from earlier versions.
- The bundled SQLite engine is checked against the JavaScript shipped beside it. A mismatch shows up on device as chat failing to pick a model.
- Node.js headers are checked against the digest nodejs.org publishes, the last download taken on trust.
- All toolchain downloads use one package mirror. Three still pointed at a host the others had left while sharing a cached index, so a build could resolve from one and fetch from another.
- The musl loader now comes from a supported Alpine branch, and an index older than 30 days fails the build. The previous branch stopped receiving fixes in April; its signature still verified.
- The loopback bind on the editor server's command line is pinned by a test. A wildcard bind would have put the editor on the phone's network without failing anything.
- Test suites stopped reporting results they had not measured: skipped device tests read as passing, one slept a minute and asserted nothing, and the four toolchain delivery functions had no test at all.
- The on-device suite states when it is meant to run, checks that what shipped actually runs, and reads versions from the build rather than from literals two releases stale.
- The on-device suite now checks toolchain installs and the terminal's shell configuration, and reports skipped rather than passed where adb cannot reach, instead of leaving both to a human checklist.
- The extra key row's trackpad now has tests covering its speed gears and arrow output. It is the only way a touch user moves the cursor, and nothing guarded it.
- Hardware keyboard support is now pinned by tests: no key event is intercepted, and the manifest still keeps a keyboard or a rotation from rebuilding the window.
- A test now pins that the port scan steps over a bound port rather than abandoning the range for an ephemeral one, which costs the workbench origin its stability.
- The extensions and server-log directories the server is launched with are now pinned by name. Moving the extensions one empties the workbench's extension list silently.
- Removed twenty-six tracked files that nothing built, ran or opened, including the cross-compilation scripts replaced when binaries began coming from Termux.
- Six releases that shipped without a changelog entry now have one, reconstructed from commit history and marked as such. Two footer links named a tag that never existed.

- README, the user guide and the requirements specification now carry storage figures that match the gate: 810 MB of assets, 875 MB required before extraction, about 1.15 GB with all toolchains. The README promised 500 MB while setup refuses under 875 MB.
- The milestone checklist says a ticked box records what was true when it was ticked. It is the only planning document still tracking open work, so it carries a note rather than a historical marker.
- The milestone log and implementation plan no longer record a process-liveness check as the fix for the white screen on reopen. A contributor could reintroduce it as precedent.
- The milestone checklist no longer plans on-device CI as a hosted device lab. Nothing in the workflows starts the app, and arm64 runners cannot boot an emulator.
- The bug report's documented contents match what it emits, and the logging table no longer names a server or extension-host log file that nothing in the app writes.
- The logging table names the Logcat tags in use. It named a tag nothing logs under, and called WebView output debug-only when errors and warnings ship in release.
- The logging table says where Extension Host output lands: the server's log service writes it to `remoteagent.log` and echoes it on the server console.
- The deploy script's closing hint prints a Logcat filter that matches. It named the bare tag, which nothing logs under, so the command returned nothing.
- The requirements table's heap limit is no longer a flat 512 MB; the ceiling has been derived from device RAM since a fixed limit left 3-4 GB phones nothing to work with.
- `CONTRIBUTING.md` no longer describes a URL allow-list the app does not have. VSCodroid is a development environment and reaches LAN hosts on purpose; the session token is what is checked.
- `CONTRIBUTING.md` describes both toolchain delivery paths. It named only Play Asset Delivery, while every non-Play install takes the HTTP path that developers themselves run on.
- The contributing guide's repository map matches the shipped binaries: Python never lived in `jniLibs`, while git's HTTPS helper and the musl loader do.
- The contributing guide records that findings not fixed in the same pull request get an issue, and rejected ones a stated reason.
- The planning-era development guide is now a pointer at `CONTRIBUTING.md`. It named a build script that no longer exists and a launch command that skips first-run extraction.
- `CONTRIBUTING.md`'s asset steps now start with `./scripts/setup.sh`. It refuses a missing NDK before the downloads rather than after them, and nothing else documented the script.
- The contributing guide covers device debugging and the version bump: which log carries the server's output, how to probe readiness, and why `versionCode` alone does not re-extract assets.
- README realigned with how the project builds: local builds fetch the prebuilt server, SSH ships as OpenSSH plus `ssh-keygen`, toolchains install on sideloaded devices, and the size table is measured.
- The user guide no longer documents Command Palette entries that were never registered. Each now describes a route that works or says plainly it is not reachable.
- The user guide no longer claims sideloaded builds carry every toolchain inside the APK. They download on demand and land in app storage.
- Three device test cases expected results that cannot happen. The SSH cases now use the terminal and the folder cases are marked blocked, so an unchecked box means untested.
- The device test checklist covers device folders again. Three storage rows were blocked against a defect since fixed, and five more rows were added.
- The privacy policy describes that opening a device folder makes a copy in app storage, which the old text omitted entirely.
- The privacy policy no longer claims Microsoft's telemetry code was removed. It is disabled and given nowhere to report to, which is the accurate and stronger statement.
- The published privacy page carries the project's trademark notice, and the store listing no longer opens with a Microsoft trademark and carries the disclaimer in full.
- The key row guide now matches the app: the plus key it listed never existed, and the trackpad, extra pages, long-press alternates and Shift's reach were missing or wrong.
- The guide, README and PRD no longer promise first-run extraction in 5-10 seconds. It unpacks about 810 MB, and nothing had ever measured how long that takes.

### Added

- The Explorer's **Upload...** now opens the device file picker. It was on every folder's menu and hung silently, the only route to import a single file from device storage.
- The Explorer's **Download** now asks where to save each file and writes it there. It did nothing at all, and a failure says so instead of leaving a partial file.
- **Open Source Licenses**, on the About dialog: the notices now ship inside the app and read offline, so the GPL written offer of source reaches every device holding those binaries.
- **License Texts**, one tap from that screen: GPL-2.0, GPL-3.0 and LGPL-2.1 now ship verbatim in the app. A gnu.org link is not the copy those licenses require.
- VSCodroid warns when the installed Android System WebView is older than Chrome 105, the version it is tested against. It warns and continues rather than refusing to start, and a version it cannot read is not treated as an old one.
- **Serve on Network**: lists the ports your dev servers are listening on, shows the address other devices can reach them at, and copies it. Loopback-only servers are called out.
- You can preview your own dev server at the device's network address from inside the editor, not only at `localhost`.
- `Simple Browser: Show` opens any loopback address in a tab beside your code. It shipped all along and was documented nowhere.
- The guide documents two silent limits: the interface is English only, and an extension bundling a compiled Linux program can install and never run. The Get Started screen warns of the second.
- **GitHub Copilot Chat works on device**: platform packages aliased under the name Android resolves, the SDK entry ships again, and `@vscode/sqlite3` is rebuilt for Bionic.
- **Claude Code extension support**: the marketplace serves its musl build, the CLI starts through the bundled musl loader, and a loopback DNS proxy gives musl binaries name resolution.
- A glibc compatibility shim so prebuilt glibc-only addons load against Bionic, supplying the `__isoc99_` scanf family, the ctype tables and `copy_file_range`, and translating differently-numbered constants.
- Toolchain downloads, the server tarball, npm, extensions and every bundled tool are verified against the strongest digest their source publishes; a missing digest fails the build.
- Every release carries a manifest of what it was built from: editor version and commit, plus the version and checksum of each bundled tool.
- The privacy policy describes Android backup, which copies `~/.vscodroid/data/Machine` to your Google account. SSH keys, tokens, projects and toolchains are excluded by an allowlist.
- Two checks hold the bridge API documentation to the code: one reads the compiled class for method existence, the other reads source for parameter names, order, nullability and return types.
- Two more key row pages carry F1 to F12, Home, End, PgUp and PgDn. None were reachable by touch, so a shortcut bound to one needed a hardware keyboard.
- Rebuilding the Termux-sourced binaries no longer needs a network: `TERMUX_OFFLINE=1` checks the cached index against a stored signature, re-judged in full rather than trusted for having passed once.

### Security

- The on-device editor server requires a connection token, generated on first start and kept in private storage. Binding to `127.0.0.1` is not access control on Android.
- The connection token no longer reaches the Android system log, which other software on the device can read.
- Crash text reaching the clipboard, the crash dialog and the page now has the connection token stripped from the `tkn=` parameter it travels in.
- The address the editor is opened at is redacted by the shared routine before logging, replacing a hand-maintained token-free copy that could drift from the real one.
- Deciding whether the server on the port is ours no longer sends it the connection token. Ownership is settled from a record this app writes; a holder we have no record for is treated as a stranger.
- A server already on the port must now report the build it is before the app reuses it. Answering at all used to be enough, so a stranger holding the port could be handed the session token.
- Startup readiness is judged by the one endpoint answered before the token check. The previous check treated any non-error reply as healthy, including "forbidden".
- The session token is compared in constant time. Nothing was reachable through the old comparison; the point is that the property belongs in the comparison rather than in the token length.
- Every one of the twenty-eight editor-to-Android calls now requires the session token. Six took none, and a test enumerates them so a new one cannot skip it.
- Signing in to an extension only completes if this app started the sign-in, and only in the minutes after it sent you to a browser. An unsolicited callback could previously be answered on your behalf.
- Callbacks are matched to the sign-in request they name, not to any recent browser launch, so opening a link no longer widens the window an unsolicited one is accepted in.
- Content rendered inside the editor can only read files from directories the app publishes to it, rather than anywhere in app storage. Opening the home directory as a workspace now costs preview images rather than exposing the SSH key.
- The loopback DNS proxy requires a per-boot token. Any installed app could previously use it as an open forwarder attributed to VSCodroid, and a rejected tunnel no longer leaves a connection pinned open.
- The musl loader is anchored to Alpine's signing key and the signed chain followed to the payload. An Alpine index checksum covers only metadata, so the old check would have accepted a modified loader.
- Bundled binaries are traced to a signature from the project that built them, not a checksum served by the same host. A signed index older than a month is refused to stop replay.
- That index must also name the repository this build takes packages from. One key signs all of Termux's repositories, so a sibling repository's index verified clean.
- A server tarball downloaded from a release is checked against the digest that release records. Only a cached copy was, so freshly fetched bytes reached the APK unexamined.
- The bundled npm tarball and the Node headers are checked against digests recorded here, not a checksum the same host serves. That catches later substitution, not a first one.
- Each bundled extension is checked against a digest recorded here, not one the registry serves beside the VSIX. A digest that moves under a fixed version fails the build.

### Fixed

**Setup and storage**

- Upgrading re-extracts even when the version name is unchanged. Setup compared the name alone, and two builds have carried 1.1.0, so the first would have kept the old server tree.
- First run asks for as much free space as unpacking needs, measured from what the app carries. The old figure was 500 MB against a tree now over 800 MB, so devices in between failed partway with "Setup failed".
- Updating no longer asks for room the install already occupies. What is unpacked is credited, so an update asks about 177 MB instead of 874 MB.
- That credit now covers `usr/` and the extensions directory too, not just the server tree. Both are shared with installed toolchains and gallery extensions, which are subtracted, so a device with 177 MB free is no longer refused an update that needed 334 MB only on paper.
- A setup interrupted partway can be retried. The retry now counts what is already on disk rather than demanding the full amount again.
- A first run that cannot unpack the editor stops and offers to retry instead of reporting success, which previously left an app that opened but could never start its server.
- An installation left broken by an interrupted setup repairs itself on the next launch. Files partly written but still plausible are left untouched.
- An interrupted save no longer leaves settings or shell configuration permanently broken. These files are written in full or not at all.
- Running out of storage can no longer leave `npm install` permanently broken. The npm settings file is now written atomically like the others.
- A file being unpacked when something goes wrong no longer leaves a half-written copy in place of the real one.
- The list of bundled extensions can no longer be lost to an interrupted write, on a fresh install or an upgrade.
- Accented characters in `.bashrc` are no longer destroyed when the app updates its own section of that file.
- A Python installation left by an earlier version is removed even when the device is short of storage, which is the one case where the clean-up was needed and did not run.
- Python stopped working after some app updates, because the interpreter is replaced every time while its libraries were unpacked only on a version change. The app now repairs that at launch.
- Two app instances could run first-run setup concurrently; setup is now single-flight.
- Deleting the projects folder from a file manager no longer leaves the app permanently broken until you clear app data.
- An unreadable `toolchains.json` no longer deletes `toolchain-env.sh` on every launch, which took working toolchain commands out of every new terminal.

**Server lifecycle**

- A crashed editor window no longer leaves a file watcher running for three hours. Enough of them and Android kills the whole app.
- A server that has stopped for good now says so on the page, with a Try again button, instead of leaving "Starting server..." on screen for the life of the app.
- A session that adopted a surviving server now says on its notification that extensions, git and npm cannot reach the network, and that restarting fixes it. It failed silently.
- Reopening the app while the server is still coming up no longer lands on a connection-refused page. Readiness now comes from the health check rather than from whether a process exists.
- A start that fails while no window is open is reported to the next window that opens, instead of leaving a loading screen that never changes.
- A server that is merely slow is no longer treated as failed. At two minutes the app says the start is taking longer than usual and keeps waiting.
- The app no longer hangs forever when something else holds its port. A start that cannot bind is detected, reported, and the processes behind it shut down.
- The editor no longer adopts a server that is not answering. The app asks the port whether anything responds before reusing what its record names.
- A start that ends this app's own stuck server no longer records the healthy replacement as unable to bind, which could spend a restart on a server that was merely slow.
- Stopping the app now ends an adopted server instead of leaving it running. A bootstrap killed by the system leaves its editor server behind, and that survivor used to hold its memory and one of the 32 process slots Android allows until the app was force-stopped.
- A server of ours that holds the port without answering is ended before a new one starts, rather than being spawned over into a launch that cannot bind.
- An emergency port taken from the ephemeral range is no longer remembered, which used to move the workbench to a new address and empty its stored state.
- **Everything you set was forgotten on every cold start**, because the port changed each launch and the browser keys storage to the address. The port is now kept between launches.
- Stopping the server and starting again no longer leaves the new start unable to finish, and stopping the editor can no longer restart it.
- Starting the editor after it had given up no longer leaves it on the loading screen in front of a working server.
- A server that keeps failing says so once rather than once per attempt, and when it stops for good the notification carries a Stop button that lets the next launch start it.
- A start that cannot succeed no longer leaves the app behind a notification claiming the server is running.
- The Stop action on the notification stops the server. While the editor was open, which is nearly always, it stopped nothing and said nothing.
- Relaunching after repeated crashes recovers properly; the service had never marked itself stopped, so a relaunch waited for a signal that would never come.
- App froze and had to be force-restarted after the server process was killed; automatic recovery never actually ran.
- A server restart returns to the folder you had open instead of the default projects directory.
- A server killed while rewriting its own configuration no longer leaves the app unable to start.
- When the system kills the editor server for memory or the background-process limit, the log says so instead of reporting a clean exit.
- The editor's memory ceiling is sized from the device. A fixed limit left 3-4 GB phones being killed repeatedly while larger devices never used what they had.
- Switching to another app no longer kills your language servers. Android's window-hidden signal numerically outranks its real out-of-memory warning.
- A script of yours whose name merely contains a language server's, such as `run-eslint.js`, is no longer classed as one and killed under memory pressure.
- Low-memory warnings now reach the background server after the app is swiped away, so idle language servers are still reclaimed instead of holding memory and process slots.
- The toolchain picker states download and installed size separately. It showed only the unpacked figure, so every toolchain read as three times what it costs to fetch.
- Stopping the app from its notification no longer freezes the screen for several seconds or shows the "isn't responding" dialog.

**Device folders (SAF)**

- **Opening a folder from your device now works.** The commands hung for five seconds and reported `Bridge timeout` on every device; the extension carrying them ran on the server, where its channel reaches nothing.
- Renaming a folder inside a device folder no longer empties it. Android reports a rename as an unrelated delete and create, and the delete used to remove the subtree.
- A folder that appears is copied across with its contents, which also covers creating a folder that already has files in it. Symbolic links are not followed, since a synced folder is routinely a checked-out repository, and very large moves stop at a bound and say so.
- Renaming a folder no longer leaves a second copy behind. The two halves are joined where possible and the device folder is moved, carrying everything under it.
- Moving a folder into a different folder is carried across too, not just renaming one in place. Dragging `src/util` into `src/legacy/` used to leave the old copy on the device, reappearing beside the new one every time the folder was reopened.
- Where the halves still cannot be joined, a provider that supports neither move nor rename, a folder moved out of the workspace, or the second half of a two-step swap such as `mv dist dist.old; mv dist.new dist`, the old copy stays, because losing it would cost files that exist nowhere else.
- **Edits you had not saved back to a device folder are kept.** The guard compared size before timestamps, and almost every edit changes length, so almost every unsaved edit was overwritten.
- Reopening a folder no longer discards edits made since it was opened. Files are replaced only when the device copy differs in size, carries no timestamp, or is newer.
- Saving a file inside a subfolder of a device folder reaches the device. The mirror was watched at its top level only.
- Workspace settings and dotfiles travel both ways. `.vscode/`, `.gitignore` and `.editorconfig` were copied in but never written back.
- Files you delete on the device no longer reappear from the stale local copy.
- Renaming a file onto an existing name no longer leaves a "file (1)" copy, and copying in from the device no longer uploads a half-written file.
- Opening the list of recent folders no longer deletes the copy of the folder you have open.
- Closing a device folder can no longer close the app, and no longer discards writes still queued.
- Opening a device folder no longer leaves a watcher and thread running after the screen they belong to is gone. Rotation during a sync used to restart the watcher that had just been shut down.
- A folder re-granted while the app is reclaiming lapsed folders is no longer wiped. Each candidate is checked against permissions as they stand at that moment.
- A folder synced from a device copies only into its own mirror. A provider-supplied name that was not a single path segment could place the file outside it.
- A folder-sync upload interrupted mid-copy can no longer pass as a device edit on the next open; the mirror is kept and the upload attempted again.
- A symbolic link inside a mirrored folder is no longer followed by the watcher, which could watch and copy content from outside the granted folder.
- `mkdir d && mv x d` now moves in one piece once `d` reaches the device, instead of silently leaving the old parent holding everything.
- A file deleted in the editor no longer leaves a stale record that blocks the rename replacing it or swallows the next save of it.
- "Open Recent Folder" no longer closes the app. Commands arriving from the editor now move to the main thread before building a dialog.

**Terminal, keyboard and layout**

- Shutting down the terminal host waits for it to finish, up to three seconds, instead of stopping it after 200 ms. Output still being written was lost mid-write.
- Shortcuts using a punctuation key work from the on-screen keyboard. Both input routes now answer from one key table, which gained comma, full stop, hyphen, plus, asterisk, percent, question mark, caret and dollar.
- Tapping Shift on the key row and then typing on the soft keyboard inserts the character. The interceptor now takes over for Ctrl and Alt chords only.
- Ctrl+comma opens Settings and Ctrl+space asks for suggestions; both were previously unreachable by touch.
- The backslash key works. It is reached by holding `/` on the second page of the key row, and the script built for it was not valid.
- Terminal profile picker was empty, leaving no way to switch terminals ([#3](https://github.com/rmyndharis/VSCodroid/issues/3)).
- Chat panels were unusable: the extra key row covered the bottom of the page, exactly where VS Code anchors the chat toolbar.
- Tap targets stay finger-sized whichever way you hold the device, and are reconsidered on every rotation and resize rather than fixed at page load. Sizing now follows the pointer rather than screen width.
- In landscape the editor no longer extends under the punch-hole camera, which is a separate inset from the system bars.
- The Toolchains screen no longer draws its title under the status bar or its grid under the navigation bar, and an instrumented test pins it.
- The first-run toolchain picker no longer anchors Skip and Continue where a 3-button navigation bar covers them.
- Status bar icons were invisible in light mode. The app is always dark, but the bars followed the device theme; they are now pinned to light-on-dark.
- A crash in the page renderer during startup no longer takes the whole app with it, and the recovered window keeps its spacing below the status bar.
- A WebView rebuilt after a renderer crash no longer comes back without its Android bridge.
- Cold start no longer crashes while the WebView still shows its placeholder URL. Thanks [@4in4in](https://github.com/4in4in) ([#6](https://github.com/rmyndharis/VSCodroid/pull/6)).
- The editor starts without waiting on the launcher shortcut being published. Cold-start handoff fell from 50 ms to 15 ms, and from 129 ms to 24 ms at worst.

**Toolchains**

- The Go toolchain is withdrawn. It ran but could not compile, and an install that still has it is removed on first launch, freeing 179 MB.
- The Get Started screen no longer offers Go, and neither do the README, user guide or privacy policy. The picker had already dropped it.
- A toolchain install that fails now says why: out of space, no connection, not in this release, or a download that did not match. It said only "Failed".
- On a Play Store install that reason is given too. Play's error code went only to the log, so a full disk and a dropped connection read alike there.
- A sideloaded toolchain install resolves `latest` once and takes both the digest and the payload from that release, so a release published mid-download no longer refuses the install.
- Installed toolchains can be run. Android refuses to execute any file in an app's data directory, so terminal commands are handed to the system loader. **The redirect is shell functions, so its reach is the shell's reach**: `make`, directly executed scripts and extension-spawned processes still hit the binary directly and are refused.
- Tasks, npm scripts and anything run through `bash -c` now find npm, npx and the toolchain commands, which existed only in interactive shells. Direct execution and `sh -c` still cannot.
- You can add and remove languages after first run. Touch and hold the app icon and choose **Manage toolchains**. The picker is shown once and previously had no other way in.
- **The Ruby toolchain was missing six commands**, `rake` among them. It shipped a fixed list; it now installs whatever the Ruby release provides.
- Ruby's `fiddle` could never load, because the library it links was not part of the download.
- **The Java toolchain could not start on 16 KB page devices.** A JDK dependency was built for 4 KB pages, and three further libraries that could never load no longer ship.
- Uninstalling Ruby no longer removes a library Python depends on. An uninstall now leaves alone anything the base installation also provides.
- A toolchain installed by an earlier version is repaired in place on upgrade, without re-downloading.
- Installing a toolchain reports failure when its record cannot be written, instead of claiming success while leaving the toolchain absent from the picker.
- Installed toolchains can no longer disappear from the app while their files stay on disk, which also lost the file list that removal needs.
- Removing a toolchain from inside the editor works; the editor passes the full pack name and removal recognised only the short one.
- Cancelling one toolchain download no longer cancels the others queued behind it.
- A toolchain download that ends early is refused rather than installed as complete, where the server reports a length.
- Installing from inside the editor no longer stops silently on Play installs, where a large download needs a confirmation nothing was listening for.
- First-run setup no longer gets stuck on the download screen when a pack is cancelled or Play reports nothing. Skipped packs are named rather than failing silently.

**Extensions and commands**

- An extension retired from the bundle is cleaned up once instead of on every app update, so reinstalling it from the marketplace no longer loses it at the next upgrade.
- **Open in Browser, Generate SSH Key, Copy SSH Public Key and About now exist.** All four were registered through a loader API the editor does not ship, so every attempt failed silently.
- **Open in Browser** told you it had worked when it had not. The bridge method returned nothing, so the relay answered success unconditionally.
- A link opened from inside the editor no longer disappears when the bridge declines it; the click falls through to the WebView's own handling.
- A browser launch that failed left the sign-in callback window open for ten minutes, during which any `vscodroid://callback` on the device was accepted.
- A sign-in page on plain http now completes. Only the Custom Tabs hand-off was recording itself, so a self-hosted provider reached through the system browser had its return refused.
- A sign-in opened by a link navigation rather than by the editor's own route now completes. That hand-off recorded nothing, so its return was refused with nothing said.
- Signing in when Android has closed the app mid-browser now tells you what happened instead of silently doing nothing.
- A sign-in that outlasts its window now says so instead of failing silently, and coming back after five minutes no longer discards a callback the editor was collecting.
- The **Browse Extensions** step on Get Started could never complete, so the walkthrough stayed permanently unfinished. It waited on a view identifier the workbench does not register.
- **Serve on Network** appears for people upgrading, not only on a clean install. The app now records which extensions it bundled last time, so a never-bundled identifier cannot read as one you removed.
- **Serve on Network** can find your dev server again. It read a system file Android does not let an app open, and the failure was indistinguishable from finding nothing.
- Extensions maintained by this project update when the app updates. What to unpack was decided from the version in the folder name, so a code change without a version change reached only new installs.
- Bundled extensions updated by an app upgrade are visible again after the manifest is reconciled, and uninstalling one sticks across upgrades.
- The web walkthrough greets users with VSCodroid branding again, and the hamburger menu returned to touch-friendly sizing.
- **Every folder opened in Restricted Mode**, and the setting meant to turn that off has never worked on this platform. The server's own switch is now used.
- **None of the app's own settings ever took effect**; they were written to a file the editor does not read.
- Python discovery no longer runs through a native locator that is missing from the extension build available here, which found no interpreters and warned on every Python command.
- The Tailwind CSS language server is visible to the process monitor again, so it can be reclaimed and appears under its own name.
- The HTML, CSS, JSON and Markdown language servers can now be reclaimed under memory pressure. All four launch without a file extension, so no name had ever matched them.
- Language server processes are identified correctly. The guard reported them fine throughout because it compared a different form of the name, and the same matching could shut down your own script whose path contained `eslint`.
- The process count in the status bar includes the server's bootstrap process, which the system counts against the same limit. Every threshold had fired one process late.

**Network and native components**

- **Git over HTTPS now works.** The helper git runs for HTTPS was installed where Android does not permit execution, and the bundled curl looked for a CA bundle at a path that does not exist here.
- A certificate store interrupted while being written no longer breaks HTTPS cloning permanently. An interrupted write leaves the previous store and rebuilds next time.
- Git subcommands pointed into a previous installation after an app update, and were repaired only on a fresh install.
- The editor writes its log files again. The logger it ships is a native component built against a C++ library Android lacks, so it failed quietly and held every message in memory.
- Claude Code sign-in died with "Socket is closed": Node abandoned each connection attempt after 250 ms, which the handshake regularly exceeded from a phone.
- HTTPS through the name-resolution proxy no longer gets a plain-HTTP error page spliced into an encrypted stream; the proxy closes the connection instead.
- Tunnelling to an IPv6 address through the local proxy failed, because the target was split on every colon. A malformed port there could take the server down at startup.
- Plain HTTP to an IPv6 address through the local proxy failed with a bad gateway.
- Closing a tab mid-transfer no longer leaves the proxy pulling the rest from the network with nothing to receive it.
- Prebuilt glibc addons could not load on Android 13 at all: the compatibility library referenced a symbol that does not exist before Android 14.
- The glibc shim's ctype table misclassified five of twelve character classes, and its `environ`, `stdout` and `stderr` exports loaded as NULL.
- Python's compression and database modules were dead on device: `bz2`, `lzma`, `compression.zstd`, `curses.panel` and `dbm.gnu` all failed on missing libraries.
- Native terminal and file-watcher addons are built from the same versions as the JavaScript beside them, and the build fails on a mismatch.
- Every shell command an extension runs failed. The bundled runtime's default shell was a path inside Termux's data directory, which this app cannot reach; it now uses Android's own.
- Every make target that runs a command failed, and git hooks, `!` aliases, filters and the pager with it: five more bundled tools carried that same unreachable shell.
- Anything the bundled Python or Ruby ran through a shell failed on a path inside another app's data directory: `subprocess(shell=True)`, Ruby's `system` and backticks. A Ruby toolchain installed before this release keeps the old files until it is removed and installed again.

**Storage management and reporting**

- The cache clear no longer deletes files outside the cache. A shortcut left in the temporary directory was followed out of it, and the project it pointed at was deleted and counted as reclaimed.
- The storage breakdown stops counting the same files repeatedly through links; it was reporting several hundred megabytes that are not in your storage.
- The low-storage warning names something you can do. It pointed at a Settings screen that does not exist; **VSCodroid: Show Storage Usage** and **VSCodroid: Clear Caches** are now reachable.
- Crash logs are written again on Android 13, 14 and 15. The report asked the thread for an identifier that only exists on Android 16, inside a catch that swallowed the failure.
- Crash reports from a release build can be read again. The mapping file was produced during the build and discarded; it is now kept with the release.

**Build and release**

- Release builds run again. Packaging the licence documents left a step nothing declared, and the only build that reaches it is the one a tag starts.
- A stalled package mirror no longer eats a whole build. Refreshing the index was unbounded, so it could consume the job's entire budget and report every later step as skipped.
- Unit tests re-run when a patch, the bootstrap script, a bundled extension manifest or a documented requirement changes. None was a declared input, so incremental runs served stale verdicts.
- A server tree built before the current terminal-host shutdown is refused rather than packaged; the patch check matched text an earlier version of that patch had already added.
- A server build release could take over the `latest` release pointer, which breaks toolchain downloads for every non-Play install. It is now kept out of that pointer permanently.
- A release can no longer be published with one of its files missing. Upload and publish both defaulted to warning rather than failing.
- Builds no longer re-download every bundled package. The cache pointed one directory above where packages are written, so it matched none of the 72 files there.
- The release manifest can no longer name a version the run did not install; the runtime and musl loader lines are written after verification, and cleared when their step begins.
- A check that examined nothing no longer reports a clean result. The server-tree architecture check printed its count unchanged when the count was zero.
- Every binary in the native library directory is checked at packaging, not only by the installer. Search's ripgrep had never been checked for page alignment, and that failure makes Search silently return nothing.
- The build's checks name the file they failed on instead of ending in a stack trace, carry on to the end of a tree rather than stopping at one damaged file, and no longer hang on a named pipe.
- The next VS Code upgrade will build. One of the twelve patches had prose rewritten without its hunk header adjusted, which `git apply` refuses to read. Pull requests now parse every patch.
- Five checks that could not fail now can, each confirmed by breaking what it guards and watching it go red.
- The on-device suite no longer passes git's HTTPS helper when the helper is absent, or when the check could not run at all.
- Two on-device checks no longer report a working install as broken, and the toolchain shortcut check now proves the shortcut opens its screen, not merely that it exists.
- The rule deciding which addresses may open in your browser is actually exercised by tests. Its `http://` branch had never run, because the URL parser it used is unavailable off-device and the failure was swallowed.
- Building from source reports a missing Android NDK immediately instead of twenty minutes in. Set `REQUIRE_NDK=0` to skip.
- Following the contributing guide's build steps produces the same app CI builds. Three steps were missing, and a check now fails the build when the documented steps and CI diverge.
- Build and release workflows no longer fail when the runner's package index is out of date.
- Launching no longer crashes outright if refreshing tool paths fails, and comments and formatting in `settings.json` survive that refresh.
- A Claude Code wrapper you pointed somewhere yourself is no longer overwritten on every launch.
- The bundled npm tree keeps its licence files. A sweep aimed at READMEs took npm's own LICENSE and 28 dependency notices with it; the build now counts what survives.

**Documentation**

- The white-screen fix no longer sends readers to Clear Data without saying it deletes every project, and now lists how to rescue unsaved work first.
- The user guide's list of bundled extensions matches what ships. It named two that are not included and listed an included one under "extensions to install".
- The on-device toolchain checklist can be followed. Its five rows pointed at a Settings screen that does not exist.
- Fourteen disagreements between the bridge API documentation and the bridge, including an SSH argument documented as a key type when it is the comment, and seven methods missing entirely.
- Several documents described things the app no longer does: a fork rather than an MIT build, Rust and C/C++ toolchains, Play-only delivery, and a release-asset list omitting the toolchain downloads.
- The attribution for Code - OSS reproduces the copyright line exactly as the shipped licence file has it.
- Legal notices no longer list fixed versions for Node.js, Python, Bash, tmux and Make, none of which this repository pins.
- The placeholder page shown before the editor is installed no longer points at a build script that does not exist.

### Removed

- GitLens, bundled by earlier versions, is cleared from devices that still have it, roughly 22 MB. Installing it yourself from the marketplace is unaffected.
- An unused layout and seventeen unused strings left over from an earlier key row. No user-visible change.

## [1.0.0] - 2026-04-21

### 🎉 First Production Release on Google Play Store!

VSCodroid is now publicly available on Google Play. This release represents the cumulative work across milestones M0–M6, bringing a full VS Code IDE experience to Android.

### Added
- CI/CD pipeline: test job in CI, tag-triggered release workflow, GitHub Pages deployment
- Privacy policy hosted on GitHub Pages
- "VSCodroid: About" command in command palette with version info and legal links
- Third-party attribution file (NOTICE.md)
- User guide documentation
- Full changelog with milestone history

### Fixed
- Edge-to-edge display: upgrade AGP 8.9.1 + Activity 1.12.4 + Core 1.16.0 for proper edge-to-edge support
- Material library updated to 1.14.0-alpha09 to resolve edge-to-edge warnings
- Remove deprecated edge-to-edge theme attributes and fitsSystemWindows from layouts

### Changed
- Google Play production access granted — app now publicly available

## [0.2.9] - 2026-02-27

The last of four releases in twelve days that each corrected one part of the same
problem: the app drew behind the system bars without accounting for them. This one
moved to AGP 8.9.1 with Activity 1.12.4 and Core 1.16.0.

## [0.2.8] - 2026-02-27

Material updated to 1.14.0-alpha09, which cleared the edge-to-edge warnings the
previous release surfaced.

## [0.2.5] - 2026-02-27

Removed theme attributes for edge-to-edge handling that the platform had
deprecated.

## [0.2.4] - 2026-02-26

Removed `fitsSystemWindows` from the layouts, where it worked against the
edge-to-edge handling rather than with it.

## [0.2.3] - 2026-02-15

Edge-to-edge layout corrections, the Manage section hidden, and the Activity Bar
no longer overflowing. Release signing gained a local configuration and a
`build-aab.sh` script, so a bundle could be produced outside CI.

## [0.2.2-m6] - 2026-02-14

The release that got the app onto Google Play, and the last one before 1.0.0.

Extensions could sign in and stay signed in: OAuth callbacks now return from
Chrome to the WebView through an intent, and extension secrets survive a restart
instead of being asked for again. Reopening the app while its server was already
running no longer showed a white screen. The mobile menu and the on-screen
keyboard stopped fighting each other over the same space.

An OpenSSH client was bundled, with its libraries and corrected paths. App
upgrades gained migration hooks, so an update preserves what the previous version
wrote. Cleartext traffic was restricted to localhost and URL validation tightened.

Verified on four physical devices rather than an emulator -- a Redmi on Android
13, a POCO with 4 GB of RAM on Android 14, and a OnePlus on Android 16 -- along
with the toolchain download, its HTTP fallback, uninstall cleanup, and a release
build with R8 enabled.

---

**About the six entries above, this one included.** They were written retroactively, reconstructed
from the commit history, because these releases shipped without changelog entries
at the time. What each one says is what its commits did; none of it was recorded
as a release note when it happened. Two of the fixes -- the AGP and Material
upgrades -- also appear under 1.0.0, which was written as a cumulative note
covering everything since 0.1.0.

There is no 0.2.6 or 0.2.7. No such tag was ever created, so nothing is missing
between 0.2.5 and 0.2.8.

## [0.1.0-m0] - 2026-02-10

This release represents the cumulative work across milestones M0 through M5, bringing VSCodroid from initial project structure to a fully functional IDE on Android.

### M5: Quick Wins & Developer Experience
- SSH key management: generate ed25519 keys and copy public key from command palette
- "Open in Browser" command for previewing localhost dev servers (Vite, NestJS, etc.)
- Selective `platform-fix.js` preload for npm/node-gyp compatibility (no longer breaks Rollup/esbuild)
- Enhanced process monitor with tiered warnings, kill idle servers command, and storage display
- Bundled debug launch configurations (Attach to Node.js, NestJS Debug, Run Current File)
- `diffEditor.wordWrap` enabled by default
- `npm --prefer-offline` for faster installs

### M4: Polish & Stability
- On-demand toolchains via Play Asset Delivery (Go, Ruby, Java)
- Language Picker UI for first-run toolchain selection
- Toolchain settings screen for install/remove management
- npm 10.8.2 bundled with bash shell functions (noexec workaround)
- Python 3.12.12 bundled from Termux with full stdlib and pip
- Welcome walkthrough extension
- OAuth flow for GitHub authentication via Chrome Custom Tabs
- Storage management: breakdown display, cache clearing
- Crash reporter with bug report generation
- AAPT `ignoreAssetsPattern` fix for underscore-prefixed directories

### M3: SAF & Extensions
- SAF (Storage Access Framework) integration for opening device folders
- SAF two-way sync with file watcher for external storage
- Bundled extensions: One Dark Pro, ESLint, Prettier, Tailwind CSS, GitLens, Python
- Extension version pinning for VS Code 1.96.4 compatibility
- Process monitor extension with status bar indicator and phantom process tree

### M2: Terminal & Mobile UX
- Native node-pty (cross-compiled for ARM64 Android) replacing pipeTerminal.js shim
- Real PTY terminals via `/dev/pts/*` — vim, tmux, readline, colors, job control all work
- Extra Key Row with Ctrl, Alt, Tab, Esc, arrows, brackets, parens, semicolons
- Touch target enlargement CSS for phone-sized screens
- Safe area padding for round-corner devices and display cutouts
- WebView crash recovery with folder context restoration
- Back button navigation integration
- ptyHost as worker_thread (saves phantom process slot)
- Stale symlink detection and recreation on APK reinstall

### M1: Extension Host & Process Management
- Extension Host converted from child_process.fork() to worker_thread
- Phantom process monitor scanning by UID across all processes
- Memory pressure signal path: Kotlin onTrimMemory to process-monitor.js
- Idle language server cleanup (5-minute timeout)
- BroadcastChannel relay for browser extension access to AndroidBridge

### M0: Foundation
- VS Code 1.96.4 Web Client + Server running locally on Android
- Pre-built VS Code Server from Microsoft CDN with Android-specific patches
- Node.js 20.18.1 cross-compiled for ARM64 Android (48 MB libnode.so)
- vsda signing bypass (regex-replace signService.validate with Promise.resolve)
- Native module shims for spdlog and native-watchdog
- CDN URL interception in WebViewClient (rewrite vscode-cdn.net to localhost)
- Webview service worker disabled (Android WebView lifecycle incompatibility)
- Browser extension stubs for 17 built-in extensions
- Workspace Trust bypass for local remote connections
- process.platform "android" → "linux" patching (5 pattern types in minified code)
- product.json branding (VSCodroid, Open VSX marketplace)
- Foreground Service with specialUse for server persistence
- Bundled tools: Bash 5.3.9, Git 2.53.0, tmux 3.6a, Make 4.4.1, OpenSSH, ripgrep
- Open VSX extension marketplace integration
- SSL certificate configuration for HTTPS in Node.js
- Git path configuration for VS Code Git extension
- Health check polling for server readiness
- Android intent handling for "Open with VSCodroid"

[Unreleased]: https://github.com/rmyndharis/VSCodroid/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/rmyndharis/VSCodroid/compare/v0.2.9...v1.0.0
[0.2.9]: https://github.com/rmyndharis/VSCodroid/compare/v0.2.8...v0.2.9
[0.2.8]: https://github.com/rmyndharis/VSCodroid/compare/v0.2.5...v0.2.8
[0.2.5]: https://github.com/rmyndharis/VSCodroid/compare/v0.2.4...v0.2.5
[0.2.4]: https://github.com/rmyndharis/VSCodroid/compare/v0.2.3...v0.2.4
[0.2.3]: https://github.com/rmyndharis/VSCodroid/compare/v0.2.2-m6...v0.2.3
[0.2.2-m6]: https://github.com/rmyndharis/VSCodroid/compare/v0.1.0-alpha...v0.2.2-m6
[0.1.0-m0]: https://github.com/rmyndharis/VSCodroid/releases/tag/v0.1.0-alpha
