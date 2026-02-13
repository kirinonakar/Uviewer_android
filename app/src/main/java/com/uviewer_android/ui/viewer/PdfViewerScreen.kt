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

import androidx.lifecycle.viewmodel.compose.viewModel
import com.uviewer_android.ui.AppViewModelProvider

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
    val uiState by viewModel.uiState.collectAsState()
    var renderer: PdfRenderer? by remember { mutableStateOf(null) }
    var fileDescriptor: ParcelFileDescriptor? by remember { mutableStateOf(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    
    val listState = rememberLazyListState()
    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex } }

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

    DisposableEffect(Unit) {
        onDispose {
            try {
                renderer?.close()
                fileDescriptor?.close()
            } catch (e: Exception) {}
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
                        }) {
                            Icon(Icons.Default.Bookmark, contentDescription = "Bookmark", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black.copy(alpha = 0.5f),
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.DarkGray)
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
    ) {
        LazyColumn(
            state = listState,
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
                PdfPage(renderer, index, onToggleFullScreen)
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
fun PdfPage(
    renderer: PdfRenderer,
    index: Int,
    onTap: () -> Unit
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
            .fillMaxWidth()
            .pointerInput(Unit) {
                // Since this captures taps, it might conflict with parent zoom if not careful
                // But LazyColumn consumes scroll. 
                // We want to detect tap to toggle fullscreen.
                // We use a simple clickable modifier on the Image or Box?
                // pointerInput with detectTapGestures is safer.
                detectTapGestures(
                    onTap = { onTap() }
                )
            },
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
