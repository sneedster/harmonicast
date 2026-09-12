package io.github.sneedster.harmonicast

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MusicBrainzCatalogTest {
    private fun officialRelease(id: String, type: String = "Single", status: String = "Official") = JSONObject()
        .put("id", id).put("title", "Single").put("status", status).put("release-group", JSONObject().put("primary-type", type))

    @Test fun artistReleasePagingIncludesSinglesAndTrackSelectionRetainsIdentity() = runBlocking {
        val id = "11111111-1111-1111-1111-111111111111"
        val calls = mutableListOf<String>()
        val http = object : AcquisitionHttp {
            override suspend fun call(url: String, method: String, headers: Map<String, String>, body: JSONObject?): JSONObject {
                calls += url
                assertTrue(headers.getValue("User-Agent").startsWith("Harmonicast/"))
                return when {
                    url.contains("/artist/?") -> JSONObject().put("artists", JSONArray().put(JSONObject().put("id", id).put("name", "Artist")))
                    url.contains("/release-group/?") -> JSONObject().put("count", 26).put("release-groups", JSONArray().put(
                        JSONObject().put("id", id).put("title", "Single").put("primary-type", "Single").put("first-release-date", "2001-01-01")))
                    url.contains("/release/?") -> JSONObject().put("releases", JSONArray().put(officialRelease(id)))
                    url.contains("/release/$id") -> officialRelease(id).put("media", JSONArray().put(JSONObject().put("tracks", JSONArray().put(
                        JSONObject().put("recording", JSONObject().put("id", id).put("title", "Track").put("length", 180000))))))
                    url.contains("/recording/$id") -> JSONObject().put("id", id).put("title", "Track").put("length", 180000)
                        .put("artist-credit", JSONArray().put(JSONObject().put("name", "Artist")))
                        .put("releases", JSONArray().put(officialRelease(id)))
                    else -> error("Unexpected catalogue request")
                }
            }
        }
        val catalog = MusicBrainzCatalog(http, 0)
        val artist = catalog.browse("Artist", "artist").entries.single(); assertEquals("artist", artist.kind)
        val albums = catalog.browse(artist.title, "albums", artist.id)
        assertTrue(albums.more); assertEquals("Single", albums.entries.single().detail)
        assertTrue(java.net.URLDecoder.decode(calls.last(), "UTF-8").contains("status:official"))
        val track = catalog.browse("Artist", "tracks", id).entries.single()
        assertEquals("Artist", track.artist); assertEquals(180000, track.durationMs)
        val count = calls.size; assertEquals(track, catalog.recording(id)); assertEquals(count + 1, calls.size)
        catalog.browse("Artist", "albums", id, 25); assertTrue(calls.last().contains("offset=25"))
    }
    @Test fun searchFiltersIneligibleReleasesAndSubmissionRevalidatesCachedResults() = runBlocking {
        val id = "11111111-1111-1111-1111-111111111111"
        var official = true
        fun recording(releases: JSONArray) = JSONObject().put("id", id).put("title", "Track")
            .put("artist-credit", JSONArray().put(JSONObject().put("name", "Artist"))).put("releases", releases)
        val http = object : AcquisitionHttp {
            override suspend fun call(url: String, method: String, headers: Map<String, String>, body: JSONObject?): JSONObject {
                val valid = officialRelease(id, "EP", if (official) "Official" else "Bootleg")
                if (url.contains("/recording/?")) {
                    val query = java.net.URLDecoder.decode(url, "UTF-8")
                    assertTrue(query.contains("status:official")); assertTrue(query.contains("primarytype:single"))
                    return JSONObject().put("count", 6).put("recordings", JSONArray()
                        .put(recording(JSONArray().put(officialRelease(id, "Album", "Bootleg"))))
                        .put(recording(JSONArray().put(officialRelease(id, "Other"))))
                        .put(recording(JSONArray().put(officialRelease(id, "Single", "Promotion"))))
                        .put(recording(JSONArray()))
                        .put(recording(JSONArray().put(valid))))
                }
                assertTrue(url.contains("status=official"))
                return recording(JSONArray().put(valid))
            }
        }
        val catalog = MusicBrainzCatalog(http, 0)
        val page = catalog.browse("Track")
        assertEquals(1, page.entries.size); assertTrue(page.more)
        assertEquals("Artist", catalog.recording(id).artist)
        official = false
        assertTrue(runCatching { catalog.recording(id) }.isFailure)
    }

    @Test fun lookupFailureAndEmptyResponseRemainDistinct() = runBlocking {
        val empty = MusicBrainzCatalog(object : AcquisitionHttp {
            override suspend fun call(url: String, method: String, headers: Map<String, String>, body: JSONObject?) =
                JSONObject().put("recordings", JSONArray()).put("artists", JSONArray())
        }, 0)
        assertTrue(empty.browse("Nothing").entries.isEmpty())
        val broken = MusicBrainzCatalog(object : AcquisitionHttp {
            override suspend fun call(url: String, method: String, headers: Map<String, String>, body: JSONObject?): JSONObject = throw AcquisitionFailure(400, "Lookup rejected")
        }, 0)
        assertTrue(runCatching { broken.browse("Nothing") }.isFailure)
    }
}
