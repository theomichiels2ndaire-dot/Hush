package com.theo.remote.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.Features
import com.theo.remote.TidalApi
import com.theo.remote.TidalRemote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.Normalizer

/**
 * Nettoyage mensuel : Hush note les sons que tu passes pendant une playlist,
 * et une fois par mois te propose de retirer ceux que tu passes toujours.
 */
object PlaylistCleanup {
    data class Song(val title: String, val artist: String, val count: Int, val at: Long)
    data class Candidate(val playlistId: String, val name: String, val songs: List<Song>)

    private const val MONTH = 30L * 24 * 3600_000
    private var prefs: android.content.SharedPreferences? = null
    /** playlist → (son → passages) */
    private val skips = HashMap<String, HashMap<String, Song>>()
    /** sons que tu as choisi de garder, par playlist */
    private val kept = HashMap<String, MutableSet<String>>()

    private val _open = MutableStateFlow<Candidate?>(null)
    val open: StateFlow<Candidate?> = _open
    private val _due = MutableStateFlow<List<Candidate>>(emptyList())
    /** Ce qu'on propose ce mois-ci (vide = rien à montrer). */
    val due: StateFlow<List<Candidate>> = _due

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").replace(Regex("\\(.*?\\)|\\[.*?]"), "").replace(Regex("[^a-z0-9]"), "")
    private fun key(title: String, artist: String) = norm(title) + "|" + norm(artist.split(",", "&", " feat", " x ").first())

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("hush_cleanup", Context.MODE_PRIVATE)
        prefs = p
        runCatching {
            val o = JSONObject(p.getString("skips", "{}"))
            o.keys().forEach { pl ->
                val m = HashMap<String, Song>()
                val songs = o.getJSONObject(pl)
                songs.keys().forEach { k -> val x = songs.getJSONObject(k); m[k] = Song(x.optString("t"), x.optString("a"), x.optInt("n"), x.optLong("at")) }
                skips[pl] = m
            }
            val kk = JSONObject(p.getString("kept", "{}"))
            kk.keys().forEach { pl -> val a = kk.getJSONArray(pl); kept[pl] = (0 until a.length()).map { a.getString(it) }.toMutableSet() }
        }
        refreshDue()
    }

    /** Appelé quand un son est passé au bout de quelques secondes. */
    fun recordSkip(title: String, artist: String) {
        val pl = NowPlaylist.id.value ?: return
        val k = key(title, artist)
        val m = skips.getOrPut(pl) { HashMap() }
        val old = m[k]
        m[k] = Song(title, artist, (old?.count ?: 0) + 1, System.currentTimeMillis())
        save()
    }

    /** Toutes les playlists avec au moins 2 sons passés au moins 2 fois ces 2 derniers mois. */
    fun candidates(): List<Candidate> {
        val now = System.currentTimeMillis()
        val names = PlaylistStore.list.value.orEmpty().associate { it.id to it.name }
        return skips.mapNotNull { (pl, m) ->
            val name = names[pl] ?: return@mapNotNull null
            val songs = m.filter { (k, s) -> s.count >= 2 && now - s.at < 2 * MONTH && k !in kept[pl].orEmpty() }
                .values.sortedByDescending { it.count }
            if (songs.size >= 2) Candidate(pl, name, songs) else null
        }.sortedByDescending { it.songs.size }
    }

    fun refreshDue() {
        val p = prefs ?: return
        if (!Features.flags.value.playlistCleanup) { _due.value = emptyList(); return }
        val last = p.getLong("lastShown", 0L)
        if (last == 0L) { p.edit().putLong("lastShown", System.currentTimeMillis()).apply(); _due.value = emptyList(); return }
        _due.value = if (System.currentTimeMillis() - last >= MONTH) candidates() else emptyList()
    }

    fun show(c: Candidate) { _open.value = c }
    fun close() { _open.value = null }

    /** « Pas maintenant » : on repropose dans un mois. */
    fun snooze() {
        prefs?.edit()?.putLong("lastShown", System.currentTimeMillis())?.apply()
        _due.value = emptyList()
    }

    /** Les sons non cochés sont gardés : on ne les propose plus pour cette playlist. */
    fun keep(pl: String, songs: List<Song>) {
        kept.getOrPut(pl) { mutableSetOf() }.addAll(songs.map { key(it.title, it.artist) })
        save()
    }

    fun forgetSkips(pl: String, songs: List<Song>) {
        skips[pl]?.let { m -> songs.forEach { m.remove(key(it.title, it.artist)) } }
        save()
    }

    /** Retrouve les sons dans la playlist Tidal et les retire (avec « Annuler »). */
    suspend fun remove(c: Candidate, songs: List<Song>): Int {
        val wanted = songs.map { key(it.title, it.artist) }.toSet()
        val found = ArrayList<TidalApi.Result>()
        var next: String? = null
        var pages = 0
        do {
            val pg = TidalApi.playlistPage(c.playlistId, next)
            found += pg.tracks.filter { r -> key(r.title, r.artists.firstOrNull().orEmpty()) in wanted || key(r.fullTitle, r.artistLine) in wanted }
            next = pg.next; pages++
        } while (next != null && pages < 120)
        if (found.isEmpty()) return 0
        TidalApi.removeFromPlaylist(c.playlistId, found)
        forgetSkips(c.playlistId, songs)
        cachedTracks.remove(c.playlistId)
        PlaylistEdit.refreshLater(c.playlistId)
        TidalRemote.offerUndo("${found.size} son${if (found.size > 1) "s" else ""} retiré${if (found.size > 1) "s" else ""} de « ${c.name} »") {
            kotlinx.coroutines.MainScope().launch {
                runCatching { TidalApi.addToPlaylist(c.playlistId, found.map { it.id }) }
                PlaylistEdit.refreshLater(c.playlistId)
            }
        }
        return found.size
    }

    private fun save() {
        val o = JSONObject()
        skips.forEach { (pl, m) ->
            val songs = JSONObject()
            m.values.sortedByDescending { it.at }.take(200).forEach { s -> songs.put(key(s.title, s.artist), JSONObject().put("t", s.title).put("a", s.artist).put("n", s.count).put("at", s.at)) }
            o.put(pl, songs)
        }
        val kk = JSONObject()
        kept.forEach { (pl, set) -> kk.put(pl, org.json.JSONArray(set.toList())) }
        prefs?.edit()?.putString("skips", o.toString())?.putString("kept", kk.toString())?.apply()
    }
}

