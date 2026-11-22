package com.theo.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Message au futur toi : une note attachée à un son, qui ressort quand tu réécoutes ce son
 * après la date choisie (dans 1 mois, 6 mois, 1 an…).
 */
object TimeCapsule {
    data class Capsule(
        val id: Long, val key: String, val title: String, val artist: String,
        val text: String, val createdAt: Long, val openAt: Long, val opened: Boolean = false,
    )

    private val _list = MutableStateFlow<List<Capsule>>(emptyList())
    val list: StateFlow<List<Capsule>> = _list
    /** message qui vient de ressortir (affiché dans Hush) */
    val reveal = MutableStateFlow<Capsule?>(null)
    /** son pour lequel on écrit un message (titre, artiste) */
    val writing = MutableStateFlow<Pair<String, String>?>(null)
    private var prefs: android.content.SharedPreferences? = null
    private var ctx: Context? = null
    private const val CHANNEL = "hush_capsules"

    fun init(context: Context) {
        if (prefs != null) return
        ctx = context.applicationContext
        val p = context.applicationContext.getSharedPreferences("hush_capsules", Context.MODE_PRIVATE)
        prefs = p
        _list.value = runCatching {
            val a = JSONArray(p.getString("list", "[]"))
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Capsule(o.getLong("id"), o.getString("k"), o.getString("t"), o.getString("a"), o.getString("x"), o.getLong("c"), o.getLong("o"), o.optBoolean("d"))
            }
        }.getOrDefault(emptyList())
    }

    fun add(title: String, artist: String, text: String, days: Int) {
        val now = System.currentTimeMillis()
        _list.value = _list.value + Capsule(now, History.keyOf(title, artist), title, artist, text.trim(), now, now + days * 86_400_000L)
        save()
        TidalRemote.showToast("Message gardé : il ressortira quand tu réécouteras ce son")
    }

    fun pending(title: String, artist: String) = _list.value.count { !it.opened && it.key == History.keyOf(title, artist) }

    /** Appelé à chaque nouveau son : un message arrivé à échéance ressort. */
    fun onPlay(title: String, artist: String) {
        if (!Features.flags.value.timeCapsule) return
        val now = System.currentTimeMillis()
        val key = History.keyOf(title, artist)
        val due = _list.value.firstOrNull { !it.opened && it.key == key && it.openAt <= now } ?: return
        _list.value = _list.value.map { if (it.id == due.id) it.copy(opened = true) else it }
        save()
        reveal.value = due
        notify(due)
    }

    /** « Le renvoyer dans 1 an » */
    fun again(c: Capsule) {
        _list.value = _list.value.map { if (it.id == c.id) it.copy(opened = false, openAt = System.currentTimeMillis() + 365L * 86_400_000L) else it }
        save()
    }

    fun delete(c: Capsule) { _list.value = _list.value.filter { it.id != c.id }; save() }

    private fun notify(c: Capsule) {
        val context = ctx ?: return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Messages au futur toi", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(context, 77, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Un message de toi · ${c.title}")
            .setContentText(c.text)
            .setStyle(Notification.BigTextStyle().bigText(c.text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(7700 + (c.id % 100).toInt(), n) }
    }

    private fun save() {
        val a = JSONArray()
        _list.value.forEach {
            a.put(JSONObject().put("id", it.id).put("k", it.key).put("t", it.title).put("a", it.artist).put("x", it.text).put("c", it.createdAt).put("o", it.openAt).put("d", it.opened))
        }
        prefs?.edit()?.putString("list", a.toString())?.apply()
    }
}
