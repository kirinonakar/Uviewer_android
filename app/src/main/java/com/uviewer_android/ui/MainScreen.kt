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

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import androidx.navigation.NavType
import androidx.navigation.navArgument

import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val libraryViewModel: com.uviewer_android.ui.library.LibraryViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = com.uviewer_android.ui.AppViewModelProvider.Factory)
    val libraryUiState by libraryViewModel.uiState.collectAsState()
    var isFullScreen by remember { androidx.compose.runtime.mutableStateOf(false) }

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
                                        val encodedPath = URLEncoder.encode(recent.path, StandardCharsets.UTF_8.toString()).replace("+", "%20")
                                        val route = "viewer/$encodedPath?type=${recent.type}&isWebDav=${recent.isWebDav}&serverId=${recent.serverId ?: -1}"
                                        navController.navigate(route)
                                    }
                                } else {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
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
            startDestination = Screen.Library("").route,
            modifier = Modifier.padding(innerPadding)
        ) {
            // ... (other composables unchanged, need to check if I can keep them same or if I need to copy-paste all)
            // To be safe and concise, I will target the Viewer route specifically if I can, or replace the whole block.
            // Since I added `isFullScreen` at top, I need to update ViewerScreen call.
            
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
                        val encodedPath = URLEncoder.encode(entry.path, StandardCharsets.UTF_8.toString()).replace("+", "%20")
                        val route = "viewer/$encodedPath?type=${entry.type}&isWebDav=${entry.isWebDav}&serverId=${entry.serverId ?: -1}"
                        navController.navigate(route)
                    }
                ) 
            }
            
            // ... Favorites and Recent also need isFullScreen = false
            composable(Screen.Favorites("").route) {
                androidx.compose.runtime.LaunchedEffect(Unit) { isFullScreen = false }
                com.uviewer_android.ui.favorites.FavoritesScreen(
                    onNavigateToViewer = { item ->
                        if (item.type == "FOLDER") {
                            val encodedPath = URLEncoder.encode(item.path, StandardCharsets.UTF_8.toString()).replace("+", "%20")
                            val route = "library?path=$encodedPath&serverId=${item.serverId ?: -1}"
                            navController.navigate(route)
                        } else {
                            val encodedPath = URLEncoder.encode(item.path, StandardCharsets.UTF_8.toString()).replace("+", "%20")
                            val route = "viewer/$encodedPath?type=${item.type}&isWebDav=${item.isWebDav}&serverId=${item.serverId ?: -1}"
                            navController.navigate(route)
                        }
                    }
                )
            }
            composable(Screen.Recent("").route) {
                androidx.compose.runtime.LaunchedEffect(Unit) { isFullScreen = false }
                com.uviewer_android.ui.recent.RecentFilesScreen(
                    onNavigateToViewer = { file ->
                        if (file.type == "FOLDER") {
                             val encodedPath = URLEncoder.encode(file.path, StandardCharsets.UTF_8.toString()).replace("+", "%20")
                             val route = "library?path=$encodedPath&serverId=${file.serverId ?: -1}"
                             navController.navigate(route)
                        } else {
                            val encodedPath = URLEncoder.encode(file.path, StandardCharsets.UTF_8.toString()).replace("+", "%20")
                            val route = "viewer/$encodedPath?type=${file.type}&isWebDav=${file.isWebDav}&serverId=${file.serverId ?: -1}"
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
                route = "viewer/{filePath}?type={type}&isWebDav={isWebDav}&serverId={serverId}",
                arguments = listOf(
                    navArgument("filePath") { type = NavType.StringType },
                    navArgument("type") { type = NavType.StringType; nullable = true },
                    navArgument("isWebDav") { type = NavType.BoolType; defaultValue = false },
                    navArgument("serverId") { type = NavType.IntType; defaultValue = -1 }
                )
            ) { backStackEntry ->
                val filePath = backStackEntry.arguments?.getString("filePath") ?: return@composable
                val type = backStackEntry.arguments?.getString("type")
                val isWebDav = backStackEntry.arguments?.getBoolean("isWebDav") ?: false
                val serverId = backStackEntry.arguments?.getInt("serverId").takeIf { it != -1 }
                
                com.uviewer_android.ui.viewer.ViewerScreen(
                    filePath = filePath, 
                    fileType = type,
                    isWebDav = isWebDav,
                    serverId = serverId,
                    onBack = { navController.popBackStack() },
                    isFullScreen = isFullScreen,
                    onToggleFullScreen = { isFullScreen = !isFullScreen }
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
