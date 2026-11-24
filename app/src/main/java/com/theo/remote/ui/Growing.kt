package com.theo.remote.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.History
import com.theo.remote.Ratings
import com.theo.remote.TidalApi
import com.theo.remote.TidalAuth
import com.theo.remote.TidalRemote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * La playlist qui grandit toute seule : chaque semaine (dès le lundi), Hush prépare quelques sons
 * proches de tes sons notés 5 étoiles. Tu gardes (ajouté à la playlist Tidal « Hush · Ça grandit ») ou tu jettes.
 */
object Growing {
    const val PLAYLIST = "Hush · Ça grandit"
    private val _cands = MutableStateFlow<List<TidalApi.Result>>(emptyList())
    val cands: StateFlow<List<TidalApi.Result>> = _cands
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutex = Mutex()
    private var ctx: Context? = null
    private var playlistId: String? = null

    private fun prefs() = ctx?.getSharedPreferences("hush", Context.MODE_PRIVATE)

    /** Semaine en cours, au format « 2026-W46 » (une semaine commence le lundi). */
    private fun week(): String {
        val d = java.time.LocalDate.now()
        val wf = java.time.temporal.WeekFields.ISO
        return "${d.get(wf.weekBasedYear())}-W${d.get(wf.weekOfWeekBasedYear())}"
    }

    fun init(context: Context) {
        if (ctx != null) return
        ctx = context.applicationContext
        _cands.value = History.decodeResults(prefs()?.getString("growCands", null))
    }

    private fun save() {
        prefs()?.edit()?.putString("growCands", History.encodeResults(_cands.value))?.apply()
    }

    /** Prépare la fournée de la semaine si ce n'est pas déjà fait. */
    suspend fun ensureWeek(context: Context) = mutex.withLock {
        init(context)
        val p = prefs() ?: return@withLock
        if (p.getString("growWeek", null) == week()) return@withLock
        // graines : tes sons à 5 étoiles (sinon les mieux notés)
        val rated = Ratings.items.value.values.filter { it.kind == Ratings.Kind.Track }
        val best = rated.filter { it.stars >= 5f }.ifEmpty { rated.filter { it.stars >= 4.5f } }
        if (best.isEmpty()) return@withLock
        val seeds = ArrayList<TidalApi.Result>()
        for (e in best.shuffled().take(8)) {
            runCatching { TidalApi.search("${e.title} ${mainArtist(e.artist)}").firstOrNull() }.getOrNull()?.let { seeds += it }
            delay(120)
        }
        if (seeds.isEmpty()) return@withLock
        val already = p.getStringSet("growSeen", emptySet()).orEmpty()
        val heard = History.entries.value.map { History.keyOf(it.title, it.artist) }.toHashSet()
        val picks = runCatching { TidalApi.playlistRecommendations(seeds, 12, exclude = already) }.getOrDefault(emptyList())
            .filter { it.id !in already && History.keyOf(it.title, it.artistLine) !in heard }
            .take(6)
        if (picks.isEmpty()) return@withLock
        _cands.value = picks
        save()
        p.edit().putString("growWeek", week()).putStringSet("growSeen", (already + picks.map { it.id }).toList().takeLast(400).toSet()).apply()
    }

    fun keep(r: TidalApi.Result) {
        _cands.value = _cands.value.filterNot { it.id == r.id }; save()
        if (!TidalAuth.canWrite.value) { TidalRemote.showToast("Reconnecte Tidal pour que Hush puisse remplir tes playlists"); return }
        scope.launch {
            runCatching {
                val id = playlistId ?: run {
                    PlaylistStore.load()
                    PlaylistStore.list.value?.firstOrNull { it.name.equals(PLAYLIST, true) }?.id
                } ?: TidalApi.createPlaylist(PLAYLIST, "Elle grandit chaque semaine avec des sons proches de tes 5 étoiles (Hush)").id
                playlistId = id
                TidalApi.addToPlaylist(id, listOf(r.id))
                cachedTracks.remove(id); cachedNext.remove(id)
            }.onSuccess { TidalRemote.showToast("Ajouté à « $PLAYLIST »") }
                .onFailure { TidalRemote.showToast(it.message ?: "Échec de l'ajout") }
        }
    }

    fun toss(r: TidalApi.Result) {
        _cands.value = _cands.value.filterNot { it.id == r.id }; save()
    }
}

/** Carte de l'accueil : la fournée de la semaine à trier. */
@Composable
fun GrowingCard() {
    val cands by Growing.cands.collectAsState()
    if (cands.isEmpty()) return
    val cc = rememberUrlCoverColors(cands.first().coverUrl)
    val shape = Rounded(24)
    Row(
        Modifier.padding(horizontal = Space.gutter).fillMaxWidth()
            .shadow(16.dp, shape, ambientColor = cc.a, spotColor = cc.a).clip(shape)
            .background(Brush.linearGradient(listOf(androidx.compose.ui.graphics.lerp(cc.a, Color.Black, 0.3f), androidx.compose.ui.graphics.lerp(cc.b, Color.Black, 0.6f))))
            .bounceClick(pressedScale = 0.97f) { Panels.show(Panels.Kind.Growing) }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // les pochettes de la semaine, qui se chevauchent
        Box(Modifier.width(112.dp).height(64.dp)) {
            cands.take(3).forEachIndexed { i, r ->
                Cover(model = r.coverUrl, radius = 10.dp, modifier = Modifier.padding(start = (i * 24).dp).size(64.dp).shadow(10.dp, RoundedCornerShape(10.dp)))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Sons à ajouter", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Text("${cands.size} son${if (cands.size > 1) "s" else ""} proche${if (cands.size > 1) "s" else ""} de tes 5 étoiles à trier", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
        }
    }
}

/** Le tri de la semaine : toucher = écouter, ✓ = garder, × = jeter. */
@Composable
fun GrowingPanel() {
    val cands by Growing.cands.collectAsState()
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            PanelTitle("Ça grandit")
            Spacer(Modifier.height(4.dp))
            Text(
                "Chaque semaine, quelques sons proches de tes 5 étoiles. Garde ceux qui te plaisent : ils vont dans « ${Growing.PLAYLIST} » sur Tidal. Touche un son pour l'écouter.",
                color = Txt2, fontSize = 14.sp, lineHeight = 20.sp,
            )
            Spacer(Modifier.height(10.dp))
        }
        if (cands.isEmpty()) item {
            EmptyState("Tout est trié", "Une nouvelle fournée arrive lundi prochain.")
        }
        items(cands.distinctBy { it.id }, key = { it.id }) { r ->
            Row(
                Modifier.animateItem().fillMaxWidth().glass(Rounded(18), fill = 0.08f)
                    .bounceClick(pressedScale = 0.98f, onLongClick = { TrackMenu.open(r) }) { TidalRemote.play(r) }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(model = r.coverUrl, radius = 10.dp, modifier = Modifier.size(56.dp).shadow(8.dp, RoundedCornerShape(10.dp)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.fullTitle, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(r.artistLine, color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(
                    Modifier.size(42.dp).clip(PillShape).background(Color.White.copy(alpha = 0.12f)).bounceClick(pressedScale = 0.85f) { Growing.toss(r) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, contentDescription = "Jeter", tint = Txt, modifier = Modifier.size(22.dp)) }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(42.dp).clip(PillShape).background(Txt).bounceClick(pressedScale = 0.85f) { Growing.keep(r) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Check, contentDescription = "Garder", tint = Ink, modifier = Modifier.size(22.dp)) }
            }
        }
    }
}
