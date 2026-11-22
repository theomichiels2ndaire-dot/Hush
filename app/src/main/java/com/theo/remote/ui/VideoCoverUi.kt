package com.theo.remote.ui

import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Pochette vidéo : la vidéo de l'album tourne en boucle, sans son, par-dessus la pochette.
 * Elle apparaît en fondu une fois prête (sinon on garde juste la pochette).
 * MediaPlayer seul (pas VideoView) : il ne prend pas le « focus audio », donc Tidal n'est jamais coupé.
 */
@Composable
fun VideoCover(url: String, playing: Boolean, modifier: Modifier = Modifier) {
    var ready by remember(url) { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (ready) 1f else 0f, tween(500), label = "videoCover")
    val holder = remember(url) { arrayOfNulls<MediaPlayer>(1) }

    LaunchedEffect(playing, ready) {
        val mp = holder[0] ?: return@LaunchedEffect
        runCatching { if (playing) { if (!mp.isPlaying) mp.start() } else if (mp.isPlaying) mp.pause() }
    }
    DisposableEffect(url) {
        onDispose { runCatching { holder[0]?.release() }; holder[0] = null }
    }

    AndroidView(
        factory = { c ->
            TextureView(c).apply {
                isOpaque = false
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        runCatching {
                            val mp = MediaPlayer()
                            holder[0] = mp
                            mp.setSurface(Surface(st))
                            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                            mp.setVolume(0f, 0f)
                            mp.isLooping = true
                            mp.setDataSource(url)
                            mp.setOnPreparedListener { p -> p.setVolume(0f, 0f); p.start(); ready = true }
                            mp.setOnErrorListener { _, _, _ -> ready = false; true }
                            mp.prepareAsync()
                        }
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        runCatching { holder[0]?.release() }
                        holder[0] = null
                        ready = false
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
        modifier = modifier.graphicsLayer { this.alpha = alpha },
    )
}
