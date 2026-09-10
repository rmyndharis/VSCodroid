import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val signingProps = Properties()
rootProject.file("signing.properties").takeIf { it.exists() }?.inputStream()?.use { signingProps.load(it) }
fun signingProp(key: String, envVar: String, fallback: String = "") =
    signingProps.getProperty(key) ?: System.getenv(envVar) ?: fallback

val bundleNotices = tasks.register<Sync>("bundleNotices") {
    group = "build"
    description = "Copies the attribution and licence documents into the APK's assets."

    val repoRoot = rootProject.projectDir.parentFile

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

        @Suppress("OldTargetApi")
        targetSdk = 36
        versionCode = 14
        versionName = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }

        val assetsDir = file("src/main/assets")
        val assetSizes = fileTree(assetsDir).files.associate { it.path to it.length() }
        val assetBytesUnder = { subdir: String ->
            val prefix = File(assetsDir, subdir).path + File.separator
            assetSizes.entries.filter { it.key.startsWith(prefix) }.sumOf { it.value }
        }

        val nlsPrefix = File(assetsDir, "nls").path + File.separator
        val nlsSizes = assetSizes.filterKeys { it.startsWith(nlsPrefix) }.values
        buildConfigField(
            "long",
            "EXTRACTED_ASSET_BYTES",
            "${assetSizes.values.sum() - nlsSizes.sum()}L"
        )

        buildConfigField(
            "long",
            "LARGEST_ASSET_BYTES",
            "${assetSizes.values.maxOrNull() ?: 0L}L"
        )

        buildConfigField(
            "long",
            "BUNDLED_USR_BYTES",
            "${assetBytesUnder("usr")}L"
        )

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

            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"

            enableUnitTestCoverage = providers.gradleProperty("vscodroidCoverage").isPresent
        }
    }

    aaptOptions {
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*.orig:*~"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    assetPacks += listOf(":toolchain_ruby", ":toolchain_java")

    bundle {
        language {

            enableSplit = false
        }
    }

    sourceSets["main"].assets.srcDir(bundleNotices)

    lint {

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

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)

    implementation(libs.google.material)

    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.viewpager2)

    implementation(libs.androidx.browser)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.play.asset.delivery.ktx)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)

    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)

    testImplementation(libs.org.json)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
}

