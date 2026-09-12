package io.github.sneedster.harmonicast

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.KeyStore
import java.text.Normalizer
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AcquisitionFailure(val status: Int, message: String) : Exception(message)
internal interface AcquisitionHttp {
    suspend fun call(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(), body: JSONObject? = null): JSONObject
}
internal class AcquisitionNetwork(private val readAttempts: Int = 2) : AcquisitionHttp {
    // POSTs must never be transparently replayed after an uncertain connection failure.
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
    override suspend fun call(url: String, method: String, headers: Map<String, String>, body: JSONObject?): JSONObject {
        val attempts = if (method == "GET") readAttempts.coerceIn(1, 2) else 1
        repeat(attempts) { attempt ->
            try { return callOnce(url, method, headers, body) }
            catch (e: CancellationException) { throw e }
            catch (e: java.io.IOException) {
                val transient = e !is javax.net.ssl.SSLException && generateSequence<Throwable>(e) { it.cause }.take(5).any {
                    it is java.net.SocketException || it is java.net.SocketTimeoutException || it is java.io.EOFException
                }
                if (!transient || attempt == attempts - 1) throw e
                delay(250)
            }
        }
        error("Request attempts exhausted")
    }
    private suspend fun callOnce(url: String, method: String, headers: Map<String, String>, body: JSONObject?): JSONObject = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).header("Accept", "application/json")
        headers.forEach { (k, v) -> request.header(k, v) }
        if (method != "GET") request.method(method, (body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
        val call = client.newCall(request.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: java.io.IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val result = runCatching { response.use {
                    if (!response.isSuccessful) throw AcquisitionFailure(response.code, when (response.code) {
                        401, 403 -> "Sign in again or check account access"
                        429 -> "Service is busy. Please try again shortly"
                        else -> "Music service request failed (${response.code})"
                    })
                    val content = response.body ?: throw AcquisitionFailure(502, "Music service returned no data")
                    val source = content.source()
                    source.request(1_048_577)
                    val bytes = source.buffer.readByteArray(minOf(source.buffer.size, 1_048_577))
                    if (bytes.size > 1_048_576) throw AcquisitionFailure(502, "Music service response is too large")
                    try { JSONObject(String(bytes, Charsets.UTF_8)) }
                    catch (_: Exception) { throw AcquisitionFailure(502, "Music service returned invalid data") }
                } }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        })
    }
}

