package com.theo.remote

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

/**
 * Commandes par gestes :
 * - secouer le téléphone → son suivant
 * - le poser face contre la table → pause (et le retourner dans la minute → reprise)
 */
object Gestures {

    data class Settings(val shake: Boolean = false, val flip: Boolean = false)

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings

    private var prefs: android.content.SharedPreferences? = null
    private var sm: SensorManager? = null
    private var vib: Vibrator? = null
    private var listening = false
    private val main = Handler(Looper.getMainLooper())

    private var lastShakeAt = 0L
    private var shakeCount = 0
    private var firstShakeAt = 0L
    private var faceDownSince = 0L
    private var pausedByFlipAt = 0L

    fun init(context: Context) {
        if (prefs != null) return
        val ctx = context.applicationContext
        prefs = ctx.getSharedPreferences("hush", Context.MODE_PRIVATE)
        sm = ctx.getSystemService(SensorManager::class.java)
        vib = ctx.getSystemService(Vibrator::class.java)
        _settings.value = Settings(prefs!!.getBoolean("gShake", false), prefs!!.getBoolean("gFlip", false))
        update()
    }

    fun set(s: Settings) {
        _settings.value = s
        prefs?.edit()?.putBoolean("gShake", s.shake)?.putBoolean("gFlip", s.flip)?.apply()
        update()
    }

    private fun update() {
        val want = _settings.value.shake || _settings.value.flip
        val m = sm ?: return
        if (want && !listening) {
            m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { m.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI, main) }
            listening = true
        } else if (!want && listening) {
            m.unregisterListener(listener)
            listening = false
        }
    }

    private fun buzz() {
        runCatching { vib?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }

    private val listener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        override fun onSensorChanged(e: SensorEvent) {
            val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
            val now = SystemClock.elapsedRealtime()
            val st = TidalRemote.state.value
            val s = _settings.value

            // ---- secouer : deux secousses fortes en moins d'une demi-seconde
            if (s.shake && st.isPlaying) {
                val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                if (g > 2.6f) {
                    if (now - firstShakeAt > 600) { firstShakeAt = now; shakeCount = 0 }
                    shakeCount++
                    if (shakeCount >= 2 && now - lastShakeAt > 1500) {
                        lastShakeAt = now
                        shakeCount = 0
                        buzz()
                        TidalRemote.next("secousse")
                    }
                }
            }

            // ---- retourner : écran contre la table pendant 0,8 s
            if (s.flip) {
                val faceDown = z < -8.5f && kotlin.math.abs(x) < 2.5f && kotlin.math.abs(y) < 2.5f
                val faceUp = z > 8.5f && kotlin.math.abs(x) < 3f && kotlin.math.abs(y) < 3f
                if (faceDown) {
                    if (faceDownSince == 0L) faceDownSince = now
                    if (st.isPlaying && now - faceDownSince > 800 && pausedByFlipAt == 0L) {
                        pausedByFlipAt = now
                        buzz()
                        TidalRemote.playPause()
                    }
                } else {
                    faceDownSince = 0L
                    if (faceUp && pausedByFlipAt > 0L) {
                        // retourné dans la minute : on reprend
                        if (now - pausedByFlipAt < 60_000 && !st.isPlaying) TidalRemote.playPause()
                        pausedByFlipAt = 0L
                    }
                }
            }
        }
    }
}
