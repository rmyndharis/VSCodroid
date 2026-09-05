# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- A device folder holding one `.code-workspace` now opens as that workspace. Android's picker can only hand back a folder, so a workspace on device storage was reachable only by finding the file in the explorer and opening it from there.
- **VSCodroid: Open Recent Folder** is now on the remote indicator in the status bar. Typing its name into the Command Palette was the only way to reach the device folder picker, which is why people could not find it.
- The editor's interface now follows your phone's language: Chinese (Simplified and Traditional), Czech, French, German, Italian, Japanese, Korean, Polish, Portuguese (Brazil), Russian, Spanish and Turkish. Nothing to install, and a language nobody has translated the editor into stays English.
- The app's own screens are translated into those same languages, and Android's per-app language picker now lists them.
- VSCodroid's own commands, settings and the Get Started walkthrough are translated into the same thirteen languages as the editor.
- The user guide covers installing an extension from a VSIX file, and what running and debugging does and does not do on the device.

### Fixed

- Running and debugging a file works on the device. A launch configuration started a session, put the debug toolbar up and then never ran the program, with nothing on screen to say why. The editor built its terminal command around `/usr/bin/env`, which Android does not have, and behind that the debug adapter started its own helper processes with an environment too small for this device's Node to start under.
- A file you changed on the device is no longer overwritten when the app reopens the folder after an upload was cut short. The app took its own record of the unfinished write as proof that nothing else had touched the file, and replaced it. The check now reads both copies rather than trusting the size the folder provider reports, so a document the interrupted write had emptied is recognised instead of leaving a spare copy of the file in your folder.
- A setting you change in the Settings editor's Remote tab stays changed. The pass that removes the app's own old preferences was meant to run once and ran on every launch, so it deleted a matching setting you had written yourself.
- A file on your device is no longer overwritten by a later save when the app could not read it while opening the folder. The refusal protected it only for as long as the sync ran.
- Deleting a folder from the terminal no longer takes the `.git` or `node_modules` the app never copied into its own local copy.
- The Retry button survives a crash. If the editor's page crashed after the server had given up, it was replaced by a connection error with no way back.
- Opening a device folder waits for the editor to be ready, instead of loading a connection error when the server stopped during a long copy.
- The page shown when the server gives up can no longer be held back by a browser prompt, which left the app with no control able to start it again.
- A setting you change is the one that applies. The app wrote its own preferences into a file the editor ranks above your settings, so changing the theme, word wrap or the minimap in Settings did nothing, with no error to say why. Those are defaults now, and an existing install has the old overrides removed once.
- A folder you closed stays closed on the next launch. It was only remembered as closed for as long as the editor stayed open.
- Opening a second window reuses the editor's own window. It handed the editor's address to the device browser, which answered Forbidden, and left a blocked-popup message over the editor.
- The setting that closes the side bar when you open a file can be changed in Settings. Its row offered only an "Edit in settings.json" link while its own description told you to set it there; it is now a choice of auto, on or off, with auto following the screen.
- A setting you change in the Settings editor is no longer overridden by a preference a much older version of the app wrote and nothing ever removed.
- Translated string bundles are written only after every language has been fetched and checked, so an interrupted build cannot leave the app holding two VS Code versions of them at once.
- A toolchain install that runs out of storage while unpacking now says so. It reported a download failure and asked you to check your connection, so the retry was on wifi rather than on free space.
- Reinstalling a toolchain over one you already have reserves the space the unpacking actually needs, instead of passing the check and running out of disk after the download.
- A toolchain install interrupted by the system is repaired on the next launch, and an install that finds nothing to install no longer discards the record of an earlier one.
- The local copy of a device folder can be reclaimed again after a copy was interrupted. A half-written file the app left behind counted as work the device folder did not have, so removing the copy was refused with a warning that it held files which are not copies.
- Repairing an upload that was cut short no longer copies a large device file into the app's own storage. The size limit that keeps large files out was applied after that step instead of before it.
- A toolchain install the system stops part-way through no longer leaves its half-copied files behind with nothing able to remove them; the next launch gives the space back.
- The first-run progress screen no longer reports Failed for a toolchain that another install is already putting in place.
- Sizes are counted the way your phone counts them. Every figure the app showed divided by 1,048,576 and wrote MB, including the one asking you to free space, so freeing exactly what it asked for left you about 5 percent short and it refused again. Nothing grew: the same files now read about 5 percent larger.
- An Escape the editor leaves unhandled is no longer handed back to Android. Some keyboard layouts answer it with a Back press, which sends the app to the background mid-keystroke.
- Opening a workspace file no longer costs a filesystem check on every resource the editor loads, and a workspace file that is briefly absent while it is saved no longer cuts off every resource beside it.
- The address of the page is no longer written to the system log on every load. It carried the full path of the folder you had open.
- Edits made in a workspace opened from a device folder now reach the device. Nothing was syncing them back, so they stayed in the app's private copy with nothing on screen to say so.
- A workspace is reopened on the next launch and survives an editor crash. Both dropped you into the default folder, and opening a workspace file looked like it had loaded an empty project.
- A folder whose own name ends in `.code-workspace` opens as a folder. It was sent to the editor as a workspace, and an unreadable workspace opens an empty window, which every later launch reopened because the folder had already been remembered.
- An extension webview's resource requests inside such a folder stop at the folder itself. The root published for it was the folder above, so files sitting beside the folder you opened could be served too.
- Closing the folder now survives an editor crash. It reopened the workspace you had just closed.
- A file changed on the device is no longer replaced by the editor's copy when the sync record cannot account for it. The device version is set aside beside it first, and the write is held back if it cannot be read.
- Installing a toolchain again after one failed part-way now asks only for the space the copy needs. The check charged for bytes the new copy was about to write over, so a device already holding most of the tree was refused every time.
- The badge that says a modifier is still held is readable against the row it sits on. It was drawn in the accent blue, which this project's own contrast rule counts as too faint for text that size.
- The bug report no longer says credentials were removed. It names which shapes are replaced and asks you to check, because a secret that arrives as an ordinary word cannot be told from one.
- A file deleted in the editor that the editor had never read is kept in the device folder, and the notice now names it. It was kept only while the device could be asked: a lookup that failed counted as "already gone", and only an unread folder had a guard of its own.
- An install that copies a toolchain and then cannot record it no longer leaves the copy behind. Roughly 155 MB stayed with nothing able to name or remove it, on the full disk that caused the failure.
- A heap ceiling set in settings is read past strings and comments. A glob such as `**/*.log` in an earlier value swallowed the setting, and one commented out could be applied instead.
- A sign-in waiting for its callback is no longer dropped by a single address naming many requests. The record filled and evicted the entry the sign-in in flight was waiting for, and it hung with no message.
- First-run setup writes its unpack to the medium before recording the run as finished. Losing power in that window left the app marked set up with the tail of the unpack missing, and nothing looked again.
- Leaving the editor while a device folder is opening no longer leaves a file watcher running on the folder you closed. It kept writing the mirror back to the device for the rest of the session, and nothing could stop it.
- The soft keyboard stays down until you tap into text. Opening a file, or the Explorer, used to raise it over half the screen.
- The activity bar is narrower, and on a phone the file tree closes when you open a file. Opening a file from the tree left the editor 193dp of a 411dp screen and now leaves 375dp. `vscodroid.layout.autoHideSideBar` turns the closing off; it is on for phones and off for tablets.

