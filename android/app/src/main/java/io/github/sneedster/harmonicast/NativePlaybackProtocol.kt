package io.github.sneedster.harmonicast

import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal object NativePlaybackProtocol {
    fun ownerEligible(context: android.content.Context): Boolean {
        val profile = Api(context.getSharedPreferences("harmonicast", android.content.Context.MODE_PRIVATE)).profile
        return ownerEligible(profile)
    }
    fun ownerEligible(profile: HomeProfileStore) = profile.mode == HomeMode.PERSONAL_PLEX && profile.personalSource?.canWriteToPlex == true
    const val PORT = 8790
    const val LEASE_MS = 5_000L
    fun secret() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
    fun matches(candidate: String?, expected: String) = expected.isNotEmpty() && candidate != null &&
        MessageDigest.isEqual(candidate.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
    fun isLocalAddress(address: String) = address.matches(Regex("[0-9.]+")) && runCatching {
        InetAddress.getByName(address).let { it is Inet4Address && it.isSiteLocalAddress }
    }.getOrDefault(false)
    fun localNetwork(context: android.content.Context): android.net.Network {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        return manager.allNetworks.firstOrNull { network ->
            val capabilities = manager.getNetworkCapabilities(network)
            capabilities != null && capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                (capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                manager.getLinkProperties(network)?.linkAddresses?.any { it.address is Inet4Address && it.address.isSiteLocalAddress } == true
        } ?: error("Connect to Wi-Fi or Ethernet first")
    }
    fun localAddress(context: android.content.Context, network: android.net.Network = localNetwork(context)): String =
        context.getSystemService(android.net.ConnectivityManager::class.java).getLinkProperties(network)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address && it.address.isSiteLocalAddress }?.address?.hostAddress
            ?: error("Local room network is unavailable")

    fun connectionError(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        return if (causes.any { it.message?.contains("EPERM") == true || it.message?.contains("Operation not permitted") == true })
            "Local Wi-Fi is blocked. Allow local network access in your VPN, or disconnect it, then try again."
        else "Could not reach the room player. Check that both devices are on the same Wi-Fi and offer playback again."
    }

    data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)
    fun read(socket: Socket): Request? {
        socket.soTimeout = 2_000
        val input = socket.getInputStream().buffered()
        fun line(): String {
            val bytes = java.io.ByteArrayOutputStream()
            while (bytes.size() <= 8_192) {
                val b = input.read()
                if (b == -1) error("Incomplete request")
                if (b == 10) return bytes.toString("UTF-8").trimEnd('\r')
                bytes.write(b)
            }
            error("Header too long")
        }
        val first = line().split(' ')
        if (first.size != 3) return null
        val headers = mutableMapOf<String, String>()
        var count = 0
        while (true) {
            val next = line()
            if (next.isEmpty()) break
            if (++count > 30) return null
            val colon = next.indexOf(':')
            if (colon <= 0) return null
            headers[next.substring(0, colon).lowercase()] = next.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length !in 0..16_384 || "transfer-encoding" in headers) return null
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(body, offset, length - offset)
            if (n < 0) return null
            offset += n
        }
        return Request(first[0], first[1], headers, body.toString(Charsets.UTF_8))
    }
    fun reply(socket: Socket, status: Int, body: String = "{}") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val out = socket.getOutputStream()
        out.write("HTTP/1.1 $status Response\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }
}

/** A single native receiver session. Browser credentials never enter this protocol. */
internal class NativePairing(private val code: String) {
    private var attempts = 0
    private var token = ""
    private var revoked = false
    @Synchronized fun pair(candidate: String): String? {
        if (revoked || attempts >= 5 || token.isNotEmpty()) return null
        attempts++
        if (!NativePlaybackProtocol.matches(candidate, code)) return null
        return NativePlaybackProtocol.secret().also { token = it }
    }
    @Synchronized fun authorized(candidate: String?) = !revoked && NativePlaybackProtocol.matches(candidate, token)
    @Synchronized fun revoke() { revoked = true; token = "" }
}
