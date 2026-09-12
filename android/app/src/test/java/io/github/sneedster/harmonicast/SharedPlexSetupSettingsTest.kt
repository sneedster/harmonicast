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
import kotlinx.coroutines.CompletableDeferred
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
class SharedPlexSetupSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun folderRequiresInputAndBusyBlocksDuplicateActions() {
        var actions = 0
        val busy = mutableStateOf(false)
        compose.setContent {
            var folder by remember { mutableStateOf("") }
            MaterialTheme {
                Surface { Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                    SharedPlexSetupContent(SharedPlexPreparation(SharedPlexPreparation.Stage.FOLDER), busy.value, "", folder,
                        { folder = it }, "", {}, {}, { actions++; busy.value = true })
                } }
            }
        }
        compose.onNodeWithText("Create setup library").assertIsNotEnabled()
        compose.onNodeWithText("Folder on Plex server").performTextInput("/media/harmonicast")
        compose.onNodeWithText("Create setup library").performScrollTo().performClick()
        compose.onNodeWithText("Create setup library").assertIsNotEnabled()
        compose.onNodeWithText("Folder on Plex server").assertIsNotEnabled()
        compose.onNodeWithText("Save setup file").assertIsNotEnabled()
        assertEquals(1, actions)
    }

    @Test fun readyStateDistinguishesPreparationFromEnabledAcquisition() {
        compose.setContent {
            MaterialTheme(colorScheme = playerColors("Nocturne")) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                        SharedPlexSetupContent(SharedPlexPreparation(SharedPlexPreparation.Stage.READY), false, "", "", {}, "", {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText("Library ready").assertIsDisplayed()
        compose.onNodeWithText("Shared acquisition is still off.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Prepare library").assertDoesNotExist()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File("build/reports/settings-layout/shared-plex-ready.png").apply { parentFile?.mkdirs() }.outputStream()
                .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun cancellationDiscardsLateResultsAndRetryKeepsSafeErrors() {
        val model = SharedPlexSetupModel()
        val pending = CompletableDeferred<SharedPlexPreparation>()
        compose.runOnIdle { model.run { pending.await() } }
        compose.runOnIdle { model.cancel(); pending.complete(SharedPlexPreparation(SharedPlexPreparation.Stage.READY)) }
        compose.waitForIdle()
        assertNull(model.preparation)
        assertFalse(model.busy)
        compose.runOnIdle { model.run { throw IllegalStateException("secret-network-response") } }
        compose.waitUntil(3000) { !model.busy }
        assertFalse(model.message.contains("secret-network-response"))
        assertTrue(model.message.contains("Check again"))
    }
}
