package io.github.sneedster.harmonicast

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
class PrivateTrackRepairUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun namesTrackAndPermanentDeletionWithOneAction() {
        var replaced = false
        compose.setContent { MaterialTheme(colorScheme = playerColors("Nocturne")) {
            RepairDialog(RepairState(status = "ready", title = "Halcyon", artist = "Orbital",
                message = "Delete this bad file permanently and ask MusicGrabber for a fresh copy."), {}, { replaced = true }, {})
        } }
        compose.onNodeWithText("Halcyon — Orbital").assertIsDisplayed()
        compose.onNodeWithText("Delete and replace").performClick()
        assertTrue(replaced)
        compose.runOnIdle {
            val managerClass = Class.forName("android.view.WindowManagerGlobal")
            val manager = managerClass.getMethod("getInstance").invoke(null)
            @Suppress("UNCHECKED_CAST")
            val views = managerClass.getDeclaredField("mViews").apply { isAccessible = true }.get(manager) as List<android.view.View>
            val view = views.last()
            val screenshot = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(screenshot))
            File("build/reports/private-repair/delete-and-replace.png").apply { parentFile?.mkdirs() }.outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
    @Test fun uncertainSubmissionOffersNoDuplicateRequestButton() {
        compose.setContent { MaterialTheme {
            RepairDialog(RepairState(status = "request_unknown", message = "Check MusicGrabber."), {}, {}, {})
        } }
        compose.onNodeWithText("Delete and replace").assertDoesNotExist()
        compose.onNodeWithText("Request fresh copy").assertDoesNotExist()
        compose.onNodeWithText("Close").assertIsDisplayed()
    }
    @Test fun confirmedFailedRequestOnlyRetriesAcquisition() {
        var requested = false
        compose.setContent { MaterialTheme {
            RepairDialog(RepairState(status = "request_failed", message = "Bad file deleted."), {}, {}, { requested = true })
        } }
        compose.onNodeWithText("Delete and replace").assertDoesNotExist()
        compose.onNodeWithText("Request fresh copy").performClick()
        assertTrue(requested)
    }
    @Test @Config(qualifiers = "w740dp-h300dp-mdpi") fun landscapeKeepsActionReachable() {
        compose.setContent { MaterialTheme {
            RepairDialog(RepairState(status = "ready", title = "A very long song title", artist = "Artist", message = "Delete this bad file permanently and ask MusicGrabber for a fresh copy."), {}, {}, {})
        } }
        compose.onNodeWithText("Close").assertIsDisplayed()
        compose.onNodeWithText("Delete and replace").assertIsDisplayed()
    }
    @Test fun versionGestureEnablesThePrivateActionForAnOwner() {
        val prefs = compose.activity.getSharedPreferences("harmonicast", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val storage = SharedPreferencesProfileStorage(prefs)
        HomeProfileStore(storage).savePersonalSource(PersonalPlexSource("test", "https://plex", "machine", "Server", "7", "Music"))
        compose.setContent { MaterialTheme { PrivateRepairVersion() } }
        repeat(6) { compose.onNodeWithText("Installed version ${BuildConfig.VERSION_NAME}").performClick() }
        assertFalse(PrivateRepairSwitch(storage).enabled)
        compose.onNodeWithText("Installed version ${BuildConfig.VERSION_NAME}").performClick()
        assertTrue(PrivateRepairSwitch(storage).enabled)
    }

    @Test fun ordinaryInstallHasNoMaintenanceAction() {
        compose.setContent { MaterialTheme { PrivateTrackRepairAction(Song("one", "Halcyon", "Orbital"), allowed = true) } }
        compose.onNodeWithContentDescription("Track actions").assertDoesNotExist()
        compose.onNodeWithText("Replace bad file").assertDoesNotExist()
    }
}
