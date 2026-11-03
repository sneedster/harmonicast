package io.github.sneedster.harmonicast

import kotlinx.coroutines.*
import org.json.JSONObject

/** Service adapters own authentication and job semantics; the coordinator owns Plex and queue admission. */
internal interface AcquisitionProvider {
    val id: String
    val label: String
    val apiKeyOnly: Boolean get() = false
    val credentialHelp: String get() = ""
    fun headers(connection: AcquisitionConnection): Map<String, String>
    fun identity(connection: AcquisitionConnection): String
    suspend fun validate(input: AcquisitionLogin, url: String, http: AcquisitionHttp, restricted: Boolean,
        contextValid: () -> Boolean): AcquisitionConnection
    suspend fun revoke(connection: AcquisitionConnection, http: AcquisitionHttp) {}
    suspend fun check(account: AcquisitionAccount, connection: AcquisitionConnection)
    fun validateRecording(recording: CatalogEntry)
    suspend fun submit(account: AcquisitionAccount, recording: CatalogEntry, expected: String): String
    suspend fun poll(account: AcquisitionAccount, request: AcquisitionRequestState): AcquisitionProgress
}
internal data class AcquisitionProgress(val status: String = "acquiring", val message: String = "Acquiring track…",
    val checkPlex: Boolean = false)
