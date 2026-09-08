package io.github.sneedster.harmonicast

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalPlexClientTest {
    private class MemoryStorage : ProfileStorage {
        val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(values: Map<String, String>) { this.values.putAll(values) }
    }

    private class FakeHttp : PlexHttp {
        data class Call(val url: String, val method: String, val headers: Map<String, String>, val form: Map<String, String>)
        val calls = mutableListOf<Call>()
        val responses = ArrayDeque<String>()
        override suspend fun request(url: String, method: String, headers: Map<String, String>, form: Map<String, String>): String {
            calls += Call(url, method, headers, form)
            return responses.removeFirst()
        }
    }

    @Test fun pinFlowUsesStableClientIdentityAndNativeReturnUrl() = runBlocking {
        val storage = MemoryStorage()
        val http = FakeHttp().apply {
            responses += """{"id":12,"code":"pin code","authToken":null}"""
            responses += """{"id":12,"code":"pin code","authToken":"owner"}"""
        }
        val client = LocalPlexClient(storage, http)
        val pin = client.createPin()
        assertNull(pin.authToken)
        assertEquals("POST", http.calls.single().method)
        assertEquals("true", http.calls.single().form["strong"])
        assertEquals(client.clientIdentifier, http.calls.single().headers["X-Plex-Client-Identifier"])
        assertFalse(client.authorizationUrl(pin).contains("forwardUrl="))
        assertEquals("owner", client.readPin(pin).authToken)
        assertEquals(1, storage.values.filterKeys { it == "home.plex.clientId" }.size)
    }

    @Test fun sourceDiscoveryKeepsOwnedAndSharedServersWithTheirAccessMode() = runBlocking {
        val http = FakeHttp().apply {
            responses += """[
              {"owned":true,"provides":"server","clientIdentifier":"mine","name":"My Plex","connections":[{"uri":"https://mine.plex.direct/","local":true,"relay":false}]},
              {"owned":false,"provides":"server","clientIdentifier":"shared","name":"Shared","accessToken":"shared-resource-token","connections":[{"uri":"https://shared"}]}
            ]"""
            responses += """{"MediaContainer":{"machineIdentifier":"mine","friendlyName":"My Plex"}}"""
            responses += """{"MediaContainer":{"Directory":[{"key":"7","title":"Music","type":"artist","uuid":"u"},{"key":"8","title":"Movies","type":"movie"}]}}"""
        }
        val client = LocalPlexClient(MemoryStorage(), http)
        val servers = client.accessibleServers("account-token")
        val server = servers.single { it.owned }
        val shared = servers.single { !it.owned }
        assertEquals("shared-resource-token", shared.accessToken)
        assertEquals("mine", server.machineIdentifier)
        val base = client.connect("owner", server)
        assertEquals("https://mine.plex.direct", base)
        assertEquals(listOf(PlexLibrary("7", "Music", "u")), client.musicLibraries(base, "owner"))
        assertEquals("account-token", http.calls.first().headers["X-Plex-Token"])
    }

    @Test fun directSearchBuildsPlayableAuthenticatedSongs() = runBlocking {
        val http = FakeHttp().apply {
            responses += """{"MediaContainer":{"Metadata":[{
              "type":"track","ratingKey":"42","title":"A Song","grandparentTitle":"Artist","parentTitle":"Album",
              "duration":123400,"userRating":8.5,"parentYear":2004,"thumb":"/thumb/42",
              "Media":[{"Part":[{"key":"/library/parts/42/file.mp3"}]}]
            }]}}"""
            responses += """{"MediaContainer":{}}"""
            responses += """{"MediaContainer":{}}"""
        }
        val source = PersonalPlexSource("a token", "https://plex", "machine id", "Server", "7", "Music")
        val songs = LocalPlexClient(MemoryStorage(), http).search(source, "A & B")
        val song = songs.single()
        assertEquals("plex:machine+id:42", song.id)
        assertEquals(123, song.duration)
        assertEquals("https://plex/library/parts/42/file.mp3?X-Plex-Token=a+token", song.streamUri)
        assertEquals("https://plex/thumb/42?X-Plex-Token=a+token", song.artworkUri)
        assertTrue(http.calls.first().url.contains("query=A+%26+B"))
    }

    @Test fun connectionUrlsRejectNonHttpSchemesAndDropWebSuffix() {
        assertNull(normalizeServerUrl("file:///secret"))
        assertEquals("https://plex.example/prefix", normalizeServerUrl("https://plex.example/prefix/web/"))
    }

    @Test fun playlistsKeepPlexOrderAndRejectAnotherServersIds() = runBlocking {
        val http = FakeHttp().apply {
            responses += """{"MediaContainer":{"Metadata":[{"ratingKey":"9","title":"Road Trip","leafCount":2}]}}"""
            responses += """{"MediaContainer":{"Metadata":[
              {"type":"track","ratingKey":"2","title":"Second","Media":[{"Part":[{"key":"/second"}]}]},
              {"type":"track","ratingKey":"1","title":"First","Media":[{"Part":[{"key":"/first"}]}]},
              {"type":"track","ratingKey":"3","title":"Unavailable"}
            ]}}"""
        }
        val source = PersonalPlexSource("token", "https://plex", "machine", "Server", "7", "Music")
        val client = LocalPlexClient(MemoryStorage(), http)
        val playlist = client.playlists(source).single()
        assertEquals(PlexPlaylist("plex-playlist:machine:9", "Road Trip", 2), playlist)
        assertEquals(listOf("Second", "First"), client.playlistTracks(source, playlist.id).map(Song::title))
        try {
            client.playlistTracks(source, "plex-playlist:other:9")
            fail("Expected server identity check")
        } catch (_: IllegalArgumentException) {
        }
    }
    @Test fun playlistPreviewIsBoundedWithoutChangingFullPlaybackRequest() = runBlocking {
        val http = FakeHttp().apply {
            repeat(2) { responses += """{"MediaContainer":{"Metadata":[]}}""" }
        }
        val source = PersonalPlexSource("token", "https://plex", "machine", "Server", "7", "Music")
        val client = LocalPlexClient(MemoryStorage(), http)
        client.playlistPage(source, "plex-playlist:machine:9")
        client.playlistTracks(source, "plex-playlist:machine:9")
        assertTrue(http.calls[0].url.contains("X-Plex-Container-Size=100"))
        assertTrue(http.calls[0].url.contains("X-Plex-Container-Start=0"))
        assertFalse(http.calls[1].url.contains("X-Plex-Container-Size"))
    }

    @Test fun playlistPagesKeepDuplicatesAndAdvancePastUnavailableEntries() = runBlocking {
        val http = FakeHttp().apply {
            responses += """{"MediaContainer":{"totalSize":4,"Metadata":[
                {"type":"track","ratingKey":"1","title":"Repeat","Media":[{"Part":[{"key":"/one"}]}]},
                {"type":"track","ratingKey":"2","title":"Unavailable"},
                {"type":"track","ratingKey":"1","title":"Repeat","Media":[{"Part":[{"key":"/one"}]}]}
            ]}}"""
            responses += """{"MediaContainer":{"totalSize":4,"Metadata":[{"type":"track","ratingKey":"3","title":"Last","Media":[{"Part":[{"key":"/last"}]}]}]}}"""
        }
        val source = PersonalPlexSource("token", "https://plex", "machine", "Server", "7", "Music")
        val client = LocalPlexClient(MemoryStorage(), http)
        val first = client.playlistPage(source, "plex-playlist:machine:9")
        assertEquals(listOf("Repeat", "Repeat"), first.tracks.map { it.title })
        assertEquals(3, first.nextOffset)
        val last = client.playlistPage(source, "plex-playlist:machine:9", first.nextOffset!!)
        assertEquals(listOf("Last"), last.tracks.map { it.title })
        assertNull(last.nextOffset)
        assertTrue(http.calls.last().url.contains("X-Plex-Container-Start=3"))
        try { client.playlistPage(source, "plex-playlist:other:9"); fail("Expected identity rejection") }
        catch (_: IllegalArgumentException) { }
        assertEquals(2, http.calls.size)
    }

    @Test fun collectionSearchUsesSelectedSectionBoundedPagesAndEncodedQuery() = runBlocking {
        val http = FakeHttp().apply {
            responses += """{"MediaContainer":{"size":1,"totalSize":1,"Metadata":[{"type":"artist","ratingKey":"7","title":"A & B","summary":"Biography"}]}}"""
        }
        val source = PersonalPlexSource("token", "https://plex", "machine", "Server", "7", "Music")
        val page = LocalPlexClient(MemoryStorage(), http).browse(source, BrowseKind.ARTISTS, BrowseOrder.TITLE, query = "A & B")
        assertTrue(http.calls.single().url.contains("/library/sections/7/search?"))
        assertTrue(http.calls.single().url.contains("query=A+%26+B"))
        assertTrue(http.calls.single().url.contains("X-Plex-Container-Size=40"))
        assertEquals("Biography", page.entries.single().summary)
    }

    @Test fun collectionPagesUseBoundedOffsetsAndKeepSourceIdentity() = runBlocking {
        val http = FakeHttp().apply {
            responses += """{"MediaContainer":{"size":2,"totalSize":5,"Metadata":[
                {"type":"album","ratingKey":"7","title":"Album","parentTitle":"Artist","thumb":"/art/7","year":2024},
                {"type":"track","ratingKey":"8","title":"Wrong type"}
            ]}}"""
        }
        val source = PersonalPlexSource("token", "https://plex", "machine", "Server", "3", "Music")
        val page = LocalPlexClient(MemoryStorage(), http).browse(source, BrowseKind.ALBUMS, BrowseOrder.RECENT, 2)
        assertEquals(4, page.nextOffset)
        assertEquals("plex-collection:machine:3:7", page.entries.single().id)
        assertEquals("https://plex/art/7?X-Plex-Token=token", page.entries.single().artwork)
        assertTrue(http.calls.single().url.contains("X-Plex-Container-Start=2"))
        assertTrue(http.calls.single().url.contains("X-Plex-Container-Size=40"))
    }

    @Test fun artistReleasesIncludeSinglesAndEpsAcrossPages() = runBlocking {
        val http = FakeHttp().apply {
            responses += """{"MediaContainer":{"Metadata":[{"type":"artist","ratingKey":"12","librarySectionID":"3"}]}}"""
            responses += """{"MediaContainer":{"size":2,"totalSize":3,"Metadata":[
                {"type":"album","ratingKey":"21","title":"Single","Format":[{"tag":"Single"}]},
                {"type":"album","ratingKey":"22","title":"EP","Format":[{"tag":"EP"}]}
            ]}}"""
            responses += """{"MediaContainer":{"Metadata":[{"type":"artist","ratingKey":"12","librarySectionID":"3"}]}}"""
            responses += """{"MediaContainer":{"size":1,"totalSize":3,"Metadata":[{"type":"album","ratingKey":"23","title":"Another single"}]}}"""
        }
        val source = PersonalPlexSource("token", "https://plex", "mine", "Server", "3", "Music")
        val client = LocalPlexClient(MemoryStorage(), http)
        val first = client.browse(source, BrowseKind.ALBUMS, BrowseOrder.TITLE, parent = "plex-collection:mine:3:12")
        assertEquals(listOf("Single", "EP"), first.entries.map { it.title })
        assertEquals(2, first.nextOffset)
        val last = client.browse(source, BrowseKind.ALBUMS, BrowseOrder.TITLE, first.nextOffset!!, "plex-collection:mine:3:12")
        assertEquals("Another single", last.entries.single().title)
        assertNull(last.nextOffset)
        for (call in listOf(http.calls[1], http.calls[3])) {
            assertTrue(call.url.startsWith("https://plex/library/sections/3/all?type=9&"))
            assertTrue(call.url.contains("&artist.id=12"))
            assertFalse(call.url.contains("format="))
        }
        assertTrue(http.calls[3].url.contains("X-Plex-Container-Start=2"))
    }

    @Test fun collectionIdsCannotCrossServerOrLibraryBoundaries() = runBlocking {
        val http = FakeHttp()
        val client = LocalPlexClient(MemoryStorage(), http)
        val source = PersonalPlexSource("token", "https://plex", "mine", "Server", "3", "Music")
        for (id in listOf("plex-collection:other:3:7", "plex-collection:mine:4:7", "plex-collection:mine:3:../secret")) {
            try { client.albumTracks(source, id); fail("Expected invalid collection rejection") }
            catch (_: IllegalArgumentException) { }
        }
        assertTrue(http.calls.isEmpty())
    }

    @Test fun albumTracksFollowPagesAndKeepTrackOrder() = runBlocking {
        val http = FakeHttp().apply {
            responses += """{"MediaContainer":{"Metadata":[{"type":"album","ratingKey":"7","librarySectionID":"3"}]}}"""
            responses += """{"MediaContainer":{"totalSize":2,"Metadata":[{"type":"track","ratingKey":"8","title":"First","Media":[{"Part":[{"key":"/first"}]}]}]}}"""
            responses += """{"MediaContainer":{"totalSize":2,"Metadata":[{"type":"track","ratingKey":"9","title":"Second"}]}}"""
        }
        val source = PersonalPlexSource("token", "https://plex", "mine", "Server", "3", "Music")
        val tracks = LocalPlexClient(MemoryStorage(), http).albumTracks(source, "plex-collection:mine:3:7")
        assertEquals(listOf("First", "Second"), tracks.map { it.title })
        assertNull(tracks.last().streamUri)
        assertTrue(http.calls.last().url.contains("X-Plex-Container-Start=1"))
    }

    @Test fun wrongLibraryAlbumIsRejectedBeforeLoadingTracks() = runBlocking {
        val http = FakeHttp().apply { responses += """{"MediaContainer":{"Metadata":[{"librarySectionID":"99"}]}}""" }
        val source = PersonalPlexSource("token", "https://plex", "mine", "Server", "3", "Music")
        try { LocalPlexClient(MemoryStorage(), http).albumTracks(source, "plex-collection:mine:3:7"); fail("Expected library rejection") }
        catch (_: IllegalArgumentException) { }
        assertEquals(1, http.calls.size)
    }

}
