package io.github.sneedster.harmonicast

import org.json.JSONObject
import kotlin.math.pow

/** Step indices are persisted; coefficients remain an implementation detail. */
internal data class MusicTuning(
    val completion: Int = 2,
    val skip: Int = 2,
    val repeat: Int = 2,
    val selection: Int = 2,
) {
    val completionMultiplier get() = completion.coerceIn(0, 4) * 0.5
    val skipMultiplier get() = skip.coerceIn(0, 4) * 0.5
    val repeatMultiplier get() = repeat.coerceIn(0, 4) * 0.5
    val selectionExponent get() = selection.coerceIn(0, 4) * 0.8
    val defaultRatings get() = completion == 2 && skip == 2 && repeat == 2
    fun resetRatings() = copy(completion = 2, skip = 2, repeat = 2)
}

internal class MusicTuningStore(private val storage: ProfileStorage) {
    fun read(): MusicTuning {
        val json = storage.read(KEY)?.let { runCatching { JSONObject(it) }.getOrNull() }
        fun step(name: String): Int = (json?.opt(name) as? Number)?.let {
            it.toInt().takeIf { value -> value in 0..4 && value.toDouble() == it.toDouble() }
        } ?: 2
        return MusicTuning(step("completion"), step("skip"), step("repeat"), step("selection"))
    }

    fun write(value: MusicTuning) {
        require(listOf(value.completion, value.skip, value.repeat, value.selection).all { it in 0..4 })
        storage.write(mapOf(KEY to JSONObject().put("completion", value.completion)
            .put("skip", value.skip).put("repeat", value.repeat).put("selection", value.selection).toString()))
    }

    companion object { const val KEY = "local.musicTuning" }
}

internal fun selectionWeight(rating: Double?, tuning: MusicTuning): Double =
    (rating ?: 5.0).coerceAtLeast(0.1).pow(tuning.selectionExponent)
