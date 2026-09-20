package io.github.sneedster.harmonicast

import androidx.media3.common.MediaMetadata
import androidx.media3.common.StarRating
import java.util.Locale

/** Auto owns rendering; keep the real artist intact and expose a readable display subtitle. */
internal object AutoTrackRating {
    fun apply(metadata: MediaMetadata, rating: Double?): MediaMetadata {
        val value = rating?.takeIf { it.isFinite() }?.coerceIn(0.0, 10.0)
        val label = value?.let { String.format(Locale.ROOT, "★ %.1f / 10", it) } ?: "Unrated"
        val subtitle = listOfNotNull(metadata.artist?.toString()?.takeIf { it.isNotBlank() }, label).joinToString(" · ")
        return metadata.buildUpon()
            .setSubtitle(subtitle)
            .setUserRating(value?.let { StarRating(5, (it / 2).toFloat()) } ?: StarRating(5))
            .build()
    }
}
