package io.github.sneedster.harmonicast

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h760dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val restoration by lazy { StateRestorationTester(compose) }
    private lateinit var vm: HarmonicastViewModel

    private fun setup(tv: Boolean = false, fullShell: Boolean = false, readOnly: Boolean = false) {
        val context = RuntimeEnvironment.getApplication()
        if (tv) shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        val prefs = context.getSharedPreferences("harmonicast", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        // Initialize without a source to avoid launching real playback or network requests.
        vm = HarmonicastViewModel().apply { initialize(context); isHost = true }
        AppStorage(prefs).profile.savePersonalSource(PersonalPlexSource("test", "https://invalid", "test", "Server", "1", "Library", canWriteToPlex = !readOnly))
        restoration.setContent {
            val inputMode = LocalInputModeManager.current
            LaunchedEffect(tv) { if (tv) inputMode.requestInputMode(InputMode.Keyboard) }
            MaterialTheme(colorScheme = playerColors(vm.colorSchemeName)) {
                Surface(Modifier.fillMaxSize()) {
                    TvFocusRoot { if (fullShell) NocturneHome(vm) else SettingsScreen(vm) }
                }
            }
        }
    }

    private fun open(title: String) { compose.onNodeWithText(title).performScrollTo().performClick() }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output = File("build/reports/settings-layout/$name.png").apply { parentFile?.mkdirs() }
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun phoneHubAndConsentGatedTuning() {
        setup()
        SettingsCategory.entries.forEach { compose.onNodeWithText(it.title).assertExists() }
        screenshot("phone-hub")
        open("Automatic ratings")
        compose.onNodeWithContentDescription("Increase Completion boost").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Enable automatic rating changes").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Increase Completion boost").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(3, vm.musicTuning.completion) }
        screenshot("phone-ratings")
        compose.onNodeWithText("Restore rating defaults").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(MusicTuning(), vm.musicTuning) }
        compose.onNodeWithText("Back").performScrollTo().performClick()
        compose.onNodeWithText("Appearance").assertExists()
    }

    @Test fun mixWorksWhileRatingsOffAndRestoresIndependently() {
        setup()
        open("Automatic mix")
        compose.onNodeWithContentDescription("Increase Prefer higher ratings").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(3, vm.musicTuning.selection); assertFalse(vm.automaticPlexRatings) }
        compose.onNodeWithText("Restore selection preference").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, vm.musicTuning.selection); assertEquals(8, vm.ratedTrackShare) }
        screenshot("phone-mix")
    }

    @Test fun settingsLinkOpensRoomsAndBackRestoresHub() {
        setup()
        open("Rooms")
        compose.onNodeWithText("Join nearby room").assertExists()
        compose.onNodeWithText("Open room").assertExists()
        screenshot("phone-rooms")
        compose.onNodeWithText("Back").performScrollTo().performClick()
        compose.onNodeWithText("Plex account").assertExists()
    }

    @Test @Config(qualifiers = "w1024dp-h768dp-mdpi")
    fun tabletCategoriesRemainVisibleAndPaletteApplies() {
        setup()
        compose.onNodeWithText("Ember").performClick()
        compose.runOnIdle { assertEquals("Ember", vm.colorSchemeName) }
        open("Automatic mix")
        compose.onNodeWithText("Plex account").assertIsDisplayed()
        compose.onNodeWithContentDescription("Increase Prefer higher ratings").assertIsDisplayed()
        screenshot("tablet-mix-ember")
    }

    @Test @Config(qualifiers = "w1280dp-h720dp-mdpi")
    fun tvUsesTwoPanesAndBackReturnsFocusToCategory() {
        setup(tv = true)
        open("Playback")
        compose.onNodeWithText("This TV manages its own screen and background playback policies. Charging controls apply only to phones and tablets.").assertIsDisplayed()
        compose.onNodeWithText("Background playback settings").assertDoesNotExist()
        screenshot("tv-playback")
        compose.onNodeWithText("Back").performClick()
        compose.onAllNodesWithText("Playback").filter(hasClickAction()).onFirst().assertIsFocused()
    }

    @Test @Config(qualifiers = "w640dp-h300dp-mdpi")
    fun shortLandscapeDetailScrollsWithoutClippedControls() {
        setup()
        open("Automatic ratings")
        compose.onNodeWithText("Examples").performScrollTo().performClick()
        compose.onNodeWithText("Skip at 75% → 4.9 / 10").performScrollTo().assertIsDisplayed()
        screenshot("short-landscape-examples")
    }

    @Test fun roomsShortcutIsSeparateAndSettingsReturnsToOrigin() {
        setup(fullShell = true)
        compose.onNodeWithText("Rooms").performClick()
        compose.onNodeWithText("Join nearby room").assertExists()
        compose.onNodeWithText("Back").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Automatic mix").assertExists()
        open("Plex account")
        compose.onNodeWithText("Back").performScrollTo().performClick()
        compose.onNodeWithText("Back").performScrollTo().performClick()
        compose.onNodeWithText("Your automatic mix").assertExists()
    }
    @Test fun categoryAndExamplesSurviveStateRestoration() {
        setup()
        open("Automatic ratings")
        compose.onNodeWithText("Examples").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Hide examples").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Back").performScrollTo().performClick()
        compose.onNodeWithText("Plex account").assertExists()
    }

    @Test fun readOnlyAccountCannotEnableRatingsButCanTuneSelection() {
        setup(readOnly = true)
        open("Automatic ratings")
        compose.onNodeWithContentDescription("Enable automatic rating changes").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Increase Completion boost").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Back").performScrollTo().performClick()
        open("Automatic mix")
        compose.onNodeWithContentDescription("Increase Prefer higher ratings").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(3, vm.musicTuning.selection); assertFalse(vm.automaticPlexRatings) }
    }

    @Test fun replayWindowDefaultsToWeekAndIsIndependentOfRatingConsent() {
        setup()
        open("Automatic mix")
        compose.onNodeWithContentDescription("Avoid recent repeats: 1 week").assertExists()
        compose.onNodeWithContentDescription("Increase Avoid recent repeats").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(14, vm.replayWindowDays); assertFalse(vm.automaticPlexRatings) }
        compose.onNodeWithText("Back").performScrollTo().performClick()
        open("Automatic mix")
        compose.onNodeWithContentDescription("Avoid recent repeats: 2 weeks").assertExists()
        screenshot("phone-replay-window")
    }

}
