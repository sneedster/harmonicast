package io.github.sneedster.harmonicast

import org.junit.Assert.*
import org.junit.Test

class AppProfileTest {
    private class MemoryStorage(vararg entries: Pair<String, String>) : ProfileStorage {
        val values = mutableMapOf(*entries)
        override fun read(key: String) = values[key]
        override fun write(values: Map<String, String>) { this.values.putAll(values) }
    }
    private val source = PersonalPlexSource("plex-token", "https://plex.example", "machine", "Plex", "1", "Music")

    @Test fun unconfiguredAndUnknownModesCannotBecomeReady() {
        for (storage in listOf(MemoryStorage(), MemoryStorage("home.mode" to "UNKNOWN"))) {
            val home = HomeProfileStore(storage)
            assertFalse(home.homeReady)
            assertNull(home.personalSource)
        }
    }

    @Test fun personalProfileAndQueueSurviveRestart() {
        val storage = MemoryStorage("local.queue" to "saved-queue")
        HomeProfileStore(storage).savePersonalSource(source)
        val restarted = HomeProfileStore(storage)
        assertTrue(restarted.homeReady)
        assertEquals(source, restarted.personalSource)
        assertEquals("saved-queue", storage.read("local.queue"))
    }

    @Test fun roomEntryExpiryAndRestartPreservePlexConfiguration() {
        val storage = MemoryStorage()
        val app = AppProfile(HomeProfileStore(storage))
        app.home.savePersonalSource(source)
        app.enterRoom(ActiveRoom("https://nearby.example", "temporary", 200), 100)
        app.expireRoom(199)
        assertNotNull(app.activeRoom)
        app.expireRoom(200)
        assertNull(app.activeRoom)
        app.enterRoom(ActiveRoom("https://nearby.example", "temporary", 300), 200)
        val restarted = AppProfile(HomeProfileStore(storage))
        assertNull(restarted.activeRoom)
        assertEquals(source, restarted.home.personalSource)
        app.leaveRoom()
        assertTrue(app.home.homeReady)
    }

    @Test fun guestRoomDoesNotRequirePlexCredentials() {
        val app = AppProfile(HomeProfileStore(MemoryStorage()))
        app.enterRoom(ActiveRoom("https://nearby.example", "temporary", 200), 100)
        assertNotNull(app.activeRoom)
        assertFalse(app.home.homeReady)
        app.leaveRoom()
        assertEquals(HomeMode.UNCONFIGURED, app.home.mode)
    }

    @Test(expected = IllegalArgumentException::class)
    fun expiredInvitationCannotBecomeActive() {
        AppProfile(HomeProfileStore(MemoryStorage())).enterRoom(ActiveRoom("host", "expired", 100), 100)
    }

    @Test fun signingOutClearsCredentialsAndTokenBearingPlaybackState() {
        val storage = MemoryStorage("local.queue" to "queue", "local.playback" to "playback", "local.playbackHistory" to "history")
        val home = HomeProfileStore(storage)
        home.savePersonalSource(source)
        home.clearPersonalSource()
        assertFalse(HomeProfileStore(storage).homeReady)
        assertEquals("", storage.read("home.plex.token"))
        assertEquals("[]", storage.read("local.queue"))
        assertEquals("", storage.read("local.playback"))
        assertEquals("[]", storage.read("local.playbackHistory"))
    }

    @Test fun sharedLibraryRetainsSeparateAccountAndServerTokens() {
        val storage = MemoryStorage()
        HomeProfileStore(storage).savePersonalSource(source.copy(token = "shared-token", accountToken = "account-token", canWriteToPlex = false))
        val restored = HomeProfileStore(storage).personalSource!!
        assertEquals("shared-token", restored.token)
        assertEquals("account-token", restored.accountToken)
        assertFalse(restored.canWriteToPlex)
    }
}
