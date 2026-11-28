package com.theo.remote

import com.theo.remote.TidalRemote.Back
import com.theo.remote.TidalRemote.Ctx
import com.theo.remote.TidalRemote.Launch
import com.theo.remote.TidalRemote.LaunchStatus
import com.theo.remote.TidalRemote.Peek
import com.theo.remote.TidalRemote.QueueEntry
import com.theo.remote.TidalRemote.State
import com.theo.remote.TidalRemote.Track
import com.theo.remote.TidalRemote.Undo
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

// Lancer des sons, des playlists, des artistes et des albums dans Tidal (et vérifier que c'est le bon).
// (fait partie de TidalRemote : fonctions séparées pour que chaque partie reste lisible et testable)

/** Comme playSequence, mais sans filtre : la liste est jouée telle quelle (trajet). */
fun TidalRemote.playExact(list: List<Pair<String, String>>) {
    val first = list.firstOrNull() ?: return
    playTitle(first.first, first.second)
    _context.value = list.drop(1).take(80).map { Ctx(it.first, it.second) }
}

fun TidalRemote.play(result: TidalApi.Result) {
    clearContext()
    launch(result.id, result.fullTitle, result.playQuery, result.artists.firstOrNull().orEmpty())
}

/** Relancer un titre de l'historique (titre + artiste tels que Tidal les affiche). */
fun TidalRemote.playTitle(title: String, artist: String) {
    clearContext()
    launch("$title|$artist", title, listOf(title, artist).filter { it.isNotBlank() }.joinToString(" "))
}

/**
 * Lance une playlist. Avec son identifiant : par son lien exact (TA playlist, pas une autre qui porte le même nom) ;
 * si Tidal ignore le lien, ou sans identifiant : recherche par nom.
 */
fun TidalRemote.playPlaylist(name: String, id: String? = null, woke: Boolean = false) {
    clearContext()
    startingPlaylist()
    val c = tidal() ?: run {
        // Tidal fermé (coupé par Android) : on le réveille et on relance une fois
        if (!woke) {
            appContext?.let { wakeInBackground(it) }
            showToast("Tidal se réveille…")
            main.postDelayed({ if (tidalCtl != null) playPlaylist(name, id, woke = true) else showToast("Ouvre Tidal une fois, puis relance") }, 2500)
        }
        return log("Pas de session Tidal active — réveil de Tidal")
    }
    val sent = SystemClock.elapsedRealtime()
    playlistSentAt = sent
    if (id != null && Features.flags.value.exactLaunch && playlistLinkMode != 0) {
        val beforeTrack = _state.value.track?.title
        val beforeQueue = c.queueTitle?.toString()
        val beforeIds = _state.value.queue.map { it.id }
        c.transportControls.playFromUri(Uri.parse("https://tidal.com/browse/playlist/$id"), Bundle())
        log("Playlist lancée (lien exact) : $name")
        // on vérifie que Tidal a VRAIMENT lancé la playlist, sinon recherche par nom
        val watch = object : Runnable {
            override fun run() {
                if (playlistSentAt != sent) return // une autre lecture a été lancée entre-temps
                val ctl = tidal() ?: return
                val st = _state.value
                val pbState = ctl.playbackState?.state
                val qTitle = ctl.queueTitle?.toString()
                val named = qTitle != null && QueueLogic.norm(qTitle) == QueueLogic.norm(name)
                val changed = named || st.track?.title != beforeTrack ||
                    (qTitle != beforeQueue && qTitle != null) || st.queue.map { it.id } != beforeIds
                val loading = pbState == PlaybackState.STATE_BUFFERING || pbState == PlaybackState.STATE_CONNECTING
                val waited = SystemClock.elapsedRealtime() - sent
                when {
                    changed -> {
                        playlistIgnored = 0
                        if (playlistLinkMode != 1) playlistLinkMode = 1
                        // chargée mais en pause : on lance la lecture
                        if (!loading && pbState != PlaybackState.STATE_PLAYING) ctl.transportControls.play()
                    }
                    loading && waited < 10_000 -> main.postDelayed(this, 200)
                    waited < 1200 -> main.postDelayed(this, 200)
                    else -> {
                        playlistIgnored++
                        if (playlistIgnored >= 2 && playlistLinkMode == -1) { playlistLinkMode = 0; log("Tidal ignore les liens de playlists : recherche par nom") }
                        log("Lien ignoré par Tidal → recherche par nom")
                        searchPlaylist(ctl, name)
                    }
                }
            }
        }
        main.postDelayed(watch, 300)
    } else searchPlaylist(c, name)
    appContext?.getSharedPreferences("hush", Context.MODE_PRIVATE)?.edit()?.putString("lastPlaylistName", name)?.apply()
    reapplyShuffleSoon()
}

