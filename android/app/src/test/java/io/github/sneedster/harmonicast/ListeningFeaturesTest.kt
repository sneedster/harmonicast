package io.github.sneedster.harmonicast

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test
import org.junit.Assert.*
import kotlin.math.*

class ListeningFeaturesTest {
    @Test fun artworkPreviewUsesEligibleFavoritesWithoutChangingPlaybackOrConsent() = kotlinx.coroutines.runBlocking {
        val memory = Memory()
        memory.write(mapOf("local.queue" to "[]", "local.jukeboxMixIndex" to "17"))
        AutomaticPlexRatings(memory).enabled = false
        val now = 1000L * ReplayWindow.DAY_MILLIS
        val old = (now - 100 * ReplayWindow.DAY_MILLIS) / 1000
        val recent = (now - ReplayWindow.DAY_MILLIS) / 1000
        val calls = mutableListOf<String>()
        val http = object : PlexHttp {
            override suspend fun request(url: String, method: String, headers: Map<String, String>, form: Map<String, String>): String {
                assertEquals("GET", method); calls += url
                return """{"MediaContainer":{"Metadata":[
                    {"type":"track","ratingKey":"1","title":"Old favorite","parentTitle":"Old album","grandparentTitle":"Artist","userRating":8,"viewCount":2,"lastViewedAt":$old,"thumb":"/cover/1"},
                    {"type":"track","ratingKey":"4","title":"Same album","parentTitle":"Old album","grandparentTitle":"Artist","userRating":8,"viewCount":2,"lastViewedAt":$old,"thumb":"/cover/4"},
                    {"type":"track","ratingKey":"2","title":"Recent favorite","userRating":8,"viewCount":2,"lastViewedAt":$recent,"thumb":"/cover/2"},
                    {"type":"track","ratingKey":"3","title":"Unknown history","userRating":8,"viewCount":0,"thumb":"/cover/3"}
                ]}}"""
            }
        }
        val source = PersonalPlexSource("token", "https://plex", "machine", "Server", "7", "Music")
        val core = LocalHarmonicastCore(source, memory, LocalPlexClient(memory, http), nowMillis = { now })
        assertEquals(listOf("Old favorite"), core.library.mixPreview(MixDiscovery.FORGOTTEN).map { it.title })
        assertTrue(calls.single().contains("X-Plex-Container-Size=100"))
        val query = calls.single().toHttpUrl().queryParameterNames
        assertTrue(query.contains("lastViewedAt>>"))
        assertFalse(query.contains("lastViewedAt>"))
        assertEquals("[]", memory.read("local.queue"))
        assertEquals("17", memory.read("local.jukeboxMixIndex"))
        assertFalse(AutomaticPlexRatings(memory).enabled)
    }

