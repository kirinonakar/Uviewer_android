package com.uviewer_android.ui

import androidx.compose.ui.res.stringResource
import com.uviewer_android.R

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import androidx.navigation.NavType
import androidx.navigation.navArgument

import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star

@Composable
fun MainScreen(
    activity: com.uviewer_android.MainActivity? = null, 
    initialIntentPath: String? = null,
    shouldResume: Boolean = false,
    resumeSpecificPath: String? = null,
    onHandledResume: () -> Unit = {}
) {
    val navController = rememberNavController()
    val libraryViewModel: com.uviewer_android.ui.library.LibraryViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = com.uviewer_android.ui.AppViewModelProvider.Factory)
    val libraryUiState by libraryViewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    var isFullScreen by remember { androidx.compose.runtime.mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(initialIntentPath) {
        if (initialIntentPath != null) {
            val fileName = java.io.File(initialIntentPath).name.lowercase()
            val type = when {
                fileName.endsWith(".pdf") -> "PDF"
                fileName.endsWith(".epub") -> "EPUB"
                fileName.endsWith(".zip") || fileName.endsWith(".cbz") -> "COMPRESSED"
                fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") || fileName.endsWith(".png") || fileName.endsWith(".gif") || fileName.endsWith(".webp") -> "IMAGE"
                fileName.endsWith(".mp3") || fileName.endsWith(".m4a") || fileName.endsWith(".wav") || fileName.endsWith(".flac") -> "AUDIO"
                fileName.endsWith(".mp4") || fileName.endsWith(".mkv") || fileName.endsWith(".avi") -> "VIDEO"
                else -> "TEXT"
            }
            val encodedPath = android.net.Uri.encode(initialIntentPath, null)
            val route = "viewer?path=$encodedPath&type=$type&isWebDav=false&serverId=-1&position=-1"
            navController.navigate(route)
        }
    }

    // Auto-resume if flag is set
    androidx.compose.runtime.LaunchedEffect(shouldResume, resumeSpecificPath, libraryUiState.mostRecentFile?.path) {
        if (shouldResume) {
            // Priority 1: Specific path from notification (currently playing)
            if (resumeSpecificPath != null) {
                val currentEntry = navController.currentBackStackEntry
                val currentPath = currentEntry?.arguments?.getString("path")
                
                if (currentPath != resumeSpecificPath) {
                    val fileName = resumeSpecificPath.lowercase()
                    val type = when {
                        fileName.endsWith(".mp3") || fileName.endsWith(".m4a") || fileName.endsWith(".wav") || fileName.endsWith(".flac") -> "AUDIO"
                        fileName.endsWith(".mp4") || fileName.endsWith(".mkv") || fileName.endsWith(".avi") -> "VIDEO"
                        else -> "file"
                    }
                    
                    val encodedPath = android.net.Uri.encode(resumeSpecificPath, null)
                    // Position -1 to trigger auto-resume in viewer
                    val route = "viewer?path=$encodedPath&type=$type&isWebDav=false&serverId=-1&position=-1"
                    navController.navigate(route) {
                        launchSingleTop = true
                    }
                }
                onHandledResume()
            }
            // Priority 2: Database recent file
            else if (libraryUiState.mostRecentFile != null) {
                val recent = libraryUiState.mostRecentFile!!
                val currentEntry = navController.currentBackStackEntry
                val currentPath = currentEntry?.arguments?.getString("path")
                
                if (currentPath != recent.path) {
                    val encodedPath = android.net.Uri.encode(recent.path, null)
                    val route = "viewer?path=$encodedPath&type=${recent.type}&isWebDav=${recent.isWebDav}&serverId=${recent.serverId ?: -1}&position=${recent.pageIndex}"
                    navController.navigate(route) {
                        launchSingleTop = true
                    }
                }
                onHandledResume()
            }
        }
    }

    val items = listOf(
        Screen.Resume("Resume"),
        Screen.Library(stringResource(R.string.nav_library)),
        Screen.Favorites(stringResource(R.string.nav_favorites)),
        Screen.Recent(stringResource(R.string.nav_recent)),
        Screen.Settings(stringResource(R.string.nav_settings))
    )

    Scaffold(
        bottomBar = {
            if (!isFullScreen) {
                NavigationBar {
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentDestination = navBackStackEntry?.destination
                    items.forEach { screen ->
                        val title = if (screen is Screen.Resume) "Resume" else when(screen) {
                            is Screen.Library -> screen.title
                            is Screen.Favorites -> screen.title
                            is Screen.Recent -> screen.title
                            is Screen.Settings -> screen.title
                            else -> ""
                        }
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = null) },
                            label = { Text(title) },
                            selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                            onClick = {
                                if (screen is Screen.Resume) {
                                    val recent = libraryUiState.mostRecentFile
                                    if (recent != null) {
                                        val encodedPath = android.net.Uri.encode(recent.path, null)
                                        // Use pageIndex for position. For Media, it might be seek position? 
                                        // Current ViewerScreen handles position as initialLine or initialIndex.
                                        val route = "viewer?path=$encodedPath&type=${recent.type}&isWebDav=${recent.isWebDav}&serverId=${recent.serverId ?: -1}&position=${recent.pageIndex}"
                                        navController.navigate(route) {
                                            launchSingleTop = true
                                        }
                                    }
                                } else if (screen is Screen.Library) {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                } else {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        
                                        // Reset state for Favorites/Recent/Settings (User Request)
                                        if (screen is Screen.Favorites || screen is Screen.Recent || screen is Screen.Settings) {
                                            restoreState = false
                                        } else {
                                            restoreState = true
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "library",
            modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
            enterTransition = { androidx.compose.animation.EnterTransition.None },
            exitTransition = { androidx.compose.animation.ExitTransition.None },
            popEnterTransition = { androidx.compose.animation.EnterTransition.None },
            popExitTransition = { androidx.compose.animation.ExitTransition.None }
        ) {
            composable(
                route = "library?path={path}&serverId={serverId}",
                arguments = listOf(
                    navArgument("path") { type = NavType.StringType; defaultValue = "" },
                    navArgument("serverId") { type = NavType.IntType; defaultValue = -1 }
                )
            ) { backStackEntry ->
                // Ensure isFullScreen is false when in Library
                androidx.compose.runtime.LaunchedEffect(Unit) { isFullScreen = false }
                
                val path = backStackEntry.arguments?.getString("path")
                val serverId = backStackEntry.arguments?.getInt("serverId")
                com.uviewer_android.ui.library.LibraryScreen(
                    initialPath = path,
                    initialServerId = serverId,
                    viewModel = libraryViewModel,
                    onNavigateToViewer = { entry ->
                        val encodedPath = android.net.Uri.encode(entry.path, null)
                        // Note: For library entry, we don't have pageIndex easily here unless we query DB, 
                        // but DocumentViewerViewModel will fallback to DB if position is -1.
                        val route = "viewer?path=$encodedPath&type=${entry.type}&isWebDav=${entry.isWebDav}&serverId=${entry.serverId ?: -1}&position=-1"
                        navController.navigate(route)
                    }
                ) 
            }
            
            composable(Screen.Favorites("").route) {
                androidx.compose.runtime.LaunchedEffect(Unit) { isFullScreen = false }
                com.uviewer_android.ui.favorites.FavoritesScreen(
                    onNavigateToViewer = { item ->
                        if (item.type.equals("FOLDER", ignoreCase = true)) {
                            val encodedPath = android.net.Uri.encode(item.path, null)
                            val route = "library?path=$encodedPath&serverId=${item.serverId ?: -1}"
                            navController.navigate(route)
                        } else {
                            val encodedPath = android.net.Uri.encode(item.path, null)
                            val route = "viewer?path=$encodedPath&type=${item.type}&isWebDav=${item.isWebDav}&serverId=${item.serverId ?: -1}&position=${item.position}"
                            navController.navigate(route)
                        }
                    }
                )
            }
            composable(Screen.Recent("").route) {
                androidx.compose.runtime.LaunchedEffect(Unit) { isFullScreen = false }
                com.uviewer_android.ui.recent.RecentFilesScreen(
                    onNavigateToViewer = { file ->
                        if (file.type.equals("FOLDER", ignoreCase = true)) {
                             val encodedPath = android.net.Uri.encode(file.path, null)
                             val route = "library?path=$encodedPath&serverId=${file.serverId ?: -1}"
                             navController.navigate(route)
                        } else {
                            val encodedPath = android.net.Uri.encode(file.path, null)
                            val route = "viewer?path=$encodedPath&type=${file.type}&isWebDav=${file.isWebDav}&serverId=${file.serverId ?: -1}&position=${file.pageIndex}"
                            navController.navigate(route)
                        }
                    }
                )
            }
            composable(Screen.Settings("").route) {
                androidx.compose.runtime.LaunchedEffect(Unit) { isFullScreen = false }
                com.uviewer_android.ui.settings.SettingsScreen()
            }
            
             // Viewer Route
            composable(
                route = "viewer?path={path}&type={type}&isWebDav={isWebDav}&serverId={serverId}&position={position}",
                arguments = listOf(
                    navArgument("path") { type = NavType.StringType; defaultValue = "" },
                    navArgument("type") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("isWebDav") { type = NavType.BoolType; defaultValue = false },
                    navArgument("serverId") { type = NavType.IntType; defaultValue = -1 },
                    navArgument("position") { type = NavType.IntType; defaultValue = -1 }
                )
            ) { backStackEntry ->
                val filePath = backStackEntry.arguments?.getString("path")?.takeIf { it.isNotEmpty() } ?: return@composable
                val type = backStackEntry.arguments?.getString("type")
                val isWebDav = backStackEntry.arguments?.getBoolean("isWebDav") ?: false
                val serverId = backStackEntry.arguments?.getInt("serverId").takeIf { it != -1 }
                val position = backStackEntry.arguments?.getInt("position").takeIf { it != -1 }
                
                com.uviewer_android.ui.viewer.ViewerScreen(
                    filePath = filePath, 
                    fileType = type,
                    isWebDav = isWebDav,
                    serverId = serverId,
                    initialPosition = position,
                    onBack = { 
                        // Use java.io.File to get parent safely, handling different path separators
                        val file = java.io.File(filePath)
                        val parent = file.parent ?: "/"
                        val encodedParentPath = android.net.Uri.encode(parent, null)
                        val route = "library?path=$encodedParentPath&serverId=${serverId ?: -1}"
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id)
                            launchSingleTop = true
                        }
                    },
                    onNavigateToNext = {
                        scope.launch {
                             val nextFile = libraryViewModel.getNextFile(filePath, isWebDav, serverId)
                             if (nextFile != null) {
                                 val encodedPath = android.net.Uri.encode(nextFile.path, null)
                                 val route = "viewer?path=$encodedPath&type=${nextFile.type.name}&isWebDav=${nextFile.isWebDav}&serverId=${nextFile.serverId ?: -1}&position=-1"
                                 navController.navigate(route) {
                                     popUpTo("viewer?path={path}&type={type}&isWebDav={isWebDav}&serverId={serverId}&position={position}") { inclusive = true }
                                 }
                             }
                        }
                    },
                    onNavigateToPrev = {
                        scope.launch {
                             val prevFile = libraryViewModel.getPrevFile(filePath, isWebDav, serverId)
                             if (prevFile != null) {
                                 val encodedPath = android.net.Uri.encode(prevFile.path, null)
                                 val route = "viewer?path=$encodedPath&type=${prevFile.type.name}&isWebDav=${prevFile.isWebDav}&serverId=${prevFile.serverId ?: -1}&position=-1"
                                 navController.navigate(route) {
                                     popUpTo("viewer?path={path}&type={type}&isWebDav={isWebDav}&serverId={serverId}&position={position}") { inclusive = true }
                                 }
                             }
                        }
                    },
                    isFullScreen = isFullScreen,
                    onToggleFullScreen = { isFullScreen = !isFullScreen },
                    activity = activity
                )
            }
        }
    }
}

sealed class Screen(val route: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    data class Resume(val title: String) : Screen("resume", Icons.Filled.PlayArrow)
    data class Library(val title: String) : Screen("library", Icons.Filled.Folder)
    data class Favorites(val title: String) : Screen("favorites", Icons.Filled.Star)
    data class Recent(val title: String) : Screen("recent", Icons.Filled.History)
    data class Settings(val title: String) : Screen("settings", Icons.Filled.Settings)
}

// This is a placeholder for the @Database annotation, as it was not present in the original file.
// Assuming it would be in a separate file like AppDatabase.kt, but for the purpose of
// demonstrating the version change, it's included here as a comment.
/*
@Database(entities = [FavoriteItem::class, RecentFile::class, WebDavServer::class, Bookmark::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    // ...
}
*/

// Assuming LibraryViewModel is defined elsewhere, this demonstrates the init block addition.
// This code cannot be directly inserted into MainScreen.kt without breaking syntax.
/*
class LibraryViewModel(...) : ViewModel() {
    init {
        // Force initial load of Local root directory as soon as ViewModel is created
        try {
            val root = android.os.Environment.getExternalStorageDirectory().absolutePath
            loadFiles(root)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    // ... rest of ViewModel
}
*/
