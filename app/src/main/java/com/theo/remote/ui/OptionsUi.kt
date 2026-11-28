package com.theo.remote.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.horizontalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.Backup
import com.theo.remote.Features
import com.theo.remote.TidalRemote
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Position de chaque thème dans la page des Réglages (pour y sauter depuis la recherche). */
val LocalSettingsAnchors = androidx.compose.runtime.staticCompositionLocalOf<MutableMap<String, Int>?> { null }

/** Titre d'un thème dans les Réglages. */
@Composable
fun SettingsHeader(title: String) {
    val anchors = LocalSettingsAnchors.current
    Text(
        title, color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold,
        modifier = Modifier.padding(top = Space.m, start = 4.dp)
            .then(if (anchors != null) Modifier.onGloballyPositioned { anchors[title] = it.positionInParent().y.toInt() } else Modifier),
    )
}

/** Réglages allégés : les options secondaires d'une carte, repliées sous « N options de plus ». */
private class CardFold {
    val shown = mutableStateOf(false)
    val hidden = androidx.compose.runtime.mutableStateMapOf<String, Unit>()
}
private val LocalCardFold = androidx.compose.runtime.compositionLocalOf<CardFold?> { null }

/** Les réglages toujours visibles (le reste est replié tant que tu n'y as jamais touché). */
private val ESSENTIAL = setOf(
    "Style Pochette", "Mode économie", "Noir pur (AMOLED)", "Couleur adaptative", "Retour haptique",
    "Reprendre où j'en étais", "Ouvrir Tidal avec Hush", "Fichiers locaux", "Aléatoire malin",
    "Enchaînement sans blanc", "Corriger les mauvaises versions", "Reprise exacte des playlists",
    "Carte d'identité musicale", "Wrapped de l'année", "Mode invité",
    "Paroles traduites", "Recherche vocale",
    "Notification enrichie", "Masquer le lecteur Tidal", "Message au futur toi", "Mes notes", "Tes notes partout", "Sons à noter", "Mode découverte", "Pochettes de playlists perso", "Écoute sans distraction", "Panneau latéral", "Widget playlists", "Raccourcis sur l'icône", "Où est ce son ?", "Profils par appareil", "Widget carré",
    "Sauvegarde auto", "Suivre des artistes",
)

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    val flat = Features.flags.collectAsState().value.flatStyle
    val fold = remember { CardFold() }
    Column(
        // style Pochette : une simple liste sur le fond, séparée par des traits fins
        if (flat) Modifier.fillMaxWidth().padding(horizontal = 4.dp)
        else Modifier.fillMaxWidth().glass(Rounded(22), fill = 0.07f, edge = 0.12f).padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(if (flat) 10.dp else Space.m),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalCardFold provides fold) { content() }
        val n = fold.hidden.size
        if (n > 0 || fold.shown.value) {
            Text(
                if (fold.shown.value) "Moins d'options" else "$n option${if (n > 1) "s" else ""} de plus",
                color = Txt2, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).bounceClick { fold.shown.value = !fold.shown.value }.padding(vertical = 4.dp),
            )
        }
    }
}

/** Mode simple : en haut des Réglages, bien visible. */
@Composable
fun SimpleModeCard() {
    val f by Features.flags.collectAsState()
    SettingsCard {
        OptionRow(
            "Mode simple",
            if (f.simpleMode) "Juste l'essentiel : accueil, recherche, biblio et lecteur. Coupe-le pour retrouver toutes les fonctions"
            else "Cache les fonctions avancées (cartes, stats, défis…). Tes réglages actuels sont gardés et reviennent quand tu le coupes",
            f.simpleMode,
        ) { Features.setSimple(it) }
    }
}

/** Version installée + bouton pour chercher une mise à jour. */
@Composable
fun UpdateCard() {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val update by com.theo.remote.Updater.available.collectAsState()
    val dl by com.theo.remote.Updater.progress.collectAsState()
    SettingsCard {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Hush ${com.theo.remote.BuildConfig.VERSION_NAME}", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        dl != null -> "Téléchargement… ${((dl ?: 0f) * 100).toInt()} %"
                        update != null -> "${update?.name} est dispo"
                        else -> "Touche pour chercher une nouvelle version"
                    },
                    color = Txt2, fontSize = 13.sp,
                )
            }
            PillButton(if (update != null) "Installer" else "Vérifier") {
                val u = update
                scope.launch { if (u != null) com.theo.remote.Updater.install(ctx, u) else com.theo.remote.Updater.check(force = true) }
            }
        }
    }
}

