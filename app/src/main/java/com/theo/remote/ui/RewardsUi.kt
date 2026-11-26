package com.theo.remote.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.Features
import com.theo.remote.History
import com.theo.remote.Rewards
import com.theo.remote.TidalRemote
import kotlinx.coroutines.delay

private fun fmtXp(x: Long) = "%,d".format(x).replace(',', ' ')

/** Barre de niveau en haut des Stats. */
@Composable
fun LevelCard() {
    val f by Features.flags.collectAsState()
    if (!f.rewards) return
    val xp by Rewards.xp.collectAsState()
    val x = xp ?: return
    val lvl = Rewards.levelOf(x.total)
    val from = Rewards.xpFor(lvl)
    val to = Rewards.xpFor(lvl + 1)
    val prog = ((x.total - from).toFloat() / (to - from)).coerceIn(0f, 1f)
    val next = Rewards.UNLOCKS.filter { it.level > lvl }.minByOrNull { it.level }
    val shape = Rounded(22)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(flatOr(listOf(Ok.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f))))
            .glass(shape, fill = 0.03f, edge = 0.14f)
            .bounceClick(pressedScale = 0.97f) { Panels.show(Panels.Kind.Rewards) }
            .padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("Niveau $lvl", color = Ok, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                Text(Rewards.title(lvl), color = Txt, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            }
            Text("${fmtXp(x.total)} XP", color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(PillShape).background(Color.White.copy(alpha = 0.10f))) {
            Box(Modifier.fillMaxWidth(prog).height(8.dp).clip(PillShape).background(Ok))
        }
        Text(
            "Encore ${fmtXp(to - x.total)} XP pour le niveau ${lvl + 1}" + (next?.let { " · prochain déblocage : ${it.label} (niv. ${it.level})" } ?: ""),
            color = Txt3, fontSize = 12.sp, lineHeight = 16.sp,
        )
    }
}

@Composable
fun RewardsPanel() {
    val ctx = LocalContext.current
    val xp by Rewards.xp.collectAsState()
    val eq by Rewards.equipped.collectAsState()
    LaunchedEffect(Unit) { Rewards.recompute(ctx) }
    val x = xp
    val lvl = x?.let { Rewards.levelOf(it.total) } ?: 1
    val list = rememberLazyListState()
    DismissWhenAtTop(list)
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), state = list, contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        item {
            PanelTitle("Récompenses")
            LevelCard()
            Spacer(Modifier.height(Space.s))
            if (x != null) Column(Modifier.fillMaxWidth().glass(Rounded(20), fill = 0.06f, edge = 0.10f).padding(Space.m), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("D'où vient ton XP", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                XpLine("Minutes écoutées", x.listen)
                XpLine("Séries de jours", x.streak)
                XpLine("Artistes découverts", x.artists)
                XpLine("Défis réussis", x.challenges)
                XpLine("Badges", x.badges)
                XpLine("Blind test et quiz", x.games)
            }
            Spacer(Modifier.height(Space.s))
            Text("Toucher une récompense débloquée pour l'équiper, ou la retirer.", color = Txt3, fontSize = 12.sp)
        }
        Rewards.CATEGORIES.forEach { (cat, label) ->
            val items = Rewards.UNLOCKS.filter { it.cat == cat }
            item(key = "h$cat") { Text(label, color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = Space.m)) }
            items(items, key = { it.id }) { u ->
                val open = lvl >= u.level
                val on = eq[cat] == u.id && open
                val shape = Rounded(16)
                Row(
                    Modifier.fillMaxWidth().clip(shape)
                        .then(if (on) Modifier.background(Ok.copy(alpha = 0.18f)) else Modifier)
                        .glass(shape, fill = 0.06f, edge = if (on) 0.4f else 0.10f)
                        .bounceClick(pressedScale = 0.97f) {
                            if (!open) TidalRemote.showToast("Débloqué au niveau ${u.level}")
                            else Rewards.equip(ctx, cat, if (on) null else u.id)
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (open) (if (on) Icons.Rounded.CheckCircle else Icons.Rounded.CardGiftcard) else Icons.Rounded.Lock,
                        contentDescription = if (open) (if (on) "Équipé" else "Débloqué") else "Verrouillé",
                        tint = if (on) Ok else if (open) Txt else Txt3, modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(u.label, color = if (open) Txt else Txt3, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(u.desc, color = Txt3, fontSize = 12.sp)
                    }
                    Text(if (on) "Équipé" else if (open) "Équiper" else "Niv. ${u.level}", color = if (on) Ok else Txt2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                // réglages de certaines récompenses une fois équipées
                if (on && u.id == "tab:fifth") RewardChoices(
                    listOf("local" to "Fichiers locaux", "blind" to "Blind test", "tools" to "Outils"), Rewards.fifthTab(),
                ) { Rewards.setFifthTab(it) }
                if (on && u.id == "title:custom") RewardChoices(
                    Rewards.TITLES.map { it to it }, Rewards.customTitle() ?: Rewards.levelTitle(lvl),
                ) { Rewards.setCustomTitle(it) }
            }
        }
    }
}

@Composable
private fun XpLine(label: String, v: Long) {
    Row {
        Text(label, color = Txt2, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text("${fmtXp(v)} XP", color = Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** Montée de niveau : petite fête et ce que tu viens de débloquer. */
@Composable
fun LevelUpDialog() {
    val ctx = LocalContext.current
    val f by Features.flags.collectAsState()
    val entries by History.entries.collectAsState()
    val xp by Rewards.xp.collectAsState()
    LaunchedEffect(entries.size / 5) { delay(1500); runCatching { Rewards.recompute(ctx) } }
    var shown by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(xp) { if (shown == null) shown = Rewards.levelUp() }
    val up = shown ?: return
    if (!f.rewards) return
    val (from, to) = up
    val gained = Rewards.UNLOCKS.filter { it.level in (from + 1)..to }
    val pop = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 300f)) }
    fun close() { Rewards.ackLevel(to); shown = null }
    androidx.compose.ui.window.Dialog(onDismissRequest = { close() }) {
        Column(
            Modifier.fillMaxWidth().clip(Rounded(28)).background(Color(0xFF14151F))
                .border(1.dp, Ok.copy(alpha = 0.5f), Rounded(28)).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.EmojiEvents, contentDescription = null, tint = Ok, modifier = Modifier.size(64.dp).scale(pop.value))
            Text(if (from == 0) "Tu es niveau $to !" else "Niveau $to !", color = Txt, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
            Text(Rewards.levelTitle(to), color = Ok, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            if (from == 0) Text("Ton XP a été calculée depuis tout ton historique.", color = Txt2, fontSize = 13.sp, textAlign = TextAlign.Center)
            if (gained.isNotEmpty()) {
                Text("Tu débloques :", color = Txt2, fontSize = 15.sp)
                gained.forEach { Text("+ ${it.label}", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(4.dp))
            PillButton(if (gained.isNotEmpty()) "Voir mes récompenses" else "Super") {
                close()
                if (gained.isNotEmpty()) Panels.show(Panels.Kind.Rewards)
            }
        }
    }
}


/** Pastilles de choix sous une récompense équipée. */
@Composable
private fun RewardChoices(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(start = 36.dp, top = 2.dp, bottom = 6.dp),
    ) {
        items(options.size) { i ->
            val (id, label) = options[i]
            val on = id == selected
            Box(
                Modifier.clip(BtnShape)
                    .then(if (on) Modifier.background(Txt) else Modifier.glass(BtnShape, fill = 0.08f, edge = 0.14f))
                    .bounceClick { onPick(id) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) { Text(label, color = if (on) Ink else Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        }
    }
}
