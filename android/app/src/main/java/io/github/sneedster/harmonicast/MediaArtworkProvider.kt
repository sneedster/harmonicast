package io.github.sneedster.harmonicast

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.FileNotFoundException
import java.security.MessageDigest

/** Read-only artwork bridge. Callers receive pixels, never upstream URLs, redirects or headers. */
class MediaArtworkProvider : ContentProvider() {
    companion object {
        private val sources = LruCache<String, String>(512)
        fun uri(context: Context, source: String?): Uri? {
            if (source.isNullOrBlank()) return null
            val key = MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
                .joinToString("") { "%02x".format(it) }
            sources.put(key, source)
            return Uri.Builder().scheme("content").authority("${context.packageName}.media-artwork")
                .appendPath(key).build()
        }
    }

    override fun onCreate() = true
    override fun getType(uri: Uri) = "image/jpeg"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r" || uri.authority != "${requireNotNull(context).packageName}.media-artwork" ||
            uri.pathSegments.size != 1 || uri.query != null || uri.fragment != null) {
            throw FileNotFoundException("Unknown artwork")
        }
        val source = sources.get(uri.lastPathSegment) ?: throw FileNotFoundException("Expired artwork")
        // openPipeHelper performs the fetch on a worker. Browsing and playback never wait for artwork.
        return openPipeHelper(uri, "image/jpeg", null, source) { output, _, _, _, url ->
            ParcelFileDescriptor.AutoCloseOutputStream(output).use { stream ->
                try {
                    runBlocking(Dispatchers.IO) {
                        withTimeout(10_000) {
                            val ctx = requireNotNull(context)
                            val result = ctx.imageLoader.execute(ImageRequest.Builder(ctx)
                                .data(url).size(256).allowHardware(false).build())
                            if (result is SuccessResult) {
                                // Re-encoding strips file metadata as well as keeping IPC images small.
                                result.drawable.toBitmap().compress(Bitmap.CompressFormat.JPEG, 85, stream)
                            }
                        }
                    }
                } catch (_: Exception) { /* Close the pipe; never expose upstream exception text. */ }
            }
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
