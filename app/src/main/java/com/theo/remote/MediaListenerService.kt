package com.theo.remote

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Android n'autorise une appli à voir les lecteurs média des autres applis
 * que si elle est déclarée comme "écouteur de notifications".
 * Option « Masquer le lecteur Tidal » : on met en sourdine la notif lecteur de Tidal
 * (Hush a déjà la sienne). Aucun contenu de notif n'est lu ni stocké.
 */
class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        History.init(this)
        Features.init(this)
        TidalRemote.start(this)
        hideTidal()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (shouldHide(sbn)) snooze(sbn)
        else if (sbn.packageName == TidalRemote.TIDAL_PACKAGE && lastNote != sbn.key) {
            // pour le rapport de bug : pourquoi on ne l'a pas masquée
            lastNote = sbn.key
            val f = Features.flags.value
            val media = sbn.notification?.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
            TidalRemote.note("Notif Tidal vue (lecteur $media, option ${f.hideTidalNotif}, notif Hush ${f.richNotif})")
        }
    }
    private var lastNote: String? = null

    private fun shouldHide(sbn: StatusBarNotification): Boolean {
        val f = Features.flags.value
        if (!f.hideTidalNotif || !f.richNotif) return false
        if (sbn.packageName != TidalRemote.TIDAL_PACKAGE) return false
        // seulement le lecteur (notif liée à une session média), pas les autres notifs de Tidal
        return sbn.notification?.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
    }

    private fun snooze(sbn: StatusBarNotification) {
        runCatching { snoozeNotification(sbn.key, 6 * 3600_000L) }
            .onSuccess { TidalRemote.note("Lecteur Tidal masqué des notifications") }
            .onFailure { TidalRemote.note("Impossible de masquer le lecteur Tidal : ${it.message}") }
    }

    private fun hideTidal() {
        runCatching { activeNotifications?.forEach { if (shouldHide(it)) snooze(it) } }
    }

    companion object {
        @Volatile private var instance: MediaListenerService? = null
        /** Réglage activé : on masque tout de suite le lecteur Tidal déjà affiché. */
        fun refresh() { instance?.hideTidal() }
    }
}
