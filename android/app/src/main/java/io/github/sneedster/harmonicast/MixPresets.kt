package io.github.sneedster.harmonicast

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class MixDiscovery(val title: String, val description: String) {
    STANDARD("Your usual mix", "Favorites and fresh discoveries"),
    FORGOTTEN("Forgotten favorites", "Rated 7 or higher, last heard at least 90 days ago"),
    UNDERPLAYED("Underplayed tracks", "One to three recorded Plex plays"),
    UNPLAYED("Never played", "No recorded Plex plays or recent listening on this device"),
}
internal data class MixPreset(val id: String, val name: String, val ratedShare: Int, val selection: Int,
    val replayDays: Int, val discovery: MixDiscovery = MixDiscovery.STANDARD)
internal class MixPresetStore(private val storage: ProfileStorage) {
    var discovery: MixDiscovery
        get() = runCatching { MixDiscovery.valueOf(storage.read("local.mixDiscovery").orEmpty()) }.getOrDefault(MixDiscovery.STANDARD)
        set(value) = storage.write(mapOf("local.mixDiscovery" to value.name))
    fun read(): List<MixPreset> = runCatching {
        val array = JSONArray(storage.read(KEY) ?: "[]")
        (0 until array.length()).map { i -> val j = array.getJSONObject(i)
            MixPreset(j.getString("id"), j.getString("name"), j.getInt("share"), j.getInt("selection"),
                j.getInt("days"), MixDiscovery.valueOf(j.optString("discovery", "STANDARD")))
        }.filter { it.name.isNotBlank() && it.ratedShare in 0..10 && it.selection in 0..4 && it.replayDays in ReplayWindow.DAY_OPTIONS }.take(20)
    }.getOrDefault(emptyList())
    fun save(name: String): List<MixPreset> {
        val clean = name.trim(); require(clean.length in 1..40) { "Use a name of 1–40 characters" }
        val items = read(); val old = items.firstOrNull { it.name.equals(clean, true) }
        require(old != null || items.size < 20) { "Remove a preset before saving another" }
        val preset = MixPreset(old?.id ?: UUID.randomUUID().toString(), clean,
            storage.read("local.ratedTrackShare")?.toIntOrNull()?.coerceIn(0, 10) ?: 8,
            MusicTuningStore(storage).read().selection, ReplayWindow(storage).days, discovery)
        return (items.filterNot { it.id == preset.id } + preset).also(::write)
    }
    fun remove(id: String) = write(read().filterNot { it.id == id })
    fun apply(preset: MixPreset) {
        require(preset.ratedShare in 0..10 && preset.selection in 0..4 && preset.replayDays in ReplayWindow.DAY_OPTIONS)
        val tuning = MusicTuningStore(storage).read()
        // Only selection changes: presets never grant consent or change rating adjustments.
        MusicTuningStore(storage).write(tuning.copy(selection = preset.selection))
        storage.write(mapOf("local.ratedTrackShare" to preset.ratedShare.toString(),
            ReplayWindow.SETTING_KEY to preset.replayDays.toString(), "local.mixDiscovery" to preset.discovery.name,
            ReplayWindow.STATUS_KEY to ""))
    }
    private fun write(items: List<MixPreset>) = storage.write(mapOf(KEY to JSONArray().apply {
        items.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("share", it.ratedShare)
            .put("selection", it.selection).put("days", it.replayDays).put("discovery", it.discovery.name)) }
    }.toString()))
    companion object { const val KEY = "local.mixPresets" }
}
internal fun rediscoveryEligible(song: Song, mode: MixDiscovery, now: Long, local: Map<String, Long>): Boolean {
    val last = maxOf(song.lastPlayedAtMillis ?: 0, local[song.id] ?: 0)
    return when(mode) {
        MixDiscovery.STANDARD -> true
        MixDiscovery.FORGOTTEN -> (song.rating ?: 0.0) >= 7.0 && last > 0 && last <= now - 90 * ReplayWindow.DAY_MILLIS
        MixDiscovery.UNDERPLAYED -> song.viewCount in 1..3
        MixDiscovery.UNPLAYED -> song.viewCount == 0 && last == 0L
    }
}