### Changed

- A patch file whose name the manifest cannot key now fails the build. It was applied to the server tree and then left out of every check that tracks patches.

## [1.2.0] - 2026-08-24

### Added

- Toolchain commands now work when a program calls them, not only from bash. Tasks, `make` recipes and extensions previously failed on a correctly installed toolchain.
- Device folder copies can now be listed by size and removed one at a time. Removing one that holds files not on the device deletes them, so it asks first.
- The editor server's memory ceiling is now settable from settings. It is clamped to what the device can hold, and turns itself off after repeated crashes.
- git now trusts certificate authorities you installed through Android Settings. The bundle is rebuilt at launch when that store changes; pages in the editor still use system roots only.
- Bug reports now carry the server's own output. The report always had a section for it, and nothing ever wrote the file it reads, so it was always empty.
- The gesture trackpad now offers four accessibility actions to move the cursor. A drag was the only way to send an arrow, and a screen reader cannot drag.
- Long pressing a key through a screen reader now opens its alternate characters. The layer needed a finger held on the key, so `'` and `\` were unreachable.
- Ctrl+Enter and Alt+Enter now work from the extra key row. The soft keyboard reports Enter as an edit rather than a key, so a latched modifier was spent on a plain newline.
- A closing parenthesis is reachable by long pressing the `()` key. Nothing else on the row could type one.
- The Toolchains screen has a Command Palette entry, **VSCodroid: Manage Toolchains**. The launcher icon's shortcut was the only route to it.
- **VSCodroid: About**, which carries the licence notices and the written offer of source, is now on the remote indicator in the status bar as well as in the Command Palette.
- Files served to extension webviews answer byte-range requests, which is what seeking in a media preview needs.
- The release build can be exercised without publishing one. It cannot publish: the job that writes is gated on the trigger, so a dispatched run reports green having released nothing.
- A unit-test coverage report, on request: `./gradlew :app:createDebugUnitTestCoverageReport -PvscodroidCoverage`. Nothing gates on the figure and the suite CI runs stays uninstrumented.
- The storage screen has a Projects row for the workspace; an install that keeps it on shared storage now counts it in the total.
- Toolchain environment variables reach tasks, `make` recipes and extensions for a toolchain installed while the editor is open; they used to reach only bash until the server restarted.

### Changed
- The toolchain picker is offered until you answer it. An interrupted first run used to skip it permanently, leaving the launcher shortcut as the only way to add a language.
- Packaging now checks every bundled binary is 16 KB page aligned, so a build that could not load on Android 16 fails at build time instead of on a device.
- Toolchain cards now quote sizes in the same unit as the rest of the app, so a card and the storage screen no longer disagree by five percent.
- The secondary side bar, which holds the chat view, starts closed on every install, new or upgraded, so a phone-width editor is not half covered on a first run.
- The process counter no longer shows a warning on an untouched install. Its threshold sat exactly on what the app costs when idle, so it was always lit.
- Download outcome messages and the About dialog's unknown-version wording are now translatable; both were written in code and stayed English in every language.
- Long-press alternates now carry spoken, translatable names, so a screen reader can tell the apostrophe from the backtick instead of reading the bare glyph.
- The key row now says which page is showing, and which modifier is held, instead of leaving unlabelled indicator dots.
- The key row splits into more pages on a narrow phone, so no key falls below the 48dp touch target: five pages at 411dp and above, six at 360dp, seven at 320dp.
- Audio and video in the editor start only on a tap, so nothing can begin playback in a frame you have not touched. `mediaPreview.video.autoPlay` no longer has any effect.
- Idle language servers are also shed as the app nears Android's process limit, not only when the system reports memory pressure.
- A toolchain download that drops now resumes, so a lost connection costs the bytes it did not get rather than the ones it did.
- The onboarding screen, the extension reload prompts and the profile-switch dialog name VSCodroid instead of Visual Studio Code.
- The sign-in callback page is branded as VSCodroid and no longer carries the VS Code logo.
- About 4.8 MB comes off the download: the base module shipped a second copy of the workbench bundle that nothing loaded.
- Toolchain installs record Java 17 at its measured unpacked size, so the storage checks and the card no longer understate it by about 9 MB.
- Idle toolchain file work threads are released, so repeated launches no longer leave one parked per launch for the life of the process.
- The splash icon is no longer announced by a screen reader, which repeated the app name already rendered as text directly below it.
- The start summary in the log and in bug reports now names the requested heap ceiling and says clamped only when the value was actually reduced.
- Bug reports always carry a server-log section, saying explicitly when no server output was recorded instead of dropping the section without a word.
- The server log is written and read under one process-wide lock, so a report taken while the file is being rotated is no longer short, empty or interleaved.
- Packaging now refuses a build whose bundled binaries are incomplete, so a toolchain command cannot reach a device resolving to a file that was never produced.
- The privacy policy now lists the two permissions the manifest merger adds from libraries, matching what the Play Store listing shows, and a check holds it to the manifest that ships.
- The privacy policy now describes the sign-in callback window as the app enforces it: matched against the specific sign-in that produced it, not against the last browser launch.
- The privacy policy and the user guide say a new install keeps its projects in internal storage, and the guide's rescue routes no longer point at a path a new install does not use.
- The document-date and plain-punctuation checks now run on pull requests and on tags alike, so a documentation change no longer surfaces its first failure as an aborted release.
- The process monitor's status bar no longer shows an internal identifier where a readable label belongs, and drops an entry for a process type the editor stopped creating.
- The extra key row's legends are lower case, `tab`, `esc`, `ctrl`, `alt`, `shift`, `home` and `end`, the way a hardware keyboard prints them; `F1` to `F12`, `PgUp` and `PgDn` are unchanged.
- The Gradle distribution is pinned to its published digest, and a build without one is refused. It was the only thing entering the build with nothing checking it.
- The instrumented tests are compiled at tag time as well, so a tag cut from a branch that reached no other workflow cannot ship a suite that does not build.
- The instrumented suite's own README is held to the sources it describes; its test count had been wrong twice.

- Documentation, code comments and the Get Started walkthrough now use ordinary punctuation throughout, and a check keeps new text consistent with it.
- Every dialog, toast and spoken description now comes from a string resource, so the app can be translated. No translation ships yet; sixty-nine texts were unreachable to one.
- A build that packages the app now fails loudly if the checks guarding its bundled tree have come unattached, instead of going quiet and shipping an unchecked tree.
- Two accessibility guards stopped passing on code that is commented out, and one no longer loses its scope to a brace inside a comment.
- The user guide no longer promises that certificate errors should not happen. It says which roots the bundle carries and that npm does not use it.
- The user guide separates a package whose prebuilt binaries have no Android build from one that needs a compiler. The two fail differently and only the second has an alternative.
- The picker's checked state and the Toolchains back-arrow label are now pinned by tests. Both are invisible to a sighted reviewer, so either could be deleted without a symptom.
- Editing a layout or a string no longer leaves the unit suite up to date. Two suites read those files, and both were skipped on exactly the edits they exist to catch.
- A test no longer states that AGP builds no release unit test task. It does build one, and it has run; what is true is that no workflow invokes it.
- The security document no longer calls the extension host a sandbox. It is a fault boundary, and an extension reaches app-private storage exactly as the app does.
- The landing page quotes the storage figure the app computes and says extraction repeats after an update, which every other document already said.
- The design documents now describe the build that ships: two on-demand toolchains, terminals that spawn bash on a real PTY, and how the server is actually patched and built.
- Seven documents named a "Settings > Toolchains" screen the app has never had. They now name the real route, the launcher icon's **Manage toolchains** shortcut.
- Three documents no longer list file-type "Open with" intent filters as shipped. A `content://` URI has no POSIX path, so every save would reach a copy.
- The security document no longer lists two permissions the manifest has never declared, and now separates what this app declares from what the manifest merger adds on top.
- The key row is documented as the five pages it is, with no arrow buttons: a drag on the gesture trackpad is how a finger moves the cursor.
- The user guide says extraction repeats after an app update rather than happening only once, and names the nineteen colour themes that ship instead of claiming none do.
- The technical specification matches the code on WebView settings, the app version, and the 28 environment variables the server process receives before any toolchain adds its own.
- The backup test rows name the path that is really in the payload, and add the connection token and preferences as the near misses worth checking.
- Development no longer needs a physical device in the requirements document: an arm64 emulator works, which is what the contributor guide already said.
- A note in the implementation plan rendered as four full-width headings, because its lines began with `#` outside a code block.
- Five section cross-references pointed at sections that do not exist or do not hold what the pointer promised.
- The milestone checklist no longer names Python 3.12 or a `vscode-web` asset directory. Neither has been true for a long time; the Python version is resolved at build time.
- The install figures match what the app computes: the storage gate quotes its own figure, and the core download is about 270 MB rather than 135 MB.
- Two passages blamed a `noexec` mount for scripts not running under `filesDir`. It is SELinux, and a `noexec` mount would also block the native addons loaded from there.
- The first-run extraction row in the device checklist records an elapsed time instead of failing against a 15-second target nobody has ever measured.
- Two documents said the server build clones VS Code at the tag in `VSCODE_VERSION`. It checks out the commit in `VSCODE_COMMIT`, so a moved tag cannot be followed silently.
- The toolchain check now reads the design documents too, and no longer skips a whole line because it mentions Google Play, which is how the sentences describing toolchain delivery went unread.
- The shrinker runs on pushes to main, not only on pull requests, so a configuration change reaches a minified build the same day.
- The build moves to Android Gradle plugin 9.3.1 and Gradle 9.7.1, which turns on optimized resource shrinking. Play flagged the old configuration for memory and performance.
- Kotlin now comes from the Android Gradle plugin rather than a separate plugin, so the compiler is 2.2.10 and the version catalog no longer names one.
- 97 unused Material and AndroidX resources no longer ship: the date and time pickers, the navigation drawer, fragment transitions and the legacy notification templates.
- A toolchain pack that ships a library its manifest does not list now fails the build, instead of redistributing it with no licence notice.
- The zlib notice names the Java and Ruby toolchains, whose libraries link the copy the base app ships.
- The weekly upstream patch check prints its verdict on the run summary, so a queued rebase is visible without opening the log.
- A build that packages the app without the checks deciding whether its bundled tree may ship now fails, instead of producing an unchecked APK.
- The three deprecated edge-to-edge APIs Play reports are gone. Bar colours, the display cutout and bar contrast move to theme attributes; edge-to-edge and bar icons stay in code.
- The licence check matches each copyleft component against the source offer's own entries. A deleted offer used to pass because a neighbouring entry mentioned the project by name.
- The toolchain check finds the welcome extension by name rather than a pinned version, so the next ordinary bump of that extension no longer fails the build.
- The translatable-text check reports the test it actually applied instead of claiming no user-facing text sits outside the string resources.
- Two tests pinning the write-back notice no longer pass when its wiring is commented out rather than deleted.
- The Kotlin plugin's own build directory is ignored, so a build no longer leaves compiler scratch sitting in the working tree.
- The release plan carries a checklist for an ordinary release, and tagging refuses a version this file has no section for.
- Packaging checks that the documented list of bundled binaries matches what ships, so a binary added without its documentation fails the build.
- The testing document gives the command that measures the suite instead of a figure that goes stale the next time a test is added.
- Packaging refuses a release whose bundled extension tree failed to build, which previously deleted the extension and reported success.
- Kill Idle Servers is removed, along with the automatic kill under memory pressure and at 24 processes: a killed language server is restarted by its extension within a second, so no slot was ever freed.
- Claude Code works on Android 13 and 14 again. Its runtime calls `epoll_pwait2`, which an app may only use from Android 15, and the kernel stops the process there; VSCodroid now answers that call with `epoll_pwait` so sign-in and everyday use work on the older releases too.
- Material Icon Theme is no longer bundled. It was 5.9 MB that nothing enabled: no default setting selected it, so it shipped inactive unless the user picked it. Install it from the Extensions view to keep using it.
- The process tree marks language servers idle after five minutes without CPU and says that disabling the owning extension is what frees a slot.
- The toolchain picker's Skip is a 48dp button, so a screen reader announces it as a control.
- The "Starting server..." placeholder and the repeated-crash explanation are string resources, so translations reach them.
- Build: node-addon-api is fetched with npm pack and pinned by sha256 per addon, like the addon sources.
- Build: verify-android-elf.py refuses an executable naming a non-Android program interpreter in every mode, not only under --tree.
- Build: verify-server-tree.py requires the GitHub Copilot CLI licence text in the server tree and checks the listening line readiness waits for.
- Build: bundleNotices fails the build when a notice or licence text is missing instead of packaging a shorter set.
- CI: release gates must be an equality on github.event_name with no top-level ||, and each gate script carries a --self-test that lint.yml runs.
- CI: the workflows set up Node 24, the major the app ships, and check-build-steps.py holds them there.
- CI: build-vscode-oss.yml builds with a read-only token and publishes from a separate write-scoped job.
- Tooling: deploy.sh always builds (SKIP_BUILD=1 to install as is) and refuses more than one attached device unless ANDROID_SERIAL is set.

