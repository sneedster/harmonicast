package io.github.sneedster.harmonicast

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h760dp-mdpi")
class AcquisitionPickerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val entry = CatalogEntry("recording", "Test song", "Test artist")
    private fun request(message: String) = JSONObject().put("id", "request-1")
        .put("recording", entry.json()).put("message", message)

    @Test fun acceptedRequestHeaderFollowsPollingWithoutAnotherSubmission() {
        val current = AtomicReference("Acquiring track…")
        var submissions = 0
        compose.setContent { MaterialTheme {
            AcquisitionPicker("Test song", "search", {},
                browse = { CatalogPage(listOf(entry)) },
                submit = { submissions++; request(current.get()) },
                status = { JSONArray().put(request(current.get())) })
        } }
        compose.onNodeWithText("Acquire track").performClick()
        compose.onNodeWithText("Retry lookup").assertDoesNotExist()
        current.set("Waiting for Plex to index the track…")
        compose.mainClock.advanceTimeBy(5100)
        compose.waitUntil(8000) {
            compose.onAllNodesWithText("Test song — Waiting for Plex to index the track…")
                .fetchSemanticsNodes().isNotEmpty()
        }
        current.set("Queued")
        compose.mainClock.advanceTimeBy(5100)
        compose.waitUntil(8000) {
            compose.onAllNodesWithText("Test song — Queued").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Test song — Queued").onFirst().assertIsDisplayed()
        compose.onNodeWithText("Test song — Acquiring track…").assertDoesNotExist()
        compose.onNodeWithText("Retry lookup").assertDoesNotExist()
        assertEquals(1, submissions)
    }

    @Test fun onlyLookupFailuresOfferLookupRetry() {
        var lookups = 0
        compose.setContent { MaterialTheme {
            AcquisitionPicker("Test song", "search", {},
                browse = { if (lookups++ == 0) error("Lookup unavailable") else CatalogPage(listOf(entry)) },
                submit = { error("Submission unavailable") }, status = { JSONArray() })
        } }
        compose.onNodeWithText("Retry lookup").performClick()
        compose.onNodeWithText("Acquire track").performClick()
        compose.onNodeWithText("Retry lookup").assertDoesNotExist()
        assertEquals(2, lookups)
    }
}
