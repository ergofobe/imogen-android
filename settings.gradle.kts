pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "imogen-android"

include(":app")

/**
 * The SDK is a submodule rather than a published artifact.
 *
 * There is no Maven Central release of `com.imogen:imogen-sdk` yet, and vendoring a copy
 * of it here would mean two copies of the API contract drifting apart — which is the exact
 * failure the conformance suite in that repository exists to prevent. A composite build
 * keeps one copy, at a commit this repository pins.
 */
includeBuild("imogen-sdk/kotlin")
