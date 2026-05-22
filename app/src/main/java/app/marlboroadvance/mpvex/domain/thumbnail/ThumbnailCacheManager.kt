package app.marlboroadvance.mpvex.domain.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import app.marlboroadvance.mpvex.domain.media.model.Video
import app.marlboroadvance.mpvex.preferences.AppearancePreferences
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class ThumbnailCacheManager(
    private val context: Context,
    private val appearancePreferences: AppearancePreferences
) {
    val diskCacheDimension = 1024
    private val diskJpegQuality = 100
    private val memoryCache: LruCache<String, Bitmap>
    private val networkDiskDir: File = File(context.filesDir, "thumbnails/network").apply { mkdirs() }
    private val localDiskDir: File = File(context.filesDir, "thumbnails/local").apply { mkdirs() }

    val networkMemoryKeys: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    init {
        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
        val cacheSizeKb = maxMemoryKb / 6
        memoryCache = object : LruCache<String, Bitmap>(cacheSizeKb) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }
        runCatching {
            File(context.filesDir, "thumbnails")
                .listFiles()
                ?.filter { it.isFile && it.name.endsWith(".jpg") }
                ?.forEach { it.delete() }
        }
    }

    fun thumbnailKey(video: Video, width: Int, height: Int): String {
        return "${videoBaseKey(video)}|$width|$height"
    }

    fun videoBaseKey(video: Video): String {
        if (isNetworkUrl(video.path)) {
            val base = video.path.ifBlank { video.uri.toString() }
            return "$base|network"
        }
        return "${video.size}|${video.dateModified}|${video.duration}"
    }

    private fun keyToFileName(key: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(key.toByteArray())
        return digest.joinToString("") { b -> "%02x".format(b) } + ".jpg"
    }

    private fun diskKey(video: Video): String {
        val baseKey = videoBaseKey(video)
        return if (isNetworkUrl(video.path)) {
            "$baseKey|disk|d$diskCacheDimension|pos10s"
        } else {
            val strategy = appearancePreferences.thumbnailStrategy.get()
            if (strategy == app.marlboroadvance.mpvex.preferences.ThumbnailStrategy.FirstFrame) {
                "$baseKey|disk|d$diskCacheDimension|firstFrame"
            } else {
                val percent = appearancePreferences.thumbnailPositionPercent.get()
                "$baseKey|disk|d$diskCacheDimension|pos${percent}pct"
            }
        }
    }

    private fun diskDirFor(video: Video): File =
        if (isNetworkUrl(video.path)) networkDiskDir else localDiskDir

    // TODO: This logic is duplicated across app. Move this to a dedicated FileTypeUtils or UrlUtils object later.
    fun isNetworkUrl(path: String): Boolean {
        return path.startsWith("http://", ignoreCase = true) ||
            path.startsWith("https://", ignoreCase = true) ||
            path.startsWith("rtmp://", ignoreCase = true) ||
            path.startsWith("rtsp://", ignoreCase = true) ||
            path.startsWith("ftp://", ignoreCase = true) ||
            path.startsWith("sftp://", ignoreCase = true)
    }

    fun loadFromDisk(video: Video, targetDimension: Int = diskCacheDimension): Bitmap? {
        val diskFile = File(diskDirFor(video), keyToFileName(diskKey(video)))
        if (!diskFile.exists()) return null
        
        return runCatching {
            val options = BitmapFactory.Options().apply {
                // Read bounds first to calculate sample size
                inJustDecodeBounds = true
                BitmapFactory.decodeFile(diskFile.absolutePath, this)
                
                // Use your new utility function here
                inSampleSize = calculateThumbnailSampleSize(outWidth, outHeight, targetDimension)
                
                inJustDecodeBounds = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(diskFile.absolutePath, options)
        }.getOrNull()
    }

    fun writeToDisk(video: Video, bitmap: Bitmap) {
        val diskFile = File(diskDirFor(video), keyToFileName(diskKey(video)))
        runCatching {
            FileOutputStream(diskFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, diskJpegQuality, out)
                out.flush()
            }
        }
    }

    fun getFromMemory(key: String): Bitmap? = synchronized(memoryCache) { memoryCache.get(key) }

    fun putInMemory(key: String, bitmap: Bitmap, isNetwork: Boolean) {
        synchronized(memoryCache) {
            if (isNetwork) networkMemoryKeys.add(key)
            memoryCache.put(key, bitmap)
        }
    }

    fun clearLocalCache() {
        synchronized(memoryCache) {
            val snapshot = memoryCache.snapshot().keys.toList()
            for (key in snapshot) {
                if (key !in networkMemoryKeys) memoryCache.remove(key)
            }
        }
        runCatching { localDiskDir.listFiles()?.forEach { it.delete() } }
    }

    fun clearAllCache() {
        networkMemoryKeys.clear()
        synchronized(memoryCache) { memoryCache.evictAll() }
        runCatching {
            if (networkDiskDir.exists()) networkDiskDir.listFiles()?.forEach { it.delete() }
            if (localDiskDir.exists()) localDiskDir.listFiles()?.forEach { it.delete() }
        }
    }
}