# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- When a previous editor server is still running, the app now serves that one instead of starting a second it cannot use. Android reclaims background processes individually, so the process the app launches can be killed while the server it started keeps running and keeps the port — and the app would then start another that could never listen, watch that one instead, and lose track of the server you were actually using. It now recognises the surviving server as its own, keeps watching it, and notices if it goes away. It still cannot stop a server it did not start, and now says so instead of reporting that it did
- Opening a link from the editor no longer depends on which route the editor happened to use. A dev server on the local network, or anything else on plain http to a machine other than this one, was silently dropped when the editor opened it through one path and worked when it opened it through the other — with nothing said either way. VSCodroid is a development environment, so any address it is asked to open now opens
- Berkeley DB is no longer bundled, and every remaining library is attributed.
  It is licensed **AGPL-3.0-only** -- the strongest copyleft in common use -- and
  had shipped in every release since it arrived as a dependency of Kerberos,
  with no attribution anywhere and no offer of source. Reading the link tables of
  all 150 shipped binaries showed nothing referenced it at all, so it was dropped
  rather than documented, which ends the obligation instead of taking it on.
  Four libraries that *are* used were missing from the source offer for the same
  reason -- libiconv, gdbm, xz/liblzma and Zstandard, reached through Bash, Git
  and three Python modules -- and are now listed. `NOTICE.md` and
  `docs/LEGAL_NOTICES.md` carry the full inventory of 39 components, generated
  from the files actually present rather than written by hand: the old table
  named liblzma as public domain and libcrypt as LGPL, and both were wrong.
  Builds now fail on a shipped binary nobody has classified
- The Accounts and Manage (gear) icons are back at the bottom of the activity
  bar. A stylesheet appended at server-build time had hidden them since before
  the Code - OSS pivot -- parity carried forward release after release -- which
  left a touch user with no on-screen route to Settings, the Command Palette
  menu, or their sign-in sessions. The activity bar's overflow calculation
  already measures the room its neighbours actually leave, so it adapts to the
  section being visible without further change
- The terminal step now shows what a command and its output look like, in the step text itself. The examples were only ever in the illustration beside it, and that illustration is hidden on any phone dense enough to put the editor under 950 CSS pixels wide — which is every 480 dpi device, in both orientations. The picture was not wrong; nobody with such a screen could see it
- The Get Started screen told you to pick **Toolchains** after touching and holding the app icon. The launcher shows the shortcut's long label there, so what is on screen reads **Manage toolchains** — and on a phone it truncates to "Manage toolchai…", cutting off before the word you were told to look for. It now names the label you see
- Removed an origin check on the Android bridge that nothing called. It compared a caller's origin against the local server's, but the bridge Android exposes to the page does not carry the caller's origin at all, so there was no way to supply the value it wanted and no production code ever tried. What actually gates that surface is the session token, which every bridge method now validates. The six tests covering the removed method go with it, and the port it needed is no longer passed in
- The Get Started screen no longer states versions for the bundled tools, and no longer says Go, Java and Ruby are coming: all three install today, from the Toolchains shortcut on the app icon. The versions it named had been wrong for two releases -- Node.js 20 while 24 shipped, Python 3.12 while 3.14 shipped -- because they are resolved from the package index when the app is built, while the screen is written by hand. The illustrations beside the text carried the same claims and a mock terminal printing `v20.18.1`, and are fixed too. Telling a user to wait for a language already sitting on their device is the part that cost something: the picker offering it is shown once, and someone who read that screen had no reason to look for it again
- VSCodroid no longer offers itself in the Android "Open with" list for source files. It advertised about twenty file types and never opened any of them: picking it brought up the editor on the default folder with no file and no message. Folders still open through "Open Folder from Device"
- Builds now check that the downloaded server tree actually carries every patch this repository applies, instead of only checking that at build time. A tree built before a patch landed has the same filename, the same version and a digest that verifies, so nothing here could previously tell it apart -- and an app could ship a server missing a fix that the repository already contained
- The build now refuses a server tree that is missing any of the Android adaptations, instead of checking only the ones someone remembered to list. A patch added without its check used to produce a clean-looking build that quietly shipped without it
- npm upgraded 10.8.2 → 11.16.0, the version the bundled Node runtime actually ships with. The app had been packaging npm taken from an older Node release it no longer runs, left behind when the runtime was replaced
- The on-device test suite now states when it is expected to be run, and the build checks that it can still read the versions it asserts. It had gone two releases expecting a Node version the app no longer shipped, because nothing ran it and nothing noticed
- The user guide no longer tells you to run commands that do not exist. Generating an SSH key, copying the public key, previewing a dev server and opening the toolchain settings were all documented as Command Palette entries that were never registered, so anyone following the guide found an empty palette. Each one now describes a route that works — `ssh-keygen` in the terminal, `Terminal: Open Last URL Link` for a dev server — or says plainly that it is not reachable yet. Two details that only show up when you actually try: a terminal URL cannot be followed by tapping it, because VS Code opens terminal links on Ctrl+click and a touch tap carries no Ctrl; and `ssh-keygen` needs its output path given with `-f`, because the default is derived from a user database the app sandbox does not provide. The welcome file the app writes on first run pointed at two of the same commands and now points at what works instead
- The user guide said sideloaded builds carry every toolchain inside the APK. They never have: toolchains download on demand through the store, or over HTTP from the latest release for sideloaded installs, and either way they land in the app's own storage and survive updates. Anyone who believed the old sentence would have concluded a working install was broken
- Three device test cases expected results that cannot happen — two drove SSH key handling through the unregistered commands, and the folder picker cases depend on a picker that cannot be invoked. The SSH cases now use the terminal and the folder cases are marked blocked, so an unchecked box means untested rather than broken
- The release now checks the app bundle against the store's size limits before publishing anything, rather than measuring it and carrying on. A bundle that has grown too large previously failed at store upload with the download release already public, leaving one version shipping through two channels with different contents
- The on-device test suite now checks that what shipped actually runs: Python has to import the ten modules that need a bundled library behind them, the shell has to start, and git's HTTPS helper has to be executable. Five Python modules were dead on shipped builds for months while every check passed. The suite also stopped expecting a Node version the app replaced two releases ago — the versions it checks are now read from what was built rather than written down
- Extensions dropped from the bundled set are now removed from the build tree instead of lingering and shipping in development builds
- The Node.js headers the native components are compiled against are now checked against the digest nodejs.org publishes, the last download in the build that was still taken on trust
- The build now checks that the bundled SQLite database engine was compiled from the same version as the JavaScript shipped beside it, the last of the three native components without that check. A mismatch there shows up on device as chat failing to pick a model, an error several layers from its cause
- The build now checks every binary the Python bundling step installs — the runtime, the shared libraries and all 75 extension modules — rather than the launcher alone, and refuses to continue when a library is missing or pip did not install. It also removes standard libraries left over from an earlier Python version instead of shipping them alongside the current one
- Contributing guide's repository map matches the shipped binaries again: the Python runtime never lived in `jniLibs` (and its version is not pinned), while git's HTTPS helper and the musl loader do live there and were missing
- README brought back in line with how the project actually builds and installs: local builds fetch the prebuilt server rather than needing Node and Yarn to build VS Code, SSH ships as the bundled OpenSSH client and `ssh-keygen` rather than a command-palette flow, toolchains install on sideloaded devices too (direct download) rather than Play-only, and the size table now carries figures measured from the release AAB
- Contributing guide: review findings that are not fixed in the same pull request now get an issue, and rejected ones a stated reason, so nothing is left referenced only by its position in a discussion
- **The VS Code server is now built from the MIT-licensed Code - OSS source** instead of downloading Microsoft's proprietary pre-built server, which could not legally be modified and redistributed inside an APK. The build applies this repository's patches and branding to readable source, is verified for tree shape, architecture and branding before it ships, and is published once per VS Code version
- VS Code upgraded 1.96.4 → 1.133.0
- Node.js runtime upgraded to 24.18.0, now taken from Termux's `nodejs-lts` package — the previous hand-cross-compiled 20.18.1 segfaulted inside several CLI tools
- Every bundled executable and shared library — the shell, git, tmux, make, ssh and the libraries they load — is now checked before packaging for the right architecture, dependencies that are actually present, and the page alignment Android 16 requires. Previously only the Node runtime and the native addons were, so a tool whose dependency had gone missing produced a successful build and an install where the terminal would not start
- The Python bundling step now fails the build when the interpreter's runtime library is absent, installs that runtime under the exact name the launcher links against, and runs the same architecture, dependency and page-alignment check as every other bundled binary — the one it did not yet cover. It previously printed a note and carried on, which could ship a build where `python` failed on first use with a missing-library error
- The downloadable Go and Ruby toolchains are now checked the same way before they are packaged: every binary they ship must be the right architecture, find the libraries it links, and carry the page alignment Android 16 requires. They reach devices through two channels and nothing had examined them, so a broken binary would have been packaged, uploaded and installed before anyone noticed it could not start
- The compatibility layer that lets prebuilt Linux add-ons run is now checked end to end before packaging: every symbol an add-on imports must be present in the library that actually ships, and anything the build cannot provide stops it rather than being noted and skipped. It previously reported such gaps and continued, which could ship an add-on that loads and then fails at its first call to the missing piece
- All toolchain downloads now use the same package mirror. Three of them still pointed at a host the others had moved off for being unreliable, while sharing one cached package index between them — so a build could resolve a file from one mirror and fetch it from another and fail on the mismatch. A missing library during a toolchain download now stops the build as well, instead of being noted while the pack was assembled without it
- The Java toolchain is now checked the same way, completing the set — every downloadable toolchain is examined before it is packaged

