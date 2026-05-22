package app.marlboroadvance.mpvex.domain.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import app.marlboroadvance.mpvex.domain.media.model.Video
import app.marlboroadvance.mpvex.utils.media.MediaInfoOps
import org.koin.java.KoinJavaComponent
import app.marlboroadvance.mpvex.utils.storage.FileTypeUtils
import app.marlboroadvance.mpvex.preferences.AppearancePreferences
import app.marlboroadvance.mpvex.domain.thumbnail.ThumbnailCacheManager
import app.marlboroadvance.mpvex.domain.thumbnail.ThumbnailExtractionEngine
import app.marlboroadvance.mpvex.domain.thumbnail.FolderThumbnailScheduler
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.NetworkStreamingProxy
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import `is`.xyz.mpv.FastThumbnails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections
import kotlin.math.max

class ThumbnailRepository(
 context: Context,
) {
  private val appearancePreferences by lazy {KoinJavaComponent.get<AppearancePreferences>(AppearancePreferences::class.java)}

  private val cacheManager = ThumbnailCacheManager(context, appearancePreferences)
  private val extractionEngine = ThumbnailExtractionEngine(context, appearancePreferences)

  private val ongoingOperations = ConcurrentHashMap<String, Deferred<Bitmap?>>()
  private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val folderScheduler = FolderThumbnailScheduler(
      repositoryScope = repositoryScope,
      isNetworkUrl = { path -> cacheManager.isNetworkUrl(path) },
      showNetworkThumbnails = { appearancePreferences.showNetworkThumbnails.get() },
      fetchThumbnail = { video, width, height -> getThumbnail(video, width, height) }
  )

  // ! REMOVE LATER: this is only for satisfy the UI (videocard, m3ucard, playlistcard)
  fun thumbnailKey(video: Video, widthPx: Int, heightPx: Int): String {
    return cacheManager.thumbnailKey(video, widthPx, heightPx)
  }

  private val _thumbnailReadyKeys =
    MutableSharedFlow<String>(
      extraBufferCapacity = 256,
    )
  val thumbnailReadyKeys: SharedFlow<String> = _thumbnailReadyKeys.asSharedFlow()

  suspend fun getThumbnail(
    video: Video,
    widthPx: Int,
    heightPx: Int,
  ): Bitmap? =
    withContext(Dispatchers.IO) {
      if (video.isAudio || FileTypeUtils.isAudioFile(java.io.File(video.path))) {
        return@withContext null
      }
      
      // 1. Define isNetwork ONCE
      val isNetwork = cacheManager.isNetworkUrl(video.path)

      if (isNetwork && !appearancePreferences.showNetworkThumbnails.get()) {
        return@withContext null
      }

      // 2. Define key ONCE using the cacheManager
      val key = cacheManager.thumbnailKey(video, widthPx, heightPx)

      cacheManager.getFromMemory(key)?.let { return@withContext it }

      ongoingOperations[key]?.let {
        return@withContext it.await()
      }

      val deferred =
        async {
          try {
            cacheManager.loadFromDisk(video)?.let { thumbnail ->
              cacheManager.putInMemory(key, thumbnail, isNetwork)
              _thumbnailReadyKeys.tryEmit(key)
              return@async thumbnail
            }

            // Engine handles FastThumbnails, MediaStore, Retriever, and fallback tracking internally.
            val videoKey = cacheManager.videoBaseKey(video)
            val thumbnail = extractionEngine.extract(
                video = video,
                dimension = cacheManager.diskCacheDimension,
                videoKey = videoKey,
                isNetwork = isNetwork
            )

            if (thumbnail == null) {
              return@async null
            }
            
            cacheManager.putInMemory(key, thumbnail, isNetwork)
            _thumbnailReadyKeys.tryEmit(key)
            cacheManager.writeToDisk(video, thumbnail)

            thumbnail
          } finally {
            ongoingOperations.remove(key)
          }
        }

      ongoingOperations[key] = deferred
      return@withContext deferred.await()
    }

  suspend fun getCachedThumbnail(
    video: Video,
    widthPx: Int,
    heightPx: Int,
  ): Bitmap? =
    withContext(Dispatchers.IO) {
      if (video.isAudio || FileTypeUtils.isAudioFile(java.io.File(video.path))) {
        return@withContext null
      }
      
      val isNetwork = cacheManager.isNetworkUrl(video.path)
      if (isNetwork && !appearancePreferences.showNetworkThumbnails.get()) {
        return@withContext null
      }
      
      val key = cacheManager.thumbnailKey(video, widthPx, heightPx)
      
      cacheManager.getFromMemory(key)?.let { return@withContext it }
      
      cacheManager.loadFromDisk(video)?.let { thumbnail ->
        cacheManager.putInMemory(key, thumbnail, isNetwork)
        return@withContext thumbnail
      }
      null
    }

  fun getThumbnailFromMemory(
    video: Video,
    widthPx: Int,
    heightPx: Int,
  ): Bitmap? {
    if (video.isAudio || FileTypeUtils.isAudioFile(java.io.File(video.path))) {
      return null
    }
    if (cacheManager.isNetworkUrl(video.path) && !appearancePreferences.showNetworkThumbnails.get()) {
      return null
    }
    
    val key = cacheManager.thumbnailKey(video, widthPx, heightPx)
    return cacheManager.getFromMemory(key)
  }

  /**
   * Resolves network video thumbnails via a localized proxy stream.
   * Orchestrates the proxy, but delegates caching to CacheManager and extraction to ExtractionEngine.
   */
  suspend fun getThumbnailViaProxy(
    path: String,
    name: String,
    size: Long,
    connection: NetworkConnection,
    dimension: Int
  ): Bitmap? = withContext(Dispatchers.IO) {
    if (!appearancePreferences.showNetworkThumbnails.get()) return@withContext null

    // 1. Create baseline Video representing the REAL network file for accurate caching
    val originalVideo = Video(
      id = path.hashCode().toLong(),
      title = name,
      displayName = name,
      path = path,
      uri = android.net.Uri.parse(path),
      duration = 0, // Engine defaults to 10s extraction if duration is 0
      durationFormatted = "",
      size = size,
      sizeFormatted = "",
      dateModified = 0,
      dateAdded = 0,
      mimeType = "video/*",
      bucketId = "",
      bucketDisplayName = "",
      width = 0,
      height = 0,
      fps = 0f,
      resolution = ""
    )

    // Map UI dimension to width/height to keep cache keys consistent
    val key = cacheManager.thumbnailKey(originalVideo, dimension, dimension)
    val videoKey = cacheManager.videoBaseKey(originalVideo)

    // 2. Check Memory Cache
    cacheManager.getFromMemory(key)?.let { return@withContext it }

    // Prevent concurrent identical operations
    ongoingOperations[key]?.let { return@withContext it.await() }

    val deferred = async {
      try {
        // 3. Check Disk Cache
        cacheManager.loadFromDisk(originalVideo)?.let { thumbnail ->
          cacheManager.putInMemory(key, thumbnail, true)
          _thumbnailReadyKeys.tryEmit(key)
          return@async thumbnail
        }

        // 4. Spin up Proxy
        val proxy = NetworkStreamingProxy.getInstance()
        val streamId = "thumb_${path.hashCode()}_${System.nanoTime()}"
        val localUrl = proxy.registerStream(streamId, connection, path, size)

        // 5. Delegate extraction to the Engine using the localhost URL
        val thumbnail = try {
          val tempVideo = originalVideo.copy(
            path = localUrl,
            uri = android.net.Uri.parse(localUrl)
          )
          
          extractionEngine.extract(
            video = tempVideo,
            dimension = cacheManager.diskCacheDimension, // Extract at high-res, CacheManager scales it
            videoKey = videoKey, // Pass original key so failure states map to the real SMB path
            isNetwork = true // Force strict network rules
          )
        } finally {
          proxy.unregisterStream(streamId)
        }

        if (thumbnail == null) return@async null

        // 6. Save State
        cacheManager.putInMemory(key, thumbnail, true)
        _thumbnailReadyKeys.tryEmit(key)
        cacheManager.writeToDisk(originalVideo, thumbnail)

        thumbnail
      } finally {
        ongoingOperations.remove(key)
      }
    }

    ongoingOperations[key] = deferred
    return@withContext deferred.await()
  }

  fun clearLocalThumbnailCache() {
    folderScheduler.clearState()
    cacheManager.clearLocalCache()
    extractionEngine.clearLocalState()
  }

  fun clearThumbnailCache() {
    folderScheduler.clearState()
    ongoingOperations.clear()
    cacheManager.clearAllCache()
    extractionEngine.clearAllState()
  }

  fun startFolderThumbnailGeneration(
    folderId: String,
    videos: List<Video>,
    widthPx: Int,
    heightPx: Int,
  ) {
    folderScheduler.startFolderThumbnailGeneration(folderId, videos, widthPx, heightPx)
  }
}
