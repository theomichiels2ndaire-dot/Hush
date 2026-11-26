package com.theo.remote.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.UploadFile
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.LocalMusic
import com.theo.remote.LocalPlayer
import com.theo.remote.SmartShuffle
import com.theo.remote.TidalRemote
import kotlinx.coroutines.launch
import java.io.File

/** Dans Stats : tes écoutes de fichiers locaux, comptées à part. */
@Composable
fun LocalStatsCard() {
    val stats by LocalMusic.stats.collectAsState()
    val plays = stats.values.sumOf { it.plays }
    if (plays == 0) return
    val mins = stats.values.sumOf { it.ms } / 60_000
    val top = stats.entries.sortedByDescending { it.value.plays }.take(3).mapNotNull { e -> LocalMusic.byId(e.key)?.let { it to e.value.plays } }
    Spacer(Modifier.height(Space.s))
    Column(
        Modifier.fillMaxWidth().glass(Rounded(20), fill = 0.06f, edge = 0.10f)
            .bounceClick(pressedScale = 0.98f) { Panels.show(Panels.Kind.Local) }.padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Fichiers locaux", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        Text("$plays écoute${if (plays > 1) "s" else ""} · $mins min (à part de tes stats Tidal)", color = Txt2, fontSize = 13.sp)
        top.forEachIndexed { i, (t, n) ->
            Text("${i + 1}. ${t.title} · ${t.artist} — $n×", color = Txt, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Carte « Fichiers locaux » de la Biblio. */
@Composable
fun LocalFilesCard() {
    val tracks by LocalMusic.tracks.collectAsState()
    val pls by LocalMusic.playlists.collectAsState()
    val shape = Rounded(18)
    Row(
        Modifier.fillMaxWidth().clip(shape).glass(shape, fill = 0.08f, edge = 0.14f)
            .bounceClick(pressedScale = 0.97f) { Panels.show(Panels.Kind.Local) }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.LibraryMusic, contentDescription = null, tint = Txt, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Fichiers locaux", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                if (tracks.isEmpty()) "Ajoute les sons de ton téléphone"
                else "${tracks.size} son${if (tracks.size > 1) "s" else ""} · ${pls.size} playlist${if (pls.size > 1) "s" else ""}",
                color = Txt2, fontSize = 13.sp,
            )
        }
    }
}

@Composable
fun LocalPanel() {
    val ctx = LocalContext.current
    val tracks by LocalMusic.tracks.collectAsState()
    val pls by LocalMusic.playlists.collectAsState()
    val now by LocalPlayer.now.collectAsState()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    var openId by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var picking by remember { mutableStateOf<List<String>?>(null) }
    var addingTo by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<LocalMusic.Track?>(null) }
    var editing by remember { mutableStateOf<LocalMusic.Track?>(null) }
    val stats by LocalMusic.stats.collectAsState()
    androidx.compose.runtime.LaunchedEffect(tracks.size) { runCatching { LocalMusic.fetchMissingArt() } }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val n = LocalMusic.importFiles(ctx, uris)
            importing = false
            TidalRemote.showToast(if (n == 0) "Aucun nouveau son" else "$n son${if (n > 1) "s" else ""} ajouté${if (n > 1) "s" else ""}")
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val n = LocalMusic.importFolder(ctx, uri)
            importing = false
            TidalRemote.showToast(if (n == 0) "Aucun nouveau son dans ce dossier" else "$n son${if (n > 1) "s" else ""} ajouté${if (n > 1) "s" else ""}")
        }
    }

    val open = pls.firstOrNull { it.id == openId }
    BackHandler(enabled = picking != null || addingTo != null || menuFor != null || editing != null || selected.isNotEmpty() || open != null) {
        when {
            editing != null -> editing = null
            menuFor != null -> menuFor = null
            picking != null -> picking = null
            addingTo != null -> addingTo = null
            selected.isNotEmpty() -> selected = emptySet()
            else -> openId = null
        }
    }

    fun toggle(id: String) { selected = if (id in selected) selected - id else selected + id }

    Box(Modifier.fillMaxSize()) {
        val list = rememberLazyListState()
        DismissWhenAtTop(list)
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(), state = list,
            contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = 160.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (open != null) {
                // ---------------- une playlist Hush (fichiers locaux + sons Tidal)
                val entries = LocalMusic.entriesOf(open)
                item(key = "plh") {
                    PanelTitle(open.name)
                    val nT = entries.count { it is LocalMusic.Entry.T }
                    Text(
                        "${entries.size} son${if (entries.size > 1) "s" else ""}" + if (nT > 0) " · dont $nT de Tidal" else " · playlist Hush",
                        color = Txt2, fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        LocalPill(Icons.Rounded.PlayArrow, "Lire", true, Modifier.weight(1f)) { com.theo.remote.MixQueue.play(ctx, entries, 0) }
                        LocalPill(Icons.Rounded.Shuffle, "Aléatoire", false, Modifier.weight(1f)) {
                            com.theo.remote.MixQueue.play(ctx, SmartShuffle.order(entries, { e -> entryTitle(e) }, { e -> entryArtist(e) }), 0)
                        }
                        GlassCircle(Icons.AutoMirrored.Rounded.ArrowBack, "Retour") { openId = null }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TextAction("+ Ajouter des sons") { addingTo = open.id }
                        TextAction("Supprimer la playlist", Ko) { LocalMusic.deletePlaylist(open.id); openId = null }
                    }
                    Text("Pour y mettre un son Tidal : ⋯ sur le son → Ajouter à une playlist → section Hush.", color = Txt3, fontSize = 12.sp, lineHeight = 16.sp)
                    Spacer(Modifier.height(12.dp))
                    if (entries.isEmpty()) Text("Playlist vide : ajoute des sons.", color = Txt2, fontSize = 13.sp)
                }
                itemsIndexed(entries, key = { _, e -> "p" + e.key }) { i, e ->
                    val remove: @Composable () -> Unit = {
                        Box(Modifier.size(40.dp).clip(PillShape).bounceClick(pressedScale = 0.8f) { LocalMusic.removeFrom(open.id, e.key) }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.RemoveCircleOutline, contentDescription = "Retirer de la playlist", tint = Ko, modifier = Modifier.size(22.dp))
                        }
                    }
                    when (e) {
                        is LocalMusic.Entry.L -> LocalRow(e.t, playing = now == e.t.id, onClick = { com.theo.remote.MixQueue.play(ctx, entries, i) }, trailing = remove)
                        is LocalMusic.Entry.T -> TidalEntryRow(e.r, onClick = { com.theo.remote.MixQueue.play(ctx, entries, i) }, trailing = remove)
                    }
                }
            } else {
                item(key = "h") {
                    PanelTitle("Fichiers locaux")
                    Text("Les sons de ton téléphone, lus par Hush. Tidal se met en pause pendant ce temps.", color = Txt2, fontSize = 13.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LocalPill(Icons.Rounded.UploadFile, "Ajouter des sons", true, Modifier.weight(1f)) { pickFiles.launch(arrayOf("audio/*")) }
                        LocalPill(Icons.Rounded.CreateNewFolder, "Un dossier", false, Modifier.weight(1f)) { pickFolder.launch(null) }
                    }
                    if (importing) {
                        Spacer(Modifier.height(12.dp))
                        Text("Import en cours…", color = Txt2, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Sons (${tracks.size})", "Playlists (${pls.size})").forEachIndexed { i, label ->
                            val on = tab == i
                            val shape = PillShape
                            Box(
                                Modifier.clip(shape).then(if (on) Modifier.background(Txt) else Modifier.glass(shape, fill = 0.08f, edge = 0.14f))
                                    .bounceClick { tab = i }.padding(horizontal = 16.dp, vertical = 9.dp),
                            ) { Text(label, color = if (on) Ink else Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (tab == 0) {
                    val shown = if (filter.isBlank()) tracks else tracks.filter {
                        it.title.contains(filter, true) || it.artist.contains(filter, true) || it.album.contains(filter, true)
                    }
                    item(key = "tools") {
                        if (tracks.isEmpty()) {
                            EmptyState("Aucun son pour l'instant", "Ajoute des fichiers ou un dossier entier (MP3, FLAC, M4A, WAV…).", "Ajouter des sons") { pickFiles.launch(arrayOf("audio/*")) }
                        } else {
                            LocalSearch(filter) { filter = it }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                LocalPill(Icons.Rounded.PlayArrow, "Tout lire", true, Modifier.weight(1f)) { LocalPlayer.play(ctx, shown, 0) }
                                LocalPill(Icons.Rounded.Shuffle, "Aléatoire", false, Modifier.weight(1f)) {
                                    LocalPlayer.play(ctx, SmartShuffle.order(shown, { it.title }, { it.artist }), 0)
                                }
                            }
                            val plays = stats.values.sumOf { it.plays }
                            if (plays > 0) {
                                Spacer(Modifier.height(12.dp))
                                val mins = stats.values.sumOf { it.ms } / 60_000
                                val top = stats.maxByOrNull { it.value.plays }?.let { LocalMusic.byId(it.key) }
                                Column(Modifier.fillMaxWidth().glass(Rounded(16), fill = 0.06f, edge = 0.10f).padding(12.dp)) {
                                    Text("Tes écoutes locales", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                                    Text("$plays écoute${if (plays > 1) "s" else ""} · $mins min" + (top?.let { " · n°1 : ${it.title}" } ?: ""), color = Txt2, fontSize = 13.sp, maxLines = 2)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("Appui long pour sélectionner plusieurs sons.", color = Txt3, fontSize = 12.sp)
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    itemsIndexed(shown.distinctBy { it.id }, key = { _, t -> t.id }) { i, t ->
                        val sel = t.id in selected
                        LocalRow(
                            t, playing = now == t.id, selected = sel,
                            onLongClick = { toggle(t.id) },
                            onClick = { if (selected.isNotEmpty()) toggle(t.id) else LocalPlayer.play(ctx, shown, shown.indexOf(t).coerceAtLeast(0)) },
                        ) {
                            if (selected.isNotEmpty()) CheckDot(sel)
                            else Box(Modifier.size(40.dp).clip(PillShape).bounceClick(pressedScale = 0.8f) { menuFor = t }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "Plus d'actions", tint = Txt2, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                } else {
                    item(key = "new") {
                        NewPlaylistRow { name -> LocalMusic.createPlaylist(name) }
                        Spacer(Modifier.height(6.dp))
                        if (pls.isEmpty()) Text("Crée une playlist puis ajoute-y tes sons.", color = Txt2, fontSize = 13.sp)
                    }
                    items(pls, key = { it.id }) { p ->
                        val first = LocalMusic.tracksOf(p).firstOrNull { it.art != null }
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).bounceClick(pressedScale = 0.97f) { openId = p.id }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LocalCover(first, 52)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${p.ids.size} son${if (p.ids.size > 1) "s" else ""}", color = Txt2, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // barre de sélection
        if (selected.isNotEmpty() && open == null) {
            val shape = Rounded(22)
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(Space.gutter).fillMaxWidth()
                    .clip(shape).background(Color(0xFF1B1B24)).glass(shape, fill = 0.06f, edge = 0.16f).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${selected.size} sélectionné${if (selected.size > 1) "s" else ""}", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (selected.size == 1) {
                    GlassCircle(Icons.Rounded.Edit, "Modifier les infos") { editing = LocalMusic.byId(selected.first()); selected = emptySet() }
                    Spacer(Modifier.width(8.dp))
                }
                GlassCircle(Icons.AutoMirrored.Rounded.PlaylistAdd, "Ajouter à des playlists") { picking = selected.toList() }
                Spacer(Modifier.width(8.dp))
                GlassCircle(Icons.Rounded.DeleteOutline, "Retirer de Hush") {
                    LocalMusic.remove(selected); TidalRemote.showToast("Retiré de Hush (le fichier reste sur ton téléphone)"); selected = emptySet()
                }
                Spacer(Modifier.width(8.dp))
                GlassCircle(Icons.Rounded.Close, "Annuler") { selected = emptySet() }
            }
        }

        if (selected.isEmpty()) PanelMiniPlayer(Modifier.align(Alignment.BottomCenter))

        picking?.let { ids ->
            LocalPlaylistPicker(ids, onClose = { picking = null }) { selected = emptySet() }
        }
        addingTo?.let { pid ->
            LocalTrackPicker(pid, onClose = { addingTo = null })
        }
        menuFor?.let { t ->
            LocalTrackMenu(t, onClose = { menuFor = null },
                onPlaylist = { menuFor = null; picking = listOf(t.id) },
                onEdit = { menuFor = null; editing = t })
        }
        editing?.let { t -> EditInfoSheet(t) { editing = null } }
    }
}

/** Choisir une ou plusieurs playlists (Hush ou Tidal) pour y ranger des sons locaux. */
@Composable
private fun LocalPlaylistPicker(ids: List<String>, onClose: () -> Unit, onDone: () -> Unit) {
    val pls by LocalMusic.playlists.collectAsState()
    val tidalPls by PlaylistStore.list.collectAsState()
    val loggedIn by com.theo.remote.TidalAuth.loggedIn.collectAsState()
    val canWrite by com.theo.remote.TidalAuth.canWrite.collectAsState()
    var chosen by remember { mutableStateOf<Set<String>>(emptySet()) }
    androidx.compose.runtime.LaunchedEffect(loggedIn) { if (loggedIn) runCatching { PlaylistStore.load() } }
    val tidalList = if (loggedIn && canWrite) tidalPls.orEmpty() else emptyList()

    fun confirm() {
        val hush = pls.filter { it.id in chosen }.map { it.id }
        val tidal = tidalList.filter { it.id in chosen }
        if (hush.isNotEmpty()) LocalMusic.addTo(hush, ids)
        if (tidal.isEmpty()) {
            TidalRemote.showToast(if (hush.size == 1) "Ajouté à « ${pls.firstOrNull { it.id in chosen }?.name} »" else "Ajouté à ${hush.size} playlists")
            onDone(); onClose(); return
        }
        // Tidal ne peut pas stocker tes fichiers : Hush les range dans la playlist de son côté
        LocalMusic.attach(tidal.map { it.id }, ids)
        val n = hush.size + tidal.size
        val one = tidal.firstOrNull()?.name ?: pls.firstOrNull { it.id in chosen }?.name
        TidalRemote.showToast(if (n == 1) "Ajouté à « $one »" else "Ajouté à $n playlists")
        onDone(); onClose()
    }

    @Composable
    fun PickRow(id: String, name: String, sub: String, cover: @Composable () -> Unit) {
        val on = id in chosen
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .bounceClick(pressedScale = 0.97f) { chosen = if (on) chosen - id else chosen + id }
                .padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            cover()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, color = Txt2, fontSize = 12.sp)
            }
            CheckDot(on)
        }
    }

    Sheet(onClose) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ajouter à une playlist", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            if (chosen.isNotEmpty()) PillButton(if (chosen.size == 1) "Ajouter" else "Ajouter (${chosen.size})") { confirm() }
        }
        Text(if (ids.size == 1) LocalMusic.byId(ids.first())?.title ?: "" else "${ids.size} sons", color = Txt2, fontSize = 13.sp)
        Spacer(Modifier.height(Space.s))
        NewPlaylistRow { name ->
            LocalMusic.createPlaylist(name, ids)
            TidalRemote.showToast("Playlist « $name » créée")
            onDone(); onClose()
        }
        LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (pls.isNotEmpty()) item(key = "hh") { Text("Playlists Hush", color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) }
            items(pls, key = { "h" + it.id }) { p ->
                PickRow(p.id, p.name, "${p.ids.size} son${if (p.ids.size > 1) "s" else ""}") { LocalCover(LocalMusic.tracksOf(p).firstOrNull { it.art != null }, 48) }
            }
            if (tidalList.isNotEmpty()) {
                item(key = "th") {
                    Text("Playlists Tidal", color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 8.dp))
                    Text("Ton fichier y apparaît dans Hush (pas dans l'appli Tidal).", color = Txt3, fontSize = 11.sp, modifier = Modifier.padding(bottom = 2.dp))
                }
                items(tidalList, key = { "t" + it.id }) { p ->
                    PickRow(p.id, p.name, "${p.count} titre${if (p.count > 1) "s" else ""} · Tidal") { PlaylistThumb(p) }
                }
            }
        }
    }
}

/** Choisir des sons de ta bibliothèque locale à mettre dans une playlist. */
@Composable
private fun LocalTrackPicker(playlistId: String, onClose: () -> Unit) {
    val tracks by LocalMusic.tracks.collectAsState()
    val pls by LocalMusic.playlists.collectAsState()
    val already = pls.firstOrNull { it.id == playlistId }?.ids.orEmpty().toSet()
    var chosen by remember { mutableStateOf<Set<String>>(emptySet()) }
    var filter by remember { mutableStateOf("") }
    val shown = tracks.filter { it.id !in already }.filter {
        filter.isBlank() || it.title.contains(filter, true) || it.artist.contains(filter, true)
    }
    Sheet(onClose) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ajouter des sons", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            if (chosen.isNotEmpty()) PillButton("Ajouter (${chosen.size})") {
                LocalMusic.addTo(listOf(playlistId), chosen.toList())
                onClose()
            }
        }
        Spacer(Modifier.height(Space.s))
        if (tracks.isEmpty()) Text("Ajoute d'abord des sons dans l'onglet Sons.", color = Txt2, fontSize = 13.sp)
        else LocalSearch(filter) { filter = it }
        Spacer(Modifier.height(Space.xs))
        LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(shown.distinctBy { it.id }, key = { it.id }) { t ->
                val on = t.id in chosen
                LocalRow(t, playing = false, selected = on, onClick = { chosen = if (on) chosen - t.id else chosen + t.id }) { CheckDot(on) }
            }
        }
    }
}

