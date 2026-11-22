package com.theo.remote.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.theo.remote.AutoResume
import com.theo.remote.PartyServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Panneaux plein écran ouverts depuis n'importe où (soirée, réglages). */
object Panels {
    enum class Kind { None, Party, Settings, Alarm, Shazam, BlindTest, Transitions, Karaoke, Mix, Smart, Quotes, Era, Notes, IdCard, Feats, Words, Trip, Sound, Tools, Diagnostic, Albums, Credits, Concerts, Ambient, OneSong, Skips, Setlists, LyricsQuiz, Rewards, Local, Ratings, Discover, CoverWall, Growing, Stats, Cards }
    private val _open = MutableStateFlow(Kind.None)
    val open: StateFlow<Kind> = _open
    /** Pages ouvertes avant celle-ci : le geste retour y revient au lieu de tout fermer. */
    private val stack = ArrayDeque<Kind>()
    fun show(k: Kind) {
        val cur = _open.value
        if (cur != Kind.None && cur != k) { stack.remove(k); stack.addLast(cur) }
        _open.value = k
    }
    /** Retour : la page d'avant, sinon on ferme. */
    fun close() { _open.value = stack.removeLastOrNull() ?: Kind.None }
    /** Tout fermer d'un coup. */
    fun closeAll() { stack.clear(); _open.value = Kind.None }
}

@Composable
fun PanelHost() {
    val which by Panels.open.collectAsState()
    androidx.compose.animation.AnimatedVisibility(
        visible = which != Panels.Kind.None,
        enter = androidx.compose.animation.slideInVertically(spring(dampingRatio = 0.86f, stiffness = 320f)) { it } + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
    ) {
        // garde le dernier panneau affiché pendant l'animation de fermeture
        val last = remember { arrayOf(Panels.Kind.Party) }
        if (which != Panels.Kind.None) last[0] = which
        BackHandler { Panels.close() }
        // verre liquide + option « Panneaux en verre » : une grande plaque de verre flottante
        val glassy = com.theo.remote.Features.flags.collectAsState().value.glassPanels && liquidOn() && LocalMainGlass.current != null
        SwipeDismissible(onDismiss = { Panels.close() }, modifier = Modifier.fillMaxSize()) {
            if (glassy) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
            Box(
                if (glassy) Modifier.fillMaxSize() else Modifier.fillMaxSize().background(Ink),
            ) {
                if (!glassy) Ambient(rememberCoverColors(com.theo.remote.TidalRemote.state.collectAsState().value.track?.art), strength = 0.8f, themeable = true)
                PanelFrame(glassy) {
                when (last[0]) {
                    Panels.Kind.Settings -> SettingsScreen()
                    Panels.Kind.Alarm -> AlarmPanel()
                    Panels.Kind.Shazam -> ShazamPanel()
                    Panels.Kind.BlindTest -> BlindTestPanel()
                    Panels.Kind.Transitions -> TransitionsPanel()
                    Panels.Kind.Karaoke -> KaraokePanel()
                    Panels.Kind.Mix -> MixPanel()
                    Panels.Kind.Smart -> SmartPanel()
                    Panels.Kind.Quotes -> QuotesPanel()
                    Panels.Kind.Era -> EraPanel()
                    Panels.Kind.Notes -> NotesPanel()
                    Panels.Kind.IdCard -> IdCardPanel()
                    Panels.Kind.Feats -> FeatsPanel()
                    Panels.Kind.Words -> WordsPanel()
                    Panels.Kind.Trip -> TripPanel()
                    Panels.Kind.Sound -> SoundPanel()
                    Panels.Kind.Tools -> ToolsPanel()
                    Panels.Kind.Diagnostic -> DiagnosticPanel()
                    Panels.Kind.Albums -> AlbumsPanel()
                    Panels.Kind.Credits -> CreditsPanel()
                    Panels.Kind.Concerts -> ConcertsPanel()
                    Panels.Kind.Ambient -> AmbientPanel()
                    Panels.Kind.OneSong -> OneSongPanel()
                    Panels.Kind.Skips -> SkipsPanel()
                    Panels.Kind.Setlists -> SetlistsPanel()
                    Panels.Kind.LyricsQuiz -> LyricsQuizPanel()
                    Panels.Kind.Rewards -> RewardsPanel()
                    Panels.Kind.Local -> LocalPanel()
                    Panels.Kind.Ratings -> RatingsPanel()
                    Panels.Kind.Discover -> DiscoverPanel()
                    Panels.Kind.CoverWall -> CoverWallPanel()
                    Panels.Kind.Growing -> GrowingPanel()
                    Panels.Kind.Stats -> StatsScreen()
                    Panels.Kind.Cards -> CardsScreen()
                    else -> PartyScreen()
                }
                }
            }
        }
    }
}

