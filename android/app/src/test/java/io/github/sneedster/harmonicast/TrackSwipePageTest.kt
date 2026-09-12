package io.github.sneedster.harmonicast

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h760dp-mdpi")
class TrackSwipePageTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var next = 0
    private var previous = 0

    private fun page(enabled: Boolean = true) {
        compose.setContent {
            var track by remember { mutableIntStateOf(0) }
            TrackSwipePage(track.toString(), enabled, enabled,
                { next++; track++ }, { previous++; track-- }) {
                Box(Modifier.fillMaxSize().testTag("page")) { Text("Track $track") }
            }
        }
    }

    @Test fun dragTracksFingerAndOnlySkipsAfterRelease() {
        page()
        compose.onNodeWithTag("page").performTouchInput {
            down(Offset(width * .9f, height * .5f))
            moveTo(Offset(width * .2f, height * .5f), 800)
        }
        compose.runOnIdle { assertEquals(0, next) }
        assertTrue(compose.onNodeWithTag("page").fetchSemanticsNode().positionInRoot.x < -100f)
        compose.onNodeWithTag("page").performTouchInput { up() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, next); assertEquals(0, previous) }
        compose.onNodeWithText("Track 1").assertIsDisplayed()
    }

    @Test fun rightSwipeGoesBack() {
        page()
        compose.onNodeWithTag("page").performTouchInput {
            swipe(Offset(width * .1f, centerY), Offset(width * .85f, centerY), 700)
        }
        compose.runOnIdle { assertEquals(1, previous); assertEquals(0, next) }
    }

    @Test fun shortSlowSwipeAndCancellationReturnWithoutSkipping() {
        page()
        compose.onNodeWithTag("page").performTouchInput {
            swipe(Offset(width * .7f, centerY), Offset(width * .5f, centerY), 1000)
        }
        compose.onNodeWithTag("page").performTouchInput {
            down(Offset(width * .9f, centerY))
            moveTo(Offset(width * .1f, centerY), 500)
            cancel()
        }
        compose.runOnIdle { assertEquals(0, next); assertEquals(0, previous) }
        assertEquals(0f, compose.onNodeWithTag("page").fetchSemanticsNode().positionInRoot.x, 1f)
    }

    @Test fun shortFastFlickSkips() {
        page()
        compose.onNodeWithTag("page").performTouchInput {
            swipe(Offset(width * .8f, centerY), Offset(width * .5f, centerY), 80)
        }
        compose.runOnIdle { assertEquals(1, next) }
    }

    @Test fun holdingAfterFlickLosesMomentum() {
        page()
        compose.onNodeWithTag("page").performTouchInput {
            down(Offset(width * .8f, centerY))
            moveTo(Offset(width * .7f, centerY), 20)
            moveTo(Offset(width * .6f, centerY), 20)
            moveTo(Offset(width * .5f, centerY), 20)
            advanceEventTime(300)
            up()
        }
        compose.runOnIdle { assertEquals(0, next); assertEquals(0, previous) }
    }

    @Test fun disabledPlayerDoesNotSkip() {
        page(false)
        compose.onNodeWithTag("page").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(0, next); assertEquals(0, previous) }
    }

    @Test fun momentumAndHalfwayThresholdAreSymmetric() {
        assertEquals(0, trackSwipeDestination(190f, 0f, 400f, 1f))
        assertEquals(1, trackSwipeDestination(210f, 0f, 400f, 1f))
        assertEquals(-1, trackSwipeDestination(-210f, 0f, 400f, 1f))
        assertEquals(1, trackSwipeDestination(60f, 1200f, 400f, 1f))
        assertEquals(-1, trackSwipeDestination(-60f, -1200f, 400f, 1f))
        assertEquals(0, trackSwipeDestination(60f, -400f, 400f, 1f))
    }
}