internal fun acquisitionUrl(raw: String): String {
    val uri = runCatching { URI(raw.trim()) }.getOrNull()
    require(uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
        uri.userInfo == null && uri.query == null && uri.fragment == null && uri.port in -1..65535) { "Enter an HTTP or HTTPS service URL" }
    return uri.toASCIIString().trimEnd('/')
}
internal interface SecretCipher { fun seal(value: String): String; fun open(value: String): String }
internal class AndroidSecretCipher : SecretCipher {
    private fun key(): SecretKey = synchronized(this) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("harmonicast.acquisition", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("harmonicast.acquisition", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun seal(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }
    override fun open(value: String): String {
        val bytes = Base64.getDecoder().decode(value)
        return String(Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
}
internal data class AcquisitionLogin(val url: String, val username: String = "", val password: String = "", val remember: Boolean = true,
    val apiKeyMode: Boolean = false, val apiKey: String = "")
internal data class AcquisitionConnection(val url: String, val username: String, val accountId: String, val token: String,
    val password: String = "", val apiKeyMode: Boolean = false, val apiKey: String = "") {
    val identity: String get() = "$url|${if (apiKeyMode) "api-key" else accountId}"
    fun json() = JSONObject().put("url", url).put("username", username).put("accountId", accountId).put("token", token)
        .put("password", password).put("apiKeyMode", apiKeyMode).put("apiKey", apiKey)
    fun headers() = if (apiKeyMode) if (apiKey.isBlank()) emptyMap() else mapOf("X-API-Key" to apiKey) else mapOf("Authorization" to "Bearer $token")
}
internal data class AcquisitionConnectionState(val configured: Boolean = false, val available: Boolean = false, val checking: Boolean = false,
    val message: String = "Not configured", val url: String = "", val username: String = "")

internal class MusicGrabberAccount(private val storage: ProfileStorage, private val cipher: SecretCipher,
    private val http: AcquisitionHttp = AcquisitionNetwork(), private val now: () -> Long = System::currentTimeMillis,
    private val readSpacingMillis: Long = 5_000) {
    private val mutex = Mutex()
    private var blockedToken: String? = null
    private var checkedAt: Long? = null
    private val healthMutex = Mutex()
    @Volatile private var healthGeneration = 0L
    private val readMutex = Mutex()
    private var lastReadAt: Long? = null
    val state = MutableStateFlow(AcquisitionConnectionState())
    fun connection(): AcquisitionConnection? = storage.read("acquisition.credentials")?.takeIf { it.isNotBlank() }?.let {
        runCatching { val j = JSONObject(cipher.open(it)); AcquisitionConnection(j.getString("url"), j.optString("username"), j.getString("accountId"),
            j.optString("token"), j.optString("password"), j.optBoolean("apiKeyMode"), j.optString("apiKey")) }.getOrElse {
            state.value = AcquisitionConnectionState(message = "Saved login is unavailable. Connect again")
            null
        }
    }
    suspend fun validate(input: AcquisitionLogin): AcquisitionConnection {
        val url = acquisitionUrl(input.url)
        val config = http.call("$url/api/config")
        require(config.has("auth_required") || config.has("version")) { "This URL did not return MusicGrabber configuration" }
        var candidate: AcquisitionConnection? = null
        try {
            candidate = if (input.apiKeyMode) AcquisitionConnection(url, "API key", "api-key", "", apiKeyMode = true, apiKey = input.apiKey.trim())
            else {
                require(input.username.isNotBlank() && input.password.isNotEmpty()) { "Enter your MusicGrabber username and password" }
                val login = http.call("$url/api/auth/login", "POST", body = JSONObject().put("username", input.username.trim()).put("password", input.password))
                val token = login.getString("token")
                candidate = AcquisitionConnection(url, input.username.trim(), "", token)
                val user = http.call("$url/api/auth/me", headers = candidate.headers())
                val result = AcquisitionConnection(url, user.getString("username"), user.getString("id"), token, if (input.remember) input.password else "")
                candidate = result
                require(!user.optBoolean("force_password_change")) { "Change your password in MusicGrabber, then connect again" }
                result
            }
            require(candidate.apiKeyMode || (candidate.token.isNotBlank() && candidate.accountId.isNotBlank())) { "MusicGrabber returned an invalid login" }
            val access = http.call("$url/api/bulk-imports?limit=1", headers = candidate.headers())
            require(access.optJSONArray("imports") != null) { "MusicGrabber acquisition API is unavailable" }
            return candidate
        } catch (e: Exception) { candidate?.let { revoke(it) }; throw e }
    }
    suspend fun save(candidate: AcquisitionConnection) = mutex.withLock {
        val old = connection()
        storage.write(mapOf("acquisition.credentials" to cipher.seal(candidate.json().toString())))
        blockedToken = null; checkedAt = now()
        state.value = AcquisitionConnectionState(true, true, message = "Connected", url = candidate.url, username = candidate.username)
        if (old != null && old.token != candidate.token) revoke(old)
    }
    suspend fun revoke(connection: AcquisitionConnection) {
        if (!connection.apiKeyMode && connection.token.isNotBlank()) withContext(NonCancellable) {
            runCatching { withTimeout(5_000) { http.call("${connection.url}/api/auth/logout", "POST", connection.headers()) } }
        }
    }
    suspend fun disconnect() = mutex.withLock {
        val old = connection()
        storage.write(mapOf("acquisition.credentials" to "")); checkedAt = null; blockedToken = null
        state.value = AcquisitionConnectionState()
        old?.let { revoke(it) }
    }
    suspend fun check(force: Boolean = false): Boolean {
        val generation = healthGeneration
        return healthMutex.withLock {
            val c = connection() ?: return@withLock false
            if (generation != healthGeneration) return@withLock state.value.available
            val cacheMillis = if (state.value.available) 300_000L else 60_000L
            if (!force && checkedAt?.let { now() - it < cacheMillis } == true) return@withLock state.value.available
            state.value = AcquisitionConnectionState(true, checking = true, message = "Checking connection…", url = c.url, username = c.username)
            try {
                val response = call("/api/bulk-imports?limit=1", expected = c.identity)
                require(response.optJSONArray("imports") != null)
                checkedAt = now(); healthGeneration++
                state.value = AcquisitionConnectionState(true, true, message = "Connected", url = c.url, username = c.username)
                true
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                checkedAt = now(); healthGeneration++
                state.value = AcquisitionConnectionState(true, message = safeAcquisitionError(e), url = c.url, username = c.username)
                false
            }
        }
    }
    suspend fun call(path: String, method: String = "GET", body: JSONObject? = null, expected: String? = null): JSONObject {
        if (method == "GET") readMutex.withLock {
            lastReadAt?.let { delay((readSpacingMillis - (now() - it)).coerceAtLeast(0)) }
            lastReadAt = now()
        }
        var c = connection() ?: throw AcquisitionFailure(401, "Connect MusicGrabber in Settings")
        require(expected == null || c.identity == expected) { "Connection changed; request is paused" }
        if (blockedToken == c.token && !c.apiKeyMode) throw AcquisitionFailure(401, "Sign in again")
        try { return http.call(c.url + path, method, c.headers(), body) }
        catch (e: AcquisitionFailure) {
            if (e.status != 401 || c.apiKeyMode) throw e
        }
        c = mutex.withLock {
            val latest = connection() ?: throw AcquisitionFailure(401, "Sign in again")
            require(latest.identity == c.identity) { "Connection changed; request is paused" }
            if (latest.token != c.token) latest else {
                if (blockedToken == latest.token || latest.password.isBlank()) {
                    blockedToken = latest.token; throw AcquisitionFailure(401, "Sign in again")
                }
                try {
                    val refreshed = validate(AcquisitionLogin(latest.url, latest.username, latest.password))
                    require(refreshed.accountId == latest.accountId) { "MusicGrabber account changed" }
                    storage.write(mapOf("acquisition.credentials" to cipher.seal(refreshed.json().toString())))
                    refreshed
                } catch (e: Exception) {
                    if (e is AcquisitionFailure && e.status in setOf(401, 403) || e is IllegalArgumentException) blockedToken = latest.token
                    throw e
                }
            }
        }
        // A definitive HTTP 401 rejected the operation before execution; this retry is safe.
        return http.call(c.url + path, method, c.headers(), body)
    }
}
internal fun safeAcquisitionError(error: Throwable): String = when (error) {
    is org.json.JSONException -> "Music service returned incompatible data"
    is AcquisitionFailure, is IllegalArgumentException -> error.message ?: "Music acquisition failed"
    is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "Music service timed out. Try Test connection in Settings"
    is java.net.UnknownHostException -> "Music service address could not be resolved. Check its URL"
    is javax.net.ssl.SSLException -> "Music service secure connection failed. Check its HTTPS configuration"
    is java.net.ConnectException -> "Cannot connect to the music service. Check its address and that it is running"
    is java.net.SocketException, is java.io.EOFException -> "Music service connection was interrupted. Try Test connection in Settings"
    is java.io.IOException -> "Music service communication failed. Try Test connection in Settings"
    else -> "Music acquisition failed (${error.javaClass.simpleName}). Try again"
}

internal data class AcquisitionRequestState(val id: String, val recording: CatalogEntry, val participant: String,
    val connection: String, val library: String, val room: String, val status: String = "submitting", val importId: String = "",
    val message: String = "Submitting request…") {
    fun json(safe: Boolean = false) = JSONObject().put("id", id).put("recording", recording.json()).put("status", status).put("message", message).apply {
        if (!safe) { put("participant", participant); put("connection", connection); put("library", library); put("room", room); put("importId", importId) }
    }
    companion object { fun decode(j: JSONObject) = AcquisitionRequestState(j.getString("id"), CatalogEntry.decode(j.getJSONObject("recording")),
        j.optString("participant"), j.optString("connection"), j.optString("library"), j.optString("room"), j.getString("status"), j.optString("importId"), j.optString("message")) }
}
internal fun plexIdentity(source: PersonalPlexSource?) = source?.let { "${it.machineIdentifier}|${it.libraryKey}" }.orEmpty()
internal class AcquisitionCoordinator(val account: MusicGrabberAccount, val catalog: MusicBrainzCatalog, private val storage: ProfileStorage,
    private val source: () -> PersonalPlexSource?, private val core: () -> HarmonicastCore,
    private val recent: suspend () -> List<Song>, private val autoStart: Boolean = true) {
    val requests = MutableStateFlow(readRequests())
    val roomAllowed = MutableStateFlow(false)
    val roomId = MutableStateFlow("")
    private val submission = Mutex()
    private val advanceLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var polling: Job? = null
    @Synchronized fun start() { if (polling?.isActive == true) return; polling = scope.launch {
        var wait = 30_000L
        while (isActive) {
            try {
                if (roomAllowed.value && roomId.value.isNotBlank()) account.check()
                advance(); wait = 30_000L
            } catch (e: CancellationException) { throw e } catch (_: Exception) { wait = (wait * 2).coerceAtMost(300_000) }
            delay(wait)
        }
    } }
    private fun readRequests(): List<AcquisitionRequestState> = runCatching {
        val values = JSONArray(storage.read("acquisition.requests") ?: "[]")
        List(values.length()) { AcquisitionRequestState.decode(values.getJSONObject(it)).let { request ->
            if (request.status == "submitting") request.copy(status = "unconfirmed", message = "Submission could not be confirmed. Check MusicGrabber") else request
        } }
    }.getOrDefault(emptyList())
    @Synchronized private fun update(value: AcquisitionRequestState) {
        val updated = requests.value.filterNot { it.id == value.id } + value
        val recentFinished = updated.filter { it.status in setOf("failed", "fulfilled") }.takeLast(100).mapTo(mutableSetOf()) { it.id }
        val all = updated.filter { it.status !in setOf("failed", "fulfilled") || it.id in recentFinished }
        storage.write(mapOf("acquisition.requests" to JSONArray().apply { all.forEach { put(it.json()) } }.toString()))
        requests.value = all
    }
    fun dismissUnconfirmed(id: String) {
        val request = requests.value.firstOrNull { it.id == id && it.status == "unconfirmed" } ?: return
        update(request.copy(status = "failed", message = "Tracking dismissed. Check MusicGrabber before requesting this track again"))
    }
    fun pending(participant: String) = requests.value.count { it.participant == participant && it.status !in setOf("failed", "fulfilled") }
    fun visible(participant: String?, room: String? = null) = requests.value.filter { (participant == null || it.participant == participant) && (room == null || it.room == room) }.takeLast(50)
    suspend fun setRoomAllowed(value: Boolean) {
        if (value) require(source()?.canWriteToPlex == true && account.check()) { "MusicGrabber must be connected and available" }
        roomAllowed.value = value
    }
    suspend fun submit(recordingId: String, participant: String = "Owner", room: String = ""): AcquisitionRequestState =
        scope.async { submitAccepted(recordingId, participant, room) }.await()
    private suspend fun submitAccepted(recordingId: String, participant: String, room: String): AcquisitionRequestState = submission.withLock {
        require(source()?.canWriteToPlex == true) { "Acquisition requires an owner Plex library" }
        if (room.isNotBlank()) require(roomId.value == room && roomAllowed.value) { "Music acquisition is disabled in this room" }
        require(account.check()) { account.state.value.message }
        val conn = account.connection() ?: throw IllegalArgumentException("Connect MusicGrabber first")
        val lib = plexIdentity(source())
        val recording = catalog.recording(recordingId)
        val line = acquisitionLine(recording)
        require(line.length <= 200 && !line.contains('\n') && !line.contains('\r')) { "This track cannot be represented by MusicGrabber's single-track input" }
        // Duplicate taps return the accepted request rather than submitting twice.
        requests.value.lastOrNull { it.participant == participant && it.recording.id == recordingId && it.library == lib && it.connection == conn.identity && it.status !in setOf("failed", "fulfilled") }?.let { return@withLock it }
        if (room.isNotBlank()) require(roomId.value == room && roomAllowed.value) { "Music acquisition is disabled in this room" }
        val request = AcquisitionRequestState(UUID.randomUUID().toString(), recording, participant, conn.identity, lib, room)
        // Reserve under the same lock as ordinary room queue admission.
        QueueTransactions.mutex.withLock {
            if (room.isNotBlank() && core().queue.songs().count { it.isManual && it.addedByEmail == participant } + pending(participant) >= 5)
                throw AcquisitionFailure(429, "You already have 5 songs queued or being acquired")
            update(request)
        }
        var submissionStarted = false
        try {
            require(plexIdentity(source()) == lib && account.connection()?.identity == conn.identity) { "Connection changed; request is paused" }
            submissionStarted = true
            val result = account.call("/api/bulk-import-async", "POST", JSONObject().put("songs", line).put("create_playlist", false).put("use_playlists_dir", false), conn.identity)
            val importId = result.optString("import_id")
            require(importId.isNotBlank()) { "Submission could not be confirmed. Check MusicGrabber before retrying" }
            val accepted = request.copy(status = "acquiring", importId = importId, message = "Acquiring track…")
            update(accepted); if (autoStart) start(); accepted
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            val definite = !submissionStarted || e is AcquisitionFailure && e.status in setOf(400, 401, 403, 404, 422, 429) || e is IllegalArgumentException && !e.message.orEmpty().startsWith("Submission")
            val failed = request.copy(status = if (definite) "failed" else "unconfirmed", message = if (definite) safeAcquisitionError(e) else "Submission could not be confirmed. Check MusicGrabber; it will not be resubmitted automatically")
            update(failed); failed
        }
    }
    private suspend fun match(recording: CatalogEntry): Song? {
        val library = core().library
        library.searchForBrowsing(recording.title).firstOrNull { acquisitionMatches(it, recording) }?.let { return it }
        library.searchForBrowsing("${recording.artist} ${recording.title}").firstOrNull { acquisitionMatches(it, recording) }?.let { return it }
        return recent().firstOrNull { acquisitionMatches(it, recording) }
    }
    private suspend fun fulfill(request: AcquisitionRequestState, song: Song) {
        require(request.library == plexIdentity(source()) && request.connection == account.connection()?.identity && source()?.canWriteToPlex == true) { "Connection changed; request is paused" }
        core().queue.addOnce(request.id, song.copy(isManual = true, addedByEmail = request.participant))
        update(request.copy(status = "fulfilled", message = "Queued"))
    }
    suspend fun advance() = advanceLock.withLock {
        for (saved in requests.value) {
            if (saved.status !in setOf("acquiring", "waiting_for_plex", "submitting", "unconfirmed")) continue
            if (saved.library != plexIdentity(source()) || saved.connection != account.connection()?.identity || source()?.canWriteToPlex != true) {
                if (!saved.message.startsWith("Paused")) update(saved.copy(message = "Paused — reconnect the original account and owner Plex library"))
                continue
            }
            if (saved.status == "submitting") continue
            if (saved.status == "unconfirmed") continue
            var request = saved
            try {
                if (request.status == "acquiring") {
                    val job = account.call("/api/bulk-import/${encode(request.importId)}/status", expected = request.connection)
                    val terminal = job.optString("status") in setOf("error", "failed", "cancelled")
                    if (terminal || job.optBoolean("complete") && job.optInt("completed") == 0 && job.optInt("failed") > 0) {
                        update(request.copy(status = "failed", message = "MusicGrabber could not acquire this track")); continue
                    }
                    if (job.optBoolean("complete") && job.optInt("completed") == 0 && job.optInt("failed") == 0 && job.optInt("skipped") == 0 && job.optInt("dupe_skipped") == 0) {
                        update(request.copy(status = "failed", message = "MusicGrabber finished without a track")); continue
                    }
                    if (job.optBoolean("complete") && (job.optInt("completed") > 0 || job.optInt("skipped") > 0 || job.optInt("dupe_skipped") > 0)) {
                        request = request.copy(status = "waiting_for_plex", message = "Waiting for Plex to index the track…"); update(request)
                    }
                }
                if (request.status == "waiting_for_plex") {
                    val found = match(request.recording)
                    if (found != null) fulfill(request, found) else update(request.copy(message = "Waiting for Plex to index the track…"))
                } else update(request.copy(message = "Acquiring track…"))
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                val verifyingPlex = request.status == "waiting_for_plex"
                val missingImport = !verifyingPlex && e is AcquisitionFailure && e.status == 404
                val message = if (verifyingPlex) "Acquisition finished; waiting for Plex access to verify the track"
                    else if (missingImport) "Import no longer exists in MusicGrabber" else safeAcquisitionError(e)
                update(request.copy(message = message, status = if (missingImport) "failed" else request.status))
                throw e
            }
        }
    }
}
internal fun acquisitionLine(recording: CatalogEntry): String {
    require(!Regex("[-–—]").containsMatchIn(recording.artist)) { "MusicGrabber's input format cannot safely represent this artist name" }
    val line = "${recording.artist} - ${recording.title}"
    require(line.length <= 200 && '\n' !in line && '\r' !in line) { "This track exceeds MusicGrabber's single-track input limits" }
    return line
}
internal fun acquisitionMatches(song: Song, recording: CatalogEntry): Boolean {
    fun normalize(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKD).lowercase()
        .replace(Regex("\\s*[\\[(].*?(remaster(?:ed)?|mono|stereo|radio edit|single version|album version).*?[\\])]"), "")
        .replace(Regex("\\b(feat(?:uring)?|ft)\\.?\\s+"), "").replace(Regex("[^a-z0-9]+"), "")
    val a = normalize(song.artist); val b = normalize(recording.artist)
    return a.isNotBlank() && b.isNotBlank() && normalize(song.title) == normalize(recording.title) &&
        (a == b || a.contains(b) || b.contains(a)) && (song.duration == 0 || recording.durationMs == 0 || kotlin.math.abs(song.duration * 1000 - recording.durationMs) <= 30_000)
}
internal object AcquisitionRuntime {
    private var instance: AcquisitionCoordinator? = null
    @Synchronized fun get(context: Context): AcquisitionCoordinator = instance ?: run {
        val storage = SharedPreferencesProfileStorage(context.applicationContext.getSharedPreferences("harmonicast", Context.MODE_PRIVATE))
        val profile = HomeProfileStore(storage)
        val plex = LocalPlexClient(storage)
        AcquisitionCoordinator(MusicGrabberAccount(storage, AndroidSecretCipher()), MusicBrainzCatalog(), storage,
            { profile.personalSource }, { LocalHarmonicastCore(profile.personalSource, storage) },
            { profile.personalSource?.let { plex.recentTracks(it) } ?: emptyList() }).also { instance = it; it.start() }
    }
}
