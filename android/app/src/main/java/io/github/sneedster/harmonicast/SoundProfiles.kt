package io.github.sneedster.harmonicast

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class SoundProfile(val name: String, val settings: EqSettings)
internal class SoundProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("device_equalizer", Context.MODE_PRIVATE)
    fun read(): List<SoundProfile> = runCatching {
        val array = JSONArray(prefs.getString("profiles_v1", "[]"))
        (0 until array.length()).map { i -> val json = array.getJSONObject(i)
            SoundProfile(json.getString("name"), EqualizerStore.decode(json.getJSONObject("eq").toString()))
        }.take(20)
    }.getOrDefault(emptyList())
    fun save(name: String, settings: EqSettings) {
        val clean = name.trim(); require(clean.length in 1..40) { "Use a name of 1–40 characters" }
        val profiles = read(); require(profiles.size < 20 || profiles.any { it.name.equals(clean, true) }) { "Remove a profile before saving another" }
        write(profiles.filterNot { it.name.equals(clean, true) } + SoundProfile(clean, settings.validated()))
    }
    fun remove(name: String) = write(read().filterNot { it.name == name })
    private fun write(profiles: List<SoundProfile>) {
        check(prefs.edit().putString("profiles_v1", JSONArray().apply {
            profiles.forEach { profile -> put(JSONObject().put("name", profile.name).put("eq",
                JSONObject().put("enabled", profile.settings.enabled).put("preampDb", profile.settings.preampDb)
                    .put("gains", JSONArray().apply { profile.settings.points.forEach { put(it.gain) } }))) }
        }.toString()).commit()) { "Could not save sound profiles" }
    }
}
