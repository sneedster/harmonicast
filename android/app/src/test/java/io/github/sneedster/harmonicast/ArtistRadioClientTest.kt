package io.github.sneedster.harmonicast

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtistRadioClientTest {
    private class Memory : ProfileStorage {
        private val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(updates: Map<String, String>) { values.putAll(updates) }
    }
    private val source = PersonalPlexSource("token", "https://plex", "machine", "Server", "7", "Music")
    private val seed = Song("plex:machine:1", "Seed", "Seed Artist", albumArtist = "Seed Artist", streamUri = "https://plex/seed")
    private fun track(id: Int, artist: String, distance: Any? = 0.1, library: String = "7") = JSONObject()
        .put("type", "track").put("ratingKey", "$id").put("librarySectionID", library)
        .put("title", "Track $id").put("grandparentTitle", artist).put("parentTitle", "Album $id")
        .put("distance", distance ?: JSONObject.NULL)
        .put("Media", JSONArray().put(JSONObject().put("Part", JSONArray().put(JSONObject().put("key", "/part/$id")))))
    private fun container(vararg entries: JSONObject) = JSONObject().put("MediaContainer",
        JSONObject().put("Metadata", JSONArray(entries.toList()))).toString()

    private class Http(val respond: suspend (String) -> String) : PlexHttp {
        val calls = mutableListOf<String>()
        override suspend fun request(url: String, method: String, headers: Map<String, String>, form: Map<String, String>): String {
            assertEquals("GET", method)
            calls += url
            return respond(url)
        }
    }
    private fun seedMetadata() = track(1, "Seed Artist").put("title", "Seed").put("grandparentRatingKey", "10")
    private fun artistMetadata(library: String = "7") = JSONObject().put("type", "artist").put("ratingKey", "10")
        .put("librarySectionID", library).put("title", "Seed Artist")
        // Similar.id is a tag ID, not an artist ratingKey.
        .put("Similar", JSONArray().put(JSONObject().put("id", "99999").put("tag", "Related Artist")))

    @Test fun artistRelationshipsIntersectWithSoundAndSelectedLibraryWithoutWidening() = runBlocking {
        val http = Http { url -> when {
            url.endsWith("/metadata/1") -> container(seedMetadata())
            url.endsWith("/metadata/10") -> container(artistMetadata())
            url.contains("/1/nearest?") -> container(
                track(2, "Unrelated Artist", 0.01), track(3, "Related Artist", 0.15),
                track(4, "Seed Artist", 0.09), track(5, "Related Artist", 0.26),
                track(6, "Related Artist", null), track(7, "Related Artist", 0.10, "8"),
                track(8, "Related Artist", "NaN"),
            )
            else -> error("Unexpected endpoint")
        } }
        val selected = LocalPlexClient(Memory(), http).artistRadio(source, seed, emptyList(), 0.25)
        assertEquals(setOf("plex:machine:3", "plex:machine:4"), selected.map { it.id }.toSet())
        assertTrue(http.calls.last().endsWith("/1/nearest?limit=500&maxDistance=0.25"))
        assertEquals(3, http.calls.size)
    }

    @Test fun compilationStationUsesActualTrackArtistAndExactArtistLookup() = runBlocking {
        val http = Http { url -> when {
            url.endsWith("/metadata/1") -> container(seedMetadata().put("grandparentTitle", "Various Artists")
                .put("grandparentRatingKey", "999").put("originalTitle", "Seed Artist"))
            url.contains("/search?") -> container(
                artistMetadata().put("ratingKey", "11").put("title", "Seed Artist Tribute"), artistMetadata(),
            )
            url.endsWith("/metadata/10") -> container(artistMetadata())
            url.contains("/1/nearest?") -> container(track(2, "Related Artist"), track(3, "Compilation Related"))
            else -> error("Compilation album artist must not be used")
        } }
        val selected = LocalPlexClient(Memory(), http).artistRadio(source, seed.copy(artist = "Various Artists"), emptyList(), 0.25)
        assertEquals(listOf("plex:machine:2"), selected.map { it.id })
        assertTrue(http.calls[1].contains("query=Seed+Artist&type=8&limit=8"))
        assertFalse(http.calls.any { it.endsWith("/metadata/999") || it.endsWith("/metadata/99999") })
    }

    @Test fun missingOrForeignArtistRelationshipsDoNotBroadenTheStation() = runBlocking {
        for (artist in listOf(container(), container(artistMetadata("8")))) {
            val http = Http { url -> when {
                url.endsWith("/metadata/1") -> container(seedMetadata())
                url.endsWith("/metadata/10") -> artist
                url.contains("/1/nearest?") -> container(track(2, "Seed Artist"), track(3, "Related Artist"))
                else -> error("Unexpected endpoint")
            } }
            assertEquals(listOf("plex:machine:2"), LocalPlexClient(Memory(), http)
                .artistRadio(source, seed, emptyList(), 0.25).map { it.id })
        }
    }

    @Test fun foreignSeedAndInvalidSoundRangeNeverIssueRequests() = runBlocking {
        val http = Http { error("No request expected") }
        val plex = LocalPlexClient(Memory(), http)
        assertTrue(runCatching { plex.artistRadio(source, seed.copy(id = "plex:other:1"), emptyList(), 0.25) }.isFailure)
        assertTrue(runCatching { plex.artistRadio(source, seed, emptyList(), Double.NaN) }.isFailure)
        assertTrue(http.calls.isEmpty())
    }

    @Test fun cancellationPropagatesWithoutChangingTheQueueOrStation() = runBlocking {
        val storage = Memory()
        storage.write(mapOf("local.queue" to JSONArray().put(encodeSong(seed.copy(id = "plex:machine:9"))).toString()))
        val http = Http { throw kotlinx.coroutines.CancellationException("Cancelled") }
        val core = LocalHarmonicastCore(source, storage, LocalPlexClient(storage, http))
        core.playback.publish(seed, true, false)
        try { core.queue.radio(); fail("Cancellation must propagate") }
        catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(listOf("plex:machine:9"), core.queue.songs().map { it.id })
        assertNull(storage.read("local.radioActive"))
    }
}
