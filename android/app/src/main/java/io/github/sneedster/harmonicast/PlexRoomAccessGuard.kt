package io.github.sneedster.harmonicast

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

internal enum class PlexRoomAccess { AVAILABLE, DENIED, SOURCE_CHANGED, UNAVAILABLE }

/** One room's access, bound to the exact source. A failed initial probe never opens a room. */
internal class PlexRoomAccessGuard(
    private val expected: PersonalPlexSource,
    private val current: () -> PersonalPlexSource?,
    private val probe: suspend (PersonalPlexSource) -> Boolean,
    private val joinedGuest: () -> Boolean = { NearbyGuestParticipation.active },
    private val timeoutMillis: Long = 15_000,
) {
    private val checks = Mutex()
    @Volatile private var verified = false
    @Volatile private var revoked = false
    private fun sourceMatches() = current() == expected &&
        PlexAccessPolicy.forSource(expected, joinedGuest()).canHostRoom
    fun permitsRequests() = verified && !revoked && sourceMatches()
    fun close() { revoked = true }

    suspend fun refresh(): PlexRoomAccess = checks.withLock {
        if (revoked || !sourceMatches()) {
            close()
            return@withLock PlexRoomAccess.SOURCE_CHANGED
        }
        val result = try {
            if (withTimeout(timeoutMillis) { probe(expected) }) PlexRoomAccess.AVAILABLE else PlexRoomAccess.DENIED
        } catch (_: TimeoutCancellationException) {
            coroutineContext.ensureActive()
            PlexRoomAccess.UNAVAILABLE
        } catch (e: CancellationException) {
            throw e
        } catch (e: PlexRequestFailure) {
            if (e.status in setOf(401, 403)) PlexRoomAccess.DENIED else PlexRoomAccess.UNAVAILABLE
        } catch (_: Exception) {
            PlexRoomAccess.UNAVAILABLE
        }
        // Sign-out, a source replacement, or room teardown can race a server response.
        if (revoked || !sourceMatches()) {
            close()
            return@withLock PlexRoomAccess.SOURCE_CHANGED
        }
        when (result) {
            PlexRoomAccess.AVAILABLE -> verified = true
            PlexRoomAccess.DENIED -> close()
            else -> Unit // An established room tolerates transient outages; never upgrades access.
        }
        result
    }
}
