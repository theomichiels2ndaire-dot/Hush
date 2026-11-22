package com.theo.remote

import android.content.Context
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.service.media.MediaBrowserService
import com.theo.remote.ui.PlaylistStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Hush dans Android Auto : tes playlists, tes fichiers locaux et tes sons récents
 * sur l'écran de la voiture, et les commandes qui pilotent Tidal (ou le lecteur local).
 */
class HushAutoService : MediaBrowserService() {
    private lateinit var session: MediaSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val ctx: Context = this
        History.init(ctx)
        LocalMusic.init(ctx)
        TidalRemote.start(ctx)
        session = MediaSession(ctx, "HushAuto").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { if (!TidalRemote.state.value.isPlaying) TidalRemote.playPause() }
                override fun onPause() { if (TidalRemote.state.value.isPlaying) TidalRemote.playPause() }
                override fun onSkipToNext() = TidalRemote.next("Android Auto")
                override fun onSkipToPrevious() = TidalRemote.previous()
                override fun onSeekTo(pos: Long) = TidalRemote.seekTo(pos)
                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    val q = query?.trim().orEmpty()
                    if (q.isNotEmpty()) TidalRemote.playArtist(q)
                }
                override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) = playId(mediaId)
            })
            isActive = true
        }
        sessionToken = session.sessionToken
        // l'écran de la voiture suit ce qui joue (Tidal ou fichier local)
        scope.launch {
            TidalRemote.state.collect { st ->
                val t = st.track
                if (t != null) {
                    session.setMetadata(
                        MediaMetadata.Builder()
                            .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                            .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                            .putLong(MediaMetadata.METADATA_KEY_DURATION, t.durationMs)
                            .apply { t.art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                            .build()
                    )
                }
                val updated = if (st.positionUpdatedAt > 0) st.positionUpdatedAt else SystemClock.elapsedRealtime()
                session.setPlaybackState(
                    PlaybackState.Builder()
                        .setActions(
                            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
                                PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH
                        )
                        .setState(
                            if (st.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                            st.positionMs, if (st.isPlaying) 1f else 0f, updated,
                        )
                        .build()
                )
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?) = BrowserRoot(ROOT, null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        when (parentId) {
            ROOT -> result.sendResult(
                mutableListOf(
                    folder("playlists", "Mes playlists"),
                    folder("local", "Fichiers locaux"),
                    folder("recent", "Récents"),
                )
            )
            "playlists" -> {
                result.detach()
                scope.launch {
                    runCatching { PlaylistStore.load() }
                    val list = PlaylistStore.list.value.orEmpty()
                    result.sendResult(list.map { p -> playable("pl:" + p.name, p.name, "${p.count} titres") }.toMutableList())
                }
            }
            "local" -> {
                val pls = LocalMusic.playlists.value.map { p -> playable("lp:" + p.id, p.name, "Playlist Hush · ${p.ids.size} sons") }
                val tracks = LocalMusic.tracks.value.take(200).map { t -> playable("loc:" + t.id, t.title, t.artist) }
                result.sendResult((pls + tracks).toMutableList())
            }
            "recent" -> {
                val seen = HashSet<String>()
                val recent = History.entries.value.asReversed().filter { seen.add(it.title + "|" + it.artist) }.take(30)
                result.sendResult(recent.map { e -> playable("rec:" + e.title + "\u001F" + e.artist, e.title, e.artist) }.toMutableList())
            }
            else -> result.sendResult(mutableListOf())
        }
    }

    private fun playId(id: String) {
        when {
            id.startsWith("pl:") -> TidalRemote.playPlaylist(id.removePrefix("pl:"))
            id.startsWith("lp:") -> LocalMusic.playlists.value.firstOrNull { it.id == id.removePrefix("lp:") }
                ?.let { MixQueue.play(this, LocalMusic.entriesOf(it), 0) }
            id.startsWith("loc:") -> {
                val all = LocalMusic.tracks.value
                val i = all.indexOfFirst { it.id == id.removePrefix("loc:") }
                if (i >= 0) LocalPlayer.play(this, all, i)
            }
            id.startsWith("rec:") -> {
                val (title, artist) = id.removePrefix("rec:").split("\u001F").let { it[0] to it.getOrElse(1) { "" } }
                TidalRemote.playTitle(title, artist)
            }
        }
    }

    private fun folder(id: String, title: String) = MediaBrowser.MediaItem(
        MediaDescription.Builder().setMediaId(id).setTitle(title).build(), MediaBrowser.MediaItem.FLAG_BROWSABLE,
    )

    private fun playable(id: String, title: String, sub: String) = MediaBrowser.MediaItem(
        MediaDescription.Builder().setMediaId(id).setTitle(title).setSubtitle(sub).build(), MediaBrowser.MediaItem.FLAG_PLAYABLE,
    )

    companion object { private const val ROOT = "hush_root" }
}
