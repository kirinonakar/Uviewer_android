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
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val authHeaders = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun onCreate() {
        super.onCreate()
        
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Uviewer/1.0")
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
        
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

        val mediaCodecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, audioSinkSideChannel ->
            // 기본 디코더 리스트 가져오기
            val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, audioSinkSideChannel)
            
            // AVC(H.264) 영상인 경우 소프트웨어 디코더를 우선순위로 변경
            if (mimeType == "video/avc") {
                val (software, hardware) = decoders.partition { info ->
                    val name = info.name.lowercase()
                    name.contains("google") || name.contains("android") || name.contains("sw")
                }
                // 소프트웨어 디코더를 리스트 앞쪽으로 배치하여 우선 사용하게 함
                software + hardware
            } else {
                decoders
            }
        }

        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(this)
            .setMediaCodecSelector(mediaCodecSelector)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)
            .setSubtitleParserFactory(androidx.media3.extractor.text.SubtitleParser.Factory.UNSUPPORTED)
            .setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(3))

        player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setSeekBackIncrementMs(15000)
            .setSeekForwardIncrementMs(15000)
            .build()
            
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("PlaybackService", "ExoPlayer Error: ${error.errorCodeName} (${error.errorCode})", error)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                super.onMediaItemTransition(mediaItem, reason)
                updateSessionIntent(mediaItem)
            }
        })

        mediaSession = MediaSession.Builder(this, player)
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
            
        updateSessionIntent(player.currentMediaItem)
    }

    private fun updateSessionIntent(mediaItem: MediaItem?) {
        val path = mediaItem?.localConfiguration?.uri?.toString() ?: return
        
        // Remove file:// prefix if present
        val cleanPath = if (path.startsWith("file://")) path.substring(7) else path

        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("action", "resume")
            putExtra("playing_path", cleanPath)
        }
        
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent, 
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        mediaSession?.setSessionActivity(pendingIntent)
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
