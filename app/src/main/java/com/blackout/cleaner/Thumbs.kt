package com.blackout.cleaner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Small previews made on the phone only. Nothing is saved or sent anywhere. */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val photo = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
    private val video = setOf("mp4", "mkv", "3gp", "webm", "mov", "avi", "m4v")

    fun canPreview(f: File): Boolean = f.extension.lowercase().let { it in photo || it in video }

    fun load(f: File, px: Int = 128): Bitmap? {
        val key = f.absolutePath + "|" + f.lastModified()
        cache.get(key)?.let { return it }
        val ext = f.extension.lowercase()
        val bmp = try {
            if (ext in video) {
                val r = MediaMetadataRetriever()
                try { r.setDataSource(f.absolutePath); r.getFrameAtTime(0) } finally { r.release() }
            } else {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, o)
                var s = 1
                while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2
                BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
            }
        } catch (e: Throwable) { null }
        if (bmp != null) cache.put(key, bmp)
        return bmp
    }
}

@Composable
fun Thumb(file: File, fallback: @Composable () -> Unit) {
    if (!Thumbs.canPreview(file)) { fallback(); return }
    val bmp by produceState<Bitmap?>(null, file) { value = withContext(Dispatchers.IO) { Thumbs.load(file) } }
    val b = bmp
    if (b == null) fallback() else Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
}
