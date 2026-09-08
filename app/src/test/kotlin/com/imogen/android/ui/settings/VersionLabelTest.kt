package com.imogen.android.ui.settings

import com.imogen.android.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionLabelTest {

    @Test
    fun `a release build shows the version on its own`() {
        assertEquals("0.2.3", versionLabel("0.2.3", ""))
    }

    @Test
    fun `a debug build names the commit it was built from`() {
        // "0.2.3" is true of every build made between two tags, which is no answer at all
        // to which build is on the phone.
        assertEquals("0.2.3 (71f0202)", versionLabel("0.2.3", "71f0202"))
    }

    @Test
    fun `a build from a modified tree says so`() {
        assertEquals("0.2.3 (71f0202-dirty)", versionLabel("0.2.3", "71f0202-dirty"))
    }

    @Test
    fun `a checkout with no history falls back to the version`() {
        // A `git archive` tarball has no history, so the build records an empty sha
        // rather than failing. Whitespace counts as empty: it is what a trimmed blank
        // command output leaves behind.
        assertEquals("0.2.3", versionLabel("0.2.3", ""))
        assertEquals("0.2.3", versionLabel("0.2.3", "   "))
    }

    @Test
    fun `every label is non-empty and starts with the manifest version`() {
        // The guard against the two drifting apart: whatever the git side produces, what
        // reaches the screen still begins with what `versionName` says.
        for (sha in listOf("", "   ", "71f0202", "71f0202-dirty")) {
            val label = versionLabel(BuildConfig.VERSION_NAME, sha)
            assertTrue("empty label for sha '$sha'", label.isNotEmpty())
            assertTrue("'$label' does not start with the manifest version", label.startsWith(BuildConfig.VERSION_NAME))
        }
    }

    @Test
    fun `the label this build would actually show holds to the same guard`() {
        // Not a restatement of the case above: this one runs against the real
        // `buildConfigField`, so a git command that started returning something
        // unexpected fails here rather than on a phone.
        val label = versionLabel(BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA)
        assertTrue("empty label", label.isNotEmpty())
        assertTrue("'$label' does not start with the manifest version", label.startsWith(BuildConfig.VERSION_NAME))
    }
}
