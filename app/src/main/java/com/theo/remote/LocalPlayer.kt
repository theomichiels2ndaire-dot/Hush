package com.theo.remote

import android.content.Context
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Lecteur de Hush pour tes fichiers locaux.
 * Il expose sa propre session média : tout Hush (lecteur, notif, stats) le pilote
 * exactement comme il pilote Tidal. Dès que Tidal reprend le son, il s'efface.
 */
object LocalPlayer {
    private var ctx: Context? = null
    private var mp: MediaPlayer? = null
    private var session: MediaSession? = null
    private var focus: AudioFocusRequest? = null
    private val main = Handler(Looper.getMainLooper())

    private var queue: List<LocalMusic.Track> = emptyList()
    private var index = 0
    private var prepared = false

    /** true tant qu'un son local est la lecture en cours de Hush. */
    @Volatile var active = false
        private set
    val controller: MediaController? get() = session?.controller

    private val _now = MutableStateFlow<String?>(null)
    /** Identifiant du son local en cours (pour le surligner dans les listes). */
    val now: StateFlow<String?> = _now

    val current get() = queue.getOrNull(index)

    /** Lecture lancée par toi (efface une éventuelle playlist mixte en cours). */
    fun play(context: Context, list: List<LocalMusic.Track>, start: Int = 0) {
        MixQueue.clear()
        playRun(context, list, start)
    }

    /** Joue une suite de sons locaux (utilisé aussi par les playlists mixtes). */
    internal fun playRun(context: Context, list: List<LocalMusic.Track>, start: Int = 0, at: Long = 0L, autoStart: Boolean = true) {
        if (list.isEmpty()) return
        ctx = context.applicationContext
        countCurrent()
        queue = list
        index = start.coerceIn(0, list.lastIndex)
        ensureSession()
        if (autoStart && !requestFocus()) return TidalRemote.showToast("Impossible de prendre la main sur le son")
        active = true
        wantsPlay = autoStart
        session?.isActive = true
        load(at, autoStart)
        TidalRemote.reattach()
        prefs()?.edit()?.putBoolean("localLast", true)?.apply()
        main.removeCallbacks(saver); main.postDelayed(saver, 5000)
    }

    /** « Lire ensuite » : glisse des sons juste après celui en cours. */
    fun playNext(context: Context, tracks: List<LocalMusic.Track>) {
        if (!active || queue.isEmpty()) return play(context, tracks, 0)
        queue = queue.take(index + 1) + tracks + queue.drop(index + 1)
        save()
    }

    // ---------------------------------------------------------------- fondu, volume égalisé, anti-saut

    private var lastPos = 0L
    private var retriedFor: String? = null
    private var duck = 1f
    private var fadeInFrom = 0L
    private const val FADE_MS = 3000L
    private var fx: android.media.audiofx.DynamicsProcessing? = null

    /** Toutes les 100 ms : on retient la position (pour reprendre après un raté) et on fait les fondus. */
    private val ticker = object : Runnable {
        override fun run() {
            val p = mp ?: return
            if (!prepared) return
            runCatching { if (p.isPlaying) lastPos = p.currentPosition.toLong() }
            applyVolume()
            main.postDelayed(this, 100)
        }
    }

    private fun applyVolume() {
        val p = mp ?: return
        var v = 1f
        if (Features.flags.value.localFade && prepared) {
            val d = current?.durationMs ?: 0L
            val pos = lastPos
            // sortie : on baisse les 3 dernières secondes, s'il y a un son après
            if (d > 20_000 && d - pos in 0..FADE_MS && (index < queue.lastIndex || MixQueue.hasNext())) v = ((d - pos).toFloat() / FADE_MS).coerceIn(0.05f, 1f)
            // entrée : on monte en douceur au début du son
            if (fadeInFrom > 0) {
                val e = SystemClock.elapsedRealtime() - fadeInFrom
                if (e < FADE_MS) v = minOf(v, (0.15f + 0.85f * e / FADE_MS)) else fadeInFrom = 0L
            }
        }
        runCatching { p.setVolume(v * duck, v * duck) }
    }

