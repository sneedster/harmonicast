package io.github.sneedster.harmonicast

import android.content.Context
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class RoomAcquisitionServiceTest {
    @Test fun verifiedRoomStartupChecksConfiguredAcquisitionWithoutOpeningSettings() {
        withRoom(false) { coordinator ->
            assertTrue(HarmonicastMediaService.roomShareState.value.enabled)
            assertTrue(coordinator.roomId.value.isNotBlank())
            assertTrue("Room startup must check access and apply the enabled default", coordinator.roomAllowed.value)
        }
    }

    @Test fun verifiedRoomStartupPreservesExplicitAcquisitionRefusal() {
        withRoom(true) { coordinator ->
            assertTrue(HarmonicastMediaService.roomShareState.value.enabled)
            assertFalse(coordinator.roomAllowed.value)
        }
    }

    private fun withRoom(disabled: Boolean, block: (AcquisitionCoordinator) -> Unit) {
        val context: Context = RuntimeEnvironment.getApplication()
        val fixture = AcquisitionFixture()
        runBlocking { fixture.connect() }
        val coordinator = fixture.coordinator()
        if (disabled) runBlocking { coordinator.setRoomAllowed(false) }
        val runtime = AcquisitionRuntime::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = runtime.get(AcquisitionRuntime)
        runtime.set(AcquisitionRuntime, coordinator)
        val prefs = context.getSharedPreferences("room-acquisition-service-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val api = AppStorage(prefs, context)
        val source = fixture.source!!
        api.profile.savePersonalSource(source)
        val service = Robolectric.buildService(HarmonicastMediaService::class.java).get()
        val guard = PlexRoomAccessGuard(source, { source }, { true })
        runBlocking { assertEquals(PlexRoomAccess.AVAILABLE, guard.refresh()) }
        for ((name, value) in listOf("api" to api, "core" to fixture.core(), "roomAccessGuard" to guard)) {
            HarmonicastMediaService::class.java.getDeclaredField(name).apply { isAccessible = true; set(service, value) }
        }
        HarmonicastMediaService.roomShareState.value = RoomShareState()
        try {
            HarmonicastMediaService::class.java.getDeclaredMethod("startVerifiedGuestRoom", PlexRoomAccessGuard::class.java).apply {
                isAccessible = true
                invoke(service, guard)
            }
            shadowOf(Looper.getMainLooper()).idle()
            block(coordinator)
        } finally {
            HarmonicastMediaService::class.java.getDeclaredMethod("disableGuestControl", String::class.java).apply {
                isAccessible = true
                invoke(service, "")
            }
            (HarmonicastMediaService::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(service) as CoroutineScope).cancel()
            runtime.set(AcquisitionRuntime, previous)
            HarmonicastMediaService.roomShareState.value = RoomShareState()
        }
    }
}
