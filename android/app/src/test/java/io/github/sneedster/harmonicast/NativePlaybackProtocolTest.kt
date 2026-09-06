package io.github.sneedster.harmonicast

import org.junit.Assert.*
import org.junit.Test

class NativePlaybackProtocolTest {
    @Test fun onlyPersonalOwnerProfilesAreEligible() {
        val values = mutableMapOf<String, String>()
        val profile = HomeProfileStore(object : ProfileStorage {
            override fun read(key: String) = values[key]
            override fun write(next: Map<String, String>) { values.putAll(next) }
        })
        assertFalse(NativePlaybackProtocol.ownerEligible(profile))
        profile.setBase("http://server")
        profile.setToken("remote-token")
        assertFalse(NativePlaybackProtocol.ownerEligible(profile))
        val source = PersonalPlexSource("test-token", "http://plex", "machine", "Test", "1", "Music", canWriteToPlex = false)
        profile.savePersonalSource(source)
        assertFalse(NativePlaybackProtocol.ownerEligible(profile))
        profile.savePersonalSource(source.copy(canWriteToPlex = true))
        assertTrue(NativePlaybackProtocol.ownerEligible(profile))
    }
    @Test fun roomSecretsAreOneUseAndRevocable() {
        val secret = NativePlaybackProtocol.secret()
        assertEquals(43, secret.length)
        val locked = NativePairing(secret)
        repeat(5) { assertNull(locked.pair("654321")) }
        assertNull(locked.pair(secret))
        val pairing = NativePairing(secret)
        assertFalse(pairing.authorized(null))
        assertFalse(pairing.authorized(""))
        val token = pairing.pair(secret)!!
        assertTrue(pairing.authorized(token))
        assertFalse(pairing.authorized("browser-guest-token"))
        assertNull(pairing.pair(secret))
        pairing.revoke()
        assertFalse(pairing.authorized(token))
        assertNull(pairing.pair(secret))
    }
    @Test fun blockedLocalNetworkHasActionableMessage() {
        val error = RuntimeException(java.net.SocketException("Binding socket failed: EPERM (Operation not permitted)"))
        assertTrue(NativePlaybackProtocol.connectionError(error).contains("VPN"))
        assertFalse(NativePlaybackProtocol.connectionError(RuntimeException("secret URL")).contains("secret"))
    }
    @Test fun destinationsMustBePrivateLiteralAddresses() {
        assertTrue(NativePlaybackProtocol.isLocalAddress("192.168.1.2"))
        assertTrue(NativePlaybackProtocol.isLocalAddress("10.11.12.41"))
        listOf("127.0.0.1", "8.8.8.8", "example.com", "192.168.1.2:8790", "http://192.168.1.2", "169.254.1.2").forEach {
            assertFalse(it, NativePlaybackProtocol.isLocalAddress(it))
        }
    }
}
