package com.theo.remote.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.Blocked
import com.theo.remote.History
import com.theo.remote.TidalApi
import com.theo.remote.TidalAuth
import com.theo.remote.TidalRemote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/**
 * Mode découverte : des sons que tu n'as jamais écoutés, proches de tes préférés, un par un.
 * À droite → playlist « Découvertes » ; à gauche → « Pas pour moi ».
 */
object DiscoverDeck {
    const val PLAYLIST = "Découvertes"
    val cards = MutableStateFlow<List<TidalApi.Result>>(emptyList())
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    private val seen = HashSet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val addLock = Mutex()
    private var playlistId: String? = null

    private fun norm(t: String) = t.lowercase().substringBefore(" (").replace(Regex("[^\\p{L}\\p{N}]"), "")

    /** Remplit le paquet quand il reste peu de cartes. */
    fun refill() {
        if (loading.value || cards.value.size > 4) return
        loading.value = true
        error.value = null
        scope.launch {
            runCatching { build() }
                .onSuccess { new -> cards.value = cards.value + new.filter { n -> cards.value.none { it.id == n.id } } }
                .onFailure { error.value = it.message ?: "Erreur réseau" }
            if (cards.value.isEmpty() && error.value == null) error.value = "Rien de nouveau à te proposer pour l'instant"
            loading.value = false
        }
    }

    private suspend fun build(): List<TidalApi.Result> {
        val entries = History.entries.value
        val heard = entries.map { norm(it.title) + "|" + norm(mainArtist(it.artist)) }.toHashSet()
        // graines : tes sons les mieux notés et les plus écoutés du moment
        val rated = com.theo.remote.Ratings.items.value.values
            .filter { it.kind == com.theo.remote.Ratings.Kind.Track && it.stars >= 4 }.map { it.title to it.artist }
        val month = System.currentTimeMillis() - 30L * 86_400_000
        val top = entries.sortedByDescending { e -> e.plays.count { it >= month } }.take(25).map { it.title to it.artist }
        val seeds = (rated.shuffled().take(3) + top.shuffled().take(4)).distinct().take(6)
        val out = ArrayList<TidalApi.Result>()
        for ((t, a) in seeds) {
            val r = runCatching { TidalApi.search("$t ${mainArtist(a)}").firstOrNull() }.getOrNull() ?: continue
            val sim = runCatching { TidalApi.similarTracks(r.id) }.getOrDefault(emptyList())
            out += sim.shuffled().take(6)
            delay(150)
        }
        return out.distinctBy { it.id }.filter { r ->
            r.id !in seen &&
                (norm(r.title) + "|" + norm(r.artists.firstOrNull().orEmpty())) !in heard &&
                !Blocked.isBlocked(r.title, r.artistLine)
        }.shuffled().also { l -> l.forEach { seen += it.id } }
    }

    fun pop() { cards.value = cards.value.drop(1); refill() }

    fun like(r: TidalApi.Result) {
        pop()
        if (!TidalAuth.canWrite.value) { TidalRemote.showToast("Reconnecte Tidal pour que Hush puisse remplir tes playlists"); return }
        scope.launch {
            addLock.withLock {
                runCatching {
                    val id = playlistId ?: run {
                        PlaylistStore.load()
                        PlaylistStore.list.value?.firstOrNull { it.name.equals(PLAYLIST, true) }?.id
                    } ?: TidalApi.createPlaylist(PLAYLIST, "Trouvés avec le mode découverte de Hush").id
                    playlistId = id
                    TidalApi.addToPlaylist(id, listOf(r.id))
                    cachedTracks.remove(id); cachedNext.remove(id)
                }.onSuccess { TidalRemote.showToast("Ajouté à « $PLAYLIST »") }
                    .onFailure { TidalRemote.showToast(it.message ?: "Échec de l'ajout") }
            }
        }
    }

    fun nope(r: TidalApi.Result) {
        pop()
        Blocked.block(r.title, r.artistLine)
    }

    /** Extrait : le son se lance dans Tidal et saute vers son milieu. */
    fun preview(r: TidalApi.Result) {
        TidalRemote.play(r)
        scope.launch {
            delay(1800)
            val s = TidalRemote.state.value
            val d = s.track?.durationMs?.takeIf { it > 0 } ?: r.durationMs
            if (d > 60_000 && norm(s.track?.title.orEmpty()) == norm(r.title)) TidalRemote.seekTo((d * 0.35).toLong())
        }
    }
}

