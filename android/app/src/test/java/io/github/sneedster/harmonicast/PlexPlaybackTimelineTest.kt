package io.github.sneedster.harmonicast

import org.junit.Assert.*
import org.junit.Test

class PlexPlaybackTimelineTest {
    @Test fun giantSamplingGapsAndBackwardClockDoNotBecomeListeningTime() {
        val tracker = PlexTimelineTracker()
        tracker.update("track", PlexPlaybackState.PLAYING, 0, 180_000, 10_000)
        val afterGap = tracker.update("track", PlexPlaybackState.PLAYING, 100_000, 180_000, 3_600_000).single()
        assertEquals(5_000L, afterGap.playbackTimeMillis)
        val backward = tracker.update("track", PlexPlaybackState.PAUSED, 100_000, 180_000, 3_599_000).single()
        assertEquals(5_000L, backward.playbackTimeMillis)
        val resumed = tracker.update("track", PlexPlaybackState.PLAYING, 100_000, 180_000, 3_600_000).single()
        assertEquals(5_000L, resumed.playbackTimeMillis)
        val stopped = tracker.update(null, PlexPlaybackState.STOPPED, 0, 0, Long.MAX_VALUE).single()
        assertEquals(10_000L, stopped.playbackTimeMillis)
        val negativeClock = PlexTimelineTracker()
        negativeClock.update("track", PlexPlaybackState.PLAYING, -1, 100, Long.MIN_VALUE)
        val safe = negativeClock.update("track", PlexPlaybackState.PAUSED, -1, 100, 2_000).single()
        assertEquals(2_000L, safe.playbackTimeMillis)
    }

    @Test fun unknownDurationWaitsForMetadataAndSameTrackRetainsKnownDuration() {
        val tracker = PlexTimelineTracker()
        assertTrue(tracker.update("track", PlexPlaybackState.PLAYING, -9, -1, 0).isEmpty())
        val known = tracker.update("track", PlexPlaybackState.PLAYING, -1, 100_000, 2_000).single()
        assertEquals(0L, known.positionMillis)
        assertEquals(100_000L, known.durationMillis)
        assertEquals(2_000L, known.playbackTimeMillis)
        val paused = tracker.update("track", PlexPlaybackState.PAUSED, 900_000, 0, 4_000).single()
        assertEquals(100_000L, paused.positionMillis)
        assertEquals(100_000L, paused.durationMillis)
        val newUnknown = tracker.update("other", PlexPlaybackState.PLAYING, -1, 0, 6_000)
        assertEquals(listOf(PlexPlaybackState.STOPPED), newUnknown.map { it.state })
        assertEquals(100_000L, newUnknown.single().durationMillis)
        val newPaused = tracker.update("other", PlexPlaybackState.PAUSED, -1, -1, 8_000).single()
        assertEquals(0L, newPaused.durationMillis)
        assertEquals(0L, newPaused.positionMillis)
    }

    @Test fun trackChangeStopsOldTrackBeforeNewSessionAndStopAllowsReplay() {
        val tracker = PlexTimelineTracker()
        val first = tracker.update("old", PlexPlaybackState.PLAYING, 5_000, 180_000, 0).single()
        val changed = tracker.update("new", PlexPlaybackState.PLAYING, 0, 240_000, 2_000)
        assertEquals(listOf("old", "new"), changed.map { it.id })
        assertEquals(listOf(PlexPlaybackState.STOPPED, PlexPlaybackState.PLAYING), changed.map { it.state })
        assertEquals(180_000L, changed.first().durationMillis)
        assertEquals(5_000L, changed.first().positionMillis)
        assertEquals(2_000L, changed.first().playbackTimeMillis)
        assertEquals(first.sessionIdentifier, changed.first().sessionIdentifier)
        assertNotEquals(first.sessionIdentifier, changed.last().sessionIdentifier)
        assertEquals(0L, changed.last().playbackTimeMillis)
        val stopped = tracker.update("new", PlexPlaybackState.STOPPED, 2_000, 240_000, 4_000).single()
        assertEquals(PlexPlaybackState.STOPPED, stopped.state)
        assertEquals(2_000L, stopped.playbackTimeMillis)
        assertEquals(changed.last().sessionIdentifier, stopped.sessionIdentifier)
        assertTrue(tracker.update("new", PlexPlaybackState.STOPPED, 2_000, 240_000, 5_000).isEmpty())
        val replay = tracker.update("new", PlexPlaybackState.PLAYING, 0, 240_000, 6_000).single()
        assertNotEquals(stopped.sessionIdentifier, replay.sessionIdentifier)
        assertEquals(0L, replay.playbackTimeMillis)
        val cleared = tracker.update(null, PlexPlaybackState.PLAYING, 0, 0, 8_000).single()
        assertEquals("new", cleared.id)
        assertEquals(PlexPlaybackState.STOPPED, cleared.state)
        assertEquals(2_000L, cleared.playbackTimeMillis)
        assertTrue(tracker.update(null, PlexPlaybackState.STOPPED, 0, 0, 9_000).isEmpty())
    }

    @Test fun stateTransitionsReportImmediatelyButPausedTimeAndSeeksAreNotListening() {
        val tracker = PlexTimelineTracker()
        val first = tracker.update("track", PlexPlaybackState.PLAYING, 0, 180_000, 0).single()
        tracker.update("track", PlexPlaybackState.PLAYING, 120_000, 180_000, 2_000)
        val paused = tracker.update("track", PlexPlaybackState.PAUSED, 130_000, 180_000, 4_000).single()
        assertEquals(4_000L, paused.playbackTimeMillis)
        assertEquals(PlexPlaybackState.PAUSED, paused.state)
        assertEquals(first.sessionIdentifier, paused.sessionIdentifier)
        assertTrue(tracker.update("track", PlexPlaybackState.PAUSED, 130_000, 180_000, 60_000).isEmpty())
        val resumed = tracker.update("track", PlexPlaybackState.PLAYING, 10_000, 180_000, 80_000).single()
        assertEquals(4_000L, resumed.playbackTimeMillis)
        assertEquals(first.sessionIdentifier, resumed.sessionIdentifier)
        val pausedAgain = tracker.update("track", PlexPlaybackState.PAUSED, 11_000, 180_000, 82_000).single()
        assertEquals(6_000L, pausedAgain.playbackTimeMillis)
    }

    @Test fun initialPlayingReportsImmediatelyAndRepeatsAtTenSeconds() {
        val tracker = PlexTimelineTracker()
        val first = tracker.update("track", PlexPlaybackState.PLAYING, 40_000, 180_000, 1_000).single()
        assertEquals(PlexPlaybackState.PLAYING, first.state)
        assertEquals("track", first.id)
        assertEquals(40_000L, first.positionMillis)
        assertEquals(180_000L, first.durationMillis)
        assertEquals(0L, first.playbackTimeMillis)
        assertTrue(first.sessionIdentifier.isNotBlank())
        for (now in listOf(3_000L, 5_000L, 7_000L, 9_000L, 10_999L)) {
            assertTrue(tracker.update("track", PlexPlaybackState.PLAYING, 50_000, 180_000, now).isEmpty())
        }
        val next = tracker.update("track", PlexPlaybackState.PLAYING, 50_000, 180_000, 11_000).single()
        assertEquals(10_000L, next.playbackTimeMillis)
        assertEquals(first.sessionIdentifier, next.sessionIdentifier)
    }
}