- Holding Ctrl, Alt or Shift on the key row now does exactly what tapping it does. Previously a long press switched the modifier on without the button following, so the next tap turned it off from the editor's side while the button believed it was turning it on. **This changes behaviour as well as fixing it:** holding a modifier that is already on now switches it off
- Lint now fails the build on new issues rather than reporting them into a file nobody had to read. It was configured with a baseline, whose whole purpose is to narrow it to new issues, alongside a setting that discarded the result — so the two cancelled out. Turning it on surfaced 22 errors that had been invisible
- The build no longer treats a check it could not run as a check that passed. A cached server tarball is compared against the digest its release carries, but the command that reads that digest was allowed to fail silently — so a `gh` that was absent, unauthenticated or rate-limited skipped the comparison and the next line announced the file as cached. That is one of the few ways a server built before a patch landed can still reach an APK: it has the same name, the same version and an intact digest, and the two gates after it read the shape of the tree and the patch fingerprints rather than the rest of the bytes. It now refuses, quoting what `gh` actually said. `VSCODE_OSS_SHA256` gives any of the three sources a digest to be checked against, and a direct URL with none named reports itself unchecked rather than saying nothing. A directly named URL also stops losing to whatever happens to be cached: it silently lost before, which mattered because that is precisely the remedy the build prints when it detects a stale server tree — the one situation in which the instruction could never have worked
- The three gates that exist only on the release build — the code shrinker, the resource shrinker and lintVital — now run weekly, and whenever the files deciding their behaviour change, instead of running for the first time on the day a release is tagged. Only the tag-triggered workflow ever built that variant, so a dependency arriving without its keep rules first appeared with the tag already pushed and the signing key already decoded. What this catches is those gates failing, not their being wrong: something reached only by reflection can still be removed silently, and only a minified build on a device finds that. A pull request that only changes Kotlin does not trigger it either — the weekly run is what covers that, so a source change can be up to a week from its first minified build
- The test suites stopped reporting results they had not measured. Three device tests were gated on a precondition that, when unmet, skipped them and reported zero failures — indistinguishable from passing unless a person read the skip count, which is exactly what the file asked a person to do. A fourth slept a flat minute and asserted nothing at all. In the unit suite, one test hid its only assertion behind a condition that made it vacuous the moment the token generator stopped producing letters, five classes left a mock standing for whichever class ran next, and both guards in first-run toolchain downloading could be deleted with every test still green. Each of those now fails when the thing it describes breaks. And the four functions that decide how a toolchain reaches a device — which channel to use, and what to do with the pack once it arrives — had no test of any kind between them, on either delivery channel; they now have seven
- Six releases that shipped without a changelog entry now have one. The file jumped from 0.1.0-m0 to 1.0.0, so anyone asking what 0.2.4 contained found nothing between them. The entries are reconstructed from the commit history and say so, and they record that no 0.2.6 or 0.2.7 was ever tagged, so what remains is not another omission. Both links at the foot of the file named a tag that never existed — that release is tagged `v0.1.0-alpha` while its version is 0.1.0-m0 — which left one a dead link and the other a comparison against nothing
- Removed twenty-six tracked files that nothing built, ran or opened: two fixture projects reachable only through a line that recreated their empty directories on every setup run, and the cross-compilation scripts that were replaced when the bundled binaries began coming from Termux's packages. The documents describing them are corrected where they are current and labelled historical where they are not. The branding overlay's own closing instruction went with them — it said to keep a setting identical in two places when that setting is deliberately in only one, which is the regression that already cost three build attempts
- Asking the terminal host or the extension host to stop **now** no longer gets the polite shutdown instead. Both run as threads rather than processes on Android, behind an adaptor that presents the small part of the process interface the editor uses — and its stop method took no urgency argument at all, so a caller escalating past the graceful path silently got the graceful path, with nothing to say so. Nothing escalates today, which is why this was invisible; the adaptor now honours it. The thread identifier it reports as a process id is unchanged, because every reader of that number uses it as a log tag and a thread id is what tells the two hosts apart in one log — but it now says in place that it is not a process id, so nobody signals it or looks for it among the running processes
- The check that proves each Android adaptation actually reached the shipped editor no longer depends on how the minifier felt that day. Each adaptation is proven by searching the built output for a fragment it introduces, and those fragments were written down exactly as one build tool version happened to emit them — down to which quote character it chose. The next version choosing differently would have failed several of them at once, during a version upgrade, pointing at the adaptations rather than at the formatting. It also now requires each fragment to be text the adaptation itself adds: the rule was written down from the start, as an instruction to whoever added one, and enforcing it catches a fragment copied from surrounding code, which would have proven nothing while looking like proof
- Reopening the app while the server is still coming up no longer lands you on a connection-refused page. The app decided the server was ready by asking whether its process existed — which is true from the instant that process is spawned, stays true for the seconds the editor server inside it takes to claim its port, and is true for the whole of a restart after a crash. Anything that reattached during that window pointed the editor at a port with nothing listening, and nothing in the app took the resulting page away again, so it sat there until you closed and reopened. Readiness is now the answer the app's own health check already produced, remembered where that check runs, so the question can be answered instantly instead of guessed at from the wrong thing. A start that fails while no window is open now also says so to the next window that opens: that message used to be announced to a listener that did not exist yet, leaving a loading screen that never changed and never explained itself. And a server that is merely slow is no longer treated as one that failed — the app running out of patience is not a verdict on the server, and this project's own device tests allow four times as long for the same event — so one that claims its port a minute in is picked up whenever it gets there and the editor opens, instead of the app sitting in front of a working server it will not use. Patience running out is still worth hearing about, though, so at two minutes — the same budget the device tests allow — it tells you the start is taking longer than usual and that the editor will open once the server is ready. That is a status rather than a failure, and it is said once: the app goes on waiting either way. A server that answers after that still opens the editor; what it no longer does is leave you watching a loading screen that means nothing and says nothing
- Toolchains downloaded outside the Play Store are now checked against a digest the release publishes, before anything is unpacked. Sideloaded, debug and adb installs fetch a toolchain as a ZIP from the latest release, and nothing had ever looked at what came back. The one check that existed asked whether the transfer ended early, which says a download finished and nothing whatever about the bytes being the ones that were built; the archive was then extracted, made executable and linked into the tools directory on that basis. Releases now publish a `toolchains.sha256` beside the ZIPs, the app refuses any download that does not match it, and a release carrying no manifest at all fails the install rather than falling back to trusting what arrived — silently trusting is the behaviour being removed, so it cannot be what happens when the check is unavailable. The manifest is fetched before the payload, so a release that cannot vouch for a toolchain costs a few hundred bytes instead of 179 MB. The release build now fails if it packages a ZIP whose digest it did not publish, because the two halves have to agree or a toolchain stops installing for everyone not on the Play Store. Installing from the Play Store is unaffected, as that channel gets its integrity from Play, and toolchains already installed are neither re-downloaded nor re-checked
- The server build now selects its source by commit rather than by tag. It cloned by tag, and a tag is a name upstream can move: pointed at a different commit, the clone followed it and produced a different tree, with nothing on this side holding an expectation to compare against. The digest on the published tarball and the patch fingerprints both describe the artifact — the link from the source to that artifact was the part running through a mutable name. `VSCODE_COMMIT` at the repository root now holds the commit, the build fetches exactly it, and the tag is advisory. Fetching rather than merely checking matters at the moment the pin earns its keep: a build that only detected a moved tag would refuse every build from then on, including a rebuild of the release already published, and the way out would be to accept whatever upstream had moved to — so the guard against losing the tree would be what lost it. The comparison is kept as a second belt, because it also catches something fetching cannot: a work volume still holding an earlier version's checkout, which skips the fetch entirely and would otherwise be patched, built and published under this version's name. A version bump that forgets the new file stops the build rather than passing quietly, the Node version for the build host is now read at that commit too, and a run that overrides the version to try a tag before committing to it resolves that tag and prints the commit to record

