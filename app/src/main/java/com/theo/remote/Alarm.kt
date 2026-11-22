package com.theo.remote

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Réveil musical : Hush te réveille avec ta musique, le volume monte doucement. */
object Alarm {

    /** days : jours de la semaine (Calendar.MONDAY…), vide = une seule fois. */
    data class Config(
        val enabled: Boolean = false,
        val hour: Int = 7,
        val minute: Int = 30,
        val days: Set<Int> = setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY),
        /** "last" (dernier son), "favorites" (Ma Musique) ou "playlist" */
        val source: String = "last",
        val playlist: String? = null,
        /** volume final, en % du maximum */
        val volume: Int = 60,
        /** durée de la montée du volume */
        val rampSec: Int = 60,
    )

    private val _config = MutableStateFlow(Config())
    val config: StateFlow<Config> = _config

    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("hush", Context.MODE_PRIVATE)
        _config.value = decode(prefs!!.getString("alarm", null)) ?: Config()
    }

    fun set(context: Context, c: Config) {
        init(context)
        _config.value = c
        prefs?.edit()?.putString("alarm", encode(c))?.apply()
        schedule(context)
    }

    /** Peut-on programmer une alarme exacte ? (Android 12+ peut demander l'autorisation) */
    fun canSchedule(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }

    /** Prochaine sonnerie (ms), ou null si le réveil est coupé. */
    fun next(c: Config = _config.value, now: Long = System.currentTimeMillis()): Long? {
        if (!c.enabled) return null
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, c.hour); set(Calendar.MINUTE, c.minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        if (c.days.isNotEmpty()) {
            var guard = 0
            while (cal.get(Calendar.DAY_OF_WEEK) !in c.days && guard++ < 8) cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    fun schedule(context: Context) {
        val ctx = context.applicationContext
        init(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = ringIntent(ctx)
        am.cancel(pi)
        val at = next() ?: return
        val show = PendingIntent.getActivity(
            ctx, 11, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
        )
        if (canSchedule(ctx)) {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
        } else {
            // sans l'autorisation « alarmes exactes », Android peut décaler de quelques minutes
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Répéter dans `minutes` minutes. */
    fun snooze(context: Context, minutes: Int = 9) {
        val ctx = context.applicationContext
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + minutes * 60_000L
        val show = PendingIntent.getActivity(ctx, 11, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        if (canSchedule(ctx)) am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), ringIntent(ctx))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, ringIntent(ctx))
    }

    /** Après une sonnerie : on programme la suivante (ou on coupe si c'était « une fois »). */
    fun afterRing(context: Context) {
        val c = _config.value
        if (c.days.isEmpty()) set(context, c.copy(enabled = false)) else schedule(context)
    }

    private fun ringIntent(ctx: Context) = PendingIntent.getActivity(
        ctx, 10,
        Intent(ctx, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun encode(c: Config) = JSONObject()
        .put("on", c.enabled).put("h", c.hour).put("m", c.minute)
        .put("d", JSONArray(c.days.toList())).put("s", c.source).put("p", c.playlist ?: "")
        .put("v", c.volume).put("r", c.rampSec).toString()

    private fun decode(s: String?): Config? = runCatching {
        val o = JSONObject(s ?: return null)
        val d = o.optJSONArray("d") ?: JSONArray()
        Config(
            enabled = o.optBoolean("on"),
            hour = o.optInt("h", 7),
            minute = o.optInt("m", 30),
            days = (0 until d.length()).map { d.getInt(it) }.toSet(),
            source = o.optString("s", "last"),
            playlist = o.optString("p").ifBlank { null },
            volume = o.optInt("v", 60),
            rampSec = o.optInt("r", 60),
        )
    }.getOrNull()
}

/** Ce qui se passe quand le réveil sonne : musique, montée du volume, sonnerie de secours. */
object AlarmRinger {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var fallback: MediaPlayer? = null
    private var originalVolume = -1

    private val _ringing = MutableStateFlow(false)
    val ringing: StateFlow<Boolean> = _ringing

    fun start(context: Context) {
        if (_ringing.value) return
        val ctx = context.applicationContext
        _ringing.value = true
        History.init(ctx)
        TidalAuth.init(ctx)
        Alarm.init(ctx)
        TidalRemote.start(ctx)
        val c = Alarm.config.value
        val am = ctx.getSystemService(AudioManager::class.java)
        val max = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
        originalVolume = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
        val target = (max * c.volume / 100f).toInt().coerceIn(1, max)
        runCatching { am?.setStreamVolume(AudioManager.STREAM_MUSIC, 1, 0) }

        job = scope.launch {
            // 1) on lance la musique
            if (!TidalRemote.state.value.connected) {
                TidalRemote.resume(ctx) // réveille Tidal avec la touche « lecture »
                var waited = 0
                while (!TidalRemote.state.value.connected && waited < 8000) { delay(250); waited += 250 }
            }
            when (c.source) {
                "playlist" -> c.playlist?.let { TidalRemote.playPlaylist(it) } ?: TidalRemote.resume(ctx)
                "favorites" -> {
                    val favs = runCatching { TidalApi.favoritesPage().tracks }.getOrDefault(emptyList()).shuffled()
                    if (favs.isNotEmpty()) {
                        TidalRemote.play(favs.first())
                        TidalRemote.replaceQueue(favs.drop(1).take(40))
                    } else TidalRemote.resume(ctx)
                }
                else -> TidalRemote.resume(ctx)
            }

            // 2) le volume monte doucement
            val steps = (target - 1).coerceAtLeast(1)
            val stepMs = (c.rampSec * 1000L / steps).coerceAtLeast(300)
            var v = 1
            var elapsed = 0L
            while (v < target) {
                delay(stepMs)
                elapsed += stepMs
                v++
                runCatching { am?.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
                // 3) si rien ne joue au bout de 25 s : sonnerie de secours (un réveil doit sonner !)
                if (elapsed >= 25_000 && fallback == null && !TidalRemote.state.value.isPlaying) startFallback(ctx)
            }
            var extra = 0L
            while (fallback == null && extra < 25_000) {
                delay(1000); extra += 1000
                if (elapsed + extra >= 25_000 && !TidalRemote.state.value.isPlaying) startFallback(ctx)
            }
        }
    }

    private fun startFallback(ctx: Context) {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: return
        fallback = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(ctx, uri)
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
    }

    /** keepMusic = « Je me lève » : on coupe le réveil mais la musique continue. */
    fun stop(context: Context, keepMusic: Boolean) {
        job?.cancel()
        runCatching { fallback?.stop(); fallback?.release() }
        fallback = null
        if (!keepMusic) {
            if (TidalRemote.state.value.isPlaying) TidalRemote.playPause()
            val am = context.getSystemService(AudioManager::class.java)
            if (originalVolume >= 0) runCatching { am?.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0) }
        }
        _ringing.value = false
        Alarm.afterRing(context)
    }

    fun snooze(context: Context) {
        stop(context, keepMusic = false)
        Alarm.snooze(context, 9)
    }
}

/** Après un redémarrage du téléphone (ou une mise à jour de Hush) : on reprogramme le réveil. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Alarm.init(context)
        Alarm.schedule(context)
        Reminder.schedule(context)
        Follow.schedule(context)
        WeeklyRecap.schedule(context)
    }
}
