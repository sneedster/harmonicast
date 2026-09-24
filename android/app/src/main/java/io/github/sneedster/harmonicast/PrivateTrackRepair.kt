package io.github.sneedster.harmonicast

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.util.UUID

internal data class RepairChoice(val id: Int, val title: String, val artist: String, val source: String, val quality: String)
internal data class RepairState(
    val enabled: Boolean = false, val id: String = "", val status: String = "idle",
    val title: String = "", val artist: String = "", val message: String = "",
    val choices: List<RepairChoice> = emptyList(), val working: Boolean = false,
)

/** No public setting: only an explicitly configured server/account exposes this capability. */
internal class PrivateTrackRepair(
    private val source: PersonalPlexSource,
    private val storage: ProfileStorage,
    private val connection: String,
    private val valid: () -> Boolean,
    private val file: suspend (String) -> String,
    private val call: suspend (String, String, JSONObject?) -> JSONObject,
    private val pause: suspend () -> Unit = { delay(3_000) },
) {
    val state = MutableStateFlow(RepairState())
    private val key = "privateRepair.pending"
    private val scope = "$connection|${plexIdentity(source)}"

    suspend fun checkEnabled() {
        try {
            requireValid()
            val response = call("/api/private-repair/capability", "GET", null)
            requireValid()
            state.value = state.value.copy(enabled = response.optBoolean("enabled") &&
                response.optString("machine") == source.machineIdentifier && response.optString("library") == source.libraryKey)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { state.value = state.value.copy(enabled = false) }
    }

    private fun requireValid() {
        require(source.canWriteToPlex && valid()) { "Connection changed. Reopen the player." }
    }
    private fun pending(): String? = runCatching {
        val value = JSONObject(storage.read(key).orEmpty())
        value.optString("id").takeIf { value.optString("scope") == scope && it.isNotBlank() }
    }.getOrNull()

    suspend fun open(song: Song) {
        val saved = pending()
        if (saved != null) refresh(saved) else search(song)
    }

    suspend fun search(song: Song) {
        if (!state.value.enabled || state.value.working || state.value.status in setOf("replacing", "unknown", "unconfirmed")) return
        state.value = state.value.copy(working = true, status = "locating", title = song.title, artist = song.artist, choices = emptyList(), message = "Checking the current file…")
        var submitted = false
        try {
            requireValid()
            val path = file(song.id)
            requireValid()
            val id = UUID.randomUUID().toString()
            // Save before submitting: leaving the player or losing a response must
            // recover the same operation, never blindly issue a second replacement.
            storage.write(mapOf(key to JSONObject().put("scope", scope).put("id", id).toString()))
            state.value = state.value.copy(id = id)
            val body = JSONObject().put("request_id", id).put("machine", source.machineIdentifier)
                .put("library", source.libraryKey).put("path", path).put("artist", song.artist).put("title", song.title)
            submitted = true
            accept(call("/api/private-repair/requests", "POST", body))
            poll()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { fail(submitted) }
        finally { state.value = state.value.copy(working = false) }
    }

    suspend fun replace(choice: Int) {
        if (!state.value.enabled || state.value.working || state.value.status != "ready" || state.value.choices.none { it.id == choice }) return
        state.value = state.value.copy(working = true, message = "Starting replacement…")
        try {
            requireValid()
            accept(call("/api/private-repair/requests/${state.value.id}/replace", "POST", JSONObject().put("choice", choice)))
            poll()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { fail(true) }
        finally { state.value = state.value.copy(working = false) }
    }

    suspend fun refresh(id: String = state.value.id) {
        if (!state.value.enabled || state.value.working || id.isBlank()) return
        state.value = state.value.copy(id = id, working = true, message = "Checking request…")
        try {
            requireValid()
            accept(call("/api/private-repair/requests/$id", "GET", null))
            poll()
        } catch (e: CancellationException) { throw e }
        catch (e: AcquisitionFailure) {
            if (e.status == 404) {
                state.value = state.value.copy(status = "failed", message = "Request is unavailable. Check MusicGrabber before starting a new search.")
            } else fail(true)
        } catch (_: Exception) { fail(true) }
        finally { state.value = state.value.copy(working = false) }
    }

    private suspend fun poll() {
        while (state.value.status in setOf("searching", "replacing")) {
            pause()
            requireValid()
            accept(call("/api/private-repair/requests/${state.value.id}", "GET", null))
        }
    }
    private fun accept(value: JSONObject) {
        requireValid()
        require(value.getString("id") == state.value.id) { "Unexpected repair response" }
        val items = value.optJSONArray("choices")
        require((items?.length() ?: 0) <= 8)
        val choices = List(items?.length() ?: 0) { i ->
            val row = items!!.getJSONObject(i)
            RepairChoice(row.getInt("id"), row.optString("title"), row.optString("artist"), row.optString("source"), row.optString("quality"))
        }
        val status = value.getString("state")
        require(status in setOf("searching", "ready", "empty", "replacing", "done", "failed", "unknown"))
        state.value = state.value.copy(status = status, title = value.optString("title"), artist = value.optString("artist"),
            message = value.optString("message"), choices = choices)
    }
    private fun fail(uncertain: Boolean) {
        state.value = state.value.copy(status = if (uncertain) "unconfirmed" else "failed",
            message = if (uncertain) "Connection interrupted. Check status before trying anything else." else "Could not locate this track for repair. Check your library and connection.")
    }
}
