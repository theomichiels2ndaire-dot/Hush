package com.theo.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

/**
 * Pochette animée : écoute le niveau du son qui sort du téléphone (si Android le permet)
 * pour faire « respirer » la pochette au rythme de la musique.
 * Sans accès (pas de micro autorisé, téléphone qui refuse), l'écran fait une respiration douce.
 */
object CoverPulse {
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    /** true si on reçoit vraiment le son (sinon : respiration douce). */
    private val _live = MutableStateFlow(false)
    val live: StateFlow<Boolean> = _live

    private var viz: Visualizer? = null
    private var users = 0
    private var lastSignalAt = 0L

    fun start(context: Context) {
        users++
        if (viz != null) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val v = Visualizer(0)
            v.captureSize = Visualizer.getCaptureSizeRange()[0].coerceAtLeast(128)
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                    val w = waveform ?: return
                    var sum = 0.0
                    for (b in w) { val x = (b.toInt() and 0xFF) - 128; sum += x * x }
                    val rms = (sqrt(sum / w.size) / 64.0).toFloat().coerceIn(0f, 1f)
                    val now = SystemClock.elapsedRealtime()
                    if (rms > 0.02f) lastSignalAt = now
                    _live.value = now - lastSignalAt < 3000
                    // monte vite, redescend doucement
                    val prev = _level.value
                    _level.value = if (rms > prev) rms else prev * 0.82f + rms * 0.18f
                }

                override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {}
            }, (Visualizer.getMaxCaptureRate() / 2).coerceAtLeast(10_000), true, false)
            v.enabled = true
            viz = v
        }
    }

    fun stop() {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return
        runCatching { viz?.enabled = false; viz?.release() }
        viz = null
        _level.value = 0f
        _live.value = false
    }
}
