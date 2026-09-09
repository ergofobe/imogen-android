package com.imogen.android.ui.timeline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
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
    private val seeks = mutableListOf<Int>()
    // The grid follows every seek, as the timeline's does.
    private var day by mutableIntStateOf(0)

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
                day = day,
                onScrubbing = { scrubbing = it },
                onSeek = {
                    seeks += it
                    day = it
                },
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
            assertTrue(seeks.isEmpty())
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
            // Down the rail is back in time: a thumb that followed the finger has left
            // the newest day, and one that stayed put would still report it.
            assertTrue(seeks.last() > 0)
        }
        compose.onNodeWithTag("grid").performTouchInput { up() }
        compose.runOnIdle { assertFalse(scrubbing) }
    }

    @Test
    fun `dragging past the end and back moves the thumb at once`() {
        show()
        compose.onNodeWithTag("grid").performTouchInput {
            down(Offset(width - 22.dp.toPx(), 24.dp.toPx()))
            // Well past the bottom of the rail, then a little way back up.
            moveBy(Offset(0f, height * 2f))
        }
        val bottom = compose.runOnIdle { seeks.last() }
        compose.onNodeWithTag("grid").performTouchInput { moveBy(Offset(0f, -height / 4f)) }
        compose.runOnIdle { assertTrue(seeks.last() < bottom) }
        compose.onNodeWithTag("grid").performTouchInput { up() }
    }

    @Test
    fun `the year marks do not take a tap while they are shown`() {
        show()
        compose.onNodeWithTag("grid").performTouchInput {
            down(0, Offset(width - 22.dp.toPx(), 24.dp.toPx()))
            moveBy(0, Offset(0f, 1500f))
        }
        // In a block of its own, so the thumb has been laid out where the drag left it
        // and the marks have faded in before the second finger lands — on the newest
        // year's mark, at the top of the rail and a long way above the thumb.
        val mark = compose.onNodeWithText("2026").assertIsDisplayed().getBoundsInRoot()
        val centre = with(compose.density) {
            Offset((mark.left + mark.right).toPx() / 2, (mark.top + mark.bottom).toPx() / 2)
        }
        compose.onNodeWithTag("grid").performTouchInput {
            down(1, centre)
            up(1)
        }
        compose.runOnIdle { assertEquals(1, taps) }
        compose.onNodeWithTag("grid").performTouchInput { up(0) }
    }
}
