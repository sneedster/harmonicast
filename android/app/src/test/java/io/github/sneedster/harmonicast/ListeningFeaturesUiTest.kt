package io.github.sneedster.harmonicast

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h760dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListeningFeaturesUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun customSoundProfileCanBeSavedAndAppliedOnPhone() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("device_equalizer", Context.MODE_PRIVATE).edit().clear().commit()
        val eq = EqualizerStore(context)
        eq.update(EqSettings(enabled = true, preampDb = -4.0))
        compose.setContent { MaterialTheme(colorScheme = playerColors("Nocturne")) { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.verticalScroll(rememberScrollState())) { SoundProfilesContent(eq) }
        } } }
        compose.onNodeWithText("Sound profile name").performTextInput("Headphones")
        compose.onNodeWithText("Save current sound").performClick()
        compose.runOnIdle { eq.update(EqSettings()) }
        compose.onNodeWithText("Headphones").performClick()
        compose.runOnIdle { assertTrue(eq.state.value.enabled); assertEquals(-4.0, eq.state.value.preampDb, 0.0) }
        compose.onNodeWithText("Headphones applied").assertExists()
        screenshot("sound-profiles-phone")
    }
    @Test @Config(qualifiers = "w1280dp-h720dp-mdpi") fun tvDemoscenePreviewAndControlsRender() {
        compose.setContent { MaterialTheme(colorScheme = playerColors("Nocturne")) { Surface(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) { DemoVisualizer(DemoScene.PLASMA, true, false, Modifier.fillMaxSize()) }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) { VisualizerSettingsContent() }
            }
        } } }
        compose.onNodeWithText("Starfield").performClick()
        compose.onNodeWithText("✓ Starfield").assertExists()
        compose.onNodeWithText("Classic neon").assertExists()
        screenshot("demoscene-tv")
    }
    @Test @Config(qualifiers = "w1280dp-h720dp-mdpi") fun tvPlayerKeepsTransportAboveWireframeBackground() {
        val context = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(context.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK, true)
        context.getSharedPreferences("harmonicast", Context.MODE_PRIVATE).edit().clear().commit()
        DemoSettings(context).apply { scene = DemoScene.WIREFRAME; neon = true }
        val vm = HarmonicastViewModel().apply {
            initialize(context); isHost = true; isActivePlayer = true
            nowPlaying = NowPlaying(Song("test", "Space / Time", "Assembly", "The midnight session", duration = 240), false)
        }
        compose.setContent { MaterialTheme(colorScheme = playerColors("Nocturne")) { Surface(Modifier.fillMaxSize()) { NocturnePlayer(vm) {} } } }
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        compose.onNodeWithContentDescription("More listening actions").performClick()
        compose.onNodeWithText("Take me somewhere different").assertExists()
        compose.onNodeWithText("Download track").assertExists()
        screenshot("wireframe-player-tv")
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File("build/listening-features-screenshots").mkdirs()
            File("build/listening-features-screenshots/$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
