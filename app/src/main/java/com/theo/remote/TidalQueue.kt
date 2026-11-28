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

// La file « À suivre » de Hush et la suite de ta lecture.
// (fait partie de TidalRemote : fonctions séparées pour que chaque partie reste lisible et testable)

internal fun TidalRemote.saveUpNext() {
    appContext?.getSharedPreferences("hush", Context.MODE_PRIVATE)?.edit()
        ?.putString("upNext", History.encodeResults(_upNext.value))?.apply()
}

fun TidalRemote.addToQueue(r: TidalApi.Result) {
    _upNext.update { it + r }
    saveUpNext()
    showToast("Ajouté à la file : ${r.fullTitle}")
}

/** Remplace la file « À suivre » d'un coup (ex. lire tes favoris). */
fun TidalRemote.replaceQueue(list: List<TidalApi.Result>) {
    _upNext.value = list
    saveUpNext()
}

/** Vide la file « À suivre » (et oublie la suite de lecture gardée de côté). */
fun TidalRemote.clearQueue() {
    val up = _upNext.value
    val ctx = _context.value
    val mix = MixQueue.snapshot()
    _upNext.value = emptyList()
    saveUpNext()
    clearContext()
    offerUndo("File d'attente vidée") {
        _upNext.value = up; saveUpNext()
        _context.value = ctx
        MixQueue.restore(mix)
    }
}

/** Déplace un son de la file « À suivre ». */
fun TidalRemote.moveInQueue(from: Int, to: Int) {
    val before = _upNext.value
    if (from != to && from in before.indices && to in before.indices) offerUndo("Ordre changé", "moveQueue") { _upNext.value = before; saveUpNext() }
    _upNext.update { list -> QueueLogic.move(list, from, to) }
    saveUpNext()
}

fun TidalRemote.removeFromQueue(index: Int) {
    val before = _upNext.value
    before.getOrNull(index)?.let { r -> offerUndo("Retiré de la file : ${r.title}") { _upNext.value = before; saveUpNext() } }
    _upNext.update { list -> QueueLogic.removeAt(list, index) }
    saveUpNext()
}

internal fun TidalRemote.clearContext() {
    if (_context.value.isNotEmpty()) _context.value = emptyList()
    MixQueue.clear()
}

/**
 * Une nouvelle playlist démarre : les vieux sons de « À suivre » (ajoutés il y a longtemps, réveil…)
 * passaient avant elle et donnaient l'impression que la playlist lançait n'importe quoi.
 */
fun TidalRemote.startingPlaylist() {
    cancelLaunch()
    if (!Features.flags.value.freshQueue) return
    val up = _upNext.value
    if (up.isEmpty()) return
    _upNext.value = emptyList(); saveUpNext()
    offerUndo("File « À suivre » vidée (${up.size})", "freshQueue") { _upNext.value = up; saveUpNext() }
}

/** On oublie le dernier son demandé : sinon la vérif « bonne version » relançait l'ancien son par-dessus. */
internal fun TidalRemote.cancelLaunch() {
    if (_state.value.launch?.status == LaunchStatus.Pending) _state.update { it.copy(launch = null) }
}

internal fun TidalRemote.captureContext() {
    if (_context.value.isNotEmpty()) return
    val st = _state.value
    val i = st.queue.indexOfFirst { it.id == st.activeQueueId }
    if (i < 0) return
    _context.value = st.queue.drop(i + 1).take(60).map { Ctx(it.title, it.subtitle) }
}

/**
 * Joue une liste de sons (titre, artiste) à la suite : le premier tout de suite,
 * les autres deviennent « la suite de ta lecture » (Mix du jour, humeur, dormir…).
 */
fun TidalRemote.playSequence(list: List<Pair<String, String>>) {
    val first = list.firstOrNull() ?: return
    playTitle(first.first, first.second)
    // anti-répétition : on évite les sons déjà passés ces 2 dernières heures
    val rest = AntiRepeat.filter(list.drop(1), { it.first }, { it.second })
    _context.value = rest.take(80).map { Ctx(it.first, it.second) }
}

/** Ce qui vient après : la file Hush d'abord, puis la suite de ta lecture. */
fun TidalRemote.peekNext(): Pair<String, String>? =
    _upNext.value.firstOrNull()?.let { it.title to it.artistLine } ?: _context.value.firstOrNull()?.let { it.title to it.artist }

/** Le son qui arrivera si tu passes au suivant (true) ou au précédent (false). null = on ne sait pas. */
fun TidalRemote.peekAdjacent(next: Boolean): Peek? {
    if (LocalPlayer.active) return null
    val st = _state.value
    val qi = st.queue.indexOfFirst { it.id == st.activeQueueId }
    if (!next) {
        if (MixQueue.active || st.livePosition() > 3000) return null // revient au début du son, ou géré par Hush
        return st.queue.getOrNull(qi - 1)?.takeIf { qi > 0 }?.let { Peek(it.title, it.subtitle, null) }
    }
    _upNext.value.firstOrNull()?.let { return Peek(it.fullTitle, it.artistLine, it.coverUrl) }
    if (MixQueue.hasNext()) when (val e = MixQueue.peekNext()) {
        is LocalMusic.Entry.T -> return Peek(e.r.fullTitle, e.r.artistLine, e.r.coverUrl)
        is LocalMusic.Entry.L -> return Peek(e.t.title, e.t.artist, e.t.art?.let { java.io.File(it) })
        null -> {}
    }
    _context.value.firstOrNull()?.let { return Peek(it.title, it.artist, null) }
    return st.queue.getOrNull(qi + 1)?.takeIf { qi >= 0 }?.let { Peek(it.title, it.subtitle, null) }
}