    /** Volume égalisé : un compresseur doux + un limiteur, pour que tes fichiers sonnent pareil. */
    private fun attachFx(p: MediaPlayer) {
        releaseFx()
        if (!Features.flags.value.localNormalize || android.os.Build.VERSION.SDK_INT < 28) return
        fx = runCatching {
            val cfg = android.media.audiofx.DynamicsProcessing.Config.Builder(
                android.media.audiofx.DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                false, 0, true, 1, false, 0, true,
            ).build()
            android.media.audiofx.DynamicsProcessing(0, p.audioSessionId, cfg).apply {
                setMbcBandAllChannelsTo(0, android.media.audiofx.DynamicsProcessing.MbcBand(
                    true, 20000f, 5f, 200f, 3f, -24f, 6f, -90f, 1f, 0f, 6f,
                ))
                setLimiterAllChannelsTo(android.media.audiofx.DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1.5f, 0f))
                setEnabled(true)
            }
        }.getOrNull()
    }

    private fun releaseFx() { runCatching { fx?.release() }; fx = null }

    /** Réglage changé : on applique tout de suite au son en cours. */
    fun refreshFx() {
        main.post {
            val p = mp
            if (p != null && prepared) attachFx(p) else releaseFx()
            applyVolume()
        }
    }

    // ---------------------------------------------------------------- reprise après fermeture

    private fun prefs() = ctx?.getSharedPreferences("hush", Context.MODE_PRIVATE)

    private val saver = object : Runnable {
        override fun run() {
            if (!active) return
            save()
            main.postDelayed(this, 5000)
        }
    }

    private fun save() {
        val p = prefs() ?: return
        val pos = if (prepared) runCatching { mp?.currentPosition?.toLong() }.getOrNull() ?: 0L else 0L
        p.edit().putString("localQueue", queue.joinToString("\n") { it.id }).putInt("localIndex", index).putLong("localPos", pos).apply()
    }

    /** Au lancement de Hush : on retrouve ton son local là où tu l'avais laissé (en pause). */
    fun restore(context: Context) {
        if (active) return
        ctx = context.applicationContext
        val p = prefs() ?: return
        if (!p.getBoolean("localLast", false)) return
        val ids = p.getString("localQueue", null)?.split("\n").orEmpty()
        val list = ids.mapNotNull { LocalMusic.byId(it) }
        if (list.isEmpty()) return
        val i = p.getInt("localIndex", 0).coerceIn(0, list.lastIndex)
        // Tidal joue déjà ? On ne touche à rien.
        if (TidalRemote.state.value.isPlaying) return
        playRun(context, list, i, p.getLong("localPos", 0L), autoStart = false)
    }

    // ---------------------------------------------------------------- stats locales

    private var startedAt = 0L
    private var listenedMs = 0L

    /** Compte l'écoute du son en cours s'il a tourné au moins 30 s. */
    private fun countCurrent() {
        val t = current ?: return
        if (startedAt > 0) { listenedMs += SystemClock.elapsedRealtime() - startedAt; startedAt = 0 }
        if (listenedMs >= 30_000) LocalMusic.countPlay(t.id, listenedMs)
        listenedMs = 0
    }

    private fun clockOn() { if (startedAt == 0L) startedAt = SystemClock.elapsedRealtime() }
    private fun clockOff() { if (startedAt > 0) { listenedMs += SystemClock.elapsedRealtime() - startedAt; startedAt = 0 } }

    val isPlaying get() = prepared && mp?.isPlaying == true

    /** true quand la lecture locale doit jouer (même pendant le chargement du fichier). */
    @Volatile var wantsPlay = false
        private set

    private fun ensureSession() {
        if (session != null) return
        val c = ctx ?: return
        session = MediaSession(c, "HushLocal").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = previous()
                override fun onSeekTo(pos: Long) = seekTo(pos)
                override fun onStop() = stop()
            })
        }
    }

    private fun load(at: Long = 0L, autoStart: Boolean = true) {
        val c = ctx ?: return
        val t = current ?: return stop()
        runCatching { mp?.release() }
        releaseFx()
        prepared = false
        if (current?.id != retriedFor) { lastPos = 0L; retriedFor = null }
        clockOff(); listenedMs = 0
        _now.value = t.id
        publishMeta(t)
        publishState(PlaybackState.STATE_BUFFERING)
        mp = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            setOnPreparedListener {
                if (at > 0) it.seekTo(at.toInt())
                prepared = true
                attachFx(it)
                fadeInFrom = if (at < 1500) SystemClock.elapsedRealtime() else 0L
                applyVolume()
                if (autoStart) { it.start(); clockOn() }
                publishState()
                main.removeCallbacks(ticker); main.post(ticker)
            }
            setOnCompletionListener {
                // fin anormale (fichier coupé, lecteur système relancé) : on reprend où on en était au lieu de sauter
                val d = t.durationMs
                if (d > 15_000 && lastPos in 1_000 until d - 4_000 && retriedFor != t.id) {
                    retriedFor = t.id
                    TidalRemote.note("Local · fin anormale à ${lastPos / 1000}/${d / 1000} s, on reprend")
                    main.post { load(lastPos) }
                    return@setOnCompletionListener
                }
                countCurrent()
                when {
                    index < queue.lastIndex -> { index++; load() }
                    MixQueue.hasNext() -> MixQueue.advance()
                    else -> { publishState(PlaybackState.STATE_STOPPED); seekTo(0) }
                }
            }
            setOnErrorListener { _, what, extra ->
                TidalRemote.note("Local · erreur $what/$extra sur « ${t.title} » à ${lastPos / 1000} s")
                Diag.add(Diag.Kind.LocalError, t.title)
                if (lastPos > 1_000 && retriedFor != t.id) {
                    // une fois : on relance le fichier à la même seconde
                    retriedFor = t.id
                    main.post { load(lastPos) }
                    return@setOnErrorListener true
                }
                TidalRemote.showToast("Lecture impossible : ${t.title}")
                if (index < queue.lastIndex) main.post { index++; load() }
                true
            }
            try {
                setDataSource(c, Uri.parse(t.uri))
                prepareAsync()
            } catch (e: Exception) {
                TidalRemote.showToast("Fichier introuvable : ${t.title}")
            }
        }
    }

    private fun publishMeta(t: LocalMusic.Track) {
        val art = t.art?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
        session?.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, t.durationMs)
                .apply { art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build()
        )
    }

    private fun publishState(forced: Int? = null) {
        val p = mp
        val playing = prepared && p?.isPlaying == true
        val st = forced ?: if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val pos = if (prepared) runCatching { p?.currentPosition?.toLong() }.getOrNull() ?: 0L else 0L
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP
                )
                .setState(st, pos, if (st == PlaybackState.STATE_PLAYING) 1f else 0f, SystemClock.elapsedRealtime())
                .build()
        )
    }

    fun resume() {
        if (!active) return
        requestFocus()
        wantsPlay = true
        if (prepared) { mp?.start(); clockOn(); publishState() } else load()
    }

    fun pause() {
        if (prepared) runCatching { mp?.pause() }
        wantsPlay = false
        clockOff()
        publishState()
        save()
    }

    fun next() {
        if (queue.isEmpty()) return
        countCurrent()
        if (index >= queue.lastIndex && MixQueue.hasNext()) { MixQueue.advance(); return }
        index = if (index < queue.lastIndex) index + 1 else 0
        load()
    }

    fun currentIndex() = index

    fun previous() {
        val pos = if (prepared) mp?.currentPosition ?: 0 else 0
        // début d'un passage local dans une playlist mixte : on revient au son d'avant de la playlist
        if (pos <= 3000 && index == 0 && MixQueue.active && MixQueue.back()) return
        if (pos > 3000 || index == 0) { seekTo(0); if (prepared && mp?.isPlaying != true) resume(); return }
        index--
        load()
    }

    fun seekTo(ms: Long) {
        if (prepared) runCatching { mp?.seekTo(ms.toInt()) }
        publishState()
    }

    /** Arrête la lecture locale et rend la main à Tidal. */
    fun stop() {
        if (!active && mp == null) return
        countCurrent()
        save()
        wantsPlay = false
        main.removeCallbacks(saver)
        prefs()?.edit()?.putBoolean("localLast", false)?.apply()
        active = false
        prepared = false
        runCatching { mp?.release() }
        releaseFx()
        main.removeCallbacks(ticker)
        mp = null
        _now.value = null
        publishState(PlaybackState.STATE_STOPPED)
        session?.isActive = false
        focus?.let { f -> ctx?.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(f) }
        TidalRemote.reattach()
    }

    /** On prend le son (Tidal se met en pause). Si Tidal le reprend, on s'arrête. */
    private fun requestFocus(): Boolean {
        val am = ctx?.getSystemService(AudioManager::class.java) ?: return false
        val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> stop()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { duck = 0.3f; applyVolume() }
                    AudioManager.AUDIOFOCUS_GAIN -> { duck = 1f; applyVolume() }
                }
            }, main)
            .build().also { focus = it }
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
}

