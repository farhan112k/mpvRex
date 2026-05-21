package app.marlboroadvance.mpvex.domain.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import app.marlboroadvance.mpvex.domain.media.model.Video
import app.marlboroadvance.mpvex.utils.media.MediaInfoOps
import app.marlboroadvance.mpvex.domain.thumbnail.ThumbnailCacheManager
import app.marlboroadvance.mpvex.domain.thumbnail.ThumbnailExtractionEngine
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
  private val appearancePreferences by lazy { 
    org.koin.java.KoinJavaComponent.get<app.marlboroadvance.mpvex.preferences.AppearancePreferences>(
      app.marlboroadvance.mpvex.preferences.AppearancePreferences::class.java
    ) 
  }
  private val cacheManager = ThumbnailCacheManager(context, appearancePreferences)
  private val extractionEngine = ThumbnailExtractionEngine(context, appearancePreferences)

  private val ongoingOperations = ConcurrentHashMap<String, Deferred<Bitmap?>>()
  private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val maxconcurrentfolders = 3

  private data class FolderState(
    val signature: String,
    @Volatile var nextIndex: Int = 0,
  )

  private val folderStates = ConcurrentHashMap<String, FolderState>()
  private val folderJobs = ConcurrentHashMap<String, Job>()

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
      if (video.isAudio || app.marlboroadvance.mpvex.utils.storage.FileTypeUtils.isAudioFile(java.io.File(video.path))) {
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
      if (video.isAudio || app.marlboroadvance.mpvex.utils.storage.FileTypeUtils.isAudioFile(java.io.File(video.path))) {
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
    if (video.isAudio || app.marlboroadvance.mpvex.utils.storage.FileTypeUtils.isAudioFile(java.io.File(video.path))) {
      return null
    }
    if (cacheManager.isNetworkUrl(video.path) && !appearancePreferences.showNetworkThumbnails.get()) {
      return null
    }
    
    val key = cacheManager.thumbnailKey(video, widthPx, heightPx)
    return cacheManager.getFromMemory(key)
  }

  fun clearLocalThumbnailCache() {
    folderJobs.values.forEach { it.cancel() }
    folderJobs.clear()
    folderStates.clear()
    
    cacheManager.clearLocalCache()
    extractionEngine.clearLocalState()
  }

  fun clearThumbnailCache() {
    folderJobs.values.forEach { it.cancel() }
    folderJobs.clear()
    folderStates.clear()
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
    val filteredVideos = videos.filterNot { it.isAudio }.let {
      if (appearancePreferences.showNetworkThumbnails.get()) {
        it
      } else {
        it.filterNot { v -> isNetworkUrl(v.path) }
      }
    }
    
    if (filteredVideos.isEmpty()) return
    
    folderJobs.entries.removeAll { !it.value.isActive }
    
    if (folderJobs.size >= maxconcurrentfolders && !folderJobs.containsKey(folderId)) {
      folderJobs.entries.firstOrNull()?.let { (oldestId, job) ->
        job.cancel()
        folderJobs.remove(oldestId)
        folderStates.remove(oldestId)
      }
    }
    
    val signature = folderSignature(filteredVideos, widthPx, heightPx)
    val state =
      folderStates.compute(folderId) { _, existing ->
        if (existing == null || existing.signature != signature) {
          FolderState(signature = signature, nextIndex = 0)
        } else {
          existing
        }
      }!!

    folderJobs.remove(folderId)?.cancel()
    folderJobs[folderId] =
      repositoryScope.launch {
        var i = state.nextIndex
        while (i < filteredVideos.size) {
          val video = filteredVideos[i]
          getThumbnail(video, widthPx, heightPx)
          i++
          state.nextIndex = i
        }
      }
  }
 
  private fun isNetworkUrl(path: String): Boolean {
    return path.startsWith("http://", ignoreCase = true) ||
      path.startsWith("https://", ignoreCase = true) ||
      path.startsWith("rtmp://", ignoreCase = true) ||
      path.startsWith("rtsp://", ignoreCase = true) ||
      path.startsWith("ftp://", ignoreCase = true) ||
      path.startsWith("sftp://", ignoreCase = true)
  }

  private fun folderSignature(
    videos: List<Video>,
    widthPx: Int,
    heightPx: Int,
  ): String {
    val md = MessageDigest.getInstance("MD5")
    md.update("$widthPx|$heightPx|".toByteArray())
    for (v in videos) {
      md.update(v.path.toByteArray())
      md.update("|".toByteArray())
      md.update(v.size.toString().toByteArray())
      md.update("|".toByteArray())
      md.update(v.dateModified.toString().toByteArray())
      md.update(";".toByteArray())
    }
    return md.digest().joinToString("") { b -> "%02x".format(b) }
  }
}
