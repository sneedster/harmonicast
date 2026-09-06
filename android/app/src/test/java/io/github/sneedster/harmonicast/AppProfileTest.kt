package io.github.sneedster.harmonicast

import org.junit.Assert.*
import org.junit.Test

class AppProfileTest {
    private class MemoryStorage(vararg entries: Pair<String, String>) : ProfileStorage {
        val values = mutableMapOf(*entries)
        override fun read(key: String) = values[key]
        override fun write(values: Map<String, String>) { this.values.putAll(values) }
    }

    @Test fun existingInstallKeepsItsSignInAcrossMigrationAndRestart() {
        val storage = MemoryStorage("base" to "https://home.example", "token" to "owner")
        val home = HomeProfileStore(storage)
        assertTrue(home.ready)
        assertEquals(HomeMode.REMOTE_SERVER, home.mode)
        assertEquals("https://home.example", home.base)
        assertEquals("owner", HomeProfileStore(storage).token)
    }

    @Test fun partiallyConfiguredInstallCanFinishSetup() {
        val storage = MemoryStorage("base" to "https://home.example")
        val home = HomeProfileStore(storage)
        assertFalse(home.ready)
        home.setToken("owner")
        assertTrue(HomeProfileStore(storage).ready)
    }

    @Test fun switchingServerClearsOldTokenAndDoesNotRemigrateIt() {
        val storage = MemoryStorage("base" to "https://old.example", "token" to "old-owner")
        val home = HomeProfileStore(storage)
        home.setBase(" https://new.example/ ")
        val restarted = HomeProfileStore(storage)
        assertEquals("https://new.example", restarted.base)
        assertEquals("", restarted.token)
        assertFalse(restarted.ready)
        restarted.setToken("new-owner")
        assertTrue(restarted.ready)
    }

    @Test fun savingSameServerRetainsSignIn() {
        val home = HomeProfileStore(MemoryStorage("base" to "https://home.example", "token" to "owner"))
        home.setBase("https://home.example/")
        assertTrue(home.ready)
        assertEquals("owner", home.token)
    }

    @Test fun roomEntryExpiryAndRestartNeverChangeHomeCredentials() {
        val storage = MemoryStorage("base" to "https://home.example", "token" to "owner")
        val app = AppProfile(HomeProfileStore(storage))
        app.enterRoom(ActiveRoom("https://guest.example", "temporary", 200), 100)
        assertEquals("https://home.example", app.home.base)
        assertEquals("owner", app.home.token)
        app.expireRoom(199)
        assertNotNull(app.activeRoom)
        app.expireRoom(200)
        assertNull(app.activeRoom)
        app.enterRoom(ActiveRoom("https://guest.example", "temporary", 300), 200)
        val restarted = AppProfile(HomeProfileStore(storage))
        assertNull(restarted.activeRoom)
        assertEquals("owner", restarted.home.token)
        app.leaveRoom()
        assertTrue(app.home.ready)
    }

    @Test fun guestRoomDoesNotRequirePlexOrRemoteServerCredentials() {
        val app = AppProfile(HomeProfileStore(MemoryStorage()))
        app.enterRoom(ActiveRoom("https://nearby.example", "temporary-room-only", 200), 100)
        assertNotNull(app.activeRoom)
        assertFalse(app.home.ready)
        assertEquals("", app.home.token)
        app.leaveRoom()
        assertEquals(HomeMode.UNCONFIGURED, app.home.mode)
    }

    @Test(expected = IllegalArgumentException::class)
    fun expiredInvitationCannotBecomeActive() {
        AppProfile(HomeProfileStore(MemoryStorage())).enterRoom(ActiveRoom("host", "expired", 100), 100)
    }

    @Test fun unknownModeDoesNotSilentlyFallBackToRemoteCredentials() {
        val home = HomeProfileStore(MemoryStorage("home.mode" to "FUTURE_MODE", "home.remote.base" to "host", "home.remote.token" to "owner"))
        assertFalse(home.ready)
    }

    @Test fun personalPlexSourceIsCompleteAndIndependentOfRemoteCredentials() {
        val storage = MemoryStorage("base" to "https://old-harmonicast.example", "token" to "old-session")
        val home = HomeProfileStore(storage)
        home.savePersonalSource(PersonalPlexSource(
            "plex-owner", "https://plex.direct/", "machine", "Living Room", "7", "Music",
        ))
        val restarted = HomeProfileStore(storage)
        assertEquals(HomeMode.PERSONAL_PLEX, restarted.mode)
        assertTrue(restarted.homeReady)
        assertFalse(restarted.ready)
        assertEquals("plex-owner", restarted.personalSource?.token)
        assertEquals("https://plex.direct", restarted.personalSource?.baseUrl)
        assertEquals("old-session", restarted.token)
    }

    @Test fun signingOutClearsPersonalCredentialsAndTokenBearingPlaybackState() {
        val storage = MemoryStorage(
            "local.queue" to "token-bearing queue",
            "local.playback" to "token-bearing playback",
            "local.playbackHistory" to "token-bearing history",
        )
        val home = HomeProfileStore(storage)
        home.savePersonalSource(PersonalPlexSource(
            "plex-owner", "https://plex.direct", "machine", "Living Room", "7", "Music",
        ))

        home.clearPersonalSource()

        val restarted = HomeProfileStore(storage)
        assertEquals(HomeMode.UNCONFIGURED, restarted.mode)
        assertNull(restarted.personalSource)
        assertEquals("", storage.values["home.plex.token"])
        assertEquals("[]", storage.values["local.queue"])
        assertEquals("", storage.values["local.playback"])
        assertEquals("[]", storage.values["local.playbackHistory"])
    }

    @Test fun sharedPlexSourceKeepsAccountAndServerTokensSeparateAndReadOnly() {
        val storage = MemoryStorage()
        val home = HomeProfileStore(storage)
        home.savePersonalSource(PersonalPlexSource(
            token = "shared-server-token",
            baseUrl = "https://shared.plex.direct",
            machineIdentifier = "shared-machine",
            serverName = "Family Plex",
            libraryKey = "7",
            libraryName = "Music",
            accountToken = "shared-user-account-token",
            canWriteToPlex = false,
        ))

        val source = HomeProfileStore(storage).personalSource
        assertEquals("shared-server-token", source?.token)
        assertEquals("shared-user-account-token", source?.accountToken)
        assertFalse(source?.canWriteToPlex ?: true)
    }
}
