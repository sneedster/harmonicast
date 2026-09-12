package io.github.sneedster.harmonicast

import org.junit.Assert.*
import org.junit.Test

class PlexAccessPolicyTest {
    private val shared = PersonalPlexSource("token", "http://plex", "server", "Server", "1", "Music", canWriteToPlex = false)

    @Test fun sharedCanHostAndOfferWithoutWriteOrAcquisitionAuthority() {
        val policy = PlexAccessPolicy.forSource(shared, false)
        assertTrue(policy.canHostRoom)
        assertTrue(policy.canOfferPlayback)
        assertFalse(policy.canWriteToPlex)
        assertFalse(policy.canManageAcquisition)
        assertFalse(policy.canSubmitAcquisition)
    }

    @Test fun ownerRetainsPersonalCapabilities() {
        val policy = PlexAccessPolicy.forSource(shared.copy(canWriteToPlex = true), false)
        assertTrue(policy.canHostRoom)
        assertTrue(policy.canOfferPlayback)
        assertTrue(policy.canWriteToPlex)
        assertTrue(policy.canManageAcquisition)
        assertTrue(policy.canSubmitAcquisition)
    }

    @Test fun joinedGuestsCanOfferSavedPersonalDeviceButCannotHostOrAcquire() {
        for (source in listOf(shared, shared.copy(canWriteToPlex = true))) {
            val policy = PlexAccessPolicy.forSource(source, true)
            assertTrue(policy.canOfferPlayback)
            assertFalse(policy.canHostRoom)
            assertFalse(policy.canWriteToPlex)
            assertFalse(policy.canManageAcquisition)
            assertFalse(policy.canSubmitAcquisition)
        }
    }

    @Test fun missingOrIncompleteSourcesFailClosed() {
        for (source in listOf(null, shared.copy(token = ""), shared.copy(machineIdentifier = ""),
            shared.copy(libraryKey = "../2"), shared.copy(baseUrl = "file:///tmp/music"), shared.copy(baseUrl = "https://"))) {
            for (guest in listOf(false, true)) {
                val policy = PlexAccessPolicy.forSource(source, guest)
                assertEquals(PlexAccessPolicy(false, false, false, false, false), policy)
            }
        }
    }

    @Test fun overlappingClientsCannotClearEachOthersGuestParticipation() {
        val first = Any()
        val second = Any()
        try {
            NearbyGuestParticipation.update(first, true)
            NearbyGuestParticipation.update(second, true)
            NearbyGuestParticipation.update(first, false)
            assertFalse(PlexAccessPolicy.forSource(shared).canHostRoom)
            NearbyGuestParticipation.update(second, false)
            assertTrue(PlexAccessPolicy.forSource(shared).canHostRoom)
        } finally {
            NearbyGuestParticipation.update(first, false)
            NearbyGuestParticipation.update(second, false)
        }
    }
}
