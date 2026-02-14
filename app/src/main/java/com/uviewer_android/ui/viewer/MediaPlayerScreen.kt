package com.uviewer_android.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.core.view.isVisible
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput

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
    // For Video: Hide status bar when isFullScreen is true
    // For Audio: Keep status bar visible
    LaunchedEffect(isFullScreen, fileType) {
        val window = (context as? android.app.Activity)?.window
        if (window != null) {
            val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            insetsController.isAppearanceLightStatusBars = false
            
            if (fileType == FileEntry.FileType.VIDEO) {
                 if (isFullScreen) {
                     insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                     insetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                 } else {
                     insetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                 }
            } else {
                insetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Keep Screen On
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    
    DisposableEffect(Unit) {
        onDispose {
            val window = (context as? android.app.Activity)?.window
            if (window != null) {
                 val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                 insetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    LaunchedEffect(filePath) {
        viewModel.prepareMedia(filePath, isWebDav, serverId, fileType)
    }

    var playbackError by remember { mutableStateOf<String?>(null) }

    // 15s Skip Configuration
    val exoPlayer = remember(uiState.mediaUrl, uiState.authHeader) {
        if (uiState.mediaUrl != null) {
            ExoPlayer.Builder(context)
                .setSeekBackIncrementMs(15000)
                .setSeekForwardIncrementMs(15000)
                .build().apply {
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
                
                if (uiState.savedPosition > 0) {
                    seekTo(uiState.savedPosition)
                }
                prepare()
                playWhenReady = true
            }
        } else null
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            viewModel.savePosition(exoPlayer?.currentPosition ?: 0L)
            exoPlayer?.release()
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            if (!isFullScreen) {
                TopAppBar(
                    title = { Text(uiState.currentPath?.substringAfterLast('/') ?: "", color = Color.White, style = MaterialTheme.typography.bodyMedium) },
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
                // Update subtitle tracks
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
                            // Video size handled by PlayerView now
                            viewModel.setVideoSize(videoSize.width, videoSize.height)
                        }
                    })
                }

                var controlViewRef by remember { mutableStateOf<androidx.media3.ui.PlayerControlView?>(null) }
                var artworkData by remember { mutableStateOf<ByteArray?>(null) }

                LaunchedEffect(exoPlayer) {
                    exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
                        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                            artworkData = mediaMetadata.artworkData
                        }
                    })
                }

                Box(
                     modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // Rotation removed
                        }
                ) {
                    if (fileType == FileEntry.FileType.AUDIO && artworkData != null) {
                         coil.compose.AsyncImage(
                             model = artworkData,
                             contentDescription = "Album Art",
                             modifier = Modifier.fillMaxSize(),
                             contentScale = androidx.compose.ui.layout.ContentScale.Fit
                         )
                    }

                    // PlayerView handles Video + Subtitles + Aspect Ratio
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false // We use separate controls
                                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                                layoutParams = android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                // Ensure background is black to avoid artifacting
                                setShutterBackgroundColor(android.graphics.Color.BLACK)
                                // Hide artwork if we are showing it manually or if it's video
                                defaultArtwork = null 
                            }
                        },
                        update = { playerView ->
                             playerView.player = exoPlayer
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            // If Audio and we have artwork, we might want to hide the surface or keep it z-indexed below??
                            // Actually PlayerView hides surface for audio.
                            // But our AsyncImage should be ON TOP or INSIDE?
                            // If we put AsyncImage BEHIND PlayerView, PlayerView might cover it with black shutter.
                            // If we put it ON TOP, it covers visualization (if any).
                            // Let's put it BEHIND, but ensure PlayerView is transparent? 
                            // Easier: Put it ON TOP of PlayerView (which is empty for Audio)
                            // But wait, PlayerView handles subtitles?
                            // If Audio, we don't have video subtitles mostly.
                            // Let's rely on Box order. AsyncImage last = on top.
                    )
                    
                    if (fileType == FileEntry.FileType.AUDIO && artworkData != null) {
                         coil.compose.AsyncImage(
                             model = artworkData,
                             contentDescription = "Album Art",
                             modifier = Modifier.fillMaxSize(),
                             contentScale = androidx.compose.ui.layout.ContentScale.Fit
                         )
                    }
                }
                
                // Overlay for tap-to-toggle UI (Invisible)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { offset ->
                                    val screenHeightPx = size.height
                                    if (offset.y > screenHeightPx * 0.7f) {
                                        // Bottom area tap -> Show Controls
                                        controlViewRef?.show()
                                    } else {
                                        // Toggle Fullscreen / Controls
                                        if (controlViewRef?.isVisible == true) {
                                            controlViewRef?.hide()
                                        } else {
                                            controlViewRef?.show()
                                        }
                                        onToggleFullScreen()
                                    }
                                }
                            )
                        }
                )

                // Separate Controls (Always upright)
                AndroidView(
                    factory = { ctx ->
                        androidx.media3.ui.PlayerControlView(ctx).apply {
                            player = exoPlayer
                            showTimeoutMs = 5000
                            setShowFastForwardButton(true)
                            setShowRewindButton(true)
                            setShowNextButton(false) // Handled by top bar
                            setShowPreviousButton(false) // Handled by top bar
                        }
                    },
                    update = { controlView ->
                        controlView.player = exoPlayer
                        controlViewRef = controlView
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .wrapContentHeight()
                )
            }
        }
    }
}

