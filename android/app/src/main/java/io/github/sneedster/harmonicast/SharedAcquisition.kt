package io.github.sneedster.harmonicast

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.security.MessageDigest

internal class PrefixedProfileStorage(private val base: ProfileStorage, private val prefix: String) : ProfileStorage {
    override fun read(key: String) = base.read(prefix + key)
    override fun write(values: Map<String, String>) = base.write(values.mapKeys { prefix + it.key })
}

internal fun sharedSourceIdentity(source: PersonalPlexSource): String = MessageDigest.getInstance("SHA-256")
    .digest("${source.accountToken}|${source.machineIdentifier}|${source.libraryKey}".toByteArray())
    .joinToString("") { "%02x".format(it.toInt() and 255) }

/** Short process-local lease for presentation only. Submission always performs a fresh read. */
internal object SharedAcquisitionAccess {
    @Volatile private var source: PersonalPlexSource? = null
    @Volatile private var expires = 0L
    @Volatile var rooms = false; private set
    fun available(candidate: PersonalPlexSource?) = candidate != null && candidate == source && System.currentTimeMillis() < expires
    fun verified(candidate: PersonalPlexSource, allowRooms: Boolean) { rooms = allowRooms; source = candidate; expires = System.currentTimeMillis() + 60_000 }
    fun clear() { expires = 0; source = null; rooms = false }
}

internal data class SharedAcquisitionState(val available: Boolean = false, val checking: Boolean = false,
    val message: String = "Checking for access shared by the Plex owner…", val optedOut: Boolean = false,
    val username: String = "", val url: String = "", val rooms: Boolean = false)

