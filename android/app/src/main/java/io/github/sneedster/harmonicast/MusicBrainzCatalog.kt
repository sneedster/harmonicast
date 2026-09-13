package io.github.sneedster.harmonicast

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal data class CatalogEntry(val id: String, val title: String, val artist: String = "", val kind: String = "recording",
    val album: String = "", val year: String = "", val durationMs: Int = 0, val detail: String = "", val inLibrary: Boolean = false) {
    fun json() = JSONObject().put("id", id).put("title", title).put("artist", artist).put("kind", kind)
        .put("album", album).put("year", year).put("durationMs", durationMs).put("detail", detail).put("inLibrary", inLibrary)
    companion object { fun decode(j: JSONObject) = CatalogEntry(j.getString("id"), j.getString("title"), j.optString("artist"),
        j.optString("kind", "recording"), j.optString("album"), j.optString("year"), j.optInt("durationMs"), j.optString("detail"), j.optBoolean("inLibrary")) }
}
internal data class CatalogPage(val entries: List<CatalogEntry>, val more: Boolean = false, val offset: Int = 0) {
    fun json() = JSONObject().put("items", JSONArray().apply { entries.forEach { put(it.json()) } }).put("more", more).put("offset", offset)
    companion object { fun decode(j: JSONObject): CatalogPage {
        val a = j.optJSONArray("items") ?: JSONArray()
        return CatalogPage(List(a.length()) { CatalogEntry.decode(a.getJSONObject(it)) }, j.optBoolean("more"), j.optInt("offset"))
    } }
}
internal class MusicBrainzCatalog(private val http: AcquisitionHttp = AcquisitionNetwork(readAttempts = 1), private val paceMillis: Long = 1000) {
    private val mutex = Mutex()
    private var lastRequest = 0L
    private val recordings = linkedMapOf<String, CatalogEntry>()
    private val pages = linkedMapOf<String, CatalogPage>()
    private suspend fun get(path: String): JSONObject = mutex.withLock {
        var last: Exception? = null
        repeat(3) { attempt ->
            delay((paceMillis - (System.currentTimeMillis() - lastRequest)).coerceAtLeast(0))
            lastRequest = System.currentTimeMillis()
            try { return@withLock http.call("https://musicbrainz.org/ws/2/$path", headers = mapOf("User-Agent" to "Harmonicast/${BuildConfig.VERSION_NAME} (https://github.com/sneedster/harmonicast)")) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is AcquisitionFailure && e.status in 400..499 && e.status !in setOf(408, 429)) throw e
                last = e; if (attempt < 2) delay(750L * (attempt + 1))
            }
        }
        throw last ?: IllegalStateException("MusicBrainz unavailable")
    }
    private val allowedTypes = setOf("album", "ep", "single")
    private val typeQuery = "(primarytype:album OR primarytype:ep OR primarytype:single) AND status:official"
    private fun officialRelease(release: JSONObject) = release.optString("status").equals("Official", true) &&
        release.optJSONObject("release-group")?.optString("primary-type")?.lowercase() in allowedTypes
    private fun recordingEntry(j: JSONObject, fallback: String = "", fromRelease: JSONObject? = null): CatalogEntry? {
        val id = j.optString("id"); val title = j.optString("title")
        val credits = j.optJSONArray("artist-credit") ?: JSONArray()
        val artist = (0 until credits.length()).joinToString(", ") { i ->
            val c = credits.getJSONObject(i); c.optString("name").ifBlank { c.optJSONObject("artist")?.optString("name").orEmpty() }
        }.ifBlank { fallback }
        if (id.isBlank() || title.isBlank() || artist.isBlank()) return null
        val releases = j.optJSONArray("releases") ?: JSONArray()
        val release = fromRelease?.takeIf(::officialRelease)
            ?: (0 until releases.length()).map { releases.getJSONObject(it) }.firstOrNull(::officialRelease)
            ?: return null
        val entry = CatalogEntry(id, title, artist, album = release?.optString("title").orEmpty(),
            year = release?.optString("date").orEmpty().take(4), durationMs = j.optInt("length"), detail = j.optString("disambiguation"))
        synchronized(recordings) { recordings[id] = entry; while (recordings.size > 500) recordings.remove(recordings.keys.first()) }
        return entry
    }
    private fun uuid(id: String) { require(Regex("[0-9a-fA-F-]{36}").matches(id)) { "Invalid catalogue selection" } }
    suspend fun recording(id: String): CatalogEntry {
        uuid(id)
        // Revalidate at submission; cached search results are not acquisition authority.
        return recordingEntry(get("recording/${encode(id)}?inc=artist-credits+releases+release-groups&status=official&type=album%7Cep%7Csingle&fmt=json"))
            ?: throw IllegalArgumentException("Only recordings on an official album, EP, or single can be acquired")
    }
    suspend fun browse(query: String, mode: String = "search", parent: String = "", offset: Int = 0): CatalogPage {
        require(query.length <= 200 && offset in 0..10000) { "Search is too long or page is unavailable" }
        val key = "$mode|$query|$parent|$offset"
        synchronized(pages) { pages[key] }?.let { return it }
        val page = when (mode) {
            "search" -> {
                require(query.isNotBlank()) { "Enter a song or artist" }
                val literal = query.replace(Regex("([+\\\\!(){}\\[\\]^\"~*?:/|&-])")) { "\\${it.value}" }
                val data = get("recording/?query=${encode("($literal) AND $typeQuery")}&fmt=json&limit=5&offset=$offset")
                val found = data.optJSONArray("recordings") ?: JSONArray()
                val entries = (0 until found.length()).mapNotNull { recordingEntry(found.getJSONObject(it)) }
                if (entries.isEmpty() && offset == 0 && data.optInt("count") == 0) browse(query, "artist") else CatalogPage(entries, offset + found.length() < data.optInt("count", found.length()), offset)
            }
            "artist" -> {
                val found = get("artist/?query=${encode(query)}&fmt=json&limit=5").optJSONArray("artists") ?: JSONArray()
                fun normal(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
                CatalogPage((0 until found.length()).map { found.getJSONObject(it) }.filter { normal(it.optString("name")) == normal(query) }
                    .map { CatalogEntry(it.getString("id"), it.getString("name"), it.getString("name"), "artist") })
            }
            "albums" -> {
                uuid(parent)
                val data = get("release-group/?query=${encode("arid:$parent AND $typeQuery")}&fmt=json&limit=25&offset=$offset")
                val found = data.optJSONArray("release-groups") ?: JSONArray()
                CatalogPage((0 until found.length()).mapNotNull { val j = found.getJSONObject(it)
                    if (j.optString("primary-type").lowercase() !in allowedTypes) return@mapNotNull null
                    CatalogEntry(j.getString("id"), j.getString("title"), query, "album", year = j.optString("first-release-date").take(4), detail = j.optString("primary-type"))
                }, offset + found.length() < data.optInt("count", offset + found.length()), offset)
            }
            "tracks" -> {
                uuid(parent)
                val releases = get("release/?release-group=${encode(parent)}&status=official&type=album%7Cep%7Csingle&inc=release-groups&fmt=json&limit=25").optJSONArray("releases")
                val releaseId = (0 until (releases?.length() ?: 0)).map { releases!!.getJSONObject(it) }.firstOrNull(::officialRelease)?.optString("id")
                if (releaseId.isNullOrBlank()) CatalogPage(emptyList()) else {
                    val release = get("release/${encode(releaseId)}?inc=recordings+artist-credits+release-groups&fmt=json")
                    require(officialRelease(release)) { "Only official albums, EPs, and singles are available" }
                    val media = release.optJSONArray("media") ?: JSONArray()
                    val entries = buildList { for (i in 0 until media.length()) {
                        val tracks = media.getJSONObject(i).optJSONArray("tracks") ?: continue
                        for (j in 0 until tracks.length()) tracks.getJSONObject(j).optJSONObject("recording")?.let { recordingEntry(it, query, release)?.let(::add) }
                    } }.distinctBy { it.id }
                    CatalogPage(entries.drop(offset).take(25), offset + 25 < entries.size, offset)
                }
            }
            else -> throw IllegalArgumentException("Unknown catalogue operation")
        }
        synchronized(pages) { pages[key] = page; while (pages.size > 50) pages.remove(pages.keys.first()) }
        return page
    }
}
