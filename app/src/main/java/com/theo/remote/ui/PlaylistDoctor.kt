package com.theo.remote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.SkipTracker
import com.theo.remote.TidalAuth
import com.theo.remote.TidalApi
import com.theo.remote.TidalRemote
import kotlinx.coroutines.launch

/** Docteur ouvert (dessiné tout en haut de l'appli, par-dessus la barre du bas et le mini-lecteur). */
object PlaylistDoctorState {
    var target by mutableStateOf<TidalApi.Playlist?>(null)
    var onCleaned: () -> Unit = {}
    fun open(p: TidalApi.Playlist, cleaned: () -> Unit) { onCleaned = cleaned; target = p }
    fun close() { target = null }
}

@Composable
fun PlaylistDoctorHost() {
    val p = PlaylistDoctorState.target ?: return
    PlaylistDoctorSheet(p, onClose = { PlaylistDoctorState.close() }) { PlaylistDoctorState.onCleaned() }
}

/** Ce que le docteur a trouvé dans une playlist. */
private data class Diagnosis(
    val duplicates: List<TidalApi.Result>,
    val unavailable: List<TidalApi.Result>,
    val skipped: List<Pair<TidalApi.Result, Int>>,
    val total: Int,
)

private fun dnorm(x: String) = x.lowercase().substringBefore(" (").substringBefore(" - ").replace(Regex("[^\\p{L}\\p{N}]"), "")
private fun dkey(title: String, artist: String) = dnorm(title) + "|" + dnorm(artist.split(",", "&", " feat", " ft.").first())

/**
 * Docteur de playlist : repère les doublons, les sons que Tidal ne peut plus lire et ceux que tu passes
 * presque toujours. Tout est coché par défaut ; un toucher enlève ce qui est coché de la playlist Tidal.
 */
@Composable
fun PlaylistDoctorSheet(p: TidalApi.Playlist, onClose: () -> Unit, onCleaned: () -> Unit) {
    var diag by remember(p.id) { mutableStateOf<Diagnosis?>(null) }
    var error by remember(p.id) { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val picked: SnapshotStateMap<String, Boolean> = remember(p.id) { mutableStateMapOf() }
    val scope = rememberCoroutineScope()
    BackHandler { onClose() }

    LaunchedEffect(p.id) {
        try {
            // toute la playlist (avec l'identifiant de chaque ligne, pour pouvoir la retirer)
            val all = ArrayList<TidalApi.Result>()
            var next: String? = null
            var guard = 0
            do {
                val pg = TidalApi.playlistPage(p.id, next)
                all += pg.tracks
                next = pg.next
                guard++
            } while (next != null && guard < 60)
            // doublons : on garde la 1re fois, on propose d'enlever les suivantes
            val seen = HashSet<String>()
            val dupes = all.filter { r -> !seen.add(dkey(r.title, r.artistLine)) && r.itemId != null }
            val dead = runCatching { TidalApi.playlistUnavailable(p.id) }.getOrDefault(emptyList())
            // passés presque à chaque fois (au moins 3 fois en moins de 30 s)
            val skips = SkipTracker.skips.value.values.filter { it.count >= 3 }.associateBy { dkey(it.title, it.artist) }
            val skipped = all.filter { it.itemId != null && it !in dupes }.mapNotNull { r -> skips[dkey(r.title, r.artistLine)]?.let { r to it.count } }
                .distinctBy { it.first.itemId }
            val d = Diagnosis(dupes, dead, skipped, all.size)
            (d.duplicates + d.unavailable + d.skipped.map { it.first }).forEach { r -> r.itemId?.let { picked[it] = true } }
            diag = d
        } catch (e: Exception) {
            error = netError(e)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { onClose() })
        // plaque de verre flottante, comme les menus
        GlassPlate(
            Rounded(30), 30.dp,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(10.dp).fillMaxWidth().heightIn(max = 680.dp),
        ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 40.dp, height = 5.dp).clip(PillShape).background(Color.White.copy(alpha = 0.25f)))
            Spacer(Modifier.height(16.dp))
            Text("Docteur de playlist", color = Txt, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text(p.name, color = Txt2, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            val d = diag
            when {
                error != null -> Text(error!!, color = Ko, fontSize = 13.sp)
                d == null -> { Text("Examen de la playlist…", color = Txt2, fontSize = 15.sp); Spacer(Modifier.height(12.dp)); SkeletonList(4) }
                d.duplicates.isEmpty() && d.unavailable.isEmpty() && d.skipped.isEmpty() -> {
                    Text("Rien à signaler", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.Black)
                    Text("Pas de doublon, rien d'indisponible, et aucun son que tu passes à chaque fois sur ${d.total} sons.", color = Txt2, fontSize = 15.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(16.dp))
                    PillButton("Fermer") { onClose() }
                }
                else -> {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DoctorSection("Doublons", "La 1re version est gardée, les autres partent", d.duplicates.map { it to null }, picked)
                        DoctorSection("Indisponibles sur Tidal", "Tidal ne peut plus les lire", d.unavailable.map { it to null }, picked)
                        DoctorSection("Toujours passés", "Tu les zappes dès le début", d.skipped.map { it.first to "passé ${it.second} fois" }, picked)
                    }
                    Spacer(Modifier.height(12.dp))
                    val n = picked.count { it.value }
                    PillButton(if (working) "Nettoyage…" else if (n == 0) "Rien de coché" else "Retirer $n son${if (n > 1) "s" else ""} de la playlist") {
                        if (working || n == 0) return@PillButton
                        if (!TidalAuth.canWrite.value) { TidalRemote.showToast("Reconnecte Tidal pour que Hush puisse modifier tes playlists"); return@PillButton }
                        working = true
                        val ids = picked.filterValues { it }.keys
                        val toRemove = (d.duplicates + d.unavailable + d.skipped.map { it.first }).filter { it.itemId in ids }.distinctBy { it.itemId }
                        scope.launch {
                            runCatching { toRemove.chunked(20).forEach { TidalApi.removeFromPlaylist(p.id, it) } }
                                .onSuccess {
                                    TidalRemote.showToast("${toRemove.size} son${if (toRemove.size > 1) "s" else ""} retiré${if (toRemove.size > 1) "s" else ""}")
                                    onCleaned()
                                    onClose()
                                }
                                .onFailure { TidalRemote.showToast(it.message ?: "Échec du nettoyage") }
                            working = false
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        }
    }
}

@Composable
private fun DoctorSection(title: String, sub: String, items: List<Pair<TidalApi.Result, String?>>, picked: SnapshotStateMap<String, Boolean>) {
    if (items.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Txt, fontSize = 20.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
        Text("${items.size}", color = Txt2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
    Text(sub, color = Txt2, fontSize = 13.sp)
    Spacer(Modifier.height(4.dp))
    items.forEach { (r, extra) ->
        val id = r.itemId ?: return@forEach
        val on = picked[id] == true
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).bounceClick(pressedScale = 0.98f) { picked[id] = !on }.padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(model = r.coverUrl, radius = 8.dp, modifier = Modifier.size(44.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.fullTitle, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(r.artistLine.ifBlank { null }, extra).joinToString(" · "), color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(
                Modifier.size(26.dp).clip(PillShape).background(if (on) Txt else Color.White.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) { if (on) Icon(Icons.Rounded.Check, contentDescription = "Coché", tint = Ink, modifier = Modifier.size(18.dp)) }
        }
    }
}
