package io.github.sneedster.harmonicast

import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import org.robolectric.annotation.GraphicsMode
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h760dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrivateTrackRepairUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun userMustSelectCopyBeforeReplacement() {
        var selected: Int? = null
        compose.setContent { MaterialTheme(colorScheme = playerColors("Nocturne")) {
            RepairDialog(RepairState(enabled = true, id = "one", status = "ready", title = "Halcyon", artist = "Orbital", message = "Choose another copy.",
                choices = listOf(RepairChoice(0, "Halcyon", "Orbital", "qobuz", "FLAC"))), {}, { selected = it }, {}, {})
        } }
        compose.onNodeWithText("Replace copy").assertIsNotEnabled()
        compose.onNodeWithText("Halcyon").performClick()
        compose.onNodeWithText("Replace copy").assertIsEnabled().performClick()
        assertEquals(0, selected)
        compose.runOnIdle {
            // Draw the dialog window directly; Robolectric has no PixelCopy redraw event.
            val managerClass = Class.forName("android.view.WindowManagerGlobal")
            val manager = managerClass.getMethod("getInstance").invoke(null)
            @Suppress("UNCHECKED_CAST")
            val views = managerClass.getDeclaredField("mViews").apply { isAccessible = true }.get(manager) as List<android.view.View>
            val view = views.last()
            val screenshot = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(screenshot))
            File("build/reports/private-repair/selection.png").apply { parentFile?.mkdirs() }.outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
    @Test fun uncertainResultOnlyOffersStatusCheck() {
        var checked = false
        compose.setContent { MaterialTheme(colorScheme = playerColors("Nocturne")) {
            RepairDialog(RepairState(enabled = true, status = "unconfirmed", title = "Halcyon", message = "Connection interrupted."), {}, {}, { checked = true }, {})
        } }
        compose.onNodeWithText("Replace copy").assertDoesNotExist()
        compose.onNodeWithText("New search").assertDoesNotExist()
        compose.onNodeWithText("Check status").performClick()
        assertTrue(checked)
    }
    @Test fun workingReplacementCanBeDismissedWithoutAnotherMutation() {
        var closed = false
        compose.setContent { MaterialTheme(colorScheme = playerColors("Nocturne")) {
            RepairDialog(RepairState(enabled = true, status = "replacing", working = true, title = "Halcyon", message = "Downloading…"), { closed = true }, {}, {}, {})
        } }
        compose.onNodeWithText("Replace copy").assertDoesNotExist()
        compose.onNodeWithText("New search").assertDoesNotExist()
        compose.onNodeWithText("Close").performClick()
        assertTrue(closed)
    }
    @Test
    @Config(qualifiers = "w740dp-h300dp-mdpi")
    fun landscapeKeepsCloseAndReplaceReachable() {
        compose.setContent { MaterialTheme(colorScheme = playerColors("Nocturne")) {
            RepairDialog(RepairState(enabled = true, id = "wide", status = "ready", title = "Halcyon", artist = "Orbital", message = "Choose another copy.",
                choices = List(8) { RepairChoice(it, "Halcyon version $it", "Orbital", "qobuz", "FLAC") }), {}, {}, {}, {})
        } }
        compose.onNodeWithText("Close").assertIsDisplayed()
        compose.onNodeWithText("Replace copy").assertIsDisplayed()
        compose.onNodeWithText("Halcyon version 7").performScrollTo().performClick()
        compose.onNodeWithText("Replace copy").assertIsEnabled().assertIsDisplayed()
    }

    @Test fun ordinaryInstallHasNoMaintenanceAction() {
        compose.setContent { MaterialTheme(colorScheme = playerColors("Nocturne")) {
            PrivateTrackRepairAction(Song("one", "Halcyon", "Orbital"), allowed = false)
        } }
        compose.onNodeWithContentDescription("Track actions").assertDoesNotExist()
        compose.onNodeWithText("Find another copy").assertDoesNotExist()
    }
}
