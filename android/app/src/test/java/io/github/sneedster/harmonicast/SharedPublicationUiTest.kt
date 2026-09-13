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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h760dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharedPublicationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun publicationReviewNamesAccountRequiresDestinationCheckAndDoesNotDisplaySecrets() {
        var reviews = 0
        compose.setContent {
            var destination by remember { mutableStateOf(false) }
            var rooms by remember { mutableStateOf(false) }
            MaterialTheme(colorScheme = playerColors("Nocturne")) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                        SharedAccountReview(PersonalPlexSource("plex-secret", "https://plex.example", "server-id", "Home server", "1", "Music"),
                            SharedPlexPreparation(SharedPlexPreparation.Stage.READY, albumName = "Shared Access Setup"),
                            AcquisitionConnection("https://music.example.com", "plex-guests", "account-id", "session-secret", "password-secret", role = "peon", destination = "/music/Singles"),
                            rooms, destination, false, { rooms = it }, { destination = it }, { reviews++ })
                    }
                }
            }
        }
        compose.onNodeWithText("Account: plex-guests (peon)", substring = true).assertExists()
        compose.onNodeWithText("password-secret", substring = true).assertDoesNotExist()
        compose.onNodeWithText("session-secret", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Review publication").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Permit room acquisition").assertIsOff()
        compose.onNodeWithContentDescription("Downloads reach this music library").performScrollTo().performClick()
        compose.onNodeWithText("Review publication").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, reviews)
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File("build/reports/settings-layout/shared-publication-review.png").apply { parentFile?.mkdirs() }.outputStream()
                .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