internal object AcquisitionProviders {
    val all: List<AcquisitionProvider> = listOf(MusicGrabberProvider, SoulSyncProvider)
    fun get(id: String): AcquisitionProvider = all.singleOrNull { it.id == id }
        ?: throw IllegalArgumentException("Unknown music acquisition service. Connect again in Settings")
}
internal object MusicGrabberProvider : AcquisitionProvider {
    override val id = "musicgrabber"
    override val label = "MusicGrabber"
    override fun headers(connection: AcquisitionConnection) = with(connection) {
        if (apiKeyMode) if (apiKey.isBlank()) emptyMap() else mapOf("X-API-Key" to apiKey)
        else mapOf("Authorization" to "Bearer $token")
    }
    override fun identity(connection: AcquisitionConnection) = with(connection) {
        "$url|${if (apiKeyMode) "api-key" else accountId}" + if (scopeId.isBlank()) "" else "|$scopeId"
    }
    override suspend fun validate(input: AcquisitionLogin, url: String, http: AcquisitionHttp, restricted: Boolean,
        contextValid: () -> Boolean): AcquisitionConnection {
        val config = http.call("$url/api/config")
        if (restricted) {
            require(!input.apiKeyMode && input.remember) { "Shared access requires a dedicated username and password" }
            require(config.opt("users_exist") == true && config.optString("auth_mode") == "session") {
                "Enable MusicGrabber multi-user mode with an administrator and a dedicated non-admin account"
            }
            val denied = try { http.call("$url/api/auth/me"); false }
                catch (e: AcquisitionFailure) { if (e.status == 401) true else throw e }
            require(denied) { "MusicGrabber must require a login for shared access" }
        }
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
                if (restricted) require(user.optString("role") in setOf("peon", "user") && user.opt("id") is String && user.getString("id").isNotBlank()) {
                    "Use a verified non-admin MusicGrabber account (peon recommended)"
                }
                val result = AcquisitionConnection(url, user.getString("username"), user.getString("id"), token,
                    if (input.remember) input.password else "", role = user.optString("role"),
                    destination = config.optString("singles_path_example").ifBlank { config.optString("music_dir") })
                candidate = result
                require(!user.optBoolean("force_password_change")) { "Change your password in MusicGrabber, then connect again" }
                result
            }
            require(candidate.apiKeyMode || (candidate.token.isNotBlank() && candidate.accountId.isNotBlank())) { "MusicGrabber returned an invalid login" }
            val access = http.call("$url/api/bulk-imports?limit=1", headers = candidate.headers())
            require(access.optJSONArray("imports") != null) { "MusicGrabber acquisition API is unavailable" }
            if (restricted) {
                val denied = try { http.call("$url/api/users", headers = candidate.headers()); false }
                    catch (e: AcquisitionFailure) { if (e.status == 403) true else throw e }
                require(denied) { "This MusicGrabber account has administrator access" }
            }
            require(contextValid()) { "Plex source changed; start again" }
            return candidate
        } catch (e: Exception) { candidate?.let { revoke(it, http) }; throw e }
    }
    override suspend fun revoke(connection: AcquisitionConnection, http: AcquisitionHttp) {
        if (!connection.apiKeyMode && connection.token.isNotBlank()) withContext(NonCancellable) {
            runCatching { withTimeout(5_000) { http.call("${connection.url}/api/auth/logout", "POST", connection.headers()) } }
        }
    }
    override suspend fun check(account: AcquisitionAccount, connection: AcquisitionConnection) {
        val response = account.call("/api/bulk-imports?limit=1", expected = connection.identity)
        require(response.optJSONArray("imports") != null) { "MusicGrabber acquisition API is unavailable" }
    }
    override fun validateRecording(recording: CatalogEntry) { acquisitionLine(recording) }
    override suspend fun submit(account: AcquisitionAccount, recording: CatalogEntry, expected: String): String {
        val result = account.call("/api/bulk-import-async", "POST", JSONObject().put("songs", acquisitionLine(recording))
            .put("create_playlist", false).put("use_playlists_dir", false), expected)
        return result.optString("import_id").also {
            require(it.isNotBlank()) { "Submission could not be confirmed. Check MusicGrabber before retrying" }
        }
    }
    override suspend fun poll(account: AcquisitionAccount, request: AcquisitionRequestState): AcquisitionProgress {
        val job = account.call("/api/bulk-import/${encode(request.importId)}/status", expected = request.connection)
        val terminal = job.optString("status") in setOf("error", "failed", "cancelled")
        if (terminal || job.optBoolean("complete") && job.optInt("completed") == 0 && job.optInt("failed") > 0)
            return AcquisitionProgress("failed", "MusicGrabber could not acquire this track")
        if (job.optBoolean("complete")) {
            if (job.optInt("completed") > 0 || job.optInt("skipped") > 0 || job.optInt("dupe_skipped") > 0)
                return AcquisitionProgress("waiting_for_plex", "Waiting for Plex to index the track…", true)
            return AcquisitionProgress("failed", "MusicGrabber finished without a track")
        }
        return AcquisitionProgress()
    }
}
internal object SoulSyncProvider : AcquisitionProvider {
    override val id = "soulsync"
    override val label = "SoulSync"
    override val apiKeyOnly = true
    override val credentialHelp = "Use an API key with download access from SoulSync Settings → Advanced → SoulSync API. Shared Plex account publishing currently supports MusicGrabber."
    override fun headers(connection: AcquisitionConnection) = mapOf("Authorization" to "Bearer ${connection.apiKey}")
    override fun identity(connection: AcquisitionConnection) = "$id|${connection.url}|${connection.accountId}"
    private fun data(response: JSONObject): JSONObject {
        if (response.opt("success") != true || response.optJSONObject("data") == null)
            throw AcquisitionFailure(502, "SoulSync returned incompatible data. Check its version and API settings")
        return response.getJSONObject("data")
    }
    private fun status(response: JSONObject) {
        val value = data(response)
        require(value.optJSONObject("services") != null && value.has("uptime_seconds")) {
            "This URL did not return SoulSync status. Use the server's base URL"
        }
    }
    override suspend fun validate(input: AcquisitionLogin, url: String, http: AcquisitionHttp, restricted: Boolean,
        contextValid: () -> Boolean): AcquisitionConnection {
        require(!restricted) { "SoulSync is currently available for personal connections only" }
        val key = input.apiKey.trim()
        require(key.isNotBlank()) { "Enter a SoulSync API key with download access" }
        val accountId = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val connection = AcquisitionConnection(url, "API key", accountId, "", apiKeyMode = true, apiKey = key, providerId = id)
        status(http.call("$url/api/v1/system/status", headers = connection.headers()))
        require(contextValid()) { "Plex source changed; start again" }
        return connection
    }
    override suspend fun check(account: AcquisitionAccount, connection: AcquisitionConnection) {
        status(account.call("/api/v1/system/status", expected = connection.identity))
    }
    override fun validateRecording(recording: CatalogEntry) {
        require(recording.artist.isNotBlank() && recording.title.isNotBlank() &&
            recording.artist.length <= 500 && recording.title.length <= 500 &&
            '\n' !in recording.artist && '\r' !in recording.artist && '\n' !in recording.title && '\r' !in recording.title) {
            "This track cannot be represented by SoulSync's track input"
        }
    }
    override suspend fun submit(account: AcquisitionAccount, recording: CatalogEntry, expected: String): String {
        val body = JSONObject().put("query", "${recording.artist} - ${recording.title}")
            .put("artist", recording.artist).put("title", recording.title)
        if (recording.durationMs > 0) body.put("duration_ms", recording.durationMs)
        val result = data(account.call("/api/v1/request", "POST", body, expected))
        return result.optString("request_id").also {
            require(it.isNotBlank()) { "Submission could not be confirmed. Check SoulSync before retrying" }
        }
    }
    override suspend fun poll(account: AcquisitionAccount, request: AcquisitionRequestState): AcquisitionProgress {
        val job = try { data(account.call("/api/v1/request/${encode(request.importId)}", expected = request.connection)) }
        catch (e: AcquisitionFailure) {
            if (e.status != 404) throw e
            // Request records expire and are lost on server restart. Never replay an accepted POST.
            return AcquisitionProgress("unconfirmed", "SoulSync tracking expired. Check SoulSync before requesting again", true)
        }
        require(job.optString("request_id") == request.importId) { "SoulSync returned a different request" }
        return when (job.optString("status")) {
            "completed" -> AcquisitionProgress("waiting_for_plex", "Waiting for Plex to index the track…", true)
            "failed", "not_found", "cancelled" -> AcquisitionProgress("failed", "SoulSync could not acquire this track")
            "queued", "searching", "downloading" -> AcquisitionProgress(message = if (job.optBoolean("timed_out"))
                "SoulSync is still downloading; checking Plex…" else "Acquiring track…", checkPlex = true)
            else -> throw AcquisitionFailure(502, "SoulSync returned an unknown request status")
        }
    }
}
