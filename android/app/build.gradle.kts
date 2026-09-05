import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Load signing config from signing.properties (local) or env vars (CI)
val signingProps = Properties()
rootProject.file("signing.properties").takeIf { it.exists() }?.inputStream()?.use { signingProps.load(it) }
fun signingProp(key: String, envVar: String, fallback: String = "") =
    signingProps.getProperty(key) ?: System.getenv(envVar) ?: fallback

// The attribution documents, copied into the APK so a person holding the
// binaries can read the notices that have to travel with them.
//
// This matters most for the copyleft ones. Bash, Git, Make, readline, libiconv,
// gdbm, liblzma and zstd are all GPL or LGPL, and the GPL's written offer of
// source has to accompany the binary rather than sit in a repository the holder
// of an APK has no reason to know exists. NOTICE.md and docs/LEGAL_NOTICES.md
// carried that offer and shipped nowhere; every device ran the binaries with no
// notice of any kind on it.
//
// Naming a licence is not supplying it, and the four COPYING files are the
// half that was missing. GPL-2.0 section 1, GPL-3.0 section 4, LGPL-2.1
// section 1 and LGPL-3.0 section 4 each require a copy of the licence itself to
// accompany the binary; the documents above carried a gnu.org URL instead,
// which discharges nothing, least of all on the offline device this app is
// built to be usable on. Three of them are the FSF texts as shipped in Termux's
// liblzma package, which is one of the packages this APK redistributes, and
// LGPL-3.0 is the FSF publication; all four are verbatim, and NoticesTest pins
// each one's sha256, because a licence text that has been edited is not the
// licence.
//
// LGPL-3.0 is the one whose subject is not in the base APK. GMP is LGPL-3.0 and
// ships inside the Ruby toolchain pack, which has no licence screen of its own,
// so this dialog is the only route to a copy on a device that installed Ruby.
//
// Not everything under licenses/ is copied here, and the two that are not are
// deliberate: LICENSE.ICU and COPYRIGHT.musl are placed beside their binaries
// under assets/usr/share/doc by the download scripts, because that is where the
// rest of the per-component notices live and where a reader would look for
// them. This directory is for the licence TEXTS the dialog offers as a set.
//
// Copied from the repository root rather than committed under src/main/assets,
// because a second copy is a copy that goes stale, and a stale licence notice is
// worse than an absent one: it is a claim about terms that is no longer true.
// The markdown is ~48 KiB and the licence texts ~78 KiB, which together deflate
// to roughly 50 KiB in the APK, well under 0.1% of the base module. The markdown
// grows as the tree does, since every binary in it has to be named in both, so
// treat that as the order of magnitude rather than as a figure to check against.
//
// They are read out of the APK by MainActivity's licences dialog and never
// extracted, which is why they are a separate assets source directory: it keeps
// them out of the src/main/assets sum that sizes first-run extraction.
//
// Sync rather than Copy, because the app opens these by basename and a Copy
// leaves behind whatever the last run wrote. Measured: with COPYING.GPLv3
// removed from the tree, the Copy ran, succeeded, and left the previous run's
// COPYING.GPLv3 in the output directory, where the next incremental APK
// packaged it. A licence text still shipping under a name the repository no
// longer has is the stale copy this task exists to avoid, arriving by the back
// door. Only this task writes that directory, so deleting what it did not write
// costs nothing.
val bundleNotices = tasks.register<Sync>("bundleNotices") {
    group = "build"
    description = "Copies the attribution and licence documents into the APK's assets."

    val repoRoot = rootProject.projectDir.parentFile
    // One list, read by the copy and by the guard below, so the two cannot
    // name different documents. The names the app opens are kept beside it:
    // com.vscodroid.util.Notices lists the same six, and NoticesTest compares
    // the lists, so a rename here fails the build rather than emptying the
    // dialog on a device.
    val documents = listOf(
        "NOTICE.md",
        "docs/LEGAL_NOTICES.md",
        "licenses/COPYING.GPLv2",
        "licenses/COPYING.GPLv3",
        "licenses/COPYING.LGPLv2.1",
        "licenses/COPYING.LGPLv3"
    ).map { File(repoRoot, it) }
    from(documents)
    into(layout.buildDirectory.dir("generated/notices"))

    // A source that is not there fails the build rather than shortening the
    // copy. Sync and Copy both skip a missing from() without a word, which is
    // the measurement recorded above: with COPYING.GPLv3 removed the task ran
    // and reported success. NoticesTest pins every one of these by sha256, but
    // the test task is not in the packaging graph, so a local bundleRelease or
    // scripts/build-aab.sh on a checkout with one moved aside reaches a signed
    // AAB with every packaging gate green, and Notices.readOne then opens that
    // entry of the licence chooser on its missing-document marker. For the
    // LGPL-3.0 text that is the one copy a device that installed Ruby has of
    // the terms GMP ships under.
    doFirst {
        val missing = documents.filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "bundleNotices: ${missing.joinToString { it.relativeTo(repoRoot).path }} " +
                    "missing from the repository. The licences dialog would ship " +
                    "without it, and the GPL and LGPL require the text to travel " +
                    "with the binaries."
            )
        }
    }
}

