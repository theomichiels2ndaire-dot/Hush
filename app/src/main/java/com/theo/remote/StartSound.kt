package com.theo.remote

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.exp
import kotlin.random.Random

/** Récompense « Son de démarrage » : un « shh » très court et discret à l'ouverture de Hush. */
object StartSound {
    fun play() {
        Thread {
            runCatching {
                val rate = 44_100
                val n = (rate * 0.45).toInt()
                val pcm = ShortArray(n)
                val r = Random(7)
                var lp = 0f
                var prev = 0f
                for (i in 0 until n) {
                    val t = i.toFloat() / rate
                    // souffle : bruit blanc adouci (passe-bas) puis éclairci (passe-haut) → un « shh »
                    val w = r.nextFloat() * 2f - 1f
                    lp += 0.35f * (w - lp)
                    val hp = lp - prev
                    prev = lp
                    val env = (t / 0.05f).coerceAtMost(1f) * exp(-(t - 0.05f).coerceAtLeast(0f) * 7f)
                    pcm[i] = (hp * env * 0.55f * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(n * 2)
                    .build()
                track.write(pcm, 0, n)
                track.setVolume(0.5f)
                track.play()
                Thread.sleep(600)
                track.release()
            }
        }.start()
    }
}
