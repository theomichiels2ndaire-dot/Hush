package com.theo.remote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.foundation.border
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.TidalApi
import com.theo.remote.TidalAuth
import com.theo.remote.TidalRemote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Ajout de sons à tes playlists Tidal, depuis n'importe où dans Hush. */
object PlaylistEdit {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _picking = MutableStateFlow<List<TidalApi.Result>?>(null)
    val picking: StateFlow<List<TidalApi.Result>?> = _picking

    fun pick(tracks: List<TidalApi.Result>) { if (tracks.isNotEmpty()) _picking.value = tracks }
    fun close() { _picking.value = null }

    /** Le son en cours n'a pas d'identifiant Tidal : on le retrouve dans le catalogue. */
    fun pickCurrent(title: String, artist: String) {
        scope.launch {
            val found = runCatching { TidalApi.search("$title ${artist.split(",").first()}") }.getOrNull()
            fun n(x: String) = x.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
            val r = found?.firstOrNull { n(it.title) == n(title) } ?: found?.firstOrNull()
            if (r == null) TidalRemote.showToast("Son introuvable dans le catalogue") else pick(listOf(r))
        }
    }

    /** Tout le contenu d'une playlist (cache si complet, sinon on charge toutes les pages). */
    private suspend fun contents(id: String): List<TidalApi.Result> {
        cachedTracks[id]?.let { if (cachedNext.containsKey(id) && cachedNext[id] == null) return it }
        var all = emptyList<TidalApi.Result>()
        var next: String? = null
        var guard = 0
        do {
            val page = runCatching { TidalApi.playlistPage(id, next) }.getOrNull() ?: break
            all = all + page.tracks; next = page.next; guard++
        } while (next != null && guard < 40)
        return all
    }

    private fun norm(x: String) = x.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    /** Les sons pas encore dans la playlist (anti-doublons). */
    private suspend fun withoutDuplicates(p: TidalApi.Playlist, tracks: List<TidalApi.Result>): List<TidalApi.Result> {
        if (!com.theo.remote.Features.flags.value.dupWarn) return tracks
        val have = runCatching { contents(p.id) }.getOrDefault(emptyList())
        if (have.isEmpty()) return tracks
        val ids = have.map { it.id }.toSet()
        val keys = have.map { norm(it.fullTitle) + "|" + norm(it.artistLine) }.toSet()
        return tracks.filter { it.id !in ids && (norm(it.fullTitle) + "|" + norm(it.artistLine)) !in keys }
    }

    fun addTo(p: TidalApi.Playlist, tracks0: List<TidalApi.Result>) {
        close()
        scope.launch {
            try {
                val tracks = withoutDuplicates(p, tracks0)
                if (tracks.isEmpty()) {
                    TidalRemote.showToast(if (tracks0.size == 1) "Déjà dans « ${p.name} »" else "Tous déjà dans « ${p.name} »")
                    return@launch
                }
                // réponse immédiate : on confirme tout de suite, Tidal suit derrière (message d'erreur si ça rate)
                val skipped = tracks0.size - tracks.size
                TidalRemote.showToast(
                    (if (tracks.size == 1) "Ajouté à « ${p.name} »" else "${tracks.size} sons ajoutés à « ${p.name} »") +
                        if (skipped > 0) " · $skipped déjà dedans" else ""
                )
                TidalApi.addToPlaylist(p.id, tracks.map { it.id })
                refresh(p.id)
            } catch (e: Exception) {
                TidalRemote.showToast("L'ajout à « ${p.name} » a échoué" + (e.message?.let { " : $it" } ?: ""))
            }
        }
    }

    /** Ajout d'un coup dans plusieurs playlists. */
    fun addToMany(ps: List<TidalApi.Playlist>, tracks: List<TidalApi.Result>) {
        if (ps.size == 1) return addTo(ps.first(), tracks)
        close()
        scope.launch {
            var ok = 0
            var dups = 0
            for (p in ps) {
                val add = withoutDuplicates(p, tracks)
                if (add.isEmpty()) { dups++; ok++; continue }
                runCatching { TidalApi.addToPlaylist(p.id, add.map { it.id }) }.onSuccess { ok++ }
                p.id.let { cachedTracks.remove(it); cachedNext.remove(it) }
            }
            TidalRemote.showToast((if (ok == ps.size) "Ajouté à $ok playlists" else "Ajouté à $ok playlists sur ${ps.size}") + if (dups > 0) " · déjà dans $dups" else "")
            refresh(null)
        }
    }

