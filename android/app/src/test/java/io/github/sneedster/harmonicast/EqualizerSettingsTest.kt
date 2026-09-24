package io.github.sneedster.harmonicast

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@OptIn(ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EqualizerSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var current by mutableStateOf(EqSettings())
    private fun setup(palette: String = "Nocturne", keyboard: Boolean = false) {
        compose.setContent {
            val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
            LaunchedEffect(keyboard) { if (keyboard) inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
            MaterialTheme(colorScheme = playerColors(palette)) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        SettingsHeading("Equalizer")
                        EqualizerContent(current) { current = it }
                    }
                }
            }
        }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File("build/reports/equalizer/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun tenFixedBandsBypassAndReset() {
        setup()
        eqBandLabels.forEach { compose.onNodeWithContentDescription("$it Hz").assertExists() }
        compose.onNodeWithText("Add point").assertDoesNotExist()
        compose.onNodeWithText("Width").assertDoesNotExist()
        compose.onNodeWithContentDescription("Enable equalizer").performClick()
        compose.onNodeWithContentDescription("63 Hz").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(4f) }
        compose.runOnIdle {
            assertTrue(current.enabled)
            assertEquals(4.0, current.points[1].gain, 0.0)
            assertEquals(EqSettings.defaults.map { it.frequency }, current.points.map { it.frequency })
        }
        screenshot("continuous-phone")
        compose.onNodeWithContentDescription("Enable equalizer").performClick()
        compose.runOnIdle { assertFalse(current.enabled); assertEquals(4.0, current.points[1].gain, 0.0) }
        compose.onNodeWithText("Reset to flat").performClick()
        compose.runOnIdle { assertEquals(EqSettings.defaults, current.points); assertFalse(current.enabled) }
    }
    @Test fun verticalDragBoostsAndCutsOnlyItsBand() {
        setup()
        val band = compose.onNodeWithContentDescription("125 Hz")
        band.performTouchInput { swipe(center, center.copy(y = top + 12f), 400) }
        compose.runOnIdle { assertTrue("Up must boost: ${current.points[2].gain}", current.points[2].gain > 5) }
        band.performTouchInput { swipe(center, center.copy(y = bottom - 12f), 400) }
        compose.runOnIdle {
            assertTrue("Down must cut: ${current.points[2].gain}", current.points[2].gain < -5)
            assertTrue(current.points.filterIndexed { i, _ -> i != 2 }.all { it.gain == 0.0 })
        }
    }
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun narrowPhoneKeepsEveryBandReachable() {
        setup("Aurora")
        val nodes = eqBandLabels.map { compose.onNodeWithContentDescription("$it Hz") }
        nodes.forEach { it.assertIsDisplayed() }
        val bounds = nodes.map { it.fetchSemanticsNode().boundsInRoot }
        assertTrue(bounds.all { it.top == bounds.first().top && it.bottom == bounds.first().bottom })
        assertTrue(bounds.zipWithNext().all { (a, b) -> a.right <= b.left + 1 })
        compose.onNodeWithContentDescription("16k Hz").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(-3f) }
        compose.runOnIdle { assertEquals(-3.0, current.points[9].gain, 0.0) }
        screenshot("continuous-narrow")
    }
    @Test @Config(qualifiers = "w960dp-h720dp-mdpi")
    fun keyboardAdjustsAndCanLeaveBands() {
        setup("Ember", keyboard = true)
        val first = compose.onNodeWithContentDescription("31.5 Hz")
        first.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        first.performKeyInput { pressKey(Key.DirectionUp) }
        compose.runOnIdle { assertEquals(0.5, current.points[0].gain, 0.0) }
        first.performKeyInput { pressKey(Key.DirectionRight) }
        val second = compose.onNodeWithContentDescription("63 Hz")
        second.assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.runOnIdle { assertEquals(-0.5, current.points[1].gain, 0.0) }
        second.performKeyInput { pressKey(Key.DirectionLeft) }
        first.assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithContentDescription("Equalizer preset").assertIsFocused()
        screenshot("continuous-wide")
    }
    @Test fun presetsManualEditsAndPreampStayIndependent() {
        setup()
        compose.onNodeWithContentDescription("Equalizer preset").performClick()
        screenshot("presets-open")
        compose.onNodeWithText("Bass lift").performClick()
        compose.runOnIdle {
            assertEquals("Bass lift", equalizerPresetName(current))
            assertEquals(4.0, current.points[0].gain, 0.0)
            assertFalse(current.enabled)
        }
        compose.onNodeWithContentDescription("Equalizer preamp").performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(-3f) }
        compose.runOnIdle { assertEquals(-3.0, current.preampDb, 0.0) }
        compose.onNodeWithContentDescription("125 Hz").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Increase selected band").performClick()
        compose.runOnIdle {
            assertEquals("Custom", equalizerPresetName(current))
            assertEquals(3.0, current.points[2].gain, 0.0)
            assertEquals(-3.0, current.preampDb, 0.0)
        }
        compose.onNodeWithText("Reset to flat").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(EqSettings(), current) }
    }

}
