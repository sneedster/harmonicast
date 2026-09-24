package io.github.sneedster.harmonicast

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalPlexClientTest {
    @Test fun maintenanceRequiresOneFileFromOwnedSelectedLibrary() = runBlocking {
        val source = PersonalPlexSource("token", "https://plex", "machine", "Server", "7", "Music")
        for ((section, media, allowed) in listOf(
            Triple("7", """[{"Part":[{"file":"/music/song.flac"}]}]""", true),
            Triple("8", """[{"Part":[{"file":"/music/song.flac"}]}]""", false),
            Triple("7", """[{"Part":[{"file":"/music/song.flac"},{}]}]""", false),
            Triple("7", """[{"Part":[{"file":"/music/song.flac"}]},{}]""", false),
            Triple("7", """[{"Part":[{"key":"/stream"}]}]""", false),
        )) {
            val http = FakeHttp().apply { responses += """{"MediaContainer":{"Metadata":[{"ratingKey":"1","librarySectionID":"$section","Media":$media}]}}""" }
            val result = runCatching { LocalPlexClient(MemoryStorage(), http).maintenanceFile(source, "plex:machine:1") }
            assertEquals(allowed, result.isSuccess)
            if (allowed) assertEquals("/music/song.flac", result.getOrThrow())
        }
        val http = FakeHttp()
        assertTrue(runCatching { LocalPlexClient(MemoryStorage(), http).maintenanceFile(source.copy(canWriteToPlex = false), "plex:machine:1") }.isFailure)
        assertTrue(http.calls.isEmpty())
        assertTrue(runCatching { LocalPlexClient(MemoryStorage(), http).maintenanceFile(source, "plex:other:1") }.isFailure)
        assertTrue(http.calls.isEmpty())
    }

    @Test fun artistDiscoveryPrefersBackgroundAndFallsBackToThumbnail() = runBlocking {
        for ((art, expected) in listOf("/artist/banner" to "/artist/banner", "" to "/artist/thumb")) {
            val http = FakeHttp().apply {
                responses += """{"MediaContainer":{"Metadata":[{"ratingKey":"1","librarySectionID":"7","grandparentRatingKey":"2","parentRatingKey":"3"}]}}"""
                responses += """{"MediaContainer":{"Metadata":[{"title":"Artist","art":"$art","thumb":"/artist/thumb","summary":"Bio"}]}}"""
                responses += """{"MediaContainer":{"Metadata":[{"title":"Album","summary":"Review"}]}}"""
            }
            val source = PersonalPlexSource("token", "https://plex", "machine", "Server", "7", "Music")
            val info = LocalPlexClient(MemoryStorage(), http).discovery(source, Song("plex:machine:1", "Track", "Artist"))
            assertEquals("https://plex$expected?X-Plex-Token=token", info.artistArtworkUri)
            assertEquals("Bio", info.bio)
            assertEquals("Review", info.albumSummary)
        }
    }

    @Test fun trackArtistOverridesAlbumArtistAndFallsBackForMissingMetadata() = runBlocking {
        val http = FakeHttp().apply {
            responses += """{"MediaContainer":{"Metadata":[
                {"type":"track","ratingKey":"1","title":"Compilation track","originalTitle":"  Guest Performer  ","grandparentTitle":"Various Artists","parentTitle":"Compilation"},
                {"type":"track","ratingKey":"2","title":"Ordinary track","grandparentTitle":"Album Performer"},
                {"type":"track","ratingKey":"3","title":"Blank track artist","originalTitle":"  ","grandparentTitle":"Album Performer"},
                {"type":"track","ratingKey":"4","title":"Missing artists"},
                {"type":"track","ratingKey":"5","title":"Null artists","originalTitle":null,"grandparentTitle":null},
                {"type":"track","ratingKey":"6","title":"Blank artists","originalTitle":"  ","grandparentTitle":"  "},
                {"type":"track","ratingKey":"7","title":"Track only","originalTitle":"Solo Performer"}
            ]}}"""
        }
        val source = PersonalPlexSource("token", "https://plex", "machine", "Server", "7", "Music")
        val tracks = LocalPlexClient(MemoryStorage(), http).random(source, 7)
        assertEquals(listOf("Guest Performer", "Album Performer", "Album Performer", "Unknown artist", "Unknown artist", "Unknown artist", "Solo Performer"), tracks.map { it.artist })
        assertEquals("Various Artists", tracks.first().albumArtist)
        assertEquals("Compilation", tracks.first().album)
        assertEquals("", tracks.last().albumArtist)
        assertEquals(tracks.first(), decodeSong(encodeSong(tracks.first())))
    }

    @Test fun compilationAlbumBrowsingRetainsAlbumArtist() = runBlocking {
        val http = FakeHttp().apply {
            responses += """{"MediaContainer":{"Metadata":[{"type":"album","ratingKey":"91","title":"Compilation","parentTitle":"Various Artists"}],"totalSize":1}}"""
        }
        val source = PersonalPlexSource("token", "https://plex", "machine", "Server", "7", "Music")
        val album = LocalPlexClient(MemoryStorage(), http).browse(source, BrowseKind.ALBUMS, BrowseOrder.TITLE).entries.single()
        assertEquals("Various Artists", album.subtitle)
    }

    @Test fun acquisitionRecentTracksUseAlbumIndexAndIncludeLaterTracks() = runBlocking {
        val http = FakeHttp().apply {
            responses += """{"MediaContainer":{"Metadata":[{"type":"album","ratingKey":"7"}]}}"""
            responses += """{"MediaContainer":{"Metadata":[{"type":"track","ratingKey":"41","title":"First"},{"type":"track","ratingKey":"42","title":"Hear Me"}]}}"""
        }
        val source = PersonalPlexSource("token", "https://plex", "machine", "Server", "7", "Music")
        val tracks = LocalPlexClient(MemoryStorage(), http).recentTracks(source)
        assertEquals(listOf("First", "Hear Me"), tracks.map { it.title })
        assertTrue(http.calls[0].url.contains("type=9&sort=addedAt:desc"))
        assertTrue(http.calls[1].url.contains("/7/children?"))
        assertTrue(http.calls[1].url.contains("X-Plex-Container-Size=100"))
    }

    @Test fun roomRecentPicksReadAlbumDatesAndBoundTrackFetches() = runBlocking {
        val http = FakeHttp().apply {
            responses += """{"MediaContainer":{"Metadata":[{"type":"album","ratingKey":"7"}]}}"""
            responses += """{"MediaContainer":{"Metadata":[{"type":"track","ratingKey":"42","title":"New Track","Media":[{"Part":[{"key":"/song"}]}]}]}}"""
        }
        val source = PersonalPlexSource("token", "https://plex", "machine", "Server", "7", "Music")
        val tracks = LocalPlexClient(MemoryStorage(), http).discoveryRecentTracks(source)
        assertEquals("New Track", tracks.single().title)
        assertTrue(http.calls[0].url.contains("type=9&sort=addedAt:desc"))
        assertTrue(http.calls[0].url.contains("X-Plex-Container-Size=48"))
        assertTrue(http.calls[1].url.contains("/7/children?"))
        assertTrue(http.calls[1].url.contains("X-Plex-Container-Size=1"))
    }

    @Test fun roomDiscoverySamplesBoundedIndexedPagesWithoutRandomSort() = runBlocking {
        val http = FakeHttp().apply {
            responses += """{"MediaContainer":{"totalSize":2,"Metadata":[]}}"""
            responses += """{"MediaContainer":{"Metadata":[]}}"""
        }
        val source = PersonalPlexSource("token", "https://plex", "machine", "Server", "7", "Music")
        LocalPlexClient(MemoryStorage(), http).discoverySample(source)
        assertTrue(http.calls.all { it.url.contains("sort=titleSort:asc") })
        assertTrue(http.calls.first().url.contains("X-Plex-Container-Size=1"))
        assertTrue(http.calls.last().url.contains("X-Plex-Container-Size=2"))
        assertEquals(2, http.calls.size)
    }

    @Test fun randomTrackSamplingUsesExplicitPlexPagination() = runBlocking {
        val http = FakeHttp().apply { responses += """{"MediaContainer":{"Metadata":[]}}""" }
        val source = PersonalPlexSource("token", "https://plex", "machine", "Server", "7", "Music")
        LocalPlexClient(MemoryStorage(), http).random(source, 999)
        val url = http.calls.single().url
        assertTrue(url.contains("X-Plex-Container-Start=0"))
        assertTrue(url.contains("X-Plex-Container-Size=100"))
    }

    @Test fun alphabetIndexIncludesLettersBeyondFirstBrowsePageWithoutLoadingMedia() = runBlocking {
        val http = FakeHttp().apply {
            responses += """{"MediaContainer":{"Directory":[{"title":"A","size":400},{"title":"Z","size":12}]}}"""
        }
        val source = PersonalPlexSource("token", "https://plex", "machine", "Server", "7", "Music")
        val index = LocalPlexClient(MemoryStorage(), http).letterIndex(source, BrowseKind.ARTISTS)
        assertEquals(listOf(LibraryLetter("A", 0, 400), LibraryLetter("Z", 400, 12)), index)
        assertEquals(1, http.calls.size)
        assertTrue(http.calls.single().url.contains("/7/firstCharacter?type=8"))
    }

    @Test fun autoSearchUsesServerOffsetAndReportedTotal() = runBlocking {
        val http = FakeHttp().apply {
            responses += """{"MediaContainer":{"totalSize":150,"Metadata":[{"type":"track","ratingKey":"90","title":"Later match","grandparentTitle":"Artist"}]}}"""
        }
        val source = PersonalPlexSource("token", "https://plex", "machine", "Server", "7", "Music")
        val page = LocalPlexClient(MemoryStorage(), http).searchPage(source, "Artist", 90, 1)
        assertEquals(150, page.total); assertEquals("Later match", page.songs.single().title)
        assertTrue(http.calls.single().url.contains("X-Plex-Container-Start=90"))
        assertTrue(http.calls.single().url.contains("artist.title=Artist"))
    }

    @Test fun punctuationFallbackFindsHyphenatedTitleWithoutChangingSpelling() = runBlocking {
        val http = FakeHttp().apply {
            responses += """{"MediaContainer":{"totalSize":0,"size":0}}"""
            responses += """{"MediaContainer":{"totalSize":2,"Metadata":[{"type":"track","ratingKey":"90","title":"Franco Un-American","grandparentTitle":"NOFX"},{"type":"track","ratingKey":"91","title":"Franco Elsewhere","grandparentTitle":"Other"}]}}"""
        }
        val source = PersonalPlexSource("token", "https://plex", "machine", "Server", "7", "Music")
        val page = LocalPlexClient(MemoryStorage(), http).searchPage(source, "Franco Unamerican", 0, 40)
        assertEquals(1, page.total); assertEquals("Franco Un-American", page.songs.single().title)
        assertEquals(2, http.calls.size)
        assertFalse(punctuationSearchMatches(Song("x", "Franco Un-American", "NOFX"), "Franco Unamerikan"))
    }

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

    @Test fun roomAccessProbeChecksServerAndExactMusicSectionUsingSharedToken() = runBlocking {
        val http = FakeHttp().apply {
            responses += """{"MediaContainer":{"machineIdentifier":"machine"}}"""
            responses += """{"MediaContainer":{"Directory":[{"key":"7","type":"artist"}]}}"""
        }
        val source = PersonalPlexSource("shared-token", "https://plex", "machine", "Server", "7", "Music", canWriteToPlex = false)
        assertTrue(LocalPlexClient(MemoryStorage(), http).canAccessMusicLibrary(source))
        assertEquals(listOf("https://plex/", "https://plex/library/sections"), http.calls.map { it.url })
        assertTrue(http.calls.all { it.method == "GET" && it.headers["X-Plex-Token"] == "shared-token" })
    }

    @Test fun roomAccessProbeRejectsMissingLibraryWrongTypeAndWrongServer() = runBlocking {
        val source = PersonalPlexSource("shared-token", "https://plex", "machine", "Server", "7", "Music", canWriteToPlex = false)
        for (sections in listOf("""{"Directory":[]}""", """{"size":0}""",
            """{"Directory":[{"key":"8","type":"artist"}]}""", """{"Directory":[{"key":"7","type":"movie"}]}""")) {
            val http = FakeHttp().apply {
                responses += """{"MediaContainer":{"machineIdentifier":"machine"}}"""
                responses += """{"MediaContainer":$sections}"""
            }
            assertFalse(LocalPlexClient(MemoryStorage(), http).canAccessMusicLibrary(source))
        }
        val http = FakeHttp().apply { responses += """{"MediaContainer":{"machineIdentifier":"other"}}""" }
        assertFalse(LocalPlexClient(MemoryStorage(), http).canAccessMusicLibrary(source))
        assertEquals(1, http.calls.size)
    }

    @Test fun incompleteLibraryResponseIsNotAnAccessRevocation() = runBlocking {
        val http = FakeHttp().apply {
            responses += """{"MediaContainer":{"machineIdentifier":"machine"}}"""
            responses += """{"MediaContainer":{}}"""
        }
        val source = PersonalPlexSource("shared-token", "https://plex", "machine", "Server", "7", "Music", canWriteToPlex = false)
        assertTrue(runCatching { LocalPlexClient(MemoryStorage(), http).canAccessMusicLibrary(source) }.isFailure)
    }

    @Test fun accountUsesAccountTokenAndParsesIdentity() = runBlocking {
        val http = FakeHttp().apply {
            responses += """{"username":"listener","email":"listener@example.com","title":"Display name"}"""
            responses += """{"username":null,"title":"Home listener","email":null}"""
            responses += """{"email":"listener@example.com"}"""
        }
        val client = LocalPlexClient(MemoryStorage(), http)
        assertEquals(PlexAccount("listener", "listener@example.com"), client.account("account-token"))
        assertEquals("https://plex.tv/api/v2/user", http.calls.first().url)
        assertEquals("account-token", http.calls.first().headers["X-Plex-Token"])
        assertEquals(PlexAccount("Home listener", ""), client.account("account-token"))
        assertEquals(PlexAccount("listener@example.com", "listener@example.com"), client.account("account-token"))
    }

    @Test fun accountRejectsMissingIdentity() = runBlocking {
        val http = FakeHttp().apply { responses += """{"username":null,"email":null}""" }
        assertTrue(runCatching { LocalPlexClient(MemoryStorage(), http).account("account-token") }.isFailure)
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
              {"owned":true,"provides":"server","clientIdentifier":"mine","name":"My Plex","connections":[{"uri":"https://mine.plex.direct/","local":false,"relay":false}]},
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

    @Test fun connectionSkipsLocalAndPrefersDirectRemoteBeforeRelay() = runBlocking {
        val http = FakeHttp().apply {
            responses += """{"MediaContainer":{"machineIdentifier":"machine"}}"""
        }
        val server = PlexServer("machine", "Server", listOf(
            PlexConnection("https://local", true, false),
            PlexConnection("https://relay", false, true),
            PlexConnection("https://remote", false, false),
        ))
        assertEquals("https://remote", LocalPlexClient(MemoryStorage(), http).connect("token", server))
        assertEquals(listOf("https://remote/"), http.calls.map { it.url })
    }

    @Test fun connectionFallsBackToRelayButNeverLocal() = runBlocking {
        val http = FakeHttp().apply {
            responses += """{"MediaContainer":{"machineIdentifier":"wrong"}}"""
            responses += """{"MediaContainer":{"machineIdentifier":"machine"}}"""
        }
        val server = PlexServer("machine", "Server", listOf(
            PlexConnection("https://local", true, false),
            PlexConnection("https://remote", false, false),
            PlexConnection("https://relay", false, true),
        ))
        val client = LocalPlexClient(MemoryStorage(), http)
        assertEquals("https://relay", client.connect("token", server))
        assertEquals(listOf("https://remote/", "https://relay/"), http.calls.map { it.url })
        assertTrue(runCatching { client.connect("token", server.copy(connections = server.connections.take(1))) }.isFailure)
        assertEquals(2, http.calls.size)
    }

    @Test fun savedLocalSourceRefreshPreservesLibraryAndAccount() = runBlocking {
        val http = FakeHttp().apply {
            responses += """[{"clientIdentifier":"machine","name":"Server","provides":"server","accessToken":"resource","connections":[{"uri":"https://local","local":true},{"uri":"https://remote","local":false}]}]"""
            responses += """{"MediaContainer":{"machineIdentifier":"machine"}}"""
        }
        val source = PersonalPlexSource("old", "https://local", "machine", "Server", "7", "Music", "account", false)
        assertEquals(source.copy(token = "resource", baseUrl = "https://remote"),
            LocalPlexClient(MemoryStorage(), http).refreshRemoteSource(source))
        assertEquals("account", http.calls.first().headers["X-Plex-Token"])
        assertEquals("resource", http.calls.last().headers["X-Plex-Token"])
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

    @Test fun browseSearchKeepsAlbumMetadataWithoutExpandingAlbumTracks() = runBlocking {
        val http = FakeHttp().apply {
            responses += """{"MediaContainer":{}}"""
            responses += """{"MediaContainer":{}}"""
            responses += """{"MediaContainer":{"Metadata":[{"type":"album","ratingKey":"91","title":"Blue","parentTitle":"Joni Mitchell","year":1971}],"totalSize":1}}"""
        }
        val source = PersonalPlexSource("token", "https://plex", "machine", "Server", "7", "Music")
        val client = LocalPlexClient(MemoryStorage(), http)
        assertTrue(client.search(source, "Blue", expandAlbums = false).isEmpty())
        val album = client.browse(source, BrowseKind.ALBUMS, BrowseOrder.TITLE, query = "Blue").entries.single()
        assertEquals("Blue", album.title)
        assertEquals("Joni Mitchell", album.subtitle)
        assertEquals("plex-collection:machine:7:91", album.id)
        assertEquals(BrowseKind.ALBUMS, album.kind)
        assertFalse(http.calls.any { it.url.contains("/children") || it.url.contains("/allLeaves") })
        assertTrue(http.calls.last().url.contains("type=9"))
    }

    @Test fun artistBrowseSearchShowsAllAlbumPagesWithoutExpandingSongs() = runBlocking {
        val http = FakeHttp().apply {
            // Direct song match remains available alongside album cards.
            responses += """{"MediaContainer":{"Metadata":[{"type":"track","ratingKey":"42","title":"Tesla Song","Media":[{"Part":[{"key":"/song"}]}]}]}}"""
            responses += """{"MediaContainer":{"Metadata":[{"type":"album","ratingKey":"91","title":"Tesla","parentTitle":"Tesla"}]}}"""
            responses += """{"MediaContainer":{"Metadata":[{"type":"artist","ratingKey":"12","title":"Tesla"}]}}"""
            responses += """{"MediaContainer":{"Metadata":[{"librarySectionID":"7"}]}}"""
            responses += """{"MediaContainer":{"size":1,"totalSize":2,"Metadata":[{"type":"album","ratingKey":"91","title":"Tesla","parentTitle":"Tesla"}]}}"""
            responses += """{"MediaContainer":{"Metadata":[{"librarySectionID":"7"}]}}"""
            responses += """{"MediaContainer":{"size":1,"totalSize":2,"Metadata":[{"type":"album","ratingKey":"92","title":"Mechanical Resonance","parentTitle":"Tesla","year":1986}]}}"""
        }
        val storage = MemoryStorage()
        val source = PersonalPlexSource("token", "https://plex", "machine", "Server", "7", "Music")
        val library = LocalHarmonicastCore(source, storage, LocalPlexClient(storage, http)).library
        assertEquals(listOf("Tesla Song"), library.searchForBrowsing("Tesla").map { it.title })
        val albums = library.searchAlbums("Tesla")
        assertEquals(listOf("Mechanical Resonance", "Tesla"), albums.map { it.title })
        assertEquals(listOf(1986, null), albums.map { it.year })
        assertEquals(2, albums.map { it.id }.distinct().size)
        assertTrue(albums.all { it.kind == BrowseKind.ALBUMS })
        assertFalse(http.calls.any { it.url.contains("/allLeaves") || it.url.contains("/children") })
        assertTrue(http.calls.last().url.contains("artist.id=12"))
        assertTrue(http.calls.last().url.contains("X-Plex-Container-Start=1"))
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
