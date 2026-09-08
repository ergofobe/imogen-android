import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Derived, never typed. A version number copied into source is a version number that
// goes stale: the server's health check claimed 0.1.0 through three releases doing
// exactly that. Missing git metadata is normal rather than an error — a `git archive`
// tarball has no history — so this yields an empty string and the label falls back to
// the plain `versionName`.
//
// Tags are suppressed (`--match=` matches none, leaving `--always` to print the
// abbreviated sha alone) because the label already carries `versionName`; a leading
// `v0.2.3-5-` would only repeat it. It is also what makes this work on CI, where
// `actions/checkout` clones at depth 1 and there are no tags to find.
//
// Every way this can fail warns. The failures are silent by design — the build must not
// break over absent metadata — but a debug build that has quietly lost its commit looks
// exactly like a correct release build, so the one signal is this log line and the unit
// test that asserts a debug build carries a sha.
val gitSha: String = run {
    val described = try {
        val out = providers.exec {
            workingDir = rootDir
            commandLine("git", "describe", "--always", "--dirty", "--abbrev=7", "--match=")
            isIgnoreExitValue = true
        }
        val exit = out.result.get().exitValue
        if (exit == 0) {
            out.standardOutput.asText.get().trim()
        } else {
            logger.warn("imogen: `git describe` exited $exit; this build will not name its commit.")
            ""
        }
    } catch (e: Exception) {
        logger.warn("imogen: could not run git (${e.message}); this build will not name its commit.")
        ""
    }
    // The value is pasted into a generated string literal, so anything that is not a
    // short sha is dropped rather than embedded.
    when {
        described.isEmpty() -> ""
        described.matches(Regex("[0-9a-f]{7,40}(-dirty)?")) -> described
        else -> {
            logger.warn("imogen: ignoring unexpected `git describe` output '$described'.")
            ""
        }
    }
}

android {
    namespace = "com.imogen.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.imogen.android"
        // 26 is where java.util.Base64 arrives, which the SDK's PKCE uses. Below it the
        // choice is a desugaring dependency or a fork of the SDK, and neither is worth
        // the versions of Android that are left down there.
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.2.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // A release is identified by its tag. Recording the sha here as well would
            // invalidate the build cache on every commit for a string nobody reads.
            buildConfigField("String", "GIT_SHA", "\"\"")
        }
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/INDEX.LIST",
            "/META-INF/DEPENDENCIES",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.imogen.sdk) {
        // The SDK leaves the engine to whoever uses it and ships CIO for the JVM. On
        // Android that would put two engines on the classpath and let a service loader
        // pick one, which is not a thing to leave to chance.
        exclude(group = "io.ktor", module = "ktor-client-cio")
    }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window.size)
    implementation(libs.compose.material3.adaptive.navigation.suite)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.exifinterface)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource.okhttp)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
