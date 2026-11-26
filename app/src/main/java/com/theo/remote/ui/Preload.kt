package com.theo.remote.ui

import android.content.Context
import android.os.BatteryManager
import com.theo.remote.Features
import com.theo.remote.TidalApi
import com.theo.remote.TidalAuth
import kotlinx.coroutines.delay

/**
 * Toutes tes playlists gardées sur le téléphone : en Wi-Fi et en charge, Hush les charge en entier
 * en fond (au plus toutes les 12 h). Ouverture, aléatoire et hors connexion deviennent instantanés.
 */
object PlaylistPreload {
    private const val EVERY_MS = 12 * 3600_000L
    @Volatile private var running = false

    private fun charging(ctx: Context): Boolean =
        ctx.getSystemService(BatteryManager::class.java)?.isCharging == true

    private fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) && !cm.isActiveNetworkMetered
    }

    /** Appelé régulièrement par le service : ne fait rien si les conditions ne sont pas réunies. */
    suspend fun maybeRun(context: Context) {
        val ctx = context.applicationContext
        if (running || !Features.flags.value.preloadPlaylists || !TidalAuth.loggedIn.value) return
        val p = ctx.getSharedPreferences("hush_preload", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - p.getLong("at", 0L) < EVERY_MS) return
        if (!charging(ctx) || !onWifi(ctx)) return
        running = true
        try {
            if (PlaylistStore.list.value == null) runCatching { PlaylistStore.load() }
            val lists = PlaylistStore.list.value ?: return
            // tes favoris d'abord, puis chaque playlist
            PlaylistFull.fetch(ctx, PlaylistFull.key("", true)) { n -> TidalApi.favoritesPage(n) }
            for (pl in lists) {
                // on s'arrête si tu débranches ou quittes le Wi-Fi
                if (!charging(ctx) || !onWifi(ctx)) return
                PlaylistFull.fetch(ctx, PlaylistFull.key(pl.id, false)) { n -> TidalApi.playlistPage(pl.id, n) }
                delay(1500) // gentil avec les serveurs de Tidal
            }
            p.edit().putLong("at", System.currentTimeMillis()).apply()
        } finally {
            running = false
        }
    }
}
