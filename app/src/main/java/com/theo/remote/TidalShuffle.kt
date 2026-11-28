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

// L'aléatoire : ce que tu as choisi dans Hush fait foi, on le remet dans Tidal si besoin.
// (fait partie de TidalRemote : fonctions séparées pour que chaque partie reste lisible et testable)

fun TidalRemote.setShuffle(on: Boolean) {
    wantShuffle = on
    appContext?.getSharedPreferences("hush", Context.MODE_PRIVATE)?.edit()?.putBoolean("shuffle", on)?.apply()
    _state.update { it.copy(shuffle = on) }
    dumpShuffleInfo("avant")
    applyShuffle(on)
    main.postDelayed({ dumpShuffleInfo("après") }, 800)
}

/** Écrit dans Logcat (filtre « HushDiag ») tout ce que Tidal expose pour l'aléatoire. */
fun TidalRemote.dumpShuffleInfo(tag: String) {
    val pb = controller?.playbackState
    val c = compat
    android.util.Log.i("HushDiag", "[$tag] compat=${c != null} shuffleMode=${c?.shuffleMode} repeat=${c?.repeatMode}")
    android.util.Log.i("HushDiag", "[$tag] actions=0x${java.lang.Long.toHexString(pb?.actions ?: 0)} compatActions=0x${java.lang.Long.toHexString(c?.playbackState?.actions ?: 0)}")
    android.util.Log.i("HushDiag", "[$tag] SET_SHUFFLE supported=${((c?.playbackState?.actions ?: 0) and PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE) != 0L}")
    pb?.customActions?.forEach {
        val icon = try {
            appContext?.packageManager?.getResourcesForApplication(TIDAL_PACKAGE)?.getResourceEntryName(it.icon)
        } catch (e: Exception) { "?" }
        android.util.Log.i("HushDiag", "[$tag] customAction action='${it.action}' name='${it.name}' icon=$icon")
    }
    android.util.Log.i("HushDiag", "[$tag] tidalShuffleOn=${tidalShuffleOn()} want=$wantShuffle")
    if (pb?.customActions.isNullOrEmpty()) android.util.Log.i("HushDiag", "[$tag] aucune customAction")
    pb?.extras?.keySet()?.forEach { k -> android.util.Log.i("HushDiag", "[$tag] pbExtra $k=${pb.extras?.get(k)}") }
    c?.extras?.keySet()?.forEach { k -> android.util.Log.i("HushDiag", "[$tag] sessionExtra $k=${c.extras?.get(k)}") }
}

internal fun TidalRemote.shuffleAction() =
    controller?.playbackState?.customActions?.firstOrNull { it.action == SHUFFLE_ACTION }
        ?: controller?.playbackState?.customActions?.firstOrNull { it.action.contains("shuffle", ignoreCase = true) }

/**
 * État réel de l'aléatoire dans Tidal, deviné depuis le nom de l'icône de son bouton
 * (ex. « ic_shuffle_on » / « ic_shuffle_off »). null = impossible à savoir.
 */
internal fun TidalRemote.tidalShuffleOn(): Boolean? {
    val a = shuffleAction() ?: return null
    val name = try {
        appContext?.packageManager?.getResourcesForApplication(TIDAL_PACKAGE)?.getResourceEntryName(a.icon)
    } catch (e: Exception) {
        null
    } ?: return null
    val tokens = name.lowercase(Locale.ROOT).split('_', '.', '-')
    return when {
        tokens.any { it in setOf("off", "disabled", "inactive", "unselected", "outline") } -> false
        tokens.any { it in setOf("on", "active", "selected", "enabled", "checked", "filled") } -> true
        else -> null
    }
}

/** Remet Tidal dans l'état choisi dans Hush s'il a dévié (seulement si on connaît son état). */
internal fun TidalRemote.enforceShuffle() {
    // playlist enchaînée par Hush : c'est Hush qui mélange, Tidal doit rester dans l'ordre
    if (MixQueue.active) return
    val c = compat
    val supportsStandard = ((c?.playbackState?.actions ?: 0) and PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE) != 0L
    if (supportsStandard && c != null) {
        val m = c.shuffleMode
        val isOn = m != PlaybackStateCompat.SHUFFLE_MODE_NONE && m != PlaybackStateCompat.SHUFFLE_MODE_INVALID
        if (isOn != wantShuffle) applyShuffle(wantShuffle)
        return
    }
    val real = tidalShuffleOn() ?: return
    if (real != wantShuffle) shuffleAction()?.let { controls?.sendCustomAction(it, null) }
}

/** Applique un choix fait par toi dans Hush. */
internal fun TidalRemote.applyShuffle(on: Boolean) {
    val c = compat
    val supportsStandard = ((c?.playbackState?.actions ?: 0) and PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE) != 0L
    if (supportsStandard && c != null) {
        c.transportControls.setShuffleMode(if (on) PlaybackStateCompat.SHUFFLE_MODE_ALL else PlaybackStateCompat.SHUFFLE_MODE_NONE)
        return
    }
    // Tidal : bouton « bascule » maison. On ne bascule que si son état diffère (ou s'il est inconnu).
    val action = shuffleAction() ?: return
    val real = tidalShuffleOn()
    if (real == null || real != on) controls?.sendCustomAction(action, null)
}

/** Après un lancement, Tidal recrée sa file : on réapplique l'aléatoire un peu après. */
internal fun TidalRemote.reapplyShuffleSoon() {
    if (!wantShuffle) return
    main.postDelayed({ enforceShuffle() }, 350)
    main.postDelayed({ enforceShuffle() }, 1500)
}

/** Aléatoire géré par Hush (playlist mélangée par Hush) : on allume juste le bouton du lecteur. */
fun TidalRemote.markShuffle(on: Boolean) {
    wantShuffle = on
    _state.update { it.copy(shuffle = on) }
}

/** Bouton aléatoire du lecteur. */
fun TidalRemote.toggleShuffle() {
    val on = !_state.value.shuffle
    // une playlist lancée par Hush (mixte ou file « À suivre ») : c'est Hush qui mélange, pas Tidal
    if (MixQueue.active || _upNext.value.size > 1) {
        wantShuffle = on
        _state.update { it.copy(shuffle = on) }
        if (MixQueue.active) { MixQueue.setShuffle(on); log(if (on) "Aléatoire : playlist mélangée" else "Aléatoire coupé : ordre de la playlist"); return }
        if (_upNext.value.size > 1) {
            if (on) {
                upNextOriginal = _upNext.value
                _upNext.value = SmartShuffle.order(_upNext.value, { it.fullTitle }, { it.artistLine })
            } else upNextOriginal?.let { o -> val left = _upNext.value.toSet(); _upNext.value = o.filter { it in left } }
            if (!on) upNextOriginal = null
            saveUpNext()
            return
        }
    }
    setShuffle(on)
}
