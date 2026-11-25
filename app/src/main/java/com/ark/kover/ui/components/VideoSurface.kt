package com.ark.kover.ui.components

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Video playback inside dialogs.
 *
 * A [TextureView] + [MediaPlayer] is used instead of VideoView because SurfaceView
 * renders black when it lives inside a dialog/scroll container.
 */
@Composable
fun VideoSurface(
    url: String,
    modifier: Modifier = Modifier,
) {
    val player = remember(url) { MediaPlayer() }

    DisposableEffect(url) {
        onDispose { runCatching { player.release() } }
    }

    AndroidView(
        modifier = modifier.fillMaxSize().background(Color.Black),
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        runCatching {
                            player.reset()
                            player.setSurface(Surface(texture))
                            player.setDataSource(url)
                            player.isLooping = true
                            player.setOnPreparedListener { it.start() }
                            player.prepareAsync()
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        runCatching {
                            if (player.isPlaying) player.pause()
                        }
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
            }
        },
    )
}