    fun createWith(name: String, tracks: List<TidalApi.Result>) {
        close()
        scope.launch {
            try {
                val p = TidalApi.createPlaylist(name)
                if (tracks.isNotEmpty()) TidalApi.addToPlaylist(p.id, tracks.map { it.id })
                TidalRemote.showToast("Playlist « $name » créée")
                refresh(p.id)
            } catch (e: Exception) {
                TidalRemote.showToast(e.message ?: "Échec de la création")
            }
        }
    }

    fun refreshLater(id: String?) { scope.launch { refresh(id) } }

    /** Après une modif : on oublie le cache de la playlist et on recharge la liste. */
    suspend fun refresh(id: String?) {
        id?.let { cachedTracks.remove(it); cachedNext.remove(it) }
        kotlinx.coroutines.delay(300) // Tidal met un instant à refléter le changement
        runCatching { PlaylistStore.load(force = true) }
    }
}

/** Panneau « Ajouter à une playlist ». */
@Composable
fun PlaylistPicker() {
    val tracks by PlaylistEdit.picking.collectAsState()
    val playlists by PlaylistStore.list.collectAsState()
    val canWrite by TidalAuth.canWrite.collectAsState()
    val loggedIn by TidalAuth.loggedIn.collectAsState()
    var last by remember { mutableStateOf<List<TidalApi.Result>>(emptyList()) }
    tracks?.let { last = it }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val localPls by com.theo.remote.LocalMusic.playlists.collectAsState()
    val localOn = com.theo.remote.Features.flags.collectAsState().value.localFiles
    LaunchedEffect(tracks) { selected = emptySet() }
    val ctx = LocalContext.current
    // les playlists où le son est déjà : cochées d'office (un seul son ajouté)
    var already by remember { mutableStateOf<Set<String>>(emptySet()) }
    var checking by remember { mutableStateOf(false) }
    LaunchedEffect(tracks) {
        already = emptySet()
        val one = tracks?.singleOrNull() ?: return@LaunchedEffect
        checking = true
        already = runCatching { WhereIsIt.find(ctx, WhereIsIt.Target(one.title, one.artistLine, one.id)) { _, _ -> } }
            .getOrDefault(emptyList()).map { it.id }.toSet()
        checking = false
    }

    androidx.activity.compose.BackHandler(enabled = tracks != null) { PlaylistEdit.close() }
    LaunchedEffect(tracks != null) { if (tracks != null && loggedIn) runCatching { PlaylistStore.load() } }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(tracks != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).bounceClick(pressedScale = 1f) { PlaylistEdit.close() })
        }
        AnimatedVisibility(
            tracks != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 340f)) { it } + fadeIn(),
            exit = slideOutVertically(tween(220)) { it } + fadeOut(),
        ) {
            // plaque de verre flottante (verre liquide si actif)
            val shape = Rounded(30)
            GlassPlate(shape, 30.dp, Modifier.navigationBarsPadding().imePadding().padding(10.dp).fillMaxWidth().heightIn(max = 560.dp)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(Space.gutter),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(width = 40.dp, height = 4.dp).clip(PillShape).background(Txt3))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ajouter à une playlist", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                    if (selected.isNotEmpty()) {
                        PillButton(if (selected.size == 1) "Ajouter" else "Ajouter (${selected.size})") {
                            val tidalPls = playlists.orEmpty().filter { it.id in selected }
                            val hushPls = localPls.filter { it.id in selected }.map { it.id }
                            if (hushPls.isNotEmpty()) {
                                com.theo.remote.LocalMusic.addTidal(hushPls, last)
                                if (tidalPls.isEmpty()) {
                                    TidalRemote.showToast(if (hushPls.size == 1) "Ajouté à la playlist Hush" else "Ajouté à ${hushPls.size} playlists Hush")
                                    PlaylistEdit.close()
                                }
                            }
                            if (tidalPls.isNotEmpty()) PlaylistEdit.addToMany(tidalPls, last)
                        }
                    }
                }
                Text(
                    (if (last.size == 1) last.first().fullTitle + " · " + last.first().artistLine else "${last.size} sons") +
                        if (checking) " · je regarde où il est déjà…" else "",
                    color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Space.xs))
                when {
                    !loggedIn -> {
                        Text("Connecte ton compte Tidal (onglet Biblio) pour gérer tes playlists.", color = Txt2, fontSize = 14.sp)
                    }
                    !canWrite -> {
                        Text(
                            "Hush doit avoir le droit de modifier tes playlists. Reconnecte ton compte une fois pour l'autoriser.",
                            color = Txt2, fontSize = 14.sp, lineHeight = 20.sp,
                        )
                        PillButton("Reconnecter Tidal") { PlaylistEdit.close(); TidalAuth.logout(); PlaylistStore.clear(); TidalAuth.login(ctx) }
                    }
                    else -> {
                        NewPlaylistRow { name -> PlaylistEdit.createWith(name, last) }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (localOn && localPls.isNotEmpty()) {
                                item(key = "hushHead") { Text("Playlists Hush", color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) }
                                items(localPls, key = { "h" + it.id }) { lp ->
                                    val on = lp.id in selected
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).bounceClick(pressedScale = 0.97f) { selected = if (on) selected - lp.id else selected + lp.id }
                                            .padding(vertical = 6.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                                            Icon(androidx.compose.material.icons.Icons.Rounded.LibraryMusic, contentDescription = null, tint = Txt2, modifier = Modifier.size(24.dp))
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(lp.name, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("Playlist Hush · ${lp.ids.size} son${if (lp.ids.size > 1) "s" else ""}", color = Txt2, fontSize = 12.sp)
                                        }
                                        Box(
                                            Modifier.size(24.dp).clip(PillShape).background(if (on) Color.White else Color.Transparent)
                                                .border(1.5.dp, if (on) Color.White else Txt3, PillShape),
                                            contentAlignment = Alignment.Center,
                                        ) { if (on) Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp)) }
                                    }
                                }
                                item(key = "tidalHead") { Text("Playlists Tidal", color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) }
                            }
                            // celles où le son est déjà passent en premier
                            items(playlists.orEmpty().sortedByDescending { it.id in already }, key = { it.id }) { p ->
                                val isIn = p.id in already
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).bounceClick(pressedScale = 0.97f) {
                                        if (isIn) TidalRemote.showToast("Déjà dans « ${p.name} »")
                                        else selected = if (p.id in selected) selected - p.id else selected + p.id
                                    }
                                        .padding(vertical = 6.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    PlaylistThumb(p)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(p.name, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(if (isIn) "Déjà dedans" else "${p.count} titre${if (p.count > 1) "s" else ""}", color = if (isIn) Ok else Txt2, fontSize = 12.sp, fontWeight = if (isIn) FontWeight.Bold else FontWeight.Normal)
                                    }
                                    val on = p.id in selected || isIn
                                    Box(
                                        Modifier.size(24.dp).clip(PillShape)
                                            .background(if (on) Color.White else Color.Transparent)
                                            .border(1.5.dp, if (on) Color.White else Txt3, PillShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (on) Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

/** « + Nouvelle playlist », qui se transforme en champ de saisie. */
@Composable
fun NewPlaylistRow(dashed: Boolean = false, onCreate: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val shape = Rounded(14)
    if (!editing) {
        Row(
            Modifier.fillMaxWidth().then(if (dashed) Modifier.dashedBorder(Color.White.copy(alpha = 0.22f), 16.dp) else Modifier)
                .clip(RoundedCornerShape(if (dashed) 16.dp else 12.dp)).bounceClick(pressedScale = 0.97f) { editing = true }
                .padding(vertical = if (dashed) 8.dp else 6.dp, horizontal = if (dashed) 8.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(48.dp).glass(RoundedCornerShape(8.dp), fill = 0.10f, edge = 0.16f), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Add, contentDescription = null, tint = Txt, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text("Nouvelle playlist", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    } else {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Box(Modifier.weight(1f).height(48.dp).glass(shape, fill = 0.10f, edge = 0.18f).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                if (name.isEmpty()) Text("Nom de la playlist", color = Txt3, fontSize = 15.sp)
                BasicTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    singleLine = true,
                    textStyle = TextStyle(color = Txt, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    cursorBrush = SolidColor(Txt),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) { onCreate(name.trim()); editing = false; name = "" } }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            PillButton("Créer") { if (name.isNotBlank()) { onCreate(name.trim()); editing = false; name = "" } }
        }
    }
}
