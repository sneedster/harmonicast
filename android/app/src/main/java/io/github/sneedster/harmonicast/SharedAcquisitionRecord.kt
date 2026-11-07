package io.github.sneedster.harmonicast

import org.json.JSONObject
import java.net.URI
import java.util.UUID

/** Strict subset used by the configuration contract: objects, strings, booleans, integers. */
internal fun strictConfigurationJson(text: String): JSONObject {
    require(text.toByteArray(Charsets.UTF_8).size <= 16 * 1024) { "Configuration is too large" }
    var at = 0
    fun space() { while (at < text.length && text[at] in " \r\n\t") at++ }
    fun string(): String {
        val start = at
        require(at < text.length && text[at++] == '"')
        while (at < text.length) {
            val c = text[at++]
            require(c.code >= 32)
            if (c == '"') return JSONObject("{\"v\":" + text.substring(start, at) + "}").getString("v")
            if (c == '\\') {
                require(at < text.length)
                val escape = text[at++]
                require(escape in "\"\\/bfnrtu")
                if (escape == 'u') { repeat(4) { require(at < text.length && text[at++] in "0123456789abcdefABCDEF") } }
            }
        }
        error("Unterminated configuration string")
    }
    fun value(depth: Int): Any {
        require(depth <= 3)
        space(); require(at < text.length)
        if (text[at] == '"') return string()
        if (text[at] == '{') {
            at++; space()
            val result = JSONObject(); val keys = mutableSetOf<String>()
            if (at < text.length && text[at] == '}') { at++; return result }
            while (true) {
                space(); val key = string(); require(keys.add(key))
                space(); require(at < text.length && text[at++] == ':')
                result.put(key, value(depth + 1)); space(); require(at < text.length)
                when (text[at++]) { '}' -> return result; ',' -> Unit; else -> error("Invalid configuration object") }
            }
        }
        for ((literal, parsed) in listOf("true" to true, "false" to false)) {
            if (text.startsWith(literal, at)) { at += literal.length; return parsed }
        }
        val start = at
        while (at < text.length && text[at] in '0'..'9') at++
        val digits = text.substring(start, at)
        require(digits.matches(Regex("0|[1-9][0-9]*")))
        return digits.toInt()
    }
    val result = value(0) as? JSONObject ?: error("Configuration must be an object")
    space(); require(at == text.length)
    return result
}

internal class SharedAcquisitionRecord private constructor(val json: JSONObject) {
    val id: String get() = json.getString("configurationId")
    val revision: Int get() = json.getInt("revision")
    val enabled: Boolean get() = json.getBoolean("allowAcquisition")
    val rooms: Boolean get() = enabled && json.getBoolean("allowRoomAcquisition")
    val usesRelay: Boolean get() = json.getInt("version") == 2
    private val service get() = json.getJSONObject(if (usesRelay) "soulSyncRelay" else "musicGrabber")
    val url: String get() = service.getString("url")
    val username: String get() = if (usesRelay) "SoulSync request key" else service.getString("username")
    val accountId: String get() = service.optString("accountId")
    private val credential: String get() = service.getString(if (usesRelay) "requestKey" else "password")
    fun login(): AcquisitionLogin = if (usesRelay)
        AcquisitionLogin(url, "", "", apiKeyMode = true, apiKey = credential, providerId = SoulSyncRelayProvider.id)
    else AcquisitionLogin(url, username, credential)

    fun matches(connection: AcquisitionConnection, scope: String): Boolean =
        connection.url == url && connection.scopeId == scope && if (usesRelay) {
            connection.providerId == SoulSyncRelayProvider.id && connection.apiKey == credential
        } else connection.providerId == MusicGrabberProvider.id && connection.accountId == accountId && connection.password == credential

    fun verify(connection: AcquisitionConnection) {
        require(if (usesRelay) connection.providerId == SoulSyncRelayProvider.id && connection.role == "track-request"
            else connection.accountId == accountId) { "The shared acquisition credential changed" }
    }
    fun encoded(): String = json.toString()
    override fun toString() = "SharedAcquisitionRecord(redacted)"

    companion object {
        fun parse(text: String, source: PersonalPlexSource): SharedAcquisitionRecord {
            val j = strictConfigurationJson(text)
            val version = j.opt("version")
            require(j.opt("type") == "harmonicast.acquisition" && version in setOf(1, 2))
            val serviceKey = if (version == 2) "soulSyncRelay" else "musicGrabber"
            require(j.keys().asSequence().toSet() == setOf("type", "version", "configurationId", "revision", "plexServerId",
                "musicLibraryId", "allowAcquisition", "allowRoomAcquisition", serviceKey))
            require(j.opt("revision") is Int && j.getInt("revision") > 0)
            require(j.opt("configurationId") is String && UUID.fromString(j.getString("configurationId")).toString() == j.getString("configurationId"))
            require(j.opt("plexServerId") == source.machineIdentifier && j.opt("musicLibraryId") == source.libraryKey)
            require(j.opt("allowAcquisition") is Boolean && j.opt("allowRoomAcquisition") is Boolean)
            if (version == 2) {
                val relay = j.getJSONObject(serviceKey)
                require(j.getBoolean("allowAcquisition") && relay.keys().asSequence().toSet() == setOf("url", "requestKey"))
                require(relay.opt("url") is String && sharedAcquisitionUrl(relay.getString("url")) == relay.getString("url"))
                require(relay.opt("requestKey") is String && SoulSyncRelayProvider.requestKey.matches(relay.getString("requestKey")))
                return SharedAcquisitionRecord(j)
            }
            val mg = j.getJSONObject("musicGrabber")
            val keys = mg.keys().asSequence().toSet()
            require(keys == setOf("url", "username", "password", "accountId") ||
                (!j.getBoolean("allowAcquisition") && keys == setOf("url", "username", "password")))
            for (key in keys) require(mg.opt(key) is String)
            if (j.getBoolean("allowAcquisition")) {
                require(mg.getString("accountId").isNotBlank())
                require(mg.getString("username").isNotBlank() && mg.getString("username").length <= 256)
                require(mg.getString("password").isNotEmpty() && mg.getString("password").length <= 4096)
                require(sharedAcquisitionUrl(mg.getString("url")) == mg.getString("url"))
            } else require(!j.getBoolean("allowRoomAcquisition"))
            return SharedAcquisitionRecord(j)
        }
        fun publish(previous: SharedAcquisitionRecord, source: PersonalPlexSource, connection: AcquisitionConnection, rooms: Boolean): SharedAcquisitionRecord {
            val j = JSONObject(previous.encoded()).put("revision", Math.addExact(previous.revision, 1))
                .put("allowAcquisition", true).put("allowRoomAcquisition", rooms)
            if (connection.providerId == SoulSyncRelayProvider.id) {
                j.remove("musicGrabber")
                j.put("version", 2).put("soulSyncRelay", JSONObject().put("url", connection.url).put("requestKey", connection.apiKey))
            } else {
                require(connection.providerId == MusicGrabberProvider.id)
                j.remove("soulSyncRelay")
                j.put("version", 1).put("musicGrabber", JSONObject().put("url", connection.url).put("username", connection.username)
                    .put("password", connection.password).put("accountId", connection.accountId))
            }
            return parse(j.toString(), source)
        }
    }
}

internal fun sharedAcquisitionUrl(raw: String): String {
    val url = acquisitionUrl(raw)
    val uri = URI(url)
    require(uri.scheme == "https" && !uri.host.endsWith(".invalid") && uri.path.split('/').none { it == ".." || it == "." }) {
        "Shared access requires a reachable HTTPS address"
    }
    return url
}