/** Demande d'ouvrir le grand lecteur depuis un panneau. */
object PlayerOpen {
    private val _req = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requests: kotlinx.coroutines.flow.SharedFlow<Unit> = _req
    fun request() { _req.tryEmit(Unit) }
}

/** Mini-lecteur en bas des Fichiers locaux. */
@Composable
fun PanelMiniPlayer(modifier: Modifier = Modifier) {
    val s by TidalRemote.state.collectAsState()
    val t = s.track
    androidx.compose.animation.AnimatedVisibility(
        visible = s.connected && t != null, modifier = modifier,
        enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
    ) {
        val tr = t ?: return@AnimatedVisibility
        val pos = rememberLivePosition(s, intervalMs = 1000)
        val progress = if (tr.durationMs > 0) (pos.toFloat() / tr.durationMs).coerceIn(0f, 1f) else 0f
        val cover = rememberCoverColors(tr.art)
        val shape = Rounded(16)
        Box(
            Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth().height(64.dp)
                .clip(shape)
                .background(flatOr(listOf(cover.a.copy(alpha = 0.85f), cover.b.copy(alpha = 0.9f)), flat = androidx.compose.ui.graphics.lerp(cover.a, Color.Black, 0.32f)))
                .background(Color.Black.copy(alpha = 0.30f))
                .glass(shape, fill = 0.05f, edge = 0.16f)
                .bounceClick(pressedScale = 0.98f) { Panels.closeAll(); PlayerOpen.request() },
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(bitmap = tr.art, radius = 8.dp, modifier = Modifier.size(48.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr.title, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(tr.artist, color = Txt2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                MiniBtn(Icons.Rounded.SkipPrevious, "Précédent", 24) { TidalRemote.previous() }
                MiniBtn(if (s.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (s.isPlaying) "Pause" else "Lecture", 28) { TidalRemote.playPause() }
                MiniBtn(Icons.Rounded.SkipNext, "Suivant", 24) { TidalRemote.next() }
            }
            Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 12.dp).fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.15f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(Txt))
            }
        }
    }
}

