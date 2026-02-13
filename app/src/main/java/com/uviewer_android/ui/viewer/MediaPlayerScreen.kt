package com.uviewer_android.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.PlayerView
import com.uviewer_android.ui.AppViewModelProvider

@Composable
fun MediaPlayerScreen(
    filePath: String,
    isWebDav: Boolean,
    serverId: Int?,
    viewModel: MediaPlayerViewModel = viewModel(factory = AppViewModelProvider.Factory),
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val playerState by viewModel.playerState.collectAsState()

    // Initialize player
    LaunchedEffect(filePath) {
        viewModel.initializePlayer(filePath, isWebDav, serverId)
    }

    // Lifecycle observer to pause/release if needed
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                playerState?.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (playerState != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = playerState
                        useController = true
                    }
                },
                update = { view ->
                    view.player = playerState
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        
        // Back Button Overlay
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = Color.White // Assume dark overlay
            )
        }
    }
}
