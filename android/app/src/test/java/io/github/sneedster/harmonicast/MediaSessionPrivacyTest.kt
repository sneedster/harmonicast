package io.github.sneedster.harmonicast

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import android.os.Process
import androidx.media3.common.MediaItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class MediaSessionPrivacyTest {
    @Test fun foreignIdentityCannotAuthorizeAndSerializedItemsContainNoPlexCredentials() {
        val context: Context = RuntimeEnvironment.getApplication()
        val pm = shadowOf(context.packageManager)
        val foreignUid = Process.myUid() + 100
        pm.installPackage(PackageInfo().apply {
            packageName = "org.example.foreign"
            applicationInfo = ApplicationInfo().apply { packageName = "org.example.foreign"; uid = foreignUid }
        })
        assertFalse(MediaControllerAccess.allowed(context, "org.example.foreign", foreignUid))
        assertFalse(MediaControllerAccess.allowed(context, context.packageName, foreignUid))
        assertFalse(MediaControllerAccess.allowed(context, MediaControllerAccess.ANDROID_AUTO, foreignUid))
        assertFalse(MediaControllerAccess.allowed(context, "com.android.systemui", foreignUid))
        assertFalse(MediaControllerAccess.allowed(context, context.packageName, -1))
        assertTrue(MediaControllerAccess.allowed(context, context.packageName, Process.myUid()))

        val secret = "regression-private-credential"
        val stream = "https://plex.example/track?X-Plex-Token=$secret"
        val artwork = "https://plex.example/thumb?X-Plex-Token=$secret"
        val song = Song("plex:machine:42", "Track", "Artist", "Album", coverArt = "plex:machine:42", rating = 7.0)
        val item = SessionMediaItems.track(context, song, stream, artwork)
        assertEquals(stream, item.localConfiguration!!.uri.toString()) // Playback still authenticates locally.
        assertNull(item.requestMetadata.mediaUri)
        assertEquals("content", item.mediaMetadata.artworkUri!!.scheme)
        assertEquals("${context.packageName}.media-artwork", item.mediaMetadata.artworkUri!!.authority)
        assertEquals("Track", item.mediaMetadata.title)
        assertEquals("Artist", item.mediaMetadata.artist)
        fun inspect(value: Any?) {
            when (value) {
                is Bundle -> value.keySet().forEach { key -> inspect(key); inspect(value.get(key)) }
                null -> Unit
                else -> {
                    assertFalse(value.toString().contains("X-Plex-Token", ignoreCase = true))
                    assertFalse(value.toString().contains(secret))
                }
            }
        }
        // This is the exact Media3 serialization used for library results and timeline/current items.
        inspect(item.toBundle())
        val remote = MediaItem.fromBundle(item.toBundle())
        assertNull(remote.localConfiguration)
        assertNull(remote.requestMetadata.mediaUri)
        assertEquals(item.mediaMetadata.artworkUri, remote.mediaMetadata.artworkUri)
        // Also guard values copied into text and extras, independently of connection policy.
        val poisoned = SessionMediaItems.track(context,
            song.copy(title = artwork, artist = artwork, album = artwork, coverArt = artwork), stream, artwork)
        inspect(poisoned.toBundle())
    }
}