@Composable
private fun MiniBtn(icon: ImageVector, label: String, size: Int, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(PillShape).bounceClick(pressedScale = 0.8f, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = Txt, modifier = Modifier.size(size.dp))
    }
}

private fun entryTitle(e: LocalMusic.Entry) = when (e) { is LocalMusic.Entry.L -> e.t.title; is LocalMusic.Entry.T -> e.r.fullTitle }
private fun entryArtist(e: LocalMusic.Entry) = when (e) { is LocalMusic.Entry.L -> e.t.artist; is LocalMusic.Entry.T -> e.r.artistLine }

/** Un son Tidal dans une playlist Hush. */
@Composable
private fun TidalEntryRow(r: com.theo.remote.TidalApi.Result, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .bounceClick(pressedScale = 0.97f, onLongClick = { TrackMenu.open(r) }, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(model = r.coverUrl, radius = 8.dp, modifier = Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.fullTitle, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tidal", color = Ink, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Txt2).padding(horizontal = 4.dp, vertical = 1.dp))
                Spacer(Modifier.width(6.dp))
                Text(r.artistLine, color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

/** Le menu ⋯ d'un son local : les mêmes actions qu'un son Tidal, au même endroit. */
@Composable
private fun LocalTrackMenu(t: LocalMusic.Track, onClose: () -> Unit, onPlaylist: () -> Unit, onEdit: () -> Unit) {
    val ctx = LocalContext.current
    Sheet(onClose) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LocalCover(t, 52)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.artist + if (t.album.isNotBlank()) " · " + t.album else "", color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(Space.s))
        MenuAction(Icons.Rounded.PlayArrow, "Lire") { onClose(); LocalPlayer.play(ctx, listOf(t), 0) }
        MenuAction(Icons.AutoMirrored.Rounded.QueueMusic, "Lire ensuite") { onClose(); LocalPlayer.playNext(ctx, listOf(t)); TidalRemote.showToast("Joué juste après") }
        MenuAction(Icons.AutoMirrored.Rounded.PlaylistAdd, "Ajouter à une playlist") { onPlaylist() }
        MenuAction(Icons.Rounded.Edit, "Modifier les infos") { onEdit() }
        MenuAction(Icons.Rounded.DeleteOutline, "Retirer de Hush", Ko) {
            onClose(); LocalMusic.remove(listOf(t.id)); TidalRemote.showToast("Retiré de Hush (le fichier reste sur ton téléphone)")
        }
    }
}

@Composable
private fun MenuAction(icon: ImageVector, label: String, color: Color = Txt, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).bounceClick(pressedScale = 0.97f, onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/** Corriger le titre et l'artiste d'un son local. */
@Composable
private fun EditInfoSheet(t: LocalMusic.Track, onClose: () -> Unit) {
    var title by remember(t.id) { mutableStateOf(t.title) }
    var artist by remember(t.id) { mutableStateOf(t.artist) }
    Sheet(onClose) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Modifier les infos", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            PillButton("OK") { LocalMusic.edit(t.id, title.trim(), artist.trim()); TidalRemote.showToast("Infos enregistrées"); onClose() }
        }
        Spacer(Modifier.height(Space.s))
        Text("Titre", color = Txt2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        LocalField(title, "Titre") { title = it }
        Spacer(Modifier.height(Space.xs))
        Text("Artiste", color = Txt2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        LocalField(artist, "Artiste") { artist = it }
        Spacer(Modifier.height(Space.s))
    }
}

@Composable
private fun LocalField(value: String, hint: String, onChange: (String) -> Unit) {
    val shape = Rounded(14)
    Box(Modifier.fillMaxWidth().height(48.dp).glass(shape, fill = 0.10f, edge = 0.18f).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text(hint, color = Txt3, fontSize = 15.sp)
        androidx.compose.foundation.text.BasicTextField(
            value = value, onValueChange = { onChange(it.take(120)) }, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Txt, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Txt),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------- petites briques

@Composable
private fun Sheet(onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).bounceClick(pressedScale = 1f) { onClose() }) {
        val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(shape).background(Color(0xFF15151C))
                .glass(shape, fill = 0.06f, edge = 0.12f).bounceClick(pressedScale = 1f) { }
                .navigationBarsPadding().padding(Space.gutter),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 40.dp, height = 4.dp).clip(PillShape).background(Txt3))
            content()
        }
    }
}

