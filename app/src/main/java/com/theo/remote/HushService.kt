package com.theo.remote

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.media.session.PlaybackState as PS

/**
 * Service discret qui garde Hush en vie quand l'appli est fermée :
 * la file « À suivre » continue et la musique enchaîne sur des sons similaires.
 * Il n'affiche qu'une notification silencieuse (réduite au minimum).
 */
class HushService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Images.install(this)
        CrashLog.install(this)
        running = true
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, notification)
        }
        History.init(this)
        LastFm.init(this)
        AutoResume.init(this)
        Alarm.init(this)
        Recognizer.init(this)
        Gestures.init(this)
        Features.init(this)
        Blocked.init(this)
        Follow.init(this)
        LyricQuotes.init(this)
        StatsExclude.init(this)
        SkipTracker.init(this)
        PlaylistResume.init(this)
        TimeCapsule.init(this)
        com.theo.remote.ui.RatePrompt.init(this)
        Ratings.init(this)
        PlaylistCovers.init(this)
        PlaylistPlays.init(this)
        Diag.init(this)
        LocalMusic.init(this)
        if (Features.flags.value.autoBackup) {
            val app = applicationContext
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { runCatching { Backup.autoBackup(app) } }
        }
        LastPlay.init(this)
        TidalRemote.start(this)
        watchNotification()
        // tes playlists Tidal gardées pour le widget et les raccourcis ; widget mis à jour quand ça bouge
        scope.launch {
            com.theo.remote.ui.PlaylistStore.list.collect { l -> l?.let { PlaylistPlays.remember(it.map { p -> Triple(p.id, p.name, p.coverUrl) }) } }
        }
        scope.launch {
            kotlinx.coroutines.flow.combine(PlaylistPlays.list, PlaylistPlays.known, PlaylistCovers.map) { a, b, c -> Triple(a.size to a.sumOf { it.count }, b.size, c.hashCode()) }
                .distinctUntilChanged()
                .collect { kotlinx.coroutines.delay(1500); PlaylistsWidget.refresh(this@HushService) }
        }
        // panneau latéral : suit l'option des Réglages
        scope.launch {
            Features.flags.collect { runCatching { com.theo.remote.ui.EdgePanel.sync(this@HushService) } }
        }
        AudioFx.register(this)
        // tes playlists gardées en entier sur le téléphone (en Wi-Fi et en charge seulement)
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000)
                runCatching { com.theo.remote.ui.PlaylistPreload.maybeRun(this@HushService) }
                runCatching { Updater.check() } // nouvelle version : téléchargée d'avance en Wi-Fi
                kotlinx.coroutines.delay(29 * 60_000L)
            }
        }
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    /** Notification enrichie : son en cours + boutons, mise à jour quand ça change. */
    private fun watchNotification() {
        scope.launch {
            kotlinx.coroutines.flow.combine(TidalRemote.state, Features.flags) { st, f ->
                listOf(f.richNotif, f.mediaCard, f.statusPill, st.track?.title, st.track?.artist, st.isPlaying, st.tidalFavorite, st.shuffle, st.track?.art?.let { System.identityHashCode(it) })
            }.distinctUntilChanged().collect {
                runCatching { getSystemService(NotificationManager::class.java).notify(ID, buildNotification()) }
            }
        }
        // carte lecteur : position, durée, pochette, cœur et aléatoire tenus à jour
        scope.launch {
            kotlinx.coroutines.flow.combine(TidalRemote.state, Features.flags) { st, f -> st to f }.collect { (st, f) ->
                runCatching { syncSession(st, f.richNotif && f.mediaCard) }
            }
        }
    }

    private var session: android.media.session.MediaSession? = null
    private var lastMetaKey: List<Any?>? = null

    private fun mediaSession(): android.media.session.MediaSession = session ?: android.media.session.MediaSession(this, "Hush").also { ms ->
        ms.setCallback(object : android.media.session.MediaSession.Callback() {
            override fun onPlay() { if (!TidalRemote.state.value.isPlaying) TidalRemote.playPause() }
            override fun onPause() { if (TidalRemote.state.value.isPlaying) TidalRemote.playPause() }
            override fun onSkipToNext() = TidalRemote.next("bouton casque ou notification")
            override fun onSkipToPrevious() = TidalRemote.previous()
            override fun onSeekTo(pos: Long) = TidalRemote.seekTo(pos)
            override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                val s = TidalRemote.state.value
                when (action) {
                    CA_HEART -> s.track?.let { com.theo.remote.ui.toggleHeart(s, it) }
                    CA_SHUFFLE -> TidalRemote.toggleShuffle()
                }
            }
        })
        ms.setSessionActivity(
            PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        )
        session = ms
    }

    private fun syncSession(st: TidalRemote.State, on: Boolean) {
        val t = st.track
        if (!on || t == null) {
            session?.let { it.isActive = false; it.release() }
            session = null; lastMetaKey = null
            return
        }
        val ms = mediaSession()
        val key = listOf(t.title, t.artist, t.durationMs, System.identityHashCode(t.art))
        if (key != lastMetaKey) {
            lastMetaKey = key
            ms.setMetadata(
                android.media.MediaMetadata.Builder()
                    .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, t.title)
                    .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                    .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, t.durationMs)
                    .apply { t.art?.let { putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                    .build()
            )
        }
        val liked = st.tidalFavorite ?: History.isLiked(t.title, t.artist)
        val updated = if (st.positionUpdatedAt > 0) st.positionUpdatedAt else android.os.SystemClock.elapsedRealtime()
        ms.setPlaybackState(
            PS.Builder()
                .setActions(PS.ACTION_PLAY or PS.ACTION_PAUSE or PS.ACTION_PLAY_PAUSE or PS.ACTION_SKIP_TO_NEXT or PS.ACTION_SKIP_TO_PREVIOUS or PS.ACTION_SEEK_TO)
                .setState(if (st.isPlaying) PS.STATE_PLAYING else PS.STATE_PAUSED, st.positionMs, if (st.isPlaying && st.advancing) st.speed else 0f, updated)
                .addCustomAction(PS.CustomAction.Builder(CA_HEART, if (liked) "Retirer des favoris" else "J'aime", if (liked) R.drawable.ic_n_heart_on else R.drawable.ic_n_heart).build())
                .addCustomAction(PS.CustomAction.Builder(CA_SHUFFLE, "Aléatoire", if (st.shuffle) R.drawable.ic_n_shuffle_on else R.drawable.ic_n_shuffle).build())
                .build()
        )
        if (!ms.isActive) ms.isActive = true
    }

    override fun onDestroy() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        running = false
        AudioFx.unregister(this)
        session?.release(); session = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TidalRemote.start(this)
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Hush en arrière-plan", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Garde ta file d'attente active quand Hush est fermé"
                    setShowBadge(false)
                }
            )
        }
        if (nm.getNotificationChannel(RICH) == null) {
            nm.createNotificationChannel(
                NotificationChannel(RICH, "Hush : son en cours", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Le son en cours avec tes boutons (cœur, précédent, lecture, suivant)"
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val st = TidalRemote.state.value
        val t = st.track
        if (Features.flags.value.richNotif && t != null) {
            fun action(icon: Int, label: String, what: String, req: Int) = Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, icon), label,
                PendingIntent.getBroadcast(this, req, Intent(this, NotifActionReceiver::class.java).setAction(what), PendingIntent.FLAG_IMMUTABLE),
            ).build()
            val liked = st.tidalFavorite ?: History.isLiked(t.title, t.artist)
            return Notification.Builder(this, RICH)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(t.title)
                .setContentText(t.artist)
                .setLargeIcon(t.art)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(action(if (liked) R.drawable.ic_n_heart_on else R.drawable.ic_n_heart, if (liked) "Retirer" else "J'aime", NotifActionReceiver.HEART, 31))
                .addAction(action(R.drawable.ic_w_prev, "Précédent", NotifActionReceiver.PREV, 32))
                .addAction(action(if (st.isPlaying) R.drawable.ic_w_pause else R.drawable.ic_w_play, "Lecture", NotifActionReceiver.PLAY, 33))
                .addAction(action(R.drawable.ic_w_next, "Suivant", NotifActionReceiver.NEXT, 34))
                .apply { if (Features.flags.value.mediaCard) addAction(action(if (st.shuffle) R.drawable.ic_n_shuffle_on else R.drawable.ic_n_shuffle, "Aléatoire", NotifActionReceiver.SHUFFLE, 35)) }
                .setStyle(Notification.MediaStyle().setShowActionsInCompactView(1, 2, 3).apply {
                    // la session reste active (Orbite et les autres applis peuvent piloter Hush) ;
                    // sans la pastille, on ne la rattache juste pas à la notif
                    if (Features.flags.value.mediaCard) runCatching { syncSession(st, true); if (Features.flags.value.statusPill) setMediaSession(mediaSession().sessionToken) }
                })
                .build()
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Hush est actif")
            .setContentText("Ta file continue et la musique ne s'arrête pas")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL = "hush_background"
        private const val RICH = "hush_now"
        private const val ID = 7
        private const val CA_HEART = "hush.heart"
        private const val CA_SHUFFLE = "hush.shuffle"

        /** true tant que le service tourne (pour le diagnostic) */
        @Volatile var running = false

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, HushService::class.java))
            } catch (e: Exception) {
                // Android peut refuser si l'appli n'est pas au premier plan : on réessaiera au prochain lancement
            }
        }
    }
}

/** Boutons de la notification enrichie. */
class NotifActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        History.init(context)
        TidalRemote.start(context)
        when (intent.action) {
            PREV -> TidalRemote.previous()
            PLAY -> TidalRemote.playPause()
            NEXT -> TidalRemote.next("notification")
            SHUFFLE -> TidalRemote.toggleShuffle()
            HEART -> {
                val s = TidalRemote.state.value
                s.track?.let { com.theo.remote.ui.toggleHeart(s, it) }
            }
        }
    }

    companion object {
        const val PREV = "hush.notif.prev"
        const val PLAY = "hush.notif.play"
        const val NEXT = "hush.notif.next"
        const val HEART = "hush.notif.heart"
        const val SHUFFLE = "hush.notif.shuffle"
    }
}
