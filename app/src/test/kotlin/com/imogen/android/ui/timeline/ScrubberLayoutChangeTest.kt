package com.imogen.android.ui.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.imogen.sdk.TimelineBucket
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The library does not hold still while a finger is on the thumb. A refresh lands, a trash
 * completes, the device turns — and the drag has to end on the finger coming up, not on
 * whichever of those happened to rebuild the rail.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScrubberLayoutChangeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun layoutOf(years: Int) = TimelineLayout(
        TimelineIndex((0 until years).map { TimelineBucket("${2026 - it}-06-01", 300) }),
        TimelineMetrics(columns = 3, rowHeight = 137f, headerHeight = 40f, viewportHeight = 800f),
    )

    private var scrubbing = false
    private val seeks = mutableListOf<Int>()
    private var layout by mutableStateOf(layoutOf(12))
    private var day by mutableIntStateOf(0)

    private fun show() = compose.setContent {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("grid"),
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

    private fun takeHoldOfTheThumb() {
        compose.onNodeWithTag("grid").performTouchInput {
            // At rest on the newest day the thumb sits at the top, 32dp wide inside a 6dp
            // margin, so this is its centre.
            down(Offset(width - 22.dp.toPx(), 24.dp.toPx()))
            moveBy(Offset(0f, 200f))
            moveBy(Offset(0f, 200f))
        }
        compose.runOnIdle { assertTrue(scrubbing) }
    }

    @Test
    fun `a refresh while the thumb is held still ends the drag on release`() {
        show()
        takeHoldOfTheThumb()

        compose.runOnIdle { layout = layoutOf(13) }
        compose.waitForIdle()

        compose.onNodeWithTag("grid").performTouchInput { up() }
        compose.runOnIdle { assertFalse(scrubbing) }
    }

    @Test
    fun `a refresh that shortens the library does not strand the drag past the end`() {
        show()
        compose.onNodeWithTag("grid").performTouchInput {
            down(Offset(width - 22.dp.toPx(), 24.dp.toPx()))
            // All the way to the oldest day, so the shorter index no longer holds it.
            moveBy(Offset(0f, height * 2f))
        }
        compose.runOnIdle { assertTrue(scrubbing) }

        compose.runOnIdle { layout = layoutOf(2) }
        compose.waitForIdle()

        // Lifted where it was, without moving: the release seek is the one that has to be
        // brought back inside the shorter index, since the grid indexes its buckets raw.
        compose.onNodeWithTag("grid").performTouchInput { up() }
        compose.runOnIdle {
            assertFalse(scrubbing)
            assertTrue(seeks.last() <= 1)
        }
    }

    @Test
    fun `the scrubber going away while held ends the drag`() {
        show()
        takeHoldOfTheThumb()

        // The last photographs trashed: the rail has nothing to measure and leaves.
        //
        // This one already held before the gesture stopped being keyed on the layout —
        // detaching the whole node delivers a cancel where changing its key did not. It is
        // here so the fix cannot quietly take that away.
        compose.runOnIdle { layout = layoutOf(0) }
        compose.runOnIdle { assertFalse(scrubbing) }
    }
}
