package io.github.sneedster.harmonicast

import java.text.Normalizer
import java.util.Locale

internal class TrackRadioSettings(private val storage: ProfileStorage) {
    var distance: Double
        get() = storage.read("local.radioDistance")?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it in 0.05..0.30 } ?: 0.25
        set(value) {
            require(value.isFinite())
            val bounded = kotlin.math.round(value.coerceIn(0.05, 0.30) * 100) / 100
            storage.write(mapOf("local.radioDistance" to bounded.toString()))
        }
}

internal data class ArtistRadioCandidate(val song: Song, val distance: Double)

/** Restart at the saved target for every batch, widening only until it fills. */
internal suspend fun expandingArtistRadioBatch(
    seed: Song,
    excluded: List<Song>,
    artists: Set<String>,
    startDistance: Double,
    fetch: suspend (Double) -> List<ArtistRadioCandidate>,
): List<Song> {
    require(startDistance.isFinite() && startDistance in 0.05..0.30)
    val candidates = mutableListOf<ArtistRadioCandidate>()
    var distance = startDistance
    while (true) {
        candidates += fetch(distance)
        val selected = artistRadioBatch(seed, excluded, artists, candidates, distance)
        if (selected.size == 20 || distance >= 0.30) return selected
        distance = (kotlin.math.round((distance + 0.05) * 100) / 100).coerceAtMost(0.30)
    }
}

/** Artist relationships and sound are both requirements; a sparse pool stays short. */
internal fun artistRadioBatch(
    seed: Song,
    excluded: List<Song>,
    artists: Set<String>,
    candidates: List<ArtistRadioCandidate>,
    maxDistance: Double,
): List<Song> {
    require(maxDistance.isFinite() && maxDistance > 0)
    val allowed = artists.mapTo(mutableSetOf(), ::normalizeRadioTag)
    val close = candidates.filter {
        it.distance.isFinite() && it.distance >= 0 && it.distance <= maxDistance &&
            normalizeRadioTag(it.song.artist) in allowed && it.song.streamUri != null
    }.sortedBy { it.distance }.map { it.song }
    val remaining = distinctRadioTracks(seed, excluded, close).toMutableList()
    val selected = mutableListOf<Song>()
    val albums = (excluded + seed).mapTo(mutableSetOf(), ::albumKey)
    val artistCounts = (excluded + seed).groupingBy { normalizeRadioTag(it.artist) }.eachCount().toMutableMap()
    while (selected.size < 20 && remaining.isNotEmpty()) {
        val diverse = remaining.filterNot { albumKey(it) in albums }.ifEmpty { remaining }
        // Balance performers across the listening window. Within each artist,
        // the stable order retains Plex's closest sound matches first.
        val song = diverse.minBy { artistCounts[normalizeRadioTag(it.artist)] ?: 0 }
        remaining.remove(song)
        selected += song
        albums += albumKey(song)
        val artist = normalizeRadioTag(song.artist)
        artistCounts[artist] = (artistCounts[artist] ?: 0) + 1
    }
    return selected
}

/** Album/compilation copies must not pad a radio queue. Keep Plex's suggestion order. */
internal fun distinctRadioTracks(current: Song, queued: List<Song>, candidates: List<Song>): List<Song> {
    val ids = mutableSetOf<String>()
    val identities = mutableSetOf<Pair<String, String>>()
    fun remember(song: Song) {
        ids.add(song.id)
        radioIdentity(song)?.let(identities::add)
    }
    remember(current)
    queued.forEach(::remember)
    return candidates.filter { song ->
        val identity = radioIdentity(song)
        val duplicate = song.id in ids || (identity != null && identity in identities)
        // Remember rejected aliases too, so an ID cannot reappear with different metadata.
        remember(song)
        !duplicate
    }
}

internal fun normalizeRadioTag(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT)
    .replace(Regex("[’‘]"), "'")
    .replace(Regex("[‐‑–—]"), "-")
    .trim().replace(Regex("\\s+"), " ")

private fun radioIdentity(song: Song): Pair<String, String>? {
    val artist = normalizeRadioTag(song.artist)
    val title = normalizeRadioTag(song.title)
    // Missing tags do not establish identity. Version/remix qualifiers stay intact.
    return if (artist.isBlank() || title.isBlank()) null else artist to title
}