android {
    namespace = "com.vscodroid"
    // Compile against the newest platform, which the androidx libraries this app
    // uses now require. Compile-time only: what the platform applies to a running
    // app is decided by targetSdk below, and that is deliberately not moved here.
    compileSdk = 37

    signingConfigs {
        create("release") {
            storeFile = file(signingProp("storeFile", "VSCODROID_KEYSTORE_FILE",
                "${System.getProperty("user.home")}/vscodroid-release.jks"))
            storePassword = signingProp("storePassword", "VSCODROID_KEYSTORE_PASSWORD")
            keyAlias = signingProp("keyAlias", "VSCODROID_KEY_ALIAS", "vscodroid")
            keyPassword = signingProp("keyPassword", "VSCODROID_KEY_PASSWORD")
        }
    }

    defaultConfig {
        applicationId = "com.vscodroid"
        minSdk = 33
        // Held at 36 on purpose, and lint's OldTargetApi is answered rather than
        // ignored. Targeting 37 blocks local network access by default, so a dev
        // server running here stops being reachable from another device on the
        // same Wi-Fi, which is one of the things this app exists to do. Making
        // that move means declaring ACCESS_LOCAL_NETWORK, requesting it at run
        // time and handling a refusal; scripts/check-local-network-permission.py
        // fails the build if the number moves without the permission, so this is
        // recorded rather than left to be discovered by a user whose friend's
        // browser simply times out.
        @Suppress("OldTargetApi")
        targetSdk = 36
        versionCode = 13
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }

        // How much room first-run extraction needs is the size of the asset tree,
        // and that number cannot be written by hand: it moves with every VS Code
        // pin. It was written by hand, as 500 MB, when the tree was a pre-built
        // 1.96.4 server, and by 1.133.0 the tree is over 800 MiB, so the gate
        // passed devices that then ran out of disk mid-extraction and reported
        // "Setup failed" rather than "not enough room". Measuring it here instead
        // removes the way that goes wrong rather than restating the number.
        //
        // Every file under src/main/assets is extracted: extractAssetDir copies
        // whole trees for vscode-reh and usr, extractBundledExtensions unpacks
        // all of extensions/, and the bootstrap scripts are copied individually.
        // So the whole tree is the right thing to measure, not a subset.
        //
        // The APK's assets are that tree plus the notices bundled by
        // `bundleNotices` below, and those are deliberately outside this sum:
        // they are read straight out of the APK by the licences dialog and never
        // written to disk, so charging a device for room to unpack them would
        // ask for space nothing is going to use.
        //
        // Jobs that build without a real asset tree (lint, unit tests, R8) get a
        // small number here, which is correct rather than a gap: their APK has no
        // tree to unpack either, so the gate keeps matching what shipped.
        //
        // One walk, five figures. Each of the fields below used to call
        // `fileTree(...).files` itself: the whole 22,320-file tree twice over
        // (this total and the maximum under it) and each of the three subtrees a
        // third time. All five run during the configuration phase of every
        // invocation, `lint`, `testDebugUnitTest` and `assembleDebugAndroidTest`
        // included, none of which packages an asset. The subtrees are a strict
        // subset of the first walk, so they are derived from it by path prefix
        // rather than enumerated and stat'ed again.
        val assetsDir = file("src/main/assets")
        val assetSizes = fileTree(assetsDir).files.associate { it.path to it.length() }
        val assetBytesUnder = { subdir: String ->
            val prefix = File(assetsDir, subdir).path + File.separator
            assetSizes.entries.filter { it.key.startsWith(prefix) }.sumOf { it.value }
        }

        // Translated interface bundles are the one part of the tree that is not
        // unpacked at all. The page is served them straight out of the APK by
        // VSCodroidWebViewClient, and nothing else reads them: FirstRunSetup's
        // extraction targets are named one by one and none of them is `nls`.
        // Charging the tree for them would ask a first run for 16 MiB it never
        // writes, so they come off the total and nothing goes back.
        val nlsPrefix = File(assetsDir, "nls").path + File.separator
        val nlsSizes = assetSizes.filterKeys { it.startsWith(nlsPrefix) }.values
        buildConfigField(
            "long",
            "EXTRACTED_ASSET_BYTES",
            "${assetSizes.values.sum() - nlsSizes.sum()}L"
        )

        // The biggest single file in that tree, which is what an install that
        // already holds the tree needs rather than the total. Extraction writes
        // through <dest>.tmp~ and renames, so re-unpacking over an existing tree
        // needs room for one more copy of one file at a time, currently the
        // 113 MiB Copilot runtime.node, and not for a second copy of the tree.
        // FirstRunSetup.requiredExtractionBytes is where the two meet.
        //
        // Measured here for the same reason the total is: it moves with every VS
        // Code pin, and the largest file has changed identity twice already. A
        // literal would be a figure nobody rechecks.
        //
        // maxOrNull, because the jobs that build without a real asset tree
        // (lint, unit tests, R8) have nothing to take a maximum of and maxOf
        // throws on an empty tree.
        buildConfigField(
            "long",
            "LARGEST_ASSET_BYTES",
            "${assetSizes.values.maxOrNull() ?: 0L}L"
        )

        // The two subtrees extraction writes into ground it does not own alone.
        // `usr/` also holds installed toolchains and anything `npm install -g`
        // leaves; the extensions directory also holds whatever the user took
        // from the gallery. So neither directory's size on disk answers "how
        // much of what we are about to write is already there", and the gate
        // used to charge both in full on every update: about 334 MB asked of a
        // device that needed roughly 180.
        //
        // Measured from the same tree as the two figures above so they cannot
        // drift apart, and used by FirstRunSetup.sharedTreeCredit as the ceiling
        // on what an existing directory may be credited for. A build with no
        // real asset tree gets zero, which credits nothing and is the safe
        // direction.
        buildConfigField(
            "long",
            "BUNDLED_USR_BYTES",
            "${assetBytesUnder("usr")}L"
        )
        // The server tree on its own, so extraction can report real progress across it
        // rather than sitting at one number for the minutes it takes. It is by far the
        // largest single step, and computed the same way as its siblings so it cannot
        // drift from what is actually packaged.
        buildConfigField(
            "long",
            "BUNDLED_SERVER_BYTES",
            "${assetBytesUnder("vscode-reh")}L"
        )
        buildConfigField(
            "long",
            "BUNDLED_EXTENSION_BYTES",
            "${assetBytesUnder("extensions")}L"
        )
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // The documented switch behind Play's native-crash support: with
            // this set, the bundle task emits app-release-symbols.zip beside
            // the AAB, which is the file the console recognises by name and
            // layout. Most bundled libraries arrive stripped by their
            // builders, so what this carries is the symbol table of anything
            // that still has one; release.yml adds the glibc shim, the one
            // native library compiled here, into that same zip.
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Which of the 2000-odd unit tests actually reach a line, answered by
            // the tooling already in the Android plugin rather than by adding a
            // coverage plugin: this switch makes the debug unit-test task emit
            // JaCoCo execution data and gives it a report task,
            // `createDebugUnitTestCoverageReport`, writing HTML and XML under
            // app/build/reports/coverage/.
            //
            // Behind a property because it is not free. Turning it on instruments
            // every class the tests load, and an instrumented run is a different
            // run: it is slower, and a timing-sensitive case can pass or fail on
            // that alone. The suite CI gates on therefore stays uninstrumented and
            // the report is asked for deliberately:
            //
            //   ./gradlew :app:createDebugUnitTestCoverageReport -PvscodroidCoverage
            //
            // No threshold and no gate. A number that fails the build invites
            // tests written to move it, which is the opposite of what the suite
            // here is for; this exists so an untested file can be found, not so a
            // percentage can be defended.
            enableUnitTestCoverage = providers.gradleProperty("vscodroidCoverage").isPresent
        }
    }

    // AAPT's default ignoreAssetsPattern skips files/dirs starting with "_"
    // (pattern "_*"). npm's @sigstore/protobuf-specs has a __generated__/
    // directory that gets silently dropped, breaking `npm install`. Override
    // to only skip dotfiles and VCS metadata (keep underscore-prefixed dirs).
    //
    // The `.*` entry is kept on purpose and is not the other half of the same
    // defect. Every dotfile under assets/ today is inert build-time metadata
    // that would only add bytes on a device: measured 2026-08-22, nine of them
    // (npm's own .npmrc at 0 bytes, three language-server .npmrc, .gitignore,
    // two .gitattributes, .travis.yml, .release-please-manifest.json), none
    // present in the release APK and none read at runtime. What would make this
    // wrong is a load-bearing dotfile arriving in the server tree, which would
    // ship in the repository, pass verify-server-tree.py and be absent on
    // device. Patch 0010 is the near miss: it exists because a .moduleignore
    // matters, and the file it keeps is sdk/index.js, which is not a dotfile.
    aaptOptions {
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*.orig:*~"
    }

    // CRITICAL: Required for the .so binary bundling trick on Android 14+.
    // Without useLegacyPackaging = true, the Package Manager will not extract
    // .so files from the APK, and our bundled binaries (libnode.so, libgit.so,
    // etc.) will not be accessible with execute permission at runtime.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // On-demand toolchain asset packs (Play Asset Delivery)
    assetPacks += listOf(":toolchain_ruby", ":toolchain_java")

    bundle {
        language {
            // Ship every language in the base module instead of letting Play
            // install only the one the device is set to.
            //
            // The split is on by default and it does not fit this app. Android's
            // per-app language picker, which `android:localeConfig` publishes,
            // offers all thirteen; picking one whose split Play never installed
            // leaves the app's own screens in English while the editor beside
            // them is translated, because the editor's strings are assets and
            // assets are not split by language. Fetching the missing split needs
            // the Play Core split-install API and a dependency this app does not
            // carry, so the cheaper answer is not to split: the thirteen
            // `values-*` directories are a few hundred KB against an APK of some
            // hundreds of MB.
            enableSplit = false
        }
    }

    // The attribution documents, packaged so they reach the device. See
    // `bundleNotices` at the foot of this file for why they are copied rather
    // than committed here.
    //
    // Named by the task, not by its directory, and that is what carries the
    // dependency. A bare path is a directory something happens to fill, so
    // every consumer has to declare the producer itself or Gradle refuses the
    // build at validation. Lint has several such consumers and AGP adds more
    // between versions; naming the task answers for all of them at once.
    // `bundleNotices` is registered above for this reason and no other.
    sourceSets["main"].assets.srcDir(bundleNotices)

    lint {
        // The baseline is what makes this affordable: the 3 issues recorded in
        // lint-baseline.xml are filtered out of every report, so what remains is
        // what arrived after it was taken.
        //
        // Three, and each is a decision rather than a backlog. Two are the newer
        // asset-delivery this build declines: measured on the merged release
        // manifest, 2.3.0 adds ACCESS_NETWORK_STATE, RECEIVE_BOOT_COMPLETED and
        // WAKE_LOCK to what the installed app asks for, and the reason is written
        // beside the version in libs.versions.toml. The third is x86_64 for
        // ChromeOS, which this app cannot offer: every bundled binary, from the
        // Node runtime to git and Python, is an arm64 build taken from Termux,
        // and there is no second toolchain to ship.
        //
        // An entry naming a file above this module carries whatever path lint
        // recorded, and that depends on where the checkout sat. Regenerated
        // from under the home directory, the location goes in through lint's
        // `$HOME` variable, which lint expands when it reads the baseline back,
        // so the entry matches only where that expansion lands on this module's
        // own file. Regenerated from outside the home directory, the same
        // location reads `../gradle/libs.versions.toml`, which resolves against
        // the module and travels with it.
        //
        // Measured on two checkouts, one below the home directory and one
        // outside it, plus the report from a CI run: one regenerated `../`
        // file, the same bytes in both places, filtered all 92 of its entries
        // in each, while the 39 entries removed here expanded to a third
        // checkout and filtered nothing in either run, as on the runner, where
        // they sit among the 42 it could not match. Pointing that expansion at
        // the checkout under test brought six of them back. So read which form
        // an entry has before deleting it. Only the `$HOME` form is confined to
        // one checkout, and only there do the warnings it had been hiding come
        // back. Regenerating the baseline is not the answer, since lint writes
        // such an entry straight back; lint already names the entries that
        // stopped matching, under LintBaselineFixed, and those are the ones to
        // delete by hand.
        //
        // scripts/check-lint-baseline.py holds the committed file to both
        // halves of that: the count stated above, and every location staying
        // relative to this module. Nothing else can. Regenerating it here
        // produced 91 entries, 45 of them rooted at `$HOME`, which match
        // nothing when read from any other checkout; `./gradlew lint` still
        // exited 0, because LintBaselineFixed is Information severity.
        //
        // abortOnError was false alongside it, and the two cancel out. The
        // baseline narrows lint to new issues; the flag then discarded those
        // too, so `./gradlew lint` ran in CI, produced a report, uploaded it,
        // and could not fail a pull request whatever it found. The comment here
        // claimed new issues were "flagged", which was true of the report and
        // not of the build.
        abortOnError = true
        baseline = file("lint-baseline.xml")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)

    // Material Design
    implementation(libs.google.material)

    // Layout
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.viewpager2)

    // Chrome Custom Tabs (GitHub OAuth, external auth flows)
    implementation(libs.androidx.browser)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Play Asset Delivery (on-demand toolchain packs)
    implementation(libs.play.asset.delivery.ktx)

    // Unit Testing (JUnit 5, JVM only)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    // Gradle 9 no longer puts the JUnit Platform launcher on the test runtime
    // classpath by itself. Without it every JVM test fails before any test
    // class is loaded, with "Could not start Gradle Test Executor".
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    // org.json is on the test classpath, so a JVM test CAN parse JSON. Parse the
    // manifest and assert on the parsed object. Do not pattern-match its text and do
    // not stub the parser: both assert on something other than what the code under
    // test actually reads, and both stay green while the parsing is wrong.
    //
    // Why the dependency is here: android.jar's stub throws "not mocked" from every
    // org.json method, which is what used to put manifest parsing out of reach. That
    // is the problem this line solved -- past tense -- not a limit still in force.
    // Check it in seconds rather than by building, and note the :app: -- the
    // configuration is on the module, not the root project:
    //   ./gradlew :app:dependencies --configuration debugUnitTestRuntimeClasspath
    // Adding it changed no existing verdict, because nothing parsed JSON before it.
    testImplementation(libs.org.json)

    // Instrumented Testing (JUnit 4, runs on device)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
}