internal fun TidalRemote.searchPlaylist(c: MediaController, name: String) {
    val extras = Bundle().apply {
        putString(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/playlist")
        putString("android.intent.extra.playlist", name)
    }
    c.transportControls.playFromSearch(name, extras)
    log("Playlist lancée : $name")
}

/**
 * Lance une playlist dans Tidal puis saute directement au son touché :
 * la suite reste exactement l'ordre de la playlist. Si Tidal ne montre pas ce son dans sa file
 * (playlist très longue), [fallback] prend le relais.
 */
fun TidalRemote.playPlaylistFrom(name: String, fullTitle: String, title: String, occurrence: Int, id: String? = null, fallback: () -> Unit) {
    val before = _state.value.queue.map { it.id to it.title }
    setShuffle(false)
    playPlaylist(name, id)
    val started = SystemClock.elapsedRealtime()
    val check = object : Runnable {
        override fun run() {
            val st = _state.value
            val q = st.queue
            val waited = SystemClock.elapsedRealtime() - started
            val fresh = q.isNotEmpty() && (q.map { it.id to it.title } != before || waited > 1200)
            if (fresh) {
                val hits = q.filter { matches(it.title, fullTitle) || matches(it.title, title) }
                val hit = hits.getOrNull(occurrence) ?: hits.firstOrNull()
                if (hit != null) {
                    if (hit.id != st.activeQueueId) controls?.skipToQueueItem(hit.id)
                    log("Playlist « $name » lancée à partir de « $title »")
                    return
                }
                if (waited > 2000) { log("« $title » pas dans la file de Tidal : Hush enchaîne la playlist"); fallback(); return }
            }
            if (waited > 5000) { fallback(); return }
            main.postDelayed(this, 150)
        }
    }
    main.postDelayed(check, 150)
}

/** Playlist en aléatoire : on active l'aléatoire puis on la lance. */
fun TidalRemote.playPlaylistShuffled(name: String, id: String? = null) {
    setShuffle(true)
    main.postDelayed({ playPlaylist(name, id) }, 80)
}

/** Lance les sons d'un artiste (recherche « artiste » côté Tidal). */
fun TidalRemote.playArtist(name: String) {
    clearContext()
    cancelLaunch(); playlistSentAt = 0L
    val c = tidal() ?: return log("Pas de session Tidal active — lance un son dans Tidal d'abord")
    val extras = Bundle().apply {
        putString(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/artist")
        putString(android.provider.MediaStore.EXTRA_MEDIA_ARTIST, name)
    }
    c.transportControls.playFromSearch(name, extras)
    log("Artiste lancé : $name")
    reapplyShuffleSoon()
}

/** Lance un album (recherche « album » côté Tidal). */
fun TidalRemote.playAlbum(title: String, artist: String) {
    clearContext()
    cancelLaunch(); playlistSentAt = 0L
    val c = tidal() ?: return log("Pas de session Tidal active — lance un son dans Tidal d'abord")
    val extras = Bundle().apply {
        putString(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/album")
        putString(android.provider.MediaStore.EXTRA_MEDIA_ALBUM, title)
        if (artist.isNotBlank()) putString(android.provider.MediaStore.EXTRA_MEDIA_ARTIST, artist)
    }
    c.transportControls.playFromSearch(listOf(title, artist).filter { it.isNotBlank() }.joinToString(" "), extras)
    log("Album lancé : $title")
    reapplyShuffleSoon()
}

fun TidalRemote.skipToQueueItem(id: Long) {
    clearContext()
    cancelLaunch()
    controls?.skipToQueueItem(id)
}

internal fun TidalRemote.launch(id: String, expected: String, query: String, artist: String = "") {
    val c = tidal() ?: run {
        // Tidal fermé entre deux sons : on le réveille et on réessaie une fois
        appContext?.let { wakeInBackground(it) }
        main.postDelayed({ if (tidalCtl != null) launch(id, expected, query, artist) }, 2500)
        return log("Tidal se réveille…")
    }
    playlistSentAt = 0L
    // avec l'identifiant Tidal du son : on le lance par son lien exact (pas de recherche qui tombe sur un autre son)
    // lien exact seulement quand on sait que Tidal le suit (sinon on perdait 2 s à attendre) : la recherche part tout de suite
    val exact = id.isNotEmpty() && id.all { it.isDigit() } && Features.flags.value.exactLaunch && trackLinkMode == 1
    val launch = Launch(trackId = id, expected = expected, status = LaunchStatus.Pending, query = query, artist = artist,
        tries = if (exact) 1 else 0, viaLink = exact)
    _state.update { it.copy(launch = launch) }
    if (exact) {
        c.transportControls.playFromUri(Uri.parse("https://tidal.com/browse/track/$id"), Bundle())
        log("Lancé (lien exact) : $expected")
        // Tidal n'a pas bougé : il ignore les liens de sons → recherche classique
        main.postDelayed({
            val l = _state.value.launch
            if (l != null && l.sentAt == launch.sentAt && l.status == LaunchStatus.Pending) {
                val cur = _state.value.track?.title
                if (cur != null && matches(cur, expected)) return@postDelayed
                linkIgnored++
                if (linkIgnored >= 2 && trackLinkMode == -1) { trackLinkMode = 0; log("Tidal ignore les liens de sons : recherche classique") }
                val again = l.copy(tries = 0, viaLink = false, sentAt = SystemClock.elapsedRealtime())
                _state.update { it.copy(launch = again) }
                tidal()?.transportControls?.playFromSearch(query, Bundle())
            }
        }, 2200)
    } else {
        c.transportControls.playFromSearch(query, Bundle())
        log("Lancé : $expected")
    }
    reapplyShuffleSoon()

    // Si le titre en cours était déjà celui-là, ou si rien ne bouge, on le dit au bout de 6 s
    main.postDelayed({
        val l = _state.value.launch
        if (l != null && l.sentAt == launch.sentAt && l.status == LaunchStatus.Pending) {
            val current = _state.value.track?.title
            val status = if (current != null && matches(current, launch.expected)) LaunchStatus.Ok else LaunchStatus.NoChange
            _state.update { it.copy(launch = l.copy(status = status, actual = current)) }
            if (status == LaunchStatus.NoChange) Diag.add(Diag.Kind.NotFound, launch.expected)
        }
    }, 6000)
}

/** Nouvel essai pour lancer la bonne version : d'abord le lien exact du son, puis une recherche plus précise. */
internal fun TidalRemote.retryLaunch(prev: Launch) {
    val c = tidal() ?: return
    // entre-temps tu as lancé autre chose (une playlist…) : on ne relance pas l'ancien son par-dessus
    val now = _state.value.launch
    if (now == null || now.sentAt != prev.sentAt) return
    val l = prev.copy(status = LaunchStatus.Pending, tries = prev.tries + 1, sentAt = SystemClock.elapsedRealtime(), actual = null)
    _state.update { it.copy(launch = l) }
    if (l.tries == 1) {
        c.transportControls.playFromUri(Uri.parse("https://tidal.com/browse/track/${l.trackId}"), Bundle())
    } else {
        val extras = Bundle().apply {
            putString(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
            putString(android.provider.MediaStore.EXTRA_MEDIA_TITLE, l.expected)
        }
        c.transportControls.playFromSearch("\"${l.expected}\" " + l.query.removePrefix(l.expected).trim(), extras)
    }
    // rien n'a bougé : essai suivant, ou on s'arrête là
    main.postDelayed({
        val cur = _state.value.launch
        if (cur != null && cur.sentAt == l.sentAt && cur.status == LaunchStatus.Pending) {
            if (l.tries < 2) retryLaunch(l)
            else _state.update { it.copy(launch = cur.copy(status = LaunchStatus.OtherVersion, actual = _state.value.track?.title)) }
        }
    }, 4500)
}

/** Relancer à la main la bonne version (bouton du mini-lecteur). */
fun TidalRemote.retryVersion() {
    val l = _state.value.launch ?: return
    if (!l.trackId.all { it.isDigit() }) return
    retryLaunch(l.copy(tries = 0))
}

fun TidalRemote.testPlayFromSearch(query: String) {
    val c = controller ?: return log("Pas de session Tidal active")
    if (query.isBlank()) return log("Tape un titre + artiste d'abord")
    c.transportControls.playFromSearch(query, Bundle())
    log("Recherche envoyée : « $query » → regarde si le titre change")
}

fun TidalRemote.testPlayFromUri(link: String) {
    val c = controller ?: return log("Pas de session Tidal active")
    if (link.isBlank()) return log("Colle un lien Tidal d'abord")
    c.transportControls.playFromUri(Uri.parse(link.trim()), Bundle())
    log("Lien envoyé : $link → regarde si le titre change")
}