### Fixed
- Deleting a folder from the terminal or a script no longer removes files on the device that were never copied into the editor. Such a folder is kept on the device and a notice says so.
- A page can no longer navigate the editor to the app's own `vscodroid://callback` and have it accepted as a finished sign-in. The link route now refuses that address, as the browser route already did.
- The primary side bar can be resized on a phone in portrait. Its minimum and its reachable maximum were the same number, so the divider had nowhere to travel and dragging it did nothing.
- Dragging inside an open submenu scrolls it instead of closing it. The gesture reached the menu behind it as well, and a menu that scrolls dismisses the submenu anchored to it.
- Menu dividers are drawn as lines again, not as blank bands. The touch-target floor applied to them too, which added about 400px to the File menu and is most of why it ran off the screen.
- View dividers are 20px rather than 4px, which is the size the editor already uses on the other touch platform.
- The default workspace is on internal storage. Shared storage cannot hold a symbolic link, so `npm install` failed on the first package shipping an executable, and npm blamed a path in `node_modules/.bin`. An install that already has projects on shared storage keeps them, and npm now says which of the two it hit.
- Extensions survive a dropped connection. Backgrounding the app for a minute killed the extension host, and the workbench came back looking healthy with nothing loaded.
- A first run interrupted part-way keeps the files it already unpacked, instead of writing the whole 800 MB tree again on the retry.
- The storage check reserves enough for the filesystem overhead of an 800 MB tree, so an install can no longer pass it and then meet a full disk part-way through.
- A latched Ctrl or Alt no longer turns a word committed in one keystroke, from a prediction chip, into one shortcut per letter and no text at all.
- The key row shows a latched modifier from every page, not only from the page the modifier keys are on.
- The alternates popup no longer closes the soft keyboard, and it stays on screen at the right-hand edge.
- Keys on the extra key row and in the alternates popup are announced as buttons by a screen reader.
- A second finger on the gesture trackpad no longer jumps the caret, and lifting a finger the drag never belonged to no longer ends it.
- A toolchain install that meets a full disk while copying says so instead of blaming the connection, and takes back the partly copied tree so the retry has the room it needs.
- Confirming a toolchain removal while another toolchain is downloading now shows that it was accepted, instead of leaving the card offering Remove for minutes.
- The execute-bit repair reaches the binaries a toolchain keeps outside its own directory, which for Ruby is the interpreter itself.
- Removing a toolchain no longer deletes a shared library another installed toolchain also ships.
- A toolchain download whose server declares no length fills its progress bar instead of stopping near a third of the way.
- A file created in a device folder while the write-back could not run reaches the device the next time that folder is opened, instead of staying inside VSCodroid until the app is uninstalled.
- An edit that never reached the device folder is kept beside the device's newer copy under a `.local-<time>` name, instead of being overwritten without notice.
- A device document is no longer copied through a symbolic link out of the folder you granted.
- A device folder granted without write access is refused when it is opened, rather than opening and then refusing every save.
- Renaming a folder in the editor no longer leaves a second, partial copy on the device when the provider will not rename a document it has already moved.
- An adopted session no longer warns that extensions, git and npm have no network. The DNS proxy now runs inside the editor server, so a survivor keeps a working one.
- Extension webviews get the right content type for ES modules, WebAssembly, JPEG, GIF, WebP and media files, which all arrived as an unknown type and would not load.
- Link hand-off notices resume after a quiet stretch instead of going silent for the rest of the session once eight have been shown.
- A comment above settings.json's opening brace is no longer mistaken for the document itself when an upgrade adds a setting, which left the file unparseable and dropped every preference.
- The setup screen now keeps the display awake while it unpacks the app, so a screen timeout can no longer strand a first run part-way through an 800 MB extraction.
- Toolchain screen: a download already running on either delivery path is now shown with its progress and a Cancel button instead of an Install button that started nothing.
- Clearing caches now frees the toolchain download staging directories it was already counting, leaving alone any directory a running download is still using.
- Saving a download no longer stops working after the page reloads or the workspace folder changes; the abandoned transfer is released and its part-written file removed.
- The copied crash report is marked sensitive, so Android no longer draws a preview of the server log and crash text over the editor.
- The storage and device-folder screens no longer time out on installs with many files: the commands that walk the disk now get a deadline that fits.
- Refusals the bridge shows in the editor, a link that would not open or a folder copy still in use, moved to string resources so they can be translated.
- Settings files written before this release now gain the secondary side bar default, which previously reached clean installs only and left every upgraded device on the upstream layout.
- A workspace whose saved layout still opens the chat view is corrected once, on the next launch. Opening the bar yourself is remembered and is never undone again.
- The process list now names the program behind each entry, so several rows no longer read identically as the bundled Node runtime and its heap setting.
- The chat agent's model backend is now recognised as a language server, so both the idle reclaim under memory pressure and Kill Idle Servers can reach it.
- Git helper setup no longer follows its own symlinks when setting the execute bit, ending refused permission changes on every launch.
- Extra key row: a Shift latched and then left unused while typing on the soft keyboard no longer changes which character the next row key inserts.
- The first-run toolchain picker subtitle now uses plain punctuation; the escaped dash it carried was expanded into the compiled resource and shown to users.
- The alternates popup is closed when the key row leaves its window, so finishing or recreating the activity with one open no longer leaks that window.
- Fixed a device folder re-opened during startup cleanup having its local copy removed underneath the editor, which reached the device as deletions of the user's real documents.
- A save the device folder refused, followed by deleting or renaming that file, no longer marks the folder's local copy as permanently holding work the device does not have.
- Moving a folder into another directory now reaches the device when its previous location cannot be looked up, instead of being recorded as done with nothing sent.
- Clearing caches now reports the space actually released rather than counting files the filesystem refused to remove.
- Creating directories in a device folder no longer slows down for the rest of a session after a folder is moved out of it.
- Cancel now reaches a toolchain download started before the screen was recreated, and a second tap no longer starts the same transfer over again.
- A Play delivery whose install runs out of room at the record write is kept, so the next launch can finish it without downloading the pack again.
- One toolchain whose delivery cannot be reconciled at launch no longer stops the packs behind it from being installed or reclaimed.
- A toolchain script whose upstream name bash cannot use as a function is skipped rather than written, which used to break every new terminal.
- Staging directories left by a toolchain download the system killed are reclaimed at launch, instead of occupying the space the retry then asks for.
- A selected toolchain card no longer announces its selection twice to a screen reader; the card's own checked state is the single channel.
- Setup no longer stalls the launch thread for the length of one asset copy: atomic writes now exclude per destination instead of sharing a single process-wide lock.
- The storage check no longer charges an upgrade for rewriting a file bigger than everything currently on disk, which refused devices that had room to spare.
- An upgrade across the server-tree change reclaims the old tree once, so a retry after a failed unpack keeps what the previous attempt already wrote.
- Setup clears its own caches before refusing for storage, so a full npm cache no longer strands the user behind a screen the editor never opens.
- The storage check credits only bundled extension directories that are already unpacked, no longer counting gallery installs as space the unpack can reuse.
- A bundled extension is no longer unpacked when a newer copy is installed, which left tens of megabytes that nothing loaded and nothing removed.
- A heap ceiling set below the value derived from device RAM is no longer disabled after three kills, which used to raise the ceiling on the device being killed.
- A WebView provider that is missing or being updated by Play no longer crashes the app at launch, before first-run setup and toolchain repairs can run.
- Stopping the server while it is still starting now ends the process that start spawned, instead of leaving an untracked Node holding the editor port.
- Fix sign-in callbacks whose authorisation code or state contained escaped characters: the relay decoded the payload a second time, rewriting the provider's own values before the extension saw them.
- Opening a vscodroid:// link on an install that had never finished setting up started the editor with no server tree behind it; it now runs setup first and keeps the link.
- The setup screen is no longer dismissed when the display times out, which used to skip the toolchain picker permanently and could cost a full re-extraction on the next launch.
- A download still transferring when the editor window is rebuilt no longer leaves a part-written file in the chosen folder under the name the user picked.
- A download whose page script cannot start now reports a failure instead of stalling silently and blocking every download queued behind it.
- The memory-pressure hint is written again after Android reclaims the app cache directory, so idle language servers are still shed when memory runs short.
- Switching device folders no longer freezes the interface while the previous folder's pending writes drain or the new copy's directory watches are registered.
- Closing the editor no longer waits up to two seconds for pending device-folder writes before the window goes away.
- The sign-in restart notice is shown once per session, so a repeated external link can no longer fill the screen with it.
- The extra key row releases a WebView destroyed by a renderer crash instead of holding it for the rest of the session.
- Requests for editor assets that name the VS Code CDN are now always answered on device; when the local server is unreachable they no longer fall through to the network.
- A page failing certificates on many hosts could cover the editor with toasts almost continuously; past the cap refusals are now throttled to one per interval.
- External links are logged by scheme and host only, so an OAuth code or API key carried in a link's query no longer reaches release logs.
- Download names chosen by the page are now redacted wherever they are logged, matching the download report and closing the paths that still printed them in full.
- Overlong download names are trimmed by byte length, so a name in CJK or emoji no longer exceeds the filesystem limit the chosen folder must create it under.

