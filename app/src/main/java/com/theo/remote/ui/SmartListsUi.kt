package com.theo.remote.ui

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.History
import com.theo.remote.LastFm
import com.theo.remote.TidalAuth
import com.theo.remote.TidalRemote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private val SmartColors = listOf(
    listOf(Color(0xFF5B3CF0), Color(0xFFE0458B)),
    listOf(Color(0xFF0E8C7F), Color(0xFF2F5FE0)),
    listOf(Color(0xFFE0452F), Color(0xFFF0A024)),
    listOf(Color(0xFF14524A), Color(0xFF5BE3A2)),
    listOf(Color(0xFF8C1A4A), Color(0xFFF08A24)),
    listOf(Color(0xFF1B2B8C), Color(0xFF7A2FE0)),
)

/** Quelle playlist intelligente est ouverte (et si on la modifie). */
object SmartUi {
    val open = MutableStateFlow<SmartLists.Smart?>(null)
    val editing = MutableStateFlow(false)
    fun show(s: SmartLists.Smart, edit: Boolean = false) {
        open.value = s; editing.value = edit
        Panels.show(Panels.Kind.Smart)
    }
}

/** Rangée de playlists intelligentes (dans la Biblio). */
@Composable
fun SmartListsRow() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { SmartLists.init(ctx) }
    val lists by SmartLists.lists.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = Txt, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Playlists intelligentes", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            items(lists, key = { it.id }) { s -> SmartCard(s) { SmartUi.show(s) } }
            item {
                Box(
                    Modifier.size(width = 130.dp, height = 96.dp).glass(Rounded(18), fill = 0.06f, edge = 0.14f)
                        .bounceClick(pressedScale = 0.95f) { SmartUi.show(SmartLists.newDraft(), edit = true) },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Add, contentDescription = null, tint = Txt, modifier = Modifier.size(26.dp))
                        Text("Créer", color = Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SmartCard(s: SmartLists.Smart, onClick: () -> Unit) {
    val shape = Rounded(18)
    Box(
        Modifier.size(width = 150.dp, height = 96.dp).clip(shape)
            .background(flatOr(SmartColors[s.color.mod(SmartColors.size)]))
            .glass(shape, fill = 0.02f, edge = 0.16f)
            .bounceClick(pressedScale = 0.95f, onClick = onClick)
            .padding(12.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = Txt.copy(alpha = 0.7f), modifier = Modifier.size(16.dp).align(Alignment.TopStart))
        Text(s.name, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
    }
}

// ---------------------------------------------------------------- panneau : voir / modifier

@Composable
fun SmartPanel() {
    val opened by SmartUi.open.collectAsState()
    val editing by SmartUi.editing.collectAsState()
    val s = opened ?: return
    if (editing) SmartEditor(s) else SmartView(s)
}

@Composable
private fun SmartView(s: SmartLists.Smart) {
    val ctx = LocalContext.current
    val entries by History.entries.collectAsState()
    val scrobbles by LastFm.scrobbles.collectAsState()
    val items by produceState<List<SmartLists.Item>?>(null, s, entries, scrobbles) { value = SmartLists.evaluate(s.rules) }
    val canWrite by TidalAuth.canWrite.collectAsState()
    var copying by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    DismissWhenAtTop(list)

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), state = list, contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = 80.dp)) {
        item {
            PanelTitle(s.name)
            Text(describe(s.rules), color = Txt2, fontSize = 13.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(Space.m))
            val l = items.orEmpty()
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                PillButton("Lire") { if (l.isNotEmpty()) SmartLists.play(l, false) }
                PillButton("Aléatoire", filled = false) { if (l.isNotEmpty()) SmartLists.play(l, true) }
                PillButton("Modifier", filled = false) { SmartUi.editing.value = true }
            }
            if (canWrite && l.isNotEmpty()) {
                Spacer(Modifier.height(Space.s))
                Text(
                    copying ?: "Copier dans Tidal (playlist figée)",
                    color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).bounceClick(pressedScale = 0.97f) {
                        if (copying != null) return@bounceClick
                        scope.launch {
                            runCatching {
                                val n = SmartLists.copyToTidal(s.name, l) { i, total -> copying = "Recherche dans Tidal… $i / $total" }
                                TidalRemote.showToast("« ${s.name} » copiée dans Tidal ($n sons)")
                            }.onFailure { TidalRemote.showToast(it.message ?: "Copie impossible") }
                            copying = null
                        }
                    }.padding(vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(Space.m))
            when {
                items == null -> SkeletonList(6)
                l.isEmpty() -> Text("Aucun son ne correspond pour l'instant. Assouplis les règles avec « Modifier ».", color = Txt2, fontSize = 14.sp, lineHeight = 20.sp)
                else -> Text("${l.size} son${if (l.size > 1) "s" else ""} · se met à jour tout seul", color = Txt3, fontSize = 12.sp)
            }
            Spacer(Modifier.height(Space.s))
        }
        itemsIndexed(items.orEmpty(), key = { i, it -> "$i${it.title}" }) { i, it ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .bounceClick(
                        pressedScale = 0.97f,
                        onLongClick = { TrackMenu.open(TrackMenu.Target(it.title, it.artist, it.cover)) },
                    ) { TidalRemote.playSequence(items.orEmpty().drop(i).map { x -> x.title to x.artist }) }
                    .padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${i + 1}", color = Txt3, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
                Cover(model = it.cover, radius = 6.dp, modifier = Modifier.size(46.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(it.title, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(it.artist, color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (it.plays > 0) Text("${it.plays}×", color = Txt3, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun describe(r: SmartLists.Rules): String = buildList {
    add(if (r.periodDays == 0) "depuis toujours" else "sur ${periodLabel(r.periodDays)}")
    if (r.artist.isNotBlank()) add("artiste : ${r.artist}")
    if (r.likedOnly) add("likés seulement")
    if (r.noFeats) add("sans les feats")
    if (r.notSinceDays > 0) add("pas écoutés depuis ${periodLabel(r.notSinceDays)}")
    if (r.minPlays > 1 && r.minStars == 0) add("au moins ${r.minPlays} écoutes")
    if (r.minStars > 0) add(if (r.minStars == 5) "notés 5 étoiles" else "notés ${r.minStars} étoiles et plus")
    if (r.newOnly) add("découvertes seulement")
    add(r.sort.label.lowercase())
    add("${r.limit} max")
}.joinToString(" · ").replaceFirstChar { it.uppercase() }

private fun periodLabel(d: Int) = when (d) { 7 -> "7 jours"; 30 -> "1 mois"; 60 -> "2 mois"; 90 -> "3 mois"; 180 -> "6 mois"; 365 -> "1 an"; else -> "$d jours" }

@Composable
private fun SmartEditor(start: SmartLists.Smart) {
    var name by remember(start.id) { mutableStateOf(start.name) }
    var r by remember(start.id) { mutableStateOf(start.rules) }
    var color by remember(start.id) { mutableStateOf(start.color) }
    var confirmDelete by remember { mutableStateOf(false) }
    val exists = SmartLists.lists.collectAsState().value.any { it.id == start.id }
    val scroll = androidx.compose.foundation.rememberScrollState()
    DismissWhenAtTop(scroll)

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(scroll).padding(horizontal = Space.gutter).padding(bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        PanelTitle(if (exists) "Modifier" else "Nouvelle playlist")
        val shape = Rounded(14)
        Box(Modifier.fillMaxWidth().height(50.dp).glass(shape, fill = 0.10f, edge = 0.18f).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                textStyle = TextStyle(color = Txt, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold),
                cursorBrush = SolidColor(Txt), modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmartColors.forEachIndexed { i, c ->
                Box(
                    Modifier.size(if (color == i) 34.dp else 28.dp).clip(PillShape).background(flatOr(c))
                        .bounceClick(pressedScale = 0.85f) { color = i },
                )
            }
        }

        RuleLabel("Période")
        ChipRow(listOf(7 to "7 jours", 30 to "1 mois", 90 to "3 mois", 365 to "1 an", 0 to "Toujours"), r.periodDays) { r = r.copy(periodDays = it) }

        RuleLabel("Artiste (facultatif)")
        Box(Modifier.fillMaxWidth().height(46.dp).glass(shape, fill = 0.08f, edge = 0.14f).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
            if (r.artist.isEmpty()) Text("Ex. : Jok'Air", color = Txt3, fontSize = 15.sp)
            BasicTextField(
                value = r.artist, onValueChange = { r = r.copy(artist = it.take(40)) }, singleLine = true,
                textStyle = TextStyle(color = Txt, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                cursorBrush = SolidColor(Txt), modifier = Modifier.fillMaxWidth(),
            )
        }

        RuleSwitch("Seulement les sons likés", r.likedOnly) { r = r.copy(likedOnly = it) }
        RuleSwitch("Sans les feats", r.noFeats) { r = r.copy(noFeats = it) }
        RuleSwitch("Seulement des découvertes (1re écoute pendant la période)", r.newOnly) { r = r.copy(newOnly = it) }

        RuleLabel("Pas écouté depuis")
        ChipRow(listOf(0 to "Peu importe", 30 to "1 mois", 90 to "3 mois", 180 to "6 mois"), r.notSinceDays) { r = r.copy(notSinceDays = it) }

        RuleLabel("Ta note minimum")
        ChipRow(listOf(0 to "Peu importe", 3 to "3 et plus", 4 to "4 et plus", 5 to "5 étoiles"), r.minStars) { r = r.copy(minStars = it) }

        RuleLabel("Écoutes minimum")
        ChipRow(listOf(1 to "1", 3 to "3", 5 to "5", 10 to "10", 25 to "25"), r.minPlays) { r = r.copy(minPlays = it) }

        RuleLabel("Ordre")
        ChipRow(SmartLists.Sort.entries.map { it.ordinal to it.label }, r.sort.ordinal) { r = r.copy(sort = SmartLists.Sort.entries[it]) }

        RuleLabel("Nombre de sons")
        ChipRow(listOf(25 to "25", 50 to "50", 100 to "100", 200 to "200"), r.limit) { r = r.copy(limit = it) }

        Spacer(Modifier.height(Space.s))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            PillButton("Enregistrer") {
                val saved = start.copy(name = name.ifBlank { "Ma playlist" }, rules = r, color = color)
                SmartLists.upsert(saved)
                SmartUi.open.value = saved
                SmartUi.editing.value = false
            }
            if (exists) {
                PillButton(if (confirmDelete) "Sûr ? Supprimer" else "Supprimer", filled = false) {
                    if (confirmDelete) { SmartLists.delete(start.id); Panels.close() } else confirmDelete = true
                }
            }
        }
    }
}

@Composable
private fun RuleLabel(t: String) { Text(t, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold) }

@Composable
private fun RuleSwitch(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Txt, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(Space.s))
        GlassSwitch(on, onChange)
    }
}

@Composable
private fun ChipRow(options: List<Pair<Int, String>>, selected: Int, onPick: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { (v, l) ->
            val on = v == selected
            val shape = PillShape
            Box(
                Modifier.clip(shape)
                    .then(if (on) Modifier.background(Txt) else Modifier.glass(shape, fill = 0.08f, edge = 0.14f))
                    .bounceClick { onPick(v) }
                    .padding(horizontal = 13.dp, vertical = 8.dp),
            ) { Text(l, color = if (on) Ink else Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
        }
    }
}
