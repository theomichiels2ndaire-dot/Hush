package com.theo.remote.ui

import com.theo.remote.peekAdjacent
import com.theo.remote.retryVersion

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.toArgb
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.Crossfade
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.TidalApi
import com.theo.remote.TidalRemote
import kotlinx.coroutines.delay

private enum class Tab { Home, Search, Library, Stats, Cards }
private enum class Sheet { None, Player, Lyrics }

@Composable
fun RemoteApp() {
    val s by TidalRemote.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    // Cartes à la place de Stats (et inversement si l'option est coupée)
    val cardsOn = com.theo.remote.Features.flags.collectAsState().value.songCards
    val threeTabs = com.theo.remote.Features.flags.collectAsState().value.threeTabs
    LaunchedEffect(cardsOn, threeTabs) {
        if (cardsOn && tab == Tab.Stats) tab = Tab.Cards
        if (!cardsOn && tab == Tab.Cards) tab = Tab.Stats
        com.theo.remote.Features.flags.value.let { if ((it.simpleMode || it.threeTabs) && (tab == Tab.Stats || tab == Tab.Cards)) tab = Tab.Home }
    }
    var sheet by rememberSaveable { mutableStateOf(Sheet.None) }
    var openPlaylist by remember { mutableStateOf<TidalApi.Playlist?>(null) }
    // garde la dernière playlist affichée pendant l'animation de fermeture
    var shownPlaylist by remember { mutableStateOf<TidalApi.Playlist?>(null) }
    SideEffect { if (openPlaylist != null) shownPlaylist = openPlaylist }
    LaunchedEffect(Unit) { NavEvents.openPlaylistEvents.collect { openPlaylist = it; sheet = Sheet.None } }
    var sheetDrag by remember { mutableFloatStateOf(0f) }
    // le mini-lecteur d'un panneau (Fichiers locaux) demande le grand lecteur
    LaunchedEffect(Unit) { PlayerOpen.requests.collect { sheetDrag = 0f; sheet = Sheet.Player } }
    val backdrop = rememberBackdrop()
    // tout le lecteur ouvert (fond + contenu) : ce que tordent les fenêtres ouvertes depuis le lecteur
    val sheetGlass = rememberBackdrop()
    val tabStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    // option : en quittant l'onglet Recherche, la recherche en cours est effacée (retour à l'historique)
    val searchReset = com.theo.remote.Features.flags.collectAsState().value.searchReset
    LaunchedEffect(tab) {
        if (tab != Tab.Search && searchReset) { resetSearch(); tabStates.removeState(Tab.Search.name) }
    }
    // pages artiste / album empilées (artiste → album → …)
    var details by remember { mutableStateOf(listOf<Detail>()) }
    val openDetail: (Detail) -> Unit = remember {
        { d -> sheet = Sheet.None; details = details + d }
    }
    val colors = rememberCoverColors(s.track?.art)
    // couleur d'accent « pochette » : une version claire de la couleur du son en cours
    SideEffect {
        com.theo.remote.Accent.cover = androidx.compose.ui.graphics.lerp(colors.b, Color.White, 0.35f).toArgb().toLong() and 0xFFFFFFFFL
        CoverTint.color.value = if (s.track?.art != null) androidx.compose.ui.graphics.lerp(colors.b, Color.White, 0.35f) else Color.Unspecified
    }

    // tout est fermé, sur l'Accueil : il faut faire retour deux fois pour quitter (jamais par erreur)
    val backCtx = androidx.compose.ui.platform.LocalContext.current
    var exitArmedAt by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val safeExit = com.theo.remote.Features.flags.collectAsState().value.safeBack
    BackHandler(enabled = safeExit && sheet == Sheet.None && tab == Tab.Home && openPlaylist == null && details.isEmpty()) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - exitArmedAt < 2000) { exitArmedAt = 0L; (backCtx as? android.app.Activity)?.moveTaskToBack(true) }
        else { exitArmedAt = now; TidalRemote.showToast("Refais retour pour quitter Hush") }
    }
    BackHandler(enabled = sheet != Sheet.None) {
        sheet = if (sheet == Sheet.Lyrics) Sheet.Player else Sheet.None
    }
    BackHandler(enabled = sheet == Sheet.None && openPlaylist != null) { openPlaylist = null }
    BackHandler(enabled = sheet == Sheet.None && tab != Tab.Home && openPlaylist == null && details.isEmpty()) { tab = Tab.Home }
    BackHandler(enabled = sheet == Sheet.None && details.isNotEmpty()) { details = details.dropLast(1) }

    // l'arrière-plan recule un peu quand le lecteur s'ouvre (et revient pendant qu'on le glisse)
    val open = animateFloatAsState(
        if (sheet != Sheet.None) 1f else 0f,
        spring(dampingRatio = 1f, stiffness = 420f),
        label = "open",
    )
    // lu seulement au dessin (pas à chaque recomposition) pour rester fluide pendant le geste
    fun depth() = open.value * (1f - sheetDrag)

    CompositionLocalProvider(LocalOpenDetail provides openDetail) {
    Box(Modifier.fillMaxSize().background(Ink)) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val d = depth()
                    val sc = 1f - 0.06f * d
                    scaleX = sc
                    scaleY = sc
                    shape = RoundedCornerShape(28.dp.toPx() * d.coerceIn(0f, 1f))
                    clip = d > 0.01f
                },
        ) {
            Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
            // fond animé figé quand il est caché sous le lecteur, une playlist ou une page (économie de batterie)
            Ambient(colors, strength = 0.95f, themeable = true, animated = sheet == Sheet.None && openPlaylist == null && details.isEmpty() && Panels.open.collectAsState().value == Panels.Kind.None)
            TimeTint()

            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (fadeIn(tween(160, delayMillis = 20)) + slideInHorizontally(tween(220)) { it / 14 * dir }) togetherWith
                        (fadeOut(tween(100)) + slideOutHorizontally(tween(220)) { -it / 14 * dir })
                },
                label = "tabs",
            ) { t ->
                Box(Modifier.fillMaxSize()) {
                    if (t == Tab.Home) SeasonOverlay()
                    // chaque onglet garde sa position quand on y revient
                    tabStates.SaveableStateProvider(t.name) {
                    when (t) {
                        Tab.Home -> HomeScreen(
                            s,
                            onOpenPlayer = { sheetDrag = 0f; sheet = Sheet.Player },
                            onOpenPlaylist = { openPlaylist = it },
                        )
                        Tab.Search -> SearchScreen(s)
                        Tab.Library -> LibraryScreen(s, onOpen = { openPlaylist = it })
                        Tab.Stats -> StatsScreen()
                        Tab.Cards -> CardsScreen()
                    }
                    }
                }
            }

            // une playlist : fenêtre qui monte par-dessus, à fermer en glissant vers le bas
            AnimatedVisibility(
                visible = openPlaylist != null,
                // pochette qui s'envole depuis la grille : la page apparaît en fondu ; sinon elle monte
                enter = if (com.theo.remote.Features.flags.value.coverMorph && PlaylistAnchor.has(openPlaylist?.id)) fadeIn(tween(240))
                else slideInVertically(spring(dampingRatio = 0.86f, stiffness = 440f)) { it } + fadeIn(tween(180)),
                exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(220)),
            ) {
                val p = openPlaylist ?: shownPlaylist
                if (p != null) {
                    SwipeDismissible(onDismiss = { openPlaylist = null }, modifier = Modifier.fillMaxSize()) {
                        Ambient(rememberUrlCoverColors(p.coverUrl), strength = 0.95f, header = true)
                        PlaylistScreen(p, s, onBack = { openPlaylist = null })
                    }
                }
            }

            // pages artiste / album : montent par-dessus, à fermer en glissant vers le bas
            AnimatedContent(
                targetState = details,
                transitionSpec = {
                    if (targetState.size >= initialState.size) {
                        (slideInVertically(spring(dampingRatio = 0.86f, stiffness = 440f)) { it } + fadeIn(tween(180))) togetherWith
                            fadeOut(tween(250))
                    } else {
                        fadeIn(tween(200)) togetherWith (slideOutVertically(tween(260)) { it } + fadeOut(tween(220)))
                    }
                },
                label = "details",
            ) { stack ->
                val d = stack.lastOrNull()
                if (d == null) {
                    Box(Modifier.fillMaxSize())
                } else {
                    SwipeDismissible(onDismiss = { details = details.dropLast(1) }, modifier = Modifier.fillMaxSize()) {
                        if (d.isArtist) ArtistScreen(d, s, onBack = { details = details.dropLast(1) })
                        else AlbumScreen(d, s, onBack = { details = details.dropLast(1) })
                    }
                }
            }
            } // fin du contenu qui passe sous les barres

            // mini-lecteur + barre de navigation
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                val toast by TidalRemote.toast.collectAsState()
                var lastToast by remember { mutableStateOf("") }
                SideEffect { toast?.let { lastToast = it } }
                AnimatedVisibility(
                    visible = toast != null,
                    enter = slideInVertically(SoftSpringInt) { it / 2 } + fadeIn(),
                    exit = fadeOut(tween(200)) + slideOutVertically { it / 2 },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(
                        toast ?: lastToast,
                        color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(horizontal = 24.dp, vertical = 8.dp)
                            .clip(PillShape)
                            .background(Txt)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                // « Annuler » pendant 5 s après une action
                val undo by TidalRemote.undo.collectAsState()
                var lastUndo by remember { mutableStateOf("") }
                SideEffect { undo?.let { lastUndo = it.label } }
                AnimatedVisibility(
                    visible = undo != null,
                    enter = slideInVertically(SoftSpringInt) { it / 2 } + fadeIn(),
                    exit = fadeOut(tween(200)) + slideOutVertically { it / 2 },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    BottomBar(undo?.label ?: lastUndo, "Annuler") { TidalRemote.runUndo() }
                }
                // nouvelle version de Hush disponible
                val update by com.theo.remote.Updater.available.collectAsState()
                val dl by com.theo.remote.Updater.progress.collectAsState()
                val uctx = androidx.compose.ui.platform.LocalContext.current
                val uscope = rememberCoroutineScope()
                // vérifie à chaque retour dans l'appli (au plus toutes les 10 min)
                val ulife = (uctx as? androidx.lifecycle.LifecycleOwner)?.lifecycle
                DisposableEffect(ulife) {
                    val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                        if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) uscope.launch { com.theo.remote.Updater.check() }
                    }
                    ulife?.addObserver(obs)
                    if (ulife == null) uscope.launch { com.theo.remote.Updater.check() }
                    onDispose { ulife?.removeObserver(obs) }
                }
                AnimatedVisibility(
                    visible = update != null && undo == null,
                    enter = slideInVertically(SoftSpringInt) { it / 2 } + fadeIn(),
                    exit = fadeOut(tween(200)) + slideOutVertically { it / 2 },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    val u = update
                    BottomBar(
                        if (dl != null) "Téléchargement… ${((dl ?: 0f) * 100).toInt()} %" else "${u?.name ?: "Hush"} est dispo",
                        if (dl != null) null else "Installer",
                        onClose = { com.theo.remote.Updater.dismiss() },
                    ) { u?.let { r -> uscope.launch { com.theo.remote.Updater.install(uctx, r) } } }
                }
                RatePromptBubble()
                AnimatedVisibility(
                    visible = s.connected && s.track != null,
                    enter = slideInVertically(SoftSpringInt) { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                ) {
                    Column {
                        MiniPlayer(s, backdrop, onOpen = { sheetDrag = 0f; sheet = Sheet.Player })
                        Spacer(Modifier.height(8.dp))
                    }
                }
                BottomNav(
                    tab, backdrop,
                    onLong = { t ->
                        when (t) {
                            Tab.Search -> { tab = Tab.Search; openPlaylist = null; details = emptyList(); NavEvents.voice() }
                            Tab.Library -> if (com.theo.remote.Features.flags.value.localFiles) Panels.show(Panels.Kind.Local)
                            Tab.Stats, Tab.Cards -> Panels.show(Panels.Kind.Rewards)
                            Tab.Home -> Panels.show(Panels.Kind.Tools)
                        }
                    },
                ) {
                    // re-toucher l'onglet actif : on remonte tout en haut
                    if (it == tab && openPlaylist == null && details.isEmpty()) NavEvents.reselect(it.name)
                    tab = it; openPlaylist = null; details = emptyList()
                }
            }

            // assombrit l'arrière-plan quand le lecteur est ouvert
            Box(
                Modifier.fillMaxSize().drawBehind {
                    val d = depth()
                    if (d > 0.01f) drawRect(Color.Black.copy(alpha = 0.35f * d))
                },
            )
        }

        AnimatedVisibility(
            visible = sheet != Sheet.None,
            // la pochette grandit depuis le mini-lecteur (dans PlayerScreen) : le lecteur, lui, apparaît en fondu
            enter = if (CoverAnchor.mini != null) fadeIn(tween(240)) else slideInVertically(spring(dampingRatio = 0.86f, stiffness = 420f)) { it },
            exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(240)),
        ) {
            AnimatedContent(
                targetState = if (sheet == Sheet.Lyrics) Sheet.Lyrics else Sheet.Player,
                transitionSpec = {
                    (fadeIn(tween(170)) + scaleIn(tween(220), initialScale = 0.98f)) togetherWith
                        (fadeOut(tween(130)) + scaleOut(tween(220), targetScale = 1.01f))
                },
                label = "sheet",
            ) { which ->
                SwipeDismissible(
                    onDismiss = {
                        // on NE remet PAS sheetDrag à 0 ici : l'arrière-plan est déjà revenu en place,
                        // le remettre à 0 le ferait rétrécir puis rebondir (tremblement)
                        sheet = if (which == Sheet.Lyrics) Sheet.Player else Sheet.None
                    },
                    onProgress = { if (which == Sheet.Player) sheetDrag = it },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // le fond animé est aussi ce que les boutons en verre liquide du lecteur tordent,
                    // et tout le lecteur (fond + contenu) ce que tordent ses menus
                    val playerGlass = rememberBackdrop()
                    val liquidSheet = liquidOn()
                    Box(Modifier.fillMaxSize().backdropSource(sheetGlass)) {
                        Box(Modifier.fillMaxSize().backdropSource(playerGlass)) {
                            Ambient(colors, strength = 1f, animated = true)
                        }
                        if (which == Sheet.Lyrics) {
                            LyricsScreen(s, onQueue = { PlayerUi.openQueue.value = true; sheet = Sheet.Player }, onClose = { sheet = Sheet.Player })
                        } else {
                            androidx.compose.runtime.CompositionLocalProvider(
                                LocalPlayerGlass provides (if (liquidSheet) playerGlass else null),
                                LocalSheetGlass provides (if (liquidSheet) sheetGlass else null),
                            ) {
                                PlayerScreen(
                                    s,
                                    onClose = { sheet = Sheet.None },
                                    onLyrics = { sheet = Sheet.Lyrics },
                                )
                            }
                        }
                    }
                    if (which == Sheet.Player && liquidSheet) PlayerGlassOverlays(sheetGlass)
                }
            }
        }

        // fenêtres et menus : plaques de verre qui tordent la page dessous
        // lecteur ouvert : les fenêtres tordent le lecteur ; sinon la page en dessous
        androidx.compose.runtime.CompositionLocalProvider(LocalMainGlass provides (if (sheet != Sheet.None) sheetGlass else backdrop)) {
        // soirée / réglages / outils
        PanelHost()

        // menu d'un son (appui long)
        TrackMenuSheet(LocalMainGlass.current)
        // docteur de playlist (par-dessus la barre du bas et le mini-lecteur)
        PlaylistDoctorHost()
        // cartes de sons : une carte en grand, et l'annonce quand une carte monte
        CardsHost()
        InstantPlaylistHost()
        // pas de connexion : on le dit en haut
        val offCtx = androidx.compose.ui.platform.LocalContext.current
        LaunchedEffect(Unit) { Net.init(offCtx) }
        if (com.theo.remote.Features.flags.collectAsState().value.clearStates) Box(Modifier.fillMaxSize()) { Box(Modifier.align(Alignment.TopCenter)) { OfflineBanner() } }
        // vérification au premier lancement (et tant que l'indispensable manque)
        CleanupHost()
        WhatsNewHost()
        CrashPromptHost()
        V2WelcomeHost()
        SetupScreen()

        // ajout à une playlist (par-dessus tout)
        PlaylistPicker()
        FolderDialog()
        SharedLinkSheet()
        CapsuleWriteDialog()
        CapsuleRevealDialog()
        RateDialog()
        }
        PlaylistCoverDialog()
        WhereIsItDialog()

        // écoute d'album sans distraction (par-dessus tout)
        FocusAlbumHost(s)

        // mode voiture
        val car by CarMode.shown.collectAsState()
        AnimatedVisibility(
            visible = car,
            enter = fadeIn(tween(170)) + scaleIn(tween(210), initialScale = 1.03f),
            exit = fadeOut(tween(200)),
        ) {
            CarModeScreen(s)
        }
    }
    }
}

