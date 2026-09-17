package io.github.sneedster.harmonicast

import java.text.Normalizer
import java.util.Locale

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
