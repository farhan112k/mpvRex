package app.marlboroadvance.mpvex.ui.browser.allfiles

import android.content.Intent
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.rememberTooltipState
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.sheets.PlayLinkSheet
import app.marlboroadvance.mpvex.ui.browser.components.BrowserBottomBar
import app.marlboroadvance.mpvex.ui.browser.components.BrowserTopBar
import app.marlboroadvance.mpvex.ui.browser.dialogs.AddToPlaylistDialog
import app.marlboroadvance.mpvex.ui.browser.dialogs.DeleteConfirmationDialog
import app.marlboroadvance.mpvex.ui.browser.dialogs.FileOperationProgressDialog
import app.marlboroadvance.mpvex.ui.browser.dialogs.FolderPickerDialog
import app.marlboroadvance.mpvex.ui.browser.dialogs.RenameDialog
import app.marlboroadvance.mpvex.ui.browser.selection.rememberSelectionManager
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import app.marlboroadvance.mpvex.utils.media.CopyPasteOps
import app.marlboroadvance.mpvex.utils.media.MediaUtils
import app.marlboroadvance.mpvex.utils.media.OpenDocumentTreeContract
import app.marlboroadvance.mpvex.ui.browser.videolist.VideoListContent
import app.marlboroadvance.mpvex.ui.browser.videolist.VideoSortDialog
import app.marlboroadvance.mpvex.utils.history.RecentlyPlayedOps
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import java.io.File