- A terminal in a window with no folder open now starts in your projects directory, not in the last device folder you ever opened.
- A file you edited on the device is no longer overwritten by the app's copy after a write-back that failed once and later succeeded.
- Cancelling a toolchain download while it installs no longer deletes the files being copied out of it, which left a part-written toolchain behind.
- A selected toolchain in the first-run picker no longer draws two check marks in the same corner.
- A terminal opened on a folder now stays in it. The shell profile moved every new terminal to the last folder the app recorded, whatever was open.
- The Java toolchain now ships OpenJDK's own licence and third-party notices. The download stripped them, so the JDK reached devices with none of its attribution.
- Values the editor page hands the app no longer reach logcat in the clear: a download's name and failure detail, a folder URI, and a toolchain name.
- A link that fails to open now says why. Every refusal blamed a missing app, including a stale session and a URL Android refused outright.
- The connection token no longer reaches logcat when a link fails to open. The URL was logged whole, twice, on a line that ships in release builds.
- A link that fails to open no longer repeats its address inside the exception message. The redaction covered the log line and not the throwable beside it.
- A page in the editor loading an https address with a bad certificate now says which host was blocked and why. The load was refused in silence.
- The system dark theme flipping no longer moves you out of your workspace, or restarts first-run extraction if it lands while setup is running.
- Opening the app no longer discards what `npm config set` wrote. A private registry, its auth token, `cafile` and `strict-ssl` all survive a launch now.
- The menubar no longer closes itself when the on-screen keyboard drops. The resize that follows the tap shut the menu 40ms after it opened, leaving no route to the File or Terminal menus.
- A screen reader now hears the Toolchains back arrow, which toolchain is selected in the picker, that setup failed, and that a download finished.
- Six texts on the setup and toolchain screens were too faint to read, including the Continue button and the word that says a download failed.
- Two write-backs of one device file can no longer interleave. Each opened the document with truncation, so the copy on the device could end up neither version.
- A file the device folder refuses to create now says so. It stayed inside the app looking synced, and the difference only showed after an uninstall.
- A folder copied out that did not arrive whole now says how much is missing, once for the folder rather than once for every file in it.
- A first run that fails now names the step and quotes the error, instead of only "Setup failed" and a Retry that walks into the same wall.
- A link that no installed app can open now says so. The tap did nothing and said nothing, which reads as a broken link rather than a missing app.
- Activating a key on the extra key row through a screen reader now types it. Every key offered activation and none of them did anything.
- A latched Ctrl, Alt or Shift now says so to a screen reader. The latch was carried by colour alone, so it could be switched but not observed.
- Keys on the extra key row are wider to touch. The 2dp gap between them was taken off each key, which left them under Android's minimum target size.
- Dragging a finger inside an application menu now scrolls it instead of closing the submenu. A menu that cannot scroll still reported scrolling, and the submenu closed on it.
- Tapping the editor now raises the keyboard, and what it types now arrives. The app never took Android input focus for its view, so keyboard input was silently discarded.
- Brackets, quotes and other symbols on the extra key row now type into the editor. On recent WebViews they inserted nothing at all.
- A save the sync refuses to write back now says so. It was silent, so an edit that never reached the device folder looked exactly like one that did.
- Closing a device folder while a save is still going out no longer lets the next folder share its write-back queue, which could interleave two writes into one document.
- A file two saves are writing at once stays marked as unfinished until the last one lands, so a crash between them can no longer leave a half-written device copy unrecorded.
- Opening a device folder now says when files did not make it across. They were simply absent from the tree, and the first sign was a later save being refused.
- The editor sees files changed outside it again. One source file was missing from the watcher build, so the addon could not load and nothing in a folder was watched.
- Narrowing the platform-detection patch now fails the build. It could previously be narrowed with every check still green, leaving the marketplace asked for a binary that cannot start on Android.
- The local network check reads the manifest as XML. The permission named in a comment counted as declared, so the first targetSdk 37 build would pass with dev servers unreachable.
- First-run setup now shows progress while it extracts the editor, instead of holding at 5% for minutes and looking like it has hung.
- Installing a toolchain from Play now checks for space first. Without room it failed partway and left a half-installed toolchain behind.
- The storage figures in the README can be re-measured on Linux. The command printed a plausible 0 MB there, because it used the macOS spelling of `stat`.
- Creating a folder no longer overwrites a device document the app could not read, such as one too large to copy. Only the single-file path checked this before.
- On a device folder whose provider reports no modification time, a file you edited starts receiving device changes again once both sides hold the same content.
- Toolchain downloads now come from the release matching the installed app, instead of whichever is newest. A newer release can retire a payload an older app still offers.
- Closing the editor or swiping the app away no longer keeps the destroyed screen and its view tree in memory until the app is next opened.
- A toolchain install started from Play no longer keeps that screen in memory for as long as the download runs.
- A toolchain Play finished downloading after its screen closed is now installed on the next launch, instead of being paid for and never appearing.
- Refusing a toolchain install for lack of space now hands Play's copy back, instead of leaving it on the device that just ran out.
- A toolchain whose release publishes no checksum for it now says so, instead of reporting a connection problem that retrying cannot fix.
- On a device provider that reports no modification time, reopening a folder no longer replaces edits made in the editor. It refreshes only files identical to the last sync.
- Reopening a device folder now writes back an edit the watcher never carried out, instead of leaving it in the app forever.
- A save that cannot reach the device folder now says so, once per burst, instead of failing silently and looking like a save that worked.
- A device folder's local copy is no longer deleted when it falls off the recent list. Anything written there but never sent, a cloned repository included, was the only copy.
- First-run setup asks for the space the unpack needs. A repair that runs before the check made it demand 113 MB more and refuse devices that had room.
- A file being written back to a device folder can no longer be truncated by a second writer after the editor screen is rebuilt.
- Removing a device folder copy stays refused for the whole session the folder was open in, rather than becoming allowed as soon as the editor screen is rebuilt.
- A directory holding exactly the copy limit is no longer reported as partly copied when every file in it arrived.
- Keys on the extra row keep the space above and below their labels, which the rounded background had been replacing with none.
- A setup failure names the real error in release builds. The shrinker renamed the type, so the screen showed a two-letter token instead.
- The download percentage on the first-run screen comes from a string resource, so its sign and digits follow the device's language.
- Installing a toolchain already being installed is declined rather than copying the tree twice, which could run a device out of space and leave a partial install.
- A page can no longer bury the editor under notices about links it could not open. Only navigations you started are announced, and repeats of the same one are dropped.
- Re-adding a device folder while its previous copy is still being removed no longer discards the new copy's record of which files have not reached the device.
- Installing a toolchain another install is already fetching no longer downloads and unpacks it a second time before declining.
- The toolchain screen says an install is already running, instead of answering a tap with nothing at all.
- Certificate notices pause during a burst and resume after it, so a page can no longer silence a genuine certificate fault for the rest of the session.
- A registry token or proxy password in `.npmrc` survives the launch-time repair. Any byte that was not valid UTF-8 was replaced when the file was rewritten.
- Bug reports now name the memory ceiling the server actually started with. The line existed but never reached the file the report carries.
- The storage refusal on first run names the Retry button, so a screen reader user is told it is there.
- A device folder list that fails to load says so, instead of reporting a failed removal the user never asked for.
- Removing a device folder's local copy no longer reports success when the copy could not be set aside. The folder keeps its permission and its recent entry.
- The notice shown when a device folder fails to open comes from a string resource, so it can be translated instead of staying English in every language.
- The server log no longer rewrites itself on every output line once the lines it keeps are large, which stalled the thread draining the server's output.
- A delivered toolchain pack the store did not actually delete is reclaimed at the next launch, instead of occupying a toolchain's worth of storage for good.
- Certificate and link failure notices are capped at eight distinct failures in a session, so a page failing many hosts can no longer bury the editor under them.
- The status badge and the action button on a toolchain card no longer draw over each other when a long label makes both wide.
- Saving a download to a slow storage provider no longer freezes the app. The write held a lock that closing the editor had to wait on.
- Managing device folder storage no longer freezes the editor while it measures the copies on disk.
- Renaming a folder inside a device folder keeps the record of edits that never reached the device, which could otherwise let a part-written device copy overwrite the complete one.
- A save made as a device folder closes now reaches the queue being emptied instead of one nothing drains, where it waited until the folder was next opened.
- Choosing where to save a download no longer reloads the editor out from under the transfer when the picker is left open a long time.
- Reopening the app after Android reclaims its window returns to the folder that was open, rather than to the default projects directory.
- The first-run toolchain picker shows which toolchains are already installed instead of offering them as fresh downloads that would be fetched and unpacked again.
- Toolchain download progress keeps moving after the screen is rebuilt, and the card becomes Installed when the transfer ends rather than staying on Cancel.
- Cancelling a toolchain reinstall no longer hides Remove for a toolchain that is still fully installed.
- A toolchain name supplied by the editor page no longer reaches the log in full when it names nothing the app offers.
- Uninstalling a bundled extension is no longer undone by the next app update when a newer copy of it was installed at the time.
- The custom memory limit is bounded by a share of the device's own memory below three gigabytes, where it previously allowed three times what that device is given by default.
- A first launch after the app process is killed adopts the editor server still holding its port instead of starting a second one behind it.
- A foreground start the system refuses now stops the service, rather than leaving a server running with no notification and no way to stop it.
- Kill Idle Servers kills only idle ones. It ended every language server, including one in the middle of answering.
- A project of your own that depends on a Copilot package is no longer treated as a language server and shed when memory runs short.
- The process counter's warning and its status bar change colour at the same count, instead of the warning arriving two processes early.
- The screen stays awake while a toolchain downloads, which is the longest wait of a first run and the one it was missing.
- A modifier latched on the key row is cleared when the editor page is rebuilt after a crash, instead of staying held for a page that never heard of it.
- Running out of space during setup says how much to free rather than how much the whole unpack needs.
- The storage check no longer counts an installed toolchain's files as space the unpack can reuse, which let a full device through a gate meant to refuse it.
- Files created in the editor inside a device folder no longer arrive on the device with .txt appended to their name.
- A screen reader can reach Cancel during a toolchain download. Every progress report replaced the card, taking accessibility focus with it.
- Cancelling a toolchain download now stops it during extraction and the copy, not only before the checksum, and no longer stalls the packs queued behind it.
- A first run interrupted by the system no longer leaves a bundled extension permanently half-installed, listed but broken on every activation.
- The loading screen is shown while the editor server starts, instead of a blank white page.
- Renaming a folder inside a device folder keeps the record of edits that never reached the device, so a part-written copy can no longer overwrite the complete one.
- Saving a download no longer freezes the app between chunks, and a storage provider that has gone away no longer ends the app when the destination is opened.
- Ctrl+Shift+P and other two-modifier shortcuts no longer lose the modifier just pressed to a stale reading from the page.
- Opening a device folder no longer does two cross-process lookups and a preferences read on the interface thread.
- Workspace images, fonts and other assets edited on disk are served fresh instead of from a one-year cache.
- Kill Idle Servers and the memory-pressure sweep no longer treat a program running from your own device folder as a language server.
- The repair for a truncated .bashrc or settings.json no longer deletes the file before rewriting it, so a failed repair leaves it for the next launch instead of losing it.
- Running out of space during setup says how much to free rather than the size of the whole unpack.
- The process list no longer freezes for the rest of a session when the system reclaims the app's cache directory.
- The toolchain removal dialog is dismissed on rotation instead of leaking its window.
- The editor server is trusted only once the process the app started reports that it bound its port, so a stranger holding the port during a restart is no longer handed the connection token for answering with the public commit.
- A cold start no longer abandons a server of ours that was slow to answer: it is ended and the same port reused, so workbench sign-ins and state keyed to that origin are kept.
- A heap ceiling commented out with a `/* */` block in settings.json is no longer applied.
- A file edited on the device and then in the editor before the folder was reopened keeps the device edit beside it as `<name>.device-<time>` instead of overwriting it.
- Reopening a folder puts every stranded save onto the device in mirrors larger than 2000 entries; the cap counts stranded files, not entries walked.
- A document whose name holds a tab or line break is no longer recreated on the device after being deleted there, and its mirror can be reclaimed.
- A save still resolving when a folder is switched no longer writes the previous folder's directories into the next folder's document cache.
- Cancelling a queued toolchain download on a full device reports it as cancelled rather than as a storage failure.
- A withdrawn toolchain's leftover Play delivery is reclaimed at launch; up to 179 MB could sit in app storage that no screen counted.
- Opening a device folder while another is still being copied no longer leaves write-back on the wrong folder; opens run one at a time, and one the page has already left is skipped.
- The page shown after repeated editor crashes stays up until Reload is tapped, even when the server restarts on its own in the meantime.
- A second splash screen created during first-run setup no longer releases the foreground hold while the unpack is still writing.
- A bundled extension the user uninstalled is no longer unpacked again on the next upgrade as an unlisted directory.
- Extension-record preferences are written synchronously, as every other first-run record already was.
- Extra key row: a latched Ctrl or Alt is spent when a composing keyboard starts a word on the EditContext path, so the space ending the word is no longer Ctrl+Space.
- Extra key row: a latched modifier is spent when focus moves into an extension webview or the Simple Browser, so the first character typed back in the editor is inserted rather than run as a chord.
- An extension host whose client dropped before it finished starting is disposed after the reconnection grace instead of living until the server exits.
- Copying a bug report from the crash dialog no longer builds it on the main thread.
- Instrumented server tests follow the port the app recorded instead of probing 13337.

