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
  private val context: Context,
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
