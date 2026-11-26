package com.theo.remote.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Style
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.TidalApi
import com.theo.remote.TidalAuth
import com.theo.remote.TidalRemote

/** Tes playlists Tidal, partagées entre l'Accueil et la Biblio (chargées une fois). */
object PlaylistStore {
    private val _list = kotlinx.coroutines.flow.MutableStateFlow<List<TidalApi.Playlist>?>(null)
    val list: kotlinx.coroutines.flow.StateFlow<List<TidalApi.Playlist>?> = _list
    private val mutex = kotlinx.coroutines.sync.Mutex()

    suspend fun load(force: Boolean = false) {
        if (!force && _list.value != null) return
        // « Affichage instantané » : la liste gardée sur le téléphone s'affiche tout de suite, puis on la met à jour
        if (_list.value == null && com.theo.remote.Features.flags.value.homeCache) readDisk()?.let { _list.value = it }
        mutex.lock()
        try {
            val fresh = TidalApi.myPlaylists()
            _list.value = fresh
            writeDisk(fresh)
        } finally {
            mutex.unlock()
        }
    }

    private fun diskFile(): java.io.File? = com.theo.remote.TidalRemote.appContext?.let { java.io.File(it.filesDir, "playlists_cache.json") }

    private suspend fun readDisk(): List<TidalApi.Playlist>? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val a = org.json.JSONArray(diskFile()?.takeIf { it.exists() }?.readText() ?: return@runCatching null)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                TidalApi.Playlist(o.getString("id"), o.getString("name"), o.optInt("count"), o.optString("cover").ifBlank { null })
            }.ifEmpty { null }
        }.getOrNull()
    }

    private suspend fun writeDisk(list: List<TidalApi.Playlist>) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val a = org.json.JSONArray()
            list.forEach { p -> a.put(org.json.JSONObject().put("id", p.id).put("name", p.name).put("count", p.count).put("cover", p.coverUrl ?: "")) }
            diskFile()?.writeText(a.toString())
        }
    }

    fun clear() { _list.value = null }
}

// cache en mémoire pour ne pas tout recharger à chaque changement d'onglet
private var cachedPlaylists: List<TidalApi.Playlist>?
    get() = PlaylistStore.list.value
    set(v) { if (v == null) PlaylistStore.clear() }
internal val cachedTracks = HashMap<String, List<TidalApi.Result>>()

/** Copie des playlists sur le téléphone : elles s'affichent tout de suite, puis se mettent à jour. */
internal object PlaylistDisk {
    private fun file(ctx: android.content.Context, id: String) = java.io.File(ctx.cacheDir, "pl_" + id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")
    suspend fun get(ctx: android.content.Context, id: String): List<TidalApi.Result> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { com.theo.remote.History.decodeResults(file(ctx, id).readText()) }.getOrDefault(emptyList())
    }
    fun put(ctx: android.content.Context, id: String, list: List<TidalApi.Result>, cap: Int = 600) {
        val f = file(ctx, id)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { f.writeText(com.theo.remote.History.encodeResults(list.take(cap))) }
        }
    }
}

/**
 * La playlist ENTIÈRE (toutes les pages), pour un vrai aléatoire.
 * Chargée en fond dès qu'on ouvre la playlist, gardée en mémoire et sur le téléphone :
 * le mélange tire dans tous les sons sans faire attendre.
 */
internal object PlaylistFull {
    private val mem = java.util.concurrent.ConcurrentHashMap<String, List<TidalApi.Result>>()
    private val inflight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<List<TidalApi.Result>?>>()
    private val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    fun key(id: String, fav: Boolean) = if (fav) "full_favorites" else "full_$id"

    /** Ce qu'on connaît déjà en entier (mémoire, sinon téléphone), sans réseau. */
    suspend fun known(ctx: android.content.Context, key: String): List<TidalApi.Result>? =
        mem[key] ?: PlaylistDisk.get(ctx, key).ifEmpty { null }?.also { mem[key] = it }

    /** Charge toutes les pages (une seule fois à la fois par playlist). */
    suspend fun fetch(ctx: android.content.Context, key: String, page: suspend (String?) -> TidalApi.TrackPage): List<TidalApi.Result>? {
        val app = ctx.applicationContext
        val d = inflight[key] ?: bg.async {
            try {
                var acc = ArrayList<TidalApi.Result>()
                var nn: String? = null
                var guard = 0
                var ok = true
                do {
                    val pg = runCatching { page(nn) }.getOrNull()
                    if (pg == null) { ok = false; break }
                    acc.addAll(pg.tracks); nn = pg.next; guard++
                } while (nn != null && guard < 120)
                val all = acc.distinctBy { it.id }
                if (ok && all.isNotEmpty()) { mem[key] = all; PlaylistDisk.put(app, key, all, cap = 5000) }
                all.ifEmpty { null }
            } finally { inflight.remove(key) }
        }.also { inflight[key] = it }
        return d.await()
    }
}
internal val cachedNext = HashMap<String, String?>()

