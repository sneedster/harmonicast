package io.github.sneedster.harmonicast

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w740dp-h300dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NocturnePlayerLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun render(name: String, frame: Boolean = false) {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("harmonicast", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val vm = HarmonicastViewModel().apply {
            initialize(context) // Unconfigured: no playback service or Plex connection.
            nowPlaying = NowPlaying(Song("test", "A Very Long Song Title for Landscape",
                "Artist with a longer name", "The Album with a Longer Name", duration = 240, rating = 7.0), false)
            isHost = true
            isActivePlayer = true
        }
        // Enable owner controls without connecting the test to a real server.
        AppStorage(prefs).profile.savePersonalSource(PersonalPlexSource("test", "https://invalid", "test", "Test", "1", "Music"))
        compose.setContent {
            MaterialTheme(colorScheme = playerColors("Nocturne")) {
                Surface(Modifier.fillMaxSize()) { if (frame) NocturneHome(vm) else NocturnePlayer(vm) {} }
            }
        }
        if (frame) {
            compose.onNodeWithText("A Very Long Song Title for Landscape").performClick()
            compose.onNodeWithContentDescription("Queue").assertIsDisplayed()
        }
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        compose.onNodeWithContentDescription("Vote up").assertIsDisplayed()
        compose.onNodeWithContentDescription("Vote down").assertIsDisplayed()
        if (name == "portrait") compose.onNodeWithText(" Discover").assertIsDisplayed()
        else compose.onNodeWithContentDescription("Discover").assertIsDisplayed()
        compose.onNodeWithText("7.0 / 10").assertIsDisplayed()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        listOf("Play", "Vote up", "Vote down", "Next").forEach { description ->
            val bounds = compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
            check(bounds.left >= root.left && bounds.right <= root.right && bounds.bottom <= root.bottom) {
                "$description is clipped: $bounds outside $root"
            }
        }
        val output = File("build/reports/player-layout/$name.png").apply { parentFile?.mkdirs() }
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.runOnIdle { vm.nowPlaying = vm.nowPlaying.copy(song = vm.nowPlaying.song!!.copy(rating = 8.0)) }
        compose.onNodeWithText("8.0 / 10").assertIsDisplayed()
        compose.onNodeWithText("7.0 / 10").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w640dp-h360dp-mdpi")
    fun landscapeWithNavigationFits() = render("landscape-with-navigation", frame = true)

    @Test fun landscapeControlsAndRatingFit() = render("landscape")

    @Test @Config(qualifiers = "w560dp-h260dp-mdpi")
    fun shortLandscapeControlsFit() = render("short-landscape")

    @Test @Config(qualifiers = "w390dp-h760dp-mdpi")
    fun portraitControlsAndRatingFit() = render("portrait")
}
