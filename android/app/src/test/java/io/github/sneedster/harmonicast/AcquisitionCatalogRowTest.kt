package io.github.sneedster.harmonicast

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
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
class AcquisitionCatalogRowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun libraryMatchOffersQueueAndUnconfirmedTrackOffersAcquire() {
        var queued = 0
        compose.setContent {
            MaterialTheme(colorScheme = playerColors("Ember")) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Find music", style = MaterialTheme.typography.headlineSmall)
                        AcquisitionCatalogRow(CatalogEntry("existing", "Dancing Queen", "ABBA", album = "ABBA Gold", inLibrary = true), false) { queued++ }
                        AcquisitionCatalogRow(CatalogEntry("new", "Another song", "ABBA"), true) {}
                    }
                }
            }
        }
        compose.onNodeWithText("In your library").assertIsDisplayed()
        compose.onNodeWithText("Queue existing track").performClick()
        assertEquals(1, queued)
        compose.onNodeWithText("Acquire track").assertIsNotEnabled()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File("build/reports/settings-layout/acquisition-library-match.png").apply { parentFile?.mkdirs() }.outputStream()
                .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
