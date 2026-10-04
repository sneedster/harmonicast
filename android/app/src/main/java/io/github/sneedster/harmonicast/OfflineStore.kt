package io.github.sneedster.harmonicast

import android.content.Context
import android.net.Uri
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/** Private complete files only; queued work contains hashes/track IDs, never access tokens. */
class OfflineStore(private val context: Context) {
    private val base = File(context.noBackupFilesDir, "offline_music").apply { mkdirs() }
    private fun directory(source: PersonalPlexSource) = File(base, scope(source)).apply { mkdirs() }
    private fun index(source: PersonalPlexSource) = File(directory(source), "tracks.json")
    private fun audio(source: PersonalPlexSource, id: String) = File(directory(source), "${digest(id)}.audio")
    internal val cacheSettings get() = QueueCacheSettings(context)
    internal fun connected(): Boolean = context.getSystemService(android.net.ConnectivityManager::class.java).activeNetwork != null
    private fun cacheIndex(source: PersonalPlexSource) = File(directory(source), "cache.json")
    private fun targetsFile(source: PersonalPlexSource) = File(directory(source), "cache_targets.json")
    private fun cacheMetadata(source: PersonalPlexSource): List<Song> = runCatching { decodeSongs(JSONArray(cacheIndex(source).takeIf { it.isFile }?.readText() ?: "[]")) }.getOrDefault(emptyList())
    fun cachedSongs(source: PersonalPlexSource): List<Song> = synchronized(lock) { cacheMetadata(source).filter { audio(source, it.id).let { file -> file.isFile && file.length() > 0 } } }
    fun availableSongs(source: PersonalPlexSource): List<Song> = synchronized(lock) { (songs(source) + cachedSongs(source)).distinctBy { it.id } }
    fun cachedBytes(source: PersonalPlexSource) = cachedSongs(source).sumOf { audio(source, it.id).length() }
    private fun targets(source: PersonalPlexSource): List<String> = runCatching { val data = JSONArray(targetsFile(source).readText()); (0 until data.length()).map { data.getString(it) } }.getOrDefault(emptyList())
    fun songs(source: PersonalPlexSource): List<Song> = synchronized(lock) {
        metadata(source).filter { audio(source, it.id).let { file -> file.exists() && file.length() > 0 } }
    }
    private fun metadata(source: PersonalPlexSource): List<Song> = runCatching {
        decodeSongs(JSONArray(index(source).takeIf { it.isFile }?.readText() ?: "[]"))
    }.getOrDefault(emptyList())
    fun song(source: PersonalPlexSource, id: String) = availableSongs(source).firstOrNull { it.id == id }
    fun uri(source: PersonalPlexSource, id: String): String? = synchronized(lock) {
        if ((metadata(source) + cacheMetadata(source)).none { it.id == id }) null
        else audio(source, id).takeIf { it.isFile && it.length() > 0 }?.let { Uri.fromFile(it).toString() }
    }
    fun bytes(source: PersonalPlexSource) = songs(source).sumOf { audio(source, it.id).length() }
    private fun generations(): JSONObject = runCatching { JSONObject(File(base, "generations.json").readText()) }.getOrDefault(JSONObject())
    private fun bump(key: String) {
        base.mkdirs()
        val data = generations().put(key, java.util.UUID.randomUUID().toString())
        val pending = File(base, "generations.tmp"); pending.writeText(data.toString())
        check(pending.renameTo(File(base, "generations.json")))
    }
    internal fun version(source: PersonalPlexSource, id: String, saved: Boolean = true): String = synchronized(lock) {
        val data = generations(); data.optString(if (saved) scope(source) else cacheTag(source)) + ":" + data.optString(if (saved) workName(source, id) else cacheWorkName(source, id))
    }
    fun enqueue(source: PersonalPlexSource, songs: List<Song>, wifiOnly: Boolean) = synchronized(lock) {
        require(songs.size <= 500) { "Download at most 500 tracks at a time" }
        val manager = WorkManager.getInstance(context)
        if (!generations().has(scope(source))) bump(scope(source))
        songs.distinctBy { it.id }.forEach { song ->
            if (uri(source, song.id) != null) { promote(source, song.id); return@forEach }
            val request = OneTimeWorkRequestBuilder<OfflineDownloadWorker>()
                .setInputData(workDataOf("source" to scope(source), "track" to song.id, "title" to song.title, "generation" to version(source, song.id)))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .addTag(tag(source)).addTag("offline_music").addTag("offline_track:${digest(song.id)}").setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            manager.enqueueUniqueWork(workName(source, song.id), ExistingWorkPolicy.KEEP, request)
        }
    }
    fun cancel(source: PersonalPlexSource) = synchronized(lock) {
        bump(scope(source)); WorkManager.getInstance(context).cancelAllWorkByTag(tag(source)); Unit
    }
    internal fun cancelWork(source: PersonalPlexSource, work: WorkInfo) = synchronized(lock) {
        work.tags.firstOrNull { it.startsWith("offline_track:") }?.substringAfter(":")?.let { bump("${tag(source)}:$it") }
        WorkManager.getInstance(context).cancelWorkById(work.id); Unit
    }
    private fun clean(song: Song) = song.copy(streamUri = null, artworkUri = null, coverArt = "", addedByEmail = "")
    private fun promote(source: PersonalPlexSource, id: String) {
        if (metadata(source).any { it.id == id }) return
        val cached = cachedSongs(source).firstOrNull { it.id == id } ?: return
        writeIndex(source, metadata(source) + cached)
        writeCache(source, cacheMetadata(source).filterNot { it.id == id })
    }
    fun remove(source: PersonalPlexSource, id: String) = synchronized(lock) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(source, id))
        bump(workName(source, id))
        writeIndex(source, metadata(source).filterNot { it.id == id })
        if (cacheMetadata(source).none { it.id == id }) audio(source, id).delete()
    }
    fun clear(source: PersonalPlexSource) = synchronized(lock) {
        cancel(source)
        val saved = metadata(source)
        writeIndex(source, emptyList())
        saved.filter { song -> cacheMetadata(source).none { it.id == song.id } }.forEach { audio(source, it.id).delete() }
    }
    fun clearAll() = synchronized(lock) { WorkManager.getInstance(context).cancelAllWorkByTag("offline_music"); base.deleteRecursively(); Unit }

    /** Update a bounded moving window. Saved downloads never belong to the eviction pool. */
    fun syncQueueCache(source: PersonalPlexSource, queue: List<Song>, enabled: Boolean = cacheSettings.enabled,
                       count: Int = cacheSettings.count, wifiOnly: Boolean = cacheSettings.wifiOnly) = synchronized(lock) {
        val desired = if (enabled) queue.distinctBy { it.id }.take(count.coerceIn(1, 50)) else emptyList()
        val ids = desired.map { it.id }
        val previous = targets(source)
        val manager = WorkManager.getInstance(context)
        if (!generations().has(cacheTag(source))) bump(cacheTag(source))
        (previous - ids.toSet()).forEach { id ->
            bump(cacheWorkName(source, id)); manager.cancelUniqueWork(cacheWorkName(source, id))
        }
        writeJson(targetsFile(source), JSONArray(ids))
        val removed = cacheMetadata(source).filter { it.id !in ids }
        writeCache(source, cacheMetadata(source).filter { it.id in ids })
        removed.filter { song -> metadata(source).none { it.id == song.id } }.forEach { audio(source, it.id).delete() }
        desired.filter { uri(source, it.id) == null }.forEach { song ->
            val request = OneTimeWorkRequestBuilder<OfflineDownloadWorker>()
                .setInputData(workDataOf("source" to scope(source), "track" to song.id, "title" to song.title,
                    "saved" to false, "generation" to version(source, song.id, false)))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .addTag(cacheTag(source)).addTag("offline_music")
                .addTag(cacheRequestTag(source, song.id)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            manager.enqueueUniqueWork(cacheWorkName(source, song.id), ExistingWorkPolicy.KEEP, request)
        }
    }
    private fun cacheRequestTag(source: PersonalPlexSource, id: String) = "queue_cache_request:${digest(id)}:${digest(version(source, id, false))}"
    internal fun currentCacheWorks(source: PersonalPlexSource, works: List<WorkInfo>): List<WorkInfo> = synchronized(lock) {
        val wanted = targets(source).map { cacheRequestTag(source, it) }.toSet()
        works.filter { work -> work.tags.any { it in wanted } }
    }
    fun resetCacheWork(source: PersonalPlexSource) = synchronized(lock) {
        bump(cacheTag(source)); WorkManager.getInstance(context).cancelAllWorkByTag(cacheTag(source)); Unit
    }
    fun clearCache(source: PersonalPlexSource) = synchronized(lock) {
        bump(cacheTag(source)); WorkManager.getInstance(context).cancelAllWorkByTag(cacheTag(source))
        syncQueueCache(source, emptyList(), enabled = false)
    }
    internal fun commit(source: PersonalPlexSource, song: Song, partial: File, expectedVersion: String? = null, saved: Boolean = true) = synchronized(lock) {
        check(expectedVersion == null || expectedVersion == version(source, song.id, saved)) { "Download was removed or cancelled" }
        if (!saved) {
            check(song.id in targets(source)) { "Track left the queue cache window" }
            if (metadata(source).any { it.id == song.id } && uri(source, song.id) != null) return@synchronized
            check(cachedBytes(source) - audio(source, song.id).length() + partial.length() <= MAX_CACHE) { "Queue cache is full (512 MB)" }
        }
        check(base.walkTopDown().filter { it.isFile }.sumOf { it.length() } <= MAX_TOTAL) { "Offline storage is full (5 GB). Remove some downloads." }
        val previous = if (saved) metadata(source) else cacheMetadata(source)
        check(partial.renameTo(audio(source, song.id))) { "Could not save this download" }
        if (saved) {
            writeIndex(source, previous.filterNot { it.id == song.id } + clean(song))
            writeCache(source, cacheMetadata(source).filterNot { it.id == song.id })
        } else writeCache(source, previous.filterNot { it.id == song.id } + clean(song))
    }
    private fun writeCache(source: PersonalPlexSource, songs: List<Song>) = writeJson(cacheIndex(source), JSONArray().apply { songs.forEach { put(encodeSong(it)) } })
    private fun writeJson(target: File, data: JSONArray) {
        val pending = File(target.parentFile, target.name + ".tmp")
        pending.writeText(data.toString()); check(pending.renameTo(target)) { "Could not save offline index" }
    }
    private fun writeIndex(source: PersonalPlexSource, songs: List<Song>) {
        val target = index(source); val pending = File(target.parentFile, "tracks.tmp")
        pending.writeText(JSONArray().apply { songs.forEach { put(encodeSong(it)) } }.toString())
        check(pending.renameTo(target)) { "Could not save downloads" }
    }
    internal fun partial(source: PersonalPlexSource, id: String, attempt: String = "") = File(directory(source), "${digest(id)}$attempt.part")
    companion object {
        private val lock = Any()
        const val MAX_TRACK = 256L * 1024 * 1024
        const val MAX_CACHE = 512L * 1024 * 1024
        fun cacheTag(source: PersonalPlexSource) = "queue_cache:${scope(source)}"
        fun cacheWorkName(source: PersonalPlexSource, id: String) = "${cacheTag(source)}:${digest(id)}"
        const val MAX_TOTAL = 5L * 1024 * 1024 * 1024
        fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        fun scope(source: PersonalPlexSource) = digest("${source.accountToken}\u0000${source.machineIdentifier}\u0000${source.libraryKey}")
        fun tag(source: PersonalPlexSource) = "offline_music:${scope(source)}"
        fun workName(source: PersonalPlexSource, id: String) = "${tag(source)}:${digest(id)}"
    }
}

class OfflineDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val api = AppStorage(applicationContext.getSharedPreferences("harmonicast", Context.MODE_PRIVATE))
        val source = api.profile.personalSource ?: return@withContext Result.failure()
        val id = inputData.getString("track") ?: return@withContext Result.failure()
        if (OfflineStore.scope(source) != inputData.getString("source")) return@withContext Result.failure()
        val store = OfflineStore(applicationContext)
        val saved = inputData.getBoolean("saved", true)
        val generation = inputData.getString("generation") ?: return@withContext Result.failure()
        if (generation != store.version(source, id, saved)) return@withContext Result.failure()
        if (store.uri(source, id) != null) { if (saved) store.enqueue(source, listOfNotNull(store.song(source, id)), false); return@withContext Result.success() }
        val partial = store.partial(source, id, this@OfflineDownloadWorker.id.toString())
        try {
            setProgress(workDataOf("title" to inputData.getString("title"), "bytes" to 0L))
            val song = LocalPlexClient(api.storage).track(source, id) ?: return@withContext failure("Track is no longer available in Plex")
            val url = song.streamUri ?: return@withContext failure("Track has no downloadable audio")
            val client = OkHttpClient.Builder().callTimeout(8, TimeUnit.MINUTES).readTimeout(45, TimeUnit.SECONDS).build()
            val call = client.newCall(Request.Builder().url(url).build())
            val cancellation = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { call.cancel() }
            try {
                call.execute().use { response ->
                    if (response.code == 401 || response.code == 403) return@withContext failure("Plex access expired. Reconnect and retry.")
                    if (!response.isSuccessful) return@withContext if (runAttemptCount < 3) Result.retry() else failure("Plex could not download this track. Retry when connected.")
                    val body = response.body ?: return@withContext failure("Plex returned no audio")
                    val type = body.contentType()?.toString().orEmpty()
                    if (type.contains("text/") || type.contains("json") || type.contains("xml")) return@withContext failure("Plex returned a page instead of audio")
                    check(body.contentLength() <= OfflineStore.MAX_TRACK) { "Track exceeds the 256 MB download limit" }
                    var bytes = 0L
                    var lastProgress = 0L
                    body.byteStream().use { input -> partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            if (isStopped) throw kotlinx.coroutines.CancellationException()
                            val count = input.read(buffer); if (count < 0) break
                            bytes += count; check(bytes <= OfflineStore.MAX_TRACK) { "Track exceeds the 256 MB download limit" }
                            if (bytes == count.toLong()) {
                                val prefix = String(buffer, 0, minOf(count, 64), Charsets.UTF_8).trimStart().lowercase()
                                check(!prefix.startsWith("<") && !prefix.startsWith("{\"")) { "Plex returned a page instead of audio" }
                            }
                            output.write(buffer, 0, count)
                            if (bytes - lastProgress >= 1024 * 1024) {
                                setProgress(workDataOf("title" to song.title, "bytes" to bytes, "total" to body.contentLength()))
                                lastProgress = bytes
                            }
                        }
                    } }
                    check(bytes > 0 && (body.contentLength() < 0 || bytes == body.contentLength())) { "Download was incomplete" }
                }
            } finally { cancellation?.dispose() }
            coroutineContext.ensureActive()
            if (api.profile.personalSource?.let(OfflineStore::scope) != OfflineStore.scope(source)) return@withContext Result.failure()
            if (isStopped) throw kotlinx.coroutines.CancellationException()
            store.commit(source, song, partial, generation, saved)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: java.io.IOException) { if (runAttemptCount < 3) Result.retry() else failure("Connection lost. Retry this download.") }
        catch (e: Exception) { failure(if (e is IllegalStateException) e.message ?: "Could not save audio" else "Could not download audio. Reconnect to Plex and retry.") }
        finally { partial.delete() }
    }
    private fun failure(message: String) = Result.failure(workDataOf("error" to message, "track" to inputData.getString("track"), "title" to inputData.getString("title")))
}
