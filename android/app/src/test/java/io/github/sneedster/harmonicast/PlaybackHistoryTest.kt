package io.github.sneedster.harmonicast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackHistoryTest {
    @Test fun backThenForwardPreservesThePlayedOrder() {
        val history = PlaybackHistory<String>()
        listOf("A", "B", "C").forEach(history::record)
        assertEquals("B", history.previous(0))
        history.record("B") // Media3 reports the replayed item transition.
        assertEquals("A", history.previous(0))
        history.record("A")
        assertEquals("B", history.next())
        history.record("B")
        assertEquals("C", history.next())
        assertNull(history.next()) // Only now should the server dequeue run.
    }

    @Test fun restartDoesNotConsumeHistory() {
        val history = PlaybackHistory<String>()
        history.record("A")
        history.record("B")
        assertNull(history.previous(3_001))
        assertEquals("A", history.previous(0))
        assertNull(history.previous(0))
        assertEquals("B", history.next())
    }

    @Test fun previousAtThreeSecondsAndExplicitPreviousReturnThePriorTrack() {
        val history = PlaybackHistory<String>()
        listOf("A", "B", "C").forEach(history::record)
        assertEquals("B", history.previous(3_000))
        assertEquals("A", history.previous(60_000, forcePrevious = true))
    }

    @Test fun selectingAnotherSongReplacesTheForwardBranch() {
        val history = PlaybackHistory<String>()
        listOf("A", "B", "C").forEach(history::record)
        assertEquals("B", history.previous(0))
        history.record("D")
        assertNull(history.next())
        assertEquals("B", history.previous(0))
        assertEquals("D", history.next())
    }

    @Test fun historyIsBoundedAndEmptyHistoryRestarts() {
        val history = PlaybackHistory<String>(2)
        assertNull(history.previous(0))
        assertNull(history.next())
        listOf("A", "B", "C").forEach(history::record)
        assertEquals("B", history.previous(0))
        assertNull(history.previous(0))
        assertEquals("C", history.next())
    }
}
