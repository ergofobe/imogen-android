package com.imogen.android.ui.settings

import com.imogen.android.BuildConfig

/**
 * What About says the running build is.
 *
 * A release carries no sha and reads as the bare `versionName`. A debug build names the
 * commit as well, because the version on its own is true of every build made between two
 * tags and so answers nothing about which one is on the phone. A checkout with no history
 * produces no sha either, which is why an absent one falls back rather than failing.
 */
fun versionLabel(versionName: String, gitSha: String): String =
    if (gitSha.isBlank()) versionName else "$versionName ($gitSha)"

/**
 * The label for this build, and the only place allowed to say which build that is. A
 * version named at a call site is a version that drifts from the manifest — the mistake
 * that had the server's health check reporting 0.1.0 through three releases.
 */
fun aboutVersionLabel(): String = versionLabel(BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA)