/** Carte de l'accueil, une fois par mois : « 4 sons que tu passes toujours dans X ». */
@Composable
fun CleanupCard(modifier: Modifier = Modifier) {
    val due by PlaylistCleanup.due.collectAsState()
    val c = due.firstOrNull() ?: return
    Column(
        modifier.fillMaxWidth().clip(Rounded(22)).background(Color.White.copy(alpha = 0.07f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Nettoyage du mois", color = Txt2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("${c.songs.size} sons que tu passes toujours dans « ${c.name} »", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Voir") { PlaylistCleanup.show(c) }
            PillButton("Pas maintenant", filled = false) { PlaylistCleanup.snooze() }
        }
    }
}

/** La feuille : les sons cochés seront retirés, les autres gardés. */
@Composable
fun CleanupHost() {
    val c by PlaylistCleanup.open.collectAsState()
    val cand = c ?: return
    val scope = rememberCoroutineScope()
    var checked by remember(cand.playlistId) { mutableStateOf(cand.songs.map { true }) }
    var busy by remember { mutableStateOf(false) }
    BackHandler { PlaylistCleanup.close() }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(remember { MutableInteractionSource() }, null) { PlaylistCleanup.close() })
        GlassPlate(
            Rounded(30), 30.dp,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(10.dp).fillMaxWidth().heightIn(max = 640.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Nettoyer « ${cand.name} »", color = Txt, fontSize = 24.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Tu passes souvent ces sons au bout de quelques secondes. Ceux qui sont cochés seront retirés.", color = Txt2, fontSize = 14.sp, lineHeight = 19.sp)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    cand.songs.forEachIndexed { i, s ->
                        val on = checked.getOrElse(i) { true }
                        Row(
                            Modifier.fillMaxWidth().clip(Rounded(12)).clickable { checked = checked.toMutableList().also { it[i] = !on } }.padding(vertical = 8.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(24.dp).clip(Rounded(7)).background(if (on) Txt else Color.White.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center,
                            ) { if (on) Icon(Icons.Rounded.Check, contentDescription = "Coché", tint = Ink, modifier = Modifier.size(16.dp)) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.title, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(s.artist, color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("passé ${s.count}×", color = Txt3, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                val n = checked.count { it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(if (busy) "…" else if (n == 0) "Tout garder" else "Retirer $n son${if (n > 1) "s" else ""}") {
                        if (busy) return@PillButton
                        val toRemove = cand.songs.filterIndexed { i, _ -> checked.getOrElse(i) { true } }
                        val toKeep = cand.songs.filterIndexed { i, _ -> !checked.getOrElse(i) { true } }
                        PlaylistCleanup.keep(cand.playlistId, toKeep)
                        if (toRemove.isEmpty()) { PlaylistCleanup.snooze(); PlaylistCleanup.close(); return@PillButton }
                        busy = true
                        scope.launch {
                            val r = runCatching { PlaylistCleanup.remove(cand, toRemove) }
                            busy = false
                            r.onFailure { TidalRemote.showToast("Le nettoyage a échoué : ${it.message ?: "réseau"}") }
                            r.onSuccess { if (it == 0) TidalRemote.showToast("Ces sons ne sont plus dans la playlist") }
                            PlaylistCleanup.snooze()
                            PlaylistCleanup.close()
                        }
                    }
                    PillButton("Plus tard", filled = false) { PlaylistCleanup.snooze(); PlaylistCleanup.close() }
                }
            }
        }
    }
}

/** Dans les Réglages : voir tout de suite ce qu'il y a à nettoyer. */
@Composable
fun CleanupNowCard() {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().clip(Rounded(20)).background(Color.White.copy(alpha = 0.06f)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Nettoyer mes playlists", color = Txt, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("Les sons que tu passes toujours, playlist par playlist.", color = Txt2, fontSize = 13.sp)
        PillButton("Voir maintenant") {
            PlaylistCleanup.init(ctx)
            val c = PlaylistCleanup.candidates().firstOrNull()
            if (c == null) TidalRemote.showToast("Rien à nettoyer pour l'instant") else PlaylistCleanup.show(c)
        }
    }
}
