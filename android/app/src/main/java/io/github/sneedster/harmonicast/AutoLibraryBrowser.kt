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

    suspend fun children(id: String): List<AutoLibraryNode> {
        if (id == ROOT) return listOf(
            AutoLibraryNode(route(BrowseKind.ALBUMS, BrowseOrder.RECENT), "Recently added"),
            AutoLibraryNode(route(BrowseKind.ALBUMS, BrowseOrder.TITLE), "Albums"),
            AutoLibraryNode(route(BrowseKind.ARTISTS, BrowseOrder.TITLE), "Artists"),
            AutoLibraryNode(route(BrowseKind.ALBUMS, BrowseOrder.PLAYED), "Recently played"),
        )
        require(id.startsWith(PREFIX))
        val pieces = id.removePrefix(PREFIX).split('/')
        if (pieces.first() == "album") {
            require(pieces.size == 2)
            return library.albumTracks(decode(pieces[1])).filter { it.streamUri != null }.map { AutoLibraryNode(it.id, it.title, it.artist, song = it) }
        }
        require(pieces.size == 5 && pieces[0] == "browse")
        val kind = BrowseKind.valueOf(pieces[1]); val order = BrowseOrder.valueOf(pieces[2])
        val offset = pieces[3].toInt().also { require(it >= 0) }
        val parent = decode(pieces[4]).ifEmpty { null }
        val page = library.browse(kind, order, offset, parent)
        val nodes = page.entries.map { entry ->
            AutoLibraryNode(if (entry.kind == BrowseKind.ARTISTS) route(BrowseKind.ALBUMS, BrowseOrder.TITLE, parent = entry.id)
                else "${PREFIX}album/${encode(entry.id)}", entry.title, entry.subtitle, entry.artwork)
        }
        return nodes + listOfNotNull(page.nextOffset?.takeIf { it > offset }?.let {
            AutoLibraryNode(route(kind, order, it, parent.orEmpty()), "More…", "Continue browsing")
        })
    }
}
