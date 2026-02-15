package com.uviewer_android.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import com.uviewer_android.PlaybackService
import com.uviewer_android.data.model.FileEntry
import com.uviewer_android.ui.AppViewModelProvider
import androidx.core.view.isVisible
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import android.widget.Toast
import android.util.Log

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
    val systemInDarkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    DisposableEffect(isFullScreen, fileType) {
        val window = (context as? android.app.Activity)?.window
        if (window != null) {
            val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            insetsController.isAppearanceLightStatusBars = false // White icons on dark background
            
            if (fileType == FileEntry.FileType.VIDEO) {
                 if (isFullScreen) {
                     insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
                     insetsController.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
                     insetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                 } else {
                     insetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                 }
            } else {
                insetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            val window = (context as? android.app.Activity)?.window
            if (window != null) {
                val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                insetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                insetsController.isAppearanceLightStatusBars = !systemInDarkTheme
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
    

    LaunchedEffect(filePath) {
        viewModel.prepareMedia(filePath, isWebDav, serverId, fileType)
    }

    var playbackError by remember { mutableStateOf<String?>(null) }

    // Subtitle persistence
    val subtitleEnabled by viewModel.subtitleEnabled.collectAsState()

    val sessionToken = remember {
        androidx.media3.session.SessionToken(context, android.content.ComponentName(context, PlaybackService::class.java))
    }
    
    val controllerFuture = remember(sessionToken) {
        androidx.media3.session.MediaController.Builder(context, sessionToken).buildAsync()
    }
    
    var mediaController by remember { mutableStateOf<androidx.media3.session.MediaController?>(null) }
    
    DisposableEffect(controllerFuture) {
        controllerFuture.addListener({
            mediaController = controllerFuture.get().apply {
                // Initial setup if needed
                 addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        val controller = mediaController ?: return
                        // 현재 자막이 켜져 있는지 확인
                        val isSubtitleEnabled = !controller.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_TEXT)

                        // 자막이 켜진 상태에서 에러가 났다면, 자막 문제일 확률이 매우 높음
                        if (isSubtitleEnabled) {
                            Log.e("MediaPlayer", "자막 오류 감지. 자막을 끄고 복구 시도.", error)

                            // 1. 사용자에게 알림 (UI 컨텍스트 사용)
                            Toast.makeText(context, "자막 파일 오류로 인해 자막을 끕니다.", Toast.LENGTH_LONG).show()

                            // 2. 자막 트랙 비활성화
                            val params = controller.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                                .build()
                            controller.trackSelectionParameters = params
                            
                            // 3. ViewModel 상태 동기화 (UI 메뉴 갱신)
                            viewModel.toggleSubtitleEnabled(false)

                            // 4. 재생 복구 시도
                            val currentPos = controller.currentPosition // 현재 위치 저장
                            controller.prepare() // 에러 상태 초기화
                            controller.seekTo(currentPos) // 원래 위치로 이동
                            controller.play() // 재생 재개
                            
                            // 에러 화면을 띄우지 않도록 여기서 함수 종료
                            return
                        }

                        // 자막 문제가 아니라면 기존처럼 에러 화면 표시
                        playbackError = error.message
                    }
                    override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                        viewModel.updateMetadata(mediaMetadata)
                    }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        mediaItem?.let { item ->
                             val index = uiState.playlist.indexOfFirst { it.path == item.mediaId }
                             if (index != -1 && index != uiState.currentIndex) {
                                  viewModel.updateCurrentIndex(index)
                             }
                        }
                    }
                })
            }
        }, context.mainExecutor)
        onDispose {
            mediaController?.let {
                viewModel.savePosition(it.currentPosition)
            }
            androidx.media3.session.MediaController.releaseFuture(controllerFuture)
        }
    }

    LaunchedEffect(uiState.mediaUrl, uiState.playlist, uiState.authHeader, mediaController) {
        val controller = mediaController ?: return@LaunchedEffect
        if (uiState.mediaUrl != null && uiState.playlist.isNotEmpty()) {
            val mediaItems = uiState.playlist.mapIndexed { index, file ->
                val uriString = uiState.playlistUrls.getOrNull(index) ?: file.path
                val uri = if (isWebDav) {
                    android.net.Uri.parse(uriString)
                } else {
                    android.net.Uri.fromFile(java.io.File(file.path))
                }
                
                MediaItem.Builder()
                    .setUri(uri)
                    .setMediaId(file.path)
                    .setRequestMetadata(
                        androidx.media3.common.MediaItem.RequestMetadata.Builder()
                            .setExtras(android.os.Bundle().apply {
                                uiState.authHeader.forEach { (k, v) -> putString(k, v) }
                            })
                            .build()
                    )
                    .build()
            }
            
            val currentIndex = uiState.currentIndex.coerceAtLeast(0)
            
            // If the controller has a different playlist, refresh it. 
            // Otherwise, if just the index is different, seek to it.
            val currentMediaId = controller.currentMediaItem?.mediaId
            val expectedMediaId = uiState.playlist.getOrNull(currentIndex)?.path
            
            if (controller.mediaItemCount != mediaItems.size || controller.getMediaItemAt(0).mediaId != mediaItems[0].mediaId) {
                 controller.setMediaItems(mediaItems, currentIndex, uiState.savedPosition)
                 controller.prepare()
                 controller.playWhenReady = true
            } else if (currentMediaId != expectedMediaId) {
                controller.seekToDefaultPosition(currentIndex)
                controller.playWhenReady = true
            }
            
            controller.trackSelectionParameters = controller.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, !subtitleEnabled)
                .build()
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            if (!isFullScreen) {
                Column {
                    TopAppBar(
                        title = { 
                            Column {
                                Text(uiState.title ?: uiState.currentPath?.substringAfterLast('/') ?: "", color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1) 
                                if (!uiState.artist.isNullOrBlank() || !uiState.album.isNullOrBlank()) {
                                    val meta = listOfNotNull(uiState.artist, uiState.album).joinToString(" - ")
                                    Text(meta, color = Color.LightGray, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
                            }
                        },
                        actions = {
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
                                                mediaController?.let { controller ->
                                                    val params = controller.trackSelectionParameters
                                                        .buildUpon()
                                                        .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                                                        .build()
                                                    controller.trackSelectionParameters = params
                                                }
                                                viewModel.toggleSubtitleEnabled(false)
                                                showSubtitleMenu = false 
                                            }
                                        )
                                        uiState.subtitleTracks.forEach { track ->
                                            DropdownMenuItem(
                                                text = { Text(track.label) },
                                                onClick = {
                                                    mediaController?.let { controller ->
                                                        val params = controller.trackSelectionParameters
                                                            .buildUpon()
                                                            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                                                            .setPreferredTextLanguage(track.language)
                                                            .build()
                                                        controller.trackSelectionParameters = params
                                                    }
                                                    viewModel.toggleSubtitleEnabled(true)
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
                    HorizontalDivider(color = Color.White.copy(alpha = 0.3f))
                }
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
            } else if (mediaController != null) {
                // Update subtitle tracks
                LaunchedEffect(mediaController) {
                    mediaController?.addListener(object : androidx.media3.common.Player.Listener {
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
                var showControls by remember { mutableStateOf(true) }
                var isPlaying by remember { mutableStateOf(false) }

                LaunchedEffect(mediaController) {
                    mediaController?.let { controller ->
                        isPlaying = controller.isPlaying
                        val listener = object : androidx.media3.common.Player.Listener {
                            override fun onIsPlayingChanged(playing: Boolean) {
                                isPlaying = playing
                            }
                            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                                artworkData = mediaMetadata.artworkData
                            }
                        }
                        controller.addListener(listener)
                    }
                }

                // Control Visibility Timeout
                LaunchedEffect(showControls, isPlaying) {
                    if (showControls && isPlaying) {
                        kotlinx.coroutines.delay(5000)
                        showControls = false
                    }
                }

                Box(
                     modifier = Modifier
                        .fillMaxSize()
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
                             if (playerView.player != mediaController) {
                                 playerView.player = mediaController
                             }
                        },
                        modifier = Modifier
                            .fillMaxSize()
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
                                        showControls = true
                                    } else {
                                        // Toggle Fullscreen / Controls
                                        showControls = !showControls
                                        onToggleFullScreen()
                                    }
                                }
                            )
                        }
                )

                androidx.compose.animation.AnimatedVisibility(
                    visible = showControls,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.7f)
                                    )
                                )
                            )
                            .padding(bottom = 24.dp, top = 48.dp) // Added top padding for better gradient spread
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Custom Player Control Row (Prev - Play/Pause - Next)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { 
                                    mediaController?.seekToPrevious()
                                    viewModel.prev(isWebDav, serverId) 
                                    showControls = true // Reset timer
                                }) {
                                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(48.dp))
                                }
                                
                                Spacer(Modifier.width(48.dp))
                                
                                IconButton(onClick = { 
                                    mediaController?.let {
                                        if (it.isPlaying) it.pause() else it.play()
                                    }
                                    showControls = true // Reset timer
                                }) {
                                    Icon(
                                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, 
                                        contentDescription = "Play/Pause", 
                                        tint = Color.White, 
                                        modifier = Modifier.size(64.dp)
                                    )
                                }
                                
                                Spacer(Modifier.width(48.dp))
                                
                                IconButton(onClick = { 
                                    mediaController?.seekToNext()
                                    viewModel.next(isWebDav, serverId) 
                                    showControls = true // Reset timer
                                }) {
                                    Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(48.dp))
                                }
                            }
                            
                            // Progress Slider (Using PlayerControlView purely for slider/time)
                            AndroidView(
                                factory = { ctx ->
                                    androidx.media3.ui.PlayerControlView(ctx).apply {
                                        setBackgroundColor(android.graphics.Color.TRANSPARENT) // Ensure no internal background
                                        player = mediaController
                                        showTimeoutMs = 0 
                                        setShowFastForwardButton(false)
                                        setShowRewindButton(false)
                                        setShowNextButton(false) 
                                        setShowPreviousButton(false)
                                        
                                        // Hide internal play/pause and other center controls that might cast shadows
                                        try {
                                            findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play_pause)?.let { it.visibility = android.view.View.GONE }
                                            findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play)?.let { it.visibility = android.view.View.GONE }
                                            findViewById<android.view.View>(androidx.media3.ui.R.id.exo_pause)?.let { it.visibility = android.view.View.GONE }
                                            findViewById<android.view.View>(androidx.media3.ui.R.id.exo_prev)?.let { it.visibility = android.view.View.GONE }
                                            findViewById<android.view.View>(androidx.media3.ui.R.id.exo_next)?.let { it.visibility = android.view.View.GONE }
                                            findViewById<android.view.View>(androidx.media3.ui.R.id.exo_rew)?.let { it.visibility = android.view.View.GONE }
                                            findViewById<android.view.View>(androidx.media3.ui.R.id.exo_ffwd)?.let { it.visibility = android.view.View.GONE }
                                            
                                            // Some themes apply a background to the control bar itself
                                            val controlsParent = findViewById<android.view.View>(androidx.media3.ui.R.id.exo_controls_background)
                                            controlsParent?.visibility = android.view.View.GONE
                                        } catch (e: Exception) {}
                                    }
                                },
                                update = { controlView ->
                                    controlView.player = mediaController
                                    controlViewRef = controlView
                                    controlView.show()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .wrapContentHeight()
                                    .graphicsLayer {
                                        // Slight adjustment to avoid any clipping issues at the bottom
                                        translationY = -8.dp.toPx()
                                    }
                            )
                        }
                    }
                }
            }
        }
    }
}