/**
 * Playlist mixte (fichiers locaux + sons Tidal) : on joue chaque bloc avec le bon lecteur,
 * et on enchaîne sur le suivant quand l'un finit.
 */
object MixQueue {
    /** Toute la playlist, dans l'ordre de lecture, et la position du prochain bloc. */
    private var all: List<LocalMusic.Entry> = emptyList()
    private var cursor = 0
    private var ctx: Context? = null
    /** true pendant qu'on lance nous-mêmes le bloc suivant (pour ne pas s'effacer). */
    @Volatile var advancing = false
        private set

    /** Ordre d'origine (pour couper l'aléatoire et retrouver l'ordre de la playlist). */
    private var original: List<LocalMusic.Entry>? = null
    private val _upcoming = MutableStateFlow<List<LocalMusic.Entry>>(emptyList())
    /** La suite de la playlist (affichée dans la file d'attente du lecteur). */
    val upcoming: StateFlow<List<LocalMusic.Entry>> = _upcoming

    val active get() = all.isNotEmpty()

    private fun publish() { _upcoming.value = all.drop(cursor).take(150) }

    /** Il reste quelque chose à jouer (la playlist reboucle tant qu'elle a plus d'un son). */
    fun hasNext() = all.size > 1 || (all.size == 1 && cursor < 1)
    fun clear() { if (!advancing) { all = emptyList(); cursor = 0; original = null; publish() } }