- Extension signature verification is now off by default, and is turned off in existing settings that do not already mention it. The editor build used here has no signature-checking component, so the check could never succeed: every install from the marketplace stopped on a warning that the signature could not be verified and offered to continue anyway — a prompt with nothing behind it, on every install. Anyone who turns verification back on keeps it on.

### Added
- Previewing a dev server no longer means leaving the editor: `Simple Browser: Show` opens any loopback address in a tab beside your code, with back, forward and reload. It shipped all along and was documented nowhere, so nobody knew to type it
- **Serve on Network**: a command that answers "what address do I give my friend?" — it lists the ports your dev servers are listening on, shows the address other devices can reach them at, and copies it. Servers listening only on this device are called out as such, with the flag needed to change that
- You can now preview your own dev server at the device's network address from inside the editor, not only at `localhost` — the same thing a laptop does when you check how a page looks from another machine on the same Wi-Fi
- **GitHub Copilot Chat now works on device**: the bundled extension's platform packages are aliased under the name Android resolves, its SDK entry ships again, and `@vscode/sqlite3` is rebuilt for Bionic so model selection completes end to end
- **Claude Code extension support**: the marketplace serves its musl build, the CLI starts through the bundled musl loader, and a loopback DNS proxy gives musl binaries working name resolution
- A glibc compatibility shim: prebuilt glibc-only native addons (spdlog, sqlite3 and friends) now load against Bionic through versioned forwarder stubs instead of dying at `dlopen`. It supplies what Bionic has no equivalent for — the `__isoc99_` scanf family, the `tolower`/`toupper` character tables, and `copy_file_range` on devices below Android 14 — and translates what the two libraries number differently, so `getaddrinfo` and `getnameinfo` answer the question the addon actually asked instead of a differently-numbered one
- On-demand toolchain downloads, the server tarball, npm, extensions and every bundled tool are now verified against the strongest digest their source publishes, and a missing or wrong digest fails the build instead of shipping unverified bytes
- Every release now carries a manifest of what it was built from — the editor version and source commit, and the exact version and checksum of each bundled tool. Those versions are resolved from a live index while the release is being built, and until now nothing recorded the answer, so a report of a problem in a particular release could not be tied to the components that release actually contained. It records rather than pins, because upstream removes superseded packages and a pin would break the build on every routine update

### Security
- The musl loader is now anchored to Alpine's signing key, and the signed chain
  is followed all the way to the bytes that ship. It was the last download taken
  on trust: the index naming its checksum came from the same host as the package,
  so a host able to serve both could serve a modified loader with a matching
  index and every check passed. Worse, the checksum an Alpine index carries
  covers only a package's metadata stream, not its payload — the file this
  installs is the loader that starts the Claude Code CLI on device, and it landed
  in the one directory Android permits execution from. The index signature is now
  verified against a key committed to this repository rather than fetched
  alongside it, and the signed metadata's own `datahash` is checked against the
  payload. Confirmed by rebuilding the package with a modified loader and
  identical signature and metadata streams: the old checksum still matched it,
  and the new check rejects it
