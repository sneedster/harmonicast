package io.github.sneedster.harmonicast

/** Read shared storage on each event so an existing playback service sees opt-out immediately. */
internal class AutomaticPlexRatings(private val storage: ProfileStorage) {
    var enabled: Boolean
        get() = storage.read("local.automaticPlexRatings") == "true"
        set(value) { storage.write(mapOf("local.automaticPlexRatings" to value.toString())) }
}
