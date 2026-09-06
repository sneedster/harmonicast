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

    @Test fun sourceDiscoveryKeepsOwnedServersAndMusicLibrariesOnly() = runBlocking {
        val http = FakeHttp().apply {
            responses += """[
              {"owned":true,"provides":"server","clientIdentifier":"mine","name":"My Plex","connections":[{"uri":"https://mine.plex.direct/","local":true,"relay":false}]},
              {"owned":false,"provides":"server","clientIdentifier":"shared","name":"Shared","connections":[{"uri":"https://shared"}]}
            ]"""
            responses += """{"MediaContainer":{"machineIdentifier":"mine","friendlyName":"My Plex"}}"""
            responses += """{"MediaContainer":{"Directory":[{"key":"7","title":"Music","type":"artist","uuid":"u"},{"key":"8","title":"Movies","type":"movie"}]}}"""
        }
        val client = LocalPlexClient(MemoryStorage(), http)
        val server = client.ownedServers("owner").single()
        assertEquals("mine", server.machineIdentifier)
        val base = client.connect("owner", server)
        assertEquals("https://mine.plex.direct", base)
        assertEquals(listOf(PlexLibrary("7", "Music", "u")), client.musicLibraries(base, "owner"))
        assertEquals("owner", http.calls.first().headers["X-Plex-Token"])
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
}
