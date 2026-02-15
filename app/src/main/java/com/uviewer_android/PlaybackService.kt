package com.uviewer_android

import android.content.Intent
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val authHeaders = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun onCreate() {
        super.onCreate()
        
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Uviewer/1.0")
        
        val baseDataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(this, httpDataSourceFactory)
        
        lateinit var player: ExoPlayer
        
        val dataSourceFactory = DataSource.Factory {
            val delegate = baseDataSourceFactory.createDataSource()
            object : DataSource by delegate {
                override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
                    val uriStr = dataSpec.uri.toString()
                    // Try exact match, then decoded match, then maybe host-only match if needed
                    val authHeader = authHeaders[uriStr] 
                        ?: authHeaders[try { java.net.URLDecoder.decode(uriStr, "UTF-8") } catch(e: Exception) { uriStr }]
                    
                    val updatedDataSpec = if (authHeader != null) {
                        dataSpec.buildUpon()
                            .setHttpRequestHeaders(mapOf("Authorization" to authHeader))
                            .build()
                    } else {
                        dataSpec
                    }
                    return delegate.open(updatedDataSpec)
                }
            }
        }

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
            .setSeekBackIncrementMs(15000)
            .setSeekForwardIncrementMs(15000)
            .build()
            
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("PlaybackService", "ExoPlayer Error: ${error.errorCodeName} (${error.errorCode})", error)
            }
        })

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent, 
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>
                ): com.google.common.util.concurrent.ListenableFuture<MutableList<MediaItem>> {
                    mediaItems.forEach { item ->
                        val auth = item.requestMetadata.extras?.getString("Authorization")
                        if (auth != null) {
                            val uri = item.localConfiguration?.uri?.toString()
                            if (uri != null) {
                                authHeaders[uri] = auth
                            }
                        }
                    }
                    return com.google.common.util.concurrent.Futures.immediateFuture(mediaItems)
                }
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
