package io.github.sneedster.harmonicast

import android.content.Context
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidAutoGuestRoomTest {
    @Test fun projectionStartsRoomThroughExistingPlexAccessCheck() {
        val context: Context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("auto-room-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val service = Robolectric.buildService(HarmonicastMediaService::class.java).get()
        HarmonicastMediaService::class.java.getDeclaredField("api").apply {
            isAccessible = true
            set(service, AppStorage(prefs, context))
        }
        @Suppress("UNCHECKED_CAST")
        val observer = HarmonicastMediaService::class.java.getDeclaredField("carConnectionObserver").run {
            isAccessible = true
            get(service) as Observer<Int>
        }
        HarmonicastMediaService.roomShareState.value = RoomShareState()
        try {
            observer.onChanged(CarConnection.CONNECTION_TYPE_NOT_CONNECTED)
            assertEquals("", HarmonicastMediaService.roomShareState.value.error)
            observer.onChanged(CarConnection.CONNECTION_TYPE_PROJECTION)
            assertFalse(HarmonicastMediaService.roomShareState.value.enabled)
            assertEquals("Choose a Plex music library and leave the joined room before hosting",
                HarmonicastMediaService.roomShareState.value.error)
        } finally {
            HarmonicastMediaService.roomShareState.value = RoomShareState()
        }
    }

    @Test fun disconnectCancelsAutomaticRoomStartBeforeAccessCheckCompletes() {
        withConfiguredService { service, observer ->
            observer.onChanged(CarConnection.CONNECTION_TYPE_PROJECTION)
            assertTrue(HarmonicastMediaService.roomShareState.value.checkingAccess)
            val pending = field(service, "roomStartJob") as Job
            assertTrue(pending.isActive)

            observer.onChanged(CarConnection.CONNECTION_TYPE_NOT_CONNECTED)

            assertTrue(pending.isCancelled)
            assertFalse(HarmonicastMediaService.roomShareState.value.checkingAccess)
            assertFalse(HarmonicastMediaService.roomShareState.value.enabled)
        }
    }

    @Test fun manuallyClosedRoomStaysClosedUntilNextProjectionConnection() {
        withConfiguredService { service, observer ->
            observer.onChanged(CarConnection.CONNECTION_TYPE_PROJECTION)
            disableRoom(service)
            observer.onChanged(CarConnection.CONNECTION_TYPE_PROJECTION)
            assertFalse(HarmonicastMediaService.roomShareState.value.checkingAccess)
            assertNull(field(service, "roomStartJob"))

            observer.onChanged(CarConnection.CONNECTION_TYPE_NOT_CONNECTED)
            observer.onChanged(CarConnection.CONNECTION_TYPE_PROJECTION)
            assertTrue(HarmonicastMediaService.roomShareState.value.checkingAccess)
        }
    }

    @Test fun disconnectClosesRoomOpenedByProjection() {
        withConfiguredService { _, observer ->
            observer.onChanged(CarConnection.CONNECTION_TYPE_PROJECTION)
            HarmonicastMediaService.roomShareState.value = RoomShareState(enabled = true, roomCode = "TEST")
            observer.onChanged(CarConnection.CONNECTION_TYPE_NOT_CONNECTED)
            assertFalse(HarmonicastMediaService.roomShareState.value.enabled)
            assertEquals("", HarmonicastMediaService.roomShareState.value.roomCode)
        }
    }

    @Test fun manuallyOpenedRoomSurvivesProjectionDisconnect() {
        withConfiguredService { _, observer ->
            val manual = RoomShareState(enabled = true, roomCode = "TEST")
            HarmonicastMediaService.roomShareState.value = manual
            observer.onChanged(CarConnection.CONNECTION_TYPE_PROJECTION)
            observer.onChanged(CarConnection.CONNECTION_TYPE_NOT_CONNECTED)
            assertEquals(manual, HarmonicastMediaService.roomShareState.value)
        }
    }

    @Test fun manualRoomAccessCheckSurvivesProjectionDisconnect() {
        withConfiguredService { service, observer ->
            HarmonicastMediaService::class.java.getDeclaredMethod("enableGuestControl", Boolean::class.javaPrimitiveType).apply {
                isAccessible = true
                invoke(service, false)
            }
            val pending = field(service, "roomStartJob") as Job
            observer.onChanged(CarConnection.CONNECTION_TYPE_PROJECTION)
            observer.onChanged(CarConnection.CONNECTION_TYPE_NOT_CONNECTED)
            assertTrue(pending.isActive)
            assertTrue(HarmonicastMediaService.roomShareState.value.checkingAccess)
        }
    }

    @Test fun repeatedProjectionDoesNotRestartPendingAccessCheck() {
        withConfiguredService { service, observer ->
            observer.onChanged(CarConnection.CONNECTION_TYPE_PROJECTION)
            val pending = field(service, "roomStartJob") as Job
            observer.onChanged(CarConnection.CONNECTION_TYPE_PROJECTION)
            assertSame(pending, field(service, "roomStartJob"))
        }
    }

    @Test fun failedAutomaticGatewayStartDoesNotOwnManualRetry() {
        withConfiguredService { service, observer ->
            observer.onChanged(CarConnection.CONNECTION_TYPE_PROJECTION)
            val automaticCheck = field(service, "roomStartJob") as Job
            assertTrue(automaticCheck.isActive)
            assertEquals(true, field(service, "guestRoomStartedForAuto"))

            // Replace the queued network probe with a real, locally verified guard.
            // Revoke its source only at the gateway's own access check, before it
            // can bind a socket, so startVerifiedGuestRoom must handle start() failing.
            automaticCheck.cancel()
            (field(service, "roomAccessGuard") as PlexRoomAccessGuard).close()
            val api = field(service, "api") as AppStorage
            val source = api.profile.personalSource!!
            var sourceChecksRemaining = Int.MAX_VALUE
            val guard = PlexRoomAccessGuard(source,
                { if (sourceChecksRemaining-- > 0) source else null }, { true })
            assertEquals(PlexRoomAccess.AVAILABLE, runBlocking { guard.refresh() })
            setField(service, "roomAccessGuard", guard)
            setField(service, "core", harmonicastCore(api))
            sourceChecksRemaining = 1
            HarmonicastMediaService::class.java.getDeclaredMethod("startVerifiedGuestRoom", PlexRoomAccessGuard::class.java).apply {
                isAccessible = true
                invoke(service, guard)
            }

            val failure = ShadowLog.getLogsForTag("HarmonicastMedia")
                .last { it.msg == "Could not enable guest room" }.throwable
            assertTrue(failure is IllegalStateException)
            assertEquals("Room source is no longer available", failure!!.message)
            assertTrue("Failure must originate in the real gateway start, not fixture setup",
                failure.stackTrace.any { it.className == GuestRoomGateway::class.java.name && it.methodName == "start" })
            assertEquals("Could not start the same-Wi-Fi room controller", HarmonicastMediaService.roomShareState.value.error)
            assertNull(field(service, "guestRoomGateway"))
            assertEquals("Failed automatic startup must release room ownership",
                false, field(service, "guestRoomStartedForAuto"))

            HarmonicastMediaService::class.java.getDeclaredMethod("enableGuestControl", Boolean::class.javaPrimitiveType).apply {
                isAccessible = true
                invoke(service, false)
            }
            val manualCheck = field(service, "roomStartJob") as Job
            assertNotSame(automaticCheck, manualCheck)
            assertTrue(manualCheck.isActive)
            observer.onChanged(CarConnection.CONNECTION_TYPE_NOT_CONNECTED)
            assertSame(manualCheck, field(service, "roomStartJob"))
            assertTrue("Projection disconnect must not cancel the manual retry", manualCheck.isActive)
            assertTrue(HarmonicastMediaService.roomShareState.value.checkingAccess)
        }
    }

    @Test fun nativeCarConnectionDoesNotStartRoom() {
        withConfiguredService { service, observer ->
            observer.onChanged(CarConnection.CONNECTION_TYPE_NATIVE)
            assertFalse(HarmonicastMediaService.roomShareState.value.checkingAccess)
            assertNull(field(service, "roomStartJob"))
        }
    }

    private fun disableRoom(service: HarmonicastMediaService) {
        HarmonicastMediaService::class.java.getDeclaredMethod("disableGuestControl", String::class.java).apply {
            isAccessible = true
            invoke(service, "")
        }
    }

    private fun field(service: HarmonicastMediaService, name: String): Any? =
        HarmonicastMediaService::class.java.getDeclaredField(name).run {
            isAccessible = true
            get(service)
        }

    private fun setField(service: HarmonicastMediaService, name: String, value: Any?) {
        HarmonicastMediaService::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(service, value)
        }
    }

    private fun withConfiguredService(block: (HarmonicastMediaService, Observer<Int>) -> Unit) {
        val context: Context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("auto-room-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val api = AppStorage(prefs, context)
        api.profile.savePersonalSource(PersonalPlexSource("fixture", "https://plex.invalid", "server", "Server", "7", "Music"))
        val service = Robolectric.buildService(HarmonicastMediaService::class.java).get()
        HarmonicastMediaService::class.java.getDeclaredField("api").apply {
            isAccessible = true
            set(service, api)
        }
        @Suppress("UNCHECKED_CAST")
        val observer = field(service, "carConnectionObserver") as Observer<Int>
        HarmonicastMediaService.roomShareState.value = RoomShareState()
        try {
            block(service, observer)
        } finally {
            disableRoom(service)
            (field(service, "scope") as CoroutineScope).cancel()
        }
    }
}
