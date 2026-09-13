package io.github.sneedster.harmonicast

/** Independent app capabilities. A configured source is not proof of current server access. */
internal data class PlexAccessPolicy(
    val canHostRoom: Boolean,
    val canOfferPlayback: Boolean,
    val canWriteToPlex: Boolean,
    val canManageAcquisition: Boolean,
    val canSubmitAcquisition: Boolean,
) {
    companion object {
        fun forSource(source: PersonalPlexSource?, joinedGuest: Boolean = NearbyGuestParticipation.active): PlexAccessPolicy {
            val usable = source != null && source.token.isNotBlank() &&
                source.machineIdentifier.isNotBlank() && source.libraryKey.matches(Regex("\\d+")) &&
                runCatching { java.net.URI(source.baseUrl).let {
                    it.scheme in listOf("http", "https") && !it.host.isNullOrBlank()
                } }.getOrDefault(false)
            val personal = usable && !joinedGuest
            val owner = personal && source?.canWriteToPlex == true
            return PlexAccessPolicy(personal, usable, owner, owner, owner || personal && SharedAcquisitionAccess.available(source))
        }
    }
}

/** Process-local guest overlay, shared by UI, service and acquisition enforcement. */
internal object NearbyGuestParticipation {
    private val clients = mutableSetOf<Any>()
    val active: Boolean @Synchronized get() = clients.isNotEmpty()
    @Synchronized fun update(client: Any, connected: Boolean) {
        if (connected) clients.add(client) else clients.remove(client)
    }
}
