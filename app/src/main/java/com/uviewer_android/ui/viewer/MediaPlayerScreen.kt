package com.uviewer_android.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.uviewer_android.R
import com.uviewer_android.data.model.FileEntry
import com.uviewer_android.ui.AppViewModelProvider

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun MediaPlayerScreen(
    filePath: String,
    fileType: FileEntry.FileType,
    isWebDav: Boolean,
    serverId: Int?,
    viewModel: MediaPlayerViewModel = viewModel(factory = AppViewModelProvider.Factory),
    onBack: () -> Unit,
    isFullScreen: Boolean = false,
    onToggleFullScreen: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    
    // Ensure status bar icons are visible (white) on dark background even in light theme
    LaunchedEffect(isFullScreen) {
        val window = (context as? android.app.Activity)?.window
        if (window != null) {
            val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            insetsController.isAppearanceLightStatusBars = false
        }
    }

    LaunchedEffect(filePath) {
        viewModel.prepareMedia(filePath, isWebDav, serverId, fileType)
    }

    var playbackError by remember { mutableStateOf<String?>(null) }

    val exoPlayer = remember(uiState.mediaUrl, uiState.authHeader) {
        if (uiState.mediaUrl != null) {
            ExoPlayer.Builder(context).build().apply {
                val uri = if (isWebDav) {
                    android.net.Uri.parse(uiState.mediaUrl!!)
                } else {
                    android.net.Uri.fromFile(java.io.File(uiState.mediaUrl!!))
                }
                
                val mediaItem = MediaItem.fromUri(uri)
                if (isWebDav) {
                    val dataSourceFactory = DefaultHttpDataSource.Factory()
                    dataSourceFactory.setDefaultRequestProperties(uiState.authHeader)
                    val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(mediaItem)
                    setMediaSource(mediaSource)
                } else {
                    setMediaItem(mediaItem)
                }
                
                addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        playbackError = error.message
                    }
                })
                
                prepare()
                playWhenReady = true
            }
        } else null
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer?.release()
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            if (!isFullScreen) {
                TopAppBar(
                    title = { Text(uiState.currentPath?.substringAfterLast('/') ?: "", color = Color.White) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.prev(isWebDav, serverId) }) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = Color.White)
                        }
                        IconButton(onClick = { viewModel.next(isWebDav, serverId) }) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White)
                        }
                        if (fileType == FileEntry.FileType.VIDEO) {
                            var showSubtitleMenu by remember { mutableStateOf(false) }
                            IconButton(onClick = { showSubtitleMenu = true }) {
                                Icon(Icons.Default.Subtitles, contentDescription = "Subtitles", tint = Color.White)
                            }
                            if (showSubtitleMenu && uiState.subtitleTracks.isNotEmpty()) {
                                DropdownMenu(
                                    expanded = showSubtitleMenu,
                                    onDismissRequest = { showSubtitleMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Off") },
                                        onClick = { 
                                            exoPlayer?.trackSelectionParameters = exoPlayer?.trackSelectionParameters
                                                ?.buildUpon()
                                                ?.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                                                ?.build() ?: exoPlayer!!.trackSelectionParameters
                                            showSubtitleMenu = false 
                                        }
                                    )
                                    uiState.subtitleTracks.forEach { track ->
                                        DropdownMenuItem(
                                            text = { Text(track.label) },
                                            onClick = {
                                                exoPlayer?.trackSelectionParameters = exoPlayer?.trackSelectionParameters
                                                    ?.buildUpon()
                                                    ?.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                                                    ?.setPreferredTextLanguage(track.language)
                                                    ?.build() ?: exoPlayer!!.trackSelectionParameters
                                                showSubtitleMenu = false
                                            }
                                        )
                                    }
                                }
                            }

                            IconButton(onClick = { viewModel.rotate() }) {
                                Icon(Icons.Default.RotateRight, contentDescription = "Rotate", tint = Color.White)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
                )
            }
        }
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            val screenWidth = maxWidth.value
            val screenHeight = maxHeight.value
            
            if (uiState.isLoading) {
                CircularProgressIndicator()
            } else if (uiState.error != null || playbackError != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        stringResource(R.string.error_fmt, uiState.error ?: playbackError ?: ""),
                        color = Color.Red
                    )
                    Button(onClick = onBack) {
                        Text(stringResource(R.string.back))
                    }
                }
            } else if (exoPlayer != null) {
                // Update subtitle tracks and video size
                LaunchedEffect(exoPlayer) {
                    exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
                        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                            val subtitleTracks = mutableListOf<SubtitleTrack>()
                            tracks.groups.forEach { group ->
                                if (group.type == androidx.media3.common.C.TRACK_TYPE_TEXT) {
                                    for (i in 0 until group.length) {
                                        val format = group.getTrackFormat(i)
                                        subtitleTracks.add(
                                            SubtitleTrack(
                                                id = format.id ?: i.toString(),
                                                label = format.label ?: format.language ?: "Track $i",
                                                language = format.language,
                                                isSelected = group.isTrackSelected(i)
                                            )
                                        )
                                    }
                                }
                            }
                            viewModel.updateSubtitleTracks(subtitleTracks)
                        }

                        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                            viewModel.setVideoSize(videoSize.width, videoSize.height)
                        }
                    })
                }

                val isRotated = uiState.rotation % 180f != 0f
                val scale = if (isRotated && screenWidth > 0f && screenHeight > 0f) {
                    // When rotated 90/270, the view's width becomes height and height becomes width.
                    // To fit the screen perfectly, we want the new width (old height) to be maxWidth
                    // and new height (old width) to be maxHeight.
                    // So scale = maxWidth / screenHeight (if we want to match width) 
                    // or scale = maxHeight / screenWidth (if we want to match height)
                    // Usually we want to fit both, so min(maxWidth / screenHeight, maxHeight / screenWidth)
                    if (screenHeight > screenWidth) screenHeight / screenWidth else screenWidth / screenHeight
                } else 1f

                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = true
                            if (fileType == FileEntry.FileType.AUDIO) {
                                hideController()
                            }
                        }
                    },
                    update = { playerView ->
                        playerView.player = exoPlayer
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            rotationZ = uiState.rotation,
                            scaleX = scale,
                            scaleY = scale
                        )
                )
                
                // Overlay for tap-to-toggle UI (below top bar area)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            onClick = onToggleFullScreen
                        )
                )
            }
        }
    }
}
