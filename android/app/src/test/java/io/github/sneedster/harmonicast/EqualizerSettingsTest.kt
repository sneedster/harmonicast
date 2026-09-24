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
                        EqualizerContent(current, 48000) { current = it }
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
    @Test fun enableTuneBypassAndResetPreserveExpectedState() {
        setup()
        compose.onNodeWithText("Curve preview · bypassed").assertExists()
        compose.onNodeWithContentDescription("Enable equalizer").performClick()
        compose.onNodeWithContentDescription("Increase Gain").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(current.enabled); assertEquals(0.5, current.points[0].gain, 0.0) }
        compose.onNodeWithContentDescription("Width").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0.0f) }
        compose.runOnIdle { assertEquals(1.0, current.points[0].q, 0.001) }
        compose.onNodeWithContentDescription("Enable equalizer").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0.5, current.points[0].gain, 0.0); assertFalse(current.enabled) }
        screenshot("phone-bypassed")
        compose.onNodeWithText("Reset to flat").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(EqSettings.defaults, current.points) }
    }
    @Test fun graphDragAndTapEditRealSettings() {
        setup()
        compose.onNodeWithContentDescription("Enable equalizer").performClick()
        val graph = compose.onNodeWithContentDescription("Equalizer response curve.", substring = true)
        graph.performScrollTo().performTouchInput { swipe(center.copy(y = center.y - 35f), center.copy(y = center.y - 75f), 400) }
        compose.runOnIdle { assertEquals(5, current.points.size); assertTrue(current.points.last().gain > 8) }
        screenshot("phone-curve")
    }
    @Test fun eightPointLimitAndEmptyCurveHaveReachableRecovery() {
        current = EqSettings(true, emptyList())
        setup("Aurora")
        compose.onNodeWithText("Flat response.", substring = true).assertExists()
        repeat(8) { compose.onNodeWithText("Add point").performScrollTo().performClick() }
        compose.onNodeWithText("Add point").assertIsNotEnabled()
        compose.onNodeWithText("Remove point").performScrollTo().performClick()
        compose.onNodeWithText("Add point").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertEquals(7, current.points.size) }
    }
    @Test @Config(qualifiers = "w960dp-h720dp-mdpi")
    fun keyboardCanChangeSelectedPointWithoutDragging() {
        setup("Ember", keyboard = true)
        val gain = compose.onNodeWithContentDescription("Gain", substring = false)
        gain.performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        gain.assertIsFocused()
        gain.performKeyInput { pressKey(Key.DirectionRight) }
        compose.runOnIdle { assertTrue(current.points[0].gain > 0) }
        compose.onNodeWithContentDescription("Enable equalizer").performScrollTo().performClick()
        screenshot("wide-ember")
    }
}
