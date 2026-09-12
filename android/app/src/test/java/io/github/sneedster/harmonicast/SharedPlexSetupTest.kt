package io.github.sneedster.harmonicast

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SharedPlexSetupTest {
    private class Store : ProfileStorage {
        val data = mutableMapOf<String, String>()
        override fun read(key: String) = data[key]
        override fun write(values: Map<String, String>) { data.putAll(values) }
    }
    private val source = PersonalPlexSource("resource", "https://plex.example", "machine", "Server", "1", "Music", "account")
    private class Http(val source: PersonalPlexSource) : PlexHttp {
        var owned = true
        var exists = true
        var duplicate = false
        var hasAlbum = true
        var locked = false
        var summary = ""
        var ignoreWrite = false
        var failCreate = false
        var afterRead: (() -> Unit)? = null
        val writes = mutableListOf<Pair<String, Map<String, String>>>()
        val calls = mutableListOf<Pair<String, String>>()
        fun section(key: String, title: String, path: String) = JSONObject().put("key", key).put("title", title)
            .put("type", "artist").put("scanner", "Plex Music").put("agent", "tv.plex.agents.music")
            .put("Location", JSONArray().put(JSONObject().put("path", path)))
        override suspend fun request(url: String, method: String, headers: Map<String, String>, form: Map<String, String>): String {
            calls += url to headers["X-Plex-Token"].orEmpty()
            if (url.startsWith("https://plex.tv/")) return """[{"clientIdentifier":"machine","name":"Server","provides":"server","owned":$owned,"accessToken":"resource","connections":[{"uri":"https://plex.example","local":false}]}]"""
            if (method == "POST") {
                writes += method to form
                exists = true
                if (failCreate) throw java.io.IOException("uncertain secret response")
                return "{}"
            }
            if (method == "PUT") {
                writes += method to form
                if (!ignoreWrite) { summary = form.getValue("summary.value"); locked = true }
                return "{}"
            }
            val path = url.removePrefix(source.baseUrl)
            val body = when {
                path == "/" -> JSONObject().put("machineIdentifier", "machine")
                path == "/library/sections" -> JSONObject().put("Directory", JSONArray()
                    .put(section("1", "Music", "/music")).apply {
                        if (exists) put(section("6", "Harmonicast", "/setup"))
                        if (duplicate) put(section("7", "Harmonicast", "/other"))
                    })
                path.endsWith("/refresh") -> { writes += "SCAN" to emptyMap(); JSONObject() }
                path.startsWith("/library/sections/6/all") -> JSONObject().put("size", if (hasAlbum) 1 else 0)
                    .put("totalSize", if (hasAlbum) 1 else 0).put("Metadata", JSONArray().apply { if (hasAlbum) put(JSONObject().put("ratingKey", "42")) })
                path == "/library/metadata/42" -> JSONObject().put("Metadata", JSONArray().put(JSONObject()
                    .put("ratingKey", "42").put("librarySectionID", "6").put("type", "album")
                    .put("title", "Harmonicast Sharing Proof").put("parentTitle", "Harmonicast Test")
                    .put("summary", summary).put("Field", JSONArray().put(JSONObject().put("name", "summary").put("locked", locked)))))
                else -> error("Unexpected test route")
            }
            val response = JSONObject().put("MediaContainer", body).toString()
            if (path == "/library/metadata/42") afterRead?.invoke()
            return response
        }
    }

    @Test fun preparesEmptyAlbumAfterReviewAndReusesItWithoutWritingAgain() = runBlocking {
        val store = Store(); val http = Http(source)
        val service = SharedPlexSetup(store, { source }, http, { false })
        val review = service.inspect(source, "/setup")
        assertEquals(SharedPlexPreparation.Stage.REVIEW, review.stage)
        assertTrue(http.writes.isEmpty())
        val result = service.prepare(source, "/setup", review)
        assertEquals(SharedPlexPreparation.Stage.READY, result.stage)
        assertEquals("6", store.read(SharedPlexSetup.libraryStorageKey("machine")))
        assertTrue(SharedPlexSetup.isPlaceholder(http.summary, source))
        assertEquals("1", http.writes.single().second["summary.locked"])
        assertEquals(SharedPlexPreparation.Stage.READY, service.prepare(source, "/setup").stage)
        assertEquals(1, http.writes.size)
        assertTrue(http.calls.filter { it.first.startsWith("https://plex.example") }.all { it.second == "resource" })
        assertTrue(http.calls.filter { it.first.startsWith("https://plex.tv") }.all { it.second == "account" })
    }

    @Test fun recognizesPreviouslyPreparedRecordWithoutFolderEntry() = runBlocking {
        val http = Http(source).apply { summary = SharedPlexSetup.placeholder(source, UUID.randomUUID().toString()).toString(); locked = true }
        assertEquals(SharedPlexPreparation.Stage.READY, SharedPlexSetup(Store(), { source }, http, { false }).inspect(source).stage)
        assertTrue(http.writes.isEmpty())
    }

    @Test fun refusesSharedAccountsGuestsAndSourceChangeBeforeWrites() = runBlocking {
        val http = Http(source).apply { owned = false }
        assertTrue(runCatching { SharedPlexSetup(Store(), { source }, http, { false }).prepare(source, "/setup") }.isFailure)
        http.owned = true
        assertTrue(runCatching { SharedPlexSetup(Store(), { source }, http, { true }).prepare(source, "/setup") }.isFailure)
        var current: PersonalPlexSource? = source
        http.afterRead = { current = null }
        assertTrue(runCatching { SharedPlexSetup(Store(), { current }, http, { false }).prepare(source, "/setup") }.isFailure)
        assertTrue(http.writes.isEmpty())
    }

    @Test fun preservesOtherMetadataAndRejectsDuplicateLibraries() = runBlocking {
        val http = Http(source).apply { summary = "An owner's unrelated review" }
        val service = SharedPlexSetup(Store(), { source }, http, { false })
        assertTrue(runCatching { service.prepare(source, "/setup") }.isFailure)
        http.summary = ""; http.duplicate = true
        assertTrue(runCatching { service.prepare(source, "/setup") }.isFailure)
        assertTrue(http.writes.isEmpty())
    }

    @Test fun rejectsConflictAndUnverifiedWrite() = runBlocking {
        val http = Http(source); val service = SharedPlexSetup(Store(), { source }, http, { false })
        val review = service.inspect(source, "/setup")
        http.summary = "Changed elsewhere"
        assertTrue(runCatching { service.prepare(source, "/setup", review) }.isFailure)
        assertTrue(http.writes.isEmpty())
        http.summary = ""; http.ignoreWrite = true
        assertTrue(runCatching { service.prepare(source, "/setup", review) }.isFailure)
    }

    @Test fun resumesUncertainCreationWithoutPostingTwice() = runBlocking {
        val http = Http(source).apply { exists = false; hasAlbum = false; failCreate = true }
        val store = Store()
        assertTrue(runCatching { SharedPlexSetup(store, { source }, http, { false }).prepare(source, "/setup") }.isFailure)
        val resumed = SharedPlexSetup(store, { source }, http, { false }).prepare(source, "/setup")
        assertEquals(SharedPlexPreparation.Stage.SCANNING, resumed.stage)
        assertEquals(1, http.writes.count { it.first == "POST" })
        assertEquals("/setup", http.writes.first().second["location"])
    }

    @Test fun rejectsSetupFoldersInsideOrdinaryMusic() = runBlocking {
        val http = Http(source).apply { exists = false }
        val service = SharedPlexSetup(Store(), { source }, http, { false })
        assertTrue(runCatching { service.prepare(source, "/music/harmonicast") }.isFailure)
        assertTrue(http.writes.isEmpty())
    }

    @Test fun successfulCreateScansAndReturnsNextAction() = runBlocking {
        val http = Http(source).apply { exists = false; hasAlbum = false }
        val result = SharedPlexSetup(Store(), { source }, http, { false }).prepare(source, "/setup")
        assertEquals(SharedPlexPreparation.Stage.SCANNING, result.stage)
        assertEquals(listOf("POST", "SCAN"), http.writes.map { it.first })
    }

    @Test fun placeholderRejectsEnabledWrongBindingAndDuplicateKeys() {
        val record = SharedPlexSetup.placeholder(source, UUID.randomUUID().toString()).toString()
        assertTrue(SharedPlexSetup.isPlaceholder(record, source))
        assertFalse(SharedPlexSetup.isPlaceholder(record, source.copy(libraryKey = "2")))
        assertFalse(SharedPlexSetup.isPlaceholder(record.replace("\"allowAcquisition\":false", "\"allowAcquisition\":true"), source))
        assertFalse(SharedPlexSetup.isPlaceholder(record.replace("\"version\":1", "\"version\":1,\"version\":1"), source))
        assertFalse(SharedPlexSetup.isPlaceholder(record.replace("\"version\":1", "\"version\":true"), source))
    }
}
