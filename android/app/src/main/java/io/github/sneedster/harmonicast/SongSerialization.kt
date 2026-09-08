package io.github.sneedster.harmonicast

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

internal fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
internal fun decodeSongs(array: JSONArray): List<Song> = List(array.length()) { decodeSong(array.getJSONObject(it)) }
internal fun decodeSong(item: JSONObject) = Song(
    item.optString("id"), item.optString("title"), item.optString("artist"), item.optString("album"),
    item.optInt("duration"), item.optString("coverArt"),
    if (item.has("rating") && !item.isNull("rating")) item.optDouble("rating").coerceIn(0.0, 10.0) else null,
    item.optString("addedByEmail"), item.optBoolean("isManual", true), item.optBoolean("isRadio", false),
    if (item.has("year") && !item.isNull("year")) item.optInt("year").takeIf { it > 0 } else null,
    item.optString("streamUri").takeIf { it.isNotBlank() },
    item.optString("artworkUri").takeIf { it.isNotBlank() },
    item.optInt("viewCount").coerceAtLeast(0),
)
internal fun encodeSong(song: Song) = JSONObject().put("id", song.id).put("title", song.title)
    .put("artist", song.artist).put("album", song.album).put("year", song.year)
    .put("duration", song.duration).put("coverArt", song.coverArt)
    .put("rating", song.rating ?: JSONObject.NULL).put("addedByEmail", song.addedByEmail)
    .put("isManual", song.isManual).put("isRadio", song.isRadio)
    .put("streamUri", song.streamUri).put("artworkUri", song.artworkUri)
    .put("viewCount", song.viewCount)
