package com.imogen.android.ui.settings

/**
 * What About says the running build is.
 *
 * A release carries no sha and reads as the bare `versionName`. A debug build names the
 * commit as well, because the version on its own is true of every build made between two
 * tags and so answers nothing about which one is on the phone. So is a build from a
 * checkout with no history, which is why an absent sha falls back rather than failing.
 */
fun versionLabel(versionName: String, gitSha: String): String =
    if (gitSha.isBlank()) versionName else "$versionName ($gitSha)"