internal class SharedAcquisition(private val storage: ProfileStorage, cipher: SecretCipher,
    private val source: () -> PersonalPlexSource?,
    private val plexHttp: PlexHttp = OkHttpPlexHttp(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build(), 1024 * 1024),
    serviceHttp: AcquisitionHttp = AcquisitionNetwork(), private val onUnavailable: () -> Unit = {},
    readSpacingMillis: Long = 5_000) {
    private val privateStorage = PrefixedProfileStorage(storage, "sharedAcquisition.")
    @Volatile private var bound: PersonalPlexSource? = source()?.takeIf { privateStorage.read("binding") == sharedSourceIdentity(it) }
    @Volatile private var epoch = 0
    private val mutex = Mutex()
    private val plex = LocalPlexClient(storage, plexHttp)
    val state = MutableStateFlow(SharedAcquisitionState())
    val account = MusicGrabberAccount(privateStorage, cipher, serviceHttp, readSpacingMillis = readSpacingMillis, restricted = true,
        contextValid = { bound != null && source() == bound && !NearbyGuestParticipation.active && bound?.canWriteToPlex == false })
    private fun current(expected: PersonalPlexSource, generation: Int) {
        require(epoch == generation && source() == expected && !NearbyGuestParticipation.active) { "Plex source changed" }
    }
    private fun unavailable(message: String, optedOut: Boolean = false) {
        SharedAcquisitionAccess.clear(); onUnavailable()
        state.value = SharedAcquisitionState(message = message, optedOut = optedOut)
    }
    /** Called synchronously on sign-out/source replacement; invalidates in-flight work immediately. */
    fun invalidate() {
        val old = account.connection()
        epoch++; bound = null
        privateStorage.write(mapOf("acquisition.credentials" to "", "binding" to "", "library" to "", "album" to ""))
        unavailable("Plex source changed. Refresh shared access.")
        if (old != null) CoroutineScope(Dispatchers.IO).launch { account.revoke(old) }
    }
    suspend fun optOut() = mutex.withLock {
        source()?.let { storage.write(mapOf("sharedAcquisition.optOut.${sharedSourceIdentity(it)}" to "true")) }
        unavailable("Shared acquisition is disconnected on this device", true)
        account.disconnect()
    }
    suspend fun reconnect(): Boolean {
        source()?.let { storage.write(mapOf("sharedAcquisition.optOut.${sharedSourceIdentity(it)}" to "false")) }
        return refresh(true)
    }
    suspend fun refresh(force: Boolean = false): Boolean = mutex.withLock {
        val selected = source()
        if (selected == null || selected.canWriteToPlex || NearbyGuestParticipation.active) {
            unavailable("Shared acquisition is unavailable in this mode")
            if (selected != bound) { account.disconnect(); bound = null }
            return@withLock false
        }
        val generation = epoch
        val binding = sharedSourceIdentity(selected)
        if (bound != selected) {
            SharedAcquisitionAccess.clear(); account.disconnect(); current(selected, generation)
            bound = selected
            privateStorage.write(mapOf("binding" to binding, "library" to "", "album" to "", "configurationId" to "", "revision" to ""))
        }
        if (storage.read("sharedAcquisition.optOut.$binding") == "true") {
            unavailable("Shared acquisition is disconnected on this device", true); return@withLock false
        }
        if (!force && SharedAcquisitionAccess.available(selected) && state.value.available) return@withLock true
        state.value = state.value.copy(checking = true, message = "Checking shared Plex access…")
        try {
            withTimeout(45_000) {
                require(plex.canAccessMusicLibrary(selected)) { "Plex music access was removed" }
                current(selected, generation)
                val grant = readGrant(selected)
                current(selected, generation)
                val record = grant.third
                val previousId = privateStorage.read("configurationId")
                val previousRevision = privateStorage.read("revision")?.toIntOrNull() ?: 0
                if (previousId == record.id) require(record.revision >= previousRevision) { "Shared configuration revision moved backwards" }
                if (!record.enabled) {
                    privateStorage.write(mapOf("configurationId" to record.id, "revision" to record.revision.toString()))
                    account.disconnect(); unavailable("The Plex owner has disabled shared acquisition"); return@withTimeout false
                }
                val scope = "$binding|${record.id}"
                val old = account.connection()
                if (old == null || old.url != record.url || old.accountId != record.accountId || old.password != record.password || old.scopeId != scope) {
                    val candidate = account.validate(AcquisitionLogin(record.url, record.username, record.password)).copy(scopeId = scope)
                    try {
                        current(selected, generation)
                        require(candidate.accountId == record.accountId) { "Shared MusicGrabber account identity changed" }
                        account.save(candidate)
                    } catch (e: Exception) { account.revoke(candidate); throw e }
                } else if (!account.check(true)) throw AcquisitionFailure(503, "Shared MusicGrabber connection is unavailable")
                current(selected, generation)
                privateStorage.write(mapOf("library" to grant.first, "album" to grant.second,
                    "configurationId" to record.id, "revision" to record.revision.toString()))
                storage.write(mapOf(SharedPlexSetup.libraryStorageKey(selected.machineIdentifier) to grant.first))
                SharedAcquisitionAccess.verified(selected, record.rooms)
                if (!record.rooms) onUnavailable()
                state.value = SharedAcquisitionState(true, message = "Connected through ${selected.serverName}",
                    username = record.username, url = record.url, rooms = record.rooms)
                true
            }
        } catch (e: CancellationException) {
            unavailable("Shared access check interrupted. Refresh to try again."); throw e
        } catch (e: Exception) {
            if (epoch == generation) {
                val removed = e is PlexRequestFailure && e.status in setOf(401, 403, 404) || e is IllegalArgumentException
                if (removed) account.disconnect()
                unavailable(if (removed) "Shared access is missing, disabled, or invalid. Ask the Plex owner to check the Harmonicast library."
                    else "Shared access could not be verified. Check Plex and MusicGrabber connectivity, then refresh.")
            }
            false
        }
    }

    private suspend fun readGrant(selected: PersonalPlexSource): Triple<String, String, SharedAcquisitionRecord> {
        fun rows(body: JSONObject, name: String): List<JSONObject> {
            val a = body.optJSONArray(name) ?: if (body.optInt("size", -1) == 0) org.json.JSONArray() else error("Incomplete Plex response")
            require(a.length() <= 100)
            return List(a.length()) { a.getJSONObject(it) }
        }
        suspend fun get(path: String) = plex.serverContainer(selected.baseUrl, selected.token, path)
        val sections = rows(get("/library/sections"), "Directory")
        val known = privateStorage.read("library").orEmpty()
        val libraries = sections.filter { it.optString("title") == "Harmonicast" || it.optString("key") == known }
        require(libraries.size == 1) { "Shared configuration library is missing or ambiguous" }
        val library = libraries.single()
        val key = library.getString("key")
        require(key.matches(Regex("[0-9]+")) && key != selected.libraryKey && library.optString("type") == "artist")
        val page = get("/library/sections/$key/all?type=9&X-Plex-Container-Start=0&X-Plex-Container-Size=50")
        val albums = rows(page, "Metadata")
        require(albums.size == 1 && page.optInt("size", albums.size) == 1 && page.optInt("totalSize", albums.size) == 1 && page.optInt("offset", 0) == 0)
        val album = albums.single().getString("ratingKey")
        require(album.matches(Regex("[0-9]+")))
        val item = rows(get("/library/metadata/$album"), "Metadata").single()
        require(item.optString("ratingKey") == album && item.optString("librarySectionID") == key && item.optString("type") == "album")
        val fields = item.optJSONArray("Field") ?: error("Missing lock state")
        require((0 until fields.length()).any { fields.getJSONObject(it).let { f -> f.optString("name") == "summary" && (f.opt("locked") == 1 || f.opt("locked") == true) } })
        return Triple(key, album, SharedAcquisitionRecord.parse(item.getString("summary"), selected))
    }
}
