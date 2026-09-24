package io.github.sneedster.harmonicast

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h760dp-mdpi")
class ArtistDiscoveryLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(info: ArtistDiscovery?) {
        compose.setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    ArtistDiscoveryContent(Song("1", "Track", "Artist name", "Album name"), info, false, "") {}
                }
            }
        }
    }

    @Test fun textScrollLeavesPhotoAndTabsFixedAndTabsRestorePosition() {
        show(ArtistDiscovery("Artist name", "Biography paragraph. ".repeat(400), emptyList(), emptyList(),
            "Album name", 2001, "Album review. ".repeat(400)))
        val photo = compose.onNodeWithContentDescription("Artist photo").fetchSemanticsNode().boundsInRoot
        val tab = compose.onNodeWithText("Artist").fetchSemanticsNode().boundsInRoot
        fun scrollPosition() = compose.onNode(hasScrollAction()).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        val initialBioTop = scrollPosition()
        compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 250f) }
        compose.waitForIdle()
        val scrolledBioTop = scrollPosition()
        org.junit.Assert.assertTrue(scrolledBioTop > initialBioTop)
        assertEquals(photo, compose.onNodeWithContentDescription("Artist photo").fetchSemanticsNode().boundsInRoot)
        assertEquals(tab, compose.onNodeWithText("Artist").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("Album", substring = false).performClick().assertIsSelected()
        compose.onNodeWithText("Album name").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 180f) }
        compose.onNodeWithText("Artist", substring = false).performClick().assertIsSelected()
        assertEquals(scrolledBioTop, scrollPosition())
    }

    @Test fun absentMetadataHasSeparateEmptyStates() {
        show(null)
        compose.onNodeWithText("No artist biography available.").assertIsDisplayed()
        compose.onNodeWithText("Album", substring = false).performClick()
        compose.onNodeWithText("No album review available.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Artist photo").assertIsDisplayed()
        compose.onNodeWithText("Down").assertIsDisplayed()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val output = java.io.File("build/reports/artist-discovery/${view.width}x${view.height}.png")
            output.parentFile?.mkdirs()
            output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    @Config(qualifiers = "w760dp-h360dp-mdpi")
    fun shortLandscapeKeepsTabsAndTextReachable() {
        absentMetadataHasSeparateEmptyStates()
    }
}
