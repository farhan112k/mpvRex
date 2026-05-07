package app.marlboroadvance.mpvex.ui.browser.allfiles

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.marlboroadvance.mpvex.domain.media.model.Video
import app.marlboroadvance.mpvex.repository.MediaFileRepository
import app.marlboroadvance.mpvex.ui.browser.base.BaseBrowserViewModel
import app.marlboroadvance.mpvex.ui.browser.videolist.VideoWithPlaybackInfo
import app.marlboroadvance.mpvex.utils.media.MetadataRetrieval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class AllFilesViewModel(application: Application) : BaseBrowserViewModel<VideoWithPlaybackInfo>(application), KoinComponent {
  private val appearancePreferences: app.marlboroadvance.mpvex.preferences.AppearancePreferences by inject()
  private val browserPreferences: app.marlboroadvance.mpvex.preferences.BrowserPreferences by inject()

  private val _videosWithPlaybackInfo = MutableStateFlow<List<VideoWithPlaybackInfo>>(emptyList())
  val videosWithPlaybackInfo: StateFlow<List<VideoWithPlaybackInfo>> = _videosWithPlaybackInfo.asStateFlow()

  // Tracks if a move operation emptied a subset of the global list (e.g. all files from a folder moved out)
  private val _videosWereDeletedOrMoved = MutableStateFlow(false)
  val videosWereDeletedOrMoved: StateFlow<Boolean> = _videosWereDeletedOrMoved.asStateFlow()

  // Track previous count to detect mass deletions/moves
  private var previousVideoCount = 0

  init {
    loadData()
  }

  override fun loadData() {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        _isLoading.value = true
        var videoList = MediaFileRepository.getAllVideosGlobally(getApplication())

        if (!browserPreferences.showAudioFiles.get()) {
          videoList = videoList.filterNot { it.isAudio }
        }

        // Detect if a large number of files disappeared (e.g. moved out)
        if (previousVideoCount > 0 && videoList.isEmpty()) {
          _videosWereDeletedOrMoved.value = true
        } else if (videoList.isNotEmpty()) {
          _videosWereDeletedOrMoved.value = false
        }
        previousVideoCount = videoList.size

        // STEP 1: Push the fast/cached MediaStore data to the UI instantly
        loadPlaybackInfo(videoList)
        
        // Dismiss the loading spinner immediately so the user can interact
        _isLoading.value = false 

        // STEP 2: Process heavy metadata enrichment in the background
        if (MetadataRetrieval.isVideoMetadataNeeded(browserPreferences)) {
          val enrichedList = MetadataRetrieval.enrichVideosIfNeeded(
            context = getApplication(),
            videos = videoList,
            browserPreferences = browserPreferences,
            metadataCache = metadataCache
          )
          // Silently update the UI with the enriched durations/resolutions
          loadPlaybackInfo(enrichedList)
        }
        
      } catch (e: Exception) {
        Log.e("AllFilesViewModel", "Error loading global videos", e)
        _isLoading.value = false
      }
    }
  }

  override fun refresh(silent: Boolean) {
    // If it's a manual refresh (e.g., swipe to refresh), clear the cache so it forces a fresh scan
    if (!silent) {
      MediaFileRepository.clearCache()
    }
    loadData()
  }

  /**
   * Called after a successful move operation so the UI can react (e.g. show empty state).
   */
  fun setVideosWereDeletedOrMoved() {
    _videosWereDeletedOrMoved.value = true
  }

  private suspend fun loadPlaybackInfo(videos: List<Video>) {
    val playbackStates = playbackStateRepository.getAllPlaybackStates()
    val currentTime = System.currentTimeMillis()
    val thresholdDays = appearancePreferences.unplayedOldVideoDays.get()
    val thresholdMillis = thresholdDays * 24 * 60 * 60 * 1000L
    val watchedThreshold = browserPreferences.watchedThreshold.get()

    val videosWithInfo = videos.map { video ->
      val playbackState = playbackStates.find { it.mediaTitle == video.displayName }

      val videoWithOrientation = if (playbackState?.savedOrientation != null) {
        video.copy(savedOrientation = playbackState.savedOrientation)
      } else video

      val progress = if (playbackState != null && video.duration > 0) {
        val durationSeconds = video.duration / 1000
        val watched = durationSeconds - playbackState.timeRemaining.toLong()
        val progressValue = (watched.toFloat() / durationSeconds.toFloat()).coerceIn(0f, 1f)
        if (progressValue in 0.01f..0.99f) progressValue else null
      } else null

      val videoAge = currentTime - (video.dateModified * 1000)
      val isOldAndUnplayed = playbackState == null && videoAge <= thresholdMillis

      val isWatched = if (playbackState != null && video.duration > 0) {
        val durationSeconds = video.duration / 1000
        val watched = durationSeconds - playbackState.timeRemaining.toLong()
        val progressValue = (watched.toFloat() / durationSeconds.toFloat()).coerceIn(0f, 1f)
        val calculatedWatched = progressValue >= (watchedThreshold / 100f)
        playbackState.hasBeenWatched || calculatedWatched
      } else false

      VideoWithPlaybackInfo(
        video = videoWithOrientation,
        timeRemaining = playbackState?.timeRemaining?.toLong(),
        progressPercentage = progress,
        isOldAndUnplayed = isOldAndUnplayed,
        isWatched = isWatched,
        isNeverPlayed = playbackState == null,
      )
    }
    _videosWithPlaybackInfo.value = videosWithInfo
  }

  companion object {
    fun factory(application: Application) = object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>): T = AllFilesViewModel(application) as T
    }
  }
}
