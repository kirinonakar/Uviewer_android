package com.uviewer_android.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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

    val exoPlayer = remember(uiState.mediaUrl, uiState.authHeader) {
        if (uiState.mediaUrl != null) {
            ExoPlayer.Builder(context).build().apply {
                val mediaItem = MediaItem.fromUri(uiState.mediaUrl!!)
                if (isWebDav) {
                    val dataSourceFactory = DefaultHttpDataSource.Factory()
                    dataSourceFactory.setDefaultRequestProperties(uiState.authHeader)
                    val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(mediaItem)
                    setMediaSource(mediaSource)
                } else {
                    setMediaItem(mediaItem)
                }
                prepare()
                playWhenReady = true
            }
        } else null
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer?.release()
        }
    }

    Scaffold(
        topBar = {
            if (!isFullScreen) {
                TopAppBar(
                    title = { Text(filePath.substringAfterLast('/'), color = Color.White) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
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
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator()
            } else if (uiState.error != null) {
                Text(stringResource(R.string.error_fmt, uiState.error!!))
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
                    update = {
                        if (fileType == FileEntry.FileType.AUDIO) {
                            it.showController()
                        }
                    }
                )
            }
        }
    }
}
