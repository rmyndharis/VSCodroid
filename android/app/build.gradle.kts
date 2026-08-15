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
        versionCode = 11
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
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

    lint {
        // The baseline is what makes this affordable: the 59 issues recorded in
        // lint-baseline.xml are filtered out of every report, so what remains is
        // what arrived after it was taken.
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
    // The android.jar stub throws "not mocked" from every org.json method, which puts
    // any code that parses a manifest out of reach of a JVM test. No unit test parsed
    // JSON before this, so replacing the stub cannot change an existing verdict.
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

// A dependency of the merge, not of the package or the assemble: the point is
// to stop the wrong tree getting into an APK rather than to describe one that
// already did.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(checkPatchFingerprints) }
