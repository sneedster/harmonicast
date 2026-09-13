package io.github.sneedster.harmonicast

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private val updateRelease = AppRelease("99.0.0", "Improved Plex connections.", "https://example.com/update.apk", 100, "a".repeat(64))

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppUpdateStartupTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    @Before fun resetPreferences() { app.getSharedPreferences("updates", 0).edit().clear().commit() }

    @Test fun freshLaunchChecksAndPromptsEvenAfterRecentCheck() {
        app.getSharedPreferences("updates", 0).edit().putLong("lastCheck", System.currentTimeMillis()).commit()
        var checks = 0
        val vm = AppUpdateViewModel(app) { checks++; updateRelease }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(vm.automatic); assertEquals(1, checks)
        assertTrue(vm.showPrompt); assertEquals(updateRelease, vm.release)
        vm.dismissPrompt()
        assertFalse(vm.showPrompt)
        assertEquals(updateRelease, vm.release)
        assertFalse(vm.downloaded)
    }

    @Test fun respectsOptOutAndChecksWhenEnabled() {
        app.getSharedPreferences("updates", 0).edit().putBoolean("automatic", false).commit()
        var checks = 0
        val vm = AppUpdateViewModel(app) { checks++; updateRelease }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, checks); assertFalse(vm.showPrompt)
        vm.updateAutomatic(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, checks); assertTrue(vm.showPrompt)
    }

    @Test fun noUpdateAndNetworkFailureDoNotShowAnUpdatePrompt() {
        val current = AppUpdateViewModel(app) { null }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(current.showPrompt); assertFalse(current.busy)
        val failed = AppUpdateViewModel(app) { throw IllegalStateException("Offline. Try again.") }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(failed.showPrompt); assertFalse(failed.busy)
        assertEquals("Offline. Try again.", failed.message)
        failed.check()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(failed.busy)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
class AppUpdatePromptTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun updateNowStartsFlowAndLaterDismissesWithoutDownloading() {
        var updates = 0
        compose.setContent {
            var visible by remember { mutableStateOf(true) }
            MaterialTheme(colorScheme = playerColors("Nocturne")) {
                if (visible) UpdateAvailableDialog(updateRelease, "", false, 0f, false,
                    { updates++ }, {}, {}, { visible = false })
            }
        }
        compose.onNodeWithText("Update available").assertIsDisplayed()
        compose.onNodeWithText("Update now", useUnmergedTree = true).assertIsDisplayed().performClick()
        assertEquals(1, updates)
        compose.onNodeWithText("Later").performClick()
        compose.onNodeWithText("Update available").assertDoesNotExist()
        assertEquals(1, updates)
    }

    @Test fun longNotesKeepActionsReachableAndDownloadCanBeCancelled() {
        var cancelled = false
        compose.setContent {
            MaterialTheme {
                UpdateAvailableDialog(updateRelease.copy(notes = "Release notes\n".repeat(150)), "Downloading update…", true, 0.5f, false,
                    {}, {}, { cancelled = true }, {})
            }
        }
        compose.onNodeWithText("Update now").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Cancel download").assertIsDisplayed().performClick()
        assertTrue(cancelled)
    }

    @Test fun verifiedDownloadOffersInstallerAndErrorsAllowRetry() {
        var installs = 0
        compose.setContent {
            MaterialTheme {
                UpdateAvailableDialog(updateRelease, "Allow Harmonicast to install apps, then return and tap Install update.", false, 1f, true,
                    {}, { installs++ }, {}, {})
            }
        }
        compose.onNodeWithText("Install update").assertIsDisplayed().performClick()
        assertEquals(1, installs)
    }
}
