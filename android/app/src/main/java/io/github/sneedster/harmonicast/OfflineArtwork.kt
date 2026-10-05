package io.github.sneedster.harmonicast

import android.graphics.Bitmap
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/** Optional cover preparation on a worker; artwork failures never invalidate complete audio. */
internal object OfflineArtwork {
    private const val MAX_INPUT = 4 * 1024 * 1024
    private const val EDGE = 512
    private val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    /** Room transports may read only app-owned cover files, never arbitrary local paths. */
    internal fun local(context: Context, uri: String): ByteArray? = runCatching {
        val file = File(java.net.URI(uri)).canonicalFile
        val root = File(context.noBackupFilesDir, "offline_music").canonicalFile
        file.takeIf { it.path.startsWith(root.path + File.separator) &&
            it.name.matches(Regex("[a-f0-9]{64}\\.cover\\.jpg")) && it.isFile &&
            it.length() in 1..OfflineStore.MAX_ARTWORK.toLong() }?.readBytes()
    }.getOrNull()

    suspend fun load(audio: File, plexArtwork: String?): ByteArray? = withContext(Dispatchers.IO) {
        val selected = plexArtwork?.let { url ->
            val call = http.newCall(Request.Builder().url(url).build())
            val cancellation = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { call.cancel() }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) null else response.body?.let { body ->
                        if (body.contentLength() > MAX_INPUT) null else {
                            val bytes = body.byteStream().use { input ->
                                ByteArrayOutputStream().use { output ->
                                    val buffer = ByteArray(16 * 1024)
                                    while (output.size() <= MAX_INPUT) {
                                        coroutineContext.ensureActive()
                                        val count = input.read(buffer, 0, minOf(buffer.size, MAX_INPUT + 1 - output.size()))
                                        if (count < 0) break
                                        output.write(buffer, 0, count)
                                    }
                                    output.toByteArray()
                                }
                            }
                            normalize(bytes)
                        }
                    }
                }
            } catch (_: java.io.IOException) { null }
            finally { cancellation?.dispose() }
        }
        coroutineContext.ensureActive()
        selected ?: embedded(audio)
    }

    internal fun embedded(audio: File): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(audio.absolutePath)
            retriever.embeddedPicture?.let(::normalize)
        } catch (_: Exception) { null }
        finally { runCatching { retriever.release() } }
    }

    /** Bounded pixels, no embedded file metadata, and a consistent format for every consumer. */
    internal fun normalize(bytes: ByteArray): ByteArray? {
        if (bytes.isEmpty() || bytes.size > MAX_INPUT) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > EDGE) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return try {
            ByteArrayOutputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)) null
                else output.toByteArray().takeIf { it.size <= OfflineStore.MAX_ARTWORK }
            }
        } finally { bitmap.recycle() }
    }
}
