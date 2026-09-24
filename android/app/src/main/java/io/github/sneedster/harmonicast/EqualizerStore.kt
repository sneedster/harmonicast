package io.github.sneedster.harmonicast

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Deliberately separate from Plex profiles, room messages and transferred playback state. */
internal class EqualizerStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("device_equalizer", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(decode(preferences.getString("curve", null)))
    val state = mutable.asStateFlow()
    val sampleRate = MutableStateFlow(48000)
    fun update(settings: EqSettings) {
        val safe = settings.validated()
        mutable.value = safe
        val points = JSONArray().apply { safe.points.forEach { put(JSONObject().put("hz", it.frequency).put("db", it.gain).put("q", it.q)) } }
        preferences.edit().putString("curve", JSONObject().put("enabled", safe.enabled).put("points", points).toString()).apply()
    }
    companion object {
        @Volatile private var instance: EqualizerStore? = null
        fun get(context: Context): EqualizerStore = instance ?: synchronized(this) {
            instance ?: EqualizerStore(context).also { instance = it }
        }
        internal fun decode(raw: String?): EqSettings = runCatching {
            if (raw == null) return EqSettings()
            val json = JSONObject(raw)
            val points = json.getJSONArray("points")
            EqSettings(json.optBoolean("enabled", false), (0 until minOf(8, points.length())).map {
                val p = points.getJSONObject(it)
                EqPoint(p.getDouble("hz"), p.getDouble("db"), p.getDouble("q")).validated()
            })
        }.getOrDefault(EqSettings())
    }
}
