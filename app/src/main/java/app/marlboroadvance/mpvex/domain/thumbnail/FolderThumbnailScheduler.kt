package app.marlboroadvance.mpvex.domain.thumbnail

import app.marlboroadvance.mpvex.domain.media.model.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class FolderThumbnailScheduler(
    private val repositoryScope: CoroutineScope,
    private val isNetworkUrl: (String) -> Boolean,
    private val showNetworkThumbnails: () -> Boolean,
    private val fetchThumbnail: suspend (Video, Int, Int) -> Unit
) {
    private val maxconcurrentfolders = 3

    private data class FolderState(
        val signature: String,
        @Volatile var nextIndex: Int = 0,
    )

    private val folderStates = ConcurrentHashMap<String, FolderState>()
    private val folderJobs = ConcurrentHashMap<String, Job>()

    fun clearState() {
        folderJobs.values.forEach { it.cancel() }
        folderJobs.clear()
        folderStates.clear()
    }

    fun startFolderThumbnailGeneration(
        folderId: String,
        videos: List<Video>,
        widthPx: Int,
        heightPx: Int,
    ) {
        val filteredVideos = videos.filterNot { it.isAudio }.let {
            if (showNetworkThumbnails()) {
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
        val state = folderStates.compute(folderId) { _, existing ->
            if (existing == null || existing.signature != signature) {
                FolderState(signature = signature, nextIndex = 0)
            } else {
                existing
            }
        }!!

        folderJobs.remove(folderId)?.cancel()
        folderJobs[folderId] = repositoryScope.launch {
            var i = state.nextIndex
            while (i < filteredVideos.size) {
                val video = filteredVideos[i]
                fetchThumbnail(video, widthPx, heightPx)
                i++
                state.nextIndex = i
            }
        }
    }

    private fun folderSignature(videos: List<Video>, widthPx: Int, heightPx: Int): String {
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