tasks.withType<Test> {
    useJUnitPlatform()

    // This build script is a genuine input to the unit tests, because one of
    // them reads it: NoticesTest parses the `bundleNotices` task to check that
    // what the build copies into the APK is what `Notices.BUNDLED` opens. Gradle
    // cannot see a `File("build.gradle.kts").readText()` from inside a test, so
    // without this declaration the task stays UP-TO-DATE whenever the build
    // script is the only thing that changed.
    //
    // Which is exactly the edit the test exists to catch. Deleting a `from(...)`
    // line and re-running left `> Task :app:testDebugUnitTest UP-TO-DATE`, exit
    // 0, and the results XML untouched from the previous run; only
    // `--rerun-tasks` turned it red. A guard that cannot run on the change it
    // guards against is not a guard.
    inputs.file(layout.projectDirectory.file("build.gradle.kts"))
        .withPropertyName("appBuildScript")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // The settings script, read by one suite for the other half of the same
    // question: ToolchainRegistryTest holds the `include(":toolchain_*")` lines
    // to `ToolchainRegistry.available`, because an asset pack that is not a
    // Gradle project is not in the bundle whatever `assetPacks` lists. A pack
    // module removed there and left in the catalog is what that assertion
    // exists to catch, and it is also the one edit that changes no compiled
    // input, so undeclared it is the edit the task answers UP-TO-DATE over.
    // Not measured the way the declarations below were; an input this task did
    // not have can only cost a re-run, never a wrong answer.
    inputs.file(rootProject.projectDir.resolve("settings.gradle.kts"))
        .withPropertyName("settingsScript")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // The manifest is read the same way and needs the same declaration. Editing
    // only the manifest changes no compiled input, so without this the run that
    // would catch a bad manifest edit is the one that reports
    // `> Task :app:testDebugUnitTest UP-TO-DATE`, exit 0, over the previous
    // run's results. Measured: repointing `<application android:name>` with the
    // declaration absent kept the task up to date and the build successful in
    // 1s; with it present the task re-ran and the assertion went red.
    //
    // One declaration is what makes every manifest-reading suite run, so
    // narrowing it to a single reader's question silences the others, and they
    // ask different questions. ProcessWideMemoryPressureTest asks whether
    // `<application android:name>` still points at VSCodroidApp, the class that
    // hears trim callbacks once no Activity is left. HardwareKeyboardTest,
    // arriving with the hardware-keyboard guards, asks whether both activities
    // still list every qualifier they need in `android:configChanges`. Treat
    // that as a list of readers, not a limit on them:
    //   grep -rn 'File("src/main/AndroidManifest.xml")' android/app/src/test/kotlin
    inputs.file(layout.projectDirectory.file("src/main/AndroidManifest.xml"))
        .withPropertyName("appManifest")
    // Eight suites read files no Kotlin compiles against, because the things they
    // check cross a language boundary that has no compiler: the bootstrap's
    // adoption note (assets/server.js), the shutdown the worker hosts get
    // (patches/), the manifests of the bundled extensions, the verbatim licence
    // texts, and the documents that restate a requirement the code owns. Gradle
    // knew about none of them, so it answered UP-TO-DATE for a run that had to
    // notice one of them change, and served the previous run's results. Measured
    // on this module: a signature edited in docs/05-API_SPEC.md left the task
    // UP-TO-DATE and the build successful, and with the file declared the same
    // edit re-runs the suite and turns BridgeApiSpecParityTest red. Renaming a
    // command title to a name of the same length does the same for
    // CommandReferenceTest, and that length is the point: the only thing that
    // already noticed an assets edit was the byte total in BuildConfig, which a
    // size-neutral one leaves exactly where it was. A clean CI checkout was
    // never affected; an incremental run always was.
    //
    // The set is the whole of what those File(...) reads reach, so check the
    // suites before narrowing any of it:
    //
    //   assets/*.js           AdoptionNoteWireTest
    //   assets/extensions     BundledExtensionHostTest, CommandReferenceTest,
    //                         WalkthroughCompletionEventTest
    //   patches/              WorkerHostShutdownPatchTest
    //   licenses/, NOTICE.md  NoticesTest
    //   README.md, docs/*.md  WebViewVersionTest, BridgeApiSpecParityTest,
    //                         NoticesTest
    //   MILESTONES.md         BundledExtensionVersionTest
    //
    // Sources under src/main/kotlin are read by other suites the same way and are
    // deliberately not listed: they are compiled into the classpath this task
    // already depends on, so editing one re-runs the tests without any
    // declaration. Nothing else here has a compiler standing behind it.
    inputs.files(fileTree("src/main/assets") { include("*.js") })
        .withPropertyName("bootstrapScripts")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree("src/main/assets/extensions"))
        .withPropertyName("bundledExtensions")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree(rootProject.projectDir.parentFile.resolve("patches")))
        .withPropertyName("serverPatches")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // The three files that between them decide which languages ship, read by
    // LocaleCoverageTest, which exists because they are written in three
    // notations and nothing compares them. `values-*` is under src/main/res
    // rather than src/main/kotlin, so no compiler stands behind it either.
    inputs.file(rootProject.projectDir.parentFile.resolve("scripts/build-nls-bundles.py"))
        .withPropertyName("interfaceBundleScript")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(file("src/main/res/xml/locales_config.xml"))
        .withPropertyName("localesConfig")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree("src/main/res") { include("values-*/strings.xml") })
        .withPropertyName("translatedStrings")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // One script, not the directory: MenuSeparatorFloorTest reads the block
    // build-vscode-oss.sh appends to workbench.css, because the touch-target
    // floors that block writes reach a menu separator and the exemption that
    // keeps a 1px divider from being drawn as a 44px band lives there. Nothing
    // else under scripts/ is opened by a test, and most of it is Python the
    // Python gates already cover.
    inputs.file(rootProject.projectDir.parentFile.resolve("scripts/build-vscode-oss.sh"))
        .withPropertyName("codeOssBuildScript")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // The licence texts NoticesTest pins byte for byte. That pin exists for the
    // edit nobody means to make: a reflow, a re-wrap, an editor stripping the
    // form feeds out of COPYING.LGPLv2.1. Undeclared, the run that has to notice
    // such an edit is the one that answers UP-TO-DATE over the previous run's
    // results. Measured: appending a line to licenses/COPYING.GPLv2 left the task
    // up to date and the build successful in 679ms; declared, the same edit
    // re-runs the suite and turns it red.
    inputs.files(fileTree(rootProject.projectDir.parentFile.resolve("licenses")))
        .withPropertyName("licenseTexts")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // Top-level markdown only. docs/ also carries a site, screenshots and a logo
    // directory, and no test reads any of them; hashing them on every run would
    // buy nothing and 2.6 MB of it is images.
    //
    // MILESTONES.md is here for the same reason and was missed because it is the
    // one reader outside docs/: BundledExtensionVersionTest holds the bundled
    // extension directories to that file's inventory, so an edit to either side
    // alone is exactly the mismatch the suite exists to catch, and undeclared it
    // is also exactly the edit the task answered UP-TO-DATE over. Not measured
    // the way the rows above were, but the shape is identical, and an input this
    // task did not have can only cost a re-run, never a wrong answer.
    inputs.files(
        rootProject.projectDir.parentFile.resolve("README.md"),
        rootProject.projectDir.parentFile.resolve("NOTICE.md"),
        rootProject.projectDir.parentFile.resolve("MILESTONES.md"),
        fileTree(rootProject.projectDir.parentFile.resolve("docs")) { include("*.md") },
    )
        .withPropertyName("statedRequirements")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // The branding overlay the server build applies to product.json.
    //
    // No suite parses it today. DisplayLanguageTest did, to check the `set` half
    // named no nlsCoreBaseUrl, and that check moved out when the app began
    // supplying that address itself at runtime: what matters now is that the
    // BUILT tree carries none, which only scripts/verify-server-tree.py can see.
    // The declaration stays because the file is still one a change here should
    // re-run the suite for, and because the next reader of it will be a test
    // again rather than a comment.
    inputs.file(rootProject.projectDir.parentFile.resolve("branding/product.json"))
        .withPropertyName("brandingOverlay")

    // Resources, for the same reason and with one difference that makes them
    // easier to miss than everything above: they DO have a tool standing behind
    // them, and it is not enough. aapt turns res/ into R, so an edit that adds or
    // removes a resource id recompiles and the tests re-run. An edit that changes
    // only an attribute VALUE, or deletes an attribute, produces the same ids and
    // the same R, so the compiled classpath this task depends on is byte for byte
    // what it was and Gradle answers UP-TO-DATE. That is precisely the shape of
    // edit these suites exist to catch.
    //
    // Measured on this module, both directions, with the task made UP-TO-DATE
    // first: deleting `app:navigationContentDescription` from
    // activity_toolchain.xml left `> Task :app:testDebugUnitTest UP-TO-DATE`,
    // BUILD SUCCESSFUL in 1s, and the previous run's results XML served back
    // green; flipping `android:enforceStatusBarContrast` to true in themes.xml
    // did the same. With this declaration each re-runs the suite and turns its
    // reader red. Renaming a resource is NOT a control for this: it changes the
    // id, so `processDebugResources` fails first and never reaches the test.
    //
    //   res/layout    PickerAccessibilityWiringTest, TextContrastTest
    //   res/values    PickerAccessibilityWiringTest, ThemeEdgeToEdgeTest,
    //                 TextContrastTest
    //
    // layout/ and values/ only. Nothing reads drawable/, mipmap-*/ or xml/, and
    // the mipmaps are images whose hashes would be paid for on every run and
    // never asked about. Widen this the moment a suite reads one of them:
    //   grep -rn 'File("src/main/res' android/app/src/test/kotlin
    inputs.files(
        fileTree("src/main/res/layout"),
        fileTree("src/main/res/values"),
    )
        .withPropertyName("readableResources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

/**
 * Fails the build with [help] when this `Exec` task exits non-zero.
 *
 * Eleven verification tasks in this file wrap a `python3 scripts/check-*.py` or
 * a `verify-*.py`, and each of them repeated the same five lines: turn off
 * Gradle's own exit handling, capture `executionResult`, and re-raise inside
 * `doLast` with a paragraph the script has no way to write because it does not
 * know who called it. Only the help text differs, and only the help text is
 * worth reading, so that is what stays at each site.
 *
 * The capture must sit HERE and not inside `doLast`: the receiver in that block
 * is `Task`, which has no `executionResult`, and reading it there fails at
 * configuration time with an error about the receiver rather than about the
 * property. Exactly one of the eleven copies carried a comment saying so; the
 * other ten carried the trap with nothing to warn the next person.
 *
 * `isIgnoreExitValue` is what makes this necessary at all. Without it Gradle
 * fails the task itself, with a message naming an exit status and a command
 * line, which is true and tells a reader nothing about what to do next.
 */
fun Exec.failOnExit(help: String) {
    isIgnoreExitValue = true
    val result = executionResult
    doLast {
        if (result.get().exitValue != 0) {
            throw GradleException(help)
        }
    }
}

// The server tree in assets/ has to carry this checkout's patches, and nothing
// on this side looked. fetch-vscode-oss.sh runs the same check on what it
// downloads, but package-assets.sh copies the tree in with a plain `cp -r` and
// Gradle packages assets exactly as it finds them -- so a checkout whose last
// fetch predates a patch produces an APK that builds, installs and opens with
// the adaptation missing, and the first symptom is on a device. That already
// happened: this was written against a working copy whose packaged server
// predated patch 0012, so its /callback route answered 403 and every OAuth
// sign-in hung, with every gate in the project green.
//
// It reads the six bundles named in patches/fingerprints.txt, not the 700 MB
// tree around them: 0.11s, no network, nothing resolved. Cheap enough that
// declaring Gradle inputs would cost the same hashing it would save.
val checkPatchFingerprints = tasks.register<Exec>("checkPatchFingerprints") {
    group = "verification"
    description = "Checks the server tree in assets/ carries every patch in patches/."

    // The same directory named twice, from the two bases that apply: the script
    // is run from the repository root, and file() resolves against this module.
    val serverTree = "android/app/src/main/assets/vscode-reh"
    val entryPoint = file("src/main/assets/vscode-reh/out/server-main.js")

    workingDir = rootProject.projectDir.parentFile
    commandLine("python3", "scripts/check-patch-fingerprints.py", serverTree)

    // Absent is not stale. A source-only checkout has no server tree at all,
    // and the lint and unit-test jobs create assets/vscode-reh/out as an empty
    // stub purely so Gradle will run -- neither has anything to check, and
    // failing them would report a download nobody ran as a patch nobody
    // applied. Any tree that can produce a working APK has this file: it is the
    // server's entry point, and verify-server-tree.py already requires it.
    onlyIf { entryPoint.isFile }

    // The script names which patch is missing; this adds what to do about it,
    // which it has no way to know when it is not the fetcher calling.
    failOnExit(
        "The server tree in assets/ is missing at least one patch this checkout applies.\n" +
            "The line above names which. This tree is older than patches/, and no\n" +
            "amount of rebuilding the APK will change that -- the patch is applied\n" +
            "when the server is built, not when the app is.\n" +
            "\n" +
            "Two ways forward:\n" +
            "\n" +
            "  Published server (normal case). Run the \"Build Code - OSS server\"\n" +
            "  workflow so a server-<version> release exists for the version in\n" +
            "  VSCODE_VERSION, then refresh the local tree:\n" +
            "      rm -f server/vscode-reh-web-linux-arm64-*.tar.gz\n" +
            "      ./scripts/fetch-vscode-oss.sh && ./scripts/package-assets.sh\n" +
            "  The cached tarball is only refetched when the digest on the release\n" +
            "  changes, so retrying without that rebuild will not reach you.\n" +
            "\n" +
            "  Locally, in Docker. See the header of scripts/build-vscode-oss.sh for\n" +
            "  the invocation, and remove the work volume first -- a reused one can\n" +
            "  satisfy a stage the script never ran:\n" +
            "      docker volume rm vscodroid-codeoss\n" +
            "  Then point the fetcher at what it produced:\n" +
            "      VSCODE_OSS_URL=file:///path/to/the.tar.gz ./scripts/fetch-vscode-oss.sh\n" +
            "      ./scripts/package-assets.sh"
    )
}

// The same script fetch-vscode-oss.sh runs, pointed at the tree that actually
// gets packaged instead of the one that was downloaded. Those are two different
// trees and only the second ships: server/vscode-reh is copied into assets/ by
// package-assets.sh locally and by an inline cp in build.yml and release.yml, and
// build-native-addons.sh then writes its .node files INTO that copy. Several
// steps read the packaged tree afterwards -- build-glibc-shim.sh --scan,
// check-langserver-patterns.py, the "Verify assets" existence check -- but this
// is the only one that asks whether the tree is VALID.
//
// Be honest about where it earns its keep, because the obvious answer is wrong.
// In CI this is defence in depth rather than a hole being closed: release.yml has
// no assets cache and always fetches, so the fetch-side run always happens; in
// build.yml a cache miss or a restore-keys partial hit both leave
// `cache-hit != 'true'`, so the fetch runs there too; and on an EXACT assets-cache
// hit the restored tree necessarily passed this same script already, because
// scripts/verify-server-tree.py is itself part of that cache key. What it does
// cover is the local path -- fetch once, then build repeatedly for days, possibly
// after patches/ or branding/ have moved underneath -- and any future workflow
// that packages a tree without fetching one. Do not fold it back into
// fetch-vscode-oss.sh: that script only ever sees the copy it just made.
//
// What it catches that checkPatchFingerprints cannot: staleness that is not a
// missing patch. Measured on the tree in a working checkout on 2026-08-15 --
// fingerprints exited 0 while this exited 1, on the same tree in the same
// second, because the packaged workbench.css was built before an activity-bar
// change that is appended at server-build time rather than applied as a patch.
//
// 0.56s on the real 699 MB tree, no network: one walk for native binaries, plus
// product.json and workbench.css.
/**
 * Refuses a packaged tree whose native addons are still the upstream glibc builds.
 *
 * Nothing else asks this. `verifyServerTree` reads `e_machine` and stops there,
 * which is all it *can* ask: it also runs from `build-vscode-oss.sh` and
 * `fetch-vscode-oss.sh`, on trees whose addons are legitimately still glibc
 * because the overlay has not happened yet. `verifyBundledBinaries` sweeps
 * `jniLibs/` and passes `assets/usr/lib` only as a place to resolve names from,
 * never opening those files. So a tree carrying the pristine `pty.node`,
 * `watcher.node` and `vscode-sqlite3.node` packaged green, and the failure was a
 * dlopen on the device with no build-time sign of it.
 *
 * Reachable in ordinary use rather than in theory: `package-assets.sh` copies
 * `server/vscode-reh` over `assets/vscode-reh` wholesale, so re-running it after
 * the addon build reinstates every upstream binary. The CI release path is
 * ordered so this cannot happen; a local build has no such protection.
 *
 * `gen-glibc-forwarders.py` already answers exactly this question, and is
 * already the gate at the end of `build-glibc-shim.sh`. A second implementation
 * would be the drift its own header warns about.
 */
val verifyNativeAddons = tasks.register<Exec>("verifyNativeAddons") {
    group = "verification"
    description = "Checks the packaged native addons were built for Bionic, not glibc."

    val entryPoint = file("src/main/assets/vscode-reh/out/server-main.js")

    workingDir = rootProject.projectDir.parentFile
    commandLine(
        "python3", "scripts/gen-glibc-forwarders.py",
        "--scan", "android/app/src/main/assets/vscode-reh",
        "--scan", "android/app/src/main/assets/extensions",
        "--verify-against", "android/app/src/main/assets/usr/lib",
    )

    // Same reasoning as verifyServerTree: the lint and unit-test jobs stub an
    // empty assets tree so Gradle can configure, and a tree that was never
    // downloaded has no addons to judge.
    onlyIf { entryPoint.isFile }

    failOnExit(
        "The packaged tree carries native addons that cannot load on Android.\n" +
            "The ERROR lines above name them and what they need.\n" +
            "\n" +
            "Two causes, both ordinary:\n" +
            "  * scripts/build-native-addons.sh has not run for this tree, or\n" +
            "  * package-assets.sh ran after it and copied the upstream tree\n" +
            "    back over the overlay. It must run before, not after.\n" +
            "\n" +
            "scripts/build-all.sh runs them in the order that works. If the\n" +
            "missing half is the compatibility shim rather than an addon, that is\n" +
            "scripts/build-glibc-shim.sh, which download-termux-tools.sh removes\n" +
            "as a side effect of refreshing its own libraries."
    )
}

/**
 * Refuses a packaged asset tree carrying an aarch64 binary the device cannot
 * map, or cannot start.
 *
 * The page-size rule is enforced everywhere a binary is produced and nowhere a
 * binary is packaged, for everything except `jniLibs/`. Each download script
 * checks the one file it just placed, and `verifyBundledBinaries` sweeps
 * `jniLibs/` as a directory, so the ~150 aarch64 binaries under `assets/` were
 * answered for at fetch time and never again. Three ordinary routes reach
 * packaging with no fetch having run: a cache restore in CI, a local build after
 * an old fetch, and `package-assets.sh` copying `server/vscode-reh` over the
 * tree wholesale. The server tree also arrives from a release as a tarball,
 * where only ripgrep is examined on the way in.
 *
 * What it costs when it goes wrong is a `dlopen` that fails on a 16 KB-page
 * device and nowhere else, which is the shape of failure the whole checker
 * exists for: the file is present, the build is green, and the feature the addon
 * backs is simply missing on the devices that ship today.
 *
 * Two of the four questions the script can ask: LOAD alignment, which decides
 * whether the file maps at all on Android 16, and PT_INTERP, which decides
 * whether an aarch64 executable came out of a toolchain whose loader exists
 * here. DT_NEEDED is deliberately not asked. The tree is packaged
 * rather than built here, so it legitimately holds payloads for other platforms
 * and dependencies nothing loads, and asking that question of it would fail a
 * correct build; the script's `alignment_sweep` names both cases and the
 * measurements behind them. The dependency half is asked of these same
 * directories by `verifyNativeAddons`, which knows about the shim.
 *
 * Armed by the same file as its two neighbours, and skipped with them: the lint
 * and unit-test jobs stub an empty assets tree so Gradle will configure, and a
 * tree that was never downloaded has nothing to judge.
 *
 * Measured on the real tree: 146 aarch64 binaries, 4 skipped as another ABI or
 * not loadable, 0.8s. The count is a snapshot and moves whenever the packaged
 * tree does; only the shape is durable.
 */
val verifyPackagedAlignment = tasks.register<Exec>("verifyPackagedAlignment") {
    group = "verification"
    description = "Checks every packaged aarch64 binary can be mapped and started."

    val entryPoint = file("src/main/assets/vscode-reh/out/server-main.js")

    workingDir = rootProject.projectDir.parentFile
    commandLine(
        "python3", "scripts/verify-android-elf.py",
        "--tree", "android/app/src/main/assets",
    )

    onlyIf { entryPoint.isFile }

    failOnExit(
        "A binary in the packaged asset tree cannot be used on Android.\n" +
            "The FAIL line above names the file and which of the two questions\n" +
            "it failed.\n" +
            "\n" +
            "Segment alignment: Android 16 refuses to map anything below 16 KB,\n" +
            "so whatever loads it fails on a current device and works everywhere\n" +
            "older.\n" +
            "\n" +
            "PT_INTERP: an aarch64 executable naming a program interpreter other\n" +
            "than /system/bin/linker64 came out of a glibc toolchain, and nothing\n" +
            "on the device can start it. This tree is extracted to filesDir,\n" +
            "where SELinux refuses execve outright, and the loader indirection\n" +
            "that does start a payload there hands it to /system/bin/linker64,\n" +
            "which cannot satisfy a glibc binary either. The two glibc-built\n" +
            "helpers inside @microsoft/mxc-sdk are known and allowed by name in\n" +
            "verify-android-elf.py; a third arriving with a VS Code bump is what\n" +
            "this catches. That allowlist is checked in the other direction too:\n" +
            "an entry no file matched fails, because a bump that moves one of the\n" +
            "two leaves a path waving through whatever lands there next. That FAIL\n" +
            "names a line in verify-android-elf.py rather than a file, and deleting\n" +
            "the entry is the whole fix.\n" +
            "\n" +
            "Re-run the script that\n" +
            "places the file: scripts/build-native-addons.sh for an addon under\n" +
            "vscode-reh/node_modules, scripts/download-termux-tools.sh or\n" +
            "download-python.sh for anything under assets/usr, and\n" +
            "scripts/fetch-vscode-oss.sh for the server tree itself.\n" +
            "\n" +
            "A file that arrives misaligned from upstream is not fixable here:\n" +
            "it has to be rebuilt with -Wl,-z,max-page-size=16384, which is what\n" +
            "build-native-addons.sh already passes."
    )
}

val verifyServerTree = tasks.register<Exec>("verifyServerTree") {
    group = "verification"
    description = "Checks the server tree in assets/ is one this app can run."

    val serverTree = "android/app/src/main/assets/vscode-reh"
    val entryPoint = file("src/main/assets/vscode-reh/out/server-main.js")

    workingDir = rootProject.projectDir.parentFile
    commandLine("python3", "scripts/verify-server-tree.py", serverTree)

    // Absent is not stale, for the same reason as the task above: the lint and
    // unit-test jobs create assets/vscode-reh/out as an empty stub purely so
    // Gradle will configure, and a tree that was never downloaded has nothing to
    // verify. Any tree that can produce a working APK has this file.
    onlyIf { entryPoint.isFile }

    failOnExit(
        "The server tree in assets/ is not one this app can run.\n" +
            "The FAIL line above names which check it did not meet.\n" +
            "\n" +
            "This tree is a copy of server/vscode-reh, so refresh both:\n" +
            "    ./scripts/fetch-vscode-oss.sh && ./scripts/package-assets.sh\n" +
            "\n" +
            "Nothing needs deleting by hand first. fetch-vscode-oss.sh asks the\n" +
            "server-<version> release what digest it carries now, compares the\n" +
            "cached tarball against it, and removes and refetches it when they\n" +
            "differ -- which is exactly the case when an earlier build of the same\n" +
            "version left this tree behind.\n" +
            "\n" +
            "If it reports the cached tarball as matching and this still fails, the\n" +
            "published server is itself stale: rebuild and republish it by running\n" +
            "the \"Build Code - OSS server\" workflow."
    )
}

// A jniLibs entry smaller than this is the placeholder the lint, unit-test and R8
// jobs write so Gradle will configure -- `printf '\x7fELF'` followed by 60 zero
// bytes, 64 in total, which is not an ELF file at all and would fail any check of
// it. The same threshold is written out twice more, in build.yml's and
// release.yml's "Verify assets" steps; named here so this side has one place to
// change, and so the others are findable rather than folklore:
//     grep -n 'lt 1000' .github/workflows/*.yml
//     grep -rn "printf '.x7fELF'" .github/workflows/
// The gate below skips only while EVERY jniLibs entry is under this. Raising a
// workflow's placeholder past it would arm the gate in that job -- but so would
// any real binary landing beside the placeholder, and that is the trigger that
// actually governs, since the guard stopped keying on libnode.so alone. Loud
// either way rather than silent, and in both cases the fix is in the tree that
// produced the mixture, not here.
val jniLibsStubCeiling = 1000L

// Asked by both packaging gates below, so they arm and skip together on one
// answer rather than on two copies of it: a tree worth examining for one is
// worth examining for the other, and a placeholder tree is neither's business.
fun jniLibsHoldsRealBinary(): Boolean =
    file("src/main/jniLibs/arm64-v8a").listFiles()
        ?.any { it.name.endsWith(".so") && it.length() >= jniLibsStubCeiling } == true

// Every binary in jniLibs, checked where it is packaged rather than only where
// it was downloaded.
//
// Until this task, each download script ran verify-android-elf.py on the one file
// it had just installed and that was the only time any of them was examined:
// nothing asked the question about the directory as a whole at the moment it went
// into an APK. Two paths reach that moment without a download having run: a build.yml
// assets-cache hit restores jniLibs/arm64-v8a/ wholesale with every download step
// skipped by `if: cache-hit != 'true'`, and a local `./gradlew assembleDebug`
// after an old fetch never re-downloads anything. scripts/verify-android-elf.py
// is hashed into three CI cache keys: build.yml's `downloads-` and `assets-`,
// and release.yml's `downloads-`. verify-server-tree.py is in the same three,
// so the contrast this comment used to draw between them does not exist.
// Tightening the checker therefore changes all three keys, the caches miss,
// and every binary is fetched and examined again by the stricter version
// rather than staying behind on the looser one. Re-measure with:
//     grep -c 'verify-android-elf.py' .github/workflows/*.yml
//
// What that costs when it goes wrong is the quietest failure this project has:
// a mis-built ripgrep installs perfectly and makes Search return no results,
// which is indistinguishable from a project that genuinely contains no matches.
//
// Measured on the real bundled set: 11 binaries, all pass, 1.4s across separate
// interpreter starts and well under that in the single call used here.
val verifyBundledBinaries = tasks.register<Exec>("verifyBundledBinaries") {
    group = "verification"
    description = "Checks every bundled binary in jniLibs can load on Android."

    workingDir = rootProject.projectDir.parentFile
    commandLine(
        "python3", "scripts/verify-android-elf.py",
        "--dir", "android/app/src/main/jniLibs/arm64-v8a",
        // Both directories, matching what every installer passes -- see the
        // --lib-dir pairs in download-node.sh, download-python.sh and
        // download-termux-tools.sh. jniLibs is the second one for a reason that is
        // easy to miss: Android extracts it to nativeLibraryDir, which is on the
        // loader path, so a bundled binary may legitimately name another bundled
        // lib*.so in its DT_NEEDED. Passing only assets/usr/lib here would make
        // this gate stricter than the checks it was modelled on, and it would
        // reject at packaging what the installer had just accepted -- then send
        // the developer back to re-run the script that passed.
        "--lib-dir", "android/app/src/main/assets/usr/lib",
        "--lib-dir", "android/app/src/main/jniLibs/arm64-v8a",
    )

    // Runs when jniLibs holds at least one real binary. Keyed on "any", not on
    // libnode.so: the binary this gate most earns its keep on is ripgrep, which
    // fetch-vscode-oss.sh installs while download-node.sh installs the runtime, so
    // keying on Node would skip a tree that had a fetched-but-bad ripgrep and no
    // Node in it.
    //
    // The ceiling this rests on has one blind spot, measured rather than reasoned:
    // a jniLibs in which EVERY .so is under it is taken for a placeholder tree and
    // skipped, so a directory holding nothing but a 20-byte truncated binary is not
    // examined. One real binary beside it and the sweep runs and catches it --
    // verified, all three states. The blind case cannot yield a working APK and is
    // caught on the CI path anyway, by build.yml's "Verify assets" step erroring
    // when libnode.so is under the same 1000 bytes. Tightening it further would
    // mean distinguishing a truncated binary from the placeholder by content
    // rather than size, and that trade is not worth failing the three jobs that
    // legitimately write a placeholder.
    onlyIf { jniLibsHoldsRealBinary() }

    failOnExit(
        "A bundled binary in jniLibs cannot load on Android.\n" +
            "The FAIL line above names the file and the property it fails.\n" +
            "\n" +
            "These are placed by the download scripts, so re-run the one that\n" +
            "owns the file -- scripts/download-node.sh, download-python.sh,\n" +
            "download-termux-tools.sh, download-musl-loader.sh, or\n" +
            "fetch-vscode-oss.sh for libripgrep.so -- and let it fail there,\n" +
            "where the message says which upstream package it came from.\n" +
            "\n" +
            "In CI a cached jniLibs is restored whole with every download step\n" +
            "skipped, so a binary here can be older than the tree. It cannot be\n" +
            "older than this checker: scripts/verify-android-elf.py is hashed\n" +
            "into all three cache keys, so tightening it misses the cache and\n" +
            "refetches. Re-run the owning script above; do not bust a cache."
    )
}

// The binaries that must BE there, as opposed to the properties the sweeps above
// ask of whatever happens to be there.
//
// Every other check on this directory enumerates: verifyBundledBinaries reads the
// files it finds, verifyBundledShellPaths reads the files it finds, and both are
// armed by "some .so is larger than the placeholder". None of them can notice a
// file that was never produced, so a jniLibs missing one binary outright passed
// every gate and produced a signed, installable, uploadable bundle.
//
// Measured rather than hypothetical: libexec-trampoline.so, which is newer than
// the workflows that build it, was absent from a signed release bundle on a
// developer machine while every packaging gate ran and passed. Nothing on the CI
// path was at risk, because release.yml's build step is unconditional; what
// produces that bundle is scripts/build-aab.sh, or a bare `./gradlew
// bundleRelease`, neither of which prepares an asset tree.
//
// The names are not a wish list. Each is resolved by absolute path at runtime,
// from nativeLibraryDir, by Kotlin that ships:
//   * Environment.kt names libnode.so, libbash.so, libgit.so, libldmusl.so and
//     libexec-trampoline.so directly;
//   * FirstRunSetup.setupToolSymlinks() maps bash, git, node, python3, python,
//     rg, tmux, make, ssh and ssh-keygen onto their lib*.so, and
//     setupGitCore() maps every git-remote-<protocol> onto libgit-remote-curl.so.
// A missing one is not a degraded feature: the symlink resolves to nothing, and
// a PATH lookup for the command fails with ENOENT, which on a device is
// indistinguishable from the tool never having been installed.
//
// Same arming predicate as its neighbours, for the same reason: the lint,
// unit-test and R8 jobs write a placeholder tree that has no binaries to require.
val requiredJniLibs = listOf(
    "libbash.so",
    // Both halves of what starts the Claude Code CLI. Missing, the extension
    // spawns a path that is not there; and a launcher whose LD_PRELOAD names a
    // shim that is not there fails in musl's loader before the CLI runs at all.
    "libclaude-launch.so",
    "libexec-trampoline.so",
    "libgit.so",
    "libgit-remote-curl.so",
    "libldmusl.so",
    "libmake.so",
    "libnode.so",
    "libpython.so",
    "libripgrep.so",
    "libseccomp-shim.so",
    "libssh.so",
    "libssh-keygen.so",
    "libtmux.so",
)

// Which script places each of the above, so the failure below can name the one
// command that fixes it rather than sending the reader to read three scripts.
val jniLibProducers = mapOf(
    "libbash.so" to "scripts/download-termux-tools.sh",
    "libclaude-launch.so" to "scripts/build-claude-shim.sh",
    "libexec-trampoline.so" to "scripts/build-exec-trampoline.sh",
    "libgit.so" to "scripts/download-termux-tools.sh",
    "libgit-remote-curl.so" to "scripts/download-termux-tools.sh",
    "libldmusl.so" to "scripts/download-musl-loader.sh",
    "libmake.so" to "scripts/download-termux-tools.sh",
    "libnode.so" to "scripts/download-node.sh",
    "libpython.so" to "scripts/download-python.sh",
    "libripgrep.so" to "scripts/fetch-vscode-oss.sh",
    "libseccomp-shim.so" to "scripts/build-claude-shim.sh",
    "libssh.so" to "scripts/download-termux-tools.sh",
    "libssh-keygen.so" to "scripts/download-termux-tools.sh",
    "libtmux.so" to "scripts/download-termux-tools.sh",
)

val verifyRequiredBinaries = tasks.register("verifyRequiredBinaries") {
    group = "verification"
    description = "Checks every binary the app resolves by name is in jniLibs."

    val jniLibsDir = file("src/main/jniLibs/arm64-v8a")
    val required = requiredJniLibs
    val producers = jniLibProducers

    onlyIf { jniLibsHoldsRealBinary() }

    doLast {
        val missing = required.filterNot { File(jniLibsDir, it).isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "jniLibs is missing ${missing.size} of the ${required.size} binaries " +
                    "this app resolves by name:\n" +
                    missing.joinToString("\n") { "  $it   run ${producers[it]}" } +
                    "\n\nEach is reached through an absolute path in nativeLibraryDir, " +
                    "so on a device the command it backs fails with ENOENT and looks " +
                    "exactly like a tool that was never installed.\n" +
                    "scripts/build-all.sh runs every producer above in the order that " +
                    "works. scripts/build-aab.sh does not: it builds and signs only, " +
                    "and packages whatever the last preparation left behind."
            )
        }
    }
}