- The on-device editor server now requires a connection token. The server generates it on first start and keeps it in the app's private storage; the app supplies it automatically, so nothing changes in day-to-day use and servers you run yourself are unaffected. Signing in to an extension still works: that step returns through your browser rather than through the editor, so the route it lands on is answered before the check. Binding to `127.0.0.1` is not access control on Android, so the editor's own loopback socket needed authentication of its own rather than relying on the interface it listens on
- Startup readiness is now judged by the one endpoint answered before that check. The previous check treated any reply below a server error as healthy, which would have reported a fully successful startup for a server that could only answer "forbidden"
- The loopback DNS proxy that gives musl binaries working name resolution now requires a per-boot token. Binding to `127.0.0.1` is not access control on Android — loopback is not isolated per app — so any installed app could previously have used it as an open forwarder for arbitrary outbound connections attributed to VSCodroid
- A rejected tunnel request through that proxy no longer leaves a connection pinned open. Any app on the device could previously open them in a loop and hold file descriptors in VSCodroid's server for as long as it kept running — without a token, without reaching the network, and without leaving a trace in the log

- Content rendered inside the editor can now only read files from the directories the app publishes to it — the server tree, the extension directories, and the folder you have open — rather than from anywhere in the app's storage. The previous check compared a path against `/data/data/`, `/data/user/` and `/storage/`, and since everything the app owns sits under the first two, it never refused anything inside them; what it stopped was a way out of the sandbox. Your working folder is published too, so markdown preview still shows images sitting next to the file, unless that folder holds the SSH key or the connection token — opening the home directory as a working folder now costs it its preview images rather than handing over a key stored without a passphrase
- The session token every editor-to-Android call carries is compared in a way that takes the same time whether the first character differs or the last. Nothing was reachable through the previous comparison, and that is the point: its safety rested on the token being 32 random bytes rather than on the comparison, and the comparison is where that property should be stated
- Signing in to an extension now only completes if this app was the one that started the sign-in. The route a browser returns through is open to anything on the device by design — it has to be, since the browser is a separate app — and the only thing checked on arrival was that the address was shaped correctly, which anything can manage. What arrives is handed to the editor as the result of a sign-in, so an unsolicited one could be answered on your behalf at any moment. It is now taken only in the minutes after this app itself sent you out to a browser, which is the one thing an outside caller cannot arrange. Real sign-ins are unaffected, including slow ones, and a sign-in interrupted by the editor restarting still tells you so
- The binaries the app ships — the JavaScript runtime, the shell, Git, Python and every on-demand toolchain — are now traced back to a signature from the project that built them, instead of to a checksum published by the same server that hands over the file. Each of those downloads took its expected checksum from a package index served by that host, so a host offering both a modified binary and a checksum to match it satisfied every check there was. The index is now checked against the upstream signing key, recorded in this repository and confirmed against two sources that have nothing to do with the download server. A signed index older than a month is refused as well: a valid signature does nothing to stop a server replaying last year's index and holding every build to whatever was current then
- The editor server's connection token no longer reaches the Android system log. That token is what authenticates the editor to its own server, and the system log can be read by other software on the device, so a value that gates the whole editor was leaving the app's private storage as a side effect of ordinary use. Nothing changes in day-to-day use: the token is still generated on first start, still kept in private storage, and still supplied for you

### Removed
- GitLens, which earlier versions bundled, is now cleared from devices that still have it — roughly 22 MB. It stopped being included some releases ago, but the copy already installed was left in place and nothing removed it. Installing GitLens yourself from the marketplace is unaffected.

### Fixed
- Stopping the editor server and starting it again no longer leaves the new start unable to finish. A retry left over from before the stop could wake up during the new start, cancel it, and report a failure — leaving a server running that the editor never opened until the app was stopped and started once more
- When the editor server stopped for good after repeated crashes, its notification said so and offered nothing to do about it, and the app could not be started again until it was swiped from recents or force-stopped. That notification now carries a Stop button that clears it and lets the next launch start the server
- A server that keeps failing during start-up now says so once instead of once per attempt. The same message could previously appear on every automatic restart — up to five times over several minutes — before the app gave up and said something different
- A start that cannot succeed no longer leaves the app sitting still behind a notification that says the server is running. It says the start failed, tries again a few times, and if it still cannot start it says so on the notification and lets the next launch try afresh. Previously nothing further happened at all, and the only way out was to stop the server from a notification that claimed it was running
- Starting the editor again after it had given up no longer leaves it on the loading screen in front of a server that is working. A check belonging to the abandoned attempt went on running and could report the newly started server as not ready yet, which the editor waits on rather than opening
- A link opened from inside the editor no longer disappears when the bridge
  declines it. Anything the page called `window.open` on was handed to the
  Android bridge and then reported as opened whatever happened, so a URL the
  bridge would not take was swallowed in silence: no browser, no error, no
  second chance. The click now falls through to the WebView's own handling when
  the bridge does not take it, which is what puts the page in front of the user
- **Open in Browser** told you it had worked when it had not. The bridge method
  behind it returned nothing at all, so the relay carrying the answer back to the
  extension had no failure to report and answered success unconditionally: the
  command closed its input box, the extension's own error handler sat unreachable
  behind a promise that always resolved, and nothing opened. It now reports
  whether a browser actually took the URL, and a failure says so
- A browser launch that failed left the sign-in callback window open for ten
  minutes. The window is opened just before the browser is launched -- deliberately,
  because a browser that answers instantly would otherwise come back before the
  window it is judged against exists -- but nothing closed it again when the
  launch itself threw. For those ten minutes a `vscodroid://callback` from
  anything on the device was accepted with no sign-in in flight. The window is
  now put back to its previous state when the launch fails
- The **Browse Extensions** step on the Get Started screen could never be
  completed, so the walkthrough stayed permanently unfinished no matter what you
  did. It waited on a view identifier the workbench does not register, and an
  unrecognised identifier is not an error there -- the event is accepted, nothing
  ever matches it, and the step simply waits forever. It now completes on the
  command its own button runs. The fix also reaches installs that already have
  the old copy, which the previous extraction rule would have skipped

- Five checks that were supposed to stop earlier defects returning could not fail,
  and now can. Each was confirmed by breaking the thing it guards and watching it
  stay green, then breaking it again after the change and watching it report the
  fault by name. Nothing a user can see changes; what changes is whether the next
  release notices when one of these goes wrong. Two of them needed the code around
  them reshaped rather than the check tightened — a check that searches the source
  for a name can never tell a call whose answer is obeyed from one whose answer is
  thrown away
- The user guide's list of bundled extensions matches what ships. It named two
  that are not included — a theme and a Git annotation extension, the latter
  dropped from the bundled set — and listed the icon theme that *is* included
  under "extensions to install", so a reader was sent to install something
  already there and to look for two things that were never there
- The on-device toolchain checklist can be followed. Its five rows sent the tester
  to a Settings screen that does not exist — the toolchain screen is reached by
  touching and holding the app icon, which the app's own strings call the only way
  in — and expected `go build` to succeed, which cannot happen on Android. Go's
  compile step is now recorded as a known limit rather than a case that fails every
  time, so an unchecked box means untested instead of broken
