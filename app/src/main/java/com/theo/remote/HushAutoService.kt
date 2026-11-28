package com.theo.remote

import android.content.Context
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.service.media.MediaBrowserService
import com.theo.remote.ui.PlaylistPlay
import com.theo.remote.ui.PlaylistStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Hush dans Android Auto (2.0) : ta file d'attente, tes playlists (dans l'ordre ou avec l'aléatoire de Hush),
 * tes favoris, tes récents et tes fichiers locaux sur l'écran de la voiture ; la recherche vocale
 * (« joue Ninho sur Hush ») ; et les boutons aléatoire et j'aime. Tout pilote Tidal, comme sur le téléphone.
 */
class HushAutoService : MediaBrowserService() {
    private lateinit var session: MediaSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val ctx: Context = this
        History.init(ctx)
        LocalMusic.init(ctx)
        Features.init(ctx)
        TidalRemote.start(ctx)
        session = MediaSession(ctx, "HushAuto").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { if (!TidalRemote.state.value.isPlaying) TidalRemote.playPause() }
                override fun onPause() { if (TidalRemote.state.value.isPlaying) TidalRemote.playPause() }
                override fun onSkipToNext() = TidalRemote.next("Android Auto")
                override fun onSkipToPrevious() = TidalRemote.previous()
                override fun onSeekTo(pos: Long) = TidalRemote.seekTo(pos)
                override fun onPlayFromSearch(query: String?, extras: Bundle?) = voiceSearch(query.orEmpty(), extras)
                override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) = playId(mediaId)
                override fun onSkipToQueueItem(id: Long) = jumpInQueue(id)
                override fun onCustomAction(action: String, extras: Bundle?) {
                    val s = TidalRemote.state.value
                    when (action) {
                        CA_SHUFFLE -> TidalRemote.toggleShuffle()
                        CA_HEART -> s.track?.let { com.theo.remote.ui.toggleHeart(s, it) }
                    }
                }
            })
            isActive = true
        }
        sessionToken = session.sessionToken
        // l'écran de la voiture suit ce qui joue, et sa file
        scope.launch {
            TidalRemote.state.collect { st -> syncPlayback(st) }
        }
        scope.launch {
            combine(TidalRemote.upNext, MixQueue.upcoming) { up, mix -> up to mix }.collect { (up, mix) ->
                val items = ArrayList<MediaSession.QueueItem>()
                up.take(50).forEachIndexed { i, r -> items += queueItem(i.toLong(), r.fullTitle, r.artistLine) }
                mix.take(100).forEachIndexed { i, e ->
                    val (t, a) = when (e) {
                        is LocalMusic.Entry.T -> e.r.fullTitle to e.r.artistLine
                        is LocalMusic.Entry.L -> e.t.title to e.t.artist
                    }
                    items += queueItem(MIX_BASE + i, t, a)
                }
                session.setQueue(items)
                session.setQueueTitle("À suivre")
                notifyChildrenChanged("queue")
            }
        }
    }

    private fun queueItem(id: Long, title: String, artist: String) =
        MediaSession.QueueItem(MediaDescription.Builder().setMediaId("q:$id").setTitle(title).setSubtitle(artist).build(), id)

    private fun syncPlayback(st: TidalRemote.State) {
        val t = st.track
        if (t != null) {
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, t.durationMs)
                    .apply { t.art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                    .build()
            )
        }
        val liked = st.tidalFavorite ?: (t != null && History.isLiked(t.title, t.artist))
        val updated = if (st.positionUpdatedAt > 0) st.positionUpdatedAt else SystemClock.elapsedRealtime()
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH or PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM
                )
                .setState(
                    if (st.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    st.positionMs, if (st.isPlaying && st.advancing) st.speed else 0f, updated,
                )
                .addCustomAction(PlaybackState.CustomAction.Builder(CA_SHUFFLE, if (st.shuffle) "Aléatoire activé" else "Aléatoire", if (st.shuffle) R.drawable.ic_n_shuffle_on else R.drawable.ic_n_shuffle).build())
                .addCustomAction(PlaybackState.CustomAction.Builder(CA_HEART, if (liked) "Retirer des favoris" else "J'aime", if (liked) R.drawable.ic_n_heart_on else R.drawable.ic_n_heart).build())
                .build()
        )
    }

    override fun onDestroy() {
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        // affichage conseillé : playlists en grille, sons en liste ; la recherche vocale est acceptée
        val extras = Bundle().apply {
            putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 2)
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)
        }
        return BrowserRoot(ROOT, extras)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        when (parentId) {
            ROOT -> result.sendResult(
                mutableListOf(
                    folder("queue", "À suivre"),
                    folder("playlists", "Mes playlists"),
                    playable("fav:shuffle", "Mes favoris en aléatoire", "Le mélange de Hush"),
                    folder("recent", "Récents"),
                    folder("local", "Fichiers locaux"),
                )
            )
            "queue" -> {
                val up = TidalRemote.upNext.value.take(50).mapIndexed { i, r -> playable("q:$i", r.fullTitle, r.artistLine) }
                val mix = MixQueue.upcoming.value.take(100).mapIndexed { i, e ->
                    when (e) {
                        is LocalMusic.Entry.T -> playable("q:${MIX_BASE + i}", e.r.fullTitle, e.r.artistLine)
                        is LocalMusic.Entry.L -> playable("q:${MIX_BASE + i}", e.t.title, e.t.artist)
                    }
                }
                result.sendResult((up + mix).toMutableList())
            }
            "playlists" -> {
                result.detach()
                scope.launch {
                    if (PlaylistStore.list.value == null) runCatching { PlaylistStore.load() }
                    val list = PlaylistStore.list.value.orEmpty()
                    // chaque playlist s'ouvre sur deux choix : dans l'ordre, ou en aléatoire
                    result.sendResult(list.map { p -> folder("plc:" + p.id + SEP + p.name, p.name, "${p.count} titres") }.toMutableList())
                }
            }
            "recent" -> {
                val seen = HashSet<String>()
                val recent = History.entries.value.asReversed().filter { seen.add(it.title + "|" + it.artist) }.take(30)
                result.sendResult(recent.map { e -> playable("rec:" + e.title + SEP + e.artist, e.title, e.artist) }.toMutableList())
            }
            "local" -> {
                val pls = LocalMusic.playlists.value.map { p -> playable("lp:" + p.id, p.name, "Playlist Hush · ${p.ids.size} sons") }
                val tracks = LocalMusic.tracks.value.take(200).map { t -> playable("loc:" + t.id, t.title, t.artist) }
                result.sendResult((pls + tracks).toMutableList())
            }
            else -> if (parentId.startsWith("plc:")) {
                val (id, name) = parentId.removePrefix("plc:").split(SEP).let { it[0] to it.getOrElse(1) { "" } }
                result.sendResult(
                    mutableListOf(
                        playable("pls:$id$SEP$name", "Aléatoire", name),
                        playable("plo:$id$SEP$name", "Dans l'ordre", name),
                    )
                )
            } else result.sendResult(mutableListOf())
        }
    }

    private fun playId(id: String) {
        val ctx: Context = this
        when {
            id.startsWith("pls:") || id.startsWith("plo:") -> {
                val (pid, name) = id.substring(4).split(SEP).let { it[0] to it.getOrElse(1) { "" } }
                scope.launch { PlaylistPlay.start(ctx, pid, name, shuffle = id.startsWith("pls:")) }
            }
            id == "fav:shuffle" -> scope.launch { PlaylistPlay.start(ctx, null, "Favoris", shuffle = true, favorites = true) }
            id.startsWith("q:") -> jumpInQueue(id.removePrefix("q:").toLongOrNull() ?: return)
            id.startsWith("lp:") -> LocalMusic.playlists.value.firstOrNull { it.id == id.removePrefix("lp:") }
                ?.let { MixQueue.play(ctx, LocalMusic.entriesOf(it), 0) }
            id.startsWith("loc:") -> {
                val all = LocalMusic.tracks.value
                val i = all.indexOfFirst { it.id == id.removePrefix("loc:") }
                if (i >= 0) LocalPlayer.play(ctx, all, i)
            }
            id.startsWith("rec:") -> {
                val (title, artist) = id.removePrefix("rec:").split(SEP).let { it[0] to it.getOrElse(1) { "" } }
                TidalRemote.playTitle(title, artist)
            }
        }
    }

    /** Toucher un son de la file : « À suivre » d'abord, puis la suite de la playlist. */
    private fun jumpInQueue(id: Long) {
        if (id >= MIX_BASE) { MixQueue.jumpTo((id - MIX_BASE).toInt()); return }
        val i = id.toInt()
        val up = TidalRemote.upNext.value
        val r = up.getOrNull(i) ?: return
        // on lance ce son et on garde ceux d'après dans la file
        TidalRemote.replaceQueue(up.drop(i + 1))
        TidalRemote.play(r)
    }

    /**
     * « Ok Google, joue … sur Hush » : une de tes playlists si le nom correspond, sinon l'artiste,
     * sinon le meilleur son trouvé sur Tidal. Rien de précis demandé : tes favoris en aléatoire.
     */
    private fun voiceSearch(query: String, extras: Bundle?) {
        val ctx: Context = this
        val q = query.trim()
        scope.launch {
            if (q.isEmpty()) { PlaylistPlay.start(ctx, null, "Favoris", shuffle = true, favorites = true); return@launch }
            val focus = extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS)
            if (PlaylistStore.list.value == null) runCatching { PlaylistStore.load() }
            val pl = PlaylistStore.list.value.orEmpty().maxByOrNull { Fuzzy.score(q, it.name) }?.takeIf { Fuzzy.score(q, it.name) >= 80 }
            if (pl != null && (focus == null || focus == "vnd.android.cursor.item/playlist" || Fuzzy.norm(pl.name) == Fuzzy.norm(q))) {
                PlaylistPlay.start(ctx, pl.id, pl.name, shuffle = true); return@launch
            }
            val found = runCatching { TidalApi.searchAll(q) }.getOrNull()
            val artist = found?.artists?.firstOrNull { Fuzzy.score(q, it.name) >= 80 }
            when {
                artist != null && focus != MediaStore.Audio.Media.ENTRY_CONTENT_TYPE -> TidalRemote.playArtist(artist.name)
                found?.tracks?.isNotEmpty() == true -> TidalRemote.play(found.tracks.first())
                else -> TidalRemote.playArtist(q)
            }
        }
    }

    private fun folder(id: String, title: String, sub: String? = null) = MediaBrowser.MediaItem(
        MediaDescription.Builder().setMediaId(id).setTitle(title).apply { sub?.let { setSubtitle(it) } }.build(), MediaBrowser.MediaItem.FLAG_BROWSABLE,
    )

    private fun playable(id: String, title: String, sub: String) = MediaBrowser.MediaItem(
        MediaDescription.Builder().setMediaId(id).setTitle(title).setSubtitle(sub).build(), MediaBrowser.MediaItem.FLAG_PLAYABLE,
    )

    companion object {
        private const val ROOT = "hush_root"
        private const val SEP = "\u001F"
        private const val MIX_BASE = 1000L
        private const val CA_SHUFFLE = "hush.auto.shuffle"
        private const val CA_HEART = "hush.auto.heart"
    }
}
