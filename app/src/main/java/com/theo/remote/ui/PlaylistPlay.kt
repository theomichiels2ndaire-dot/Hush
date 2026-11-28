package com.theo.remote.ui

import android.content.Context
import com.theo.remote.LocalMusic
import com.theo.remote.MixQueue
import com.theo.remote.SmartShuffle
import com.theo.remote.TidalApi
import com.theo.remote.TidalRemote
import com.theo.remote.markShuffle
import com.theo.remote.playPlaylist
import com.theo.remote.setShuffle

/**
 * Lancer une playlist entière sans passer par l'écran (Android Auto, raccourcis…) :
 * Hush l'enchaîne lui-même, dans l'ordre ou avec son aléatoire.
 */
object PlaylistPlay {
    suspend fun start(context: Context, id: String?, name: String, shuffle: Boolean, favorites: Boolean = false) {
        val ctx = context.applicationContext
        val key = PlaylistFull.key(id ?: "", favorites)
        val all = PlaylistFull.known(ctx, key)
            ?: PlaylistFull.fetch(ctx, key) { n -> if (favorites) TidalApi.favoritesPage(n) else TidalApi.playlistPage(id ?: "", n) }
        if (all.isNullOrEmpty()) {
            // rien chargé (pas de réseau…) : on laisse Tidal la lancer par son nom
            if (!favorites) { TidalRemote.setShuffle(shuffle); TidalRemote.playPlaylist(name, id) }
            return
        }
        val entries: List<LocalMusic.Entry> = all.distinctBy { it.id }.map { LocalMusic.Entry.T(it) }
        TidalRemote.setShuffle(false)
        NowPlaylist.set(if (favorites) null else id)
        val list = if (shuffle) SmartShuffle.order(entries, { (it as LocalMusic.Entry.T).r.title }, { (it as LocalMusic.Entry.T).r.artistLine }) else entries
        MixQueue.play(ctx, list, 0, original = entries)
        if (shuffle) TidalRemote.markShuffle(true)
    }
}