/** Affichage : thème, pochette animée, économie. */
@Composable
fun DisplayCard() {
    val f by Features.flags.collectAsState()
    val ctx = LocalContext.current
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val micOk = ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
    SettingsCard {
        OptionRow("Thème selon l'heure", "Couleurs plus chaudes le soir, plus sombres la nuit", f.timeTheme) { Features.set(f.copy(timeTheme = it)) }
        OptionRow("Vrai verre liquide", "Barre du bas et mini-lecteur en verre bombé qui tord ce qui passe dessous, avec reflets (Android 13+)", f.liquidGlass) { Features.set(f.copy(liquidGlass = it)) }
        OptionRow("Accueil selon le moment", "L'ordre des sections de l'accueil change : nouveautés et sons calmes le matin, mix et 5 étoiles le soir", f.smartHome) { Features.set(f.copy(smartHome = it)) }
        OptionRow("Panneaux en verre", "Outils, Réglages et autres pages : une grande plaque de verre flottante au lieu d'un plein écran", f.glassPanels) { Features.set(f.copy(glassPanels = it)) }
        OptionRow("Pochette qui s'envole", "En ouvrant une playlist, sa pochette grandit depuis la grille jusqu'en haut de la page", f.coverMorph) { Features.set(f.copy(coverMorph = it)) }
        OptionRow("Cartes de sons", "Onglet Cartes à la place de Stats : tes sons classés Bronze, Argent, Or, Diamant selon tes écoutes. Les stats passent en bouton dans la Bibliothèque", f.songCards) { Features.set(f.copy(songCards = it)) }
        OptionRow("Annonce quand une carte monte", "Un écran « Nouvelle carte » quand un son passe au rang du dessus (seulement quand Hush est ouvert)", f.cardPromo) { Features.set(f.copy(cardPromo = it)) }
        OptionRow("Cartes qui brillent", "Le reflet des cartes Diamant et Or suit l'inclinaison du téléphone ; en grand, la carte penche sous ton doigt", f.cardHolo) { Features.set(f.copy(cardHolo = it)) }
        OptionRow("Cartes rares", "Édition spéciale avec cadre animé pour ton son n°1 du mois et les sons écoutés 100 fois", f.cardSpecial) { Features.set(f.copy(cardSpecial = it)) }
        OptionRow("Cartes en danger", "Un badge sur les cartes qui vont bientôt redescendre, avec un bouton pour les relancer", f.cardDanger) { Features.set(f.copy(cardDanger = it)) }
        OptionRow("Rang des sons partout", "Un petit losange de la couleur du rang à côté du titre dans le lecteur et dans les listes", f.cardBadges) { Features.set(f.copy(cardBadges = it)) }
        OptionRow("File en pochettes", "La file d'attente en grandes pochettes : maintiens une pochette pour la déplacer", f.queueCovers) { Features.set(f.copy(queueCovers = it)) }
        OptionRow("Playlist instantanée", "Dans la Biblio : écris « rap FR calme pour la nuit » et Hush fait la playlist avec tes favoris", f.instantPlaylist) { Features.set(f.copy(instantPlaylist = it)) }
        OptionRow("Réponse immédiate", "Pause et ajout à une playlist changent tout de suite à l'écran, Tidal suit derrière", f.instantUi) { Features.set(f.copy(instantUi = it)) }
        OptionRow("Affichage instantané", "Les écrans apparaissent d'un coup (sans cascade) et tes playlists s'affichent depuis le téléphone avant la mise à jour", f.homeCache) { Features.set(f.copy(homeCache = it)) }
        OptionRow("Bulles d'aide", "Explique une fois les gestes cachés (glisser la pochette, appui long…)", f.gestureHints) { Features.set(f.copy(gestureHints = it)); if (it) Hints.reset(ctx) }
        OptionRow("Messages quand ça bloque", "Bandeau « Pas de connexion » en haut quand internet coupe", f.clearStates) { Features.set(f.copy(clearStates = it)) }
        OptionRow("Vérification au démarrage", "Au premier lancement, un écran vérifie Tidal, l'accès aux notifs et la batterie", f.setupCheck) { Features.set(f.copy(setupCheck = it)) }
        OptionRow("Mises à jour", "Hush te propose d'installer la nouvelle version dès qu'elle sort (vérifié à l'ouverture)", f.autoUpdate) { Features.set(f.copy(autoUpdate = it)); if (it) kotlinx.coroutines.MainScope().launch { com.theo.remote.Updater.check(force = true) } }
        OptionRow("Bouton Annuler", "5 s pour revenir en arrière après avoir retiré un son, vidé la file ou changé l'ordre", f.undoBar) { Features.set(f.copy(undoBar = it)) }
        OptionRow("Lancer le bon son", "Lance tes playlists et tes sons par leur lien exact, pas par une recherche (évite qu'un autre son ou une autre playlist démarre)", f.exactLaunch) { Features.set(f.copy(exactLaunch = it)) }
        OptionRow("Recherche maline", "Tes sons déjà écoutés apparaissent dès la première lettre, et les fautes de frappe sont corrigées (« drak » → Drake)", f.smartSearch) { Features.set(f.copy(smartSearch = it)) }
        OptionRow("Bloquer les sauts automatiques", "Écran éteint, si des « suivant » arrivent en rafale (3 d'affilée en moins de 20 s chacun), Hush les ignore une minute", f.ghostGuard) { Features.set(f.copy(ghostGuard = it)) }
        OptionRow("Playlists enchaînées par Hush", "« Lire » démarre tout de suite et tu peux réordonner ou retirer chaque son de la suite", f.hushChain) { Features.set(f.copy(hushChain = it)) }
        OptionRow("Écrans allégés", "Accueil et lecteur sans les sections et petits textes secondaires (date, album, cartes…)", f.lightUi) { Features.set(f.copy(lightUi = it)) }
        OptionRow("Playlists gardées sur le téléphone", "En Wi-Fi et en charge, Hush garde tes playlists en entier : tout s'ouvre et se mélange direct, même hors connexion", f.preloadPlaylists) { Features.set(f.copy(preloadPlaylists = it)) }
        OptionRow("Mises à jour prêtes d'avance", "En Wi-Fi, la nouvelle version se télécharge en fond : « Installer » est immédiat", f.prefetchUpdate) { Features.set(f.copy(prefetchUpdate = it)) }
        OptionRow("Retour sans fausse sortie", "Sur l'Accueil, il faut faire retour deux fois pour quitter Hush", f.safeBack) { Features.set(f.copy(safeBack = it)) }
        OptionRow("Économie de données", "En 4G/5G, les vignettes sont chargées en plus petit et les pochettes vidéo attendent le Wi-Fi", f.dataSaver) { Features.set(f.copy(dataSaver = it)) }
        OptionRow("Rapport après un plantage", "Si Hush plante, il te propose au lancement suivant de copier le rapport en un tap", f.crashPrompt) { Features.set(f.copy(crashPrompt = it)) }
        OptionRow("Économie de batterie", "Écran éteint, Hush surveille Tidal moins souvent (sauf en fin de son, pour les enchaînements)", f.batterySaver) { Features.set(f.copy(batterySaver = it)) }
        OptionRow("Playlist sans intrus", "Quand tu lances une playlist, la file « À suivre » est vidée (annulable), pour que rien d'autre ne s'y glisse", f.freshQueue) { Features.set(f.copy(freshQueue = it)) }
        OptionRow("Nettoyage mensuel", "Une fois par mois, Hush te propose de retirer les sons que tu passes toujours dans une playlist", f.playlistCleanup) { Features.set(f.copy(playlistCleanup = it)) }
        OptionRow("Pas de doublons dans les playlists", "Un son déjà dans une playlist n'y est pas ajouté une 2e fois", f.noDuplicates) { Features.set(f.copy(noDuplicates = it)) }
        OptionRow("Alerte sons coupés", "Une petite notif quand un son passe au suivant avant la fin, avec qui l'a passé", f.earlyCutAlert) { Features.set(f.copy(earlyCutAlert = it)) }
        OptionRow("3 onglets seulement", "Accueil, Recherche et Biblio en bas ; Stats et Cartes passent en boutons en haut de la Biblio", f.threeTabs) { Features.set(f.copy(threeTabs = it)) }
        OptionRow("Formes nettes", "Coins moins arrondis sur les cartes et les boutons, les boutons ronds restent ronds (relance l'appli pour tout voir)", f.crispShapes) { Features.set(f.copy(crispShapes = it)) }
        OptionRow("Recherche remise à zéro", "Quand tu changes d'onglet, la recherche s'efface : en revenant, tu retrouves ton historique", f.searchReset) { Features.set(f.copy(searchReset = it)) }
        OptionRow("Lecteur en verre", "Pochette bord à bord, grand panneau de verre, boutons en gouttes et paroles en direct", f.glassPlayer) { Features.set(f.copy(glassPlayer = it)) }
        OptionRow("Tube de verre", "Dans le lecteur en verre, la progression est un tube rempli de liquide (sinon la barre ondulée)", f.glassTube) { Features.set(f.copy(glassTube = it)) }
        OptionRow("Barre ondulée", "Dans le lecteur, la partie déjà écoutée ondule pendant la lecture et s'aplatit à la pause", f.wavyBar) { Features.set(f.copy(wavyBar = it)) }
        OptionRow("Appareil de lecture", "Dans le lecteur : sur quoi joue Tidal (téléphone, écouteurs, enceinte) ; touche pour changer", f.deviceChip) { Features.set(f.copy(deviceChip = it)) }
        OptionRow("Gestes du lecteur", "Deux doigts vers le haut ou le bas : volume. Deux touches à gauche ou à droite de la pochette : -10 s / +10 s", f.playerGestures) { Features.set(f.copy(playerGestures = it)) }
        OptionRow("Double-tap pour liker", "Touche deux fois la pochette du lecteur : un gros cœur et le son passe en favori", f.doubleTapLike) { Features.set(f.copy(doubleTapLike = it)) }
        OptionRow("Changement de son en douceur", "La pochette glisse et le fond change de couleur en fondu à chaque nouveau son", f.smoothChange) { Features.set(f.copy(smoothChange = it)) }
        OptionRow("Pochette animée", "Elle respire au rythme du son dans le lecteur", f.animatedCover) { Features.set(f.copy(animatedCover = it)) }
        OptionRow("Pochettes vidéo", "Si l'album a une pochette animée sur Tidal, elle tourne dans le lecteur (sans son). Coupée en mode économie", f.videoCovers) { Features.set(f.copy(videoCovers = it)) }
        if (f.animatedCover && !micOk) {
            Text(
                "Pour suivre le vrai rythme, Hush doit écouter le son qui sort du téléphone (autorisation « micro », rien n'est enregistré). Sinon, respiration douce.",
                color = Txt3, fontSize = 12.sp, lineHeight = 17.sp,
            )
            PillButton("Autoriser", filled = false) { mic.launch(android.Manifest.permission.RECORD_AUDIO) }
        }
        OptionRow("Mode économie", "Coupe le flou, les fonds animés et la pochette animée", f.eco) { Features.set(f.copy(eco = it)) }
        OptionRow("Couleur adaptative", "Les onglets et les boutons prennent la couleur de la pochette en cours", f.adaptiveColor) { Features.set(f.copy(adaptiveColor = it)) }
        OptionRow("Pochette en plein écran", "Toucher la pochette dans le lecteur pour l'afficher en grand", f.fullCover) { Features.set(f.copy(fullCover = it)) }
        OptionRow("Accueil compact", "Cartes plus petites, plus de sons d'un coup", f.compactHome) { Features.set(f.copy(compactHome = it)) }
        OptionRow("« Il y a un an »", "Sur l'accueil : ce que tu écoutais il y a un an", f.yearAgo) { Features.set(f.copy(yearAgo = it)) }
        OptionRow("Son caché du jour", "Sur l'accueil : un son d'un de tes artistes jamais écouté", f.hiddenGem) { Features.set(f.copy(hiddenGem = it)) }
        OptionRow("Discographie complète", "Sur la page d'un artiste : le % de ses sons que tu as écoutés", f.discography) { Features.set(f.copy(discography = it)) }
        OptionRow("Noir pur (AMOLED)", "Fond 100 % noir : plus joli sur ton écran et plus économe", f.amoled) { Features.set(f.copy(amoled = it)) }
        OptionRow("Retour haptique", "Une petite vibration sur chaque bouton", f.haptics) { Features.set(f.copy(haptics = it)) }
        OptionRow("Style Pochette (ancien)", "Fond noir uni, la couleur vient de la pochette en cours. Désactivé : le nouveau design, en relief et en verre", f.flatStyle) { Features.set(f.copy(flatStyle = it)) }
        OptionRow("Mode gaucher", "Cœur, menu et boutons du lecteur passent à gauche", f.leftHanded) { Features.set(f.copy(leftHanded = it)) }
        OptionRow("Thèmes de saison", "Halloween, Noël, été : couleurs et petites animations sur l'accueil", f.seasonal) { Features.set(f.copy(seasonal = it)) }
        if (f.seasonal) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to "Auto", "halloween" to "Halloween", "noel" to "Noël", "ete" to "Été").forEach { (v, l) ->
                val on = f.season == v
                val shape = PillShape
                Box(
                    Modifier.clip(shape)
                        .then(if (on) Modifier.background(Txt) else Modifier.glass(shape, fill = 0.08f, edge = 0.14f))
                        .bounceClick { Features.set(f.copy(season = v)) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text(l, color = if (on) Ink else Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
            }
        }
        OptionRow("N°1 en France il y a 5 ans", "Sur l'accueil : le tube de la même semaine, 5 ans plus tôt", f.chartsAgo) { Features.set(f.copy(chartsAgo = it)) }
        OptionRow("« Quoi de neuf »", "Après une mise à jour : la liste des nouveautés", f.whatsNew) { Features.set(f.copy(whatsNew = it)) }
        Text("Taille du texte", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(90 to "Petit", 100 to "Normal", 110 to "Grand", 125 to "Très grand").forEach { (v, l) ->
                val on = f.textScale == v
                val shape = PillShape
                Box(
                    Modifier.clip(shape)
                        .then(if (on) Modifier.background(Txt) else Modifier.glass(shape, fill = 0.08f, edge = 0.14f))
                        .bounceClick { Features.set(f.copy(textScale = v)) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) { Text(l, color = if (on) Ink else Txt, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
            }
        }
        Text("Couleur d'accent", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            com.theo.remote.Accent.presets.forEach { (id, argb) ->
                val reward = com.theo.remote.Rewards.UNLOCKS.firstOrNull { it.id == "accent:$id" }
                val locked = reward != null && !com.theo.remote.Rewards.unlocked(reward.id)
                val on = f.accent == id
                val shape = PillShape
                androidx.compose.foundation.layout.Box(
                    Modifier.then(if (locked) Modifier.alpha(0.25f) else Modifier).size(if (on) 38.dp else 32.dp).clip(shape)
                        .background(
                            if (id == "cover") androidx.compose.ui.graphics.Brush.sweepGradient(listOf(Color(0xFFF472B6), Color(0xFF60A5FA), Color(0xFF5BE3A2), Color(0xFFFB923C), Color(0xFFF472B6)))
                            else androidx.compose.ui.graphics.SolidColor(Color(argb)),
                        )
                        .then(if (on) Modifier.border(2.dp, Txt, shape) else Modifier)
                        .bounceClick(pressedScale = 0.85f) {
                            if (locked) TidalRemote.showToast("Débloqué au niveau ${reward?.level}") else Features.set(f.copy(accent = id))
                        },
                )
            }
        }
        Text(if (f.accent == "cover") "Couleur de la pochette du son en cours" else "S'applique aux interrupteurs, barres et éléments actifs", color = Txt3, fontSize = 12.sp)
    }
}

/** Lecture : reprendre, anti-répétition. */
@Composable
fun PlaybackOptionsCard() {
    val f by Features.flags.collectAsState()
    SettingsCard {
        OptionRow("Reprendre où j'en étais", "Sur l'accueil : relancer ton dernier son au bon moment", f.resumeLast) { Features.set(f.copy(resumeLast = it)) }
        OptionRow("Ouvrir Tidal avec Hush", "Si Tidal est fermé, Hush le lance en arrière-plan à l'ouverture", f.autoOpenTidal) { Features.set(f.copy(autoOpenTidal = it)) }
        OptionRow("Pour mon trajet", "Une file qui dure pile le temps de ton trajet", f.trip) { Features.set(f.copy(trip = it)) }
        OptionRow("Aléatoire malin", "Jamais deux fois le même artiste d'affilée, les sons récents plus tard", f.smartShuffle) { Features.set(f.copy(smartShuffle = it)) }
        OptionRow("Anti-répétition", "Mix, humeurs : pas un son déjà passé dans les 2 dernières heures", f.antiRepeat) { Features.set(f.copy(antiRepeat = it)) }
        OptionRow("Passage propre au son suivant", "Coupe le son le temps que Hush lance le son suivant, pour ne pas entendre 1 s du son que Tidal aurait mis", f.silentHandoff) { Features.set(f.copy(silentHandoff = it)) }
        OptionRow("Écoute sans distraction", "Sur la page d'un album : plein écran, pas de saut, notifs coupées, ta note à la fin", f.focusAlbum) { Features.set(f.copy(focusAlbum = it)) }
        OptionRow("Enchaînement sans blanc", "Playlists enchaînées par Hush : le son suivant est glissé dans la file de Tidal", f.gapless) { Features.set(f.copy(gapless = it)) }
        OptionRow("Corriger les mauvaises versions", "Si Tidal lance une autre version (slowed, live…), Hush relance la bonne", f.fixVersions) { Features.set(f.copy(fixVersions = it)) }
    }
}

/** Son : basses, 8D, profils de casque. */
@Composable
fun SoundOptionsCard() {
    val f by Features.flags.collectAsState()
    SettingsCard {
        OptionRow("Booster de basses", "Bouton dans le lecteur : 3 niveaux de basses", f.bass) { Features.set(f.copy(bass = it)) }
        OptionRow("Son 8D", "Le son tourne autour de ta tête (avec écouteurs)", f.spatial) { Features.set(f.copy(spatial = it)) }
        OptionRow("Profils par appareil", "Dans Appareils : volume, basses et son 8D réglés tout seuls pour chaque casque ou voiture", f.deviceProfiles) { Features.set(f.copy(deviceProfiles = it)) }
    }
}

/** Tes notes : étoiles, avis, messages au futur toi. */
@Composable
fun NotesOptionsCard() {
    val f by Features.flags.collectAsState()
    SettingsCard {
        OptionRow("Mes notes", "Note tes albums et tes sons sur 5 avec une phrase d'avis, façon Letterboxd", f.ratings) { Features.set(f.copy(ratings = it)) }
        OptionRow("Tes notes partout", "Tes étoiles à côté des sons dans les playlists, albums, recherche et file", f.ratingsEverywhere) { Features.set(f.copy(ratingsEverywhere = it)) }
        OptionRow("Sons à noter", "À la fin d'un son écouté souvent mais pas noté, une petite bulle te propose de le noter", f.ratePrompt) { Features.set(f.copy(ratePrompt = it)) }
        OptionRow("Message au futur toi", "Une note sur un son qui ressort quand tu le réécoutes, des mois plus tard", f.timeCapsule) { Features.set(f.copy(timeCapsule = it)) }
        OptionRow("Notes sur un son", "Un mémo perso par son (⋯ dans le lecteur)", f.notes) { Features.set(f.copy(notes = it)) }
    }
}

/** Playlists : tri, dossiers, pochettes, reprise… */
@Composable
fun PlaylistOptionsCard() {
    val f by Features.flags.collectAsState()
    SettingsCard {
        OptionRow("Playlists intelligentes", "Dans la Biblio : des playlists qui se remplissent toutes seules", f.smartLists) { Features.set(f.copy(smartLists = it)) }
        OptionRow("Docteur de playlist", "Bouton en haut de tes playlists : doublons, sons indisponibles et sons toujours passés, à retirer en un toucher", f.playlistDoctor) { Features.set(f.copy(playlistDoctor = it)) }
        OptionRow("Playlist qui grandit", "Chaque lundi sur l'accueil : des sons proches de tes 5 étoiles, à garder ou jeter", f.growing) { Features.set(f.copy(growing = it)) }
        OptionRow("Recommandations dans les playlists", "En bas de tes playlists : des sons proches, à ajouter en un tap", f.playlistRecs) { Features.set(f.copy(playlistRecs = it)) }
        OptionRow("Reprise exacte des playlists", "Dans une playlist : « Reprendre » au son et à la seconde où tu l'as laissée", f.resumePlaylist) { Features.set(f.copy(resumePlaylist = it)) }
        OptionRow("Pochettes de playlists perso", "Une photo de ta galerie ou la mosaïque des sons (appui long sur la pochette d'une playlist)", f.playlistCovers) { Features.set(f.copy(playlistCovers = it)) }
        OptionRow("Où est ce son ?", "Dans le menu d'un son : les playlists où il est déjà rangé", f.whereIsIt) { Features.set(f.copy(whereIsIt = it)) }
        OptionRow("Chercher et trier dans une playlist", "Recherche + tri par titre, artiste, album ou durée", f.playlistSort) { Features.set(f.copy(playlistSort = it)) }
        OptionRow("Dossiers de playlists", "Dans la Biblio : maintiens une playlist pour la ranger", f.folders) { Features.set(f.copy(folders = it)) }
        OptionRow("Anti-doublons", "Ajouter un son déjà présent dans une playlist ne le rajoute pas", f.dupWarn) { Features.set(f.copy(dupWarn = it)) }
    }
}

/** Fichiers locaux du téléphone. */
@Composable
fun LocalOptionsCard() {
    val f by Features.flags.collectAsState()
    SettingsCard {
        OptionRow("Fichiers locaux", "Lire les sons de ton téléphone dans Hush et les ranger en playlists", f.localFiles) { Features.set(f.copy(localFiles = it)) }
        OptionRow("Fondu entre fichiers locaux", "Le son local baisse doucement à la fin et monte au début du suivant", f.localFade) { Features.set(f.copy(localFade = it)) }
        OptionRow("Volume égalisé (locaux)", "Tes fichiers sonnent aussi fort les uns que les autres", f.localNormalize) { Features.set(f.copy(localNormalize = it)) }
        OptionRow("Pochette auto (locaux)", "Les fichiers sans pochette en reçoivent une trouvée sur Tidal", f.autoLocalArt) { Features.set(f.copy(autoLocalArt = it)) }
    }
}

/** Fun et découverte : quiz, récompenses, extras du lecteur. */
@Composable
fun FunOptionsCard() {
    val f by Features.flags.collectAsState()
    SettingsCard {
        OptionRow("Mode découverte", "Dans les Outils : des sons jamais écoutés, à garder ou jeter en glissant", f.discover) { Features.set(f.copy(discover = it)) }
        OptionRow("Sorties à venir", "Sur l'accueil : les albums annoncés de tes artistes avec un compte à rebours, et une notif le jour J", f.upcoming) { Features.set(f.copy(upcoming = it)) }
        OptionRow("Quiz paroles", "Dans les Outils : trouve la suite ou le titre de tes sons", f.lyricsQuiz) { Features.set(f.copy(lyricsQuiz = it)) }
        OptionRow("Récompenses", "De l'XP et des niveaux qui débloquent couleurs, icônes, effets…", f.rewards) { Features.set(f.copy(rewards = it)) }
        OptionRow("Sons d'ambiance", "Pluie, vagues, bruits doux par-dessus ta musique", f.ambient) { Features.set(f.copy(ambient = it)) }
        OptionRow("Crédits du son", "Producteurs, auteurs, année (MusicBrainz)", f.credits) { Features.set(f.copy(credits = it)) }
        OptionRow("Clip du son", "Ouvrir le clip sur YouTube", f.clip) { Features.set(f.copy(clip = it)) }
    }
}

/** Stats : objectif, comparaison, carte des heures. */
@Composable
fun StatsOptionsCard() {
    val f by Features.flags.collectAsState()
    SettingsCard {
        OptionRow("Mur de pochettes", "Dans les Stats : tout ce que tu as écouté en pochettes, touche pour relancer", f.coverWall) { Features.set(f.copy(coverWall = it)) }
        Text("Objectif d'écoute par jour", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Aucun", 30 to "30 min", 60 to "1 h", 90 to "1 h 30", 120 to "2 h").forEach { (m, l) ->
                val on = f.goalMin == m
                val shape = PillShape
                androidx.compose.foundation.layout.Box(
                    Modifier.clip(shape)
                        .then(if (on) Modifier.background(Txt) else Modifier.glass(shape, fill = 0.08f, edge = 0.14f))
                        .bounceClick { Features.set(f.copy(goalMin = m)) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) { Text(l, color = if (on) Ink else Txt, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
            }
        }
        OptionRow("Comparer les mois", "Ce mois-ci contre le mois dernier", f.compareMonths) { Features.set(f.copy(compareMonths = it)) }
        OptionRow("Carte des heures", "Quand tu écoutes le plus, par jour et par heure", f.heatmap) { Features.set(f.copy(heatmap = it)) }
        OptionRow("Tes records", "Ta plus longue session, ta plus grosse journée…", f.records) { Features.set(f.copy(records = it)) }
        OptionRow("Carte d'identité musicale", "Ton profil d'auditeur, à partager en story", f.idCard) { Features.set(f.copy(idCard = it)) }
        OptionRow("Première écoute", "La date où tu as découvert un son ou un artiste", f.firstListen) { Features.set(f.copy(firstListen = it)) }
        OptionRow("Featurings", "La carte des collabs entre tes artistes", f.feats) { Features.set(f.copy(feats = it)) }
        OptionRow("Tes mots", "Les mots qui reviennent le plus dans tes paroles", f.lyricWords) { Features.set(f.copy(lyricWords = it)) }
        OptionRow("Wrapped de l'année", "Ton année en musique, façon story", f.wrappedYear) { Features.set(f.copy(wrappedYear = it)) }
        OptionRow("Tes albums", "Classement de tes albums et ceux écoutés en entier", f.albums) { Features.set(f.copy(albums = it)) }
        OptionRow("Un son par artiste", "Ton son préféré de chacun de tes artistes", f.oneSong) { Features.set(f.copy(oneSong = it)) }
        OptionRow("Sons souvent passés", "Repère les sons que tu zappes et propose de les masquer", f.skipDetect) { Features.set(f.copy(skipDetect = it)) }
        OptionRow("Exclure des sons des stats", "Appui long sur un son → « Ne pas compter dans mes stats »", f.statsExclude) { Features.set(f.copy(statsExclude = it)) }
        OptionRow("Défis de la semaine", "3 petits défis chaque lundi, avec ta progression", f.challenges) { Features.set(f.copy(challenges = it)) }
        OptionRow("Sons d'une époque", "Tes sons de chaque saison passée, à réécouter", f.eras) { Features.set(f.copy(eras = it)) }
    }
}

private val SECTIONS = mapOf(
    "tools" to "Outils (Shazam, trajet, réveil…)",
    "chartsAgo" to "N°1 en France il y a 5 ans",
    "skips" to "Sons souvent passés",
    "resume" to "Reprendre où j'en étais",
    "now" to "En ce moment",
    "foryou" to "Pour toi (grandes cartes)",
    "mix" to "Mix du jour",
    "gem" to "Son caché du jour",
    "yearAgo" to "« Il y a un an »",
    "shortcuts" to "Raccourcis",
    "mood" to "Ton humeur",
    "weekly" to "Tes sons de la semaine",
    "moment" to "Pour ce moment de la journée",
    "releases" to "Nouveautés de tes artistes",
    "upcoming" to "Bientôt chez tes artistes (sorties à venir)",
    "growing" to "Ta playlist qui grandit",
    "playlists" to "Tes playlists",
    "forgotten" to "Ressortis du placard",
    "recents" to "Écoutés récemment",
)

/** Ce qui apparaît sur l'accueil, et dans quel ordre. */
@Composable
fun HomeSectionsCard() {
    val f by Features.flags.collectAsState()
    SettingsCard {
        Text("Sections de l'accueil", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        Text("Les flèches changent l'ordre, l'interrupteur affiche ou masque.", color = Txt3, fontSize = 12.sp)
        val order = f.homeOrder
        order.forEachIndexed { i, id ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(SECTIONS[id] ?: id, color = if (id in f.hidden) Txt3 else Txt, fontSize = 13.sp, modifier = Modifier.weight(1f))
                ArrowBtn("↑", i > 0) { Features.set(f.copy(homeOrder = order.toMutableList().apply { add(i - 1, removeAt(i)) })) }
                ArrowBtn("↓", i < order.size - 1) { Features.set(f.copy(homeOrder = order.toMutableList().apply { add(i + 1, removeAt(i)) })) }
                Spacer(Modifier.width(6.dp))
                GlassSwitch(id !in f.hidden) { on -> Features.set(f.copy(hidden = if (on) f.hidden - id else f.hidden + id)) }
            }
        }
        Text(
            "Remettre l'ordre d'origine", color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).bounceClick(pressedScale = 0.95f) { Features.set(f.copy(homeOrder = Features.HOME_DEFAULT)) }.padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun ArrowBtn(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(PillShape).bounceClick(enabled = enabled, pressedScale = 0.85f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) Txt else Txt3.copy(alpha = 0.4f), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold) }
}

/** Paroles et recherche. */
@Composable
fun LyricsSearchCard() {
    val f by Features.flags.collectAsState()
    SettingsCard {
        OptionRow("Paroles traduites", "Bouton « traduire » dans l'écran des paroles", f.translate) { Features.set(f.copy(translate = it)) }
        OptionRow("Recherche vocale", "Micro dans la recherche : « mets du Jok'Air »", f.voice) { Features.set(f.copy(voice = it)) }
        OptionRow("Résultats pendant que tu tapes", "Plus besoin de valider : la recherche se lance toute seule", f.liveSearch) { Features.set(f.copy(liveSearch = it)) }
        OptionRow("Paroles favorites", "Appui long sur une ligne des paroles pour la garder", f.quotes) { Features.set(f.copy(quotes = it)) }
    }
}

/** Widgets et notifications. */
@Composable
fun WidgetsNotifCard() {
    val f by Features.flags.collectAsState()
    SettingsCard {
        OptionRow("Tuile Réglages rapides", "Une tuile Hush dans le volet Android : lecture / pause, ou ta dernière playlist", f.qsTile) { Features.set(f.copy(qsTile = it)) }
        OptionRow("Notification enrichie", "Son en cours avec cœur, précédent, lecture, suivant", f.richNotif) { Features.set(f.copy(richNotif = it)) }
        OptionRow("Masquer le lecteur Tidal", "Garde seulement la notification de Hush (Tidal continue de jouer)", f.hideTidalNotif) { Features.set(f.copy(hideTidalNotif = it)) }
        OptionRow("Style lecteur média", "La notif devient une vraie carte lecteur : grande pochette, barre de lecture, cœur et aléatoire", f.mediaCard) { Features.set(f.copy(mediaCard = it)) }
        OptionRow("Pastille en haut à gauche", "Le titre du son dans une pastille dans la barre d'état. Sur Samsung elle vient de la carte lecteur : la couper enlève aussi la grande carte (les boutons restent)", f.statusPill) { Features.set(f.copy(statusPill = it)) }
        OptionRow("Widget carré", "Pochette + lecture/pause, dans la liste des widgets", f.squareWidget) { Features.set(f.copy(squareWidget = it)) }
        val edgeCtx = LocalContext.current
        OptionRow("Panneau latéral", "Une fine poignée au bord de l'écran, par-dessus tes applis : contrôles et tes 3 playlists. Cachée en paysage", f.edgePanel) {
            Features.set(f.copy(edgePanel = it))
            if (it && !EdgePanel.canShow(edgeCtx)) {
                TidalRemote.showToast("Autorise Hush à s'afficher par-dessus les autres applis")
                runCatching {
                    edgeCtx.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + edgeCtx.packageName))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            EdgePanel.sync(edgeCtx)
        }
        OptionRow("Widget playlists", "Tes playlists en pochettes sur l'écran d'accueil, une touche pour lancer", f.playlistWidget) { Features.set(f.copy(playlistWidget = it)) }
        OptionRow("Raccourcis sur l'icône", "Appui long sur l'icône de Hush : tes 4 dernières playlists", f.iconShortcuts) { Features.set(f.copy(iconShortcuts = it)) }
        OptionRow("Récap du dimanche", "Le dimanche à 20 h : ta semaine en musique", f.weeklyRecap) { Features.set(f.copy(weeklyRecap = it)) }
        OptionRow("Widget stats", "Minutes du jour, série et son de la semaine", f.statsWidget) { Features.set(f.copy(statsWidget = it)) }
        OptionRow("Setlists des concerts", "Les sons joués aux concerts de tes artistes, avec une notif après un concert", f.setlists) { Features.set(f.copy(setlists = it)) }
        OptionRow("Concerts près de chez toi", "Les dates de tes artistes, avec une notif pour les nouvelles en Île-de-France", f.concerts) { Features.set(f.copy(concerts = it)) }
        OptionRow("Rappel d'écoute", "Le soir : ta journée en musique et ta série", f.reminder) { Features.set(f.copy(reminder = it)) }
        if (f.reminder) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(19, 20, 21, 22, 23).forEach { h ->
                    val on = f.reminderHour == h
                    val shape = PillShape
                    androidx.compose.foundation.layout.Box(
                        Modifier.clip(shape)
                            .then(if (on) Modifier.background(Txt) else Modifier.glass(shape, fill = 0.08f, edge = 0.14f))
                            .bounceClick { Features.set(f.copy(reminderHour = h)) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) { Text("${h}h", color = if (on) Ink else Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

/** Sons masqués (« Pas pour moi »). */
@Composable
fun BlockedCard() {
    val items by com.theo.remote.Blocked.items.collectAsState()
    SettingsCard {
        Text("Sons masqués", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        if (items.isEmpty()) {
            Text("Appui long sur un son → « Pas pour moi » : il n'apparaîtra plus dans le Mix du jour, les humeurs, le blind test ni l'accueil.", color = Txt2, fontSize = 13.sp, lineHeight = 18.sp)
        }
        items.forEach { b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(b.title, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(b.artist, color = Txt2, fontSize = 12.sp, maxLines = 1)
                }
                Text(
                    "Remettre", color = Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).bounceClick(pressedScale = 0.95f) { com.theo.remote.Blocked.unblock(b.key) }.padding(6.dp),
                )
            }
        }
    }
}

@Composable
private fun OptionRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val flat = Features.flags.collectAsState().value.flatStyle
    val fold = LocalCardFold.current
    val showAll by SettingsFold.showAll.collectAsState()
    val secondary = fold != null && !showAll && title !in ESSENTIAL && !SettingsFold.used(title)
    if (secondary && !fold!!.shown.value) {
        // replié : on le compte juste pour « N options de plus »
        LaunchedEffect(title) { fold.hidden[title] = Unit }
        return
    }
    if (fold != null && !secondary) LaunchedEffect(title) { fold.hidden.remove(title) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(sub, color = Txt2, fontSize = 13.sp, lineHeight = 17.sp)
            }
            Spacer(Modifier.width(Space.s))
            GlassSwitch(on) { v -> SettingsFold.bump(title); onChange(v) }
        }
        if (flat) Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
    }
}

/** Exporter / importer une sauvegarde. */
@Composable
fun BackupCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmImport by remember { mutableStateOf(false) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching { Backup.export(ctx, uri) }
                .onSuccess { TidalRemote.showToast("Sauvegarde enregistrée") }
                .onFailure { TidalRemote.showToast(it.message ?: "Échec de la sauvegarde") }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { Backup.import(ctx, uri) }.onFailure { TidalRemote.showToast(it.message ?: "Restauration impossible") }
        }
    }

    val shape = Rounded(22)
    Column(Modifier.fillMaxWidth().glass(shape, fill = 0.07f, edge = 0.12f).padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text("Sauvegarde", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            "Tout Hush : historique, stats Last.fm, réglages, notes, paroles favorites, artistes suivis, défis, playlists intelligentes, réglages du son, pochettes… Ta connexion Tidal n'est pas incluse : tu te reconnectes après une restauration.",
            color = Txt2, fontSize = 13.sp, lineHeight = 18.sp,
        )
        val summary = remember { Backup.summary(ctx) }
        Text("Contenu : $summary", color = Txt3, fontSize = 12.sp, lineHeight = 16.sp)
        val f by Features.flags.collectAsState()
        OptionRow("Sauvegarde auto", "Chaque jour dans Téléchargements/Hush", f.autoBackup) { Features.set(f.copy(autoBackup = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            PillButton("Exporter") {
                val day = SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(Date())
                exporter.launch("Hush-sauvegarde-$day.json")
            }
            PillButton(if (confirmImport) "Sûr ? Tout sera remplacé" else "Importer", filled = false) {
                if (confirmImport) { confirmImport = false; importer.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }
                else confirmImport = true
            }
        }
    }
}


/** Artistes suivis : une notif quand ils sortent un son. */
@Composable
fun FollowCard() {
    val f by Features.flags.collectAsState()
    val artists by com.theo.remote.Follow.artists.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    SettingsCard {
        OptionRow("Suivre des artistes", "Bouton « Suivre » sur la page d'un artiste, notif à chaque sortie", f.follow) { Features.set(f.copy(follow = it)) }
        if (f.follow) {
            if (artists.isEmpty()) Text("Tu ne suis encore personne.", color = Txt2, fontSize = 13.sp)
            artists.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
                    Text(
                        "Ne plus suivre", color = Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).bounceClick(pressedScale = 0.95f) { com.theo.remote.Follow.toggle(ctx, a) }.padding(6.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- recherche dans les Réglages

/** Index de recherche des Réglages : options à interrupteur + raccourcis vers un thème. */
object SettingsIndex {
    sealed class Item(val title: String, val sub: String, val section: String, val keywords: String)
    class Flag(title: String, sub: String, section: String, val get: (Features.Flags) -> Boolean, val set: (Features.Flags, Boolean) -> Features.Flags) : Item(title, sub, section, "")
    class Link(title: String, sub: String, section: String, keywords: String) : Item(title, sub, section, keywords)

    val items: List<Item> = listOf(
        Flag("Thème selon l'heure", "Couleurs plus chaudes le soir, plus sombres la nuit", "Affichage", { it.timeTheme }, { f, v -> f.copy(timeTheme = v) }),
        Flag("Pochette animée", "Elle respire au rythme du son dans le lecteur", "Affichage", { it.animatedCover }, { f, v -> f.copy(animatedCover = v) }),
        Flag("Mode économie", "Coupe le flou, les fonds animés et la pochette animée", "Affichage", { it.eco }, { f, v -> f.copy(eco = v) }),
        Flag("Pochette en plein écran", "Toucher la pochette dans le lecteur pour l'afficher en grand", "Affichage", { it.fullCover }, { f, v -> f.copy(fullCover = v) }),
        Flag("Accueil compact", "Cartes plus petites, plus de sons d'un coup", "Affichage", { it.compactHome }, { f, v -> f.copy(compactHome = v) }),
        Flag("« Il y a un an »", "Sur l'accueil : ce que tu écoutais il y a un an", "Affichage", { it.yearAgo }, { f, v -> f.copy(yearAgo = v) }),
        Flag("Son caché du jour", "Sur l'accueil : un son d'un de tes artistes jamais écouté", "Affichage", { it.hiddenGem }, { f, v -> f.copy(hiddenGem = v) }),
        Flag("Discographie complète", "Sur la page d'un artiste : le % de ses sons que tu as écoutés", "Affichage", { it.discography }, { f, v -> f.copy(discography = v) }),
        Flag("Notes sur un son", "Un mémo perso par son (⋯ dans le lecteur)", "Tes notes", { it.notes }, { f, v -> f.copy(notes = v) }),
        Flag("Playlists intelligentes", "Dans la Biblio : des playlists qui se remplissent toutes seules", "Playlists", { it.smartLists }, { f, v -> f.copy(smartLists = v) }),
        Flag("Reprendre où j'en étais", "Sur l'accueil : relancer ton dernier son au bon moment", "Lecture", { it.resumeLast }, { f, v -> f.copy(resumeLast = v) }),
        Flag("Ouvrir Tidal avec Hush", "Si Tidal est fermé, Hush le lance en arrière-plan à l'ouverture", "Lecture", { it.autoOpenTidal }, { f, v -> f.copy(autoOpenTidal = v) }),
        Flag("Recommandations dans les playlists", "En bas de tes playlists : des sons proches, à ajouter en un tap", "Playlists", { it.playlistRecs }, { f, v -> f.copy(playlistRecs = v) }),
        Flag("Style Pochette", "Fond noir uni, la couleur vient de la pochette en cours", "Affichage", { it.flatStyle }, { f, v -> f.copy(flatStyle = v) }),
        Flag("Retour haptique", "Une petite vibration sur chaque bouton", "Affichage", { it.haptics }, { f, v -> f.copy(haptics = v) }),
        Flag("Fichiers locaux", "Lire les sons de ton téléphone dans Hush et les ranger en playlists", "Fichiers locaux", { it.localFiles }, { f, v -> f.copy(localFiles = v) }),
        Flag("Booster de basses", "Bouton dans le lecteur : 3 niveaux de basses", "Lecture", { it.bass }, { f, v -> f.copy(bass = v) }),
        Flag("Son 8D", "Le son tourne autour de ta tête (avec écouteurs)", "Lecture", { it.spatial }, { f, v -> f.copy(spatial = v) }),
        Flag("Pour mon trajet", "Une file qui dure pile le temps de ton trajet", "Lecture", { it.trip }, { f, v -> f.copy(trip = v) }),
        Flag("Anti-répétition", "Mix, humeurs : pas un son déjà passé dans les 2 dernières heures", "Lecture", { it.antiRepeat }, { f, v -> f.copy(antiRepeat = v) }),
        Flag("Message au futur toi", "Une note sur un son qui ressort quand tu le réécoutes, des mois plus tard", "Tes notes", { it.timeCapsule }, { f, v -> f.copy(timeCapsule = v) }),
        Flag("Mes notes", "Note tes albums et tes sons sur 5 avec une phrase d'avis, façon Letterboxd", "Tes notes", { it.ratings }, { f, v -> f.copy(ratings = v) }),
        Flag("Tes notes partout", "Tes étoiles à côté des sons dans les playlists, albums, recherche et file", "Tes notes", { it.ratingsEverywhere }, { f, v -> f.copy(ratingsEverywhere = v) }),
        Flag("Sons à noter", "À la fin d'un son écouté souvent mais pas noté, une petite bulle te propose de le noter", "Tes notes", { it.ratePrompt }, { f, v -> f.copy(ratePrompt = v) }),
        Flag("Pochettes vidéo", "Si l'album a une pochette animée sur Tidal, elle tourne dans le lecteur", "Affichage", { it.videoCovers }, { f, v -> f.copy(videoCovers = v) }),
        Flag("Passage propre au son suivant", "Coupe le son le temps que Hush lance le son suivant", "Lecture", { it.silentHandoff }, { f, v -> f.copy(silentHandoff = v) }),
        Flag("Mode découverte", "Dans les Outils : des sons jamais écoutés, à garder ou jeter en glissant", "Fun et découverte", { it.discover }, { f, v -> f.copy(discover = v) }),
        Flag("Pochettes de playlists perso", "Une photo de ta galerie ou la mosaïque des sons (appui long sur la pochette d'une playlist)", "Playlists", { it.playlistCovers }, { f, v -> f.copy(playlistCovers = v) }),
        Flag("Écoute sans distraction", "Sur la page d'un album : plein écran, pas de saut, notifs coupées, ta note à la fin", "Lecture", { it.focusAlbum }, { f, v -> f.copy(focusAlbum = v) }),
        Flag("Panneau latéral", "Une fine poignée au bord de l'écran, par-dessus tes applis : contrôles et tes 3 playlists", "Widgets et notifications", { it.edgePanel }, { f, v -> f.copy(edgePanel = v) }),
        Flag("Enchaînement sans blanc", "Playlists enchaînées par Hush : le son suivant est glissé dans la file de Tidal", "Lecture", { it.gapless }, { f, v -> f.copy(gapless = v) }),
        Flag("Corriger les mauvaises versions", "Si Tidal lance une autre version (slowed, live…), Hush relance la bonne", "Lecture", { it.fixVersions }, { f, v -> f.copy(fixVersions = v) }),
        Flag("Reprise exacte des playlists", "Dans une playlist : « Reprendre » au son et à la seconde où tu l'as laissée", "Playlists", { it.resumePlaylist }, { f, v -> f.copy(resumePlaylist = v) }),
        Flag("Fondu entre fichiers locaux", "Le son local baisse doucement à la fin et monte au début du suivant", "Fichiers locaux", { it.localFade }, { f, v -> f.copy(localFade = v) }),
        Flag("Volume égalisé (locaux)", "Tes fichiers sonnent aussi fort les uns que les autres", "Fichiers locaux", { it.localNormalize }, { f, v -> f.copy(localNormalize = v) }),
        Flag("Pochette auto (locaux)", "Les fichiers sans pochette en reçoivent une trouvée sur Tidal", "Fichiers locaux", { it.autoLocalArt }, { f, v -> f.copy(autoLocalArt = v) }),
        Flag("Anti-doublons", "Ajouter un son déjà présent dans une playlist ne le rajoute pas", "Playlists", { it.dupWarn }, { f, v -> f.copy(dupWarn = v) }),
        Flag("Dossiers de playlists", "Dans la Biblio : maintiens une playlist pour la ranger", "Playlists", { it.folders }, { f, v -> f.copy(folders = v) }),
        Flag("Chercher et trier dans une playlist", "Recherche + tri par titre, artiste, album ou durée", "Playlists", { it.playlistSort }, { f, v -> f.copy(playlistSort = v) }),
        Flag("Tuile Réglages rapides", "Une tuile Hush dans le volet Android : lecture / pause, ou ta dernière playlist", "Widgets et notifications", { it.qsTile }, { f, v -> f.copy(qsTile = v) }),
        Flag("Couleur adaptative", "Les onglets et les boutons prennent la couleur de la pochette en cours", "Affichage", { it.adaptiveColor }, { f, v -> f.copy(adaptiveColor = v) }),
        Flag("Comparer les mois", "Ce mois-ci contre le mois dernier", "Stats", { it.compareMonths }, { f, v -> f.copy(compareMonths = v) }),
        Flag("Carte des heures", "Quand tu écoutes le plus, par jour et par heure", "Stats", { it.heatmap }, { f, v -> f.copy(heatmap = v) }),
        Flag("Tes records", "Ta plus longue session, ta plus grosse journée…", "Stats", { it.records }, { f, v -> f.copy(records = v) }),
        Flag("Carte d'identité musicale", "Ton profil d'auditeur, à partager en story", "Stats", { it.idCard }, { f, v -> f.copy(idCard = v) }),
        Flag("Première écoute", "La date où tu as découvert un son ou un artiste", "Stats", { it.firstListen }, { f, v -> f.copy(firstListen = v) }),
        Flag("Featurings", "La carte des collabs entre tes artistes", "Stats", { it.feats }, { f, v -> f.copy(feats = v) }),
        Flag("Tes mots", "Les mots qui reviennent le plus dans tes paroles", "Stats", { it.lyricWords }, { f, v -> f.copy(lyricWords = v) }),
        Flag("Défis de la semaine", "3 petits défis chaque lundi, avec ta progression", "Stats", { it.challenges }, { f, v -> f.copy(challenges = v) }),
        Flag("Sons d'une époque", "Tes sons de chaque saison passée, à réécouter", "Stats", { it.eras }, { f, v -> f.copy(eras = v) }),
        Flag("Paroles traduites", "Bouton « traduire » dans l'écran des paroles", "Paroles et recherche", { it.translate }, { f, v -> f.copy(translate = v) }),
        Flag("Recherche vocale", "Micro dans la recherche : « mets du Jok'Air »", "Paroles et recherche", { it.voice }, { f, v -> f.copy(voice = v) }),
        Flag("Paroles favorites", "Appui long sur une ligne des paroles pour la garder", "Paroles et recherche", { it.quotes }, { f, v -> f.copy(quotes = v) }),
        Flag("Notification enrichie", "Son en cours avec cœur, précédent, lecture, suivant", "Widgets et notifications", { it.richNotif }, { f, v -> f.copy(richNotif = v) }),
        Flag("Masquer le lecteur Tidal", "Garde seulement la notification de Hush (Tidal continue de jouer)", "Widgets et notifications", { it.hideTidalNotif }, { f, v -> f.copy(hideTidalNotif = v) }),
        Flag("Style lecteur média", "Carte lecteur avec pochette, barre de lecture, cœur et aléatoire", "Widgets et notifications", { it.mediaCard }, { f, v -> f.copy(mediaCard = v) }),
        Flag("Pastille en haut à gauche", "Pillule pilule titre du son dans la barre d'état (enlève aussi la grande carte de la notif)", "Widgets et notifications", { it.statusPill }, { f, v -> f.copy(statusPill = v) }),
        Flag("File en pochettes", "File d'attente en grandes pochettes à déplacer", "Affichage", { it.queueCovers }, { f, v -> f.copy(queueCovers = v) }),
        Flag("Playlist instantanée", "Une playlist de tes favoris à partir d'une phrase", "Affichage", { it.instantPlaylist }, { f, v -> f.copy(instantPlaylist = v) }),
        Flag("Réponse immédiate", "Pause et ajouts qui changent tout de suite à l'écran", "Affichage", { it.instantUi }, { f, v -> f.copy(instantUi = v) }),
        Flag("Affichage instantané", "Pas d'apparition en cascade, playlists gardées sur le téléphone", "Affichage", { it.homeCache }, { f, v -> f.copy(homeCache = v) }),
        Flag("Bulles d'aide", "Expliquer une fois les gestes cachés", "Affichage", { it.gestureHints }, { f, v -> f.copy(gestureHints = v) }),
        Flag("Messages quand ça bloque", "Bandeau quand il n'y a plus de connexion", "Affichage", { it.clearStates }, { f, v -> f.copy(clearStates = v) }),
        Flag("Vérification au démarrage", "Écran qui vérifie que tout est prêt au premier lancement", "Affichage", { it.setupCheck }, { f, v -> f.copy(setupCheck = v) }),
        Flag("Mises à jour", "Proposer la nouvelle version de Hush dès qu'elle sort", "Affichage", { it.autoUpdate }, { f, v -> f.copy(autoUpdate = v) }),
        Flag("Bouton Annuler", "Revenir en arrière après avoir retiré un son ou vidé la file", "Affichage", { it.undoBar }, { f, v -> f.copy(undoBar = v) }),
        Flag("Lancer le bon son", "Lancer tes playlists et tes sons par leur lien exact plutôt que par une recherche", "Playlists", { it.exactLaunch }, { f, v -> f.copy(exactLaunch = v) }),
        Flag("Recherche maline", "Résultats dès la première lettre et fautes de frappe corrigées", "Lecture", { it.smartSearch }, { f, v -> f.copy(smartSearch = v) }),
        Flag("Bloquer les sauts automatiques", "Ignorer les « suivant » en rafale écran éteint", "Lecture", { it.ghostGuard }, { f, v -> f.copy(ghostGuard = v) }),
        Flag("Playlists enchaînées par Hush", "Lire une playlist par Hush : démarrage direct et file modifiable", "Playlists", { it.hushChain }, { f, v -> f.copy(hushChain = v) }),
        Flag("Écrans allégés", "Accueil et lecteur avec l'essentiel seulement", "Affichage", { it.lightUi }, { f, v -> f.copy(lightUi = v) }),
        Flag("Playlists gardées sur le téléphone", "Charger tes playlists en entier en Wi-Fi et en charge", "Playlists", { it.preloadPlaylists }, { f, v -> f.copy(preloadPlaylists = v) }),
        Flag("Mises à jour prêtes d'avance", "Télécharger la nouvelle version en fond en Wi-Fi", "Avancé", { it.prefetchUpdate }, { f, v -> f.copy(prefetchUpdate = v) }),
        Flag("Retour sans fausse sortie", "Faire retour deux fois pour quitter depuis l'Accueil", "Affichage", { it.safeBack }, { f, v -> f.copy(safeBack = v) }),
        Flag("Économie de données", "Vignettes plus légères et pas de pochettes vidéo en données mobiles", "Avancé", { it.dataSaver }, { f, v -> f.copy(dataSaver = v) }),
        Flag("Rapport après un plantage", "Proposer de copier le rapport après un plantage", "Avancé", { it.crashPrompt }, { f, v -> f.copy(crashPrompt = v) }),
        Flag("Économie de batterie", "Surveiller Tidal moins souvent écran éteint", "Avancé", { it.batterySaver }, { f, v -> f.copy(batterySaver = v) }),
        Flag("Playlist sans intrus", "Vider la file « À suivre » quand tu lances une playlist", "Playlists", { it.freshQueue }, { f, v -> f.copy(freshQueue = v) }),
        Flag("Nettoyage mensuel", "Retirer les sons que tu passes toujours dans une playlist", "Playlists", { it.playlistCleanup }, { f, v -> f.copy(playlistCleanup = v) }),
        Flag("Pas de doublons dans les playlists", "Ne jamais ajouter deux fois le même son à une playlist", "Playlists", { it.noDuplicates }, { f, v -> f.copy(noDuplicates = v) }),
        Flag("Alerte sons coupés", "Notif quand un son est coupé avant la fin", "Lecture", { it.earlyCutAlert }, { f, v -> f.copy(earlyCutAlert = v) }),
        Flag("3 onglets seulement", "Accueil, Recherche et Biblio, sans l'onglet Cartes ou Stats", "Affichage", { it.threeTabs }, { f, v -> f.copy(threeTabs = v) }),
        Flag("Formes nettes", "Coins moins arrondis, moins de pilules", "Affichage", { it.crispShapes }, { f, v -> f.copy(crispShapes = v) }),
        Flag("Mode simple", "Juste l'essentiel, sans les fonctions avancées", "Affichage", { it.simpleMode }, { f, v -> f.copy(simpleMode = v) }),
        Flag("Recherche remise à zéro", "Effacer la recherche en cours quand on change d'onglet", "Affichage", { it.searchReset }, { f, v -> f.copy(searchReset = v) }),
        Flag("Lecteur en verre", "Le lecteur avec pochette bord à bord, panneau de verre et boutons en gouttes", "Affichage", { it.glassPlayer }, { f, v -> f.copy(glassPlayer = v) }),
        Flag("Tube de verre", "La progression du lecteur en verre dans un tube rempli de liquide", "Affichage", { it.glassTube }, { f, v -> f.copy(glassTube = v) }),
        Flag("Barre ondulée", "La barre de progression du lecteur ondule pendant la lecture", "Affichage", { it.wavyBar }, { f, v -> f.copy(wavyBar = v) }),
        Flag("Cartes de sons", "Onglet Cartes à la place de Stats : Bronze, Argent, Or, Diamant", "Affichage", { it.songCards }, { f, v -> f.copy(songCards = v) }),
        Flag("Annonce quand une carte monte", "Écran « Nouvelle carte » quand un son change de rang", "Affichage", { it.cardPromo }, { f, v -> f.copy(cardPromo = v) }),
        Flag("Cartes qui brillent", "Reflet qui suit l'inclinaison, carte en 3D sous le doigt", "Affichage", { it.cardHolo }, { f, v -> f.copy(cardHolo = v) }),
        Flag("Cartes rares", "Édition spéciale : n°1 du mois, 100 écoutes", "Affichage", { it.cardSpecial }, { f, v -> f.copy(cardSpecial = v) }),
        Flag("Cartes en danger", "Badge sur les cartes qui vont redescendre", "Affichage", { it.cardDanger }, { f, v -> f.copy(cardDanger = v) }),
        Flag("Rang des sons partout", "Losange du rang dans le lecteur et les listes", "Affichage", { it.cardBadges }, { f, v -> f.copy(cardBadges = v) }),
        Flag("Widget playlists", "Tes playlists en pochettes sur l'écran d'accueil, une touche pour lancer", "Widgets et notifications", { it.playlistWidget }, { f, v -> f.copy(playlistWidget = v) }),
        Flag("Raccourcis sur l'icône", "Appui long sur l'icône de Hush : tes 4 dernières playlists", "Widgets et notifications", { it.iconShortcuts }, { f, v -> f.copy(iconShortcuts = v) }),
        Flag("Où est ce son ?", "Dans le menu d'un son : les playlists où il est déjà rangé", "Playlists", { it.whereIsIt }, { f, v -> f.copy(whereIsIt = v) }),
        Flag("Profils par appareil", "Volume, basses et son 8D réglés tout seuls pour chaque casque ou voiture", "Lecture", { it.deviceProfiles }, { f, v -> f.copy(deviceProfiles = v) }),
        Flag("Widget carré", "Pochette + lecture/pause, dans la liste des widgets", "Widgets et notifications", { it.squareWidget }, { f, v -> f.copy(squareWidget = v) }),
        Flag("Rappel d'écoute", "Le soir : ta journée en musique et ta série", "Widgets et notifications", { it.reminder }, { f, v -> f.copy(reminder = v) }),
        Flag("Sauvegarde auto", "Chaque jour dans Téléchargements/Hush", "Données", { it.autoBackup }, { f, v -> f.copy(autoBackup = v) }),
        Flag("Suivre des artistes", "Bouton « Suivre » sur la page d'un artiste, notif à chaque sortie", "Tes goûts", { it.follow }, { f, v -> f.copy(follow = v) }),
        Flag("Noir pur (AMOLED)", "Fond 100 % noir : plus joli sur ton écran et plus économe", "Affichage", { it.amoled }, { f, v -> f.copy(amoled = v) }),
        Flag("Mode gaucher", "Cœur, menu et boutons du lecteur passent à gauche", "Affichage", { it.leftHanded }, { f, v -> f.copy(leftHanded = v) }),
        Flag("N°1 en France il y a 5 ans", "Sur l'accueil : le tube de la même semaine, 5 ans plus tôt", "Affichage", { it.chartsAgo }, { f, v -> f.copy(chartsAgo = v) }),
        Flag("« Quoi de neuf »", "Après une mise à jour : la liste des nouveautés", "Affichage", { it.whatsNew }, { f, v -> f.copy(whatsNew = v) }),
        Flag("Sons d'ambiance", "Pluie, vagues, bruits doux par-dessus ta musique", "Fun et découverte", { it.ambient }, { f, v -> f.copy(ambient = v) }),
        Flag("Crédits du son", "Producteurs, auteurs, année (MusicBrainz)", "Fun et découverte", { it.credits }, { f, v -> f.copy(credits = v) }),
        Flag("Clip du son", "Ouvrir le clip sur YouTube", "Fun et découverte", { it.clip }, { f, v -> f.copy(clip = v) }),
        Flag("Wrapped de l'année", "Ton année en musique, façon story", "Stats", { it.wrappedYear }, { f, v -> f.copy(wrappedYear = v) }),
        Flag("Tes albums", "Classement de tes albums et ceux écoutés en entier", "Stats", { it.albums }, { f, v -> f.copy(albums = v) }),
        Flag("Exclure des sons des stats", "Appui long sur un son → « Ne pas compter dans mes stats »", "Stats", { it.statsExclude }, { f, v -> f.copy(statsExclude = v) }),
        Flag("Récap du dimanche", "Le dimanche à 20 h : ta semaine en musique", "Widgets et notifications", { it.weeklyRecap }, { f, v -> f.copy(weeklyRecap = v) }),
        Flag("Widget stats", "Minutes du jour, série et son de la semaine", "Widgets et notifications", { it.statsWidget }, { f, v -> f.copy(statsWidget = v) }),
        Flag("Concerts près de chez toi", "Les dates de tes artistes, avec une notif pour les nouvelles en Île-de-France", "Widgets et notifications", { it.concerts }, { f, v -> f.copy(concerts = v) }),
        Flag("Un son par artiste", "Ton son préféré de chacun de tes artistes", "Stats", { it.oneSong }, { f, v -> f.copy(oneSong = v) }),
        Flag("Thèmes de saison", "Halloween, Noël, été sur l'accueil", "Affichage", { it.seasonal }, { f, v -> f.copy(seasonal = v) }),
        Flag("Setlists des concerts", "Les sons joués aux concerts de tes artistes", "Widgets et notifications", { it.setlists }, { f, v -> f.copy(setlists = v) }),
        Flag("Sons souvent passés", "Repère les sons que tu zappes", "Stats", { it.skipDetect }, { f, v -> f.copy(skipDetect = v) }),
        Link("Clé setlist.fm", "Pour les setlists des concerts", "Tes goûts", "setlist concert"),
        Flag("Aléatoire malin", "Jamais deux fois le même artiste d'affilée", "Lecture", { it.smartShuffle }, { f, v -> f.copy(smartShuffle = v) }),
        Flag("Quiz paroles", "Trouve la suite ou le titre de tes sons", "Fun et découverte", { it.lyricsQuiz }, { f, v -> f.copy(lyricsQuiz = v) }),
        Flag("Récompenses", "XP, niveaux et déblocages", "Fun et découverte", { it.rewards }, { f, v -> f.copy(rewards = v) }),
        Link("Taille du texte", "Petit, normal, grand, très grand", "Affichage", "police lisibilite"),
        Link("Clé Ticketmaster", "Pour les concerts de tes artistes", "Tes goûts", "concerts billets"),
        Link("Couleur d'accent", "Vert, lilas, bleu, rose, orange ou couleur de la pochette", "Affichage", "couleur theme lilas violet"),
        Link("Sections de l'accueil", "Afficher, masquer et réordonner les sections", "Affichage", "accueil ordre masquer"),
        Link("Objectif d'écoute", "Minutes de musique visées par jour", "Stats", "objectif minutes"),
        Link("Transitions", "Fondu entre les sons, DJ auto, début et fin des sons", "Lecture", "fondu crossfade dj"),
        Link("Reprise auto", "Relance la musique quand le casque ou la voiture se connecte", "Lecture", "bluetooth casque voiture"),
        Link("Gestes", "Secouer, retourner le téléphone…", "Lecture", "secouer retourner gestes"),
        Link("Heure du rappel", "Choisir l'heure du rappel d'écoute du soir", "Widgets et notifications", "rappel heure"),
        Link("Sons masqués", "Les sons « Pas pour moi »", "Tes goûts", "bloquer pas pour moi masquer"),
        Link("Exporter / importer", "Sauvegarder ou restaurer tout Hush", "Données", "sauvegarde backup restaurer"),
        Link("Appareils", "Casques et voitures reconnus", "Appareils", "bluetooth casque voiture"),
        Link("Diagnostic", "Vérifier que tout marche", "Données", "probleme bug permissions test"),
        Link("Journal des plantages", "Les dernières erreurs de Hush", "Données", "crash bug erreur plantage"),
    )

    fun norm(x: String): String = java.text.Normalizer.normalize(x.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace('’', '\'')

    fun search(q: String): List<Item> {
        val words = norm(q).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return items.filter { i -> val hay = norm(i.title + " " + i.sub + " " + i.section + " " + i.keywords); words.all { hay.contains(it) } }
    }
}

/** Résultats de recherche : les interrupteurs directement, les autres réglages ouvrent leur thème. */
@Composable
fun SettingsSearchResults(query: String, onOpenSection: (String) -> Unit) {
    val f by Features.flags.collectAsState()
    val results = remember(query) { SettingsIndex.search(query) }
    if (results.isEmpty()) {
        Text("Aucun réglage pour « $query ».", color = Txt2, fontSize = 13.sp)
        return
    }
    SettingsCard {
        results.forEach { item ->
            when (item) {
                is SettingsIndex.Flag -> Column {
                    OptionRow(item.title, item.sub, item.get(f)) { if (item.title == "Mode simple") Features.setSimple(it) else Features.set(item.set(f, it)) }
                    Text(item.section, color = Txt3, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                is SettingsIndex.Link -> Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).bounceClick(pressedScale = 0.97f) { onOpenSection(item.section) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(item.sub, color = Txt2, fontSize = 13.sp, lineHeight = 17.sp)
                        Text(item.section, color = Txt3, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("Ouvrir ›", color = Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Champ de recherche des Réglages. */
@Composable
fun SettingsSearchField(query: String, onChange: (String) -> Unit) {
    val shape = Rounded(16)
    Row(
        Modifier.fillMaxWidth().glass(shape, fill = 0.09f, edge = 0.16f).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Search, contentDescription = null, tint = Txt2, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Chercher un réglage", color = Txt3, fontSize = 15.sp)
            androidx.compose.foundation.text.BasicTextField(
                value = query, onValueChange = { onChange(it.take(40)) }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Txt, fontSize = 15.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Txt),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Rounded.Close, contentDescription = "Effacer", tint = Txt2,
                modifier = Modifier.size(20.dp).clip(PillShape).bounceClick(pressedScale = 0.85f) { onChange("") },
            )
        }
    }
}