@Composable
fun LibraryScreen(s: TidalRemote.State, onOpen: (TidalApi.Playlist) -> Unit) {
    val loggedIn by TidalAuth.loggedIn.collectAsState()
    val authError by TidalAuth.error.collectAsState()
    val playlists by PlaylistStore.list.collectAsState()
    val canWrite by TidalAuth.canWrite.collectAsState()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    val libFlags by com.theo.remote.Features.flags.collectAsState()
    remember { PlaylistFolders.init(ctx) }
    val folderMap by PlaylistFolders.map.collectAsState()
    val folderSel by PlaylistFolders.selected.collectAsState()

    LaunchedEffect(loggedIn, reload) {
        if (!loggedIn) return@LaunchedEffect
        if (reload == 0 && playlists != null) return@LaunchedEffect
        loading = true
        error = null
        try {
            PlaylistStore.load(force = reload > 0)
            cachedTracks.clear()
            cachedNext.clear()
        } catch (e: Exception) {
            error = e.message ?: "Erreur réseau"
        }
        loading = false
    }

    val libList = rememberLazyListState()
    LaunchedEffect(Unit) { NavEvents.reselect.collect { if (it == "Library") libList.animateScrollToItem(0) } }
    val libScrolled by remember { androidx.compose.runtime.derivedStateOf { libList.firstVisibleItemIndex > 0 || libList.firstVisibleItemScrollOffset > 140 } }
    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        state = libList,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 190.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            // boutons en haut : Stats à gauche (l'onglet est devenu Cartes), réglages à droite
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Stats (et Cartes si on n'a gardé que 3 onglets) en boutons ici
                if (libFlags.songCards || libFlags.threeTabs) GlassCircle(Icons.Rounded.BarChart, "Stats") { Panels.show(Panels.Kind.Stats) }
                if (libFlags.threeTabs && libFlags.songCards) {
                    Spacer(Modifier.width(8.dp))
                    GlassCircle(Icons.Rounded.Style, "Cartes") { Panels.show(Panels.Kind.Cards) }
                }
                Spacer(Modifier.weight(1f))
                GlassCircle(Icons.Rounded.Settings, "Réglages") { Panels.show(Panels.Kind.Settings) }
                if (loggedIn) {
                    Spacer(Modifier.width(8.dp))
                    GlassCircle(Icons.Rounded.Refresh, "Resynchroniser") { reload++ }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Bibliothèque", color = Txt, fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.6).sp)
            Spacer(Modifier.height(12.dp))
            // une playlist faite à partir d'une phrase
            if (libFlags.instantPlaylist && loggedIn) {
                Row(
                    Modifier.fillMaxWidth().height(50.dp).clip(Rounded(25)).glass(Rounded(25), fill = 0.08f, edge = 0.14f)
                        .bounceClick(pressedScale = 0.98f) { InstantPlaylist.open = true }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = Txt, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Playlist instantanée : « rap FR calme pour la nuit »…", color = Txt2, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        // playlists intelligentes (pas besoin d'être connecté : elles viennent de tes écoutes)
        if (com.theo.remote.Features.flags.value.smartLists) {
            item(key = "smart") {
                SmartListsRow()
                Spacer(Modifier.height(Space.s))
            }
        }

        if (!loggedIn) {
            item {
                val shape = Rounded(22)
                Column(Modifier.fillMaxWidth().glass(shape).padding(18.dp)) {
                    Text("Connecte ton compte Tidal", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Pour retrouver tes playlists ici. Tu te connectes sur la page officielle de Tidal : Hush ne voit jamais ton mot de passe.",
                        color = Txt2, fontSize = 13.sp, lineHeight = 20.sp,
                    )
                    Spacer(Modifier.height(16.dp))
                    PillButton("Se connecter à Tidal") { TidalAuth.login(ctx) }
                    authError?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, color = Ko, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
            }
            return@LazyColumn
        }

        when {
            loading && playlists == null -> item { SkeletonList(5) }
            error != null -> item {
                Column {
                    Text(error!!, color = Ko, fontSize = 13.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(12.dp))
                    PillButton("Se reconnecter", filled = false) { TidalAuth.logout(); TidalAuth.login(ctx) }
                }
            }
            playlists?.isEmpty() == true -> item { EmptyState("Aucune playlist", "Crée ta première playlist juste en dessous.") }
        }

        item(key = "fav") {
            if (libFlags.localFiles) {
                val localTracks by com.theo.remote.LocalMusic.tracks.collectAsState()
                val localPls by com.theo.remote.LocalMusic.playlists.collectAsState()
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LibTile(
                        "Ma Musique", "Favoris Tidal", Icons.Rounded.Favorite,
                        flatOr(listOf(Color(0xFF5B3CF0), Color(0xFFE0458B)), flat = androidx.compose.ui.graphics.lerp(Ok, Color.Black, 0.55f)),
                        Modifier.weight(1f).cascadeIn(0, "tileFav"),
                    ) { onOpen(FavoritesPlaylist) }
                    LibTile(
                        "Fichiers locaux",
                        if (localTracks.isEmpty()) "Sons du téléphone" else "${localTracks.size} son${if (localTracks.size > 1) "s" else ""} · ${localPls.size} playlist${if (localPls.size > 1) "s" else ""}",
                        Icons.Rounded.LibraryMusic,
                        flatOr(listOf(Color(0xFF2E7D6B).copy(alpha = 0.85f), Color(0xFF1B3A4B)), flat = Surface2),
                        Modifier.weight(1f).cascadeIn(1, "tileLocal"),
                    ) { Panels.show(Panels.Kind.Local) }
                }
            } else FavoritesCard { onOpen(FavoritesPlaylist) }
            Spacer(Modifier.height(6.dp))
        }
        if (libFlags.ratings) item(key = "ratings") {
            RatingsLibRow()
            Spacer(Modifier.height(6.dp))
        }
        item(key = "new") {
            if (canWrite) {
                NewPlaylistRow(dashed = true) { name -> PlaylistEdit.createWith(name, emptyList()) }
            } else {
                val shape = Rounded(18)
                Column(Modifier.fillMaxWidth().glass(shape, fill = 0.06f, edge = 0.12f).padding(14.dp)) {
                    Text("Créer et modifier tes playlists", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("Reconnecte ton compte une fois pour donner ce droit à Hush.", color = Txt2, fontSize = 13.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(12.dp))
                    PillButton("Reconnecter Tidal", filled = false) { TidalAuth.logout(); PlaylistStore.clear(); TidalAuth.login(ctx) }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        val all = playlists.orEmpty()
        val list = if (libFlags.folders && folderSel != null) all.filter { folderMap[it.id] == folderSel } else all
        if (all.isNotEmpty()) {
            item {
                Text(
                    "${list.size} playlist${if (list.size > 1) "s" else ""}" + if (loading) " · mise à jour…" else "",
                    color = Txt, fontSize = 22.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(top = 10.dp),
                )
                if (libFlags.folders) FolderChips()
            }
            // grille 2 colonnes
            itemsIndexed(list.distinctBy { it.id }.chunked(2), key = { _, row -> row.first().id }) { ri, row ->
                Row(Modifier.animateItem().cascadeIn(ri + 2, "plrow" + row.first().id), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { p ->
                        PlaylistCard(p, Modifier.weight(1f), onLong = if (libFlags.folders) ({ PlaylistFolders.editing.value = p }) else null) { onOpen(p) }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            item {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Se déconnecter de Tidal",
                    color = Txt3, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).bounceClick(pressedScale = 0.96f) {
                        TidalAuth.logout(); PlaylistStore.clear()
                    }.padding(vertical = 8.dp),
                )
            }
        }
    }
    }
}

/** Tuile de la Biblio (Ma Musique, Fichiers locaux). */
@Composable
private fun LibTile(title: String, sub: String, icon: androidx.compose.ui.graphics.vector.ImageVector, brush: androidx.compose.ui.graphics.Brush, modifier: Modifier, onClick: () -> Unit) {
    val shape = Rounded(20)
    Column(
        modifier.height(104.dp).shadow(14.dp, shape).clip(shape).background(brush).glare()
            .glass(shape, fill = 0.02f, edge = 0.16f)
            .bounceClick(pressedScale = 0.96f, onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(icon, contentDescription = null, tint = Txt, modifier = Modifier.size(26.dp))
        Column {
            Text(title, color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = Txt.copy(alpha = 0.82f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Grande carte « Ma Musique » (tes favoris Tidal). */
@Composable
private fun FavoritesCard(onClick: () -> Unit) {
    val shape = Rounded(18)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(flatOr(listOf(Color(0xFF5B3CF0).copy(alpha = 0.85f), Color(0xFFE0458B).copy(alpha = 0.85f)), flat = androidx.compose.ui.graphics.lerp(Ok, Color.Black, 0.55f)))
            .glass(shape, fill = 0.04f, edge = 0.16f)
            .bounceClick(pressedScale = 0.97f, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Favorite, contentDescription = null, tint = Txt, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Ma Musique", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            Text("Tes titres favoris sur Tidal", color = Txt.copy(alpha = 0.8f), fontSize = 13.sp)
        }
    }
}

@Composable
private fun PlaylistCard(p: TidalApi.Playlist, modifier: Modifier, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val nowId by NowPlaylist.id.collectAsState()
    val st by TidalRemote.state.collectAsState()
    val isNow = nowId == p.id
    var mosaic by remember(p.id) { mutableStateOf<List<String>>(emptyList()) }
    val art = rememberPlaylistArt(p)
    if (art == null) LaunchedEffect(p.id) { mosaic = MosaicCache.get(ctx, p.id) }
    // ombre aux couleurs de la pochette, comme dans Apple Music
    val glow = rememberUrlCoverColors((art as? String) ?: p.coverUrl ?: mosaic.firstOrNull()).a
    var coverAt by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    Column(modifier.bounceClick(pressedScale = 0.95f, onLongClick = onLong) { PlaylistAnchor.set(p.id, coverAt); onClick() }.padding(bottom = 6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).onGloballyPositioned { coverAt = it.boundsInWindow() }.shadow(20.dp, Rounded(18), ambientColor = glow, spotColor = glow).clip(Rounded(18))) {
            if (art != null) Cover(model = art, radius = 16.dp, modifier = Modifier.fillMaxSize().glare())
            else Mosaic(mosaic, 16.dp, Modifier.fillMaxSize())
            if (isNow) Box(
                Modifier.align(Alignment.BottomEnd).padding(8.dp).size(30.dp).clip(PillShape).background(Color(0x99000000)),
                contentAlignment = Alignment.Center,
            ) { EqBars(playing = st.isPlaying, height = 12.dp) }
        }
        Spacer(Modifier.height(8.dp))
        Text(p.name, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${p.count} titre${if (p.count > 1) "s" else ""}" + if (isNow) " · en cours" else "", color = Txt2, fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------- une playlist

/** Identifiant spécial : « Ma Musique » (tes favoris Tidal) s'ouvre comme une playlist. */
const val FAVORITES_ID = "__favorites"
val FavoritesPlaylist = TidalApi.Playlist(FAVORITES_ID, "Ma Musique", 0, null)

private enum class Sort(val label: String) { Recent("Ordre"), Newest("Plus récent"), Oldest("Plus ancien"), EnergyUp("Calme → énervé"), EnergyDown("Énervé → calme"), Title("Titre"), Artist("Artiste"), Album("Album"), Duration("Durée"), Rating("Ta note") }

/**
 * Énergie d'un son, estimée d'après ce que Tidal donne : le tempo surtout, un peu plus pour une tonalité
 * majeure, un peu plus pour un son explicite. null = Tidal ne connaît pas son tempo (rangé à la fin).
 */
private fun energyOf(r: TidalApi.Result): Float? {
    val b = r.bpm ?: return null
    var e = b
    if (r.keyScale == "MAJOR") e += 4f
    if (r.explicit) e += 3f
    return e
}

/** Tri par énergie (les sons sans tempo connu restent à la fin, dans leur ordre). */
private fun byEnergy(list: List<TidalApi.Result>, up: Boolean): List<TidalApi.Result> {
    val (known, unknown) = list.partition { energyOf(it) != null }
    val sorted = if (up) known.sortedBy { energyOf(it) } else known.sortedByDescending { energyOf(it) }
    return sorted + unknown
}

@Composable
fun PlaylistScreen(p: TidalApi.Playlist, s: TidalRemote.State, onBack: () -> Unit) {
    val isFav = p.id == FAVORITES_ID
    var tracks by remember(p.id) { mutableStateOf(cachedTracks[p.id] ?: emptyList()) }
    var next by remember(p.id) { mutableStateOf(cachedNext[p.id]) }
    var started by remember(p.id) { mutableStateOf(cachedTracks.containsKey(p.id)) }
    var loading by remember(p.id) { mutableStateOf(false) }
    var error by remember(p.id) { mutableStateOf<String?>(null) }
    var name by remember(p.id) { mutableStateOf(p.name) }
    var editing by remember(p.id) { mutableStateOf(false) }
    var confirmDelete by remember(p.id) { mutableStateOf(false) }
    var filter by remember(p.id) { mutableStateOf("") }
    var sort by remember(p.id) { mutableStateOf(Sort.Recent) }
    val canWrite by TidalAuth.canWrite.collectAsState()
    val flags by com.theo.remote.Features.flags.collectAsState()
    val pctx = LocalContext.current
    var preview by remember(p.id) { mutableStateOf<List<TidalApi.Result>>(emptyList()) }
    // tes fichiers locaux rangés dans cette playlist (visibles seulement dans Hush)
    val attachedMap by com.theo.remote.LocalMusic.attached.collectAsState()
    val locals = if (isFav) emptyList() else attachedMap[p.id].orEmpty().mapNotNull { com.theo.remote.LocalMusic.byId(it) }
    val nowLocal by com.theo.remote.LocalPlayer.now.collectAsState()
    LaunchedEffect(p.id) { if (tracks.isEmpty()) preview = PlaylistDisk.get(pctx, p.id) }
    val list = rememberLazyListState()
    DismissWhenAtTop(list)
    val scope = rememberCoroutineScope()

    fun loadMore() {
        if (loading || (started && next == null)) return
        loading = true
        error = null
        scope.launch {
            try {
                val page = if (isFav) TidalApi.favoritesPage(if (started) next else null)
                else TidalApi.playlistPage(p.id, if (started) next else null)
                tracks = tracks + page.tracks
                next = page.next
                started = true
                cachedTracks[p.id] = tracks
                cachedNext[p.id] = next
                if (next == null || tracks.size <= 100) PlaylistDisk.put(pctx, p.id, tracks)
            } catch (e: Exception) {
                error = e.message ?: "Erreur réseau"
            }
            loading = false
        }
    }

    LaunchedEffect(p.id) { if (!started) loadMore() }
    // on charge toute la playlist en fond, pour que l'aléatoire tire dans tous les sons sans attendre
    LaunchedEffect(p.id) {
        kotlinx.coroutines.delay(500)
        val key = PlaylistFull.key(p.id, isFav)
        PlaylistFull.fetch(pctx, key) { n -> if (isFav) TidalApi.favoritesPage(n) else TidalApi.playlistPage(p.id, n) }
    }

    /** Playlist + fichiers locaux : on charge tout puis Hush enchaîne Tidal et local. */
    fun playMixed(shuffle: Boolean, startLocal: Int = -1, startTrack: String? = null) {
        fun T(r: TidalApi.Result) = com.theo.remote.LocalMusic.Entry.T(r)
        fun L(t: com.theo.remote.LocalMusic.Track) = com.theo.remote.LocalMusic.Entry.L(t)
        fun mix(l: List<com.theo.remote.LocalMusic.Entry>) = com.theo.remote.SmartShuffle.order(l,
            { e -> when (e) { is com.theo.remote.LocalMusic.Entry.L -> e.t.title; is com.theo.remote.LocalMusic.Entry.T -> e.r.title } },
            { e -> when (e) { is com.theo.remote.LocalMusic.Entry.L -> e.t.artist; is com.theo.remote.LocalMusic.Entry.T -> e.r.artistLine } })
        // on lance tout de suite avec ce qui est déjà chargé, le reste arrive en arrière-plan
        val loaded = tracks.ifEmpty { preview }
        val needAll = startLocal >= 0 || loaded.isEmpty()
        suspend fun page(n: String?) = if (isFav) TidalApi.favoritesPage(n) else TidalApi.playlistPage(p.id, n)
        scope.launch {
            // aléatoire : on tire dans TOUTE la playlist, pas seulement dans la première page chargée
            // (avant, le premier son sortait toujours des ~20 premiers, d'où les mêmes sons qui revenaient)
            if (shuffle && startLocal < 0) {
                val key = PlaylistFull.key(p.id, isFav)
                val known = PlaylistFull.known(pctx, key)
                // jamais vue en entier : on attend la liste complète (sinon le 1er son sortirait toujours du début)
                // pas encore en mémoire : plutôt que d'attendre tout le chargement, Tidal mélange lui-même
                // la playlist entière tout de suite ; la liste complète se charge en fond pour la prochaine fois
                if (known == null && !isFav && locals.isEmpty()) {
                    NowPlaylist.set(p.id)
                    TidalRemote.playPlaylistShuffled(p.name, p.id)
                    PlaylistFull.fetch(pctx, key) { page(it) }
                    return@launch
                }
                val all = known ?: run {
                    TidalRemote.showToast("Mélange de toute la playlist…")
                    PlaylistFull.fetch(pctx, key) { page(it) } ?: loaded
                }
                if (all.isEmpty() && locals.isEmpty()) return@launch
                val entries = all.distinctBy { it.id }.map { T(it) } + locals.map { L(it) }
                TidalRemote.setShuffle(false)
                NowPlaylist.set(p.id)
                com.theo.remote.MixQueue.play(pctx, mix(entries), 0, original = entries)
                TidalRemote.markShuffle(true)
                // liste reprise de la mémoire : on vérifie en fond s'il y a de nouveaux sons et on les glisse dans la suite
                if (known != null) {
                    val fresh = PlaylistFull.fetch(pctx, key) { page(it) } ?: return@launch
                    val ids = all.map { it.id }.toHashSet()
                    val added = fresh.filter { it.id !in ids }
                    if (added.isNotEmpty() && NowPlaylist.id.value == p.id) com.theo.remote.MixQueue.extend(added.map { T(it) }, true)
                }
                return@launch
            }
            var first = loaded
            var n = if (tracks.isEmpty()) null else next
            if (needAll) {
                // il faut tout avoir pour savoir où se trouve un fichier local (il est à la fin)
                first = tracks
                n = next
                if (!started) { val pg = runCatching { page(null) }.getOrNull(); if (pg != null) { first = pg.tracks; n = pg.next } }
                var guard = 0
                while (startLocal >= 0 && n != null && guard < 40) {
                    val pg = runCatching { page(n) }.getOrNull() ?: break
                    first = first + pg.tracks; n = pg.next; guard++
                }
            }
            val tail = if (n == null) locals.map { L(it) } else emptyList()
            val entries = first.map { T(it) } + tail
            val list = if (shuffle) mix(entries) else entries
            val start = when {
                startLocal >= 0 -> first.size + startLocal
                // un son touché dans la liste : la playlist part de lui, dans son ordre
                startTrack != null -> first.indexOfFirst { it.id == startTrack }.coerceAtLeast(0)
                else -> 0
            }
            TidalRemote.setShuffle(false)
            NowPlaylist.set(p.id)
            com.theo.remote.MixQueue.play(pctx, list, if (shuffle) 0 else start, original = if (shuffle) entries else null)
            TidalRemote.markShuffle(shuffle)
            // la suite de la playlist (pages suivantes + tes fichiers locaux)
            if (n != null) {
                var more = emptyList<TidalApi.Result>()
                var guard = 0
                while (n != null && guard < 40) {
                    val pg = runCatching { page(n) }.getOrNull() ?: break
                    more = more + pg.tracks; n = pg.next; guard++
                }
                if (NowPlaylist.id.value == p.id) com.theo.remote.MixQueue.extend(more.map { T(it) } + locals.map { L(it) }, shuffle)
            }
        }
    }

    /** Lit une liste dans l'ordre affiché (ex. tri par énergie), à partir d'un son. Hush enchaîne lui-même. */
    fun playInOrder(list: List<TidalApi.Result>, start: Int) {
        if (list.isEmpty()) return
        val entries = list.map { com.theo.remote.LocalMusic.Entry.T(it) as com.theo.remote.LocalMusic.Entry }
        TidalRemote.setShuffle(false)
        NowPlaylist.set(p.id)
        com.theo.remote.MixQueue.play(pctx, entries, start.coerceIn(0, entries.lastIndex))
        TidalRemote.markShuffle(false)
    }

    // favoris : on charge tout (pour trier et chercher) ; playlists : au fil du défilement
    val nearEnd by remember {
        derivedStateOf {
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= list.layoutInfo.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd, started, next, loading) {
        // une recherche ou un tri dans la playlist : on charge tout
        if (started && next != null && !loading && (isFav || nearEnd || filter.isNotBlank() || sort != Sort.Recent)) {
            if (isFav) kotlinx.coroutines.delay(80)
            loadMore()
        }
    }

    val ratingsNow by com.theo.remote.Ratings.items.collectAsState()
    val shown = remember(tracks, preview, filter, sort, if (sort == Sort.Rating) ratingsNow else null) {
        val q = filter.trim().lowercase()
        val base = if (tracks.isEmpty() && !started) preview else tracks
        val f = if (q.isEmpty()) base else base.filter {
            it.fullTitle.lowercase().contains(q) || it.artistLine.lowercase().contains(q) || (it.album?.lowercase()?.contains(q) == true)
        }
        when (sort) {
            Sort.Recent -> f
            // par date d'ajout ; sans date, l'ordre de la playlist (les ajouts arrivent à la fin)
            Sort.Newest -> f.withIndex().sortedWith(compareByDescending<IndexedValue<TidalApi.Result>> { it.value.addedAt ?: 0L }.thenByDescending { it.index }).map { it.value }
            Sort.Oldest -> f.withIndex().sortedWith(compareBy<IndexedValue<TidalApi.Result>> { it.value.addedAt ?: 0L }.thenBy { it.index }).map { it.value }
            Sort.Title -> f.sortedBy { it.title.lowercase() }
            Sort.Artist -> f.sortedBy { it.artistLine.lowercase() }
            Sort.Album -> f.sortedBy { (it.album ?: "").lowercase() }
            Sort.Duration -> f.sortedBy { it.durationMs }
            Sort.Rating -> f.sortedByDescending { com.theo.remote.Ratings.track(it.title, it.artistLine)?.stars ?: 0f }
            Sort.EnergyUp -> byEnergy(f, up = true)
            Sort.EnergyDown -> byEnergy(f, up = false)
        }
    }

    fun remove(r: TidalApi.Result) {
        val before = tracks
        tracks = tracks.filterNot { it === r }
        cachedTracks[p.id] = tracks
        scope.launch {
            try {
                TidalApi.removeFromPlaylist(p.id, listOf(r))
                PlaylistEdit.refreshLater(null)
                // « Annuler » : on remet le son dans la playlist (Tidal le range à la fin)
                TidalRemote.offerUndo("Retiré : ${r.fullTitle}") {
                    tracks = before
                    cachedTracks[p.id] = before
                    scope.launch {
                        runCatching { TidalApi.addToPlaylist(p.id, listOf(r.id)) }
                            .onFailure { TidalRemote.showToast("Impossible de le remettre") }
                        PlaylistEdit.refreshLater(null)
                    }
                }
            } catch (e: Exception) {
                tracks = before
                cachedTracks[p.id] = before
                TidalRemote.showToast(e.message ?: "Échec")
            }
        }
    }

    fun rename() {
        val n = name.trim()
        editing = false
        if (n.isEmpty() || n == p.name) { name = p.name; return }
        scope.launch {
            try {
                TidalApi.renamePlaylist(p.id, n)
                TidalRemote.showToast("Playlist renommée")
                PlaylistEdit.refreshLater(null)
            } catch (e: Exception) {
                name = p.name
                TidalRemote.showToast(e.message ?: "Échec")
            }
        }
    }

    val headColors = rememberUrlCoverColors(p.coverUrl)
    val flyFromRect = remember(p.id) { if (com.theo.remote.Features.flags.value.coverMorph) PlaylistAnchor.take(p.id) else null }
    val plGlass = rememberBackdrop()
    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        Modifier.fillMaxSize().backdropSource(plGlass).statusBarsPadding(),
        state = list,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 0.dp, bottom = 190.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "header") {
            // place des boutons du haut (ils flottent au-dessus de la liste, voir plus bas)
            Spacer(Modifier.height(50.dp))
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (isFav) {
                    Box(
                        Modifier.size(240.dp).shadow(30.dp, Rounded(14)).clip(Rounded(14))
                            .background(flatOr(listOf(Color(0xFF5B3CF0), Color(0xFFE0458B)), flat = androidx.compose.ui.graphics.lerp(Ok, Color.Black, 0.55f))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Favorite, contentDescription = null, tint = Txt, modifier = Modifier.size(88.dp))
                    }
                } else {
                    val headerMosaic = remember(tracks.size, preview.size) { (tracks.ifEmpty { preview }).mapNotNull { it.coverUrl }.distinct().take(4) }
                    val art = rememberPlaylistArt(p)
                    val canCover = flags.playlistCovers
                    val pick = { com.theo.remote.PlaylistCovers.editing.value = p.id to name }
                    Box(Modifier.clip(Rounded(18)).bounceClick(enabled = canCover, pressedScale = 0.98f, onLongClick = pick) { if (editing) pick() }) {
                        // ouverte depuis la grille : la pochette grandit depuis sa place dans la grille
                        if (art != null) Cover(model = art, radius = 18.dp, modifier = Modifier.flyFrom(flyFromRect).size(240.dp).shadow(34.dp, Rounded(18), ambientColor = headColors.a, spotColor = headColors.a).clip(Rounded(18)).glare())
                        else Mosaic(headerMosaic, 18.dp, Modifier.flyFrom(flyFromRect).size(240.dp).shadow(34.dp, Rounded(18), ambientColor = headColors.a, spotColor = headColors.a))
                    }
                }
            }
            if (editing && !isFav && flags.playlistCovers) {
                Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Changer la pochette", color = Txt2, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .bounceClick(pressedScale = 0.96f) { com.theo.remote.PlaylistCovers.editing.value = p.id to name }.padding(6.dp),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            if (editing) {
                val shape = Rounded(14)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f).height(50.dp).glass(shape, fill = 0.10f, edge = 0.18f).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = name,
                            onValueChange = { name = it.take(80) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Txt),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { rename() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    PillButton("OK") { rename() }
                }
            } else {
                // titre centré sous la pochette
                Text(
                    name, color = Txt, fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
            val count = if (isFav) tracks.size else p.count
            Text(
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                text = 
                if (isFav) "$count titre${if (count > 1) "s" else ""}" + if (next != null) " · chargement…" else "" else "${p.count} titre${if (p.count > 1) "s" else ""}" + if (locals.isNotEmpty()) " + ${locals.size} fichier${if (locals.size > 1) "s" else ""} local${if (locals.size > 1) "aux" else ""}" else "",
                color = Txt2, fontSize = 13.sp,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionPill(Icons.Rounded.PlayArrow, "Lire", filled = true, modifier = Modifier.weight(1f)) {
                    // tri par énergie : on lit la playlist dans cet ordre-là (montée ou descente en puissance)
                    if (sort != Sort.Recent) playInOrder(shown, 0)
                    // favoris et playlists avec fichiers locaux : Hush enchaîne lui-même toute la liste
                    else if (locals.isNotEmpty() || isFav) playMixed(false)
                    else {
                        TidalRemote.setShuffle(false)
                        NowPlaylist.set(p.id)
                        TidalRemote.playPlaylist(p.name, p.id.takeIf { !isFav })
                    }
                }
                ActionPill(Icons.Rounded.Shuffle, "Aléatoire", filled = false, modifier = Modifier.weight(1f)) {
                    // vrai aléatoire sur toute la playlist (pas une file d'attente) : Hush mélange tout, le bouton du lecteur reste actif
                    val all = tracks.ifEmpty { preview }
                    if (locals.isNotEmpty() || isFav || (com.theo.remote.Features.flags.value.smartShuffle && all.size >= 3)) playMixed(true)
                    else {
                        NowPlaylist.set(p.id)
                        TidalRemote.playPlaylistShuffled(p.name, p.id.takeIf { !isFav })
                    }
                }
            }
            if (editing) {
                Spacer(Modifier.height(12.dp))
                Text(
                    if (confirmDelete) "Touche encore pour supprimer définitivement" else "Supprimer la playlist",
                    color = Ko, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).bounceClick(pressedScale = 0.96f) {
                        if (!confirmDelete) confirmDelete = true else scope.launch {
                            try {
                                TidalApi.deletePlaylist(p.id)
                                TidalRemote.showToast("Playlist supprimée")
                                onBack()
                                PlaylistEdit.refreshLater(p.id)
                            } catch (e: Exception) {
                                TidalRemote.showToast(e.message ?: "Échec")
                            }
                        }
                    }.padding(vertical = 6.dp),
                )
            }
            // reprise exacte : là où tu t'étais arrêté
            val spots by com.theo.remote.PlaylistResume.spots.collectAsState()
            val spot = if (!isFav && flags.resumePlaylist) spots[p.id] else null
            val spotIdx = remember(spot, tracks.size) { spot?.let { com.theo.remote.PlaylistResume.indexIn(it, tracks) } ?: -1 }
            if (spot != null && spotIdx >= 0) {
                Spacer(Modifier.height(12.dp))
                val shape = BtnShape
                Row(
                    Modifier.fillMaxWidth().clip(shape).glass(shape, fill = 0.08f, edge = 0.14f)
                        .bounceClick(pressedScale = 0.98f) { com.theo.remote.PlaylistResume.resume(pctx, p.id, tracks) }
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Txt, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Reprendre · ${spot.title} · ${spot.posMs / 60000}:${"%02d".format((spot.posMs / 1000) % 60)}",
                        color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text("Oublier", color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).bounceClick { com.theo.remote.PlaylistResume.forget(p.id) }.padding(4.dp))
                }
            }
            if (isFav || flags.playlistSort) {
                Spacer(Modifier.height(16.dp))
                val shape = Rounded(14)
                Row(
                    Modifier.fillMaxWidth().height(46.dp).glass(shape, fill = 0.08f, edge = 0.14f).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = null, tint = Txt2, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (filter.isEmpty()) Text(if (isFav) "Chercher dans tes favoris" else "Chercher dans la playlist", color = Txt3, fontSize = 15.sp)
                        androidx.compose.foundation.text.BasicTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = Txt, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Txt),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Sort.entries.filter { it != Sort.EnergyUp && it != Sort.EnergyDown && it != Sort.Duration }) { o ->
                        val on = o == sort
                        val shape2 = PillShape
                        Box(
                            Modifier.clip(shape2)
                                .then(if (on) Modifier.background(Txt) else Modifier.glass(shape2, fill = 0.08f, edge = 0.14f))
                                .bounceClick { sort = o }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(o.label, color = if (on) Ink else Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        itemsIndexed(shown, key = { i, r -> "$i-${r.id}-${r.itemId}" }) { idx, r ->
            val playing = s.launch?.trackId == r.id && s.launch.status != TidalRemote.LaunchStatus.NoChange
            val bg by animateColorAsState(if (playing) Color.White.copy(alpha = 0.08f) else Color.Transparent, tween(250), label = "rowBg")
            SwipeToQueue(r, Modifier.animateItem().cascadeIn(idx, p.id + idx), enabled = !editing) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg)
                    .bounceClick(pressedScale = 0.97f, onLongClick = { TrackMenu.open(r) }) {
                        // le son touché lance la playlist à partir de lui (la suite enchaîne)
                        val all = tracks.ifEmpty { preview }
                        val at = all.indexOfFirst { it.id == r.id }
                        when {
                            // tri par énergie : la suite suit l'ordre affiché
                            sort != Sort.Recent -> playInOrder(shown, idx)
                            at < 0 -> TidalRemote.play(r)
                            // playlist Tidal simple : Tidal la lit dans son ordre, on saute juste au son touché
                            !isFav && locals.isEmpty() -> {
                                NowPlaylist.set(p.id)
                                val occ = all.take(at).count { it.title.equals(r.title, true) }
                                TidalRemote.playPlaylistFrom(p.name, r.fullTitle, r.title, occ, p.id.takeIf { !isFav }) { playMixed(false, startTrack = r.id) }
                            }
                            else -> playMixed(false, startTrack = r.id)
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))) {
                    Cover(model = r.coverUrl, radius = 10.dp, modifier = Modifier.fillMaxSize())
                    if (playing) PlayingOverlay(s.isPlaying, Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.fullTitle, color = Txt, fontSize = 15.sp, fontWeight = if (playing) FontWeight.ExtraBold else FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(r.artistLine, color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                if (editing) {
                    Box(
                        Modifier.size(44.dp).clip(PillShape).bounceClick(pressedScale = 0.8f) { remove(r) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.RemoveCircleOutline, contentDescription = "Retirer de la playlist", tint = Ko, modifier = Modifier.size(24.dp))
                    }
                } else {
                    RatingBadge(r.title, r.artistLine)
                    // tri par énergie : on montre le tempo à la place de la durée
                    if ((sort == Sort.EnergyUp || sort == Sort.EnergyDown) && r.bpm != null) Text("${r.bpm.toInt()} BPM", color = Txt2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    else Text(fmt(r.durationMs), color = Txt2, fontSize = 12.sp)
                    AddToQueueButton(r)
                }
            }
            }
        }

        if (locals.isNotEmpty() && (next == null || !started)) {
            item(key = "localsHead") {
                Text("Tes fichiers locaux · visibles seulement dans Hush", color = Txt2, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp, start = 6.dp))
            }
            itemsIndexed(locals, key = { _, t -> "loc" + t.id }) { i, t ->
                AttachedLocalRow(t, playing = nowLocal == t.id, onClick = { playMixed(false, i) }) {
                    if (editing) Box(
                        Modifier.size(44.dp).clip(PillShape).bounceClick(pressedScale = 0.8f) { com.theo.remote.LocalMusic.detach(p.id, t.id) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.RemoveCircleOutline, contentDescription = "Retirer de la playlist", tint = Ko, modifier = Modifier.size(24.dp)) }
                    else Text(fmt(t.durationMs), color = Txt2, fontSize = 12.sp)
                }
            }
        }

        item(key = "footer") {
            when {
                error != null -> Column(Modifier.padding(top = 8.dp)) {
                    Text(error!!, color = Ko, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    PillButton("Réessayer", filled = false) { loadMore() }
                }
                loading && tracks.isEmpty() && preview.isEmpty() -> SkeletonList(8)
                loading && !isFav -> SkeletonList(3)
                started && tracks.isEmpty() && locals.isEmpty() -> EmptyState(
                    if (isFav) "Aucun favori" else "Playlist vide",
                    if (isFav) "Touche le cœur dans le lecteur pour garder un son ici." else "Ajoute des sons depuis la recherche, ou avec ⋯ → Ajouter à une playlist.",
                )
                isFav && filter.isNotBlank() && shown.isEmpty() -> Text("Rien ne correspond.", color = Txt2, fontSize = 13.sp)
            }
        }

        if (!isFav && flags.playlistRecs && started && next == null && tracks.isNotEmpty()) {
            item(key = "recs") {
                PlaylistRecs(p, tracks, canWrite, name) { r ->
                    tracks = tracks + r
                    cachedTracks.remove(p.id); cachedNext.remove(p.id)
                }
            }
        }
    }
    // le nom de la playlist reste en haut quand on descend dans la liste
    val scrolledPast by remember { androidx.compose.runtime.derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 420 } }
    // en haut : fermer à gauche, Transitions, Docteur et Modifier à droite.
    // Ils restent en place quand on fait défiler, en verre liquide par-dessus la pochette et les sons.
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassCircle(Icons.Rounded.KeyboardArrowDown, "Fermer la playlist", backdrop = plGlass) { onBack() }
        Spacer(Modifier.weight(1f))
        if (!editing) {
            GlassCircle(Icons.Rounded.SwapVert, "Transitions", backdrop = plGlass) { TransitionEdit.forPlaylist(name, shown.ifEmpty { tracks }) }
            if (!isFav && canWrite && flags.playlistDoctor) {
                Spacer(Modifier.width(8.dp))
                GlassCircle(Icons.Rounded.CleaningServices, "Docteur de playlist", backdrop = plGlass) {
                    PlaylistDoctorState.open(p) {
                        // playlist nettoyée : on la recharge depuis Tidal
                        cachedTracks.remove(p.id); cachedNext.remove(p.id)
                        tracks = emptyList(); next = null; started = false
                        loadMore()
                    }
                }
            }
            if (!isFav && canWrite) {
                Spacer(Modifier.width(8.dp))
                GlassCircle(Icons.Rounded.Edit, "Modifier", backdrop = plGlass) { editing = true; confirmDelete = false }
            }
        }
    }

    }
}

private val recsCache = HashMap<String, List<TidalApi.Result>>()
/** Ce qu'on a déjà proposé (pour ne pas reproposer la même chose). */
private val recsSeen = HashMap<String, MutableSet<String>>()

/** « Recommandés » en bas d'une playlist, avec un bouton + pour ajouter direct. */
@Composable
private fun PlaylistRecs(p: TidalApi.Playlist, tracks: List<TidalApi.Result>, canWrite: Boolean, name: String, onAdded: (TidalApi.Result) -> Unit) {
    var recs by remember(p.id) { mutableStateOf(recsCache[p.id]) }
    var round by remember(p.id) { mutableStateOf(0) }
    val adding = remember(p.id) { androidx.compose.runtime.mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(p.id, round) {
        if (round == 0 && recs != null) return@LaunchedEffect
        recs = null
        val seen = recsSeen.getOrPut(p.id) { HashSet() }
        val got = runCatching { TidalApi.playlistRecommendations(tracks, exclude = seen) }.getOrDefault(emptyList())
        seen += got.map { it.id }
        recsCache[p.id] = got
        recs = got
    }
    Column(Modifier.fillMaxWidth().padding(top = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Recommandés", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                Text("D'après les sons de cette playlist", color = Txt2, fontSize = 13.sp)
            }
            GlassCircle(Icons.Rounded.Refresh, "Autres suggestions") { round++ }
        }
        Spacer(Modifier.height(12.dp))
        val l = recs
        when {
            l == null -> SkeletonList(4)
            l.isEmpty() -> Text("Pas de suggestion pour l'instant.", color = Txt2, fontSize = 13.sp)
            else -> l.forEach { r ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .bounceClick(pressedScale = 0.97f, onLongClick = { TrackMenu.open(r) }) { TidalRemote.play(r) }
                        .padding(horizontal = 6.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(model = r.coverUrl, radius = 6.dp, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.fullTitle, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(r.artistLine, color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    val busy = r.id in adding
                    Box(
                        Modifier.size(38.dp).glass(PillShape, fill = 0.12f, edge = 0.2f).clip(PillShape)
                            .bounceClick(pressedScale = 0.8f) {
                                if (busy) return@bounceClick
                                if (!canWrite) { TidalRemote.showToast("Reconnecte ton compte Tidal pour modifier tes playlists"); return@bounceClick }
                                adding += r.id
                                scope.launch {
                                    try {
                                        TidalApi.addToPlaylist(p.id, listOf(r.id))
                                        recs = recs?.filterNot { it.id == r.id }
                                        recsCache[p.id] = recs.orEmpty()
                                        onAdded(r)
                                        TidalRemote.showToast("Ajouté à « $name »")
                                        PlaylistEdit.refreshLater(null)
                                    } catch (e: Exception) {
                                        TidalRemote.showToast(e.message ?: "Échec de l'ajout")
                                    }
                                    adding -= r.id
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (busy) Icons.Rounded.Check else Icons.Rounded.Add, contentDescription = "Ajouter à la playlist", tint = Txt, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionPill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, filled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = BtnShape
    Row(
        modifier
            .height(56.dp)
            .then(if (filled) Modifier.clip(shape).background(CoverTint.current() ?: Txt) else Modifier.glass(shape, fill = 0.12f, edge = 0.16f))
            .bounceClick(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (filled) Ink else Txt, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = if (filled) Ink else Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}