private val SoftSpringInt = spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.82f, stiffness = 380f)

/** Position de lecture mise à jour 2× par seconde. */
@Composable
fun rememberLivePosition(s: TidalRemote.State, intervalMs: Long = 250): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(s.advancing, s.positionUpdatedAt, intervalMs) {
        now = SystemClock.elapsedRealtime()
        if (s.advancing) while (true) {
            delay(intervalMs)
            now = SystemClock.elapsedRealtime()
        }
    }
    return s.livePosition(now)
}

// ---------------------------------------------------------------- mini-lecteur

@Composable
private fun MiniPlayer(s: TidalRemote.State, backdrop: Backdrop, onOpen: () -> Unit) {
    val t = s.track ?: return
    val pos = rememberLivePosition(s, intervalMs = 1000) // une petite barre : 1 fois par seconde suffit
    val progress = if (t.durationMs > 0) pos.toFloat() / t.durationMs else 0f
    val shape = Rounded(16)
    val cover = rememberCoverColors(t.art)
    val tintA by animateColorAsState(cover.a, SongChange.spec(), label = "miniA")
    val tintB by animateColorAsState(cover.b, SongChange.spec(), label = "miniB")

    val miniHaptic = rememberHaptic()
    val swipeHaptic = rememberHaptic(strong = true)
    // glisser à gauche / à droite : le contenu suit le doigt, puis le son suivant arrive de l'autre côté
    val slide = remember { androidx.compose.animation.core.Animatable(0f) }
    val miniScope = rememberCoroutineScope()
    var miniW by remember { androidx.compose.runtime.mutableIntStateOf(1) }
    // réponse immédiate : le son touché s'affiche tout de suite, sans attendre que Tidal le charge
    val pendingLaunch = s.launch?.takeIf { it.status == TidalRemote.LaunchStatus.Pending && com.theo.remote.Features.flags.value.instantUi }
    val shownTitle = pendingLaunch?.expected ?: t.title
    val (subtitle, subColor) = when (s.launch?.status) {
        TidalRemote.LaunchStatus.Pending -> (pendingLaunch?.artist?.takeIf { it.isNotBlank() }?.let { "$it · lancement…" } ?: "Lancement…") to Txt2
        TidalRemote.LaunchStatus.OtherVersion -> "Autre version lancée · touche pour réessayer" to Warn
        TidalRemote.LaunchStatus.NoChange -> "Tidal n'a pas changé de titre" to Ko
        else -> t.artist to Txt2
    }

    Box(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .height(64.dp)
            .shadow(18.dp, shape, ambientColor = tintA, spotColor = tintA)
            .clip(shape)
            .onSizeChanged { miniW = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                // glisser : gauche = suivant, droite = précédent, haut = grand lecteur
                var dx = 0f; var dy = 0f
                var horizontal: Boolean? = null
                var armed = false
                detectDragGestures(
                    onDragStart = { dx = 0f; dy = 0f; horizontal = null; armed = false },
                    onDragCancel = { miniScope.launch { slide.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 500f)) } },
                    onDragEnd = {
                        val w = miniW.toFloat()
                        when {
                            horizontal != true && dy < -60f && kotlin.math.abs(dy) > kotlin.math.abs(dx) -> onOpen()
                            horizontal == true && kotlin.math.abs(dx) > 90f -> miniScope.launch {
                                val toNext = dx < 0
                                slide.animateTo(if (toNext) -w else w, tween(150))
                                if (toNext) TidalRemote.next("glissé sur le mini-lecteur") else TidalRemote.previous()
                                slide.snapTo(if (toNext) w * 0.5f else -w * 0.5f)
                                slide.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 320f))
                            }
                            else -> miniScope.launch { slide.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 500f)) }
                        }
                    },
                ) { change, drag ->
                    change.consume()
                    dx += drag.x; dy += drag.y
                    if (horizontal == null && (kotlin.math.abs(dx) > 12f || kotlin.math.abs(dy) > 12f)) horizontal = kotlin.math.abs(dx) > kotlin.math.abs(dy)
                    if (horizontal == true) {
                        miniScope.launch { slide.snapTo(dx * 0.85f) }
                        val a = kotlin.math.abs(dx) > 90f
                        if (a != armed) { armed = a; if (a) swipeHaptic() }
                    }
                }
            }
            .bounceClick(pressedScale = 0.98f, onClick = onOpen),
    ) {
        // flou de ce qui passe dessous, déformé sur les coins
        BackdropBlur(backdrop, shape, cornerRadius = 16.dp, modifier = Modifier.matchParentSize())
        // teinte aux couleurs de la pochette (comme « En ce moment »)
        Box(
            Modifier
                .matchParentSize()
                .then(
                    if (com.theo.remote.Features.flags.value.flatStyle)
                        // style Pochette : la couleur du son en aplat
                        Modifier.background(androidx.compose.ui.graphics.lerp(tintA, Color.Black, 0.32f))
                    // verre liquide : juste une touche de couleur, le verre reste clair
                    else if (com.theo.remote.Features.flags.value.liquidGlass && !com.theo.remote.Features.flags.value.eco && android.os.Build.VERSION.SDK_INT >= 33) Modifier
                        .background(Brush.linearGradient(listOf(tintA.copy(alpha = 0.16f), tintB.copy(alpha = 0.22f))))
                        .background(Color.Black.copy(alpha = 0.10f))
                    else Modifier
                        .background(Brush.linearGradient(listOf(tintA.copy(alpha = 0.55f), tintB.copy(alpha = 0.65f))))
                        .background(Color.Black.copy(alpha = 0.22f))
                        .glass(shape, fill = 0.05f, edge = 0.14f),
                ),
        )
        Row(
            Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // seuls la pochette et le titre suivent le doigt ; les boutons restent en place
            Row(
                Modifier.weight(1f).clipToBounds().graphicsLayer {
                    translationX = slide.value
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
            // le son d'à côté arrive sous ton doigt pendant que tu glisses
            val slideDir by remember { androidx.compose.runtime.derivedStateOf { kotlin.math.sign(slide.value) } }
            val peek = remember(slideDir, t.title, s.queue, s.activeQueueId) {
                if (slideDir == 0f) null else TidalRemote.peekAdjacent(next = slideDir < 0)
            }
            Box(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth().graphicsLayer {
                    alpha = 1f - (kotlin.math.abs(slide.value) / miniW.toFloat()).coerceIn(0f, if (peek != null) 0.85f else 0.7f)
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
            Cover(
                bitmap = t.art, radius = 8.dp,
                // on retient où est la pochette du mini-lecteur : le lecteur part d'ici en s'ouvrant
                modifier = Modifier.size(48.dp).onGloballyPositioned { CoverAnchor.mini = it.boundsInWindow() },
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = shownTitle,
                    transitionSpec = { (slideInVertically(tween(220)) { it } + fadeIn(tween(220))) togetherWith (slideOutVertically(tween(180)) { -it } + fadeOut(tween(180))) },
                    label = "miniTitle",
                ) { title ->
                    Text(title, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    subtitle, color = subColor, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    // mauvaise version : un tap relance la bonne
                    modifier = if (s.launch?.status == TidalRemote.LaunchStatus.OtherVersion) Modifier.bounceClick { TidalRemote.retryVersion() } else Modifier,
                )
            }
            
            }
            if (peek != null) Row(
                Modifier.fillMaxWidth().graphicsLayer {
                    val w = size.width + 8.dp.toPx()
                    translationX = if (slide.value < 0) w else -w
                    alpha = (kotlin.math.abs(slide.value) / miniW.toFloat() * 2.2f).coerceIn(0f, 1f)
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(model = peek.cover, radius = 8.dp, modifier = Modifier.size(48.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(peek.title, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(peek.artist, color = Txt2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            }
}
            Box(
                Modifier.size(48.dp).clip(PillShape).bounceClick(pressedScale = 0.8f) { miniHaptic(); TidalRemote.playPause() },
                contentAlignment = Alignment.Center,
            ) {
                Crossfade(s.isPlaying, label = "miniPlay") { playing ->
                    Icon(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Lecture",
                        tint = Txt,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Box(
                Modifier.size(44.dp).clip(PillShape).bounceClick(pressedScale = 0.8f) { miniHaptic(); TidalRemote.next() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Suivant", tint = Txt, modifier = Modifier.size(26.dp))
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .height(2.dp)
                .background(Color.White.copy(alpha = 0.15f)),
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Txt))
        }
    }
}

// ---------------------------------------------------------------- navigation

@Composable
private fun BottomNav(tab: Tab, backdrop: Backdrop, onLong: (Tab) -> Unit = {}, onTab: (Tab) -> Unit) {
    val haptic = rememberHaptic()
    val tabs = listOf(
        Tab.Home to (Icons.Rounded.Home to "Accueil"),
        Tab.Search to (Icons.Rounded.Search to "Recherche"),
        Tab.Library to (LibraryIcon to "Biblio"),
    ) + run {
        val nf = com.theo.remote.Features.flags.collectAsState().value
        // mode simple : 3 onglets seulement
        if (nf.simpleMode || nf.threeTabs) emptyList()
        else listOf(if (nf.songCards) Tab.Cards to (Icons.Rounded.Style to "Cartes") else Tab.Stats to (Icons.Rounded.BarChart to "Stats"))
    }
    val shape = PillShape
    val tint = CoverTint.current()
    // récompense « 5e onglet » : un raccourci de plus au bout de la barre
    com.theo.remote.Rewards.equipped.collectAsState().value
    val navFlags by com.theo.remote.Features.flags.collectAsState()
    val fifth = navFlags.rewards && com.theo.remote.Rewards.isOn("tab:fifth")
    val slots = tabs.size + if (fifth) 1 else 0
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 10.dp),
    ) {
        // capsule en verre flottante
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(66.dp)
                .shadow(20.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape),
        ) {
            // flou de ce qui passe dessous + légère déformation sur les bords arrondis
            BackdropBlur(backdrop, shape, cornerRadius = 33.dp, modifier = Modifier.matchParentSize())
            val liquid = navFlags.liquidGlass && !navFlags.eco && android.os.Build.VERSION.SDK_INT >= 33
            // verre liquide : juste un léger voile pour lire les icônes ; sinon le verre habituel
            if (liquid) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.12f)))
            else Box(Modifier.matchParentSize().background(Ink.copy(alpha = 0.32f)).glass(shape, fill = 0.16f, edge = 0.14f))
            val fullW = maxWidth
            Box(Modifier.fillMaxSize().padding(6.dp)) {
            val itemW = (fullW - 12.dp) / slots
            val index = tabs.indexOfFirst { it.first == tab }.coerceAtLeast(0)
            val target = itemW * index
            if (liquid) {
                // verre liquide : la pastille est une goutte-loupe qui grossit ce qui est dessous,
                // s'étire pendant qu'elle glisse et rebondit un peu en arrivant
                val x by animateDpAsState(target, spring(dampingRatio = 0.62f, stiffness = 380f), label = "pill")
                val stretch = (kotlin.math.abs((target - x).value) / itemW.value).coerceIn(0f, 1f)
                val w = itemW * (1f + 0.45f * stretch)
                val hScale = 1f - 0.18f * stretch
                Box(
                    Modifier
                        .offset(x = x - (w - itemW) / 2)
                        .width(w)
                        .fillMaxHeight()
                        .graphicsLayer { scaleY = hScale },
                ) {
                    LiquidGlass(
                        backdrop, shape, cornerRadius = 27.dp, modifier = Modifier.matchParentSize(),
                        blur = 0.dp, depth = 14.dp, refraction = 9.dp, chroma = 0.3f, zoom = 1.22f, spec = 1.2f,
                    )
                    Box(Modifier.matchParentSize().clip(shape).background((tint ?: Color.White).copy(alpha = 0.10f)))
                }
            } else {
                // la pastille glisse simplement, sans rebond ni déformation
                val x by animateDpAsState(target, spring(dampingRatio = 1f, stiffness = 520f), label = "pill")
                Box(
                    Modifier
                        .offset(x = x)
                        .width(itemW)
                        .fillMaxHeight()
                        .clip(shape)
                        .background(tint?.copy(alpha = 0.22f) ?: Color.White.copy(alpha = 0.16f)),
                )
            }

            Row(Modifier.fillMaxSize()) {
                tabs.forEach { (t, iconLabel) ->
                    val active = t == tab
                    val c by animateColorAsState(if (active) (tint ?: Txt) else Txt2, tween(220), label = "navTint")
                    val sc by animateFloatAsState(if (active) 1.1f else 1f, SnappySpring, label = "navScale")
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(shape)
                            .bounceClick(pressedScale = 0.85f, onLongClick = { onLong(t) }) { if (t != tab) haptic(); onTab(t) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            iconLabel.first,
                            contentDescription = iconLabel.second,
                            tint = c,
                            modifier = Modifier.size(27.dp).graphicsLayer { scaleX = sc; scaleY = sc },
                        )
                    }
                }
                if (fifth) {
                    val (icon, label, kind) = when (com.theo.remote.Rewards.fifthTab()) {
                        "blind" -> Triple(Icons.Rounded.Quiz, "Blind test", Panels.Kind.BlindTest)
                        "tools" -> Triple(Icons.Rounded.Build, "Outils", Panels.Kind.Tools)
                        else -> Triple(Icons.Rounded.Folder, "Fichiers locaux", Panels.Kind.Local)
                    }
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(shape)
                            .bounceClick(pressedScale = 0.85f) { haptic(); Panels.show(kind) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(icon, contentDescription = label, tint = Txt2, modifier = Modifier.size(25.dp)) }
                }
            }
            } // fin de la zone intérieure (marge de 6 dp)
        }
    }
}

// ---------------------------------------------------------------- états « pas prêt »

@Composable
fun SetupCard(s: TidalRemote.State) {
    val ctx = LocalContext.current
    if (s.hasPermission && s.connected) return
    val shape = Rounded(22)
    Column(Modifier.fillMaxWidth().glass(shape).padding(18.dp)) {
        if (!s.hasPermission) {
            Text("Une autorisation à donner", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Pour piloter Tidal, Android demande l'« accès aux notifications ». Active Hush dans la liste, puis reviens.",
                color = Txt2, fontSize = 13.sp, lineHeight = 20.sp,
            )
            Spacer(Modifier.height(16.dp))
            PillButton("Ouvrir les réglages") {
                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } else {
            Text("Tidal ne joue rien", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Lance un son dans Tidal une fois (même en pause ensuite) : Hush s'y branche tout seul.",
                color = Txt2, fontSize = 13.sp, lineHeight = 20.sp,
            )
            Spacer(Modifier.height(16.dp))
            PillButton("Ouvrir Tidal") { TidalRemote.openTidal(ctx) }
        }
    }
}

@Composable
fun PillButton(label: String, filled: Boolean = true, onClick: () -> Unit) {
    val shape = BtnShape
    val tint = CoverTint.current()
    Box(
        Modifier
            .clip(shape)
            .background(if (filled) (tint ?: Txt) else Color.White.copy(alpha = 0.08f))
            .bounceClick(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
    ) {
        Text(label, color = if (filled) Ink else Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}


/** Lecture/pause « stabilisée » : ignore les micro-pauses de Tidal (chargement, changement de son). */
@Composable
fun rememberSteadyPlaying(playing: Boolean): Boolean {
    var shown by remember { mutableStateOf(playing) }
    LaunchedEffect(playing) {
        if (!playing) delay(250)
        shown = playing
    }
    return shown
}


/** Icône Biblio au trait : deux tranches droites + une penchée (comme des disques rangés). */
private val LibraryIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "HushLibrary",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        val stroke = androidx.compose.ui.graphics.SolidColor(Color.White)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = addPath(
            pathData = androidx.compose.ui.graphics.vector.PathParser().parsePathString("M$x1,${y1}L$x2,$y2").toNodes(),
            stroke = stroke,
            strokeLineWidth = 2.2f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        line(5f, 4f, 5f, 20f)
        line(9.5f, 4f, 9.5f, 20f)
        line(14f, 4.6f, 19.2f, 19.4f)
    }.build()
}


/** Thème selon l'heure : un voile de couleur discret (aube rosée, soir doré, nuit bleutée). */
@Composable
fun TimeTint() {
    val f by com.theo.remote.Features.flags.collectAsState()
    var hour by remember { mutableStateOf(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        }
    }
    val target = when {
        !f.timeTheme -> Color.Transparent
        hour in 6..8 -> Color(0xFFFF9A8B).copy(alpha = 0.08f)   // matin
        hour in 17..20 -> Color(0xFFFF8A3D).copy(alpha = 0.10f) // fin de journée
        hour >= 21 || hour < 6 -> Color(0xFF0A1440).copy(alpha = 0.32f) // nuit : plus sombre et bleuté
        else -> Color.Transparent
    }
    val c by androidx.compose.animation.animateColorAsState(target, tween(1500), label = "timeTint")
    if (c.alpha > 0.005f) Box(Modifier.fillMaxSize().background(c))
}

/** Où se trouve la pochette du mini-lecteur à l'écran (point de départ de l'ouverture du lecteur). */
object CoverAnchor {
    var mini: androidx.compose.ui.geometry.Rect? = null
}

/** Bandeau du bas (Annuler, mise à jour) : un texte et un bouton. */
@Composable
private fun BottomBar(text: String, action: String?, onClose: (() -> Unit)? = null, onAction: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(PillShape)
            .background(Txt)
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (action != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                action, color = Txt, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.clip(PillShape).background(Ink).clickable(onClick = onAction).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (onClose != null) {
            Spacer(Modifier.width(4.dp))
            Text(
                "Plus tard", color = Ink.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(PillShape).clickable(onClick = onClose).padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
