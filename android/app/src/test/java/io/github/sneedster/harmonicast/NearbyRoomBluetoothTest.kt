package io.github.sneedster.harmonicast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

class NearbyRoomBluetoothTest {
    @Test fun discoveryKeepsDistinctRoomsInArrivalOrderForExplicitSelection() {
        val discovery = NearbyRoomDiscovery<String>()

        assertTrue(discovery.add("MUSE", "first-device"))
        assertFalse(discovery.add("MUSE", "stronger-device"))
        assertTrue(discovery.add("JAZZ", "second-device"))
        assertFalse(discovery.add("bad!", "invalid-device"))

        assertEquals(listOf("MUSE", "JAZZ"), discovery.roomCodes())
        assertEquals("stronger-device", discovery.candidate("MUSE"))
        assertEquals("second-device", discovery.candidate("JAZZ"))
    }

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
            album = "Oracular Spectacular",
            duration = 230,
            streamUri = "https://plex.example/secret-token",
            artworkUri = "https://plex.example/art?X-Plex-Token=owner-secret",
        )

        val state = NearbyRoomWire.decodeStatus(
            NearbyRoomWire.status("MUSE", PlaybackSnapshot(NowPlaying(song, true), positionSeconds = 42.8)),
        )

        assertTrue(state.connected)
        assertEquals("MUSE", state.roomCode)
        assertEquals(16, state.artworkKey.length)
        assertEquals("Electric Feel", state.title)
        assertEquals("MGMT", state.artist)
        assertEquals("Oracular Spectacular", state.album)
        assertEquals(230, state.durationSeconds)
        assertEquals(42, state.positionSeconds)
        assertTrue(state.isPlaying)
        assertTrue(String(NearbyRoomWire.status("MUSE", PlaybackSnapshot(NowPlaying(song, true)))).contains("secret-token").not())
        assertFalse(String(NearbyRoomWire.status("MUSE", PlaybackSnapshot(NowPlaying(song, true)))).contains("owner-secret"))
    }

    @Test fun guestListsArePagedIntoGattSizedTokenFreeResponses() {
        val body = JSONArray().apply {
            repeat(5) { index ->
                put(JSONObject()
                    .put("id", "plex:machine:$index")
                    .put("title", "Song $index")
                    .put("artist", "Artist")
                    .put("album", "An album with a useful title")
                    .put("duration", 245)
                    .put("streamUri", "https://plex/track?X-Plex-Token=owner-secret"))
            }
        }.toString()

        val bytes = NearbyRoomWire.pagedResponse(7, "queue", body, 2)
        val response = JSONObject(String(bytes))

        assertTrue(bytes.size <= 512)
        assertEquals(2, response.getJSONArray("items").length())
        assertEquals("An album with a useful t", response.getJSONArray("items").getJSONObject(0).getString("album"))
        assertEquals(245, response.getJSONArray("items").getJSONObject(0).getInt("duration"))
        assertEquals(2, response.getInt("offset"))
        assertTrue(response.getBoolean("more"))
        assertFalse(String(bytes).contains("owner-secret"))
    }

    @Test fun nowPlayingStatusStaysWithinOneLargeGattPayloadWithUnicodeMetadata() {
        val song = Song(
            id = "private-id",
            title = "🎵".repeat(200),
            artist = "🎤".repeat(200),
            album = "💿".repeat(200),
            duration = 360,
        )

        val bytes = NearbyRoomWire.status("MUSE", PlaybackSnapshot(NowPlaying(song, true), positionSeconds = 12.0))

        assertTrue(bytes.size <= 512)
        assertFalse(String(bytes).contains("private-id"))
    }

    @Test fun artworkIsSplitIntoGattSizedTokenFreeChunks() {
        val artwork = ByteArray(700) { (it % 251).toByte() }
        val received = ArrayList<Byte>()
        var offset = 0
        do {
            val bytes = NearbyRoomWire.artworkResponse(9, "safe-art-key", artwork, offset)
            assertTrue(bytes.size <= 512)
            val response = JSONObject(String(bytes))
            Base64.getDecoder().decode(response.getString("data")).forEach { received.add(it) }
            offset = response.getInt("next")
            val more = response.getBoolean("more")
        } while (more)

        assertTrue(artwork.contentEquals(received.toByteArray()))
    }
}