@Composable
private fun LocalRow(
    t: LocalMusic.Track,
    playing: Boolean,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color.White.copy(alpha = 0.10f) else if (playing) Color.White.copy(alpha = 0.06f) else Color.Transparent)
            .bounceClick(pressedScale = 0.97f, onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))) {
            LocalCover(t, 48)
            if (playing) PlayingOverlay(TidalRemote.state.collectAsState().value.isPlaying, Modifier.matchParentSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, color = Txt, fontSize = 15.sp, fontWeight = if (playing) FontWeight.ExtraBold else FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                t.artist + if (t.durationMs > 0) " · " + fmt(t.durationMs) else "",
                color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

/** Un fichier local dans une playlist Tidal (écran de playlist). */
@Composable
fun AttachedLocalRow(t: LocalMusic.Track, playing: Boolean, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    LocalRow(t, playing = playing, onClick = onClick, trailing = {
        Text("Local", color = Ink, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Txt2).padding(horizontal = 4.dp, vertical = 1.dp))
        Spacer(Modifier.width(6.dp))
        trailing()
    })
}

@Composable
private fun LocalCover(t: LocalMusic.Track?, size: Int) {
    Box(contentAlignment = Alignment.Center) {
        val art = t?.art
        if (art != null) Cover(model = File(art), radius = 10.dp, modifier = Modifier.size(size.dp))
        else Box(
            Modifier.size(size.dp).clip(RoundedCornerShape(12.dp))
                .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF3B3F55), Color(0xFF1C1E2A))))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
        )
        if (art == null) Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Txt2, modifier = Modifier.size((size / 2).dp))
    }
}

@Composable
private fun CheckDot(on: Boolean) {
    Box(
        Modifier.size(24.dp).clip(PillShape)
            .background(if (on) Color.White else Color.Transparent)
            .border(1.5.dp, if (on) Color.White else Txt3, PillShape),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun LocalPill(icon: ImageVector, label: String, filled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = BtnShape
    Row(
        modifier.height(46.dp).clip(shape)
            .then(if (filled) Modifier.background(CoverTint.current() ?: Txt) else Modifier.glass(shape, fill = 0.10f, edge = 0.16f))
            .bounceClick(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (filled) Ink else Txt, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (filled) Ink else Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun TextAction(label: String, color: Color = Txt, onClick: () -> Unit) {
    Text(
        label, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).bounceClick(pressedScale = 0.96f, onClick = onClick).padding(vertical = 6.dp),
    )
}

@Composable
private fun LocalSearch(value: String, onChange: (String) -> Unit) {
    val shape = Rounded(14)
    Box(Modifier.fillMaxWidth().height(44.dp).glass(shape, fill = 0.08f, edge = 0.14f).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text("Chercher un son ou un artiste", color = Txt3, fontSize = 13.sp)
        androidx.compose.foundation.text.BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Txt, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Txt),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
