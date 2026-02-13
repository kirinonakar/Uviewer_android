package com.uviewer_android.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
        Box(
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
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = true
                            if (fileType == FileEntry.FileType.AUDIO) {
                                hideController() // Initially hide for audio
                            }
                        }
                    },
                    update = { playerView ->
                        playerView.player = exoPlayer
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(rotationZ = uiState.rotation)
                        .padding(if (uiState.rotation % 180f != 0f) 50.dp else 0.dp) // Avoid cropping during rotation
                )
                
                // Overlay for center tap
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 100.dp, vertical = 100.dp)
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
