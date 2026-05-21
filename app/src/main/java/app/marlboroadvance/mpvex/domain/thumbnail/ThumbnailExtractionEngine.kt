package app.marlboroadvance.mpvex.domain.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.marlboroadvance.mpvex.domain.media.model.Video
import app.marlboroadvance.mpvex.preferences.AppearancePreferences
import app.marlboroadvance.mpvex.utils.media.MediaInfoOps
import `is`.xyz.mpv.FastThumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

class ThumbnailExtractionEngine(
    private val context: Context,
    private val appearancePreferences: AppearancePreferences
) {
    // State tracking moved from Repository
    private val useMediaStoreForVideo = ConcurrentHashMap<String, Boolean>()
    private val networkThumbnailFailed = ConcurrentHashMap<String, Boolean>()

    fun clearLocalState() {
        useMediaStoreForVideo.clear()
    }

    fun clearAllState() {
        useMediaStoreForVideo.clear()
        networkThumbnailFailed.clear()
    }

    private fun isNetworkUrl(path: String): Boolean {
        return path.startsWith("http://", ignoreCase = true) ||
            path.startsWith("https://", ignoreCase = true) ||
            path.startsWith("rtmp://", ignoreCase = true) ||
            path.startsWith("rtsp://", ignoreCase = true) ||
            path.startsWith("ftp://", ignoreCase = true) ||
            path.startsWith("sftp://", ignoreCase = true)
    }

    suspend fun extract(
        video: Video,
        dimension: Int,
        videoKey: String,
        isNetwork: Boolean
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (isNetwork) {
            // ---- Network path ----
            if (networkThumbnailFailed.containsKey(videoKey)) {
                android.util.Log.d("ThumbnailExtractionEngine", "Skipping network thumbnail (previously failed): ${video.displayName}")
                return@withContext null
            }
            
            val fastResult = generateWithFastThumbnails(video, dimension, isNetwork)
            if (fastResult != null) return@withContext fastResult

            android.util.Log.w("ThumbnailExtractionEngine", "FastThumbnails failed for network stream ${video.displayName}, trying MediaMetadataRetriever")
            val retrieverResult = generateWithMediaMetadataRetriever(video, dimension)
            if (retrieverResult == null) {
                android.util.Log.w("ThumbnailExtractionEngine", "All strategies failed for network stream ${video.displayName}")
                networkThumbnailFailed[videoKey] = true
            }
            return@withContext retrieverResult
        } else {
            // ---- Local-file path ----
            if (useMediaStoreForVideo.containsKey(videoKey)) {
                android.util.Log.d("ThumbnailExtractionEngine", "Using MediaStore for ${video.displayName}")
                return@withContext generateWithMediaStore(video, dimension)
            }
            
            val fastResult = generateWithFastThumbnails(video, dimension, isNetwork)
            if (fastResult == null) {
                android.util.Log.w("ThumbnailExtractionEngine", "FastThumbnails failed for ${video.displayName}, falling back to MediaStore")
                useMediaStoreForVideo[videoKey] = true
                return@withContext generateWithMediaStore(video, dimension)
            }
            return@withContext fastResult
        }
    }

    private suspend fun rotateIfNeeded(video: Video, bitmap: Bitmap): Bitmap {
        val rotation = MediaInfoOps.getRotation(context, video.uri, video.displayName)
        if (rotation == 0) return bitmap
        val matrix = android.graphics.Matrix()
        matrix.postRotate(rotation.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private suspend fun generateWithFastThumbnails(video: Video, dimension: Int, isNetwork: Boolean): Bitmap? {
        if (video.isAudio) return null
        
        val extension = video.path.substringAfterLast(".", "").lowercase()
        val audioExtensions = setOf("mp3", "wav", "flac", "ogg", "m4a", "aac", "wma", "opus", "m4p", "amr")
        if (extension in audioExtensions) {
            return null
        }

        if (video.mimeType.startsWith("audio/", ignoreCase = true)) {
            return null
        }
        
        return try {
            val positionSec = preferredPositionSeconds(video, isNetwork)
            val bmp = FastThumbnails.generateAsync(
                video.path.ifBlank { video.uri.toString() },
                positionSec,
                dimension,
                useHwDec = false
            ) ?: return null
            rotateIfNeeded(video, bmp)
        } catch (e: Throwable) {
            android.util.Log.e("ThumbnailExtractionEngine", "FastThumbnails crashed for ${video.displayName}", e)
            null
        }
    }

    private suspend fun generateWithMediaStore(video: Video, dimension: Int): Bitmap? {
        if (isNetworkUrl(video.path)) return null
        
        return withContext(Dispatchers.IO) {
            val mediaStoreThumbnail = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val baseUri = if (video.isAudio) android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    val contentUri = android.content.ContentUris.withAppendedId(baseUri, video.id)
                    val thumbnail = context.contentResolver.loadThumbnail(contentUri, android.util.Size(dimension, dimension), null)
                    if (video.isAudio) thumbnail else rotateIfNeeded(video, thumbnail)
                } else {
                    if (video.isAudio) {
                        null
                    } else {
                        @Suppress("DEPRECATION")
                        val thumbnail = android.provider.MediaStore.Video.Thumbnails.getThumbnail(context.contentResolver, video.id, android.provider.MediaStore.Video.Thumbnails.MINI_KIND, null)
                        if (thumbnail != null) {
                            val scaled = Bitmap.createScaledBitmap(thumbnail, dimension, (dimension * thumbnail.height) / thumbnail.width, true)
                            if (scaled != thumbnail) thumbnail.recycle()
                            rotateIfNeeded(video, scaled)
                        } else null
                    }
                }
            }.getOrNull()
            
            if (mediaStoreThumbnail != null || video.isAudio) return@withContext mediaStoreThumbnail
            
            runCatching {
                val file = java.io.File(video.path)
                if (!file.exists()) return@runCatching null
                
                val thumbnail = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    android.media.ThumbnailUtils.createVideoThumbnail(file, android.util.Size(dimension, dimension), null)
                } else {
                    @Suppress("DEPRECATION")
                    android.media.ThumbnailUtils.createVideoThumbnail(video.path, android.provider.MediaStore.Video.Thumbnails.MINI_KIND)?.let { thumb ->
                        Bitmap.createScaledBitmap(thumb, dimension, (dimension * thumb.height) / thumb.width, true).also {
                            if (it != thumb) thumb.recycle()
                        }
                    }
                }
                if (thumbnail != null) rotateIfNeeded(video, thumbnail) else null
            }.getOrNull()
        }
    }

    private suspend fun generateWithMediaMetadataRetriever(video: Video, dimension: Int): Bitmap? = withContext(Dispatchers.IO) {
        val url = video.path.ifBlank { video.uri.toString() }
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(url, emptyMap<String, String>())
            val streamDurationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0L }
            val durationMs = streamDurationMs ?: video.duration.takeIf { it > 0L }
            val positionUs: Long = if (durationMs != null && durationMs > 0L) {
                val maxSafePositionUs = (durationMs - 100L).coerceAtLeast(0L) * 1000L
                minOf(10_000_000L, maxSafePositionUs)
            } else {
                10_000_000L
            }

            val frame: Bitmap? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(positionUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, dimension, dimension)
            } else {
                retriever.getFrameAtTime(positionUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { raw ->
                    val scale = dimension.toFloat() / maxOf(raw.width, raw.height).toFloat()
                    if (scale >= 1f) raw else {
                        val scaled = Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt().coerceAtLeast(1), (raw.height * scale).toInt().coerceAtLeast(1), true)
                        if (scaled !== raw) raw.recycle()
                        scaled
                    }
                }
            }
            if (frame == null) return@withContext null
            rotateIfNeeded(video, frame)
        } catch (e: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private suspend fun generateAudioThumbnail(video: Video, dimension: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (isNetworkUrl(video.path)) return@withContext null
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val contentUri = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, video.id)
                context.contentResolver.loadThumbnail(contentUri, android.util.Size(dimension, dimension), null)
            } else {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(video.path)
                    val art = retriever.embeddedPicture
                    if (art != null) BitmapFactory.decodeByteArray(art, 0, art.size) else null
                } finally {
                    retriever.release()
                }
            }
        }.getOrNull()
    }

    private fun preferredPositionSeconds(video: Video, isNetwork: Boolean): Double {
        if (isNetwork) {
            val durationSec = video.duration / 1000.0
            return if (durationSec > 0.0) minOf(10.0, max(0.0, durationSec - 0.1)) else 10.0
        }
        val durationSec = video.duration / 1000.0
        if (durationSec <= 0.0 || durationSec < 20.0) return 0.0

        val strategy = appearancePreferences.thumbnailStrategy.get()
        return if (strategy == app.marlboroadvance.mpvex.preferences.ThumbnailStrategy.FirstFrame) {
            minOf(10.0, max(0.0, durationSec - 0.1))
        } else {
            val percent = appearancePreferences.thumbnailPositionPercent.get() / 100.0
            val candidate = durationSec * percent
            candidate.coerceIn(0.0, max(0.0, durationSec - 0.1))
        }
    }
}