tasks.withType<Test> {
    useJUnitPlatform()

    inputs.file(layout.projectDirectory.file("build.gradle.kts"))
        .withPropertyName("appBuildScript")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    inputs.file(rootProject.projectDir.resolve("settings.gradle.kts"))
        .withPropertyName("settingsScript")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    inputs.file(layout.projectDirectory.file("src/main/AndroidManifest.xml"))
        .withPropertyName("appManifest")

    inputs.files(fileTree("src/main/assets") { include("*.js") })
        .withPropertyName("bootstrapScripts")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree("src/main/assets/extensions"))
        .withPropertyName("bundledExtensions")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree(rootProject.projectDir.parentFile.resolve("patches")))
        .withPropertyName("serverPatches")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    inputs.file(rootProject.projectDir.parentFile.resolve("scripts/build-nls-bundles.py"))
        .withPropertyName("interfaceBundleScript")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(file("src/main/res/xml/locales_config.xml"))
        .withPropertyName("localesConfig")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree("src/main/res") { include("values-*/strings.xml") })
        .withPropertyName("translatedStrings")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    inputs.file(rootProject.projectDir.parentFile.resolve("scripts/build-vscode-oss.sh"))
        .withPropertyName("codeOssBuildScript")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    inputs.files(fileTree(rootProject.projectDir.parentFile.resolve("licenses")))
        .withPropertyName("licenseTexts")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    inputs.files(
        rootProject.projectDir.parentFile.resolve("README.md"),
        rootProject.projectDir.parentFile.resolve("NOTICE.md"),
        rootProject.projectDir.parentFile.resolve("MILESTONES.md"),
        fileTree(rootProject.projectDir.parentFile.resolve("docs")) { include("*.md") },
    )
        .withPropertyName("statedRequirements")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    inputs.file(rootProject.projectDir.parentFile.resolve("branding/product.json"))
        .withPropertyName("brandingOverlay")

    inputs.files(
        fileTree("src/main/res/layout"),
        fileTree("src/main/res/values"),
    )
        .withPropertyName("readableResources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

fun Exec.failOnExit(help: String) {
    isIgnoreExitValue = true
    val result = executionResult
    doLast {
        if (result.get().exitValue != 0) {
            throw GradleException(help)
        }
    }
}

val checkPatchFingerprints = tasks.register<Exec>("checkPatchFingerprints") {
    group = "verification"
    description = "Checks the server tree in assets/ carries every patch in patches/."

    val serverTree = "android/app/src/main/assets/vscode-reh"
    val entryPoint = file("src/main/assets/vscode-reh/out/server-main.js")

    workingDir = rootProject.projectDir.parentFile
    commandLine("python3", "scripts/check-patch-fingerprints.py", serverTree)

    onlyIf { entryPoint.isFile }

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

val jniLibsStubCeiling = 1000L

fun jniLibsHoldsRealBinary(): Boolean =
    file("src/main/jniLibs/arm64-v8a").listFiles()
        ?.any { it.name.endsWith(".so") && it.length() >= jniLibsStubCeiling } == true

val verifyBundledBinaries = tasks.register<Exec>("verifyBundledBinaries") {
    group = "verification"
    description = "Checks every bundled binary in jniLibs can load on Android."

    workingDir = rootProject.projectDir.parentFile
    commandLine(
        "python3", "scripts/verify-android-elf.py",
        "--dir", "android/app/src/main/jniLibs/arm64-v8a",

        "--lib-dir", "android/app/src/main/assets/usr/lib",
        "--lib-dir", "android/app/src/main/jniLibs/arm64-v8a",
    )

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

val requiredJniLibs = listOf(
    "libbash.so",

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

val checkPackOverlap = tasks.register<Exec>("checkPackOverlap") {
    group = "verification"
    description = "Checks no asset pack ships a file the base module already ships."

    workingDir = rootProject.projectDir.parentFile
    commandLine("python3", "scripts/check-pack-overlap.py")

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

    onlyIf { aab.get().asFile.isFile }

    failOnExit(
        "The bundle is over a limit Play enforces. The output above names\n" +
            "which one and by how much. The base module cap is a compressed\n" +
            "download size, so it moves with what compresses, not only with\n" +
            "what is added."
    )
}

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

fun anyPackHoldsPayload(): Boolean =
    rootProject.projectDir.listFiles()
        ?.any { it.isDirectory && it.name.startsWith("toolchain_") &&
            File(it, "src/main/assets/usr").isDirectory } == true

val verifyRubyPackShellPaths = tasks.register<Exec>("verifyRubyPackShellPaths") {
    group = "verification"
    description = "Checks no file in the Ruby asset pack names Termux's prefix as its shell."

    workingDir = rootProject.projectDir.parentFile
    commandLine(
        "python3", "scripts/patch-default-shell.py",
        "--check", "android/toolchain_ruby/src/main/assets",
    )

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

tasks.matching { it.name.contains("Lint") || it.name.contains("lint") }
    .configureEach { dependsOn(bundleNotices) }

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

val packagingGateNames = packagingGates.map { it.name }

gradle.taskGraph.whenReady {

    val prefix = "${project.path}:"
    val here = allTasks.filter { it.path.startsWith(prefix) }
    val packaging = here.map { it.name }
        .filter { it.startsWith("merge") && it.endsWith("Assets") }

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

val bundleSizeProducer = "bundleRelease"

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

val finalizedGates = listOf(
    bundleSizeProducer to checkBundleSize,
    permissionClaimsProducer to checkPermissionClaims,
)

finalizedGates.forEach { (producer, gate) ->
    tasks.matching { it.name == producer }.configureEach {
        finalizedBy(gate)
    }
}

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
