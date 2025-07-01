pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" // Or the Kotlin version you're using
    }
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io") // ✅ Ensure JitPack is included
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") // ✅ Required for GitHub dependencies
        flatDir {
            dirs("src/main/libs")// Ensure this is correct
        }

    }
}

rootProject.name = "printer"
include(":app")
