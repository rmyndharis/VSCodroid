pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "VSCodroid"
include(":app")

// On-demand toolchain asset packs (Play Asset Delivery)
include(":toolchain_go")
include(":toolchain_ruby")
include(":toolchain_java")
