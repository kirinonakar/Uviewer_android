package com.uviewer_android.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.uviewer_android.UviewerApplication
import com.uviewer_android.ui.library.LibraryViewModel

object AppViewModelProvider {
    val Factory = viewModelFactory {
        initializer {
            val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as UviewerApplication)
            LibraryViewModel(
                fileRepository = app.container.fileRepository,
                webDavRepository = app.container.webDavRepository,
                favoriteDao = app.container.database.favoriteDao(),
                webDavServerDao = app.container.database.webDavServerDao(),
                recentFileDao = app.container.database.recentFileDao()
            )
        }
        initializer {
            val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as UviewerApplication)
            com.uviewer_android.ui.viewer.ImageViewerViewModel(
                application = app,
                fileRepository = app.container.fileRepository,
                webDavRepository = app.container.webDavRepository,
                recentFileDao = app.container.database.recentFileDao(),
                bookmarkDao = app.container.database.bookmarkDao(),
                favoriteDao = app.container.database.favoriteDao(),
                userPreferencesRepository = app.container.userPreferencesRepository
            )
        }
        initializer {
            val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as UviewerApplication)
            com.uviewer_android.ui.viewer.DocumentViewerViewModel(
                application = app,
                fileRepository = app.container.fileRepository,
                webDavRepository = app.container.webDavRepository,
                recentFileDao = app.container.database.recentFileDao(),
                bookmarkDao = app.container.database.bookmarkDao(),
                favoriteDao = app.container.database.favoriteDao(),
                userPreferencesRepository = app.container.userPreferencesRepository
            )
        }
        initializer {
            val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as UviewerApplication)
            com.uviewer_android.ui.viewer.MediaPlayerViewModel(
                application = app,
                fileRepository = app.container.fileRepository,
                webDavRepository = app.container.webDavRepository,
                recentFileDao = app.container.database.recentFileDao(),
                userPreferencesRepository = app.container.userPreferencesRepository
            )
        }
        initializer {
            val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as UviewerApplication)
            com.uviewer_android.ui.favorites.FavoritesViewModel(
                favoriteDao = app.container.database.favoriteDao()
            )
        }
        initializer {
            val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as UviewerApplication)
            com.uviewer_android.ui.recent.RecentFilesViewModel(
                recentFileDao = app.container.database.recentFileDao()
            )
        }
        initializer {
            val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as UviewerApplication)
            com.uviewer_android.ui.settings.SettingsViewModel(
                webDavServerDao = app.container.database.webDavServerDao(),
                credentialsManager = app.container.credentialsManager,
                userPreferencesRepository = app.container.userPreferencesRepository
            )
        }
        initializer {
            val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as UviewerApplication)
            com.uviewer_android.ui.viewer.PdfViewerViewModel(
                application = app,
                webDavRepository = app.container.webDavRepository,
                recentFileDao = app.container.database.recentFileDao(),
                bookmarkDao = app.container.database.bookmarkDao()
            )
        }
    }
}

/**
 * Extension function to queries for [Application] object and returns an instance of
 * [UviewerApplication].
 */
fun CreationExtras.uviewerApplication(): UviewerApplication =
    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as UviewerApplication)