    @Test fun underplayedPreviewUsesStrictPlayCountsAndAlbumArtwork() = kotlinx.coroutines.runBlocking {
        val memory = Memory()
        var requested = ""
        val http = object : PlexHttp {
            override suspend fun request(url: String, method: String, headers: Map<String, String>, form: Map<String, String>): String {
                requested = url
                return """{"MediaContainer":{"Metadata":[
                    {"type":"track","ratingKey":"1","title":"Overlooked","parentTitle":"Album","viewCount":2,"parentThumb":"/album-cover"},
                    {"type":"track","ratingKey":"2","title":"Never heard","viewCount":0,"thumb":"/unplayed"},
                    {"type":"track","ratingKey":"3","title":"Frequently heard","viewCount":4,"thumb":"/frequent"}
                ]}}"""
            }
        }
        val source = PersonalPlexSource("token", "https://plex", "machine", "Server", "7", "Music")
        val core = LocalHarmonicastCore(source, memory, LocalPlexClient(memory, http))
        val preview = core.library.mixPreview(MixDiscovery.UNDERPLAYED).single()
        assertEquals("Overlooked", preview.title)
        assertTrue(preview.artworkUri!!.contains("/album-cover"))
        assertEquals("0", requested.toHttpUrl().queryParameter("viewCount>>"))
        assertEquals("4", requested.toHttpUrl().queryParameter("viewCount<<"))
        assertNull(requested.toHttpUrl().queryParameter("viewCount>"))
        assertNull(requested.toHttpUrl().queryParameter("viewCount<"))
    }
    @Test fun forgottenArtworkCanPreviewFavoritesBeforeNinetyDaysOfHistoryWithoutChangingMix() = kotlinx.coroutines.runBlocking {
        val memory = Memory()
        memory.write(mapOf("local.queue" to "[]", "local.jukeboxMixIndex" to "23"))
        val calls = mutableListOf<String>()
        val now = 1000L * ReplayWindow.DAY_MILLIS
        val recent = (now - ReplayWindow.DAY_MILLIS) / 1000
        val http = object : PlexHttp {
            override suspend fun request(url: String, method: String, headers: Map<String, String>, form: Map<String, String>): String {
                calls += url
                return """{"MediaContainer":{"Metadata":[
                    {"type":"track","ratingKey":"1","title":"Recent favorite","userRating":9,"viewCount":2,"lastViewedAt":$recent,"parentThumb":"/favorite-cover"}
                ]}}"""
            }
        }
        val source = PersonalPlexSource("token", "https://plex", "machine", "Server", "7", "Music")
        val core = LocalHarmonicastCore(source, memory, LocalPlexClient(memory, http), nowMillis = { now })
        val cover = core.library.mixPreview(MixDiscovery.FORGOTTEN).single()
        assertEquals("Recent favorite", cover.title)
        assertFalse(rediscoveryEligible(cover, MixDiscovery.FORGOTTEN, now, emptyMap()))
        assertEquals("userRating:desc", calls.last().toHttpUrl().queryParameter("sort"))
        assertEquals("[]", memory.read("local.queue"))
        assertEquals("23", memory.read("local.jukeboxMixIndex"))
    }
    private class Memory : ProfileStorage {
        val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(values: Map<String, String>) { this.values.putAll(values) }
    }
    @Test fun presetRoundTripPreservesRatingConsentAndTuning() {
        val memory = Memory()
        AutomaticPlexRatings(memory).enabled = false
        MusicTuningStore(memory).write(MusicTuning(4, 1, 3, 0))
        memory.write(mapOf("local.ratedTrackShare" to "3"))
        ReplayWindow(memory).days = 14
        val store = MixPresetStore(memory); store.discovery = MixDiscovery.UNPLAYED
        val saved = store.save(" Road trip ").single()
        MusicTuningStore(memory).write(MusicTuning(1, 4, 0, 4))
        store.discovery = MixDiscovery.STANDARD
        MixPresetStore(memory).apply(saved)
        assertEquals(3, memory.read("local.ratedTrackShare")!!.toInt())
        assertEquals(14, ReplayWindow(memory).days)
        assertEquals(MixDiscovery.UNPLAYED, store.discovery)
        assertEquals(MusicTuning(1, 4, 0, 0), MusicTuningStore(memory).read())
        assertFalse(AutomaticPlexRatings(memory).enabled)
        assertEquals(saved.id, store.save("road trip").single().id)
        store.remove(saved.id); assertTrue(store.read().isEmpty())
    }
    @Test fun rediscoveryRejectsRecentLocalListensAndDoesNotCallUnknownDatesForgotten() {
        val now = 1000L * ReplayWindow.DAY_MILLIS
        val old = now - 100 * ReplayWindow.DAY_MILLIS
        val song = Song("1", "Song", "Artist", rating = 8.0, viewCount = 2, lastPlayedAtMillis = old)
        assertTrue(rediscoveryEligible(song, MixDiscovery.FORGOTTEN, now, emptyMap()))
        assertFalse(rediscoveryEligible(song, MixDiscovery.FORGOTTEN, now, mapOf("1" to now)))
        assertFalse(rediscoveryEligible(song.copy(lastPlayedAtMillis = null), MixDiscovery.FORGOTTEN, now, emptyMap()))
        assertTrue(rediscoveryEligible(song, MixDiscovery.UNDERPLAYED, now, emptyMap()))
        assertFalse(rediscoveryEligible(song.copy(viewCount = 4), MixDiscovery.UNDERPLAYED, now, emptyMap()))
        val never = song.copy(viewCount = 0, lastPlayedAtMillis = null)
        assertTrue(rediscoveryEligible(never, MixDiscovery.UNPLAYED, now, emptyMap()))
        assertFalse(rediscoveryEligible(never, MixDiscovery.UNPLAYED, now, mapOf("1" to now)))
    }
    @Test fun spectrumRespondsToAudioAndSilenceHasNoInventedEnergy() {
        val silence = DemoAudio.spectrum(DoubleArray(512), 12000)
        assertTrue(silence.all { it == 0f })
        val tone = DemoAudio.spectrum(DoubleArray(512) { .5 * sin(2 * PI * 1000 * it / 12000) }, 12000)
        assertTrue(tone[4] > .5f)
        assertTrue(tone[4] > tone[0] * 10)
        assertTrue(tone.all { it in 0f..1f })
    }
}
