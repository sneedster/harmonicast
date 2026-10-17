package io.github.sneedster.harmonicast

enum class PlexPlaybackState { PLAYING, PAUSED, STOPPED }

data class PlexPlaybackTimeline(
    val id: String,
    val state: PlexPlaybackState,
    val positionMillis: Long,
    val durationMillis: Long,
    val playbackTimeMillis: Long,
    val sessionIdentifier: String,
)

/** Monotonic caller clock; expects 2s samples. Credit at most 5s per gap so a
 * suspended/stalled service cannot turn hours without observations into listening. */
class PlexTimelineTracker {
    private var active: PlexPlaybackTimeline? = null
    private var lastSampleMillis = 0L
    private var lastReportMillis = 0L
    private var lastReportedState: PlexPlaybackState? = null

    fun update(id: String?, state: PlexPlaybackState, positionMillis: Long, durationMillis: Long,
        nowMillis: Long): List<PlexPlaybackTimeline> {
        val now = nowMillis.coerceAtLeast(0)
        val reports = mutableListOf<PlexPlaybackTimeline>()
        val old = active
        if (old != null && old.id != id) {
            reports += old.copy(state = PlexPlaybackState.STOPPED,
                playbackTimeMillis = listeningTime(old, now))
            active = null
            lastReportedState = null
        }
        if (id == null || state == PlexPlaybackState.STOPPED && active == null) return reports
        val previous = active
        val duration = durationMillis.takeIf { it > 0 } ?: previous?.durationMillis ?: 0
        val position = positionMillis.coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it }
        val next = PlexPlaybackTimeline(id, state, position, duration,
            previous?.let { listeningTime(it, now) } ?: 0,
            previous?.sessionIdentifier ?: java.util.UUID.randomUUID().toString())
        active = next
        lastSampleMillis = if (previous == null) now else maxOf(lastSampleMillis, now)
        if (state == PlexPlaybackState.STOPPED) active = null
        if (state == PlexPlaybackState.PLAYING && duration <= 0) return reports
        if (previous != null && lastReportedState == state && (state != PlexPlaybackState.PLAYING || now - lastReportMillis < 10_000)) return reports
        lastReportMillis = now
        lastReportedState = if (state == PlexPlaybackState.STOPPED) null else state
        reports += next
        return reports
    }

    private fun listeningTime(previous: PlexPlaybackTimeline, now: Long): Long {
        val elapsed = if (previous.state == PlexPlaybackState.PLAYING) (now - lastSampleMillis).coerceIn(0, 5_000) else 0
        return previous.playbackTimeMillis.coerceAtMost(Long.MAX_VALUE - elapsed) + elapsed
    }
}
