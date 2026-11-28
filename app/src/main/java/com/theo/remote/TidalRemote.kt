package com.theo.remote

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pont vers le lecteur de l'appli Tidal officielle.
 *
 * On ne joue aucun son nous-mêmes : on récupère la "session média" que Tidal
 * publie (celle qu'utilisent la notif et l'écran verrouillé) et on la pilote.
 */
object TidalRemote {

    const val TIDAL_PACKAGE = "com.aspiro.tidal"

    data class Track(
        val title: String,
        val artist: String,
        val album: String,
        val art: Bitmap?,
        val durationMs: Long,
    )

    data class State(
        val hasPermission: Boolean = false,
        val connected: Boolean = false,
        val sessionPackage: String? = null,
        val track: Track? = null,
        val isPlaying: Boolean = false,
        val positionMs: Long = 0,
        val positionUpdatedAt: Long = 0,
        val speed: Float = 1f,
        val actions: Long = 0,
        val queueSize: Int? = null,
        val activeSessions: List<String> = emptyList(),
        val log: List<String> = emptyList(),
        val launch: Launch? = null,
        val queue: List<QueueEntry> = emptyList(),
        val activeQueueId: Long = -1,
        val shuffle: Boolean = false,
        /** Le son est-il dans « Ma Musique » sur Tidal ? (null = Tidal ne le dit pas) */
        val tidalFavorite: Boolean? = null,
        val autoMix: Boolean = true,
        /** La position avance vraiment (lecture, pas chargement). */
        val advancing: Boolean = false,
    ) {
        /** Position extrapolée en temps réel (Tidal ne la renvoie pas à chaque seconde). */
        fun livePosition(now: Long = SystemClock.elapsedRealtime()): Long {
            if (!advancing || positionUpdatedAt == 0L) return positionMs
            val p = positionMs + ((now - positionUpdatedAt) * speed).toLong()
            val d = track?.durationMs ?: 0L
            return if (d > 0) p.coerceIn(0L, d) else p.coerceAtLeast(0L)
        }

        fun supports(action: Long) = actions and action != 0L
    }

    internal const val FAVORITE_ACTION = "com.aspiro.tidal.player.action.TOGGLE_FAVORITE"

    /** Lit l'état « Ma Musique » dans le libellé du bouton de Tidal (« Supprimer de… » = déjà ajouté). */
    internal fun favoriteFrom(pb: PlaybackState?): Boolean? {
        val a = pb?.customActions?.firstOrNull { it.action == FAVORITE_ACTION } ?: return null
        val n = a.name?.toString()?.lowercase(Locale.ROOT) ?: return null
        return when {
            n.startsWith("supprimer") || n.startsWith("retirer") || n.startsWith("remove") || n.contains("delete") -> true
            n.startsWith("ajouter") || n.startsWith("add") -> false
            else -> null
        }
    }

    /** Ajoute / retire le son en cours de « Ma Musique » sur Tidal. */
    fun toggleTidalFavorite(): Boolean {
        val a = controller?.playbackState?.customActions?.firstOrNull { it.action == FAVORITE_ACTION } ?: return false
        controls?.sendCustomAction(a, null)
        _state.update { st -> st.copy(tidalFavorite = st.tidalFavorite?.not()) }
        return true
    }

    /** Compare deux images rapidement (taille + 36 pixels répartis). */
    internal fun sameImage(a: Bitmap, b: Bitmap): Boolean {
        if (a === b) return true
        if (a.width != b.width || a.height != b.height) return false
        return try {
            for (i in 1..6) for (j in 1..6) {
                val x = a.width * i / 7
                val y = a.height * j / 7
                if (a.getPixel(x, y) != b.getPixel(x, y)) return false
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    internal val PLAYING_LIKE = setOf(
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
    )

    data class QueueEntry(val id: Long, val title: String, val subtitle: String, val icon: String? = null)

    enum class LaunchStatus { Pending, Ok, OtherVersion, NoChange }

    /** Le dernier titre qu'on a demandé à Tidal de lancer, et si c'est bien lui qui est parti. */
    data class Launch(
        val trackId: String,
        val expected: String,
        val status: LaunchStatus,
        val actual: String? = null,
        val sentAt: Long = SystemClock.elapsedRealtime(),
        val query: String = "",
        val tries: Int = 0,
        /** Lancé par le lien exact du son. */
        val viaLink: Boolean = false,
        /** Artiste attendu : un son du même titre par un autre artiste n'est pas le bon. */
        val artist: String = "",
    )

    /** Ce que le diagnostic vérifie, dans l'ordre d'importance pour le projet. */
    val CAPABILITIES = listOf(
        "Lancer un titre via une recherche" to PlaybackState.ACTION_PLAY_FROM_SEARCH,
        "Lancer un titre via un lien" to PlaybackState.ACTION_PLAY_FROM_URI,
        "Lancer un titre via un ID interne" to PlaybackState.ACTION_PLAY_FROM_MEDIA_ID,
        "Sauter à un titre de la file" to PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM,
        "Barre de progression" to PlaybackState.ACTION_SEEK_TO,
        "Suivant / précédent" to (PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS),
    )

    internal val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    internal val main = Handler(Looper.getMainLooper())
    internal var manager: MediaSessionManager? = null
    internal var controller: MediaController? = null

    /** Tidal joue sur un autre appareil (Tidal Connect, Chromecast…) : nom donné par Tidal, sinon null. */
    fun remoteDevice(): String? {
        val c = controller ?: return null
        val info = c.playbackInfo ?: return null
        if (info.playbackType != MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE) return null
        return "Tidal Connect"
    }
    internal var compat: MediaControllerCompat? = null
    var appContext: Context? = null
        private set

    /** Ce que TOI tu as choisi dans Hush ; c'est ça qui fait foi, pas l'état momentané de Tidal. */
    internal var wantShuffle = false
    internal var shuffleLoaded = false
    internal val clock = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)

    internal val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> attach(list.orEmpty()) }

    internal val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            refresh()
            // le son a changé : on réagit tout de suite (sans attendre la prochaine vérif), pour que
            // le son lancé par Tidal à la place du nôtre ne s'entende pas
            val t = _state.value.track
            if (t != null && "${t.title}|${t.artist}" != lastKey && !LocalPlayer.active) runCatching { checkEndOfQueue() }
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            refresh()
            // Tidal annonce qu'il passe au suivant à la fin d'un son alors que Hush a prévu autre chose :
            // on coupe le son AVANT que le sien démarre
            val ps = state?.state
            if ((ps == PlaybackState.STATE_SKIPPING_TO_NEXT || ps == PlaybackState.STATE_BUFFERING || ps == PlaybackState.STATE_CONNECTING) &&
                lastKey != null && lastRemaining <= 3000 && upNextFiredFor != lastKey &&
                hasPending() && queuedNext == null && !LocalPlayer.active
            ) muteHandoff()
        }

