package io.github.sneedster.harmonicast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyRoomBluetoothTest {
    @Test fun roomAdvertisementContainsOnlyTheFourLetterRoomCode() {
        val value = NearbyRoomWire.roomAdvertisement("MUSE")

        assertEquals("MUSE", NearbyRoomWire.roomCode(value))
        assertTrue(NearbyRoomWire.roomCode("bad!".toByteArray()).isEmpty())
    }

    @Test fun statusRoundTripExposesGuestSafeNowPlayingFields() {
        val song = Song(
            id = "plex:secret-machine:99",
            title = "Electric Feel",
            artist = "MGMT",
            streamUri = "https://plex.example/secret-token",
        )

        val state = NearbyRoomWire.decodeStatus(
            NearbyRoomWire.status("MUSE", PlaybackSnapshot(NowPlaying(song, true))),
        )

        assertTrue(state.connected)
        assertEquals("MUSE", state.roomCode)
        assertEquals("Electric Feel", state.title)
        assertEquals("MGMT", state.artist)
        assertTrue(state.isPlaying)
        assertTrue(String(NearbyRoomWire.status("MUSE", PlaybackSnapshot(NowPlaying(song, true)))).contains("secret-token").not())
    }
}
