package com.theo.remote.ui

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History as HistoryIcon
import androidx.compose.runtime.collectAsState
import com.theo.remote.History
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.TidalApi
import com.theo.remote.TidalRemote
import kotlinx.coroutines.launch

// gardés en mémoire quand on change d'onglet
private var lastResults: List<TidalApi.Result> = emptyList()
private var lastArtists: List<TidalApi.Artist> = emptyList()
private var lastLyrics: List<com.theo.remote.Lyrics.Match> = emptyList()

/** Dernières recherches gardées en mémoire : revenir en arrière (effacer une lettre) est instantané. */
private val searchCache = object : LinkedHashMap<String, TidalApi.SearchAll>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TidalApi.SearchAll>?) = size > 30
}

/** Oublie la dernière recherche : en revenant sur l'onglet, on retombe sur l'historique. */
internal fun resetSearch() { lastResults = emptyList(); lastArtists = emptyList(); lastLyrics = emptyList() }

@Composable
fun SearchScreen(s: TidalRemote.State) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf(lastResults) }
    var artists by remember { mutableStateOf(lastArtists) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(lastResults.isNotEmpty()) }
    var lyricHits by remember { mutableStateOf(lastLyrics) }
    var lyricLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val pastSearches by History.searches.collectAsState()
    val pastTracks by History.searchTracks.collectAsState()

    fun playFromSearch(r: TidalApi.Result) {
        History.addSearchTrack(r)
        TidalRemote.play(r)
    }

    var autoPlay by remember { mutableStateOf(false) }
    var filterKind by rememberSaveable { mutableStateOf(0) } // 0 tout, 1 sons, 2 local
    val localAll by com.theo.remote.LocalMusic.tracks.collectAsState()
    val localHits = remember(localAll, query, searched) {
        val q = query.trim()
        if (q.length < 2 || !searched) emptyList()
        else localAll.filter { it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true) }.take(20)
    }
    val nowLocal by com.theo.remote.LocalPlayer.now.collectAsState()
    val smart = com.theo.remote.Features.flags.collectAsState().value.smartSearch
    val heard by History.entries.collectAsState()
    // tes sons déjà écoutés : affichés dès la première lettre, sans attendre Internet (fautes tolérées)
    val instant = remember(query, heard, smart) {
        val q = query.trim()
        if (!smart || q.isEmpty()) emptyList()
        else heard.asSequence()
            .map { it to com.theo.remote.Fuzzy.score(q, it.title + " " + it.artist) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<History.Entry, Int>> { it.second }.thenByDescending { it.first.plays.size })
            .take(4).map { it.first }.toList()
    }
    /** « drak » → on a cherché « drake » à la place (et on le dit). */
    var correctedFrom by remember { mutableStateOf<String?>(null) }
    var correctedTo by remember { mutableStateOf<String?>(null) }

    var searchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var lastRun by remember { mutableStateOf("") }
    /** live = lancée toute seule pendant la frappe : on garde le clavier et on n'encombre pas l'historique. */
    fun run(live: Boolean = false) {
        val q = query.trim()
        if (q.isEmpty() || (loading && !live)) return
        if (live && q == lastRun) return
        lastRun = q
        if (!live) {
            keyboard?.hide()
            History.addSearch(q)
        }
        // pendant la frappe on laisse les anciens résultats affichés (pas de clignotement)
        loading = !live || results.isEmpty()
        error = null
        lyricHits = emptyList()
        lastLyrics = emptyList()
        // une phrase (3 mots ou plus) : on cherche aussi dans les paroles (seulement quand tu valides)
        if (!live && q.split(Regex("\\s+")).size >= 3) {
            lyricLoading = true
            scope.launch {
                lyricHits = runCatching { com.theo.remote.Lyrics.searchByLyrics(q) }.getOrDefault(emptyList())
                lastLyrics = lyricHits
                lyricLoading = false
            }
        }
        searchJob?.cancel()
        correctedFrom = null; correctedTo = null
        searchCache[q.lowercase()]?.let { hit ->
            results = hit.tracks; artists = hit.artists
            lastResults = results; lastArtists = artists
            searched = true; loading = false
            if (autoPlay) { results.firstOrNull()?.let { playFromSearch(it) }; autoPlay = false }
            return
        }
        searchJob = scope.launch {
            try {
                var all = TidalApi.searchAll(q)
                if (query.trim() != q) { loading = false; return@launch } // tu as continué à taper : résultat périmé
                // faute de frappe sur un nom que tu écoutes (« drak ») : on cherche le bon nom
                if (smart) {
                    val vocab = heard.asSequence().flatMap { sequenceOf(it.artist, it.title) }.take(4000).toList()
                    val fixed = com.theo.remote.Fuzzy.correct(q, vocab)
                    val exactArtist = all.artists.any { com.theo.remote.Fuzzy.norm(it.name) == com.theo.remote.Fuzzy.norm(q) }
                    if (fixed != null && !exactArtist) {
                        val better = runCatching { TidalApi.searchAll(fixed) }.getOrNull()
                        if (query.trim() != q) { loading = false; return@launch }
                        if (better != null && (better.tracks.isNotEmpty() || better.artists.isNotEmpty())) {
                            all = better; correctedFrom = q; correctedTo = fixed
                        }
                    }
                }
                searchCache[q.lowercase()] = all
                results = all.tracks
                artists = all.artists
                lastResults = results
                lastArtists = artists
                // « mets du Jok'Air » à la voix : on lance directement le meilleur résultat
                if (autoPlay) results.firstOrNull()?.let { playFromSearch(it) }
                autoPlay = false
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Erreur réseau"
                results = emptyList()
                artists = emptyList()
            }
            searched = true
            loading = false
        }
    }

    // résultats pendant que tu tapes (option des Réglages) : on attend une petite pause dans la frappe
    val liveOn = com.theo.remote.Features.flags.collectAsState().value.liveSearch
    LaunchedEffect(query, liveOn) {
        if (!liveOn) return@LaunchedEffect
        if (query.trim().length < 2) return@LaunchedEffect
        kotlinx.coroutines.delay(150)
        run(live = true)
    }

    // recherche vocale (option activable dans les Réglages)
    val features by com.theo.remote.Features.flags.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val voice = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        val said = res.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
        if (said.isNotBlank()) {
            val (q, play) = parseVoice(said)
            query = q
            autoPlay = play
            run()
        }
    }
    fun listen() {
        val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Dis un son ou un artiste… (« mets du Jok'Air »)")
        runCatching { voice.launch(i) }.onFailure { TidalRemote.showToast("Pas de reconnaissance vocale sur ce téléphone") }
    }

    val searchList = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(Unit) { NavEvents.reselect.collect { if (it == "Search") searchList.animateScrollToItem(0) } }
    // appui long sur l'onglet Recherche : on écoute direct
    LaunchedEffect(Unit) { NavEvents.voice.collect { if (features.voice) listen() } }
    val scrolledPast by androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { searchList.firstVisibleItemIndex > 0 || searchList.firstVisibleItemScrollOffset > 140 } }
    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        state = searchList,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 190.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text("Recherche", color = Txt, fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.6).sp)
            Spacer(Modifier.height(16.dp))
            SearchField(query, { query = it }, onSearch = { run() }, onMic = if (features.voice) ({ listen() }) else null, onClear = {
                query = ""
                results = emptyList()
                lastResults = emptyList()
                lyricHits = emptyList()
                lastLyrics = emptyList()
                searched = false
            })
            Spacer(Modifier.height(16.dp))
            if (searched && (results.isNotEmpty() || localHits.isNotEmpty())) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Tout", "Sons", "Local").forEachIndexed { i, label ->
                        if (i == 2 && localAll.isEmpty()) return@forEachIndexed
                        val on = filterKind == i
                        val shape = BtnShape
                        Box(
                            Modifier.clip(shape).then(if (on) Modifier.background(Txt) else Modifier.glass(shape, fill = 0.08f, edge = 0.16f))
                                .bounceClick { filterKind = i }.padding(horizontal = 16.dp, vertical = 8.dp),
                        ) { Text(label + if (i == 2 && localHits.isNotEmpty()) " (${localHits.size})" else "", color = if (on) Ink else Txt, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold) }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        // historique : visible tant qu'aucune recherche n'est affichée
        val showHistory = !loading && results.isEmpty() && !searched && error == null && instant.isEmpty()
        if (showHistory && (pastSearches.isNotEmpty() || pastTracks.isNotEmpty())) {
            item(key = "histHeader") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Récents", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                    Text(
                        "Tout effacer",
                        color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).bounceClick(pressedScale = 0.95f) { History.clearSearchHistory() }
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            // derniers sons cherchés : une rangée de pochettes à toucher (appui long = retirer)
            if (pastTracks.isNotEmpty()) {
                item(key = "histCovers") {
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(pastTracks, key = { "hc-" + it.id }) { r ->
                            RecentCover(r, playing = isCurrent(s, r), modifier = Modifier.animateItem()) { playFromSearch(r) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            } else if (pastSearches.isNotEmpty()) {
                // pas encore de son lancé depuis la recherche : on garde tes derniers mots tapés
                item(key = "histQueries") {
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(pastSearches, key = { it }) { q ->
                            Row(
                                Modifier
                                    .animateItem()
                                    .glass(PillShape, fill = 0.08f, edge = 0.14f)
                                    .bounceClick(pressedScale = 0.95f) { query = q; run() }
                                    .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.HistoryIcon, contentDescription = null, tint = Txt2, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(q, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Box(
                                    Modifier.size(28.dp).clip(PillShape).bounceClick(pressedScale = 0.8f) { History.removeSearch(q) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Retirer « $q »", tint = Txt2, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        // déjà écoutés : tout de suite, avant même la réponse de Tidal
        if (instant.isNotEmpty() && filterKind != 2) {
            item(key = "instantHead") { SearchLabel("Déjà écoutés") }
            items(instant, key = { "in-" + it.key }) { e -> HeardRow(e, Modifier.animateItem()) }
            item(key = "instantGap") { Spacer(Modifier.height(12.dp)) }
        }
        // recherche corrigée
        if (correctedTo != null && !loading) item(key = "corrected") {
            val from = correctedFrom
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Text("Résultats pour « $correctedTo »", color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                if (from != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Chercher « $from »", color = Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).bounceClick(pressedScale = 0.95f) {
                            correctedTo = null; correctedFrom = null
                            searchJob?.cancel()
                            loading = true
                            searchJob = scope.launch {
                                runCatching { TidalApi.searchAll(from) }.onSuccess { r ->
                                    results = r.tracks; artists = r.artists; lastResults = results; lastArtists = artists
                                }
                                loading = false
                            }
                        }.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
            }
        }
        when {
            loading && instant.isEmpty() -> item { SkeletonList(7) }
            loading -> item { SkeletonList(3) }
            error != null -> item { Text(error!!, color = Ko, fontSize = 13.sp, lineHeight = 18.sp) }
            searched && results.isEmpty() && artists.isEmpty() && lyricHits.isEmpty() && localHits.isEmpty() && !lyricLoading -> item {
                EmptyState("Aucun résultat", "Essaie un autre titre, un artiste, ou une phrase des paroles.")
            }
        }

        // trouvé grâce aux paroles
        // tes fichiers locaux qui correspondent
        if (!loading && localHits.isNotEmpty() && filterKind != 1) {
            item(key = "localHead") { SearchLabel("Sur ton téléphone") }
            itemsIndexed(localHits, key = { _, t -> "loc-" + t.id }) { i, t ->
                AttachedLocalRow(t, playing = nowLocal == t.id, onClick = { com.theo.remote.LocalPlayer.play(ctx, localHits, i) }) {
                    Text(fmt(t.durationMs), color = Txt2, fontSize = 12.sp)
                }
            }
            item(key = "localGap") { Spacer(Modifier.height(12.dp)) }
        }

        if (filterKind != 2 && !loading && (lyricHits.isNotEmpty() || lyricLoading)) {
            item(key = "lyricsHeader") {
                Spacer(Modifier.height(if (results.isEmpty()) 0.dp else 4.dp))
                Text("Dans les paroles", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(6.dp))
                if (lyricLoading) SkeletonList(2)
            }
            items(lyricHits, key = { "ly-" + it.title + it.artist }) { m ->
                LyricHit(m, Modifier.animateItem()) { TidalRemote.playTitle(m.title, m.artist) }
            }
            item(key = "lyricsGap") { Spacer(Modifier.height(16.dp)) }
        }

        if (filterKind != 2 && !loading && (results.isNotEmpty() || artists.isNotEmpty())) {
            // meilleur résultat : l'artiste si c'est lui que tu as tapé, sinon le premier titre
            val q = query.trim()
            fun nz(x: String) = x.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
            val topArtist = artists.firstOrNull()
            val artistFirst = topArtist != null && (nz(topArtist.name) == nz(q) || results.isEmpty() ||
                (nz(q).contains(nz(topArtist.name)) && results.none { nz(q).contains(nz(it.title)) }))
            val best = results.firstOrNull()
            item {
                if (filterKind == 0) {
                    SearchLabel("Meilleur résultat")
                    if (artistFirst && topArtist != null) BestArtistCard(topArtist)
                    else if (best != null) BestTrackCard(best, playing = isCurrent(s, best)) { playFromSearch(best) }
                    Spacer(Modifier.height(20.dp))
                    val more = if (artistFirst) artists.drop(1) else artists
                    if (more.isNotEmpty()) {
                        SearchLabel("Artistes")
                        ArtistCards(more)
                        Spacer(Modifier.height(20.dp))
                    }
                }
                if (results.isNotEmpty()) SearchLabel("Titres")
                if (results.isNotEmpty()) HintBubble("longPressSong", "Appui long sur un son pour plus d'options. Glisse-le vers la gauche pour l'ajouter à la file.")
            }
            itemsIndexed((if (filterKind == 0 && !artistFirst) results.drop(1) else results).distinctBy { it.id }, key = { _, r -> r.id }) { i, r ->
                TrackRow(r, playing = isCurrent(s, r), modifier = Modifier.animateItem().cascadeIn(i, "sr" + r.id)) { playFromSearch(r) }
            }
        }
    }
    }
}

/** Un son de ton historique, dans les résultats. */
@Composable
private fun HeardRow(e: History.Entry, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .bounceClick(onLongClick = { TrackMenu.open(TrackMenu.Target(e.title, e.artist, e.coverUrl ?: e.artPath?.let { java.io.File(it) }, album = e.album)) }) {
                TidalRemote.playTitle(e.title, e.artist)
            }
            .padding(horizontal = 6.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(model = e.coverUrl ?: e.artPath?.let { java.io.File(it) }, radius = 10.dp, modifier = Modifier.size(50.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(e.artist, color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (e.plays.size > 1) Text("${e.plays.size} écoutes", color = Txt2, fontSize = 12.sp)
    }
}

@Composable
private fun SearchLabel(text: String) {
    Text(text, color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
}

/** « mets du Jok'Air » → ("Jok'Air", lancer direct) ; « Jok'Air » → ("Jok'Air", juste chercher). */
private fun parseVoice(said: String): Pair<String, Boolean> {
    val prefixes = listOf(
        "mets-moi ", "mets moi ", "met moi ", "mets ", "met ", "lance ", "joue ", "fais-moi écouter ", "fais moi écouter ",
        "je veux écouter ", "j'ai envie d'écouter ", "écouter ",
    )
    val low = said.lowercase()
    val p = prefixes.firstOrNull { low.startsWith(it) } ?: return said to false
    var rest = said.substring(p.length).trim()
    // « du », « de la », « le son »… ne servent à rien pour la recherche
    listOf("du son ", "le son ", "la chanson ", "du ", "de la ", "des ", "de ", "le ", "la ", "les ").firstOrNull { rest.lowercase().startsWith(it) }
        ?.let { rest = rest.substring(it.length) }
    return rest.trim().ifBlank { said } to true
}

private fun isCurrent(s: TidalRemote.State, r: TidalApi.Result) =
    s.launch?.trackId == r.id && s.launch.status != TidalRemote.LaunchStatus.NoChange

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, onSearch: () -> Unit, onMic: (() -> Unit)? = null, onClear: () -> Unit) {
    val shape = PillShape
    Row(
        Modifier.fillMaxWidth().height(54.dp).shadow(14.dp, shape).glass(shape, fill = 0.11f, edge = 0.22f).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = Txt, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Titres, artistes, paroles…", color = Color(0xFF8B93A3), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = Txt, fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                cursorBrush = SolidColor(Txt),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isEmpty() && onMic != null) {
            Box(
                Modifier.size(36.dp).clip(PillShape).bounceClick(pressedScale = 0.85f, onClick = onMic),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Mic, contentDescription = "Recherche vocale", tint = Txt, modifier = Modifier.size(22.dp))
            }
        }
        if (value.isNotEmpty()) {
            Box(
                Modifier.size(32.dp).clip(PillShape).background(Color.White.copy(alpha = 0.14f)).bounceClick(onClick = onClear),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "Effacer", tint = Txt, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun BestResult(r: TidalApi.Result, playing: Boolean, onClick: () -> Unit) {
    val shape = Rounded(24)
    Row(
        Modifier.fillMaxWidth().shadow(16.dp, shape).clip(shape)
            .background(flatOr(listOf(androidx.compose.ui.graphics.lerp(Ok, Color.Black, 0.45f).copy(alpha = 0.85f), Color.White.copy(alpha = 0.05f))))
            .glare().glass(shape, fill = 0.04f, edge = 0.18f)
            .bounceClick(pressedScale = 0.98f, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(84.dp).shadow(14.dp, Rounded(16)).clip(Rounded(16))) {
            Cover(model = r.coverUrl, radius = 16.dp, modifier = Modifier.fillMaxSize().glare())
            if (playing) PlayingOverlay(true, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(r.fullTitle, color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 24.sp)
            Spacer(Modifier.height(2.dp))
            Text("Titre · ${r.artistLine}", color = Txt.copy(alpha = 0.75f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AddToQueueButton(r)
        Spacer(Modifier.width(2.dp))
        Box(
            Modifier.size(50.dp).shadow(10.dp, PillShape).clip(PillShape).background(Txt),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = "Lire", tint = Ink, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
fun TrackRow(r: TidalApi.Result, playing: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val openDetail = LocalOpenDetail.current
    SwipeToQueue(r, modifier) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (playing) Color.White.copy(alpha = 0.08f) else Color.Transparent)
            .bounceClick(onLongClick = { TrackMenu.open(r) }, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(50.dp).shadow(8.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))) {
            Cover(model = r.coverUrl, radius = 10.dp, modifier = Modifier.fillMaxSize())
            if (playing) PlayingOverlay(true, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                r.fullTitle,
                color = Txt, fontSize = 15.sp, fontWeight = if (playing) FontWeight.ExtraBold else FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (r.explicit) {
                    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Txt2).padding(horizontal = 4.dp)) {
                        Text("E", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Spacer(Modifier.width(6.dp))
                }
                // toucher l'artiste ouvre sa page
                Text(
                    listOfNotNull(r.artistLine.ifBlank { null }, r.album).joinToString(" · "),
                    color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).bounceClick(pressedScale = 0.97f) {
                        Detail.artistOf(r)?.let(openDetail)
                    },
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        RatingBadge(r.title, r.artistLine)
        Text(fmt(r.durationMs), color = Txt2, fontSize = 12.sp)
        AddToQueueButton(r)
    }
    }
}

/** Glisser un son vers la gauche l'ajoute à la file « À suivre ». */
@Composable
fun SwipeToQueue(r: TidalApi.Result, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    if (!enabled) {
        Box(modifier) { content() }
        return
    }
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    var width by remember { androidx.compose.runtime.mutableIntStateOf(1) }
    var armed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptic(strong = true)
    val threshold = width * 0.25f
    val reveal = (-offset.value / threshold).coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().onSizeChanged { width = it.width.coerceAtLeast(1) }) {
        if (offset.value < -1f) {
            Row(
                Modifier.matchParentSize().clip(RoundedCornerShape(12.dp)).background(Ok.copy(alpha = 0.15f + 0.65f * reveal)).padding(end = 18.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (armed) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null,
                    tint = if (armed) Ink else Txt, modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("À suivre", color = if (armed) Ink else Txt, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        Box(
            Modifier
                .graphicsLayer { translationX = offset.value }
                .pointerInput(r.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (-offset.value > threshold) TidalRemote.addToQueue(r)
                                armed = false
                                offset.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 500f))
                            }
                        },
                        onDragCancel = { armed = false; scope.launch { offset.animateTo(0f) } },
                    ) { ch, d ->
                        val nv = (offset.value + d).coerceIn(-width * 0.45f, 0f)
                        if (nv != offset.value) ch.consume()
                        scope.launch { offset.snapTo(nv) }
                        val a = -nv > threshold
                        if (a != armed) { armed = a; if (a) haptic() }
                    }
                },
        ) { content() }
    }
}

/** Petit bouton « ajouter à la file » à côté d'un son. */
@Composable
fun AddToQueueButton(r: TidalApi.Result) {
    var added by remember { mutableStateOf(false) }
    val haptic = rememberHaptic(strong = true)
    androidx.compose.runtime.LaunchedEffect(added) {
        if (added) { kotlinx.coroutines.delay(1400); added = false }
    }
    Box(
        Modifier.size(44.dp).clip(PillShape).bounceClick(
            pressedScale = 0.8f,
            // appui long : ajouter à une playlist Tidal
            onLongClick = { haptic(); PlaylistEdit.pick(listOf(r)) },
        ) {
            haptic()
            TidalRemote.addToQueue(r)
            added = true
        },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.animation.Crossfade(added, label = "queued") { done ->
            Icon(
                if (done) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.PlaylistAdd,
                contentDescription = "Ajouter à la file d'attente",
                tint = if (done) Ok else Txt2,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Un son retrouvé par ses paroles, avec la ligne qui correspond. */
@Composable
private fun LyricHit(m: com.theo.remote.Lyrics.Match, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = Rounded(16)
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .glass(shape, fill = 0.06f, edge = 0.10f)
            .bounceClick(pressedScale = 0.97f, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("« ${m.line} »", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Txt2, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("${m.title} · ${m.artist}", color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}


// ---------------------------------------------------------------- meilleur résultat en carte

/** Le titre le plus pertinent, en grande carte. */
@Composable
private fun BestTrackCard(r: TidalApi.Result, playing: Boolean, onClick: () -> Unit) {
    val shape = Rounded(24)
    val cc = rememberUrlCoverColors(r.coverUrl)
    Box(
        Modifier.fillMaxWidth().shadow(20.dp, shape, ambientColor = cc.a, spotColor = cc.a).clip(shape)
            .background(Brush.linearGradient(listOf(androidx.compose.ui.graphics.lerp(cc.a, Color.Black, 0.35f), androidx.compose.ui.graphics.lerp(cc.b, Color.Black, 0.6f))))
            .bounceClick(pressedScale = 0.98f, onClick = onClick).padding(18.dp),
    ) {
        Column {
            Box(Modifier.size(132.dp).shadow(18.dp, Rounded(14)).clip(Rounded(14))) {
                Cover(model = r.coverUrl, radius = 12.dp, modifier = Modifier.fillMaxSize())
                if (playing) PlayingOverlay(true, Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(16.dp))
            Text(r.fullTitle, color = Txt, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 30.sp, modifier = Modifier.padding(end = 64.dp))
            Spacer(Modifier.height(4.dp))
            Text("Titre · ${r.artistLine}", color = Color.White.copy(alpha = 0.72f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 64.dp))
        }
        Row(Modifier.align(Alignment.BottomEnd), verticalAlignment = Alignment.CenterVertically) {
            AddToQueueButton(r)
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier.size(52.dp).shadow(10.dp, PillShape).clip(PillShape).background(Color.White),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.PlayArrow, contentDescription = "Lire", tint = Color.Black, modifier = Modifier.size(28.dp)) }
        }
    }
}

/** L'artiste le plus pertinent, en grande carte : photo ronde, toucher = sa page, bouton = lancer ses sons. */
@Composable
private fun BestArtistCard(a: TidalApi.Artist) {
    val open = LocalOpenDetail.current
    val shape = Rounded(24)
    val cc = rememberUrlCoverColors(a.pictureUrl)
    Box(
        Modifier.fillMaxWidth().shadow(20.dp, shape, ambientColor = cc.a, spotColor = cc.a).clip(shape)
            .background(Brush.linearGradient(listOf(androidx.compose.ui.graphics.lerp(cc.a, Color.Black, 0.35f), androidx.compose.ui.graphics.lerp(cc.b, Color.Black, 0.6f))))
            .bounceClick(pressedScale = 0.98f) { open(Detail.ArtistRef(a)) }.padding(18.dp),
    ) {
        Column {
            Cover(model = a.pictureUrl, radius = 66.dp, modifier = Modifier.size(132.dp).shadow(18.dp, RoundedCornerShape(66.dp)))
            Spacer(Modifier.height(16.dp))
            Text(a.name, color = Txt, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 30.sp, modifier = Modifier.padding(end = 64.dp))
            Spacer(Modifier.height(4.dp))
            Text("Artiste", color = Color.White.copy(alpha = 0.72f), fontSize = 15.sp)
        }
        Box(
            Modifier.align(Alignment.BottomEnd).size(52.dp).shadow(10.dp, PillShape).clip(PillShape).background(Color.White)
                .bounceClick { TidalRemote.playArtist(a.name) },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.PlayArrow, contentDescription = "Lancer ${a.name}", tint = Color.Black, modifier = Modifier.size(28.dp)) }
    }
}

/** Les autres artistes trouvés, en cartes qui défilent. */
@Composable
private fun ArtistCards(list: List<TidalApi.Artist>) {
    val open = LocalOpenDetail.current
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(list.size) { i ->
            val a = list[i]
            Column(
                Modifier.width(118.dp).clip(Rounded(16))
                    .bounceClick(pressedScale = 0.96f) { open(Detail.ArtistRef(a)) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Cover(model = a.pictureUrl, radius = 50.dp, modifier = Modifier.size(100.dp).shadow(14.dp, Rounded(50)))
                Spacer(Modifier.height(12.dp))
                Text(a.name, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Artiste", color = Txt2, fontSize = 12.sp)
            }
        }
    }
}

/** Un son cherché récemment : grande pochette en relief + titre. Appui long : le retirer de l'historique. */
@Composable
private fun RecentCover(r: TidalApi.Result, playing: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val glow = rememberUrlCoverColors(r.coverUrl).a
    val haptic = rememberHaptic(strong = true)
    Column(
        modifier.width(120.dp).bounceClick(pressedScale = 0.94f, onLongClick = { haptic(); History.removeSearchTrack(r.id) }, onClick = onClick),
    ) {
        Box(Modifier.size(120.dp).shadow(14.dp, Rounded(16), ambientColor = glow, spotColor = glow).clip(Rounded(16))) {
            Cover(model = r.coverUrl, radius = 16.dp, modifier = Modifier.fillMaxSize())
            if (playing) PlayingOverlay(true, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(8.dp))
        Text(r.fullTitle, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(r.artistLine, color = Txt2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