// No bundled binary may name a shell it cannot reach.
//
// Termux compiles /data/data/com.termux/files/usr/bin/sh into the binaries this
// app ships, and that directory belongs to another application. The download
// scripts rewrite it, but they name their files one by one, which is the shape
// that misses the next binary added beside them. This asks the directory instead,
// so a binary nobody thought to list is still answered for.
//
// Here rather than in verify-android-elf.py, which every installer already calls
// per file: Go's pack carries Termux shebangs in two syscall generators and a
// clang wrapper, and Java's lib/modules carries the path inside a jimage archive.
// None of those is reachable on Android and the archive cannot be rewritten in
// place, so folding the question into the shared checker would fail builds that
// are correct.
//
// What it costs when it goes wrong is silence of the worst kind: make runs every
// recipe line through the compiled-in shell and never reads SHELL, so every
// target that runs a command fails on a path the user never chose.
val verifyBundledShellPaths = tasks.register<Exec>("verifyBundledShellPaths") {
    group = "verification"
    description = "Checks no bundled binary names Termux's prefix as its shell."

    workingDir = rootProject.projectDir.parentFile
    commandLine(
        "python3", "scripts/patch-default-shell.py",
        "--check", "android/app/src/main/jniLibs/arm64-v8a",
    )

    onlyIf { jniLibsHoldsRealBinary() }

    failOnExit(
        "A bundled binary names a shell inside Termux's data directory.\n" +
            "The FAIL line above names the file. This app cannot read or\n" +
            "create that path, so whatever the binary wanted a shell for\n" +
            "fails with ENOENT wherever it runs on a device.\n" +
            "\n" +
            "Re-run the script that places the file --\n" +
            "scripts/download-termux-tools.sh, or download-node.sh for\n" +
            "libnode.so -- which rewrites the path where it installs it.\n" +
            "A binary neither of them places needs a call adding there.\n" +
            "\n" +
            "In CI this most likely means a cached jniLibs restored a binary\n" +
            "placed before that rewrite existed: bust the assets cache."
    )
}

