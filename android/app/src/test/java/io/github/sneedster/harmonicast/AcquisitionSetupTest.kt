package io.github.sneedster.harmonicast

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.Socket
import java.net.URI

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AcquisitionSetupTest {
    private fun call(base: String, path: String, body: JSONObject = JSONObject(), token: String = "", origin: String = base, host: String = URI(base).authority): Pair<Int, String> {
        val address = URI(base)
        return Socket(address.host, address.port).use { socket ->
            socket.soTimeout = 10000
            val bytes = body.toString().toByteArray()
            socket.getOutputStream().apply {
                write(("POST $path HTTP/1.1\r\nHost: $host\r\nOrigin: $origin\r\nContent-Type: application/json\r\nAuthorization: Bearer $token\r\nContent-Length: ${bytes.size}\r\n\r\n").toByteArray())
                write(bytes); flush()
            }
            val response = socket.getInputStream().bufferedReader().readText()
            response.substringBefore("\r\n").split(' ')[1].toInt() to response.substringAfter("\r\n\r\n")
        }
    }
    @Test fun pairingStagesWithoutSavingAndCancellationRevokesOnlyStagedSession() = runBlocking {
        val f = AcquisitionFixture(); f.connect(); val original = f.account.connection()
        val gateway = AcquisitionSetupGateway(RuntimeEnvironment.getApplication(), f.account, "127.0.0.1")
        try {
            gateway.start(); val base = gateway.state.value.url
            assertEquals(403, call(base, "/pair", JSONObject().put("code", gateway.state.value.code), origin = "https://untrusted.test").first)
            assertEquals(403, call(base, "/pair", JSONObject().put("code", gateway.state.value.code), host = "untrusted.test").first)
            val pair = call(base, "/pair", JSONObject().put("code", gateway.state.value.code)); assertEquals(200, pair.first)
            val token = JSONObject(pair.second).getString("token")
            assertEquals(403, call(base, "/status", token = "guest-room-token").first)
            assertEquals(403, call(base, "/pair", JSONObject().put("code", gateway.state.value.code)).first)
            val staged = call(base, "/validate", JSONObject().put("url", "https://other").put("username", "listener").put("password", "new-secret"), token)
            assertEquals(200, staged.first); assertNotNull(gateway.state.value.staged)
            assertEquals(original, f.account.connection())
            assertFalse(staged.second.contains("new-secret")); assertFalse(staged.second.contains("token-"))
            gateway.close()
            assertEquals(original, f.account.connection())
            assertTrue(f.network.calls.any { it.first == "https://other/api/auth/logout" })
        } finally { gateway.close() }
    }
    @Test fun deviceSaveCommitsTestedConfigurationAndInvalidCredentialsKeepOldConfiguration() = runBlocking {
        val f = AcquisitionFixture(); f.connect(); val original = f.account.connection()
        val gateway = AcquisitionSetupGateway(RuntimeEnvironment.getApplication(), f.account, "127.0.0.1")
        try {
            gateway.start(); val base = gateway.state.value.url
            val token = JSONObject(call(base, "/pair", JSONObject().put("code", gateway.state.value.code)).second).getString("token")
            f.network.rejectPassword = true
            assertEquals(400, call(base, "/validate", JSONObject().put("url", "https://new").put("username", "listener").put("password", "wrong"), token).first)
            assertEquals(original, f.account.connection()); assertNull(gateway.state.value.staged)
            f.network.rejectPassword = false
            assertEquals(200, call(base, "/validate", JSONObject().put("url", "https://new").put("username", "listener").put("password", "right"), token).first)
            gateway.save()
            assertEquals("https://new", f.account.connection()!!.url)
            assertTrue(gateway.state.value.url.isEmpty()); assertNull(gateway.state.value.staged)
        } finally { gateway.close() }
    }

    @Test fun sharedWebSetupOnlyStagesRestrictedAccountAndNeverReturnsSecrets() = runBlocking {
        val network = SharedGrabberStub()
        val account = MusicGrabberAccount(AcquisitionMemory(), TestSecretCipher(), network, readSpacingMillis = 0, restricted = true)
        val gateway = AcquisitionSetupGateway(RuntimeEnvironment.getApplication(), account, "127.0.0.1", initialUrl = "https://shared.example")
        try {
            gateway.start(); val base = gateway.state.value.url
            val paired = JSONObject(call(base, "/pair", JSONObject().put("code", gateway.state.value.code)).second)
            assertEquals("https://shared.example", paired.getString("url"))
            assertEquals(setOf("token", "url"), paired.keys().asSequence().toSet())
            val token = paired.getString("token")
            val result = call(base, "/validate", JSONObject().put("url", "https://shared.example").put("username", "guests")
                .put("password", "test-password"), token)
            assertEquals(200, result.first)
            assertTrue(result.second.contains("Nothing has been published yet"))
            assertFalse(result.second.contains("test-password")); assertFalse(result.second.contains("session-"))
            assertNotNull(gateway.state.value.staged); assertNull(account.connection())
            assertEquals(404, call(base, "/publish", token = token).first)
            network.role = "admin"
            assertEquals(400, call(base, "/validate", JSONObject().put("url", "https://shared.example").put("username", "guests")
                .put("password", "test-password"), token).first)
            assertNull(account.connection())
        } finally { gateway.close() }
    }
}
