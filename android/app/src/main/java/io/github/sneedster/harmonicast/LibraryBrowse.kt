package io.github.sneedster.harmonicast

/** Collection metadata stays in-process; never exposed through the guest room router. */
enum class BrowseKind(val plexType: Int, val label: String) { ALBUMS(9, "Albums"), ARTISTS(8, "Artists") }
enum class BrowseOrder(val plexSort: String, val label: String) {
    RECENT("addedAt:desc", "Recently added"), TITLE("titleSort:asc", "A–Z"), REVERSE_TITLE("titleSort:desc", "Z–A"), PLAYED("lastViewedAt:desc", "Recently played")
}
data class LibraryEntry(val id: String, val title: String, val subtitle: String, val artwork: String?, val kind: BrowseKind, val year: Int? = null, val summary: String = "")
data class LibraryPage(val entries: List<LibraryEntry>, val nextOffset: Int?)

/** Offset counts raw Plex entries, including unplayable entries and repeated tracks. */
data class PlaylistTrackPage(val tracks: List<Song>, val nextOffset: Int?)