// The pack is a directory in the checkout whether or not anything has filled it:
// its manifest is tracked and its payload is downloaded, so usr/ is what says a
// download has run.
/**
 * Two gates that examined the asset packs and the finished bundle only as
 * workflow steps, so their correctness rested on position in a step list.
 *
 * `release.yml` orders them correctly and has no conditional steps, so a
 * tag-driven release meets both. What meets neither is a local `./gradlew
 * bundleRelease`, which CONTRIBUTING documents as the way to build for Play. The
 * reasoning against relying on order is already written a few lines below, about
 * a different pack check: ordering is not a gate.
 *
 * The other two release-time checks are deliberately not wired here.
 * `check-termux-licenses.py` fetches upstream build files over the network, and
 * a gate that needs the internet on every local build is one that gets switched
 * off. `check-library-attribution.py` already runs unguarded on every pull
 * request.
 */
val checkPackOverlap = tasks.register<Exec>("checkPackOverlap") {
    group = "verification"
    description = "Checks no asset pack ships a file the base module already ships."

    workingDir = rootProject.projectDir.parentFile
    commandLine("python3", "scripts/check-pack-overlap.py")

    // Armed by ANY pack holding payload, because the script sweeps every pack
    // rather than one. This asked whether the RUBY pack had been downloaded,
    // borrowing the predicate from the Ruby-only sweep below without its
    // subject, so a checkout that had run download-java.sh and not
    // download-ruby.sh skipped the gate entirely and bundled the Java pack
    // without ever comparing it against the base module. Both payload trees are
    // gitignored, so that is an ordinary state to be in while working on one
    // pack. The reason for arming on payload at all is unchanged: a pack holding
    // only its tracked manifest overlaps nothing, and reporting that as no
    // overlap answers a question nobody asked.
    onlyIf { anyPackHoldsPayload() }

    failOnExit(
        "An asset pack ships a file the base module already ships. The\n" +
            "output above names it. Play delivers the pack over the base\n" +
            "install, so the duplicate wins on a Play device and loses on a\n" +
            "sideloaded one, and the two disagree about which build a library\n" +
            "came from.\n" +
            "\n" +
            "Re-run the download script for the pack it names."
    )
}