### Security
- A page in the editor can no longer hand a link to another app from a frame you never touched; a navigation with no gesture behind it is refused.
- Content rendered in the editor can no longer load `content://` addresses, so it cannot spend a device-folder permission you granted the app.
- Symbolic links inside a device folder's local copy are no longer written out to the device, where they arrived as ordinary files carrying whatever their target held.
- Opening a device folder no longer writes that folder's path to the system log; the lines identify it by the name of its local copy instead.
- Toolchain downloads refuse a redirect that leaves HTTPS, so the payload and the digest that vouches for it cannot arrive over cleartext.
- Server output kept for bug reports no longer records credentials an extension prints to stdout or stderr, so a report copied to the clipboard cannot carry them.

- Content rendered in the editor can no longer read workspace files across origins. Served files are readable by anyone, and a page in the built-in browser could fetch one.
- A webview can no longer name an arbitrary file and have the app read it with the editor's own credential. The route that did was reachable but unused.
- The editor can no longer make the app fetch an arbitrary web address on its behalf. The route reached any host, including addresses only this device can see.
- Bug reports redact credentials named the way a server names them. A rule required the name to start at a word boundary, so NPM_TOKEN, DB_PASSWORD and their family were written out in full.
- A device folder's path no longer reaches the system log when the folder is opened from the recent list.
- Content rendered in the editor can no longer steer the address the app fetches when it proxies an editor asset.
- A page opened in the built-in browser can no longer read the open workspace. Files served to extension webviews are refused unless the request comes from the editor, so such a page can neither run a workspace script nor learn which files exist.
- Server output kept for bug reports also removes cookie headers, private keys, and credentials inside a JSON body that was quoted into another string.