@Composable
fun PanelTitle(title: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(width = 72.dp, height = 44.dp).clip(PillShape).bounceClick { Panels.close() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Fermer", tint = Txt.copy(alpha = 0.6f), modifier = Modifier.size(36.dp))
        }
    }
    Spacer(Modifier.height(Space.s))
    Text(title, color = Txt, fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.6).sp)
}

// ---------------------------------------------------------------- mode soirée

@Composable
private fun PartyScreen() {
    val ctx = LocalContext.current
    val st by PartyServer.status.collectAsState()
    val added by PartyServer.added.collectAsState()
    LaunchedEffect(Unit) { if (!st.running) PartyServer.start(ctx) }
    val qr = remember(st.url) { st.url?.let { qrBitmap(it) } }

    val scroll = rememberScrollState()
    DismissWhenAtTop(scroll)
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(scroll).padding(horizontal = Space.gutter).padding(bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        PanelTitle("Mode soirée")
        Text(
            "Tes potes scannent le QR code avec l'appareil photo, cherchent un son et l'ajoutent à ta file. Pas besoin d'appli.",
            color = Txt2, fontSize = 14.sp, lineHeight = 20.sp,
        )

        st.error?.let {
            Text(it, color = Ko, fontSize = 14.sp, lineHeight = 20.sp)
            PillButton("Réessayer") { PartyServer.start(ctx) }
        }

        if (st.running && qr != null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.fillMaxWidth(0.82f).aspectRatio(1f).shadow(30.dp, Rounded(28)).clip(Rounded(28))
                        .background(Color.White).padding(18.dp),
                ) {
                    Image(qr.asImageBitmap(), contentDescription = "QR code de la soirée", filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
                }
            }
            Text(
                st.url.orEmpty().removePrefix("http://"),
                color = Txt3, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                InfoChip(Icons.Rounded.Celebration, if (st.guests <= 1) "${st.guests} invité" else "${st.guests} invités")
            }
            Text(
                "Ils doivent être sur le même Wi-Fi que toi. Pas de Wi-Fi ? Active le partage de connexion et fais-les s'y connecter.",
                color = Txt3, fontSize = 12.sp, lineHeight = 17.sp,
            )
            PillButton("Arrêter la soirée", filled = false) { PartyServer.stop(); Panels.close() }
        }

        if (added.isNotEmpty()) {
            Spacer(Modifier.height(Space.s))
            Text("Ajoutés par tes potes", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            added.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(PillShape).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                        Text(a.by.take(1).uppercase(), color = Txt, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.title, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${a.artist} · par ${a.by}", color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(
        Modifier.glass(PillShape, fill = 0.08f, edge = 0.14f).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Txt, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

fun qrBitmap(text: String): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    val w = m.width
    val h = m.height
    val px = IntArray(w * h) { i -> if (m.get(i % w, i / w)) 0xFF0B0B10.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}

// ---------------------------------------------------------------- réglages

@Composable
private fun SettingsScreen() {
    val enabled by AutoResume.enabled.collectAsState()
    val devices by AutoResume.devices.collectAsState()

    val scroll = rememberScrollState()
    DismissWhenAtTop(scroll)
    var query by remember { mutableStateOf("") }
    var pendingSection by remember { mutableStateOf<String?>(null) }
    val anchors = remember { mutableMapOf<String, Int>() }
    LaunchedEffect(Unit) { SettingsFold.showAll.value = false }
    LaunchedEffect(pendingSection, query) {
        val sec = pendingSection ?: return@LaunchedEffect
        if (query.isNotEmpty()) return@LaunchedEffect
        SettingsFold.expand(sec)
        kotlinx.coroutines.delay(380) // le temps que la page réapparaisse et que le thème se déplie
        anchors[sec]?.let { scroll.animateScrollTo((it - 40).coerceAtLeast(0)) }
        pendingSection = null
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalSettingsAnchors provides anchors) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(scroll).padding(horizontal = Space.gutter).padding(bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        PanelTitle("Réglages")
        SettingsSearchField(query) { query = it }
        if (query.isNotBlank()) {
            SettingsSearchResults(query) { section -> SettingsFold.showAll.value = true; pendingSection = section; query = "" }
            return@Column
        }

        SimpleModeCard()
        MostUsedCard()

        val shape = Rounded(22)
        FoldSection("Lecture") {
        PlaybackOptionsCard()
        SettingsHeader("Son")
        SoundOptionsCard()
        TransitionSettingsCard()
        Column(Modifier.fillMaxWidth().glass(shape, fill = 0.07f, edge = 0.12f).padding(Space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Reprise auto", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Relance la musique quand ton casque ou ta voiture se connecte", color = Txt2, fontSize = 13.sp, lineHeight = 18.sp)
                }
                Spacer(Modifier.width(Space.s))
                GlassSwitch(enabled) { AutoResume.setEnabled(it) }
            }
        }
        GesturesCard()
        }

        FoldSection("Affichage") {
        DisplayCard()
        HomeSectionsCard()
        }

        FoldSection("Tes notes") { NotesOptionsCard() }

        FoldSection("Playlists") { PlaylistOptionsCard() }

        FoldSection("Fichiers locaux") { LocalOptionsCard() }

        FoldSection("Fun et découverte") { FunOptionsCard() }

        FoldSection("Stats") { StatsOptionsCard() }

        FoldSection("Paroles et recherche") { LyricsSearchCard() }

        FoldSection("Widgets et notifications") { WidgetsNotifCard() }

        FoldSection("Tes goûts") {
        FollowCard()
        StatsExcludeCard()
        if (com.theo.remote.Features.flags.collectAsState().value.concerts) ConcertsKeyCard()
        if (com.theo.remote.Features.flags.collectAsState().value.setlists) SetlistKeyCard()
        BlockedCard()
        }

        FoldSection("Données") {
        BackupCard()
        DiagnosticCard()
        BugReportCard()
        }

        SettingsHeader("Appareils")
        if (devices.isEmpty()) {
            Text(
                "Branche ton casque ou connecte ta voiture : ils apparaîtront ici. Tu pourras choisir lesquels relancent la musique, ouvrent le mode voiture, et leur volume et leurs basses.",
                color = Txt2, fontSize = 14.sp, lineHeight = 20.sp,
            )
        }
        devices.forEach { d ->
            Column(Modifier.fillMaxWidth().glass(shape, fill = 0.06f, edge = 0.10f).padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when { d.car -> Icons.Rounded.DirectionsCar; d.kind == "Bluetooth" -> Icons.Rounded.Bluetooth; else -> Icons.Rounded.Headphones },
                        contentDescription = null, tint = Txt, modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, color = Txt, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(d.kind, color = Txt2, fontSize = 12.sp)
                    }
                    Text(
                        "Oublier", color = Txt3, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).bounceClick(pressedScale = 0.95f) { AutoResume.forget(d) }.padding(6.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Relancer la musique", color = Txt, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    GlassSwitch(d.resume) { AutoResume.update(d.copy(resume = it)) }
                }
                if (d.resume) {
                    val pls by PlaylistStore.list.collectAsState()
                    Text("À la connexion", color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val options = listOf<String?>(null) + pls.orEmpty().map { it.name }.take(30)
                        items(options.size) { i ->
                            val o = options[i]
                            val on = d.playlist == o
                            Box(
                                Modifier.clip(BtnShape)
                                    .then(if (on) Modifier.background(Txt) else Modifier.glass(BtnShape, fill = 0.08f, edge = 0.14f))
                                    .bounceClick { AutoResume.update(d.copy(playlist = o)) }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                            ) { Text(o ?: "Reprendre où j'en étais", color = if (on) Ink else Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("C'est ma voiture (ouvre le mode voiture)", color = Txt, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    GlassSwitch(d.car) { AutoResume.update(d.copy(car = it)) }
                }
                val pf by com.theo.remote.Features.flags.collectAsState()
                if (pf.deviceProfiles) {
                    ProfileChips("Volume", listOf(null to "Ne pas toucher", 30 to "30 %", 50 to "50 %", 70 to "70 %", 100 to "100 %"), d.volume) { AutoResume.update(d.copy(volume = it)) }
                    if (pf.bass) ProfileChips("Basses", listOf(null to "Ne pas toucher", 0 to "Normales", 1 to "Un peu", 2 to "Plus", 3 to "À fond"), d.bass) { AutoResume.update(d.copy(bass = it)) }
                    if (pf.spatial) ProfileChips("Son 8D", listOf(null to "Ne pas toucher", true to "Oui", false to "Non"), d.spatial) { AutoResume.update(d.copy(spatial = it)) }
                }
            }
        }
    }
    }
}

/** Une ligne de choix pour le profil d'un appareil. */
@Composable
private fun <T> ProfileChips(label: String, options: List<Pair<T?, String>>, value: T?, onPick: (T?) -> Unit) {
    Text(label, color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options.size) { i ->
            val (v, text) = options[i]
            val on = value == v
            Box(
                Modifier.clip(BtnShape)
                    .then(if (on) Modifier.background(Txt) else Modifier.glass(BtnShape, fill = 0.08f, edge = 0.14f))
                    .bounceClick { onPick(v) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) { Text(text, color = if (on) Ink else Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
        }
    }
}

/** Interrupteur dans le style verre de l'appli. */
@Composable
fun GlassSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val haptic = rememberHaptic()
    val target = if (on) 22.dp else 2.dp
    val x by animateDpAsState(target, spring(dampingRatio = 0.7f, stiffness = 600f), label = "switch")
    val track by animateColorAsState(if (on) Ok else Color.White.copy(alpha = 0.14f), label = "switchBg")
    val liquid = liquidOn()
    // verre liquide : quand on appuie (et pendant qu'elle glisse), la pastille devient une loupe en verre
    // qui grossit l'interrupteur dessous, un peu plus grosse ; puis elle redevient blanche en se posant
    var pressed by remember { mutableStateOf(false) }
    val moving = kotlin.math.abs((x - target).value) > 0.5f
    val g by animateFloatAsState(if (liquid && (pressed || moving)) 1f else 0f, spring(dampingRatio = 0.75f, stiffness = 500f), label = "switchLens")
    val local = rememberBackdrop()
    Box(
        Modifier.size(width = 50.dp, height = 30.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    pressed = true
                    do {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                    } while (e.changes.any { it.pressed })
                    pressed = false
                }
            }
            .bounceClick(pressedScale = 0.94f) { haptic(); onChange(!on) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier.matchParentSize().backdropSource(local).clip(PillShape).background(track)
                .border(1.dp, Color.White.copy(alpha = 0.14f), PillShape),
        )
        val grow = 8.dp * g
        Box(
            Modifier.offset(x = x - grow / 2).size(width = 26.dp + grow, height = 26.dp + grow * 0.25f)
                .shadow(4.dp, PillShape).clip(PillShape),
        ) {
            if (g > 0.02f) LiquidGlass(
                local, PillShape, cornerRadius = 15.dp,
                modifier = Modifier.matchParentSize().graphicsLayer { alpha = g },
                blur = 0.dp, depth = 9.dp, refraction = 5.dp, chroma = 0.3f, zoom = 1.3f, spec = 1.3f,
            )
            Box(Modifier.matchParentSize().background(Color.White.copy(alpha = 1f - 0.92f * g)))
        }
    }
}

/** Cadre d'un panneau : plaque de verre flottante (avec une marge tout autour) ou plein écran. */
@Composable
private fun PanelFrame(glassy: Boolean, content: @Composable () -> Unit) {
    if (!glassy) { content(); return }
    GlassPlate(
        Rounded(34), 34.dp,
        // la marge du haut prend la barre d'état : les pages dedans n'en rajoutent pas
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
    ) { content() }
}