val checkBundleSize = tasks.register<Exec>("checkBundleSize") {
    group = "verification"
    description = "Checks the finished bundle against the limits Play enforces."

    val aab = layout.buildDirectory.file("outputs/bundle/release/app-release.aab")

    workingDir = rootProject.projectDir.parentFile
    commandLine(
        "python3", "scripts/check-bundle-size.py",
        "android/app/build/outputs/bundle/release/app-release.aab",
    )

    // The bundle is a Gradle product, so this cannot be a dependency of the
    // packaging step: it reads what that step produces. Wired as `finalizedBy`
    // on bundleRelease below, and guarded here so an invocation from anywhere
    // else does not fail for a file that was never built.
    onlyIf { aab.get().asFile.isFile }

    failOnExit(
        "The bundle is over a limit Play enforces. The output above names\n" +
            "which one and by how much. The base module cap is a compressed\n" +
            "download size, so it moves with what compresses, not only with\n" +
            "what is added."
    )
}

/**
 * Refuses a build whose bundled Python extension still decides this platform is
 * unknown.
 *
 * The extension carries its own detection, tests only win/darwin/linux, and
 * answers Unknown for android. `getEnvironmentActivationShellCommands` returns
 * immediately on Unknown, so selecting a virtual environment never activates it,
 * and the shell table maps Unknown to "other" while the shell really is bash.
 * `download-extensions.sh` rewrites the conditional; this is what stops a tree
 * assembled from an older extraction shipping without it.
 *
 * Armed by the directory rather than unconditionally: the lint and unit-test
 * jobs stub an empty extensions tree, and checking one would report a pass for
 * a question it never asked.
 */
