import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Load signing config from signing.properties (local) or env vars (CI)
val signingProps = Properties()
rootProject.file("signing.properties").takeIf { it.exists() }?.inputStream()?.use { signingProps.load(it) }
fun signingProp(key: String, envVar: String, fallback: String = "") =
    signingProps.getProperty(key) ?: System.getenv(envVar) ?: fallback

android {
    namespace = "com.vscodroid"
    compileSdk = 36

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
        targetSdk = 36
        versionCode = 12
        versionName = "1.1.0"

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
        buildConfigField(
            "long",
            "EXTRACTED_ASSET_BYTES",
            "${fileTree("src/main/assets").files.sumOf { it.length() }}L"
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
        // maxOfOrNull, because the jobs that build without a real asset tree
        // (lint, unit tests, R8) have nothing to take a maximum of and maxOf
        // throws on an empty tree.
        buildConfigField(
            "long",
            "LARGEST_ASSET_BYTES",
            "${fileTree("src/main/assets").files.maxOfOrNull { it.length() } ?: 0L}L"
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
            "${fileTree("src/main/assets/usr").files.sumOf { it.length() }}L"
        )
        buildConfigField(
            "long",
            "BUNDLED_EXTENSION_BYTES",
            "${fileTree("src/main/assets/extensions").files.sumOf { it.length() }}L"
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
        }
    }

    // AAPT's default ignoreAssetsPattern skips files/dirs starting with "_"
    // (pattern "_*"). npm's @sigstore/protobuf-specs has a __generated__/
    // directory that gets silently dropped, breaking `npm install`. Override
    // to only skip dotfiles and VCS metadata (keep underscore-prefixed dirs).
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
    assetPacks += listOf(":toolchain_go", ":toolchain_ruby", ":toolchain_java")

    // The attribution documents, packaged so they reach the device. See
    // `bundleNotices` at the foot of this file for why they are copied rather
    // than committed here.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/notices"))

    lint {
        // The baseline is what makes this affordable: the 17 issues recorded in
        // lint-baseline.xml are filtered out of every report, so what remains is
        // what arrived after it was taken.
        //
        // An entry naming a file above this module carries whatever path lint
        // recorded, and that depends on where the checkout sat. Written from
        // under the home directory it goes in through lint's `$HOME` path
        // variable and then matches only the checkout that produced it. Written
        // from outside the home directory the same entry reads
        // `../gradle/libs.versions.toml` and matches wherever the module is.
        // Both forms were checked on two checkouts of this repository and
        // against one CI report: the 39 entries removed for that file were of
        // the first form and matched in neither the second checkout nor on the
        // runner, while a regenerated `../` entry matched in both checkouts. So
        // read which form an entry has before deleting it. Only the first is
        // confined to one checkout, and only there do the warnings it had been
        // hiding come back. Regenerating the baseline is not the answer, since
        // lint writes such an entry straight back; lint already names the
        // entries that stopped matching, under LintBaselineFixed, and those are
        // the ones to delete by hand.
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
    testImplementation("org.json:json:20250107")

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
    isIgnoreExitValue = true
    // Captured here rather than read inside doLast, where the receiver is Task
    // and this property is not on it.
    val result = executionResult
    doLast {
        if (result.get().exitValue != 0) {
            throw GradleException(
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
    }
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

    isIgnoreExitValue = true
    val result = executionResult
    doLast {
        if (result.get().exitValue != 0) {
            throw GradleException(
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
    }
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
// is also in neither CI cache key -- measured, 0 hits in both, against 1 for
// verify-server-tree.py -- so tightening the checker does not invalidate a cached
// binary that only ever passed the looser version of it.
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

    val jniDir = file("src/main/jniLibs/arm64-v8a")

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
    onlyIf {
        jniDir.listFiles()?.any {
            it.name.endsWith(".so") && it.length() >= jniLibsStubCeiling
        } == true
    }

    isIgnoreExitValue = true
    val result = executionResult
    doLast {
        if (result.get().exitValue != 0) {
            throw GradleException(
                "A bundled binary in jniLibs cannot load on Android.\n" +
                    "The FAIL line above names the file and the property it fails.\n" +
                    "\n" +
                    "These are placed by the download scripts, so re-run the one that\n" +
                    "owns the file -- scripts/download-node.sh, download-python.sh,\n" +
                    "download-termux-tools.sh, download-musl-loader.sh, or\n" +
                    "fetch-vscode-oss.sh for libripgrep.so -- and let it fail there,\n" +
                    "where the message says which upstream package it came from.\n" +
                    "\n" +
                    "In CI this most likely means a cached jniLibs restored a binary\n" +
                    "that predates a change to scripts/verify-android-elf.py, which is\n" +
                    "in neither cache key: bust the assets cache rather than refetching."
            )
        }
    }
}

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
// Copied from the repository root rather than committed under src/main/assets,
// because a second copy is a copy that goes stale, and a stale licence notice is
// worse than an absent one: it is a claim about terms that is no longer true.
// The two files are ~44 KiB of markdown, which deflates to roughly 15 KiB in the
// APK, about 0.01% of the base module. They grow as the tree does, since every
// binary in it has to be named in both, so treat those as the order of magnitude
// rather than as figures to check against.
//
// They are read out of the APK by MainActivity's licences dialog and never
// extracted, which is why they are a separate assets source directory: it keeps
// them out of the src/main/assets sum that sizes first-run extraction.
val bundleNotices = tasks.register<Copy>("bundleNotices") {
    group = "build"
    description = "Copies the attribution documents into the APK's assets."

    val repoRoot = rootProject.projectDir.parentFile
    from(File(repoRoot, "NOTICE.md"))
    from(File(repoRoot, "docs/LEGAL_NOTICES.md"))
    into(layout.buildDirectory.dir("generated/notices"))

    // The names the app opens. Kept beside the copy so a rename here fails the
    // build rather than emptying the dialog on a device: com.vscodroid.util
    // .Notices.BUNDLED lists the same two, and NoticesTest compares the lists.
}

// A dependency of the merge, not of the package or the assemble: the point is
// to stop the wrong tree getting into an APK rather than to describe one that
// already did.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach {
        dependsOn(checkPatchFingerprints, verifyServerTree, verifyBundledBinaries)
        dependsOn(bundleNotices)
    }
