package io.github.sneedster.harmonicast

import androidx.media3.common.MediaMetadata
import androidx.media3.common.StarRating
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutoTrackRatingTest {
    private val metadata = MediaMetadata.Builder().setTitle("Track").setArtist("Artist").setAlbumTitle("Album").build()

    @Test fun ratedTrackPreservesIdentityAndUsesPlexScale() {
        val result = AutoTrackRating.apply(metadata, 7.0)
        assertEquals("Artist · ★ 7.0 / 10", result.subtitle)
        assertEquals("Artist", result.artist)
        assertEquals("Track", result.title)
        assertEquals("Album", result.albumTitle)
        assertEquals(3.5f, (result.userRating as StarRating).starRating, 0f)
    }

    @Test fun unratedAndZeroAreDistinct() {
        val unrated = AutoTrackRating.apply(metadata, null)
        assertEquals("Artist · Unrated", unrated.subtitle)
        assertFalse(unrated.userRating!!.isRated)
        val zero = AutoTrackRating.apply(metadata, 0.0)
        assertEquals("Artist · ★ 0.0 / 10", zero.subtitle)
        assertTrue(zero.userRating!!.isRated)
    }

    @Test fun refreshReplacesRatingWithoutDuplicatingSubtitle() {
        val rated = AutoTrackRating.apply(metadata, 6.0)
        val updated = AutoTrackRating.apply(rated, 7.0)
        assertEquals("Artist · ★ 7.0 / 10", updated.subtitle)
        assertEquals(updated, AutoTrackRating.apply(updated, 7.0))
    }

    @Test fun missingArtistAndInvalidValuesRemainReadable() {
        val blank = MediaMetadata.Builder().build()
        assertEquals("Unrated", AutoTrackRating.apply(blank, Double.NaN).subtitle)
        assertEquals("★ 10.0 / 10", AutoTrackRating.apply(blank, 20.0).subtitle)
        assertEquals("★ 0.0 / 10", AutoTrackRating.apply(blank, -1.0).subtitle)
    }
}