val verifyPythonPlatform = tasks.register<Exec>("verifyPythonPlatform") {
    group = "verification"
    description = "Checks the bundled Python extension answers Linux on this platform."

    val extensions = File(projectDir, "src/main/assets/extensions")
    val tree = extensions.listFiles()
        ?.firstOrNull { it.name.startsWith("ms-python.python-") }

    workingDir = rootProject.projectDir.parentFile
    commandLine(
        "python3", "scripts/patch-python-platform.py", "--check",
        tree?.let { "android/app/src/main/assets/extensions/${it.name}" } ?: "missing",
    )

    onlyIf { tree != null }

    failOnExit(
        "The bundled Python extension still answers OSType.Unknown on this\n" +
            "platform. Selecting a virtual environment would silently fail to\n" +
            "activate it, which is the symptom users report as the interpreter\n" +
            "not being found.\n" +
            "\n" +
            "Re-run scripts/download-extensions.sh, which applies the rewrite\n" +
            "after verifying the VSIX against its pinned digest."
    )
}

/**
 * Refuses a build whose bundled venv records a home the interpreter cannot start
 * from.
 *
 * venv derives `home` from the base interpreter's own directory. That is right
 * for a plain `python3 -m venv` and wrong from inside an environment already in
 * use, where it names the outer environment's bin and the lib/python3.X beside
 * that holds site-packages and no standard library. PYTHONHOME covers for it
 * everywhere else, but the child venv bootstraps pip in has PYTHONHOME popped
 * out of its environment, so the note in pyvenv.cfg is all it has to go on.
 * `download-python.sh` rewrites the value; this is what stops a tree assembled
 * from an older extraction shipping without it.
 *
 * Armed by the tree rather than unconditionally, like verifyPythonPlatform above
 * and for the same reason: the lint and unit-test jobs stub an empty assets
 * directory, and checking one would report a pass for a question it never asked.
 */
val verifyVenvHome = tasks.register<Exec>("verifyVenvHome") {
    group = "verification"
    description = "Checks the bundled venv records a home the interpreter can start from."

    val stdlib = File(projectDir, "src/main/assets/usr/lib")
        .listFiles()
        ?.firstOrNull { it.name.startsWith("python3.") }
    val venvModule = stdlib?.let { File(it, "venv/__init__.py") }

    workingDir = rootProject.projectDir.parentFile
    commandLine(
        "python3", "scripts/patch-venv-home.py", "--check",
        stdlib?.let { "android/app/src/main/assets/usr/lib/${it.name}/venv/__init__.py" } ?: "missing",
    )

    onlyIf { venvModule?.isFile == true }

    failOnExit(
        "The bundled Python still writes a venv home that can name a directory\n" +
            "with no standard library under it. Creating an environment from\n" +
            "inside an activated one fails there while it bootstraps pip, and\n" +
            "reports only the child's exit status.\n" +
            "\n" +
            "Re-run scripts/download-python.sh, which applies the rewrite after\n" +
            "unpacking the interpreter."
    )
}

fun rubyPackHoldsPayload(): Boolean =
    File(rootProject.projectDir, "toolchain_ruby/src/main/assets/usr").isDirectory

// The same question of every pack, found the way check-pack-overlap.py finds
// them, so a pack added later is covered here without this line being edited.
fun anyPackHoldsPayload(): Boolean =
    rootProject.projectDir.listFiles()
        ?.any { it.isDirectory && it.name.startsWith("toolchain_") &&
            File(it, "src/main/assets/usr").isDirectory } == true

// The same question asked of the Ruby asset pack, which is in neither jniLibs
// nor the app module's assets.
//
// download-ruby.sh sweeps the pack once it has placed it, so a pack this build
// has just downloaded is answered for by the script that filled it. What that
// leaves open is the pack already sitting in the checkout: bundleRelease
// packages whatever is in that directory, and nothing between the two asks
// again. Ordering in release.yml, download before bundle, is what covers it
// today, and ordering is not a gate -- a local AAB built over an older pack, or
// a reordering, meets no check at all.
//
// Ruby alone, deliberately. Go's and Java's packs carry the same path in files
// that are unreachable on Android and are left as they are, so sweeping those
// would fail builds that are correct. scripts/patch-default-shell.py records
// which directories qualify and what each one had to answer first.
val verifyRubyPackShellPaths = tasks.register<Exec>("verifyRubyPackShellPaths") {
    group = "verification"
    description = "Checks no file in the Ruby asset pack names Termux's prefix as its shell."

    workingDir = rootProject.projectDir.parentFile
    commandLine(
        "python3", "scripts/patch-default-shell.py",
        "--check", "android/toolchain_ruby/src/main/assets",
    )

    // Armed by the payload rather than by the directory: a checkout that has
    // never run download-ruby.sh holds the pack's manifest and nothing else, and
    // sweeping that would report a pack clean on the strength of one JSON file.
    onlyIf { rubyPackHoldsPayload() }

    failOnExit(
        "The Ruby toolchain pack names a shell inside Termux's data\n" +
            "directory. The FAIL line above names the file, relative to the\n" +
            "pack's assets. This app cannot read or create that path, so\n" +
            "Ruby's system(), its backticks and the Makefiles mkmf writes\n" +
            "all fail with ENOENT on a device.\n" +
            "\n" +
            "Re-run scripts/download-ruby.sh, which rewrites the path in the\n" +
            "files it places and sweeps the pack afterwards. A pack left\n" +
            "over from a run that predates that rewrite is the likely cause."
    )
}


// Lint's own tasks read the asset directories by path rather than through the
// source set, so naming the task above does not reach them and they have to be
// told separately. Two do it today, `generateReleaseLintVitalReportModel` and
// `lintVitalAnalyzeRelease`, and AGP has moved that set between versions, so
// this matches the family rather than the two names: a task added later would
// otherwise fail the build at validation, and only in the release graph.
//
// That graph is the one nothing exercises. `release.yml` runs `assembleRelease`
// at tag time, `r8.yml` runs a lint task alone against a stub tree, and no other
// workflow runs a release task at all, so the build that cannot be retried
// casually is the one that would have found out.
tasks.matching { it.name.contains("Lint") || it.name.contains("lint") }
    .configureEach { dependsOn(bundleNotices) }

// A dependency of the merge, not of the package or the assemble: the point is
// to stop the wrong tree getting into an APK rather than to describe one that
// already did.
// The set, once. It was written twice before, here as task providers and again
// below as a list of strings for the graph check, with nothing keeping the two in
// step. The dangerous drift is one-directional and quiet: add a gate here and
// forget the other list, and the new gate is wired but never verified, which is
// the state the check exists to make impossible.
val packagingGates = listOf(
    checkPatchFingerprints, verifyServerTree, verifyBundledBinaries,
    verifyRequiredBinaries, verifyBundledShellPaths, verifyRubyPackShellPaths,
    verifyNativeAddons, verifyPackagedAlignment, checkPackOverlap,
    verifyPythonPlatform, verifyVenvHome,
)

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach {
        dependsOn(packagingGates)
        dependsOn(bundleNotices)
    }

// The same set, by name, so the graph can be asked whether they are in it.
//
// The wiring above is by name SHAPE, which is right: AGP has moved this family
// between versions and matching two literal names would fail the build at
// validation the first time a third appeared. What it cannot do is notice when
// the shape stops matching. Rename merge*Assets upstream and every gate here
// detaches at once, in silence, and an unchecked asset tree goes into an APK.
//
// So the shape is asserted against the graph that is about to run, not against
// the task container. `tasks.matching { ... }.isEmpty()` would be the obvious
// test and is the wrong one twice over: reading it realises every task in the
// project, which defeats configuration avoidance for the whole build, and it is
// vacuously true anyway, because mergeDebugAssets is registered in any Android
// application project whether or not this build is going to run it.
//
// Quiet unless the graph really packages assets. `./gradlew testDebugUnitTest`
// carries no *Assets merge at all (measured), and r8.yml's
// `optimizeReleaseResources lintVitalRelease` carries none either, so neither
// pays for this and neither can be failed by it.
//
// ⚠️ `gradle.taskGraph.whenReady` is not supported with Gradle's configuration
// cache, which is off here: gradle.properties sets only jvmargs and the AGP
// flags, and every build prints the "Consider enabling configuration cache"
// hint. Whoever turns it on has to move this to a build service or flow action.
// The question being asked is about the graph, so leaving that unexplained is
// the failure mode, not the assertion itself.
// Derived, not restated. What that changes, said plainly because it cuts both
// ways: the two lists can no longer disagree, so "wired but not verified" and
// "verified but not wired" both stop being possible states rather than becoming
// detectable ones. What the check below can still see is a gate that is wired and
// absent from the graph anyway, which is not hypothetical: `-x checkPatchFingerprints`
// on an assembleDebug does exactly that, and was measured failing with that gate
// named. What it can no longer see is a gate deleted from `packagingGates`
// outright, because that deletes it from both sides at once. The alarm above is
// what covers the detachment case that used to be argued for the second list.
val packagingGateNames = packagingGates.map { it.name }

