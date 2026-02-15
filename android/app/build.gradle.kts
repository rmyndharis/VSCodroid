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
        versionCode = 5
        versionName = "0.2.3"

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
        // CI: Don't abort on lint errors — report them but allow the build to pass.
        // A baseline file captures pre-existing issues so only NEW issues are flagged.
        abortOnError = false
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
