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

/** Rappel d'écoute : une notif le soir avec ta journée en musique (et ta série en cours). */
object Reminder {
    private const val CHANNEL = "hush_reminder"
    private const val ID = 9

    fun schedule(context: Context) {
        val ctx = context.applicationContext
        Features.init(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(ctx, 20, Intent(ctx, ReminderReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.cancel(pi)
        val f = Features.flags.value
        if (!f.reminder) return
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, f.reminderHour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis() + 60_000) cal.add(Calendar.DAY_OF_YEAR, 1)
        // pas besoin d'être à la seconde près : fenêtre de 10 min (économise la batterie)
        am.setWindow(AlarmManager.RTC_WAKEUP, cal.timeInMillis, 10 * 60_000L, pi)
    }

    fun show(context: Context) {
        val ctx = context.applicationContext
        History.init(ctx)
        LastFm.init(ctx)
        Features.init(ctx)
        if (!Features.flags.value.reminder) return
        val ev = buildEvents(History.entries.value, LastFm.scrobbles.value)
        val cal = Calendar.getInstance()
        fun dayStart(t: Long) = cal.apply {
            timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val today = dayStart(System.currentTimeMillis())
        val day = 24L * 3600_000
        val todayEv = ev.filter { it.ts >= today }
        val days = ev.map { dayStart(it.ts) }.toHashSet()
        var streak = 0
        var d = if (today in days) today else today - day
        while (d in days) { streak++; d -= day }

        val (title, text) = if (todayEv.isEmpty()) {
            if (streak == 0) return // rien à dire : on ne dérange pas
            "Ta série de $streak jour${if (streak > 1) "s" else ""} t'attend" to "Pas encore de musique aujourd'hui. Un son pour la garder ?"
        } else {
            val minutes = todayEv.sumOf { it.durMs } / 60_000
            val top = todayEv.groupingBy { it.key }.eachCount().maxByOrNull { it.value }?.key
            val topEv = todayEv.lastOrNull { it.key == top }
            val artist = todayEv.groupingBy { mainArtist(it.artist) }.eachCount().maxByOrNull { it.value }?.key
            "Ta journée en musique · ${todayEv.size} écoutes, ${fmtMinutes(minutes)}" to buildString {
                topEv?.let { append("Son du jour : ${it.title} — ${it.artist}") }
                if (artist != null) append("\nArtiste du jour : $artist")
                if (streak >= 2) append("\nSérie : $streak jours d'affilée")
            }
        }

        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Rappel d'écoute", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Ta journée en musique, le soir"
            })
        }
        val open = PendingIntent.getActivity(ctx, 21, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
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

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { Reminder.show(context) }
        Reminder.schedule(context) // et on programme celui de demain
    }
}