        override fun onQueueChanged(queue: MutableList<android.media.session.MediaSession.QueueItem>?) {
            refresh()
        }

        override fun onSessionDestroyed() {
            controller = null
            log("Session Tidal fermée")
            _state.update { it.copy(connected = false, sessionPackage = null, isPlaying = false) }
        }
    }

    // ---------- File « À suivre » de Hush ----------

    internal val _upNext = MutableStateFlow<List<TidalApi.Result>>(emptyList())
    val upNext: StateFlow<List<TidalApi.Result>> = _upNext.asStateFlow()

    internal val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()
    internal val clearToast = Runnable { _toast.value = null }

    // ---------- Annuler ----------
    /** Bandeau « Annuler » : 5 s pour revenir sur une action (son retiré, file vidée, ordre changé). */
    data class Undo(val label: String, val key: String, val action: () -> Unit, val at: Long)
    internal val _undo = MutableStateFlow<Undo?>(null)
    val undo: StateFlow<Undo?> = _undo.asStateFlow()
    internal val clearUndo = Runnable { _undo.value = null }

    /**
     * Propose d'annuler. Plusieurs actions de suite du même genre (ex. un glisser qui déplace plusieurs fois)
     * gardent la toute première sauvegarde : « Annuler » revient à l'état d'avant le geste.
     */
    fun offerUndo(label: String, key: String = label, action: () -> Unit) {
        if (!Features.flags.value.undoBar) { showToast(label); return }
        val cur = _undo.value
        val now = SystemClock.elapsedRealtime()
        _undo.value = if (cur != null && cur.key == key && now - cur.at < 5000) cur.copy(label = label, at = now) else Undo(label, key, action, now)
        _toast.value = null
        main.removeCallbacks(clearUndo)
        main.postDelayed(clearUndo, 5000)
    }

    fun runUndo() {
        val u = _undo.value ?: return
        _undo.value = null
        main.removeCallbacks(clearUndo)
        runCatching { u.action() }
    }

    fun showToast(message: String) {
        _toast.value = message
        main.removeCallbacks(clearToast)
        main.postDelayed(clearToast, 2200)
    }






    /**
     * Relance la musique (casque / voiture branchés).
     * Si Tidal n'a plus de session ouverte, on envoie une touche « lecture » : Android la donne
     * au dernier lecteur utilisé, donc Tidal reprend là où il s'était arrêté.
     */
    fun resume(context: Context) {
        if (_state.value.isPlaying) return
        val c = controls
        if (c != null) {
            c.play()
            return
        }
        val am = context.getSystemService(android.media.AudioManager::class.java) ?: return
        val now = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY, 0))
        am.dispatchMediaKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY, 0))
    }


    // ---------- Suite de ta lecture ----------
    // Quand un son de la file Hush passe devant, Tidal oublie sa propre file (le son est lancé
    // par une recherche). On garde donc ce qui était prévu après, pour le reprendre ensuite.

    data class Ctx(val title: String, val artist: String)

    internal val _context = MutableStateFlow<List<Ctx>>(emptyList())
    val context: StateFlow<List<Ctx>> = _context.asStateFlow()






    internal fun hasPending() = _upNext.value.isNotEmpty() || MixQueue.hasNext() || _context.value.isNotEmpty()


    /** Un son voisin, pour l'aperçu quand tu glisses le mini-lecteur (cover = lien, fichier ou null). */
    data class Peek(val title: String, val artist: String, val cover: Any?)


    /** Lance le prochain son prévu. Renvoie false s'il n'y a rien. */
    internal fun fireNext(): Boolean {
        val n = _upNext.value.firstOrNull()
        if (n != null) {
            captureContext()
            _upNext.update { it.drop(1) }; saveUpNext()
            launch(n.id, n.fullTitle, n.playQuery)
            return true
        }
        // playlist mixte Hush : bloc suivant (local ou Tidal)
        if (MixQueue.hasNext()) { MixQueue.advance(); return true }
        val c = _context.value.firstOrNull() ?: return false
        _context.update { it.drop(1) }
        launch("${c.title}|${c.artist}", c.title, listOf(c.title, c.artist).filter { it.isNotBlank() }.joinToString(" "))
        return true
    }

    internal var upNextFiredFor: String? = null
    internal var lastKey: String? = null
    internal var lastRemaining = Long.MAX_VALUE
    // qui a demandé le dernier « suivant » / « précédent » (pour expliquer un son coupé avant la fin)
    internal var lastNextAt = 0L
    internal var lastNextFrom: String? = null
    internal var lastPrevAt = 0L
    internal var lastFireAt = 0L

    // ---------- Silence le temps du passage au son suivant ----------
    // Quand un son se termine, Tidal lance parfois SON son suivant pendant ~1 s avant que celui de Hush
    // arrive. On coupe le son du téléphone à ce moment-là et on le remet dès que le bon son démarre.
    internal var hushMuted = false
    internal val unmuteSafety = Runnable { unmuteHandoff() }

    internal fun muteHandoff() {
        if (!Features.flags.value.silentHandoff) return
        val am = appContext?.getSystemService(android.media.AudioManager::class.java) ?: return
        if (!hushMuted) runCatching { am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_MUTE, 0) }
        hushMuted = true
        main.removeCallbacks(unmuteSafety)
        main.postDelayed(unmuteSafety, 6000) // filet de sécurité : jamais coupé plus de 6 s
    }

    internal fun unmuteHandoff() {
        main.removeCallbacks(unmuteSafety)
        if (!hushMuted) return
        hushMuted = false
        val am = appContext?.getSystemService(android.media.AudioManager::class.java) ?: return
        runCatching { am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_UNMUTE, 0) }
    }
    internal var keyChangedAt = 0L
    internal var preMutedFor: String? = null

    /**
     * Coupe le son ~0,3 s avant la fin quand Hush va enchaîner lui-même (Tidal mettrait sinon son propre
     * son suivant pendant un instant). Si finalement le son continue, on remet le son au bout de 2,5 s.
     */
    internal fun maybePreMute(st: State, t: Track, key: String, remaining: Long) {
        if (!Features.flags.value.silentHandoff || hushMuted || preMutedFor == key) return
        if (!st.isPlaying || !hasPending() || queuedNext != null || LocalPlayer.active) return
        // on ne se fie qu'à une position donnée récemment par Tidal près de la fin (horloge de Hush seule = pas fiable)
        if (remaining > 300 || t.durationMs - st.positionMs > 15_000) return
        preMutedFor = key
        muteHandoff()
        main.postDelayed({ if (lastKey == key && upNextFiredFor != key) unmuteHandoff() }, 2500)
    }

    /** Quand le son en cours se termine, on lance le prochain de la file Hush. */
    internal fun checkUpNext(): Boolean {
        val st = _state.value
        val t = st.track ?: return hasPending()
        val key = "${t.title}|${t.artist}"
        val now = SystemClock.elapsedRealtime()

        // ---- le son vient de changer
        if (key != lastKey) {
            val prevKey = lastKey
            val prevRemaining = lastRemaining
            lastKey = key
            keyChangedAt = now
            lastRemaining = Long.MAX_VALUE
            val ours = now - lastFireAt < 6000 ||
                (st.launch != null && st.launch.status == LaunchStatus.Pending && now - st.launch.sentAt < 6000)
            // Tidal est passé tout seul au suivant juste avant nous : on rattrape tout de suite
            // (20 s de marge : l'horloge de Hush peut être un peu en avance ou en retard sur le vrai son)
            val missed = PlaybackRules.isMissedHandoff(prevKey != null, prevRemaining, upNextFiredFor == prevKey, ours, hasPending())
            // son coupé avant la fin : on cherche qui l'a passé, et on prévient (pas pour tes propres « suivant »)
            if (prevKey != null && prevRemaining in 20_000..3_600_000 && !LocalPlayer.active) {
                val cause = when {
                    now - lastPrevAt < 4000 -> null
                    now - lastNextAt < 4000 -> lastNextFrom
                    // c'est la bonne version demandée qui arrive (Hush a corrigé) : pas une coupure
                    st.launch != null && matches(t.title, st.launch.expected) && now - st.launch.sentAt < 15_000 -> null
                    now - lastFireAt < 6000 -> "Hush (file d'attente)"
                    st.launch != null && st.launch.status == LaunchStatus.Pending && now - st.launch.sentAt < 6000 -> null
                    else -> "Tidal ou une autre appli (pas Hush)"
                }
                if (cause != null) {
                    val title = prevKey.substringBefore('|')
                    Diag.add(Diag.Kind.EarlyCut, "$title · ${prevRemaining / 1000} s avant la fin · $cause")
                    appContext?.let { EarlyCutAlert.post(it, title, prevRemaining, cause) }
                }
            }
            upNextFiredFor = null
            // le bon son vient de démarrer : on remet le son (petit délai, le temps qu'il sorte vraiment)
            if (hushMuted && ours) main.postDelayed({ unmuteHandoff() }, 450)
            // sans blanc : Tidal vient d'enchaîner le son qu'on avait glissé dans sa file
            val qn = queuedNext
            if (qn != null && prevKey == queuedFor && matches(t.title, qn)) {
                queuedNext = null
                MixQueue.acceptNext()
                log("Enchaîné sans blanc : ${t.title}")
                Diag.add(Diag.Kind.Gapless, t.title)
                return true
            }
            if (missed) {
                upNextFiredFor = key
                lastFireAt = now
                log("Suivant auto · fin réelle du son, on enchaîne la file Hush (horloge : ${prevRemaining / 1000} s restantes)")
                Diag.add(Diag.Kind.CatchUp, prevKey?.substringBefore('|') ?: "")
                // on coupe tout de suite le son que Tidal a mis à la place, le temps de lancer le bon
                muteHandoff()
                runCatching { controller?.transportControls?.pause() }
                fireNext()
                return true
            }
        }

        if (!hasPending()) return false
        if (t.durationMs <= 0) return true
        val pos = st.livePosition()
        val remaining = t.durationMs - pos
        if (st.isPlaying) lastRemaining = remaining

        // même son relancé depuis le début (ex. il était aussi dans la file) : on réarme
        if (upNextFiredFor == key && pos < 5000 && now - lastFireAt > 4000) upNextFiredFor = null
        if (upNextFiredFor == key) return true

        // Anti-saut : on ne croit la position que si Tidal l'a donnée APRÈS le changement de son
        // (sinon c'est la position de l'ancien son → « fin » détectée à tort et le son saute)
        if (!PlaybackRules.trustPosition(st.positionUpdatedAt, st.positionMs, keyChangedAt, t.durationMs, now)) return true

        // dernières millisecondes du son : on coupe le son juste avant que Tidal enchaîne sur le sien
        maybePreMute(st, t, key, remaining)

        // Pendant la lecture, on ne coupe plus d'après l'horloge de Hush (elle pouvait être en avance
        // sur le vrai son → coupé en plein milieu). On attend la vraie fin : Tidal s'arrête ou enchaîne,
        // et on rattrape aussitôt. Exception : Tidal vient lui-même de donner une position proche de la fin.
        val ending = PlaybackRules.isEnding(st.isPlaying, t.durationMs, st.positionMs, pos, nearEndKey == key && now - nearEndAt < 8000)
        if (ending && now - lastFireAt > 2500) {
            upNextFiredFor = key
            lastFireAt = now
            log("Suivant auto · fin de « ${t.title} » (${pos / 1000}/${t.durationMs / 1000} s)")
            Diag.add(Diag.Kind.AutoEnd, t.title)
            muteHandoff()
            fireNext()
            return true
        }
        maybeQueueNext(key, now)
        return true
    }

    // ---------- Enchaînement sans blanc : on glisse le son suivant dans la file de Tidal ----------

    /** Formats d'identifiant essayés pour que Tidal accepte un son dans sa file. */
    internal val GAP_FORMATS = listOf<(String) -> String>({ it }, { "track:$it" }, { "tidal://track/$it" }, { "https://tidal.com/browse/track/$it" })
    internal var queuedFor: String? = null
    internal var queuedNext: String? = null
    internal var gapProbe = 0
    internal var tidalCompatCache: Pair<MediaSession.Token, MediaControllerCompat>? = null

    /** -1 = pas encore testé, -2 = Tidal refuse, sinon l'index du format qui marche. */
    internal var gapFormat: Int
        get() = appContext?.getSharedPreferences("hush", Context.MODE_PRIVATE)?.getInt("gapFmt", -1) ?: -1
        set(v) { appContext?.getSharedPreferences("hush", Context.MODE_PRIVATE)?.edit()?.putInt("gapFmt", v)?.apply() }

    internal fun tidalCompat(): MediaControllerCompat? {
        val c = tidalCtl ?: return null
        val ctx = appContext ?: return null
        tidalCompatCache?.let { if (it.first == c.sessionToken) return it.second }
        return runCatching { MediaControllerCompat(ctx, MediaSessionCompat.Token.fromToken(c.sessionToken)) }.getOrNull()
            ?.also { tidalCompatCache = c.sessionToken to it }
    }

    internal fun maybeQueueNext(key: String, now: Long) {
        if (!Features.flags.value.gapless || !MixQueue.active || LocalPlayer.active) return
        if (queuedFor == key || now - keyChangedAt < 3500) return
        val st = _state.value
        if (!st.isPlaying || st.launch?.status == LaunchStatus.Pending) return
        val next = MixQueue.peekNext() as? LocalMusic.Entry.T ?: return
        val fmt = gapFormat
        if (fmt == -2) return
        queuedFor = key
        val c = tidalCompat() ?: return
        if ((c.flags and MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS.toLong()) == 0L) {
            gapFormat = -2
            return log("Sans blanc : Tidal n'accepte pas qu'on remplisse sa file (enchaînement classique)")
        }
        val tryFmt = if (fmt >= 0) fmt else gapProbe
        val desc = android.support.v4.media.MediaDescriptionCompat.Builder()
            .setMediaId(GAP_FORMATS[tryFmt](next.r.id)).setTitle(next.r.fullTitle).setSubtitle(next.r.artistLine).build()
        val activeIdx = st.queue.indexOfFirst { it.id == st.activeQueueId }
        runCatching { if (activeIdx >= 0) c.addQueueItem(desc, activeIdx + 1) else c.addQueueItem(desc) }
            .onFailure { gapFormat = -2; return log("Sans blanc : refusé par Tidal (${it.message})") }
        queuedNext = next.r.fullTitle
        // on vérifie que le son est bien arrivé juste après celui en cours
        main.postDelayed({
            val q = _state.value.queue
            val ai = q.indexOfFirst { it.id == _state.value.activeQueueId }
            val landed = ai >= 0 && q.getOrNull(ai + 1)?.title?.let { matches(it, next.r.fullTitle) || matches(it, next.r.title) } == true
            if (landed) {
                if (gapFormat != tryFmt) { gapFormat = tryFmt; log("Enchaînement sans blanc activé") }
            } else {
                queuedNext = null
                if (fmt < 0) {
                    gapProbe++
                    if (gapProbe >= GAP_FORMATS.size) { gapFormat = -2; log("Sans blanc : Tidal n'a accepté aucun format (enchaînement classique)") }
                }
            }
        }, 2500)
    }

    /** Réessayer l'enchaînement sans blanc (après une mise à jour de Tidal, par exemple). */
    fun resetGapless() { gapFormat = -1; gapProbe = 0 }

    /** -1 pas encore testé, -2 Tidal refuse, sinon actif. */
    fun gaplessState(): Int = gapFormat

    // les sons locaux sont comptés à part (LocalMusic), pas dans l'historique Tidal
    internal val recordStable = Runnable { if (!LocalPlayer.active) _state.value.track?.let { History.record(it) } }

    // ---------- Sons similaires quand la file est finie ----------

    internal const val MIX_ACTION = "com.aspiro.tidal.player.action.PLAY_TRACK_MIX"
    internal var autoMixDoneFor: String? = null

    var autoMix: Boolean = true
        set(v) {
            field = v
            appContext?.getSharedPreferences("hush", Context.MODE_PRIVATE)?.edit()?.putBoolean("automix", v)?.apply()
            _state.update { it.copy(autoMix = v) }
        }

    /** Dernière action « radio » vue pendant la lecture (Tidal peut la retirer une fois arrêté). */
    internal var lastMixAction: PlaybackState.CustomAction? = null

    internal fun mixAction(): PlaybackState.CustomAction? =
        controller?.playbackState?.customActions?.firstOrNull { it.action == MIX_ACTION }
            ?: controller?.playbackState?.customActions?.firstOrNull { it.action.contains("MIX", ignoreCase = true) }

    /** Lance la radio Tidal « à partir de ce titre » (sons similaires). */
    fun playSimilar(): Boolean {
        clearContext()
        val action = mixAction() ?: lastMixAction
        if (action != null) {
            controls?.sendCustomAction(action, action.extras)
            log("Sons similaires lancés")
        } else {
            // plan B : un son du même artiste, pour que la musique ne s'arrête jamais
            val artist = _state.value.track?.artist?.split(",", "&")?.firstOrNull()?.trim().orEmpty()
            if (artist.isBlank()) return false
            controls?.playFromSearch(artist, Bundle())
            log("Sons de $artist lancés")
        }
        reapplyShuffleSoon()
        return true
    }

    internal var nearEndKey: String? = null
    internal var nearEndAt = 0L

    /**
     * La musique ne doit jamais s'arrêter : si Tidal arrive à la fin de sa file et s'arrête,
     * on enchaîne sur des sons similaires. On attend que Tidal s'arrête VRAIMENT
     * (au lieu de couper avant la fin), pour ne jamais passer devant un son qu'il avait prévu.
     */
    internal fun checkEndOfQueue() {
        if (LocalPlayer.active) return
        if (checkUpNext()) return
        val st = _state.value
        val t = st.track ?: return
        if (t.durationMs <= 0) return
        val key = "${t.title}|${t.artist}"
        val now = SystemClock.elapsedRealtime()
        if (st.isPlaying) {
            mixAction()?.let { lastMixAction = it }
            if (t.durationMs - st.livePosition() <= 3000) {
                nearEndKey = key
                nearEndAt = now
            } else if (nearEndKey != null && nearEndKey != key) {
                nearEndKey = null // Tidal est passé tout seul au son suivant : rien à faire
            }
            return
        }
        // arrêté juste après la fin d'un son (même si Tidal a remis le 1er son de la file à l'écran)
        // …mais pas si tu as juste mis pause avant la fin (l'horloge de Hush peut être un peu en avance)
        val reallyEnded = key != nearEndKey || t.durationMs - st.positionMs < 5000
        if (nearEndKey != null && now - nearEndAt < 8000 && autoMixDoneFor != nearEndKey && reallyEnded) {
            autoMixDoneFor = nearEndKey
            nearEndKey = null
            playSimilar()
        }
    }

    internal var power: android.os.PowerManager? = null
    internal fun screenOn(): Boolean {
        val pm = power ?: appContext?.getSystemService(android.os.PowerManager::class.java)?.also { power = it } ?: return true
        // le système Android peut redémarrer sous nos pieds (DeadSystemException) : on suppose l'écran allumé
        return runCatching { pm.isInteractive }.getOrDefault(true)
    }

    internal val endWatcher = object : Runnable {
        override fun run() {
            checkEndOfQueue()
            runCatching { Transitions.tick() }
            if (!LocalPlayer.active) {
                runCatching { LastPlay.track(_state.value) }
                runCatching { SkipTracker.track(_state.value) }
                runCatching { PlaylistResume.track(_state.value) }
            }
            runCatching { watchSessions() }
            // économie de batterie : on ne surveille de près (4 fois par seconde) que là où ça compte —
            // le début d'un son (fondu, point de départ) et ses 25 dernières secondes (fondu, enchaînement).
            // Le reste du son : une fois par seconde. En pause : toutes les 3 s.
            val st = _state.value
            val t = st.track
            val nowMs = SystemClock.elapsedRealtime()
            val remaining = if (t != null && t.durationMs > 0) t.durationMs - st.livePosition() else Long.MAX_VALUE
            val critical = nearEndKey != null || remaining < 25_000 || nowMs - keyChangedAt < 15_000 ||
                st.launch?.status == LaunchStatus.Pending || LocalPlayer.active
            // écran éteint : personne ne regarde l'écran, on surveille bien moins souvent —
            // sauf la fin du son et les lancements, qui gardent la même précision (enchaînements intacts)
            val screenOff = Features.flags.value.batterySaver && !screenOn()
            val endZone = nearEndKey != null || remaining < 25_000 || st.launch?.status == LaunchStatus.Pending || LocalPlayer.active
            val delay = when {
                !st.isPlaying && nearEndKey == null -> if (screenOff) 5000L else 1500L
                screenOff && endZone -> 250L
                screenOff && critical -> 600L
                screenOff -> 2500L
                critical -> 250L
                else -> 600L
            }
            main.postDelayed(this, delay)
        }
    }

    internal val compatCallback = object : MediaControllerCompat.Callback() {
        override fun onShuffleModeChanged(shuffleMode: Int) {
            // Tidal remet souvent l'aléatoire à zéro quand on change de son : on le réactive
            if (wantShuffle && shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_NONE) {
                main.postDelayed({ enforceShuffle() }, 300)
            }
        }
    }

    fun hasPermission(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** À appeler au lancement / retour dans l'appli : (re)branche tout. */
    fun start(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        HushWidget.watch(ctx)
        Transitions.init(ctx)
        if (!shuffleLoaded) {
            wantShuffle = ctx.getSharedPreferences("hush", Context.MODE_PRIVATE).getBoolean("shuffle", false)
            shuffleLoaded = true
            autoMix = ctx.getSharedPreferences("hush", Context.MODE_PRIVATE).getBoolean("automix", true)
            _upNext.value = History.decodeResults(ctx.getSharedPreferences("hush", Context.MODE_PRIVATE).getString("upNext", null))
            _state.update { it.copy(shuffle = wantShuffle) }
            main.postDelayed(endWatcher, 1000)
        }
        if (!hasPermission(ctx)) {
            _state.update { it.copy(hasPermission = false, connected = false) }
            return
        }
        _state.update { it.copy(hasPermission = true) }
        val component = ComponentName(ctx, MediaListenerService::class.java)
        try {
            val m = manager ?: ctx.getSystemService(MediaSessionManager::class.java).also {
                it.addOnActiveSessionsChangedListener(sessionsListener, component, main)
                manager = it
            }
            attach(m.getActiveSessions(component))
        } catch (e: SecurityException) {
            _state.update { it.copy(hasPermission = false) }
        }
    }

    internal var lastSessions: List<MediaController> = emptyList()
    /** La session de Tidal (même quand c'est un son local qui joue). */
    internal var tidalCtl: MediaController? = null

    /** À appeler quand la lecture locale démarre ou s'arrête. */
    fun reattach() { main.post { attach(lastSessions) } }

    /** Session Tidal pour lancer un son : coupe d'abord un éventuel son local. */
    internal fun tidal(): MediaController? {
        if (LocalPlayer.active) LocalPlayer.stop()
        return tidalCtl ?: controller?.takeIf { it.packageName.contains("tidal", ignoreCase = true) }
    }

    internal fun attach(sessions: List<MediaController>) {
        lastSessions = sessions
        _state.update { it.copy(activeSessions = sessions.map { s -> s.packageName }.distinct()) }

        val tidalTarget = sessions.firstOrNull { it.packageName == TIDAL_PACKAGE }
            ?: sessions.firstOrNull { it.packageName.contains("tidal", ignoreCase = true) }
            ?: browserController
        // on garde la dernière session Tidal connue : en pause (pendant un son local), Tidal peut sortir de la liste
        if (tidalTarget != null) tidalCtl = tidalTarget
        val target = (if (LocalPlayer.active) LocalPlayer.controller else null) ?: tidalTarget

        if (target != null && target.sessionToken == controller?.sessionToken) {
            refresh()
            return
        }
        controller?.unregisterCallback(callback)
        compat?.unregisterCallback(compatCallback)
        compat = null
        controller = target
        if (target != null) {
            target.registerCallback(callback, main)
            // version « compat » de la session : c'est elle qui sait régler l'aléatoire
            compat = try {
                appContext?.let { MediaControllerCompat(it, MediaSessionCompat.Token.fromToken(target.sessionToken)) }
                    ?.also { it.registerCallback(compatCallback, main) }
            } catch (e: Exception) {
                null
            }
            log("Connecté à ${target.packageName}")
        }
        refresh()
    }

    internal fun refresh() {
        val c = controller
        if (c == null) {
            _state.update {
                it.copy(connected = false, sessionPackage = null, track = null, isPlaying = false, actions = 0, queueSize = null)
            }
            return
        }
        val md = c.metadata
        val pb = c.playbackState
        val track = md?.let {
            Track(
                title = it.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ?: it.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "—",
                artist = it.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: it.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "",
                album = it.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
                art = it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
                durationMs = it.getLong(MediaMetadata.METADATA_KEY_DURATION),
            )
        }?.let { fresh ->
            // Android renvoie une NOUVELLE copie de la pochette à chaque appel : si l'image est la même,
            // on garde l'ancienne copie (sinon l'écran relance ses animations en boucle → clignotement)
            val prev = _state.value.track
            val sameArt = prev?.art != null && fresh.art != null && sameImage(prev.art, fresh.art)
            val withArt = if (sameArt) fresh.copy(art = prev!!.art) else fresh
            if (withArt == prev) prev else withArt
        }

        // on n'enregistre la pochette qu'une fois stable (Tidal envoie parfois l'ancienne avec le nouveau titre)
        main.removeCallbacks(recordStable)
        if (track != null) main.postDelayed(recordStable, 1500)

        val previousTitle = _state.value.track?.title
        val changed = track != null && track.title != previousTitle
        if (changed && wantShuffle) main.postDelayed({ enforceShuffle() }, 600)
        if (changed && previousTitle != null) {
            log("Titre → ${track!!.title}")
            _state.value.track?.let { rememberPlayed(it) }
        }
        // message au futur toi qui arrive à échéance sur ce son
        if (changed) runCatching { TimeCapsule.onPlay(track!!.title, track.artist) }
        if (changed) runCatching { com.theo.remote.ui.RatePrompt.onTrackChange(track!!.title, track.artist) }

        // On vérifie que le titre lancé depuis Hush est bien celui demandé
        val launch = _state.value.launch
        if (changed && launch?.status == LaunchStatus.Pending) {
            val ok = QueueLogic.isRightSong(track!!.title, track.artist, launch.expected, launch.artist)
            val canFix = !ok && Features.flags.value.fixVersions && launch.tries < 2 && launch.trackId.all { it.isDigit() }
            if (!ok && launch.tries == 0) Diag.add(Diag.Kind.OtherVersion, "« ${track.title} » au lieu de « ${launch.expected} »")
            if (canFix) {
                // mauvaise version : on relance la bonne (lien exact, puis recherche plus précise)
                log("Autre version : « ${track.title} » au lieu de « ${launch.expected} » → nouvel essai")
                main.postDelayed({ retryLaunch(launch) }, 250)
            } else {
                _state.update {
                    it.copy(launch = launch.copy(status = if (ok) LaunchStatus.Ok else LaunchStatus.OtherVersion, actual = track.title))
                }
                if (ok) lastGoodLaunch = launch
                if (ok && (launch.viaLink || launch.tries == 1)) { linkIgnored = 0; if (trackLinkMode != 1) trackLinkMode = 1 }
                else if (ok && launch.tries > 0) { log("Bonne version retrouvée : ${launch.expected}"); Diag.add(Diag.Kind.Fixed, launch.expected) }
            }
        }

        _state.update {
            it.copy(
                connected = true,
                sessionPackage = c.packageName,
                track = track,
                // « en chargement » compte comme « en lecture » : sinon l'interface clignote pause/lecture
                isPlaying = pb?.state in PLAYING_LIKE,
                advancing = pb?.state == PlaybackState.STATE_PLAYING,
                positionMs = pb?.position ?: 0L,
                positionUpdatedAt = pb?.lastPositionUpdateTime ?: 0L,
                speed = pb?.playbackSpeed?.takeIf { s -> s > 0f } ?: 1f,
                actions = pb?.actions ?: 0L,
                queueSize = c.queue?.size,
                queue = c.queue.orEmpty().map { q ->
                    QueueEntry(
                        id = q.queueId,
                        title = q.description.title?.toString() ?: "—",
                        subtitle = q.description.subtitle?.toString() ?: "",
                        icon = q.description.iconUri?.toString(),
                    )
                },
                activeQueueId = pb?.activeQueueItemId ?: -1,
                shuffle = wantShuffle,
                tidalFavorite = favoriteFrom(pb),
            )
        }
    }

    // ---------- Commandes ----------

    internal val controls get() = controller?.transportControls

    fun playPause() {
        val c = controls
        if (c == null) { showToast("Tidal ne joue rien : lance un son dans Tidal"); return }
        val playing = _state.value.isPlaying
        // réponse immédiate : le bouton change tout de suite, l'état réel de Tidal suit (et corrige si besoin)
        if (Features.flags.value.instantUi) _state.update { it.copy(isPlaying = !playing) }
        if (playing) c.pause() else c.play()
    }

    fun next(from: String? = null) {
        lastNextAt = SystemClock.elapsedRealtime(); lastNextFrom = from
        if (from != null) { log("Suivant ← $from"); Diag.skipFrom(from) }
        if (LocalPlayer.active) { LocalPlayer.next(); return }
        // 1) la file Hush passe avant celle de Tidal, puis la suite de ta lecture
        if (fireNext()) {
            lastFireAt = SystemClock.elapsedRealtime()
            upNextFiredFor = _state.value.track?.let { "${it.title}|${it.artist}" }
            return
        }
        val st = _state.value
        val before = st.track?.let { "${it.title}|${it.artist}" }
        // 2) Tidal sait qu'il n'a rien après → directement des sons similaires
        val knownLast = st.queue.isNotEmpty() && st.queue.lastOrNull()?.id == st.activeQueueId
        if (knownLast) {
            playSimilar()
            return
        }
        // 3) sinon on demande à Tidal, et s'il ne bouge pas, on enchaîne quand même
        controls?.skipToNext()
        main.postDelayed({
            val now = _state.value.track?.let { "${it.title}|${it.artist}" }
            if (now == before) playSimilar()
        }, 1500)
    }

    // ---------- Sons d'avant ----------
    // Les sons lancés par Hush (lien exact, file « À suivre »…) n'ont pas d'historique côté Tidal :
    // « précédent » n'y faisait rien. Hush retient donc lui-même les derniers sons passés.
    internal data class Back(val title: String, val artist: String, val id: String?)
    internal val backStack = ArrayDeque<Back>()
    internal var goingBack = false
    /** Le dernier son lancé par Hush et bien parti (pour retrouver son lien exact en revenant dessus). */
    internal var lastGoodLaunch: Launch? = null

    internal fun rememberPlayed(prev: Track) {
        if (goingBack) { goingBack = false; return }
        val l = lastGoodLaunch
        val id = l?.trackId?.takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } && QueueLogic.sameTitle(prev.title, l.expected) }
        if (backStack.lastOrNull()?.let { QueueLogic.sameTitle(it.title, prev.title) } == true) return
        backStack.addLast(Back(prev.title, prev.artist, id))
        while (backStack.size > 50) backStack.removeFirst()
    }

    fun previous() {
        val now = SystemClock.elapsedRealtime()
        // deux appuis rapprochés : le 1er a remis le son au début, le 2e revient vraiment au son d'avant
        val again = now - lastPrevAt < 2500
        lastPrevAt = now
        if (LocalPlayer.active) { controls?.skipToPrevious(); return }
        if (!again && _state.value.livePosition() > 3000) { seekTo(0); return }
        // playlist enchaînée par Hush : c'est Hush qui y revient
        if (MixQueue.active) { MixQueue.back(); return }
        // Tidal connaît le son d'avant dans sa file
        val st = _state.value
        val qi = st.queue.indexOfFirst { it.id == st.activeQueueId }
        if (qi > 0) { controls?.skipToPrevious(); return }
        // sinon : le dernier son passé retenu par Hush (et le son actuel revient juste après)
        val b = backStack.removeLastOrNull()
        if (b == null) { controls?.skipToPrevious(); return }
        st.track?.let { cur -> _context.update { listOf(Ctx(cur.title, cur.artist)) + it } }
        goingBack = true
        launch(b.id ?: "${b.title}|${b.artist}", b.title, listOf(b.title, b.artist).filter { it.isNotBlank() }.joinToString(" "), b.artist)
    }

    fun seekTo(ms: Long) {
        controls?.seekTo(ms)
    }


    // ---------- Lancer un résultat de recherche ----------




    /** -1 pas encore su, 1 Tidal accepte les liens de playlists, 0 il les ignore. */
    internal var playlistLinkMode: Int
        get() = appContext?.getSharedPreferences("hush", Context.MODE_PRIVATE)?.getInt("plLink", -1) ?: -1
        set(v) { appContext?.getSharedPreferences("hush", Context.MODE_PRIVATE)?.edit()?.putInt("plLink", v)?.apply() }
    internal var playlistIgnored = 0

    internal var playlistSentAt = 0L




    internal var upNextOriginal: List<TidalApi.Result>? = null



    internal const val SHUFFLE_ACTION = "com.aspiro.tidal.player.action.TOGGLE_SHUFFLE"













    /** -1 pas encore su, 1 Tidal accepte les liens de sons, 0 il les ignore. */
    internal var trackLinkMode: Int
        get() = appContext?.getSharedPreferences("hush", Context.MODE_PRIVATE)?.getInt("trackLink", -1) ?: -1
        set(v) { appContext?.getSharedPreferences("hush", Context.MODE_PRIVATE)?.edit()?.putInt("trackLink", v)?.apply() }
    internal var linkIgnored = 0



    internal fun norm(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")

    /** Titre identique, version comprise (« MEMORIZING (SLOWED) » ≠ « MEMORIZING »). */
    internal fun matches(actual: String, expected: String): Boolean = norm(actual) == norm(expected)

    // ---------- Tests : lancer un titre précis sans ouvrir Tidal ----------



    internal var lastWake = 0L

    /**
     * - Tidal se remet à jouer pendant qu'un son local est en pause → on rend la main à Tidal.
     * - Plus de session Tidal (appli tuée) → on la réveille en arrière-plan, au plus une fois par minute.
     */
    internal fun watchSessions() {
        val t = tidalCtl
        if (LocalPlayer.active && !LocalPlayer.wantsPlay && t?.playbackState?.state == PlaybackState.STATE_PLAYING) {
            LocalPlayer.stop()
            return
        }
        if (t == null && !LocalPlayer.active && _state.value.hasPermission && Features.flags.value.autoOpenTidal) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastWake > 60_000) {
                lastWake = now
                appContext?.let { wakeInBackground(it) }
            }
        }
    }

    internal var browser: android.media.browse.MediaBrowser? = null
    internal var browserController: MediaController? = null

    /**
     * Réveille Tidal sans l'afficher : on se branche à son service média (celui qu'utilise Android Auto).
     * Ça démarre Tidal en arrière-plan et, s'il accepte, on récupère direct sa session.
     */
    fun wakeInBackground(context: Context): Boolean {
        val ctx = context.applicationContext
        if (browser?.isConnected == true) return true
        val svc = ctx.packageManager.queryIntentServices(
            Intent(android.service.media.MediaBrowserService.SERVICE_INTERFACE).setPackage(TIDAL_PACKAGE), 0,
        ).firstOrNull()?.serviceInfo ?: return false.also { log("Tidal : pas de service média trouvé") }
        runCatching { browser?.disconnect() }
        val b = android.media.browse.MediaBrowser(ctx, ComponentName(svc.packageName, svc.name), object : android.media.browse.MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                val token = browser?.sessionToken ?: return
                browserController = MediaController(ctx, token)
                log("Tidal réveillé en arrière-plan")
                if (controller == null) attach(emptyList())
            }
            override fun onConnectionFailed() { log("Tidal a refusé la connexion directe") }
            override fun onConnectionSuspended() { browserController = null }
        }, null)
        browser = b
        return runCatching { b.connect(); true }.getOrDefault(false)
    }

    fun openTidal(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(TIDAL_PACKAGE)
        if (intent == null) {
            log("Tidal n'est pas installé sur ce téléphone")
            return
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Pour le rapport de bug. */
    fun note(message: String) = log(message)

    internal fun log(message: String) {
        val line = "${clock.format(Date())}  $message"
        _state.update { it.copy(log = (listOf(line) + it.log).take(40)) }
    }
}