@Composable
fun DiscoverPanel() {
    val cards by DiscoverDeck.cards.collectAsState()
    val loading by DiscoverDeck.loading.collectAsState()
    val error by DiscoverDeck.error.collectAsState()
    LaunchedEffect(Unit) { DiscoverDeck.refill() }
    val top = cards.firstOrNull()
    // chaque nouvelle carte : son extrait se lance
    LaunchedEffect(top?.id) { top?.let { DiscoverDeck.preview(it) } }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = Space.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PanelTitle("Découverte")
        Text("À droite : dans « ${DiscoverDeck.PLAYLIST} ». À gauche : pas pour moi.", color = Txt2, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            when {
                top != null -> {
                    // la carte suivante, derrière
                    cards.getOrNull(1)?.let { nx -> key(nx.id) { DeckCard(nx, Modifier.graphicsLayer { scaleX = 0.94f; scaleY = 0.94f; translationY = 28f; alpha = 0.6f }) } }
                    key(top.id) { SwipeCard(top) }
                }
                loading -> Text("Je cherche des sons pour toi…", color = Txt2, fontSize = 15.sp)
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(error ?: "Plus rien pour l'instant", color = Txt2, fontSize = 15.sp, textAlign = TextAlign.Center)
                    PillButton("Chercher encore") { DiscoverDeck.refill() }
                }
            }
        }
        // boutons pour ceux qui préfèrent toucher
        if (top != null) Row(Modifier.padding(vertical = 22.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            RoundAction(Icons.Rounded.Close, "Pas pour moi", Surface2, Txt) { DiscoverDeck.nope(top) }
            RoundAction(Icons.Rounded.Favorite, "Ajouter", Txt, Ink) { DiscoverDeck.like(top) }
        } else Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun key(k: Any, content: @Composable () -> Unit) = androidx.compose.runtime.key(k) { content() }

@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(68.dp).clip(RoundedCornerShape(12.dp)).background(bg).bounceClick(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(32.dp)) }
}

@Composable
private fun DeckCard(r: TidalApi.Result, modifier: Modifier = Modifier, overlay: @Composable () -> Unit = {}) {
    val colors = rememberUrlCoverColors(r.coverUrl)
    val band = lerp(colors.b, Color.Black, 0.6f)
    Column(modifier.fillMaxWidth().clip(Rounded(18)).background(band).padding(16.dp)) {
        Box {
            Cover(model = TidalApi.bigPicture(r.coverUrl), radius = 12.dp, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
            overlay()
        }
        Spacer(Modifier.height(16.dp))
        Text(r.fullTitle, color = Txt, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 26.sp)
        Text(r.artistLine, color = Txt.copy(alpha = 0.75f), fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        r.album?.let { Text(it, color = Txt.copy(alpha = 0.55f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun SwipeCard(r: TidalApi.Result) {
    val scope = rememberCoroutineScope()
    val x = remember { Animatable(0f) }
    val y = remember { Animatable(0f) }
    val width = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val threshold = width * 0.28f
    val p = (x.value / threshold).coerceIn(-1f, 1f)
    DeckCard(
        r,
        Modifier
            .graphicsLayer { translationX = x.value; translationY = y.value; rotationZ = x.value / width * 14f }
            .pointerInput(r.id) {
                detectDragGestures(
                    onDragEnd = {
                        scope.launch {
                            when {
                                x.value > threshold -> { x.animateTo(width * 1.4f, tween(220)); DiscoverDeck.like(r) }
                                x.value < -threshold -> { x.animateTo(-width * 1.4f, tween(220)); DiscoverDeck.nope(r) }
                                else -> { launch { y.animateTo(0f, spring()) }; x.animateTo(0f, spring(dampingRatio = 0.7f)) }
                            }
                        }
                    },
                ) { ch, d ->
                    ch.consume()
                    scope.launch { x.snapTo(x.value + d.x); y.snapTo(y.value + d.y * 0.3f) }
                }
            },
    ) {
        // le verdict apparaît pendant qu'on glisse
        if (abs(p) > 0.15f) Box(
            Modifier.padding(14.dp).clip(RoundedCornerShape(12.dp)).background(if (p > 0) Txt else Color.Black.copy(alpha = 0.7f))
                .graphicsLayer { alpha = abs(p) }.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(if (p > 0) "Je garde" else "Pas pour moi", color = if (p > 0) Ink else Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}
