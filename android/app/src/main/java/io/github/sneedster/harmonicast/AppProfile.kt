package io.github.sneedster.harmonicast

/** Home configuration persists independently of any temporary room connection. */
enum class HomeMode { UNCONFIGURED, REMOTE_SERVER, PERSONAL_PLEX }

data class PersonalPlexSource(
    val token: String,
    val baseUrl: String,
    val machineIdentifier: String,
    val serverName: String,
    val libraryKey: String,
    val libraryName: String,
)

interface ProfileStorage {
    fun read(key: String): String?
    fun write(values: Map<String, String>)
}

class HomeProfileStore(private val storage: ProfileStorage) {
    init {
        // A single migration marker prevents stale legacy keys from restoring old credentials.
        if (storage.read("home.mode") == null) {
            val base = storage.read("base").orEmpty()
            val token = storage.read("token").orEmpty()
            save(base, token)
        }
    }
    val mode: HomeMode get() = storage.read("home.mode")?.let {
        runCatching { HomeMode.valueOf(it) }.getOrNull()
    } ?: HomeMode.UNCONFIGURED
    val base: String get() = storage.read("home.remote.base").orEmpty()
    val token: String get() = storage.read("home.remote.token").orEmpty()
    val ready: Boolean get() = mode == HomeMode.REMOTE_SERVER && base.isNotBlank() && token.isNotBlank()
    val personalSource: PersonalPlexSource? get() {
        if (mode != HomeMode.PERSONAL_PLEX) return null
        val source = PersonalPlexSource(
            token = storage.read("home.plex.token").orEmpty(),
            baseUrl = storage.read("home.plex.base").orEmpty(),
            machineIdentifier = storage.read("home.plex.machineId").orEmpty(),
            serverName = storage.read("home.plex.serverName").orEmpty(),
            libraryKey = storage.read("home.plex.libraryKey").orEmpty(),
            libraryName = storage.read("home.plex.libraryName").orEmpty(),
        )
        return source.takeIf {
            it.token.isNotBlank() && it.baseUrl.isNotBlank() && it.machineIdentifier.isNotBlank() &&
                it.libraryKey.isNotBlank() && it.serverName.isNotBlank() && it.libraryName.isNotBlank()
        }
    }
    val homeReady: Boolean get() = ready || personalSource != null

    fun setBase(value: String) {
        val normalized = value.trim().trimEnd('/')
        // An old server's bearer token must never be sent to a newly selected server.
        save(normalized, if (normalized == base) token else "")
    }
    fun setToken(value: String) = save(base, value)
    fun savePersonalSource(source: PersonalPlexSource) {
        require(source.token.isNotBlank() && source.baseUrl.isNotBlank()) { "Plex credentials are incomplete" }
        require(source.machineIdentifier.isNotBlank() && source.libraryKey.matches(Regex("\\d+"))) {
            "Plex source is incomplete"
        }
        storage.write(mapOf(
            "home.mode" to HomeMode.PERSONAL_PLEX.name,
            "home.plex.token" to source.token,
            "home.plex.base" to source.baseUrl.trimEnd('/'),
            "home.plex.machineId" to source.machineIdentifier,
            "home.plex.serverName" to source.serverName,
            "home.plex.libraryKey" to source.libraryKey,
            "home.plex.libraryName" to source.libraryName,
        ))
    }
    fun clearPersonalPlaybackState() = storage.write(mapOf(
        "local.queue" to "[]",
        "local.playback" to "",
        "local.playbackHistory" to "[]",
        "local.jukeboxMixIndex" to "",
    ))
    fun clearPersonalSource() {
        storage.write(mapOf(
            "home.mode" to HomeMode.UNCONFIGURED.name,
            "home.plex.token" to "",
            "home.plex.base" to "",
            "home.plex.machineId" to "",
            "home.plex.serverName" to "",
            "home.plex.libraryKey" to "",
            "home.plex.libraryName" to "",
            "local.queue" to "[]",
            "local.playback" to "",
            "local.playbackHistory" to "[]",
            "local.jukeboxMixIndex" to "",
        ))
    }
    private fun save(base: String, token: String) = storage.write(mapOf(
        "home.mode" to if (base.isNotBlank() && token.isNotBlank()) HomeMode.REMOTE_SERVER.name else HomeMode.UNCONFIGURED.name,
        "home.remote.base" to base,
        "home.remote.token" to token,
    ))
}

/** Ephemeral overlay only. It is never the configuration source for the Media3 service. */
class ActiveRoom(val base: String, val capability: String, val expiresAtMillis: Long) {
    fun isExpired(nowMillis: Long) = nowMillis >= expiresAtMillis
}

class AppProfile(val home: HomeProfileStore) {
    var activeRoom: ActiveRoom? = null
        private set
    fun enterRoom(room: ActiveRoom, nowMillis: Long) {
        require(!room.isExpired(nowMillis)) { "Room invitation expired" }
        activeRoom = room
    }
    fun leaveRoom() { activeRoom = null }
    fun expireRoom(nowMillis: Long) {
        if (activeRoom?.isExpired(nowMillis) == true) leaveRoom()
    }
}
