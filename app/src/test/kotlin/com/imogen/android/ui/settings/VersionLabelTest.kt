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
    fun `an absent sha falls back to the version rather than an empty bracket`() {
        assertEquals("0.2.3", versionLabel("0.2.3", ""))
        assertEquals("0.2.3", versionLabel("0.2.3", "   "))
    }

    @Test
    fun `the build records a well-formed sha, or nothing at all`() {
        // The build drops anything that is not a short sha rather than pasting it into a
        // generated string literal. This is that contract seen from the other side.
        assertTrue(
            "GIT_SHA is '${BuildConfig.GIT_SHA}'",
            BuildConfig.GIT_SHA.matches(Regex("([0-9a-f]{7,40}(-dirty)?)?")),
        )
    }

    @Test
    fun `this debug build knows which commit it came from`() {
        // The assertion with teeth. Every path on the Gradle side degrades to an empty
        // sha in silence — a refused repository, no git on the PATH, a flag that stops
        // meaning what it meant — and a debug build that has quietly lost its commit is
        // indistinguishable from a correct release build. That failure is invisible
        // everywhere except here.
        //
        // Safe to assert unconditionally: unit tests run the debug variant, and they run
        // from a checkout. The one case with no history is a source archive, which has no
        // submodule either and so cannot build at all.
        assertTrue("this build recorded no commit", BuildConfig.GIT_SHA.isNotEmpty())
    }

    @Test
    fun `the label About shows is the manifest's version, not a typed one`() {
        // Pins the wiring rather than the text: fails if anyone names a version by hand
        // here instead of reading it from the manifest. Deliberately does not restate
        // that the sha is present — the test above owns that, and one broken git
        // invocation should fail one test.
        assertEquals(
            versionLabel(BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA),
            aboutVersionLabel(),
        )
    }
}
