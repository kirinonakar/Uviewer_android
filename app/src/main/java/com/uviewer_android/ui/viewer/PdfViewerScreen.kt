package com.uviewer_android.ui.viewer

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.uviewer_android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.uviewer_android.MainActivity

import androidx.lifecycle.viewmodel.compose.viewModel
import com.uviewer_android.ui.AppViewModelProvider

import androidx.activity.compose.BackHandler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    filePath: String,
    isWebDav: Boolean,
    serverId: Int?,
    viewModel: PdfViewerViewModel = viewModel(factory = AppViewModelProvider.Factory),
    onBack: () -> Unit = {},
    isFullScreen: Boolean = false,
    onToggleFullScreen: () -> Unit = {}
) {
    BackHandler { onBack() }
    val uiState by viewModel.uiState.collectAsState()
    var renderer: PdfRenderer? by remember { mutableStateOf(null) }
    var fileDescriptor: ParcelFileDescriptor? by remember { mutableStateOf(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    
    val listState = rememberLazyListState()
    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    val activity = context as? MainActivity

    LaunchedEffect(filePath) {
        viewModel.loadPdf(filePath, isWebDav, serverId)
    }

    // Initialize Renderer when local file is ready
    LaunchedEffect(uiState.localFilePath) {
        if (uiState.localFilePath != null) {
            withContext(Dispatchers.IO) {
                try {
                    val file = File(uiState.localFilePath!!)
                    val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    fileDescriptor = fd
                    val pdfRenderer = PdfRenderer(fd)
                    renderer = pdfRenderer
                    pageCount = pdfRenderer.pageCount
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // Status Bar Logic
    val isLightAppTheme = MaterialTheme.colorScheme.background.luminance() > 0.5f
    
    // When UI is shown (!isFullScreen), icons follow the app's current theme (Light/Dark).
    // When UI is hidden (isFullScreen), icons follow the document background.
    val useLightStatusBar = if (!isFullScreen) {
        isLightAppTheme
    } else {
        false // PDF full screen background is black
    }
    
    DisposableEffect(Unit) {
        onDispose {
            try {
                renderer?.close()
                fileDescriptor?.close()
            } catch (e: Exception) {}

            val window = (context as? android.app.Activity)?.window
            if (window != null) {
                val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                insetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                insetsController.isAppearanceLightStatusBars = isLightAppTheme
            }
        }
    }

    LaunchedEffect(useLightStatusBar, isFullScreen) {
        val window = (context as? android.app.Activity)?.window
        if (window != null) {
            val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            insetsController.isAppearanceLightStatusBars = useLightStatusBar
            if (isFullScreen) {
                // Hide navigation, keep status
                insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
                insetsController.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
                insetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                insetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Hardware Volume Button Support
    LaunchedEffect(activity, renderer) {
        if (activity != null && renderer != null) {
            activity.keyEvents.collect { keyCode: Int ->
                if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
                    val target = (listState.firstVisibleItemIndex - 1).coerceAtLeast(0)
                    scope.launch { listState.animateScrollToItem(target) }
                } else if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
                    val target = (listState.firstVisibleItemIndex + 1).coerceAtMost(pageCount - 1)
                    scope.launch { listState.animateScrollToItem(target) }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            if (!isFullScreen) {
                TopAppBar(
                    title = { Text(File(filePath).name, maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            viewModel.toggleBookmark(filePath, currentPage, isWebDav, serverId)
                            android.widget.Toast.makeText(context, "Bookmark Saved: Page ${currentPage + 1}", android.widget.Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.Bookmark, contentDescription = "Bookmark")
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(if (isFullScreen) Color.Black else MaterialTheme.colorScheme.surface)
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (uiState.error != null) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Error: ${uiState.error}",
                        color = Color.Red
                    )
                    Button(onClick = onBack) {
                        Text(stringResource(R.string.back))
                    }
                }
            } else if (renderer != null) {
                PdfList(renderer!!, pageCount, listState, onToggleFullScreen)
            }
        }
    }
}

@Composable
fun PdfList(
    renderer: PdfRenderer,
    pageCount: Int,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onToggleFullScreen: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 3f)
                    if (scale > 1f) {
                        val maxOffsetX = (size.width * (scale - 1)) / 2
                        val maxOffsetY = (size.height * (scale - 1)) / 2
                        offsetX = (offsetX + pan.x).coerceIn(-maxOffsetX, maxOffsetX)
                        offsetY = (offsetY + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            }
            .pointerInput(pageCount) {
                detectTapGestures(
                    onTap = { offset ->
                        val width = size.width
                        val thirdWidth = width / 3f
                        when {
                            offset.x < thirdWidth -> {
                                // Left tap -> Prev
                                val target = (listState.firstVisibleItemIndex - 1).coerceAtLeast(0)
                                scope.launch { listState.animateScrollToItem(target) }
                            }
                            offset.x > thirdWidth * 2 -> {
                                // Right tap -> Next
                                val target = (listState.firstVisibleItemIndex + 1).coerceAtMost(pageCount - 1)
                                scope.launch { listState.animateScrollToItem(target) }
                            }
                            else -> {
                                // Center tap -> Toggle UI
                                onToggleFullScreen()
                            }
                        }
                    }
                )
            }
    ) {
        LazyColumn(
            state = listState,
            userScrollEnabled = scale == 1f,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                )
        ) {
            items(pageCount) { index ->
                PdfPage(renderer, index)
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
fun PdfPage(
    renderer: PdfRenderer,
    index: Int
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val scope = rememberCoroutineScope()
    // Use the screen width/density to determine bitmap size
    val context = LocalContext.current
    val displayMetrics = context.resources.displayMetrics
    val screenWidth = displayMetrics.widthPixels

    LaunchedEffect(index) {
        withContext(Dispatchers.IO) {
            try {
                synchronized(renderer) {
                    val page = renderer.openPage(index)
                    // Calculate aspect ratio
                    val width = page.width
                    val height = page.height
                    val ratio = width.toFloat() / height.toFloat()
                    
                    // Render to screen width, maintain aspect ratio
                    val renderWidth = screenWidth
                    val renderHeight = (renderWidth / ratio).toInt()
                    
                    val bmp = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    bitmap = bmp
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "Page ${index + 1}",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().background(Color.White)
            )
        } else {
             Box(modifier = Modifier.fillMaxWidth().height(200.dp).background(Color.White), contentAlignment = Alignment.Center) {
                 CircularProgressIndicator()
             }
        }
    }
}