gradle.taskGraph.whenReady {
    // This project's tasks only. The gates named below are this project's, so a
    // merge*Assets belonging to another module would be the wrong subject: it
    // would demand :app:'s checks of a graph that never packages :app:'s tree.
    // Nothing contributes one today (measured: the whole bundleRelease graph
    // holds exactly one, :app:mergeReleaseAssets, and the asset packs bring
    // generateAssetPackManifest instead), so this scopes the question rather
    // than filtering anything out yet.
    val prefix = "${project.path}:"
    val here = allTasks.filter { it.path.startsWith(prefix) }
    val packaging = here.map { it.name }
        .filter { it.startsWith("merge") && it.endsWith("Assets") }

    // The second question, and the reason it is not the first one again. Asking
    // "does this build package assets" with the SAME predicate the wiring uses
    // cannot notice the wiring detaching: rename the family upstream and this
    // list empties, the early return below fires, and the build goes quiet at
    // exactly the moment an unchecked tree could ship. So the graph is asked a
    // question the name shape does not answer.
    //
    // AGP builds the asset merge from MergeSourceSetFolders. Measured on this
    // project, AGP 9.3.1: mergeDebugAssets is
    // com.android.build.gradle.tasks.MergeSourceSetFolders_Decorated, and the only
    // other task of that type in the graph is mergeDebugJniLibFolders. The two
    // always travel together, also measured, across five graphs by --dry-run:
    // assembleDebug 1 and 1, bundleRelease 1 and 1, testDebugUnitTest 0 and 0,
    // `optimizeReleaseResources lintVitalRelease` 0 and 0, and `lint` 0 and 0
    // (37 tasks, none of them a merge). So the type answers the same question as
    // the name on every graph this project runs, while surviving the rename that
    // the name cannot.
    //
    // The `lint` figure is what says release.yml's lint step is cheap: it costs
    // no second asset merge and re-runs none of the ten gates below, which
    // matters because that job's budget already covers the server fetch, every
    // download, the addons, the unit tests, assembleRelease and bundleRelease.
    val merging = here.filter { it.javaClass.name.contains("MergeSourceSetFolders") }
    if (packaging.isEmpty() && merging.isNotEmpty()) {
        throw GradleException(
            "This build merges source-set folders (${merging.joinToString(", ") { it.name }}) " +
                "but no task in it is called merge*Assets, which is the name shape the " +
                "packaging checks are attached by.\n\nThat attachment is therefore reaching " +
                "nothing, and every check that decides whether the bundled tree may ship is " +
                "silently absent from this build. Re-point the tasks.matching predicate near " +
                "`packagingGates` at whatever the merge is called now."
        )
    }
    if (packaging.isEmpty()) return@whenReady

    val present = here.map { it.name }.toSet()
    val missing = packagingGateNames.filterNot { it in present }
    if (missing.isNotEmpty()) {
        throw GradleException(
            "This build packages an asset tree (${packaging.joinToString(", ")}) " +
                "without ${missing.size} of the ${packagingGateNames.size} checks " +
                "that decide whether the tree may ship:\n" +
                missing.joinToString("\n") { "  $it" } +
                "\n\nThe checks are attached by name shape to tasks called merge*Assets. " +
                // Which advice is right depends on how many are missing, and saying
                // the wrong one costs an afternoon in the common case. All of them
                // absent means the attachment stopped reaching this variant. Some of
                // them absent means it is reaching it, since the rest arrived, so the
                // predicate is not the suspect: `-x checkPatchFingerprints` on an
                // assembleDebug produces exactly this, measured, and sending that
                // reader to rewrite a working predicate is misdirection. The total
                // case is also nearly unproducible, because a genuine rename is
                // caught by the MergeSourceSetFolders branch above before it ever
                // reaches here.
                if (missing.size == packagingGateNames.size) {
                    "A task above matched that shape and carries none of them, so the " +
                        "attachment is no longer reaching this variant. Fix the " +
                        "tasks.matching predicate that wires them, not this message."
                } else {
                    "The rest of them are attached and present, so the wiring is intact. " +
                        "A gate excluded with -x is the usual cause; re-run without the " +
                        "exclusion, or restore the gate if it was deleted."
                }
        )
    }

    // The last question, and the one none of the ten gates can answer: is there
    // an editor in this build at all.
    //
    // Four of them would notice an absent server tree, and all four are armed by
    // `onlyIf { entryPoint.isFile }` on that very file, so a tree without it does
    // not fail them, it SKIPS them. What was then left weighed a runtime and some
    // binaries, and packaging produced a signed, installable APK and AAB that
    // open with nothing in them. release.yml refuses that in its own "Verify
    // assets" step, but `./gradlew bundleRelease` and scripts/build-aab.sh reach
    // the same packaging without passing through any workflow, and build-aab.sh
    // builds and signs only.
    //
    // Asked here rather than by rearming those four, because "absent is not
    // stale" is still right for each of them on its own: they judge what a tree
    // IS, and none is the place to demand that one exists. The graph is, because
    // the graph is what says an asset tree is about to be packaged.
    //
    // Armed by the same answer as verifyBundledBinaries, verifyRequiredBinaries
    // and verifyBundledShellPaths, which is what keeps it quiet where an absent
    // server tree is correct: the lint, unit-test and R8 jobs write a 64-byte
    // placeholder libnode.so and mkdir an empty assets/vscode-reh/out, so both
    // halves are placeholders together and neither is this check's business.
    // Real binaries beside no server tree is a half-prepared tree, and that
    // mixture is what no gate in this file can see.
    if (jniLibsHoldsRealBinary() &&
        !file("src/main/assets/vscode-reh/out/server-main.js").isFile
    ) {
        throw GradleException(
            "This build packages an asset tree (${packaging.joinToString(", ")}) " +
                "with no editor in it, so what it produces would install and open " +
                "empty.\n\n" +
                "android/app/src/main/assets/vscode-reh/out/server-main.js is " +
                "missing while jniLibs holds real binaries, so this is a " +
                "half-prepared tree rather than the placeholder the lint, " +
                "unit-test and R8 jobs write.\n\n" +
                "Restore it:\n" +
                "    ./scripts/fetch-vscode-oss.sh && ./scripts/package-assets.sh\n\n" +
                "scripts/build-all.sh runs both before every download, which is the " +
                "order that works. scripts/build-aab.sh does not: it builds and " +
                "signs only, and packages whatever the last preparation left behind."
        )
    }
}

// The finished bundle is a Gradle product, so the edge is `finalizedBy` rather
// than `dependsOn`: the file does not exist until bundleRelease has run. Writing
// it the other way round would either check a file that is not there yet or, if
// declared both ways, make a cycle.
// Named rather than inlined so that the edge and the assertion at the foot of
// this file are built from one list; see `finalizedGates` there.
val bundleSizeProducer = "bundleRelease"

// The privacy policy makes a closed statement about permissions, and the set it
// has to match is the MERGED one: AGP folds in every library's declarations, and
// the Play listing shows that result rather than this app's own manifest. The
// policy said "four and no others" while the installed app declared six, for at
// least one release, because the two extras appear in no committed file.
//
// `finalizedBy` for the same reason as the bundle check above: the merged
// manifest is a Gradle product and does not exist until this task has run.
//
// The RELEASE manifest alone, deliberately. Both variants would be a second run
// of a check whose subject is the artefact that ships, and debug's applicationId
// suffix renames the app-defined receiver permission, which the script normalises
// but which is not the name a Play reviewer reads.
//
// The script also runs from lint.yml and release.yml, where a bare checkout has
// no merged manifest and only the committed half can answer. It says which half
// ran rather than reporting a pass it did not earn, so the two placements are
// complementary rather than duplicates.
val checkPermissionClaims = tasks.register<Exec>("checkPermissionClaims") {
    group = "verification"
    description = "Checks the privacy policy names every permission the app ships with."

    workingDir = rootProject.projectDir.parentFile
    commandLine("python3", "scripts/check-permission-claims.py")

    failOnExit(
        "The published privacy policy does not describe the permissions " +
            "this build ships.\nThe FAIL line above names which way it " +
            "disagrees.\n" +
            "\n" +
            "docs/PRIVACY_POLICY.md is what the Play listing links to, so a " +
            "reader comparing\nthe two sees an undisclosed capability rather " +
            "than a library dependency.\ndocs/06-SECURITY.md section 4.1 " +
            "carries the same two tables and has to move with it."
    )
}

val permissionClaimsProducer = "processReleaseMainManifest"

// Both gates are attached by an EXACT task name, which is the wiring the
// assertion further up deliberately does not trust anywhere else: the ten
// packaging gates are matched by name SHAPE and then checked against the live
// graph, because a name that stops matching detaches in silence and takes an
// unchecked asset tree into an APK with it.
//
// The wiring and the check below are built from this one list, so a pair
// deleted here loses both at once and neither can be left pointing at a name
// the other does not use.
//
// The list names both gates, so it and the wiring under it sit after both
// `register` calls. Position costs nothing: `tasks.matching` is a live view and
// `configureEach` reaches tasks AGP has not created yet, which is every task
// either producer names.
val finalizedGates = listOf(
    bundleSizeProducer to checkBundleSize,
    permissionClaimsProducer to checkPermissionClaims,
)

finalizedGates.forEach { (producer, gate) ->
    tasks.matching { it.name == producer }.configureEach {
        finalizedBy(gate)
    }
}

// What this catches, exactly: the producer runs and its gate does not. That is
// a gate excluded with `-x`, a gate deleted from this file with its producer
// left behind, and any edit to the `finalizedBy` wiring above that stops the
// edge being created. Without it the Play size cap and the privacy-policy
// permission check leave `./gradlew bundleRelease` and `scripts/build-aab.sh`
// in silence, and the first symptom is a bundle Play rejects at upload, after
// the GitHub release is already public.
//
// What it cannot catch: a producer AGP renames is simply absent from the graph,
// so the pair never fires. Neither producer is left resting on that.
// `bundleRelease` is named by release.yml and by scripts/build-aab.sh, and
// Gradle fails an invocation it cannot resolve, so a rename of that one is loud
// wherever it matters. `processReleaseMainManifest` is named by nothing but this
// file, so release.yml asks the artefact instead: it runs
// `check-permission-claims.py --require-merged` after `bundleRelease`, which
// fails when no release merged manifest was judged, whatever the reason. A
// rename therefore costs a local `./gradlew bundleRelease` the merged half of
// that check, and costs the published build nothing.
//
// A second whenReady rather than a branch inside the first: that one is
// registered further up this file, and a lambda cannot capture a val declared
// after it. Gradle accepts any number of these listeners.
gradle.taskGraph.whenReady {
    val prefix = "${project.path}:"
    val present = allTasks.filter { it.path.startsWith(prefix) }.map { it.name }.toSet()
    val detached = finalizedGates
        .filter { (producer, gate) -> producer in present && gate.name !in present }
        .map { (producer, gate) -> "  ${gate.name}, which should follow $producer" }
    if (detached.isNotEmpty()) {
        throw GradleException(
            "This build runs a task that a verification gate is attached to, and the " +
                "gate is not in the graph:\n" +
                detached.joinToString("\n") +
                "\n\nThe producer ran, so its name is still current; what is missing is the " +
                "edge. Excluding the gate with -x produces this and is the usual cause, in " +
                "which case re-run without the exclusion. Otherwise the `finalizedGates` " +
                "list near the foot of app/build.gradle.kts, which is what creates the " +
                "edge, no longer names the pair."
        )
    }
}
