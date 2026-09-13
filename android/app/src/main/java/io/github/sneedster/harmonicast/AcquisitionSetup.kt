package io.github.sneedster.harmonicast

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors

internal data class AcquisitionSetupState(val url: String = "", val code: String = "", val message: String = "", val staged: AcquisitionConnection? = null)
/** Separate owner-only capability. No guest room token is ever accepted here. */
internal class SetupPairing(private val now: () -> Long = System::currentTimeMillis) {
    private val random = SecureRandom()
    val code = "%06d".format(random.nextInt(1_000_000))
    private val expires = now() + 300_000
    private var failures = 0
    private var revoked = false
    private var token: String? = null
    @Synchronized fun active() = !revoked && failures < 5 && now() < expires
    @Synchronized fun pair(candidate: String): String? {
        if (!active() || token != null) return null
        if (!MessageDigest.isEqual(candidate.toByteArray(), code.toByteArray())) { failures++; return null }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes)).also { token = it }
    }
    @Synchronized fun authorized(candidate: String?) = active() && candidate != null && token != null && MessageDigest.isEqual(candidate.toByteArray(), token!!.toByteArray())
    @Synchronized fun close() { revoked = true; token = null }
}
internal class AcquisitionSetupGateway(private val context: Context, private val account: MusicGrabberAccount? = null,
    private val testBindAddress: String? = null, private val pairing: SetupPairing = SetupPairing(),
    private val initialUrl: String = "") {
    val state = MutableStateFlow(AcquisitionSetupState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workers = java.util.concurrent.ThreadPoolExecutor(1, 2, 10, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue(8))
    private var socket: ServerSocket? = null
    private val mutation = kotlinx.coroutines.sync.Mutex()
    private var staged: AcquisitionConnection? = null
    private var expectedHost = ""
    fun start() {
        val address = testBindAddress ?: NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .filter { it.name.startsWith("wlan") || it.name.startsWith("eth") || it.name.startsWith("en") }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
            ?: throw IllegalArgumentException("Computer setup needs a private Wi-Fi or Ethernet connection. You can enter the login on this device instead")
        val preferred = ServerSocket()
        socket = try { preferred.apply { reuseAddress = true; bind(InetSocketAddress(address, 8789)) } }
        catch (_: java.net.BindException) { preferred.close(); ServerSocket().apply { bind(InetSocketAddress(address, 0)) } }
        expectedHost = "$address:${socket!!.localPort}"
        state.value = AcquisitionSetupState("http://$expectedHost", if (account == null) "" else pairing.code,
            "Open this address on a computer on the same private network. Keep this screen open; the page expires after five minutes.")
        scope.launch {
            while (pairing.active()) {
                val client = runCatching { socket?.accept() }.getOrNull() ?: break
                try { workers.execute { client.use { runCatching { serve(it) } } } } catch (_: java.util.concurrent.RejectedExecutionException) { client.close() }
            }
        }
        scope.launch { delay(300_000); close("Setup expired") }
    }
    private fun serve(client: Socket) {
        client.soTimeout = 10_000
        val input = client.getInputStream().buffered()
        fun line(): String {
            val bytes = java.io.ByteArrayOutputStream()
            while (true) { val b = input.read(); if (b < 0) throw java.io.EOFException(); if (b == 10) break
                require(bytes.size() < 8192); if (b != 13) bytes.write(b) }
            return bytes.toString("UTF-8")
        }
        val first = line().split(' ')
        require(first.size == 3)
        val headers = mutableMapOf<String, String>()
        repeat(50) { if (headers["__end"] == null) {
            val l = line(); if (l.isEmpty()) headers["__end"] = "true" else {
                val parts = l.split(':', limit = 2); require(parts.size == 2); headers[parts[0].lowercase()] = parts[1].trim()
            }
        } }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (headers["host"] != expectedHost || !pairing.active() || headers["__end"] == null || length !in 0..8192 || headers.containsKey("transfer-encoding")) {
            respond(client, 403, JSONObject().put("error", "Setup is unavailable").toString()); return
        }
        if (first[0] == "GET" && first[1] == "/") {
            val page = if (account == null) "shared-plex/index.html" else "setup/index.html"
            val html = context.assets.open(page).bufferedReader().use { it.readText() }
            respond(client, 200, if (account?.restricted == true) html.replace("data-purpose=\"personal\"", "data-purpose=\"shared\"") else html, true); return
        }
        // Download-only mode serves fixed, non-secret assets. It has no pairing,
        // account access, filesystem parameters, or configuration mutation routes.
        if (account == null) {
            if (first[0] == "GET" && first[1] == "/harmonicast-plex-setup.zip") {
                respondBytes(client, 200, context.assets.open("shared-plex/harmonicast-plex-setup.zip").use { it.readBytes() },
                    "application/zip", "attachment; filename=\"harmonicast-plex-setup.zip\"")
            } else respond(client, 404, "{\"error\":\"Not found\"}")
            return
        }
        if (headers["origin"] != "http://$expectedHost" || first[0] != "POST" || headers["content-type"]?.startsWith("application/json") != true) {
            respond(client, 403, "{\"error\":\"Setup request rejected\"}"); return
        }
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) { val n = input.read(bytes, read, length - read); if (n < 0) throw java.io.EOFException(); read += n }
        val body = JSONObject(String(bytes, Charsets.UTF_8))
        if (first[1] == "/pair") {
            val token = pairing.pair(body.optString("code"))
            respond(client, if (token == null) 403 else 200, if (token == null) "{\"error\":\"Incorrect code, already paired, or setup expired\"}" else JSONObject().put("token", token).put("url", initialUrl).toString())
            if (!pairing.active()) scope.launch { close("Too many incorrect pairing attempts") }
            return
        }
        if (!pairing.authorized(headers["authorization"]?.removePrefix("Bearer "))) {
            respond(client, 403, "{\"error\":\"Pair again from Harmonicast\"}"); return
        }
        if (first[1] == "/status") { respond(client, 200, JSONObject().put("message", state.value.message).put("ready", staged != null).toString()); return }
        if (first[1] != "/validate") { respond(client, 404, "{\"error\":\"Not found\"}"); return }
        runBlocking {
            mutation.lock()
            try {
                if (!pairing.active()) throw IllegalArgumentException("Setup expired")
                val candidate = account.validate(AcquisitionLogin(body.optString("url"), body.optString("username"), body.optString("password"),
                    body.optBoolean("remember", true), body.optBoolean("apiKeyMode"), body.optString("apiKey")))
                if (!pairing.active()) { account.revoke(candidate); throw IllegalArgumentException("Setup expired") }
                staged?.let { account.revoke(it) }; staged = candidate
                val completion = if (account.restricted) "Dedicated account tested. Review and publish it on the Android device. Nothing has been published yet."
                    else "Connection tested. Select Save connection on the Android device."
                state.value = state.value.copy(staged = candidate, message = completion)
                respond(client, 200, JSONObject().put("message", completion).toString())
            } catch (e: Exception) { respond(client, 400, JSONObject().put("error", safeAcquisitionError(e)).toString()) }
            finally { mutation.unlock() }
        }
    }
    private fun respond(socket: Socket, code: Int, body: String, html: Boolean = false) {
        respondBytes(socket, code, body.toByteArray(Charsets.UTF_8),
            "${if (html) "text/html" else "application/json"}; charset=utf-8")
    }
    private fun respondBytes(socket: Socket, code: Int, bytes: ByteArray, type: String, disposition: String? = null) {
        val out = socket.getOutputStream()
        out.write(("HTTP/1.1 $code Response\r\nContent-Type: $type\r\n" +
            (disposition?.let { "Content-Disposition: $it\r\n" } ?: "") +
            "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; form-action 'none'\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8))
        out.write(bytes); out.flush()
    }
    suspend fun save() {
        val account = requireNotNull(account) { "This page only provides the setup download" }
        mutation.lock()
        try {
            require(pairing.active()) { "Setup expired" }
            val candidate = staged ?: throw IllegalArgumentException("Test a connection first")
            account.save(candidate); staged = null
        } finally { mutation.unlock() }
        close("Connection saved")
    }
    suspend fun close(message: String = "Setup closed") {
        pairing.close(); runCatching { socket?.close() }; socket = null
        mutation.lock()
        try { staged?.let { account?.revoke(it) }; staged = null; state.value = AcquisitionSetupState(message = message) }
        finally { mutation.unlock() }
        workers.shutdownNow(); scope.cancel()
    }
}
