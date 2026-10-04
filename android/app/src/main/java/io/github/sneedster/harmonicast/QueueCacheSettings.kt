package io.github.sneedster.harmonicast

import android.content.Context

/** Device-specific travel buffer; mobile data allowed by default. */
internal class QueueCacheSettings(context: Context) {
    private val prefs = context.getSharedPreferences("queue_cache", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }
    var count: Int
        get() = prefs.getInt("count", 12).coerceIn(1, 50)
        set(value) { prefs.edit().putInt("count", value.coerceIn(1, 50)).apply() }
    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi_only", false)
        set(value) { prefs.edit().putBoolean("wifi_only", value).apply() }
}
