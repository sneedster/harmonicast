package io.github.sneedster.harmonicast

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Device-local. The retired curve key is retained for rollback, never applied to fixed bands. */
internal class EqualizerStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("device_equalizer", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(decode(preferences.getString("graphic_v1", null)))
    val state = mutable.asStateFlow()
    val sampleRate = MutableStateFlow(48000)
    fun update(settings: EqSettings) {
        val safe = EqSettings(settings.enabled, EqSettings.defaults.mapIndexed { index, band ->
            band.copy(gain = settings.points.getOrElse(index) { band }.validated().gain)
        }, settings.preampDb).validated()
        mutable.value = safe
        val gains = JSONArray().apply { safe.points.forEach { put(it.gain) } }
        preferences.edit().putString("graphic_v1", JSONObject().put("enabled", safe.enabled).put("gains", gains).put("preampDb", safe.preampDb).toString()).apply()
    }
    companion object {
        @Volatile private var instance: EqualizerStore? = null
        fun get(context: Context): EqualizerStore = instance ?: synchronized(this) {
            instance ?: EqualizerStore(context).also { instance = it }
        }
        internal fun decode(raw: String?): EqSettings = runCatching {
            if (raw == null) return EqSettings()
            val json = JSONObject(raw)
            val gains = json.getJSONArray("gains")
            require(gains.length() == EqSettings.defaults.size)
            EqSettings(json.optBoolean("enabled", false), EqSettings.defaults.mapIndexed { index, band ->
                band.copy(gain = gains.getDouble(index)).validated()
            }, json.optDouble("preampDb", 0.0)).validated()
        }.getOrDefault(EqSettings())
    }
}
