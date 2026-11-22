package com.theo.remote

import android.content.Context
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Tuile des Réglages rapides : un tap = lecture / pause.
 * Si rien n'est chargé, elle relance ta dernière playlist (sinon reprend Tidal).
 */
class HushTile : TileService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        History.init(this)
        TidalRemote.start(this)
        refresh()
    }

    override fun onClick() {
        super.onClick()
        History.init(this)
        TidalRemote.start(this)
        val st = TidalRemote.state.value
        when {
            st.track != null || LocalPlayer.active -> TidalRemote.playPause()
            else -> {
                val last = getSharedPreferences("hush", Context.MODE_PRIVATE).getString("lastPlaylistName", null)
                if (last != null && TidalRemote.wakeInBackground(this)) {
                    // Tidal a besoin d'un instant pour ouvrir sa session
                    main.postDelayed({ TidalRemote.playPlaylist(last) }, 1500)
                } else {
                    TidalRemote.resume(this)
                }
            }
        }
        main.postDelayed({ refresh() }, 800)
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val st = TidalRemote.state.value
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notif)
        tile.label = "Hush"
        tile.state = if (st.isPlaying) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = st.track?.title ?: getSharedPreferences("hush", Context.MODE_PRIVATE).getString("lastPlaylistName", null) ?: "Lecture"
        }
        tile.updateTile()
    }
}