## [1.1.0] - 2026-08-19

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
- When a previous editor server survives, the app serves that one instead of starting a second it cannot use.
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

- README, the user guide and the requirements specification now carry storage figures that match the gate: 810 MB of assets, 875 MB required before extraction, about 990 MB with both toolchains. The README promised 500 MB while setup refuses under 875 MB.
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
- Toolchain downloads, the server tarball, npm, extensions and every bundled tool are verified against a digest pinned in this repository rather than one the serving host supplies alongside the file; a missing digest fails the build.
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
- Every editor-to-Android call now requires the session token. Six took none, and a test enumerates them so a new one cannot skip it.
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

- Selecting a Python virtual environment now activates it in the terminal. The extension classified this platform as unknown and quietly composed no activation command.
- Generating an SSH key stops blocking the app when it hangs. The wait was twelve times longer than the editor's, so every other bridge call queued behind it.
- A build whose native addons were left as the desktop Linux versions is now refused at packaging. Nothing checked them, and the failure only appeared on a device.
- Show Storage Usage says which rows can be cleared and clears only those. Every row ran the cache clear, which freed nothing and then reported success.
- Device folders you have not opened for a while stop holding disk for ever. Their local copies were unreachable by any action in the app.
- Edits reach the device folder when reopened from Open Recent or Get Started. Only the folder picker started syncing, so those saves stayed on the app's side.
- A device file over 50 MB is no longer replaced by a smaller one you create with the same name. The sync skips such files and then wrote over them.
- A file the sync could not copy is reported rather than passed over in silence, and it is no longer overwritten later by anything created at that path.
- A device folder whose access has lapsed keeps its mirror when that mirror holds an edit which never reached the device. Launch used to delete it unasked.
- Reopening such a folder no longer writes a stale mirror back over the device copy. The record meant to prevent that outlived the mirror and caused it instead.
- The Try again button on the server-stopped page works after the editor has loaded. It answered only before the first successful start.
- Reopening the app after the server stopped shows what happened. It kept the starting screen up indefinitely behind a toast that expired in three seconds.
- The session token is no longer written into the error page, which cannot use it and which then absorbed sign-in callbacks meant for the editor.
- Closing the editor releases it. The background service held the closed window and its view tree until another one opened.
- Upgrading re-extracts even when the version name is unchanged. Setup compared the name alone, and two builds have carried 1.1.0, so the first would have kept the old server tree.
- The storage check counts a withdrawn toolchain's space. It read sizes through the registry, which no longer lists Go, so a device holding it could run out of disk mid-setup.
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

