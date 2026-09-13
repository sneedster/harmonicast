package io.github.sneedster.harmonicast

import java.net.URLDecoder
import java.net.URLEncoder

internal data class AutoLibraryNode(val id: String, val title: String, val subtitle: String = "", val artwork: String? = null, val song: Song? = null)

/** Separate Auto browse tree; shares library data, never the player's timeline. */
internal class AutoLibraryBrowser(private val library: MusicLibrary) {
    companion object {
        const val ROOT = "harmonicast:library"
        private const val PREFIX = "$ROOT/"
        fun handles(id: String) = id == ROOT || id.startsWith(PREFIX)
    }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun decode(value: String) = URLDecoder.decode(value, "UTF-8")
    private fun route(kind: BrowseKind, order: BrowseOrder, offset: Int = 0, parent: String = "") =
        "${PREFIX}browse/${kind.name}/${order.name}/$offset/${encode(parent)}"

    private fun node(entry: LibraryEntry) = AutoLibraryNode(
        if (entry.kind == BrowseKind.ARTISTS) route(BrowseKind.ALBUMS, BrowseOrder.TITLE, parent = entry.id)
        else "${PREFIX}album/${encode(entry.id)}", entry.title, entry.subtitle, entry.artwork)

    suspend fun children(id: String): List<AutoLibraryNode> {
        if (id == ROOT) return listOf(
            AutoLibraryNode(route(BrowseKind.ALBUMS, BrowseOrder.RECENT), "Recently added"),
            AutoLibraryNode("${PREFIX}letters/ALBUMS", "Albums"),
            AutoLibraryNode("${PREFIX}letters/ARTISTS", "Artists"),
            AutoLibraryNode(route(BrowseKind.ALBUMS, BrowseOrder.PLAYED), "Recently played"),
        )
        require(id.startsWith(PREFIX))
        val pieces = id.removePrefix(PREFIX).split('/')
        if (pieces.first() == "letters") {
            require(pieces.size == 2)
            val kind = BrowseKind.valueOf(pieces[1])
            return library.letterIndex(kind).map { letter ->
                AutoLibraryNode("${PREFIX}letter/${kind.name}/${letter.offset}/${Math.addExact(letter.offset, letter.count)}",
                    letter.title, "${letter.count} ${if (kind == BrowseKind.ARTISTS) "artists" else "albums"}")
            }
        }
        if (pieces.first() == "letter") {
            require(pieces.size == 4)
            val kind = BrowseKind.valueOf(pieces[1])
            val offset = pieces[2].toInt(); val end = pieces[3].toInt()
            require(offset >= 0 && end > offset)
            val page = library.browse(kind, BrowseOrder.TITLE, offset)
            val nodes = page.entries.take(end - offset).map { entry -> node(entry) }
            return nodes + listOfNotNull(page.nextOffset?.takeIf { it > offset && it < end }?.let {
                AutoLibraryNode("${PREFIX}letter/${kind.name}/$it/$end", "More…", "Continue this letter")
            })
        }
        if (pieces.first() == "album") {
            require(pieces.size == 2)
            return library.albumTracks(decode(pieces[1])).filter { it.streamUri != null }.map { AutoLibraryNode(it.id, it.title, it.artist, song = it) }
        }
        require(pieces.size == 5 && pieces[0] == "browse")
        val kind = BrowseKind.valueOf(pieces[1]); val order = BrowseOrder.valueOf(pieces[2])
        val offset = pieces[3].toInt().also { require(it >= 0) }
        val parent = decode(pieces[4]).ifEmpty { null }
        val page = library.browse(kind, order, offset, parent)
        val nodes = page.entries.map { node(it) }
        return nodes + listOfNotNull(page.nextOffset?.takeIf { it > offset }?.let {
            AutoLibraryNode(route(kind, order, it, parent.orEmpty()), "More…", "Continue browsing")
        })
    }
}
