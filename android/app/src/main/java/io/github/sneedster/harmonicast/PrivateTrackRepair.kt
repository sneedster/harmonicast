package io.github.sneedster.harmonicast

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.security.MessageDigest

internal class PrivateRepairSwitch(private val storage: ProfileStorage) {
    private var taps = 0
    private var last = 0L
    val enabled get() = storage.read("privateRepair.enabled") == "true"
    fun tap(now: Long): Boolean? {
        if (now - last > 4_000) taps = 0
        last = now
        if (++taps < 7) return null
        taps = 0
        return (!enabled).also { storage.write(mapOf("privateRepair.enabled" to it.toString())) }
    }
}

internal data class RepairState(
    val status: String = "idle", val title: String = "", val artist: String = "",
    val message: String = "", val working: Boolean = false,
)

/** Delete through Plex, then use the ordinary upstream MusicGrabber import API. */
internal class PrivateTrackRepair(
    private val storage: ProfileStorage,
    private val scope: String,
    private val valid: () -> Boolean,
    private val checkAccount: suspend () -> Boolean,
    private val file: suspend (String) -> BadPlexFile,
    private val delete: suspend (BadPlexFile) -> Unit,
    private val deleted: suspend (BadPlexFile) -> Boolean,
    private val call: suspend (String, String, JSONObject?) -> JSONObject,
    private val pause: suspend () -> Unit = { delay(3_000) },
) {
    val state = MutableStateFlow(RepairState())
    private var saved = JSONObject()
    private var key = ""
    private var target: BadPlexFile? = null
    private var line = ""
    private fun requireValid() { require(valid()) { "Connection changed. Reopen the player." } }
    private fun save(status: String, message: String) {
        saved.put("status", status).put("message", message)
        storage.write(mapOf(key to saved.toString()))
        state.value = state.value.copy(status = status, message = message)
    }

    suspend fun open(song: Song) {
        if (state.value.working) return
        key = "privateRepair.track." + MessageDigest.getInstance("SHA-256")
            .digest("$scope|${song.id}".toByteArray()).joinToString("") { "%02x".format(it) }
        saved = runCatching { JSONObject(storage.read(key).orEmpty()) }.getOrElse { JSONObject() }
        line = ""
        target = null
        state.value = RepairState(title = song.title, artist = song.artist, working = true, message = "Checking track…")
        try {
            requireValid()
            line = acquisitionLine(CatalogEntry("replacement", song.title, song.artist))
            if (saved.has("status")) {
                target = BadPlexFile(song.id, saved.getString("path"), saved.optString("partId"), saved.optString("size"))
                val status = when (saved.getString("status")) {
                    "deleting" -> "delete_unknown"
                    "submitting" -> "request_unknown"
                    else -> saved.getString("status")
                }
                state.value = state.value.copy(status = status, message = when (status) {
                    "delete_unknown" -> "Deletion was interrupted. Check status before continuing."
                    "request_unknown" -> "The file was deleted, but the download request could not be confirmed. Check MusicGrabber before requesting it again."
                    else -> saved.optString("message")
                })
            } else {
                check(checkAccount()) { "Connect MusicGrabber first" }
                requireValid()
                target = file(song.id)
                requireValid()
                state.value = state.value.copy(status = "ready", message = "Delete this bad file permanently and ask MusicGrabber for a fresh copy.")
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { state.value = state.value.copy(status = "blocked", message = "Could not prepare replacement. Check Plex ownership, a single file for this track, and your MusicGrabber connection.") }
        finally { state.value = state.value.copy(working = false) }
    }

    suspend fun replace() {
        if (state.value.working || state.value.status != "ready") return
        state.value = state.value.copy(working = true)
        try {
            requireValid()
            check(checkAccount())
            requireValid()
            val original = target ?: error("No track selected")
            saved = JSONObject().put("path", original.path).put("partId", original.partId).put("size", original.size)
            save("deleting", "Deleting the bad file…")
            delete(original)
            requireValid()
            check(deleted(original))
            save("deleted", "Bad file deleted. Requesting a fresh copy…")
            submit()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failure() }
        finally { state.value = state.value.copy(working = false) }
    }

    suspend fun refresh() {
        if (state.value.working) return
        state.value = state.value.copy(working = true)
        try {
            requireValid()
            when (state.value.status) {
                "delete_unknown", "deleting" -> {
                    if (deleted(target ?: error("No track selected"))) {
                        save("deleted", "Bad file deleted. Requesting a fresh copy…")
                        submit()
                    } else save("delete_unknown", "Plex still shows the file. No new download has been requested; check Plex before trying again.")
                }
                "deleted", "request_failed" -> submit()
                "acquiring" -> poll()
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failure() }
        finally { state.value = state.value.copy(working = false) }
    }

    private suspend fun submit() {
        requireValid()
        check(checkAccount())
        requireValid()
        save("submitting", "Requesting a fresh copy…")
        val result = try {
            call("/api/bulk-import-async", "POST", JSONObject().put("songs", line)
                .put("create_playlist", false).put("use_playlists_dir", false))
        } catch (e: AcquisitionFailure) {
            if (e.status in setOf(400, 401, 403, 404, 422, 429)) {
                save("request_failed", "Bad file deleted. MusicGrabber rejected the request; check its connection and retry the request.")
                return
            }
            throw e
        }
        requireValid()
        val id = result.optString("import_id")
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        saved.put("importId", id)
        save("acquiring", "MusicGrabber is acquiring a fresh copy…")
        poll()
    }
    private suspend fun poll() {
        while (state.value.status == "acquiring") {
            requireValid()
            val result = call("/api/bulk-import/${saved.getString("importId")}/status", "GET", null)
            requireValid()
            when {
                result.optString("status") in setOf("error", "failed", "cancelled") ->
                    save("request_failed", "Bad file deleted. MusicGrabber could not download a replacement.")
                result.optBoolean("complete") -> {
                    when {
                        result.optInt("completed") > 0 -> save("done", "Fresh copy downloaded. It will appear when Plex finishes indexing it.")
                        result.optInt("skipped") > 0 || result.optInt("dupe_skipped") > 0 -> save("done", "MusicGrabber found an existing copy instead of downloading. Check its result in MusicGrabber.")
                        else -> save("request_failed", "Bad file deleted. MusicGrabber could not download a replacement.")
                    }
                }
            }
            if (state.value.status == "acquiring") pause()
        }
    }
    private fun failure() {
        when (state.value.status) {
            "deleting" -> save("delete_unknown", "Deletion could not be confirmed. No download was requested. Check status.")
            "submitting" -> save("request_unknown", "Bad file deleted. The download request could not be confirmed; check MusicGrabber before requesting again.")
            "deleted", "request_failed" -> save("request_failed", "Bad file deleted. Check MusicGrabber's connection, then retry the request.")
            "acquiring" -> state.value = state.value.copy(message = "Download status is unavailable. Check status again; the request will not be resent.")
            else -> state.value = state.value.copy(message = "Connection unavailable. Reopen this action after reconnecting.")
        }
    }
}