VSCodroid is now publicly available on Google Play. This release represents the cumulative work across milestones M0 to M6, bringing a full VS Code IDE experience to Android.

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
- Google Play production access granted: app now publicly available

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
- Real PTY terminals via `/dev/pts/*`: vim, tmux, readline, colors, job control all work
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

[Unreleased]: https://github.com/rmyndharis/VSCodroid/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/rmyndharis/VSCodroid/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/rmyndharis/VSCodroid/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/rmyndharis/VSCodroid/compare/v0.2.9...v1.0.0
[0.2.9]: https://github.com/rmyndharis/VSCodroid/compare/v0.2.8...v0.2.9
[0.2.8]: https://github.com/rmyndharis/VSCodroid/compare/v0.2.5...v0.2.8
[0.2.5]: https://github.com/rmyndharis/VSCodroid/compare/v0.2.4...v0.2.5
[0.2.4]: https://github.com/rmyndharis/VSCodroid/compare/v0.2.3...v0.2.4
[0.2.3]: https://github.com/rmyndharis/VSCodroid/compare/v0.2.2-m6...v0.2.3
[0.2.2-m6]: https://github.com/rmyndharis/VSCodroid/compare/v0.1.0-alpha...v0.2.2-m6
[0.1.0-m0]: https://github.com/rmyndharis/VSCodroid/releases/tag/v0.1.0-alpha
