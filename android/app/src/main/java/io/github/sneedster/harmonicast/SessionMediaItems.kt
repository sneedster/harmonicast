package io.github.sneedster.harmonicast

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/** Shared by player, queue, search and playlist results; only localConfiguration holds credentials. */
internal object SessionMediaItems {
    fun text(value: String): String = value.takeUnless { it.contains("X-Plex-Token", ignoreCase = true) }.orEmpty()

    fun id(value: String): String = value.also { require(text(it) == it) { "Invalid media ID" } }

    fun track(context: Context, song: Song, streamUrl: String, artworkUrl: String?): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setExtras(Bundle().apply { putString("harmonicast.coverArt", text(song.coverArt)) })
            .setTitle(text(song.title)).setArtist(text(song.artist)).setAlbumTitle(text(song.album))
            .setDisplayTitle(text(song.title)).setSubtitle(text(song.artist))
            .setIsPlayable(true).setIsBrowsable(false).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setArtworkUri(MediaArtworkProvider.uri(context, artworkUrl))
            .build()
        return MediaItem.Builder().setMediaId(id(song.id))
            // Media3 1.5.1 excludes localConfiguration from session/library IPC (MediaItem.toBundle).
            // Keep this private URI for ExoPlayer and NativePlaybackHost; never copy it into requestMetadata.
            .setUri(Uri.parse(streamUrl))
            .setMediaMetadata(AutoTrackRating.apply(metadata, song.rating)).build()
    }
}
