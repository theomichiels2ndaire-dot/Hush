package com.theo.remote

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.theo.remote.ui.buildEvents
import com.theo.remote.ui.fmtMinutes
import com.theo.remote.ui.mainArtist
import java.util.Calendar

/** Récap du dimanche soir : ta semaine en musique. */
object WeeklyRecap {
    private const val CHANNEL = "hush_weekly"
    private const val ID = 12

    fun schedule(context: Context) {
        val ctx = context.applicationContext
        Features.init(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(ctx, 50, Intent(ctx, WeeklyRecapReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.cancel(pi)
        if (!Features.flags.value.weeklyRecap) return
        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
            set(Calendar.HOUR_OF_DAY, 20); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        while (cal.timeInMillis <= System.currentTimeMillis() + 60_000) cal.add(Calendar.WEEK_OF_YEAR, 1)
        am.setWindow(AlarmManager.RTC_WAKEUP, cal.timeInMillis, 15 * 60_000L, pi)
    }

    fun show(context: Context) {
        val ctx = context.applicationContext
        History.init(ctx); LastFm.init(ctx); Features.init(ctx); StatsExclude.init(ctx)
        if (!Features.flags.value.weeklyRecap) return
        val ev = buildEvents(History.entries.value, LastFm.scrobbles.value)
        val now = System.currentTimeMillis()
        val week = 7L * 24 * 3600_000
        val cur = ev.filter { it.ts >= now - week }
        if (cur.isEmpty()) return
        val prev = ev.filter { it.ts in (now - 2 * week) until (now - week) }
        val minutes = cur.sumOf { it.durMs } / 60_000
        val prevMin = prev.sumOf { it.durMs } / 60_000
        val topKey = cur.groupingBy { it.key }.eachCount().maxByOrNull { it.value }
        val topEv = topKey?.let { k -> cur.lastOrNull { it.key == k.key } }
        val artist = cur.groupingBy { mainArtist(it.artist) }.eachCount().maxByOrNull { it.value }
        val newArtists = cur.map { mainArtist(it.artist).lowercase() }.toSet() - ev.filter { it.ts < now - week }.map { mainArtist(it.artist).lowercase() }.toSet()
        val title = "Ta semaine en musique · ${fmtMinutes(minutes)}"
        val text = buildString {
            append("${cur.size} écoutes")
            if (prevMin > 0) {
                val d = ((minutes - prevMin) * 100 / prevMin).toInt()
                append(if (d >= 0) " · +$d % vs la semaine d'avant" else " · $d % vs la semaine d'avant")
            }
            topEv?.let { append("\nSon de la semaine : ${it.title} — ${it.artist} (${topKey?.value ?: 1}×)") }
            artist?.let { append("\nArtiste de la semaine : ${it.key}") }
            if (newArtists.isNotEmpty()) append("\n${newArtists.size} nouvel${if (newArtists.size > 1) "s" else ""} artiste${if (newArtists.size > 1) "s" else ""} découvert${if (newArtists.size > 1) "s" else ""}")
        }
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Récap du dimanche", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Ta semaine en musique, le dimanche soir"
            })
        }
        val open = PendingIntent.getActivity(ctx, 51, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull().orEmpty())
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(ID, n) }
    }
}

class WeeklyRecapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { WeeklyRecap.show(context) }
        WeeklyRecap.schedule(context)
    }
}
