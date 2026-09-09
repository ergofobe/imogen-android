package com.imogen.android.ui.timeline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.imogen.sdk.TimelineBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The rail is drawn over the last grid column, so what it captures is what that column
 * loses. Only the thumb may take a touch; everywhere else in the strip belongs to the
 * photographs underneath.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScrubberHitAreaTest {

    @get:Rule
    val compose = createComposeRule()

    // A dozen years, so the rail has somewhere to go.
    private val layout = TimelineLayout(
        TimelineIndex((0 until 12).map { TimelineBucket("${2026 - it}-06-01", 300) }),
        TimelineMetrics(columns = 3, rowHeight = 137f, headerHeight = 40f, viewportHeight = 800f),
    )

    private var taps = 0
    private var scrubbing = false
    private var seeks = 0

    private fun show() = compose.setContent {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("grid")
                    .clickable { taps++ },
            )
            Scrubber(
                layout = layout,
                day = 0,
                onScrubbing = { scrubbing = it },
                onSeek = { seeks++ },
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }

    @Test
    fun `a tap in the rail's column, away from the thumb, reaches the grid`() {
        show()
        compose.onNodeWithTag("grid").performTouchInput {
            click(Offset(width - 20.dp.toPx(), height / 2f))
        }
        compose.runOnIdle { assertEquals(1, taps) }
    }

    @Test
    fun `a drag in the rail's column, away from the thumb, does not scrub`() {
        show()
        compose.onNodeWithTag("grid").performTouchInput {
            down(Offset(width - 20.dp.toPx(), height / 2f))
            moveBy(Offset(0f, 200f))
            moveBy(Offset(0f, 200f))
        }
        compose.runOnIdle {
            assertFalse(scrubbing)
            assertEquals(0, seeks)
        }
        compose.onNodeWithTag("grid").performTouchInput { up() }
    }

    @Test
    fun `a drag that starts on the thumb scrubs`() {
        show()
        compose.onNodeWithTag("grid").performTouchInput {
            // At rest on the newest day the thumb sits at the top, 32dp wide inside a 6dp
            // margin, so this is its centre.
            down(Offset(width - 22.dp.toPx(), 24.dp.toPx()))
            moveBy(Offset(0f, 200f))
            moveBy(Offset(0f, 200f))
        }
        compose.runOnIdle {
            assertTrue(scrubbing)
            assertTrue(seeks > 0)
        }
        compose.onNodeWithTag("grid").performTouchInput { up() }
        compose.runOnIdle { assertFalse(scrubbing) }
    }
}