@Serializable
object AllFilesScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        val backstack = LocalBackStack.current
        val browserPreferences = koinInject<BrowserPreferences>()
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        // Needed at top level for both the FAB and the bottom action bar padding
        val navigationBarHeight = app.marlboroadvance.mpvex.ui.browser.LocalNavigationBarHeight.current

        val viewModel: AllFilesViewModel = viewModel(
            factory = AllFilesViewModel.factory(context.applicationContext as android.app.Application)
        )

        val videosWithPlaybackInfo by viewModel.videosWithPlaybackInfo.collectAsState()
        val isLoading by viewModel.isLoading.collectAsState()
        val uiSettings by viewModel.uiSettings.collectAsState()
        val recentlyPlayedFilePath by viewModel.recentlyPlayedFilePath.collectAsState()
        val videosWereDeletedOrMoved by viewModel.videosWereDeletedOrMoved.collectAsState()

        val sortDialogOpen = rememberSaveable { mutableStateOf(false) }
        val deleteDialogOpen = rememberSaveable { mutableStateOf(false) }
        val renameDialogOpen = rememberSaveable { mutableStateOf(false) }
        val addToPlaylistDialogOpen = rememberSaveable { mutableStateOf(false) }

        // Copy/Move state
        val folderPickerOpen = rememberSaveable { mutableStateOf(false) }
        val operationType = remember { mutableStateOf<CopyPasteOps.OperationType?>(null) }
        val progressDialogOpen = rememberSaveable { mutableStateOf(false) }
        val operationProgress by CopyPasteOps.operationProgress.collectAsState()

        val isFabVisible = remember { mutableStateOf(true) }
        val isRefreshing = remember { mutableStateOf(false) }
        var showFloatingBottomBar by remember { mutableStateOf(false) }

        val showLinkDialog = remember { mutableStateOf(false) }
        val isFabExpanded = remember { mutableStateOf(false) }

        val videoSortType by browserPreferences.videoSortType.collectAsState()
        val videoSortOrder by browserPreferences.videoSortOrder.collectAsState()
        val autoScrollToLastPlayed by browserPreferences.autoScrollToLastPlayed.collectAsState()

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

        val videos = remember(sortedVideosWithInfo) { sortedVideosWithInfo.map { it.video } }
        val selectionManager = rememberSelectionManager(
            items = videos,
            getId = { it.id },
            onDeleteItems = { items, _ -> viewModel.deleteVideos(items) },
            onRenameItem = { video, newName -> viewModel.renameVideo(video, newName) },
            onOperationComplete = { viewModel.refresh() }
        )

        // File picker for FAB "Open File" action — opens the system file picker filtered to video/*
       val filePicker = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            uri?.let {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                MediaUtils.playFile(it.toString(), context, "open_file")
            }
        }

        val treePickerLauncher = rememberLauncherForActivityResult(OpenDocumentTreeContract()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val selectedVideos = selectionManager.getSelectedItems()
            if (selectedVideos.isEmpty() || operationType.value == null) return@rememberLauncherForActivityResult

            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }

            progressDialogOpen.value = true
            coroutineScope.launch {
                when (operationType.value) {
                    is CopyPasteOps.OperationType.Copy -> CopyPasteOps.copyFilesToTreeUri(context, selectedVideos, uri)
                    is CopyPasteOps.OperationType.Move -> CopyPasteOps.moveFilesToTreeUri(context, selectedVideos, uri)
                    else -> {}
                }
            }
        }

        // Entering selection mode: collapse FAB and show bottom action bar
        LaunchedEffect(selectionManager.isInSelectionMode) {
            if (selectionManager.isInSelectionMode) isFabExpanded.value = false
            showFloatingBottomBar = selectionManager.isInSelectionMode
        }

        // Back press: close FAB first, then clear selection, then let parent handle navigation
        BackHandler(enabled = selectionManager.isInSelectionMode || isFabExpanded.value) {
            when {
                isFabExpanded.value -> isFabExpanded.value = false
                selectionManager.isInSelectionMode -> selectionManager.clear()
            }
        }

        // Refresh on Resume
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        Scaffold(
            topBar = {
                BrowserTopBar(
                    // "All Files" is the right title here — unlike folder screens that show the
                    // folder name, this is a distinct global view with its own identity.
                    title = stringResource(app.marlboroadvance.mpvex.R.string.app_name),
                    isInSelectionMode = selectionManager.isInSelectionMode,
                    selectedCount = selectionManager.selectedCount,
                    totalCount = videos.size,
                    onBackClick = null, // This is a top-level tab, no back navigation
                    onCancelSelection = { selectionManager.clear() },
                    onSortClick = { sortDialogOpen.value = true },
                    onSearchClick = { /* TODO: implement in-screen search/filter */ },
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
            },
            floatingActionButton = {
                if (sortedVideosWithInfo.isNotEmpty()) {
                    FloatingActionButtonMenu(
                        modifier = Modifier
                            .windowInsetsPadding(WindowInsets.systemBars)
                            .padding(bottom = navigationBarHeight),
                        expanded = isFabExpanded.value,
                        button = {
                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                                    if (isFabExpanded.value) TooltipAnchorPosition.Start else TooltipAnchorPosition.Above
                                ),
                                tooltip = { PlainTooltip { Text("Toggle menu") } },
                                state = rememberTooltipState(),
                            ) {
                                ToggleFloatingActionButton(
                                    modifier = Modifier.animateFloatingActionButton(
                                        visible = !selectionManager.isInSelectionMode && isFabVisible.value,
                                        alignment = Alignment.BottomEnd,
                                    ),
                                    checked = isFabExpanded.value,
                                    onCheckedChange = { isFabExpanded.value = !isFabExpanded.value },
                                ) {
                                    val imageVector by remember {
                                        derivedStateOf {
                                            if (checkedProgress > 0.5f) Icons.Filled.Close else Icons.Filled.PlayArrow
                                        }
                                    }
                                    Icon(
                                        painter = rememberVectorPainter(imageVector),
                                        contentDescription = null,
                                        modifier = Modifier.animateIcon({ checkedProgress }),
                                    )
                                }
                            }
                        },
                    ) {
                        FloatingActionButtonMenuItem(
                            onClick = {
                                isFabExpanded.value = false
                                filePicker.launch(arrayOf("video/*"))
                            },
                            icon = { Icon(Icons.Filled.FileOpen, contentDescription = null) },
                            text = { Text(text = "Open File") },
                        )

                        FloatingActionButtonMenuItem(
                            onClick = {
                                isFabExpanded.value = false
                                coroutineScope.launch {
                                    val recentlyPlayedVideos = RecentlyPlayedOps.getRecentlyPlayed(limit = 1)
                                    val lastPlayed = recentlyPlayedVideos.firstOrNull()
                                    if (lastPlayed != null) {
                                        MediaUtils.playFile(lastPlayed.filePath, context, "recently_played_button")
                                    } else if (sortedVideosWithInfo.isNotEmpty()) {
                                        MediaUtils.playFile(sortedVideosWithInfo.first().video, context, "first_video_button")
                                    }
                                }
                            },
                            icon = { Icon(Icons.Filled.History, contentDescription = null) },
                            text = { Text(text = "Recently Played") },
                        )

                        FloatingActionButtonMenuItem(
                            onClick = {
                                isFabExpanded.value = false
                                showLinkDialog.value = true
                            },
                            icon = { Icon(Icons.Filled.Link, contentDescription = null) },
                            text = { Text(text = "Open Link") },
                        )
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize()) {
                VideoListContent(
                    folderId = "GLOBAL_ALL_FILES",
                    videosWithInfo = sortedVideosWithInfo,
                    isLoading = isLoading && videos.isEmpty(),
                    uiSettings = uiSettings,
                    isRefreshing = isRefreshing,
                    recentlyPlayedFilePath = recentlyPlayedFilePath,
                    videosWereDeletedOrMoved = videosWereDeletedOrMoved,
                    autoScrollToLastPlayed = autoScrollToLastPlayed,
                    onRefresh = { viewModel.refresh() },
                    selectionManager = selectionManager,
                    onVideoClick = { video ->
                        // Tapping anywhere in the list collapses the speed-dial first
                        if (isFabExpanded.value) {
                            isFabExpanded.value = false
                        } else if (selectionManager.isInSelectionMode) {
                            selectionManager.toggle(video)
                        } else {
                            MediaUtils.playFile(video, context, "video_list")
                        }
                    },
                    onVideoLongClick = { video ->
                        if (isFabExpanded.value) isFabExpanded.value = false
                        selectionManager.toggle(video)
                    },
                    isFabVisible = isFabVisible,
                    modifier = Modifier.padding(padding),
                    showFloatingBottomBar = showFloatingBottomBar,
                    sortType = videoSortType,
                    sortOrder = videoSortOrder
                )

                // Floating bottom action bar — shown when one or more items are selected.
                // We pad it up by navigationBarHeight so it sits above the app's tab bar,
                // and use WindowInsets.navigationBars for the system gesture/button bar.
                AnimatedVisibility(
                    visible = showFloatingBottomBar,
                    enter = slideInVertically(
                        animationSpec = tween(durationMillis = 300),
                        initialOffsetY = { fullHeight -> fullHeight }
                    ),
                    exit = slideOutVertically(
                        animationSpec = tween(durationMillis = 300),
                        targetOffsetY = { fullHeight -> fullHeight }
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = navigationBarHeight),
                ) {
                    BrowserBottomBar(
                        isSelectionMode = true,
                        onCopyClick = {
                            operationType.value = CopyPasteOps.OperationType.Copy
                            if (CopyPasteOps.canUseDirectFileOperations()) folderPickerOpen.value = true
                            else treePickerLauncher.launch(null)
                        },
                        onMoveClick = {
                            operationType.value = CopyPasteOps.OperationType.Move
                            if (CopyPasteOps.canUseDirectFileOperations()) folderPickerOpen.value = true
                            else treePickerLauncher.launch(null)
                        },
                        onRenameClick = { renameDialogOpen.value = true },
                        onDeleteClick = { deleteDialogOpen.value = true },
                        onAddToPlaylistClick = { addToPlaylistDialogOpen.value = true },
                        showRename = selectionManager.isSingleSelection
                    )
                }
            }

            // Dialogs — placed outside the Box so they layer on top of everything
            VideoSortDialog(
                isOpen = sortDialogOpen.value,
                onDismiss = { sortDialogOpen.value = false },
                sortType = videoSortType,
                sortOrder = videoSortOrder,
                onSortTypeChange = { browserPreferences.videoSortType.set(it) },
                onSortOrderChange = { browserPreferences.videoSortOrder.set(it) },
            )

            DeleteConfirmationDialog(
                isOpen = deleteDialogOpen.value,
                onDismiss = { deleteDialogOpen.value = false },
                onConfirm = { selectionManager.deleteSelected() },
                itemType = "video",
                itemCount = selectionManager.selectedCount,
                itemNames = selectionManager.getSelectedItems().map { it.displayName },
            )

            if (renameDialogOpen.value && selectionManager.isSingleSelection) {
                val video = selectionManager.getSelectedItems().firstOrNull()
                if (video != null) {
                    val baseName = video.displayName.substringBeforeLast('.')
                    val extension = "." + video.displayName.substringAfterLast('.', "")
                    RenameDialog(
                        isOpen = true,
                        onDismiss = { renameDialogOpen.value = false },
                        onConfirm = { newName -> selectionManager.renameSelected(newName) },
                        currentName = baseName,
                        itemType = "file",
                        extension = if (extension != ".") extension else null,
                    )
                }
            }

            FolderPickerDialog(
                isOpen = folderPickerOpen.value,
                currentPath = Environment.getExternalStorageDirectory().absolutePath,
                onDismiss = { folderPickerOpen.value = false },
                onFolderSelected = { destinationPath ->
                    folderPickerOpen.value = false
                    val selectedVideos = selectionManager.getSelectedItems()
                    if (selectedVideos.isNotEmpty() && operationType.value != null) {
                        progressDialogOpen.value = true
                        coroutineScope.launch {
                            when (operationType.value) {
                                is CopyPasteOps.OperationType.Copy -> CopyPasteOps.copyFiles(context, selectedVideos, destinationPath)
                                is CopyPasteOps.OperationType.Move -> CopyPasteOps.moveFiles(context, selectedVideos, destinationPath)
                                else -> {}
                            }
                        }
                    }
                },
            )

            PlayLinkSheet(
                isOpen = showLinkDialog.value,
                onDismiss = { showLinkDialog.value = false },
                onPlayLink = { url -> MediaUtils.playFile(url, context, "play_link") },
            )

            if (operationType.value != null) {
                FileOperationProgressDialog(
                    isOpen = progressDialogOpen.value,
                    operationType = operationType.value!!,
                    progress = operationProgress,
                    onCancel = { CopyPasteOps.cancelOperation() },
                    onDismiss = {
                        progressDialogOpen.value = false
                        // Mirror VideoListScreen: mark deletion state when a move completes
                        if (operationType.value is CopyPasteOps.OperationType.Move &&
                            operationProgress.isComplete &&
                            operationProgress.error == null
                        ) {
                            viewModel.setVideosWereDeletedOrMoved()
                        }
                        operationType.value = null
                        selectionManager.clear()
                        viewModel.refresh()
                    },
                )
            }

            AddToPlaylistDialog(
                isOpen = addToPlaylistDialogOpen.value,
                videos = selectionManager.getSelectedItems(),
                onDismiss = { addToPlaylistDialogOpen.value = false },
                onSuccess = {
                    selectionManager.clear()
                    viewModel.refresh()
                },
            )
        }
    }
}

/**
 * A labelled row used for each mini-FAB in the speed-dial.
 * Label pill on the left, small FAB on the right — standard Material speed-dial pattern.
 */
@Composable
private fun FabSpeedDialItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        SmallFloatingActionButton(onClick = onClick) {
            Icon(imageVector = icon, contentDescription = label)
        }
    }
}