- The Tailwind CSS language server is visible to the process monitor again, so it
  can be reclaimed when memory runs short and appears under its own name rather
  than as an unknown process. It had been recognised by the extension's folder
  name, and recognition moved to the program's own name without the entry for it
  being updated — leaving one of the bundled language servers outside the only
  mechanism that reclaims them
- A folder re-granted while the app is tidying up is no longer wiped. On launch
  the app reclaims the local copies of folders whose permission you have since
  withdrawn, and it decided what to reclaim from a single reading taken before it
  started. Reclaiming a large project takes long enough for you to reach the
  editor and grant a folder again in the meantime, and that folder was missing
  from a reading older than the grant — so its freshly synced copy was deleted
  under the editor holding it. Each candidate is now checked against the
  permissions as they stand at that moment
- A certificate store interrupted while being written no longer breaks HTTPS
  cloning permanently. It is assembled on first launch from the device's own
  trusted certificates and rebuilt only when those change, so a write cut short —
  by a full disk, or the app being killed — left a partial file carrying a fresh
  timestamp, which the freshness check then accepted on every later launch. Some
  certificates were present and the rest were missing, so `git clone` over HTTPS
  worked for some hosts and failed for others, with nothing pointing at the file.
  An interrupted write now leaves the previous store in place and rebuilds next
  time
- The on-device suite no longer passes git's HTTPS helper when the helper is not
  there at all. It tested for one specific failure and treated everything else as
  success, so a helper that had gone missing — one of the two regressions the
  check exists to catch — reported green, as did a device where the check could
  not run in the first place
- Renaming a folder inside a device folder no longer empties it. Android reports a
  rename as an unrelated delete of the old name and creation of the new one, with
  nothing connecting the two, so the delete removed the folder and everything
  under it from the device while the creation put back an empty one. Renaming
  `src/util` to `src/helpers` in the explorer, or `mv`-ing it in the terminal, left
  the device copy with an empty `helpers` and no files — they survived only in the
  app's own storage, which is reclaimed when the folder's permission lapses.
  A folder that appears is now copied across with its contents, which also fixes
  the plainer case of creating a folder that already has files in it. Symbolic
  links are not followed, since a synced folder is routinely a checked-out
  repository, and very large moves stop at a bound and say so rather than
  appearing to have finished
- Installed toolchains can be run. Ruby and Java installed, reported success and
  then refused to start: typing `ruby -v` gave `Permission denied` and exit 126.
  Nothing was wrong with the download or the file. Android refuses to execute
  *any* file stored in an app's own data directory, whatever its permissions, and
  that is where a downloaded toolchain necessarily lives — the directory the
  bundled tools run from is written by the package installer and cannot be added
  to. Measured on device: the same binary is refused through a symlink too,
  because the refusal follows the file rather than the path, which is why setting
  the execute bit could never have helped. Each toolchain command is now handed to
  the system loader, which is permitted to run it — arguments, quoting and exit
  codes all come back intact.
  **Go is still limited to what it can do without compiling.** `go` itself runs,
  but `go build` starts its own compiler and linker directly, and those starts are
  refused for the same reason with nothing in between to redirect them. That is a
  platform limit rather than something left undone
- **Serve on Network** now appears for people upgrading, not only on a clean
  install. An extension bundled for the first time had no entry in the manifest
  the workbench scans, and the reconcile that repairs that manifest could not
  tell "never shipped before" from "the user uninstalled this" -- both look like
  an identifier with no entry and a directory that has just been extracted -- so
  it took the cautious reading and left the extension inert. It was invisible to
  every existing user while working perfectly on any fresh device, which is where
  it was tested. The app now records which extensions it bundled last time, and
  an identifier it has never bundled cannot be one you removed
- The next VS Code upgrade will build. One of the twelve source patches had prose
  rewritten inside its body without its hunk header being adjusted to match, which
  leaves a file `git apply` refuses to read -- and the server build applies the
  patches with `set -e`, so it would have stopped there. Nothing caught it: the
  patch checks this repository runs inspect the *downloaded* server tree, so they
  answer whether a patch was applied, never whether it can still be read. Pull
  requests now parse every patch, which is the half of the question that can be
  answered without a VS Code checkout
- In landscape, the editor no longer extends under the punch-hole camera. The
  cutout is its own inset, separate from the system bars; portrait masked the
  gap because the status bar is at least as tall as the hole
- The Toolchains screen drew its title and back arrow under the status bar --
  colliding with the clock -- and its grid under the navigation bar. It now
  respects both, and an instrumented test pins it
- The first-run toolchain picker anchored its Skip and Continue buttons where a
  3-button navigation bar covers them, on a screen that is shown exactly once
- Status bar icons were invisible when the device was in light mode: the app is
  always dark, but the system bars followed the device theme, drawing a dark
  clock on a dark background. The bars are now pinned to light-on-dark
- Upgraded androidx.activity to 1.13.0 (re-applies edge-to-edge styling on
  configuration changes, which this app relies on -- it handles rotation
  without being recreated) and Material Components to stable 1.14.0. Play's
  "deprecated APIs for edge-to-edge" warning is triggered by these libraries'
  own compatibility code and remains -- expected; see docs/PLAY_EDGE_TO_EDGE.md