    /** Pour « Annuler » : une copie de la suite, qu'on peut remettre telle quelle. */
    data class Snap(val all: List<LocalMusic.Entry>, val cursor: Int, val original: List<LocalMusic.Entry>?)
    fun snapshot() = Snap(all, cursor, original)
    fun restore(s: Snap) { all = s.all; cursor = s.cursor; original = s.original; publish() }

    private fun title(e: LocalMusic.Entry) = when (e) { is LocalMusic.Entry.L -> e.t.title; is LocalMusic.Entry.T -> e.r.title }
    private fun artist(e: LocalMusic.Entry) = when (e) { is LocalMusic.Entry.L -> e.t.artist; is LocalMusic.Entry.T -> e.r.artistLine }

    /** Le bouton aléatoire du lecteur : on mélange (ou on remet dans l'ordre) la suite de la playlist. */
    fun setShuffle(on: Boolean) {
        if (all.isEmpty()) return
        if (on) {
            if (original == null) original = all
            all = all.take(cursor) + SmartShuffle.order(all.drop(cursor), { title(it) }, { artist(it) })
        } else {
            val o = original ?: return
            val cur = all.getOrNull(cursor - 1)
            all = o
            cursor = (if (cur != null) o.indexOf(cur) + 1 else 0).coerceIn(0, o.size)
            original = null
        }
        publish()
    }

    /** La suite de la playlist arrive après le lancement (chargement en arrière-plan). */
    fun extend(more: List<LocalMusic.Entry>, shuffle: Boolean) {
        if (all.isEmpty() || more.isEmpty()) return
        original = original?.plus(more)
        all = if (shuffle) all.take(cursor) + SmartShuffle.order(all.drop(cursor) + more, { title(it) }, { artist(it) }) else all + more
        publish()
    }

    /** Où commence ce qui joue maintenant (un son Tidal, ou le début d'un passage local). */
    private var lastStart = 0

    /** Le prochain son de la playlist (sans le lancer). */
    fun peekNext(): LocalMusic.Entry? = if (all.size < 2) null else all[if (cursor >= all.size) 0 else cursor]

    /** Tidal a enchaîné lui-même le son qu'on lui avait glissé : on avance juste le curseur. */
    fun acceptNext() {
        if (all.isEmpty()) return
        val i = if (cursor >= all.size) 0 else cursor
        lastStart = i
        cursor = i + 1
        publish()
    }

    /** Bouton « précédent » : on rejoue le son d'avant dans la playlist. */
    fun back(): Boolean {
        if (all.isEmpty()) return false
        val cur = lastStart + if (LocalPlayer.active) LocalPlayer.currentIndex() else 0
        val prev = if (cur > 0) cur - 1 else all.size - 1
        TidalRemote.note("Précédent → ${title(all[prev])}")
        cursor = prev
        advance()
        return true
    }

    /** Toucher un son de la suite : on y saute directement. */
    fun jumpTo(offset: Int) {
        if (cursor + offset >= all.size) return
        cursor += offset
        advance()
    }

    fun play(context: Context, entries: List<LocalMusic.Entry>, start: Int = 0, original: List<LocalMusic.Entry>? = null) {
        ctx = context.applicationContext
        TidalRemote.startingPlaylist()
        all = entries
        this.original = original
        cursor = start.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
        advancing = false
        TidalRemote.note("Playlist mixte : ${entries.count { it is LocalMusic.Entry.T }} Tidal + ${entries.count { it is LocalMusic.Entry.L }} local")
        advance()
    }

    /** Lance le bloc suivant : tous les sons locaux d'affilée d'un coup, ou un son Tidal. Reboucle à la fin. */
    fun advance() {
        val c = ctx ?: return
        if (all.isEmpty()) return
        if (cursor >= all.size) cursor = 0
        val first = all[cursor]
        lastStart = cursor
        advancing = true
        try {
            when (first) {
                is LocalMusic.Entry.L -> {
                    val run = all.drop(cursor).takeWhile { it is LocalMusic.Entry.L }.map { (it as LocalMusic.Entry.L).t }
                    cursor += run.size
                    TidalRemote.note("Mix → local : ${run.first().title}" + if (run.size > 1) " (+${run.size - 1})" else "")
                    LocalPlayer.playRun(c, run, 0)
                }
                is LocalMusic.Entry.T -> {
                    cursor += 1
                    TidalRemote.note("Mix → Tidal : ${first.r.title}")
                    TidalRemote.play(first.r)
                }
            }
        } finally {
            advancing = false
            publish()
        }
    }
}
