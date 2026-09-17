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

/** Try close matches first; every batch restarts here, never at the last expanded distance. */
internal suspend fun radioBatch(
    current: Song,
    excluded: List<Song>,
    startDistance: Double = 0.25,
    fetch: suspend (Double) -> List<Song>,
): List<Song> {
    require(startDistance.isFinite() && startDistance > 0)
    val candidates = mutableListOf<Song>()
    var unique = emptyList<Song>()
    for (step in 0..2) {
        val distance = kotlin.math.round((startDistance + step * 0.05) * 1000) / 1000
        candidates += fetch(distance)
        unique = distinctRadioTracks(current, excluded, candidates).take(20)
        if (unique.size >= 10) break
    }
    return unique
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

private fun radioIdentity(song: Song): Pair<String, String>? {
    fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(Regex("[’‘]"), "'")
        .replace(Regex("[‐‑–—]"), "-")
        .trim().replace(Regex("\\s+"), " ")
    val artist = normalize(song.artist)
    val title = normalize(song.title)
    // Missing tags do not establish identity. Version/remix qualifiers stay intact.
    return if (artist.isBlank() || title.isBlank()) null else artist to title
}