- Builds no longer re-download every bundled package from scratch each time. The cache meant to hold them pointed at one directory above where they are written, so it matched none of the 72 files there and never held a single one. Beyond the wasted bandwidth, that is a large part of why building the same release twice could produce different binaries: with nothing reused, every run resolved every version afresh
- The low-storage warning now names something you can actually do. It said "Clear caches in Settings" and there is no Settings screen in the app, so a user who was running out of space was sent looking for a place that does not exist. Two commands now exist and the warning quotes one of them exactly as the Command Palette lists it: **VSCodroid: Show Storage Usage** breaks down what is using space, largest first, and **VSCodroid: Clear Caches** deletes cached data and tells you how many bytes it freed -- a command that claims to free space without saying how much cannot be told apart from one that did nothing. Both were fully implemented on the Android side already and reachable by nothing: the relay that carries them to the editor had a branch for each, and no caller
- Tap targets stay finger-sized whichever way you hold the device. The roomier spacing for file tree rows, editor tabs and activity bar icons was applied by screen width, so turning a phone to landscape — the orientation you turn to for more room for code — dropped it back to the desktop sizing, while the finger doing the tapping stayed exactly the same size. It now follows the pointer rather than the screen: anywhere the pointer is a fingertip gets the roomier spacing, which includes touch tablets, and anywhere a mouse or trackpad is in use keeps the compact desktop layout. That last part cuts both ways — a device driven by a mouse in a narrow window, such as a Chromebook without a touchscreen, now keeps the compact layout where the old width rule would have given it the roomier one
- The session token the editor uses to call into Android is now required by every one of those calls. Twenty-two of the twenty-eight already took it and checked it; six took no token at all, so the rule the surface was built on held for most of it rather than all of it. All twenty-eight now check, and a test enumerates them so one added later cannot skip it
- The editor now starts without waiting on the launcher shortcut being published. Publishing it is a round trip to the system server, and it ran before the editor was handed off rather than after, so every launch paid for it first. Measured over twenty cold starts on an idle emulator, the delay before the launch was handed over fell from 50 ms to 15 ms, and from 129 ms to 24 ms at its worst. Those are floor figures — the phones this is for have a busier system than an idle emulator does
- The rule that decides which addresses the editor may open in your browser is now actually exercised by the tests that cover it. It allows plain `http://` only for a server running on your own device, and that branch had never run in a test — the URL parser it used is unavailable outside a real device, the failure was swallowed, and every case fell through to "blocked". So the suite could not have caught the rule being loosened to allow any site, and could not have caught it being tightened until previewing your own dev server stopped working either. The parsing now uses a library that behaves the same way on and off the device, and refuses anything it cannot read
- **Edits you had not saved back to a device folder are actually kept now.** The protection added for this checked file size before timestamps, and almost every edit changes a file's length — so almost every unsaved edit was read as "not a copy of what is on the device" and overwritten by the older version when the folder was reopened. The check now asks which side is newer first, and size only decides when both carry the same time. Copies are also written beside their destination and moved into place once complete, so a copy interrupted part-way leaves nothing behind rather than a short file that looks like an edit
- You can add and remove languages after first run again. Touch and hold the VSCodroid icon and choose **Manage toolchains**. The Language Picker is shown exactly once, and until now the screen it offered had no way in at all — no menu entry, no palette command, nothing — so whatever you picked in the first thirty seconds of using the app was permanent unless you cleared its data, and the picker's own subtitle told you the opposite. Someone who skipped the picker to get to the editor faster, or who picked Ruby and later wanted Go, had no way back. The entry point is a launcher shortcut rather than something inside the editor on purpose: reaching this screen matters most when the editor is the part that will not start
- **Open in Browser, Generate SSH Key, Copy SSH Public Key and About now exist.** All four have been advertised since the first release and none of them was ever in the Command Palette: they were registered through a loader API the editor does not ship, so every attempt failed silently and gave up after ten seconds without a log line. They are now contributed by the bundled bridge extension, which registers them the way any extension does. Copying the public key also no longer needs Android at all — it uses the editor's own clipboard
- Deleting the projects folder from a file manager no longer leaves the app permanently broken. It was created once per app version, so once it was gone the editor opened on a folder that was not there — an empty explorer, files that could not be saved, terminals starting nowhere — through every relaunch, until you cleared app data or the app updated
- Shortcuts that use a punctuation key now work when that key is typed on the on-screen keyboard. Holding Ctrl from the key row and typing a comma, a slash or a semicolon did nothing at all: the keystroke was swallowed and the Ctrl toggle cleared itself, so it looked as though the key row had misfired. Letters behaved correctly, which made the gap easy to miss. The on-screen keyboard reaches the editor by a different route than the key row, and that route worked out each key's identity from the character typed — which only lines up for letters and digits. For punctuation it produced an identity matching no key at all, so the editor had nothing to act on. Both routes now answer from the same table of key definitions, and that table gained the punctuation it never had: comma, full stop, hyphen, plus, asterisk, percent, question mark, caret and dollar. Symbols that need Shift on a physical keyboard now say so as well, so a shortcut using one is recognised as the chord it actually is. Ctrl+comma opens Settings, which was previously unreachable by touch — there is no comma on the key row either. Ctrl+space, which asks the editor for suggestions, was lost the same way and works now too
- Stopping the editor can no longer restart it. Whether a stop was deliberate is decided by a flag the shutdown sets, and nothing verified that it was set early enough — a reordering would have left the app running after the user believed they had closed it, holding the foreground service and spending battery
- Stopping the app from its notification no longer freezes the screen for several seconds, and could no longer show the "isn't responding" dialog. The stop waited up to five seconds on the editor server from the same thread that draws the interface, so anything you touched in the meantime waited too. Generating an SSH key can also no longer hang the dialog indefinitely if the tool stops responding
- Tap targets no longer depend on which way the phone was facing when the editor opened. The roomier spacing for file tree rows, editor tabs and activity bar icons was chosen once while the page loaded, from the window width at that instant, so opening in landscape left cramped desktop-sized targets and rotating back to portrait did not restore them — the spacing changed again only when something reloaded the page, such as opening a different folder. The width is now part of the styling itself, so it is reconsidered on every rotation and window resize, including moving in and out of a split-screen window
- **Opening a folder from your device now works.** "VSCodroid: Open Folder from Device" and "Open Recent Folder" hung for five seconds and then reported `Bridge timeout`, every time, on every device — so Downloads, USB drives and cloud folders could never be opened, and the only reachable workspace was the app's own projects directory. The extension that carries those commands was being loaded in the wrong place: it talks to Android over a channel that only exists in the editor's own page, but it was declared in a way that ran it on the server instead, where that channel reaches nothing. It now runs where its channel is
- The HTML, CSS and JSON language servers are now visible to the process monitor, so they count in the process view and can be reclaimed when they go idle. Their names in the monitor had never matched the processes VS Code actually starts, which made the three servers most likely to be running the three it could not see
- The editor's memory ceiling is now sized from the device instead of being the same number everywhere. Phones with 3-4 GB were given a limit that left the system no headroom and were killed and restarted repeatedly on projects that stayed stable on larger devices, while those larger devices never used the memory they had
- Switching to another app no longer kills your language servers. Android tells an app its window is hidden using a signal that numerically outranks its real out-of-memory warning, so every app switch was read as the device running critically low — and coming back meant waiting for completions, hovers and diagnostics to start over
- When the system kills the editor server — for running out of memory, or because the device hit its background-process limit — the log now says so. It previously reported a clean exit, then reported a crash and a restart one line later, so anyone reading the log to find out why the editor kept dying was told nothing had gone wrong
- Reopening a folder from your device storage no longer discards edits made since it was opened. Opening such a folder copies it into a working area the editor reads from, and that copy was rewritten from scratch every time — with no check of which side was newer — so any edit not yet written back was overwritten. A file is now replaced only when the version on your device differs in size, carries no timestamp, or is newer than the copy already held. Copies are stamped with the source's own time so the comparison comes from one clock, and a copy interrupted part-way is removed rather than left behind looking like a newer local edit
- First-run setup no longer gets stuck on the download screen. Cancelling a toolchain download from the Play notification, or Play reporting a pack it has nothing to say about, left the remaining downloads waiting on a step that had already finished — the screen sat there until you tapped Cancel, and the toolchains behind it in the queue were never installed. They are skipped now so the rest of the queue finishes, and the skipped one is named on the setup screen rather than failing silently
- **Everything you set was forgotten on every cold start** — editor settings, keybindings, layout, unsaved file backups, signed-in accounts, and each extension's saved state. The workbench is served from a local address whose port changed on every launch, and the browser keys its storage to that address, so each start opened an empty one. Nothing in any log pointed at the cause. The port is now kept between launches
- **None of the app's own settings ever took effect** — the theme, the terminal profile, the Python interpreter path, and the rest were written to a settings file the editor does not read. There was nothing to notice: the file existed, parsed, and held the right values. They now go where the editor actually reads them
- **Every folder opened in Restricted Mode**, which stops most extensions from doing anything, and the setting meant to turn that off has never worked on this platform. The server's own switch is now used instead, so extensions activate normally
- Building from source now reports a missing Android NDK immediately instead of after the downloads, which took about twenty minutes to reach a prerequisite that was knowable at the start. Set `REQUIRE_NDK=0` to check an environment you do not intend to build in
- A toolchain installed by an earlier version stayed as it was installed, so improvements to how toolchains are packaged never reached it. Go was the visible case: only the commands its manifest named were marked runnable, and the compiler and linker it starts were not among them. Upgrading now repairs that in place, without re-downloading anything. Commands that were never installed at all — `rake` on an older Ruby install — still need the toolchain reinstalled. This was necessary and, on its own, not enough to make a toolchain run at all: see the separate entry on running toolchain commands through the system loader
- Following the contributing guide's build steps now produces the same app CI builds. Three steps were missing — copying the server tree into the app, the musl loader, and the compatibility shim — so the documented sequence produced a build that installed and opened with no editor in it, a Claude Code CLI that could not start, and native add-ons that failed to load. The "run it all at once" script ran none of the download steps at all, and the setup script asked for a tool the project does not use before pointing at a component that was abandoned for crashing. A check now fails the build when the documented steps and the ones CI runs stop agreeing
- A Claude Code wrapper you pointed somewhere yourself was overwritten on every launch; only paths this app wrote are refreshed now. Settings and the bundled-extension list are also written so an interrupted write leaves the previous file intact rather than an empty one
- Uninstalling the Ruby toolchain no longer removes a library Python depends on. The two share one library directory, and the uninstall deleted everything the toolchain's manifest named — including a file the app itself ships — leaving `import ctypes` and pip broken until the next app update. An uninstall now leaves alone anything the base installation also provides
- Plain HTTP requests to an IPv6 address through the local proxy failed with a bad-gateway error; only tunnelled HTTPS handled those addresses correctly
- Python's compression and database modules were dead on device: `import bz2`, `import lzma`, `compression.zstd`, `curses.panel` and `dbm.gnu` all failed with a missing-library error. Three of the libraries they need were never bundled, and two were bundled under a name nothing looks for
- A file being unpacked from the app when something goes wrong — running out of storage, most likely — no longer leaves a half-written copy in place of the real one. The unpacking either completes or leaves what was there before, so a later start can retry instead of finding a file that looks present and is not usable
- Python stopped working after an app update in some cases: the interpreter ships inside the app and is replaced every time, while its runtime library and standard library are unpacked only when the version number changes, so the two could end up coming from different builds. The app now notices that at launch and repairs it, and clears out standard libraries left behind by earlier versions
- **Git over HTTPS now works.** Cloning, fetching and pulling from an `https://` remote failed on every device with "cannot exec 'git-remote-https'", because the helper git runs for HTTPS was installed where Android does not permit execution; it now ships beside the other bundled tools. Behind it a second failure waited — the bundled curl looked for its list of trusted certificate authorities at a path that does not exist here, so the connection was refused before any certificate was checked, and that list is now built from the device's own trust store
- **The Java toolchain could not start on devices with 16 KB memory pages.** A library the JDK core depends on was built for 4 KB pages, so `java` failed to load before it ran anything; it is now built correctly. Three further JDK libraries that could never load at all — their dependencies were never included — no longer ship
- **`go build` would have failed with a permission error in the next release.** The Go toolchain marks only the commands named in its manifest as executable, and the manifest named just `go` and `gofmt` — but `go` compiles nothing by itself, it runs the compiler, linker and assembler that ship beside it. Those arrived unrunnable. The manifest is now built from the toolchain itself, so every program it ships is installed ready to run
- **The Ruby toolchain was missing six of its commands**, `rake` among them — so `rake` and `bundle exec rake`, how most Ruby projects are built, answered "command not found", and the debugger `rdbg` was absent too. The toolchain shipped a list of commands fixed when it was written; it now installs whatever the Ruby release actually provides, so `rake`, `rdbg`, `rbs`, `racc`, `syntax_suggest` and `typeprof` are there, and anything added later arrives on its own
- Ruby's `fiddle` could never be loaded: the library it links was not part of the toolchain download, so anything reaching for it — directly or through a gem — failed at `require`. The library now ships with the toolchain
- Git subcommands pointed into a previous installation after the app was updated, and were repaired only on a fresh install
- An emergency port picked when the usual range is full is no longer remembered. It came from the range the kernel hands to outgoing connections, so a later launch would often find it taken and move the workbench to a new address — emptying secret storage and every extension's saved state, and never moving back once the congestion cleared
- Tunnelling to an IPv6 address through the local proxy failed: the target was split on every colon, so `[::1]:443` was read as a host named `[`. A malformed port in the same position could take the server down at startup instead of being refused
- Closing a tab or cancelling a download mid-transfer left the local proxy still pulling the rest of it from the network, with nothing on the other end to receive it. Each abandoned transfer held a connection open until the far side gave up
- The placeholder page shown before the editor is installed pointed at a build script that no longer exists
- Legal notices listed fixed versions for Node.js, Python, Bash, tmux and Make, none of which this repository pins — they come from the package index at build time, so the numbers had been wrong since the runtime changed
- Chat panels were unusable: the extra key row covered the bottom of the page — exactly where VS Code anchors the chat toolbar — so the model picker and Send button could be seen but never tapped
- Claude Code sign-in died with "Socket is closed": Node abandoned each connection attempt after 250 ms, which the API's handshake regularly exceeded from a phone
- Prebuilt glibc native addons could not load on Android 13 at all: the compatibility library referenced a symbol that does not exist before Android 14, so the loader rejected the library itself on the minimum supported version
- The glibc shim's ctype table misclassified five of twelve character classes, and its `environ`/`stdout`/`stderr` exports loaded as NULL
- Two app instances could run first-run setup concurrently; setup is now single-flight
- Bundled extensions updated by an app upgrade are visible again after the manifest is reconciled, and uninstalling one now sticks across upgrades
- The web walkthrough greets users with VSCodroid branding again, and the hamburger menu returned to touch-friendly sizing — both regressions from the build pivot
- Native terminal and file-watcher addons are built from the same versions as the JavaScript they ship beside, and the build now fails on any mismatch
- Terminal profile picker was empty, leaving no way to switch terminals ([#3](https://github.com/rmyndharis/VSCodroid/issues/3))
- App froze and had to be force-restarted after the server process was killed — automatic recovery never actually ran
- A server restart now returns to the folder you had open instead of the default projects directory
- A WebView rebuilt after a renderer crash no longer comes back without its Android bridge
- Launching no longer crashes outright if refreshing tool paths fails
- Comments and formatting in `settings.json` now survive the refresh of bundled tool paths
- Build and release workflows no longer fail when the runner's package index is out of date
- Cold start no longer crashes while the WebView still shows its placeholder URL — thanks [@4in4in](https://github.com/4in4in) for the fix ([#6](https://github.com/rmyndharis/VSCodroid/pull/6))

- Saving a file inside a subfolder of a device folder now actually reaches the device. The mirror was watched at its top level only, so editing `src/main.ts` updated the app's copy and left the original untouched — with no message, and no way to notice until you opened that folder somewhere else
- Closing a device folder can no longer close the app. Stopping the folder watcher interrupted the thread doing the writing, and that interruption ended the process; the same path also discarded writes still queued, so edits made moments before switching folders were lost outright
- Workspace settings and dotfiles now travel both ways. `.vscode/`, `.gitignore` and `.editorconfig` were copied in from the device but never written back, so editing them in the editor looked like it worked and changed nothing
- Files you delete on the device no longer reappear. Re-opening a folder only ever copied and overwrote, so a deleted file came back from the stale copy — and editing it wrote it back to the device. Files that exist only in the app, or that you have edited since the last sync, are never removed
- Renaming a file onto an existing name no longer leaves a second copy called "file (1)", and copying a file in from the device no longer uploads its half-written state. Opening the list of recent folders no longer deletes the copy of the folder you currently have open
- The Stop action on the notification now stops the server. While the editor was open — which is nearly always, including when it is merely in the background — it stopped nothing, removed no notification, and said nothing about it
- When the server has crashed too many times to keep retrying, the notification now says so instead of continuing to report a running server. Relaunching the app also recovers properly: the service had never marked itself stopped, so a relaunch attached to something that believed it was already running and the editor waited for a signal that would never come
- A crash in the page renderer during startup no longer takes the whole app with it. Crash recovery was installed together with the editor bridge, which waits for the server, so for the entire startup window nothing was there to handle it. The recovered window also keeps its spacing below the status bar, which the recovery path had been dropping
- Crash logs are written again on Android 13, 14 and 15. The report asked the thread for an identifier that only exists on Android 16, inside a catch that swallowed the failure — so no log was written, no crash dialog ever appeared, and generated bug reports carried an empty crash section on most supported versions
- Signing in to an extension, when Android has closed the app while you were in the browser, now tells you what happened instead of silently doing nothing. It cannot be resumed — the editor keeps the pending request in memory only, so it belongs to the page that started it — and you are asked to sign in again rather than left waiting
- The backslash key does nothing no longer. It is reached by holding `/` on the second page of the key row; each key press is delivered as a small script, and the one built for a backslash was not valid, so it was discarded before a line ran and nothing reported the error
- Removing a toolchain from inside the editor works. The editor passes the full pack name and removal recognised only the short one, while installing had accepted both all along
- Cancelling one toolchain download no longer cancels the others queued behind it, and starting a new one no longer discards a cancellation the running download has not yet noticed
- Installed toolchains can no longer disappear from the app while their files stay on disk. The record of what is installed was rewritten by emptying the file and filling it again, and anything interrupting that left something the app reads as "nothing installed" — taking with it the list of files and links that removing a toolchain needs
- A toolchain download that ends early is now refused rather than installed as though it were complete, where the server tells us how much it is sending. Generating an SSH key can no longer freeze every other call the editor makes into Android: the time limit was placed after a read that only finishes when the program does
- Installing a toolchain from inside the editor no longer stops with nothing shown on devices that installed the app from Google Play, where a large download needs a confirmation that nothing was listening for
- A server killed while it was rewriting its own configuration no longer leaves the app unable to start. The file was rewritten by truncating and refilling it, and an interruption there left something that failed to parse on every subsequent start, with nothing inside an installed version able to repair it
- Language server processes are identified correctly again. Three of the bundled ones were never recognised at all, and the check meant to guard that list reported them fine throughout because it compared against a different form of the name. The same matching could also mistake one of your own scripts for a language server and shut it down under memory pressure, if its path merely contained a name like `eslint`
- The process count shown in the status bar includes the server's own bootstrap process, which the system counts against the same limit. Every warning threshold had been firing one process late
- A folder synced from a device now copies only into its own mirror directory. The path for each copy was built from the name the document provider reported, and a name that was not a single path segment could place the file outside the mirror. Names that come from the platform's own provider are derived from real filenames and were never affected; the check matters for providers that relay names from elsewhere


- Extensions maintained by this project now update when the app updates. Previously an extension whose code changed without a change to its version number reached only new installs: what to unpack was decided from the version in the folder name, so a device that already had that folder unpacked nothing and kept the older copy indefinitely. Extensions from the marketplace are unaffected and are not re-copied — their version always changes when their contents do.

- A Python installation left behind by an earlier version is now removed even when the device is short of storage. The clean-up previously ran only once there was already room to unpack the replacement, which is the one situation where it was not needed. On a full device the old files stayed, the space they held was exactly what the new version needed, and Python could remain unavailable indefinitely.

- An interrupted save no longer leaves settings or shell configuration permanently broken. If the device ran out of storage, or the app was stopped mid-save, part of a file was left behind — and neither the app nor a retry of setup would replace it, because both checked whether the file existed rather than whether it was complete. Editor preferences could come back as a fragment of their defaults, or every new terminal could open on an error, with no way back short of clearing app data. These files are now written in full or not at all, so an interrupted save leaves the previous version in place and the next attempt succeeds.

- The list of bundled extensions can no longer be lost. A write of that list interrupted part-way previously became permanent: later launches tried to repair the list rather than rewrite it, could not read it, and left it alone. On an upgrade there was a second route to the same place — the app recorded which extensions it had shipped before confirming the list had been written, and an extension named in that record but missing from the list reads as one you had removed, so it was never shown again.

- Accented and other non-ASCII characters in `.bashrc` are no longer damaged when the app updates its own section of that file. The app rewrote the whole file to refresh the prompt, and any byte it could not read as UTF-8 came back as a replacement character, so a Latin-1 accent in a comment or an alias was silently destroyed.

- A first run that cannot unpack the editor's own files now stops and offers to retry, instead of finishing and reporting success. Previously a file lost during setup left an app that opened but could never start its server, and it would not try again until the next app update. On a device that is still short of storage the retry now reports the storage problem rather than repeating the whole unpack.

- An installation already left broken by an interrupted setup now repairs itself on the next launch. Where a shell or settings file had been left empty or cut off part-way, nothing would replace it, because every writer checked only whether the file existed. Files that were partly written but still plausible are left untouched, so nothing you edited yourself is overwritten.

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
