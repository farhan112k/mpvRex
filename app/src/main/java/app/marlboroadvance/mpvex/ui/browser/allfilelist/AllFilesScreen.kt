package app.marlboroadvance.mpvex.ui.browser.allfiles

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.components.BrowserTopBar
import app.marlboroadvance.mpvex.ui.browser.selection.rememberSelectionManager
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import app.marlboroadvance.mpvex.utils.media.MediaUtils
import app.marlboroadvance.mpvex.ui.browser.videolist.VideoListContent
import app.marlboroadvance.mpvex.ui.browser.videolist.VideoSortDialog
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
object AllFilesScreen : Screen {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val backstack = LocalBackStack.current
        val browserPreferences = koinInject<BrowserPreferences>()

        val viewModel: AllFilesViewModel = viewModel(
            factory = AllFilesViewModel.factory(context.applicationContext as android.app.Application)
        )

        val videosWithPlaybackInfo by viewModel.videosWithPlaybackInfo.collectAsState()
        val isLoading by viewModel.isLoading.collectAsState()
        val uiSettings by viewModel.uiSettings.collectAsState()
        val recentlyPlayedFilePath by viewModel.recentlyPlayedFilePath.collectAsState()

        val sortDialogOpen = rememberSaveable { mutableStateOf(false) }
        val isFabVisible = remember { mutableStateOf(true) }
        val isRefreshing = remember { mutableStateOf(false) }

        val videoSortType by browserPreferences.videoSortType.collectAsState()
        val videoSortOrder by browserPreferences.videoSortOrder.collectAsState()

        val sortedVideosWithInfo = remember(videosWithPlaybackInfo, videoSortType, videoSortOrder) {
            val infoById = videosWithPlaybackInfo.associateBy { it.video.id }
            val sortedVideos = app.marlboroadvance.mpvex.utils.sort.SortUtils.sortVideos(
                videosWithPlaybackInfo.map { it.video }, 
                videoSortType, 
                videoSortOrder
            )
            sortedVideos.map { video ->
                infoById[video.id] ?: app.marlboroadvance.mpvex.ui.browser.videolist.VideoWithPlaybackInfo(video)
            }
        }

        // Isolate videos for the selection manager
        val videos = remember(sortedVideosWithInfo) { sortedVideosWithInfo.map { it.video } }
        val selectionManager = rememberSelectionManager(
            items = videos,
            getId = { it.id },
            onDeleteItems = { items, _ -> 
                viewModel.deleteVideos(items)
                Pair(items.size, 0)
            },
            onOperationComplete = { viewModel.refresh() }
        )

        Scaffold(
            topBar = {
                BrowserTopBar(
                    title = "All Files",
                    isInSelectionMode = selectionManager.isInSelectionMode,
                    selectedCount = selectionManager.selectedCount,
                    totalCount = videos.size,
                    onBackClick = null, // Hidden because it's a root tab
                    onCancelSelection = { selectionManager.clear() },
                    onSortClick = { sortDialogOpen.value = true },
                    onSearchClick = { /* Add search logic if needed later */ },
                    onSettingsClick = {
                        backstack.add(app.marlboroadvance.mpvex.ui.preferences.PreferencesScreen)
                    },
                    isSingleSelection = selectionManager.isSingleSelection,
                    onInfoClick = {
                        if (selectionManager.isSingleSelection) {
                            val video = selectionManager.getSelectedItems().firstOrNull()
                            if (video != null) {
                                val intent = Intent(context, app.marlboroadvance.mpvex.ui.mediainfo.MediaInfoActivity::class.java)
                                intent.action = Intent.ACTION_VIEW
                                intent.data = video.uri
                                context.startActivity(intent)
                                selectionManager.clear()
                            }
                        }
                    },
                    onShareClick = { selectionManager.shareSelected() },
                    onPlayClick = { selectionManager.playSelected() },
                    onSelectAll = { selectionManager.selectAll() },
                    onInvertSelection = { selectionManager.invertSelection() },
                    onDeselectAll = { selectionManager.clear() },
                )
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                VideoListContent(
                    folderId = "GLOBAL_ALL_FILES",
                    videosWithInfo = sortedVideosWithInfo,
                    isLoading = isLoading,
                    uiSettings = uiSettings,
                    isRefreshing = isRefreshing,
                    recentlyPlayedFilePath = recentlyPlayedFilePath,
                    videosWereDeletedOrMoved = false,
                    autoScrollToLastPlayed = false,
                    onRefresh = { viewModel.refresh() },
                    selectionManager = selectionManager,
                    onVideoClick = { video ->
                        if (selectionManager.isInSelectionMode) {
                            selectionManager.toggle(video)
                        } else {
                            MediaUtils.playFile(video, context, "video_list")
                        }
                    },
                    onVideoLongClick = { video -> selectionManager.toggle(video) },
                    isFabVisible = isFabVisible,
                    sortType = videoSortType,
                    sortOrder = videoSortOrder
                )
            }
        }

        VideoSortDialog(
            isOpen = sortDialogOpen.value,
            onDismiss = { sortDialogOpen.value = false },
            sortType = videoSortType,
            sortOrder = videoSortOrder,
            onSortTypeChange = { browserPreferences.videoSortType.set(it) },
            onSortOrderChange = { browserPreferences.videoSortOrder.set(it) },
        )
    }
}