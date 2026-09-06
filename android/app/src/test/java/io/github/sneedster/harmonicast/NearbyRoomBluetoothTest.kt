package io.github.sneedster.harmonicast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

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

    @Test fun guestListsArePagedIntoGattSizedTokenFreeResponses() {
        val body = JSONArray().apply {
            repeat(5) { index ->
                put(JSONObject()
                    .put("id", "plex:machine:$index")
                    .put("title", "Song $index")
                    .put("artist", "Artist")
                    .put("streamUri", "https://plex/track?X-Plex-Token=owner-secret"))
            }
        }.toString()

        val bytes = NearbyRoomWire.pagedResponse(7, "queue", body, 2)
        val response = JSONObject(String(bytes))

        assertTrue(bytes.size <= 512)
        assertEquals(2, response.getJSONArray("items").length())
        assertEquals(2, response.getInt("offset"))
        assertTrue(response.getBoolean("more"))
        assertFalse(String(bytes).contains("owner-secret"))
    }
}
