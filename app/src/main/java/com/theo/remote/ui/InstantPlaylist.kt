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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.LastFm
import com.theo.remote.TidalApi
import com.theo.remote.TidalAuth
import com.theo.remote.TidalRemote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale

// =====================================================================================
// Playlist instantanée : une phrase (« rap FR calme pour la nuit ») → une playlist de tes favoris
// =====================================================================================

object InstantPlaylist {
    var open by mutableStateOf(false)

    data class Built(val title: String, val tracks: List<TidalApi.Result>, val understood: List<String>, val note: String?)

    private fun n(x: String) = Normalizer.normalize(x.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

    /** Styles reconnus dans la phrase → morceaux de genres Last.fm (tags des artistes). */
    private val genres = listOf(
        "Rap" to (listOf("rap", "hip hop", "hip-hop", "hiphop") to listOf("rap", "hip hop", "hip-hop", "trap")),
        "Trap" to (listOf("trap") to listOf("trap")),
        "Drill" to (listOf("drill") to listOf("drill")),
        "R&B" to (listOf("r&b", "rnb", "rnb", "soul") to listOf("r&b", "rnb", "soul", "neo-soul")),
        "Pop" to (listOf("pop") to listOf("pop")),
        "Rock" to (listOf("rock", "metal") to listOf("rock", "metal", "punk")),
        "Électro" to (listOf("electro", "edm", "house", "techno") to listOf("electronic", "edm", "house", "techno", "electro", "dance")),
        "Afro" to (listOf("afro", "afrobeat", "amapiano") to listOf("afro", "amapiano", "afroswing")),
        "Reggaeton" to (listOf("reggaeton", "latino", "latin") to listOf("reggaeton", "latin")),
        "Jazz" to (listOf("jazz") to listOf("jazz")),
        "Lo-fi" to (listOf("lofi", "lo-fi") to listOf("lo-fi", "lofi", "chillhop")),
    )
    private val places = listOf(
        "Français" to (listOf("fr", "francais", "french", "france") to listOf("french", "francais", "france", "fr")),
        "US" to (listOf("us", "americain", "american") to listOf("american", "usa", "us rap", "east coast", "west coast", "southern")),
        "UK" to (listOf("uk", "anglais", "british") to listOf("uk", "british", "grime", "uk drill")),
        "Belge" to (listOf("belge", "belgique") to listOf("belgian", "belgique", "belge")),
    )

    private enum class Energy { Calm, Hype }

    /** Lit la phrase, va chercher tes favoris et garde ceux qui collent. */
    suspend fun build(ctx: Context, text: String, progress: (String) -> Unit): Built = withContext(Dispatchers.IO) {
        val t = " " + n(text).replace(Regex("[^a-z0-9&\\- ]"), " ") + " "
        fun has(w: String) = t.contains(" $w ")
        val gWanted = genres.filter { (_, v) -> v.first.any { has(it) } }
        val pWanted = places.filter { (_, v) -> v.first.any { has(it) } }
        val calm = listOf("calme", "chill", "doux", "douce", "tranquille", "nuit", "dormir", "detente", "relax", "pluie", "posé", "pose", "lent").any { has(it) }
        val hype = listOf("energie", "enerve", "sport", "muscu", "hype", "motive", "motivation", "fort", "violent", "soiree", "fete", "danse", "rapide").any { has(it) }
        val sad = listOf("triste", "melancolique", "melancolie", "sad", "deprime", "nostalgique", "seul").any { has(it) }
        val happy = listOf("joyeux", "joyeuse", "heureux", "gai", "ete", "soleil", "bonne humeur").any { has(it) }
        val energy = if (calm && !hype) Energy.Calm else if (hype && !calm) Energy.Hype else null
        val size = Regex("(\\d{1,3}) ?(sons|titres|morceaux)").find(t)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(5, 100) ?: 30

        // tes favoris (tout, une seule fois par session)
        progress("Je regarde tes favoris…")
        val favs = favorites(progress)

        // artistes nommés dans la phrase (« du Jok'Air », « Damso et Ninho »)
        val artistNames = favs.flatMap { it.artists }.distinct()
        val namedArtists = artistNames.filter { a -> val k = n(a).trim(); k.length >= 3 && t.contains(" $k ") || (k.length >= 4 && t.contains(k)) }.map { n(it) }.toSet()

        // genres des artistes (Last.fm), seulement si on en a besoin
        var tags: Map<String, List<String>> = emptyMap()
        var note: String? = null
        if (gWanted.isNotEmpty() || pWanted.isNotEmpty() || (energy != null && favs.none { it.bpm != null })) {
            val key = LastFm.apiKey
            if (key.isBlank()) note = "Ajoute ta clé Last.fm (Stats) pour que Hush reconnaisse les styles de tes artistes"
            else {
                val mains = favs.map { mainArtist(it.artistLine) }.filter { it.isNotBlank() }.distinct()
                progress("J'apprends les styles de tes artistes… (${mains.size})")
                tags = MoodMix.tagsFor(ctx, mains, key)
            }
        }

        fun tagsOf(r: TidalApi.Result) = tags[mainArtist(r.artistLine).lowercase()].orEmpty()
        val calmTags = listOf("chill", "lo-fi", "lofi", "r&b", "rnb", "soul", "acoustic", "ambient", "mellow", "downtempo", "cloud rap")
        val hypeTags = listOf("trap", "drill", "rage", "phonk", "hardcore", "edm", "club", "grime", "workout")

        fun score(r: TidalApi.Result, strict: Boolean): Double? {
            val tg = tagsOf(r)
            if (namedArtists.isNotEmpty() && r.artists.none { n(it) in namedArtists }) return null
            if (gWanted.isNotEmpty() && tags.isNotEmpty() && gWanted.none { (_, v) -> tg.any { tag -> v.second.any { tag.contains(it) } } }) return null
            if (pWanted.isNotEmpty() && tags.isNotEmpty() && pWanted.none { (_, v) -> tg.any { tag -> v.second.any { it == tag || tag.contains(it) && it.length > 2 } } }) return null
            var s = 1.0
            val bpm = r.bpm
            when (energy) {
                Energy.Calm -> when {
                    bpm != null && bpm <= 100f -> s += 2.0
                    bpm != null && bpm >= 125f -> if (strict) return null else s -= 1.0
                    tg.any { tag -> calmTags.any { tag.contains(it) } } -> s += 1.0
                }
                Energy.Hype -> when {
                    bpm != null && bpm >= 118f -> s += 2.0
                    bpm != null && bpm <= 92f -> if (strict) return null else s -= 1.0
                    tg.any { tag -> hypeTags.any { tag.contains(it) } } -> s += 1.0
                }
                null -> {}
            }
            if (sad) { if (r.keyScale == "MINOR") s += 1.5 else if (r.keyScale == "MAJOR" && strict) s -= 1.0 }
            if (happy) { if (r.keyScale == "MAJOR") s += 1.5 else if (r.keyScale == "MINOR" && strict) s -= 1.0 }
            return s + Math.random() * 0.8 // un peu de hasard : deux fois la même phrase, deux playlists différentes
        }

        progress("Je choisis les sons…")
        var picked = favs.mapNotNull { r -> score(r, true)?.let { r to it } }
        if (picked.size < 8) {
            picked = favs.mapNotNull { r -> score(r, false)?.let { r to it } }
            if (picked.size >= 8 && note == null) note = "Pas assez de sons qui collent parfaitement : j'ai un peu élargi"
        }
        // pas plus de 3 sons du même artiste, sauf si tu as demandé un artiste
        val perArtist = HashMap<String, Int>()
        val out = picked.sortedByDescending { it.second }.map { it.first }.filter { r ->
            if (namedArtists.isNotEmpty()) true
            else { val a = mainArtist(r.artistLine).lowercase(); val c = (perArtist[a] ?: 0) + 1; perArtist[a] = c; c <= 3 }
        }.take(size)

        val understood = buildList {
            gWanted.forEach { add(it.first) }
            pWanted.forEach { add(it.first) }
            if (energy == Energy.Calm) add("Calme")
            if (energy == Energy.Hype) add("Énergique")
            if (sad) add("Mélancolique")
            if (happy) add("Joyeux")
            artistNames.filter { n(it) in namedArtists }.take(3).forEach { add(it) }
        }
        val title = text.trim().replaceFirstChar { it.uppercase() }
        Built(title, out, understood, note)
    }

    @Volatile private var favCache: List<TidalApi.Result>? = null

    private suspend fun favorites(progress: (String) -> Unit = {}): List<TidalApi.Result> = favCache ?: run {
        val all = ArrayList<TidalApi.Result>()
        var next: String? = null
        var guard = 0
        do {
            val pg = TidalApi.favoritesPage(next)
            all += pg.tracks
            next = pg.next
            guard++
            if (guard % 3 == 0) progress("Je regarde tes favoris… ${all.size}")
        } while (next != null && guard < 40)
        all.distinctBy { it.id }.also { favCache = it }
    }

    /**
     * « + 10 sons comme ça » : tes favoris les plus proches du son en cours
     * (même artiste ou styles en commun, tempo et tonalité proches), 2 par artiste au plus.
     */
    suspend fun similar(ctx: Context, title: String, artist: String, exclude: Set<String>, count: Int = 10): List<TidalApi.Result> = withContext(Dispatchers.IO) {
        val favs = favorites()
        val nt = n(title).substringBefore(" (")
        val me = favs.firstOrNull { n(it.title) == nt && n(mainArtist(it.artistLine)) == n(mainArtist(artist)) }
        val myArtist = n(mainArtist(artist))
        val key = LastFm.apiKey
        val tags = if (key.isNotBlank()) runCatching {
            MoodMix.tagsFor(ctx, (favs.map { mainArtist(it.artistLine) } + mainArtist(artist)).filter { it.isNotBlank() }.distinct(), key)
        }.getOrDefault(emptyMap()) else emptyMap()
        val myTags = tags[mainArtist(artist).lowercase()].orEmpty().toSet()
        val scored = favs.asSequence()
            .filter { it.id != me?.id && it.id !in exclude && !(n(it.title) == nt && n(mainArtist(it.artistLine)) == myArtist) }
            .map { r ->
                var sc = 0.0
                val ra = n(mainArtist(r.artistLine))
                if (ra == myArtist) sc += 2.0
                if (r.artists.any { n(it) == myArtist }) sc += 1.0
                val rt = tags[mainArtist(r.artistLine).lowercase()].orEmpty()
                sc += rt.count { it in myTags }.coerceAtMost(4) * 0.8
                val b1 = me?.bpm; val b2 = r.bpm
                if (b1 != null && b2 != null) { val d = kotlin.math.abs(b1 - b2); sc += when { d < 6 -> 1.6; d < 12 -> 0.9; d > 30 -> -0.8; else -> 0.0 } }
                if (me?.keyScale != null && me.keyScale == r.keyScale) sc += 0.4
                r to sc + Math.random() * 0.6
            }
            .filter { it.second > 0.5 }
            .sortedByDescending { it.second }
            .toList()
        val per = HashMap<String, Int>()
        scored.map { it.first }.filter { r -> val a = n(mainArtist(r.artistLine)); val c = (per[a] ?: 0) + 1; per[a] = c; c <= 2 }.take(count)
    }
}

@Composable
fun InstantPlaylistHost() {
    if (!InstantPlaylist.open) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var built by remember { mutableStateOf<InstantPlaylist.Built?>(null) }
    var saving by remember { mutableStateOf(false) }
    fun close() { InstantPlaylist.open = false }
    BackHandler { close() }
    fun run(q: String) {
        if (q.isBlank() || busy != null) return
        text = q
        built = null
        scope.launch {
            busy = "…"
            val r = runCatching { InstantPlaylist.build(ctx, q) { p -> busy = p } }
            busy = null
            r.onSuccess { built = it }.onFailure { TidalRemote.showToast(it.message ?: "Erreur réseau") }
        }
    }
    val ideas = listOf("rap FR calme pour la nuit", "sport énervé", "R&B pour la route", "sons tristes", "soirée afro", "rap US énergique")
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(remember { MutableInteractionSource() }, null) { close() })
        GlassPlate(
            Rounded(30), 30.dp,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(10.dp).fillMaxWidth().heightIn(max = 680.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(width = 40.dp, height = 5.dp).clip(PillShape).background(Color.White.copy(alpha = 0.25f)))
                Text("Playlist instantanée", color = Txt, fontSize = 26.sp, fontWeight = FontWeight.Black)
                Text("Dis ce que tu veux écouter, Hush la fait avec tes favoris.", color = Txt2, fontSize = 14.sp)
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clip(Rounded(26)).background(Color.White.copy(alpha = 0.1f)).padding(start = 18.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (text.isEmpty()) Text("rap FR calme pour la nuit", color = Txt3, fontSize = 16.sp)
                        BasicTextField(
                            text, { text = it }, singleLine = true,
                            textStyle = TextStyle(color = Txt, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                            cursorBrush = SolidColor(Txt),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { run(text) }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Box(
                        Modifier.size(42.dp).clip(PillShape).background(Color.White).bounceClick { run(text) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.AutoAwesome, contentDescription = "Créer", tint = Color.Black, modifier = Modifier.size(22.dp)) }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ideas) { s ->
                        Box(Modifier.clip(PillShape).background(Color.White.copy(alpha = 0.08f)).bounceClick { run(s) }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(s, color = Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                busy?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = Txt2, fontSize = 14.sp)
                    SkeletonList(3)
                }
                built?.let { b ->
                    if (b.understood.isNotEmpty()) {
                        Text("Compris : " + b.understood.joinToString(" · "), color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    b.note?.let { Text(it, color = Color(0xFFFFB547), fontSize = 13.sp, lineHeight = 18.sp) }
                    if (b.tracks.isEmpty()) {
                        Text("Aucun de tes favoris ne colle. Essaie avec d'autres mots.", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Text("${b.tracks.size} son${if (b.tracks.size > 1) "s" else ""}", color = Txt, fontSize = 18.sp, fontWeight = FontWeight.Black)
                        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            items(b.tracks.distinctBy { it.id }, key = { it.id }) { r ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Cover(model = r.coverUrl, radius = 8.dp, modifier = Modifier.size(42.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(r.fullTitle, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(r.artistLine, color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    r.bpm?.let { Text("${it.toInt()} BPM", color = Txt3, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.weight(1f)) { PillButton("Lire") {
                                val entries = b.tracks.map { com.theo.remote.LocalMusic.Entry.T(it) as com.theo.remote.LocalMusic.Entry }
                                TidalRemote.setShuffle(false)
                                com.theo.remote.MixQueue.play(ctx, entries, 0)
                                TidalRemote.markShuffle(false)
                                close()
                            } }
                            Box(Modifier.weight(1f)) { PillButton(if (saving) "Création…" else "Garder sur Tidal", filled = false) {
                                if (saving) return@PillButton
                                if (!TidalAuth.canWrite.value) { TidalRemote.showToast("Reconnecte Tidal pour que Hush puisse créer des playlists"); return@PillButton }
                                saving = true
                                scope.launch {
                                    runCatching {
                                        val p = TidalApi.createPlaylist(b.title, "Faite par Hush : « ${text.trim()} »")
                                        b.tracks.chunked(20).forEach { TidalApi.addToPlaylist(p.id, it.map { r -> r.id }) }
                                        runCatching { PlaylistStore.load(force = true) }
                                    }.onSuccess { TidalRemote.showToast("Playlist « ${b.title} » créée") ; close() }
                                        .onFailure { TidalRemote.showToast(it.message ?: "Échec de la création") }
                                    saving = false
                                }
                            } }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
