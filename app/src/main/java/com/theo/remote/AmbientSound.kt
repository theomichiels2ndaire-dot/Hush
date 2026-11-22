package com.theo.remote

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Sons d'ambiance pour dormir (pluie, vagues, bruits doux), fabriqués en direct par Hush
 * et joués par-dessus ta musique, sans la couper.
 */
object AmbientSound {
    enum class Kind(val label: String) { Rain("Pluie"), Waves("Vagues"), Pink("Bruit rose"), Brown("Bruit brun"), White("Bruit blanc") }

    private val _playing = MutableStateFlow<Kind?>(null)
    val playing: StateFlow<Kind?> = _playing
    private val _volume = MutableStateFlow(0.35f)
    val volume: StateFlow<Float> = _volume

    @Volatile private var kind: Kind? = null
    @Volatile private var fadeOut = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    fun play(k: Kind) {
        kind = k
        _playing.value = k
        fadeOut = false
        if (thread?.isAlive == true) return
        val rate = 44100
        val buf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(8192)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(buf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.setVolume(_volume.value)
        t.play()
        thread = Thread {
            val rnd = Random(System.nanoTime())
            val out = ShortArray(2048)
            var b0 = 0.0; var b1 = 0.0; var b2 = 0.0; var b3 = 0.0; var b4 = 0.0; var b5 = 0.0; var b6 = 0.0
            var brown = 0.0; var lp = 0.0; var drop = 0.0
            var phase = 0.0
            var gain = 0.0 // fondu d'entrée / de sortie
            while (true) {
                val k = kind ?: break
                for (i in out.indices) {
                    val w = rnd.nextDouble() * 2 - 1
                    // bruit rose (filtre de Paul Kellet)
                    b0 = 0.99886 * b0 + w * 0.0555179; b1 = 0.99332 * b1 + w * 0.0750759; b2 = 0.96900 * b2 + w * 0.1538520
                    b3 = 0.86650 * b3 + w * 0.3104856; b4 = 0.55000 * b4 + w * 0.5329522; b5 = -0.7616 * b5 - w * 0.0168980
                    val pink = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362) * 0.11
                    b6 = w * 0.115926
                    brown = (brown + 0.02 * w) / 1.02
                    val v = when (k) {
                        Kind.White -> w * 0.35
                        Kind.Pink -> pink
                        Kind.Brown -> brown * 3.2
                        Kind.Waves -> {
                            phase += 2 * PI / (rate * 9.0)
                            val swell = 0.25 + 0.75 * (0.5 + 0.5 * sin(phase)) * (0.5 + 0.5 * sin(phase * 0.37 + 1.3))
                            lp += 0.08 * (pink - lp)
                            (brown * 2.2 + lp * 0.9) * swell
                        }
                        Kind.Rain -> {
                            // pluie : bruit rose adouci + petites gouttes au hasard
                            lp += 0.35 * (pink - lp)
                            if (rnd.nextDouble() < 0.0009) drop = 0.5 + rnd.nextDouble() * 0.5
                            drop *= 0.985
                            lp * 0.9 + drop * w * 0.6
                        }
                    }
                    val target = if (fadeOut) 0.0 else 1.0
                    gain += (target - gain) * 0.00004
                    out[i] = (v * gain * 22000).coerceIn(-32767.0, 32767.0).toInt().toShort()
                }
                runCatching { t.write(out, 0, out.size) }
                if (fadeOut && gain < 0.002) break
            }
            runCatching { t.stop(); t.release() }
            if (track === t) track = null
            kind = null
            _playing.value = null
        }.apply { isDaemon = true; name = "hush-ambient"; start() }
    }

    fun setVolume(v: Float) {
        _volume.value = v.coerceIn(0f, 1f)
        runCatching { track?.setVolume(_volume.value) }
    }

    /** Arrêt en fondu (2-3 s). */
    fun stop() {
        if (kind == null) return
        fadeOut = true
        _playing.value = null
    }
}
