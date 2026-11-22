package com.theo.remote

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Suivre des artistes : une notif quand ils sortent un nouveau son. */
object Follow {
    private val _artists = MutableStateFlow<List<String>>(emptyList())
    val artists: StateFlow<List<String>> = _artists
    private var prefs: android.content.SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val CHANNEL = "hush_releases"

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("hush", Context.MODE_PRIVATE)
        prefs = p
        _artists.value = runCatching {
            val a = JSONArray(p.getString("followed", "[]"))
            (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList())
    }

    fun isFollowed(name: String) = _artists.value.any { it.equals(name, ignoreCase = true) }

    fun toggle(context: Context, name: String) {
        init(context)
        if (isFollowed(name)) {
            _artists.value = _artists.value.filterNot { it.equals(name, ignoreCase = true) }
            TidalRemote.showToast("Tu ne suis plus $name")
        } else {
            _artists.value = _artists.value + name
            TidalRemote.showToast("Tu suis $name : notif à chaque nouveau son")
            // ses sorties actuelles sont déjà « vues » : on ne préviendra que des prochaines
            scope.launch { runCatching { markSeen(TidalApi.recentReleases(name, 60).map { it.id }) } }
            schedule(context)
        }
        save()
    }

    private fun seen(): MutableSet<String> = prefs?.getStringSet("seenReleases", emptySet()).orEmpty().toMutableSet()

    private fun markSeen(ids: List<String>) {
        val s = seen(); s.addAll(ids)
        prefs?.edit()?.putStringSet("seenReleases", s.toList().takeLast(600).toSet())?.apply()
    }

    private fun save() {
        prefs?.edit()?.putString("followed", JSONArray(_artists.value).toString())?.apply()
    }

    /** Vérifie une fois par jour, vers midi. */
    fun schedule(context: Context) {
        val ctx = context.applicationContext
        init(ctx); Features.init(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(ctx, 40, Intent(ctx, FollowReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.cancel(pi)
        // vérification quotidienne : sorties des artistes suivis + concerts (si la clé Ticketmaster est là)
        com.theo.remote.ui.Concerts.init(ctx)
        val follows = Features.flags.value.follow && _artists.value.isNotEmpty()
        val concerts = Features.flags.value.concerts && com.theo.remote.ui.Concerts.key.value.isNotBlank()
        val upcoming = Features.flags.value.upcoming
        if (!follows && !concerts && !upcoming) return
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        am.setWindow(AlarmManager.RTC_WAKEUP, cal.timeInMillis, 60 * 60_000L, pi)
    }

    /** Cherche les nouveautés des artistes suivis et prévient. */
    suspend fun check(context: Context) {
        val ctx = context.applicationContext
        init(ctx); Features.init(ctx)
        if (!Features.flags.value.follow) return
        val seen = seen()
        val fresh = ArrayList<TidalApi.Album>()
        for (a in _artists.value) {
            runCatching { TidalApi.recentReleases(a, 21) }.getOrNull()?.filter { it.id !in seen }?.let { fresh += it }
            delay(400)
        }
        if (fresh.isEmpty()) return
        markSeen(fresh.map { it.id })
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sorties des artistes suivis", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(ctx, 41, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        fresh.take(5).forEachIndexed { i, al ->
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Nouveau : ${al.artist ?: ""}")
                .setContentText("${al.title}${al.type?.let { " · ${it.lowercase()}" } ?: ""}")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { nm.notify(100 + i, n) }
        }
    }
}

/** Jour J d'une sortie annoncée : une notification par album (option « Sorties à venir »). */
suspend fun Follow.notifyUpcoming(context: Context) {
    val ctx = context.applicationContext
    Features.init(ctx)
    if (!Features.flags.value.upcoming) return
    runCatching { com.theo.remote.ui.Upcoming.refresh(ctx) }
    val due = com.theo.remote.ui.Upcoming.dueToday(ctx)
    if (due.isEmpty()) return
    val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
    val ch = "hush_upcoming"
    if (nm.getNotificationChannel(ch) == null) {
        nm.createNotificationChannel(NotificationChannel(ch, "Sorties à venir", NotificationManager.IMPORTANCE_DEFAULT))
    }
    val open = PendingIntent.getActivity(ctx, 42, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
    due.take(5).forEachIndexed { i, al ->
        val n = Notification.Builder(ctx, ch)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("C'est sorti : ${al.title}")
            .setContentText("${al.artist ?: ""} · dispo sur Tidal aujourd'hui")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(150 + i, n) }
    }
}

class FollowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { Follow.check(context) }
                runCatching { Follow.notifyUpcoming(context) }
                runCatching { com.theo.remote.ui.Concerts.dailyCheck(context) }
                runCatching { com.theo.remote.ui.Setlists.checkAfterConcerts(context) }
                Follow.schedule(context)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Paroles favorites : les lignes que tu gardes (appui long dans les paroles). */
object LyricQuotes {
    data class Quote(val text: String, val title: String, val artist: String, val timeMs: Long, val at: Long)

    private val _quotes = MutableStateFlow<List<Quote>>(emptyList())
    val quotes: StateFlow<List<Quote>> = _quotes
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("hush", Context.MODE_PRIVATE)
        prefs = p
        _quotes.value = runCatching {
            val a = JSONArray(p.getString("quotes", "[]"))
            (0 until a.length()).map { val o = a.getJSONObject(it); Quote(o.getString("x"), o.optString("t"), o.optString("a"), o.optLong("ms"), o.optLong("at")) }
        }.getOrDefault(emptyList())
    }

    fun add(text: String, title: String, artist: String, timeMs: Long) {
        if (text.isBlank()) return
        if (_quotes.value.any { it.text == text && it.title == title }) { TidalRemote.showToast("Déjà dans tes paroles"); return }
        _quotes.value = listOf(Quote(text.trim(), title, artist, timeMs, System.currentTimeMillis())) + _quotes.value
        save()
        TidalRemote.showToast("Gardé dans tes paroles")
    }

    fun remove(q: Quote) {
        _quotes.value = _quotes.value.filterNot { it == q }
        save()
    }

    private fun save() {
        val a = JSONArray()
        _quotes.value.forEach { a.put(JSONObject().put("x", it.text).put("t", it.title).put("a", it.artist).put("ms", it.timeMs).put("at", it.at)) }
        prefs?.edit()?.putString("quotes", a.toString())?.apply()
    }
}
