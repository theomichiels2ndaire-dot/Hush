package com.theo.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Virtualizer
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.cos
import kotlin.math.log10

/**
 * Basses et son 8D, appliqués sur le son de Tidal.
 * On se branche sur la session audio de Tidal quand il l'annonce, sinon sur la sortie générale du téléphone.
 */
object AudioFx {
    enum class Status { Off, Tidal, Global, Blocked }

    private val _bass = MutableStateFlow(0)
    /** 0 = normal, 1..3 = basses + */
    val bass: StateFlow<Int> = _bass
    private val _spatial = MutableStateFlow(false)
    val spatial: StateFlow<Boolean> = _spatial
    private val _status = MutableStateFlow(Status.Off)
    val status: StateFlow<Status> = _status

    private var prefs: android.content.SharedPreferences? = null
    private var tidalSession: Int? = null
    private var dp: DynamicsProcessing? = null
    private var virt: Virtualizer? = null
    private var receiver: BroadcastReceiver? = null
    private val main = Handler(Looper.getMainLooper())
    private var angle = 0.0

    private val BASS_DB = floatArrayOf(0f, 4f, 7f, 10f)

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("hush", Context.MODE_PRIVATE)
        prefs = p
        _bass.value = p.getInt("fxBass", 0).coerceIn(0, 3)
        _spatial.value = p.getBoolean("fxSpatial", false)
        tidalSession = p.getInt("fxSession", 0).takeIf { it != 0 }
    }

    /** Écoute les annonces de session audio (Tidal en envoie quand il lance la lecture). */
    fun register(context: Context) {
        init(context)
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    val pkg = i.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME).orEmpty()
                    if (!pkg.contains("tidal", ignoreCase = true) && !pkg.contains("aspiro", ignoreCase = true)) return
                    val id = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
                    if (i.action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION && id != 0) {
                        tidalSession = id
                        prefs?.edit()?.putInt("fxSession", id)?.apply()
                        apply()
                    } else if (i.action == AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION && id == tidalSession) {
                        tidalSession = null
                        prefs?.edit()?.remove("fxSession")?.apply()
                        apply()
                    }
                }
            }
            receiver = r
            val f = IntentFilter().apply {
                addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            }
            runCatching { ContextCompat.registerReceiver(context, r, f, ContextCompat.RECEIVER_EXPORTED) }
        }
        apply()
    }

    fun unregister(context: Context) {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        release()
    }

    fun setBass(level: Int) {
        _bass.value = level.coerceIn(0, 3)
        prefs?.edit()?.putInt("fxBass", _bass.value)?.apply()
        apply()
    }

    fun cycleBass() = setBass((_bass.value + 1) % 4)

    fun setSpatial(on: Boolean) {
        _spatial.value = on
        prefs?.edit()?.putBoolean("fxSpatial", on)?.apply()
        apply()
    }

    private fun release() {
        main.removeCallbacks(rotate)
        runCatching { dp?.release() }; dp = null
        runCatching { virt?.release() }; virt = null
        _status.value = Status.Off
    }

    /** (Re)crée les effets selon les réglages. */
    fun apply() {
        release()
        val f = Features.flags.value
        val bass = if (f.bass) _bass.value else 0
        val spatial = f.spatial && _spatial.value
        if (bass == 0 && !spatial) return
        if (Build.VERSION.SDK_INT < 28) { _status.value = Status.Blocked; return }
        val sessions = listOfNotNull(tidalSession, 0).distinct()
        for (session in sessions) {
            val ok = runCatching { build(session, bass, spatial) }.isSuccess
            if (ok) {
                _status.value = if (session == 0) Status.Global else Status.Tidal
                if (spatial) main.post(rotate)
                return
            }
            runCatching { dp?.release() }; dp = null
            runCatching { virt?.release() }; virt = null
        }
        _status.value = Status.Blocked
    }

    private fun build(session: Int, bass: Int, spatial: Boolean) {
        val cfg = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,
            true, 4, // égaliseur avant : 4 bandes
            false, 0,
            false, 0,
            true, // limiteur, pour ne pas saturer
        ).build()
        val d = DynamicsProcessing(0, session, cfg)
        val g = BASS_DB[bass]
        val eq = DynamicsProcessing.Eq(true, true, 4)
        val cut = floatArrayOf(60f, 150f, 400f, 20000f)
        val gain = floatArrayOf(g, g * 0.6f, g * 0.15f, 0f)
        for (i in 0 until 4) {
            eq.getBand(i).apply { isEnabled = true; cutoffFrequency = cut[i]; this.gain = gain[i] }
        }
        d.setPreEqAllChannelsTo(eq)
        // on baisse un peu l'ensemble quand on monte les basses (marge anti-saturation)
        d.setInputGainAllChannelsTo(-g * 0.35f)
        d.setEnabled(true)
        dp = d
        if (spatial) {
            virt = runCatching {
                Virtualizer(0, session).apply { setStrength(1000); setEnabled(true) }
            }.getOrNull()
        }
    }

    /** 8D : le son tourne doucement d'une oreille à l'autre (un tour toutes les ~8 s). */
    private val rotate = object : Runnable {
        override fun run() {
            val d = dp ?: return
            angle += 2 * Math.PI * 0.04 / 8.0
            val c = cos(angle)
            val l = 0.25 + 0.75 * (1 + c) / 2
            val r = 0.25 + 0.75 * (1 - c) / 2
            val base = -BASS_DB[if (Features.flags.value.bass) _bass.value else 0] * 0.35f
            runCatching {
                d.setInputGainbyChannel(0, base + (20 * log10(l)).toFloat())
                d.setInputGainbyChannel(1, base + (20 * log10(r)).toFloat())
            }
            main.postDelayed(this, if (TidalRemote.state.value.isPlaying) 60L else 1000L)
        }
    }
}
