package com.theo.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.theo.remote.SharedLink
import com.theo.remote.TidalApi
import com.theo.remote.TidalRemote
import com.theo.remote.TimeCapsule
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------- lien partagé depuis une autre appli

/** Fenêtre « Trouvé sur Tidal » après un « Partager → Hush ». */
@Composable
fun SharedLinkSheet() {
    val ui by SharedLink.ui.collectAsState()
    val u = ui ?: return
    Dialog(onDismissRequest = { SharedLink.close() }) {
        Column(
            Modifier.fillMaxWidth().clip(Rounded(18)).background(Surface2).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Depuis ${u.source}" + (u.how?.let { " · $it" } ?: ""), color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            when {
                u.loading -> {
                    Text("Recherche sur Tidal…", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    SkeletonList(2)
                }
                u.best == null -> {
                    Text(u.error ?: "Pas trouvé sur Tidal", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Text(u.asked, color = Txt2, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    PlatformChips(u)
                    PillButton("Fermer", filled = false) { SharedLink.close() }
                }
                else -> {
                    val r = u.best
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Cover(model = r.coverUrl, radius = 8.dp, modifier = Modifier.size(76.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.fullTitle, color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(r.artistLine, color = Txt2, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("Lire") { TidalRemote.play(r); SharedLink.close() }
                        PillButton("À la file", filled = false) { TidalRemote.addToQueue(r); TidalRemote.showToast("Ajouté à la file"); SharedLink.close() }
                        PillButton("Playlist", filled = false) { PlaylistEdit.pick(listOf(r)); SharedLink.close() }
                    }
                    PlatformChips(u)
                    if (u.others.isNotEmpty()) {
                        Text("Pas le bon ?", color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                        u.others.forEach { o -> OtherRow(o) { SharedLink.pick(o) } }
                    }
                }
            }
        }
    }
}

/** « Dispo sur » : les plateformes où on trouve ce son, chacune ouvre son lien. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PlatformChips(u: SharedLink.Ui) {
    if (!u.platformsLoading && u.platforms.isEmpty()) return
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    Text("Dispo sur", color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
    if (u.platformsLoading) {
        Text("Recherche des plateformes…", color = Txt3, fontSize = 13.sp)
        return
    }
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        u.platforms.forEach { (name, link) ->
            Text(
                name, color = Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(PillShape).background(Color.White.copy(alpha = 0.1f))
                    .bounceClick { runCatching { uri.openUri(link) } }.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun OtherRow(r: TidalApi.Result, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).bounceClick(pressedScale = 0.98f, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(model = r.coverUrl, radius = 6.dp, modifier = Modifier.size(40.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(r.fullTitle, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(r.artistLine, color = Txt2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------- message au futur toi

/** Écrire un message attaché au son en cours. */
@Composable
fun CapsuleWriteDialog() {
    val w by TimeCapsule.writing.collectAsState()
    val (title, artist) = w ?: return
    var text by remember(w) { mutableStateOf("") }
    var choice by remember(w) { mutableIntStateOf(2) }
    val options = listOf(30 to "Dans 1 mois", 182 to "Dans 6 mois", 365 to "Dans 1 an")
    fun close() { TimeCapsule.writing.value = null }
    Dialog(onDismissRequest = { close() }) {
        Column(
            Modifier.fillMaxWidth().clip(Rounded(18)).background(Surface2).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Message au futur toi", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                "Il ressortira quand tu réécouteras « $title », passé la date choisie.",
                color = Txt2, fontSize = 13.sp, lineHeight = 18.sp,
            )
            Box(
                Modifier.fillMaxWidth().heightIn(min = 110.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.06f)).padding(12.dp),
            ) {
                if (text.isEmpty()) Text("Ex : l'été à Rotterdam, en train avec les potes", color = Txt3, fontSize = 15.sp)
                BasicTextField(
                    value = text, onValueChange = { text = it.take(400) },
                    textStyle = TextStyle(color = Txt, fontSize = 15.sp, lineHeight = 21.sp),
                    cursorBrush = SolidColor(Txt),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEachIndexed { i, (_, label) ->
                    val on = i == choice
                    Text(
                        label, color = if (on) Ink else Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) Txt else Color.White.copy(alpha = 0.08f))
                            .bounceClick { choice = i }.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            val waiting = TimeCapsule.pending(title, artist)
            if (waiting > 0) Text("$waiting message${if (waiting > 1) "s" else ""} déjà en attente sur ce son", color = Txt3, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Garder le message") {
                    if (text.isNotBlank()) { TimeCapsule.add(title, artist, text, options[choice].first); close() }
                }
                PillButton("Annuler", filled = false) { close() }
            }
        }
    }
}

/** Le message qui ressort quand tu réécoutes le son. */
@Composable
fun CapsuleRevealDialog() {
    val c by TimeCapsule.reveal.collectAsState()
    val cap = c ?: return
    fun close() { TimeCapsule.reveal.value = null }
    Dialog(onDismissRequest = { close() }) {
        Column(
            Modifier.fillMaxWidth().clip(Rounded(18)).background(Surface2).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Message de toi · écrit le " + SimpleDateFormat("d MMMM yyyy", Locale.FRANCE).format(Date(cap.createdAt)),
                color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            )
            Text("${cap.title} · ${cap.artist}", color = Ok, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(cap.text, color = Txt, fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Fermer") { close() }
                PillButton("Le renvoyer dans 1 an", filled = false) { TimeCapsule.again(cap); TidalRemote.showToast("Il ressortira dans un an"); close() }
            }
        }
    }
}
