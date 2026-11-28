package com.theo.remote.ui

import com.theo.remote.addToQueue
import com.theo.remote.play
import com.theo.remote.playTitle

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.Blocked
import com.theo.remote.TidalApi
import com.theo.remote.TidalRemote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Menu d'un son (appui long) : lire, file, playlist, artiste, « Pas pour moi ». */
object TrackMenu {
    data class Target(val title: String, val artist: String, val cover: Any?, val result: TidalApi.Result? = null, val album: String? = null)

    private val _target = MutableStateFlow<Target?>(null)
    val target: StateFlow<Target?> = _target
    fun open(t: Target) { _target.value = t }
    fun open(r: TidalApi.Result) { _target.value = Target(r.fullTitle, r.artistLine, r.coverUrl, r) }
    fun close() { _target.value = null }
}

@Composable
fun TrackMenuSheet(backdrop: Backdrop? = null) {
    val liquid = backdrop != null && liquidOn()
    val t by TrackMenu.target.collectAsState()
    var last by remember { mutableStateOf<TrackMenu.Target?>(null) }
    t?.let { last = it }
    val open = LocalOpenDetail.current
    // ajouté à l'ouverture (et pas au démarrage) : le retour ferme d'abord le menu, même ouvert depuis le lecteur
    if (t != null) BackHandler { TrackMenu.close() }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(t != null, enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (liquid) 0.3f else 0.55f)).bounceClick(pressedScale = 1f) { TrackMenu.close() })
        }
        AnimatedVisibility(
            t != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 380f)) { it } + fadeIn(),
            exit = slideOutVertically(tween(200)) { it } + fadeOut(),
        ) {
            val x = last ?: return@AnimatedVisibility
            val shape = if (liquid) Rounded(30) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // aperçu : la pochette en grand au-dessus du menu, qui jaillit avec un petit rebond
            val pop = remember(x) { androidx.compose.animation.core.Animatable(0.6f) }
            LaunchedEffect(x) { pop.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 320f)) }
            val glow = rememberUrlCoverColors(x.cover as? String).a
            Cover(
                model = x.cover, radius = 20.dp,
                modifier = Modifier.statusBarsPadding().padding(top = 8.dp).size(230.dp)
                    .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
                    .shadow(34.dp, Rounded(20), ambientColor = glow, spotColor = glow),
            )
            Spacer(Modifier.height(16.dp))
            // verre liquide : une plaque de verre flottante (comme le menu ⋯ du lecteur) ; sinon la feuille habituelle
            Box(
                if (liquid) Modifier.navigationBarsPadding().padding(10.dp).fillMaxWidth().heightIn(max = 470.dp).shadow(26.dp, shape).clip(shape)
                else Modifier.fillMaxWidth().heightIn(max = 520.dp).clip(shape),
            ) {
            if (liquid) {
                LiquidGlass(backdrop!!, shape, cornerRadius = 30.dp, modifier = Modifier.matchParentSize(), blur = 18.dp, depth = 22.dp, refraction = 16.dp, chroma = 0.25f)
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.45f)))
            }
            Column(
                (if (liquid) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().background(Color(0xFF15151C)).glass(shape, fill = 0.06f, edge = 0.12f).navigationBarsPadding())
                    .verticalScroll(rememberScrollState())
                    .padding(Space.gutter),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(width = 40.dp, height = 4.dp).clip(PillShape).background(Txt3))
                Spacer(Modifier.height(Space.s))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cover(model = x.cover, radius = 8.dp, modifier = Modifier.size(54.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(x.title, color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(x.artist, color = Txt2, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        FirstListenLine(x.title, x.artist)
                    }
                }
                Spacer(Modifier.height(Space.s))
                MenuItem(Icons.Rounded.PlayArrow, "Lire") {
                    TrackMenu.close(); x.result?.let { TidalRemote.play(it) } ?: TidalRemote.playTitle(x.title, x.artist)
                }
                x.result?.let { r ->
                    MenuItem(Icons.AutoMirrored.Rounded.QueueMusic, "Ajouter à la file") { TrackMenu.close(); TidalRemote.addToQueue(r) }
                }
                MenuItem(Icons.AutoMirrored.Rounded.PlaylistAdd, "Ajouter à une playlist") {
                    TrackMenu.close(); x.result?.let { PlaylistEdit.pick(listOf(it)) } ?: PlaylistEdit.pickCurrent(x.title, x.artist)
                }
                val albumOf = x.result?.album ?: x.album
                if (!albumOf.isNullOrBlank()) MenuItem(Icons.Rounded.Album, "Ouvrir l'album") {
                    TrackMenu.close(); open(Detail.AlbumByName(albumOf, mainArtist(x.artist)))
                }
                MenuItem(Icons.Rounded.Person, "Voir l'artiste") {
                    TrackMenu.close()
                    val d = x.result?.let { Detail.artistOf(it) } ?: Detail.ArtistByName(mainArtist(x.artist))
                    d?.let(open)
                }
                val flags by com.theo.remote.Features.flags.collectAsState()
                if (flags.notes) {
                    val notes by TrackNotes.notes.collectAsState()
                    val has = notes.containsKey(com.theo.remote.History.keyOf(x.title, x.artist))
                    MenuItem(Icons.Rounded.EditNote, if (has) "Modifier ta note" else "Ajouter une note") { TrackMenu.close(); TrackNotes.edit(x.title, x.artist) }
                }
                if (flags.ratings) {
                    val rated by com.theo.remote.Ratings.items.collectAsState()
                    val e = rated[com.theo.remote.Ratings.keyOf(com.theo.remote.Ratings.Kind.Track, x.title, x.artist)]
                    MenuItem(Icons.Rounded.Star, if (e != null) "Ta note : ${starsLabel(e.stars)}" else "Noter ce son") {
                        TrackMenu.close(); com.theo.remote.Ratings.edit(com.theo.remote.Ratings.Kind.Track, x.title, x.artist, x.cover as? String)
                    }
                }
                if (flags.whereIsIt) MenuItem(Icons.Rounded.TravelExplore, "Où est ce son ?") { TrackMenu.close(); WhereIsIt.open(x.title, x.artist, x.result?.id) }
                val albumName = x.result?.album ?: x.album
                if (flags.credits) MenuItem(Icons.Rounded.Info, "Crédits du son") { TrackMenu.close(); Credits.open(x.title, x.artist) }
                if (flags.clip) {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    MenuItem(Icons.Rounded.OndemandVideo, "Voir le clip") { TrackMenu.close(); Credits.openClip(ctx, x.title, x.artist) }
                }
                if (flags.statsExclude) {
                    val excluded by com.theo.remote.StatsExclude.items.collectAsState()
                    val isEx = excluded.any { it.key == com.theo.remote.History.keyOf(x.title, x.artist) }
                    MenuItem(Icons.Rounded.VisibilityOff, if (isEx) "Recompter dans mes stats" else "Ne pas compter dans mes stats") {
                        TrackMenu.close()
                        if (isEx) com.theo.remote.StatsExclude.include(com.theo.remote.History.keyOf(x.title, x.artist))
                        else com.theo.remote.StatsExclude.exclude(x.title, x.artist)
                        TidalRemote.showToast(if (isEx) "Ce son compte à nouveau" else "Ce son ne compte plus dans tes stats")
                    }
                }
                MenuItem(Icons.Rounded.Block, "Pas pour moi", tint = Ko) {
                    TrackMenu.close()
                    Blocked.block(x.title, x.artist)
                    TidalRemote.showToast("On ne te proposera plus « ${x.title} »")
                }
            }
            }
            }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, tint: Color = Txt, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(Rounded(14)).bounceClick(pressedScale = 0.97f, onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = tint, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}
