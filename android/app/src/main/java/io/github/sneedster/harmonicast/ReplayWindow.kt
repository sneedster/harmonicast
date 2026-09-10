package io.github.sneedster.harmonicast

import org.json.JSONArray
import org.json.JSONObject

/** Rolling elapsed days, independent of automatic Plex rating consent. */
internal class ReplayWindow(private val storage: ProfileStorage) {
    var days: Int
        get() = storage.read(SETTING_KEY)?.toIntOrNull()?.takeIf { it in DAY_OPTIONS } ?: 7
        set(value) {
            require(value in DAY_OPTIONS)
            storage.write(mapOf(SETTING_KEY to value.toString(), STATUS_KEY to ""))
        }

    fun cutoff(nowMillis: Long): Long? = days.takeIf { it > 0 }?.let { nowMillis - it * DAY_MILLIS }

    companion object {
        val DAY_OPTIONS = listOf(0, 1, 3, 7, 14, 30)
        val LABELS = listOf("Off", "1 day", "3 days", "1 week", "2 weeks", "30 days")
        const val DAY_MILLIS = 86_400_000L
        const val SETTING_KEY = "local.replayWindowDays"
        const val EMPTY_MESSAGE = "No eligible automatic tracks. Adjust Settings → Automatic mix, or request a song."
        const val STATUS_KEY = "local.automaticMixStatus"
    }
}

/** Track-ID timestamps outlive the 500-event display history, including skips and starts. */
internal class RecentTrackPlays(private val storage: ProfileStorage) {
    fun snapshot(nowMillis: Long): Map<String, Long> = synchronized(lock) { read(nowMillis) }

    fun record(id: String, nowMillis: Long) = synchronized(lock) {
        if (id.isBlank()) return@synchronized
        val values = read(nowMillis).toMutableMap()
        values[id] = maxOf(values[id] ?: 0L, nowMillis)
        storage.write(mapOf(KEY to JSONObject(values as Map<*, *>).toString()))
    }

    private fun read(nowMillis: Long): Map<String, Long> {
        val persisted = storage.read(KEY)
        val json = persisted?.let { runCatching { JSONObject(it) }.getOrNull() }
        val values = mutableMapOf<String, Long>()
        if (json != null) {
            json.keys().forEach { id -> json.optLong(id).takeIf { it > 0 }?.let { values[id] = it } }
        } else {
            // Import the history available on upgrade; never fabricate older skip dates.
            val history = storage.read("local.playbackHistory")?.let { runCatching { JSONArray(it) }.getOrNull() }
            for (index in 0 until (history?.length() ?: 0)) {
                val event = history?.optJSONObject(index) ?: continue
                if (event.optString("event") !in setOf("complete", "completed", "skip")) continue
                val id = event.optJSONObject("song")?.optString("id").orEmpty()
                val at = event.optLong("at")
                if (id.isNotBlank() && at > 0) values[id] = maxOf(values[id] ?: 0L, at)
            }
        }
        val retained = values.filterValues { it > nowMillis - 30 * ReplayWindow.DAY_MILLIS }
        if (persisted == null || json == null || retained.size != values.size) {
            storage.write(mapOf(KEY to JSONObject(retained as Map<*, *>).toString()))
        }
        return retained
    }

    companion object {
        const val KEY = "local.recentTrackPlays"
        private val lock = Any()
    }
}

internal fun eligibleForAutomaticMix(song: Song, cutoffMillis: Long?, localPlays: Map<String, Long>): Boolean =
    cutoffMillis == null || maxOf(song.lastPlayedAtMillis ?: 0L, localPlays[song.id] ?: 0L) <= cutoffMillis
