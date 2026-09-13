package io.github.sneedster.harmonicast

import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

internal data class SharedPlexPreparation(
    val stage: Stage,
    val libraryName: String = "Harmonicast",
    val libraryKey: String = "",
    val albumKey: String = "",
    val albumName: String = "",
    val folder: String = "",
    internal val summary: String = "",
) {
    enum class Stage { FOLDER, REVIEW, SCANNING, READY, PUBLISHED }
    // Do not include metadata in logs or Compose saved state.
    override fun toString() = "SharedPlexPreparation($stage)"
}

internal class SharedPlexSetupProblem(message: String) : IllegalStateException(message)

/** Owner preparation only: publishes a disabled placeholder, never MusicGrabber credentials. */
internal class SharedPlexSetup(
    private val storage: ProfileStorage,
    private val currentSource: () -> PersonalPlexSource?,
    private val http: PlexHttp = OkHttpPlexHttp(
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build(), 1024 * 1024),
    private val guestActive: () -> Boolean = { NearbyGuestParticipation.active },
) {
    private fun check(value: Boolean, message: () -> String) {
        if (!value) throw SharedPlexSetupProblem(message())
    }
    private val mutex get() = operations
    private val plex = LocalPlexClient(storage, http)

    companion object {
        private val operations = Mutex()
        fun libraryStorageKey(machine: String) = "sharedPlex.$machine.library"
        private fun id(value: String): String {
            require(value.matches(Regex("[0-9]+"))) { "Plex returned an invalid item identity." }
            return value
        }
        internal fun placeholder(source: PersonalPlexSource, configurationId: String): JSONObject = JSONObject()
            .put("type", "harmonicast.acquisition").put("version", 1)
            .put("configurationId", configurationId).put("revision", 1)
            .put("plexServerId", source.machineIdentifier).put("musicLibraryId", source.libraryKey)
            .put("allowAcquisition", false).put("allowRoomAcquisition", false)
            .put("musicGrabber", JSONObject().put("url", "https://harmonicast-sharing-proof.invalid")
                .put("username", "DUMMY-NOT-A-REAL-ACCOUNT").put("password", "DUMMY-NOT-A-REAL-PASSWORD"))

        private fun sameRecord(left: JSONObject, right: JSONObject): Boolean {
            val keys = left.keys().asSequence().toSet()
            if (keys != right.keys().asSequence().toSet()) return false
            return keys.all { key ->
                val a = left.get(key); val b = right.get(key)
                if (a is JSONObject && b is JSONObject) sameRecord(a, b)
                else a.javaClass == b.javaClass && a == b
            }
        }

        internal fun isPlaceholder(summary: String, source: PersonalPlexSource): Boolean = try {
            // Android's JSONObject serializes URL slashes as \/. This optional
            // JSON escape must be accepted on readback of our own saved record.
            val normalized = summary.replace("\\/", "/")
            require(summary.toByteArray().size <= 16 * 1024 && '\\' !in normalized)
            // No other escapes are needed. Reject duplicate fields even on
            // Android JSONObject implementations that otherwise accept last-write-wins.
            for (key in listOf("type", "version", "configurationId", "revision", "plexServerId", "musicLibraryId",
                    "allowAcquisition", "allowRoomAcquisition", "musicGrabber", "url", "username", "password")) {
                require(Regex("\"$key\"\\s*:").findAll(normalized).count() == 1)
            }
            val json = JSONObject(normalized)
            val uuid = UUID.fromString(json.getString("configurationId")).toString()
            sameRecord(json, placeholder(source, uuid))
        } catch (_: Exception) { false }
    }

    private fun checkSource(source: PersonalPlexSource) {
        check(currentSource() == source && !guestActive() && source.canWriteToPlex) {
            "Setup stopped because your Plex account, library, or room changed. Start setup again."
        }
        id(source.libraryKey)
        val uri = URI(source.baseUrl)
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path in listOf("", "/")) {
            "Reconnect to a valid Plex server before setting up sharing."
        }
    }

    private suspend fun owner(source: PersonalPlexSource) {
        currentCoroutineContext().ensureActive()
        checkSource(source)
        val matches = plex.accessibleServers(source.accountToken).filter { it.machineIdentifier == source.machineIdentifier }
        check(matches.size == 1 && matches.single().owned && matches.single().connections.any {
            it.uri.trimEnd('/') == source.baseUrl.trimEnd('/')
        }) { "Sign in as this Plex server's owner and reconnect to the server." }
        checkSource(source)
        check(plex.canAccessMusicLibrary(source)) { "The selected music library is no longer available." }
        checkSource(source)
    }

    private suspend fun container(source: PersonalPlexSource, path: String): JSONObject {
        currentCoroutineContext().ensureActive()
        checkSource(source)
        return plex.serverContainer(source.baseUrl, source.token, path).also { checkSource(source) }
    }

    private suspend fun mutate(source: PersonalPlexSource, path: String, method: String, form: Map<String, String> = emptyMap(),
        beforeWrite: suspend () -> Unit = {}) {
        // Revalidate live ownership immediately before each operation with side effects.
        owner(source)
        beforeWrite()
        checkSource(source)
        // PMS metadata edits consume query parameters, not a form-encoded PUT
        // body. Never log the target: live publication contains delegated secrets.
        val queryEdit = method == "PUT" && form.isNotEmpty()
        val target = source.baseUrl.trimEnd('/') + path + if (queryEdit)
            "?" + form.entries.joinToString("&") { (key, value) ->
                java.net.URLEncoder.encode(key, "UTF-8") + "=" + java.net.URLEncoder.encode(value, "UTF-8")
            } else ""
        http.request(target, method,
            mapOf("Accept" to "application/json", "X-Plex-Token" to source.token,
                "X-Plex-Product" to "Harmonicast", "X-Plex-Client-Identifier" to plex.clientIdentifier),
            if (queryEdit) emptyMap() else form)
        currentCoroutineContext().ensureActive()
        checkSource(source)
    }

    private fun rows(body: JSONObject, field: String): List<JSONObject> {
        val raw = body.optJSONArray(field) ?: if (body.optInt("size", -1) == 0) JSONArray()
            else error("Plex returned incomplete setup information. Try again.")
        check(raw.length() <= 100) { "This library is too large for a dedicated setup library." }
        return List(raw.length()) { raw.getJSONObject(it) }
    }

    private suspend fun sections(source: PersonalPlexSource) = rows(container(source, "/library/sections"), "Directory")
    private fun locations(section: JSONObject): List<String> {
        val a = section.optJSONArray("Location") ?: return emptyList()
        return List(a.length()) { a.getJSONObject(it).getString("path").trimEnd('/', '\\') }
    }
    private fun overlaps(left: String, right: String): Boolean {
        fun normalize(value: String): String {
            val path = value.replace('\\', '/').trimEnd('/')
            return if (Regex("[A-Za-z]:.*").matches(path) || path.startsWith("//")) path.lowercase() else path
        }
        val a = normalize(left); val b = normalize(right)
        return a == b || a.startsWith("$b/") || b.startsWith("$a/")
    }
    private fun folder(value: String): String {
        val path = value.trim().trimEnd('/', '\\')
        require(path.length in 2..1024 && path.none { it.code < 32 } &&
            (path.startsWith('/') || Regex("[A-Za-z]:[\\\\/].+").matches(path) || path.startsWith("\\\\")) &&
            path.split('/', '\\').none { it == ".." }) { "Enter the full folder path as Plex sees it on your server." }
        return path
    }

    private suspend fun inspectLibrary(source: PersonalPlexSource, section: JSONObject, requestedFolder: String): SharedPlexPreparation {
        val key = id(section.getString("key"))
        check(key != source.libraryKey && section.opt("type") in listOf("artist", 8)) {
            "Choose a separate Music library for sharing setup."
        }
        val actualFolder = locations(section).singleOrNull().orEmpty()
        val ordinary = sections(source).single { it.optString("key") == source.libraryKey }
        check(actualFolder.isNotBlank() && locations(ordinary).none { overlaps(it, actualFolder) }) {
            "Keep the shared setup folder outside your ordinary music folders. Check its location in Plex."
        }
        val albums = container(source, "/library/sections/$key/all?type=9&X-Plex-Container-Start=0&X-Plex-Container-Size=50")
        val items = rows(albums, "Metadata")
        check(items.size <= 1 && albums.optInt("totalSize", items.size) == items.size &&
            albums.optInt("size", items.size) == items.size && albums.optInt("offset", 0) == 0) {
            "The setup library must contain only the Harmonicast setup album. No metadata was changed."
        }
        val title = section.getString("title")
        if (items.isEmpty()) return SharedPlexPreparation(
            if (requestedFolder.isNotBlank() && requestedFolder == actualFolder) SharedPlexPreparation.Stage.SCANNING
            else SharedPlexPreparation.Stage.FOLDER, title, key, folder = actualFolder)
        val album = items.single()
        val albumKey = id(album.getString("ratingKey"))
        val detail = rows(container(source, "/library/metadata/$albumKey"), "Metadata").single()
        check(detail.optString("ratingKey") == albumKey && detail.optString("librarySectionID") == key && detail.optString("type") == "album") {
            "Plex returned an album from a different library. Start setup again."
        }
        val summary = detail.optString("summary")
        val record = runCatching { SharedAcquisitionRecord.parse(summary, source) }.getOrNull()
        val prepared = isPlaceholder(summary, source) || record != null
        check(prepared || summary.isBlank()) {
            "This album already contains other configuration or metadata. It was preserved; check the library in Plex."
        }
        // Existing bound records establish reuse. Empty albums additionally need an explicit owner folder choice.
        val setupAlbum = (detail.optString("title") == "Shared Access Setup" && detail.optString("parentTitle") == "Harmonicast") ||
            (detail.optString("title") == "Harmonicast Sharing Proof" && detail.optString("parentTitle") == "Harmonicast Test")
        if (!prepared && (requestedFolder.isBlank() || actualFolder != requestedFolder || !setupAlbum)) {
            return SharedPlexPreparation(SharedPlexPreparation.Stage.FOLDER, title, key, folder = actualFolder)
        }
        val fields = detail.optJSONArray("Field") ?: JSONArray()
        val locked = (0 until fields.length()).any { fields.getJSONObject(it).let { f ->
            f.optString("name") == "summary" && (f.opt("locked") == true || f.opt("locked") == 1)
        } }
        check(record?.enabled != true || locked) {
            "The published record's summary is unlocked. Lock the album Summary field in Plex, then check again."
        }
        if (prepared) storage.write(mapOf(libraryStorageKey(source.machineIdentifier) to key))
        return SharedPlexPreparation(if (prepared && locked) {
            if (record?.enabled == true) SharedPlexPreparation.Stage.PUBLISHED else SharedPlexPreparation.Stage.READY
        } else SharedPlexPreparation.Stage.REVIEW,
            title, key, albumKey, detail.getString("title"), actualFolder, summary)
    }

    suspend fun inspect(source: PersonalPlexSource, serverFolder: String = ""): SharedPlexPreparation = mutex.withLock {
        withTimeout(45_000) { inspectUnlocked(source, serverFolder) }
    }

    private suspend fun inspectUnlocked(source: PersonalPlexSource, serverFolder: String): SharedPlexPreparation {
        owner(source)
        val requested = serverFolder.ifBlank { storage.read("sharedPlex.${source.machineIdentifier}.folder").orEmpty() }
            .takeIf { it.isNotBlank() }?.let(::folder).orEmpty()
        val all = sections(source)
        val saved = storage.read(libraryStorageKey(source.machineIdentifier))
        val candidates = all.filter { it.optString("key") == saved || it.optString("title") == "Harmonicast" }
        check(candidates.size <= 1) { "More than one possible Harmonicast library exists. Resolve duplicates in Plex before continuing." }
        if (candidates.isNotEmpty()) {
            val result = inspectLibrary(source, candidates.single(), requested)
            val pending = "sharedPlex.${source.machineIdentifier}.creationPending"
            if (result.folder.isNotBlank() && storage.read(pending) == result.folder) {
                storage.write(mapOf(pending to "", libraryStorageKey(source.machineIdentifier) to result.libraryKey))
            }
            return result
        }
        return SharedPlexPreparation(SharedPlexPreparation.Stage.FOLDER, folder = requested)
    }

    suspend fun prepare(source: PersonalPlexSource, serverFolder: String, review: SharedPlexPreparation? = null): SharedPlexPreparation = mutex.withLock {
        withTimeout(60_000) {
            if (serverFolder.isNotBlank()) {
                checkSource(source)
                storage.write(mapOf("sharedPlex.${source.machineIdentifier}.folder" to folder(serverFolder)))
            }
            val before = inspectUnlocked(source, serverFolder)
            val prefix = "sharedPlex.${source.machineIdentifier}."
            var state = before
            if (state.stage == SharedPlexPreparation.Stage.FOLDER) {
                val requested = folder(serverFolder)
                check(state.libraryKey.isBlank()) { "Place the supplied file in the displayed Plex folder, then check again." }
                check(storage.read(prefix + "creationPending").isNullOrBlank()) {
                    "Plex has not confirmed the earlier library creation. Check again; no duplicate will be created."
                }
                val all = sections(source)
                check(all.none { section -> locations(section).any { overlaps(it, requested) } }) { "That folder is already used by a Plex library. Use a dedicated folder." }
                val music = all.single { it.optString("key") == source.libraryKey }
                val scanner = music.getString("scanner")
                val agent = music.getString("agent")
                check(scanner.isNotBlank() && agent.isNotBlank()) { "Plex did not report a usable music scanner. Check the music library in Plex." }
                storage.write(mapOf(prefix + "creationPending" to requested))
                try {
                    mutate(source, "/library/sections", "POST", mapOf("name" to "Harmonicast", "type" to "artist",
                        "location" to requested, "scanner" to scanner, "agent" to agent, "language" to "en-US",
                        "prefs[includeInGlobal]" to "0"))
                } catch (e: PlexRequestFailure) {
                    if (e.status in 400..499) storage.write(mapOf(prefix + "creationPending" to ""))
                    throw e
                }
                // Requery after creation; an uncertain outcome never triggers a second POST.
                state = inspectUnlocked(source, requested)
                check(state.libraryKey.isNotBlank()) { "Waiting for Plex to confirm the new library. Check again shortly." }
                storage.write(mapOf(prefix + "creationPending" to "", libraryStorageKey(source.machineIdentifier) to state.libraryKey))
            }
            if (state.stage == SharedPlexPreparation.Stage.SCANNING) {
                mutate(source, "/library/sections/${id(state.libraryKey)}/refresh", "GET")
                repeat(5) {
                    val scanned = inspectUnlocked(source, serverFolder)
                    if (scanned.stage != SharedPlexPreparation.Stage.SCANNING) return@withTimeout scanned
                    delay(1000)
                }
                return@withTimeout inspectUnlocked(source, serverFolder)
            }
            if (state.stage == SharedPlexPreparation.Stage.REVIEW && review != null) {
                check(state == review) { "The album changed since review. Check again before continuing." }
                val summary = state.summary.ifBlank {
                    val uuid = storage.read(prefix + "configurationId") ?: UUID.randomUUID().toString().also {
                        storage.write(mapOf(prefix + "configurationId" to it))
                    }
                    placeholder(source, uuid).toString()
                }
                mutate(source, "/library/sections/${id(state.libraryKey)}/all", "PUT", mapOf("type" to "9",
                    "id" to id(state.albumKey), "summary.value" to summary, "summary.locked" to "1")) {
                    val fresh = rows(container(source, "/library/metadata/${id(state.albumKey)}"), "Metadata").single()
                    check(fresh.optString("ratingKey") == state.albumKey && fresh.optString("librarySectionID") == state.libraryKey &&
                        fresh.optString("summary") == state.summary) { "The album changed since review. Check again before continuing." }
                }
                val verified = inspectUnlocked(source, serverFolder)
                check(verified.stage == SharedPlexPreparation.Stage.READY && sameRecord(JSONObject(verified.summary), JSONObject(summary))) {
                    "Plex has not confirmed the saved setup. Check again before sharing."
                }
                return@withTimeout verified
            }
            state
        }
    }

    suspend fun publish(source: PersonalPlexSource, review: SharedPlexPreparation, connection: AcquisitionConnection,
        rooms: Boolean, verifyAccount: suspend () -> Unit): SharedPlexPreparation = mutex.withLock {
        withTimeout(60_000) {
            owner(source)
            check(!connection.apiKeyMode && connection.role in setOf("peon", "user") && connection.password.isNotEmpty()) {
                "Test a dedicated non-admin account before publishing"
            }
            sharedAcquisitionUrl(connection.url)
            val before = inspectUnlocked(source, review.folder)
            check(before == review && before.stage in setOf(SharedPlexPreparation.Stage.READY, SharedPlexPreparation.Stage.PUBLISHED)) {
                "The setup record changed. Check again and review the new record before publishing."
            }
            verifyAccount()
            val record = SharedAcquisitionRecord.publish(SharedAcquisitionRecord.parse(before.summary, source), source, connection, rooms)
            writeRecord(source, before, record.json)
        }
    }

    suspend fun unpublish(source: PersonalPlexSource, review: SharedPlexPreparation): SharedPlexPreparation = mutex.withLock {
        withTimeout(60_000) {
            val before = inspectUnlocked(source, review.folder)
            check(before == review && before.stage == SharedPlexPreparation.Stage.PUBLISHED) { "The record changed. Check again before disabling sharing." }
            val previous = SharedAcquisitionRecord.parse(before.summary, source)
            writeRecord(source, before, placeholder(source, previous.id).put("revision", Math.addExact(previous.revision, 1)))
        }
    }

    private suspend fun writeRecord(source: PersonalPlexSource, before: SharedPlexPreparation, record: JSONObject): SharedPlexPreparation {
        mutate(source, "/library/sections/${id(before.libraryKey)}/all", "PUT", mapOf("type" to "9", "id" to id(before.albumKey),
            "summary.value" to record.toString(), "summary.locked" to "1")) {
            val fresh = rows(container(source, "/library/metadata/${id(before.albumKey)}"), "Metadata").single()
            check(fresh.optString("ratingKey") == before.albumKey && fresh.optString("librarySectionID") == before.libraryKey &&
                fresh.optString("summary") == before.summary) { "The record changed during review. Check again before publishing." }
        }
        val result = inspectUnlocked(source, before.folder)
        check(result.stage in setOf(SharedPlexPreparation.Stage.READY, SharedPlexPreparation.Stage.PUBLISHED) &&
            sameRecord(JSONObject(result.summary), record)) { "Plex has not confirmed the saved record. Check again before sharing." }
        return result
    }
}
