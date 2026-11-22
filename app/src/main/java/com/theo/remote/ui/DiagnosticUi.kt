package com.theo.remote.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.theo.remote.AudioFx
import com.theo.remote.CrashLog
import com.theo.remote.Features
import com.theo.remote.HushService
import com.theo.remote.LastFm
import com.theo.remote.TidalAuth
import com.theo.remote.TidalRemote
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Lvl { Ok, Warn, Info }

private data class Check(val lvl: Lvl, val title: String, val detail: String, val fix: String? = null, val action: ((Context) -> Unit)? = null)

private fun open(ctx: Context, i: Intent) {
    runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { runCatching { ctx.startActivity(appDetails(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

private fun appDetails(ctx: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))

private fun runChecks(ctx: Context): List<Check> {
    val st = TidalRemote.state.value
    val f = Features.flags.value
    val out = ArrayList<Check>()

    val listener = TidalRemote.hasPermission(ctx)
    out += if (listener) Check(Lvl.Ok, "Accès aux notifications", "Hush peut piloter le lecteur de Tidal")
    else Check(Lvl.Warn, "Accès aux notifications", "Indispensable : sans ça, Hush ne voit pas ce que joue Tidal", "Autoriser") { open(it, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

    val tidalInstalled = ctx.packageManager.getLaunchIntentForPackage("com.aspiro.tidal") != null
    out += when {
        !tidalInstalled -> Check(Lvl.Warn, "Tidal", "L'appli Tidal n'est pas installée sur ce téléphone")
        st.connected -> Check(Lvl.Ok, "Tidal", "Connecté au lecteur de Tidal")
        else -> Check(Lvl.Info, "Tidal", "Pas de lecture en cours : lance un son pour que Hush s'y branche", "Ouvrir Tidal") { TidalRemote.openTidal(it) }
    }

    val logged = TidalAuth.loggedIn.value
    out += if (logged) Check(Lvl.Ok, "Compte Tidal", "Connecté : playlists, favoris et cœurs synchronisés")
    else Check(Lvl.Info, "Compte Tidal", "Pas connecté : tes playlists et favoris Tidal ne s'affichent pas (à faire dans la Biblio)")

    out += if (HushService.running) Check(Lvl.Ok, "Service en fond", "Ta file et tes stats continuent quand Hush est fermé")
    else Check(Lvl.Warn, "Service en fond", "Arrêté : la file Hush et l'historique ne marchent pas appli fermée", "Relancer") { HushService.start(it) }

    val notifs = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    out += if (notifs) Check(Lvl.Ok, "Notifications", "Autorisées")
    else Check(Lvl.Warn, "Notifications", "Bloquées : pas de notif de lecture, de sorties ni de rappel", "Autoriser") {
        open(it, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, it.packageName))
    }

    val pm = ctx.getSystemService(PowerManager::class.java)
    val battery = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
    out += if (battery) Check(Lvl.Ok, "Batterie", "Hush n'est pas endormi par Android")
    else Check(Lvl.Info, "Batterie", "Android peut mettre Hush en veille : la file peut s'arrêter. Choisis « Non restreinte » pour Hush.", "Réglages") { open(it, appDetails(it)) }

    out += if (com.theo.remote.Alarm.canSchedule(ctx)) Check(Lvl.Ok, "Réveil", "Les alarmes à l'heure exacte sont autorisées")
    else Check(Lvl.Warn, "Réveil", "Alarmes exactes refusées : le réveil peut sonner en retard", "Autoriser") {
        if (Build.VERSION.SDK_INT >= 31) open(it, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + it.packageName)))
    }

    val mic = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    out += if (mic) Check(Lvl.Ok, "Micro", "« Quel est ce son ? » et la pochette animée peuvent écouter")
    else Check(Lvl.Info, "Micro", "Pas autorisé : « Quel est ce son ? » et la pochette animée ne marchent pas", "Réglages") { open(it, appDetails(it)) }

    if (f.bass || f.spatial) {
        out += when (AudioFx.status.value) {
            AudioFx.Status.Tidal -> Check(Lvl.Ok, "Effets audio", "Branchés sur le son de Tidal")
            AudioFx.Status.Global -> Check(Lvl.Info, "Effets audio", "Branchés sur la sortie générale : si tu n'entends rien, relance un son dans Tidal")
            AudioFx.Status.Blocked -> Check(Lvl.Warn, "Effets audio", "Le téléphone bloque les effets pour l'instant : relance un son dans Tidal puis réessaie")
            AudioFx.Status.Off -> Check(Lvl.Info, "Effets audio", "Aucun effet actif (basses et 8D coupés)")
        }
    }

    val scrobbles = LastFm.scrobbles.value.size
    out += if (scrobbles > 0) Check(Lvl.Ok, "Last.fm", "$scrobbles écoutes importées dans tes stats")
    else Check(Lvl.Info, "Last.fm", "Pas d'import : tes stats commencent à ton installation de Hush")

    return out
}

@Composable
fun DiagnosticPanel() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } } // se met à jour quand tu reviens des réglages
    val st by TidalRemote.state.collectAsState()
    val checks = remember(tick, st.connected, st.hasPermission) { runChecks(ctx) }
    var crashes by remember { mutableStateOf(CrashLog.list()) }
    val warn = checks.count { it.lvl == Lvl.Warn }
    val list = rememberLazyListState()
    DismissWhenAtTop(list)
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(), state = list,
        contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        item {
            PanelTitle("Diagnostic")
            Text(
                if (warn == 0) "Tout est bon ✓" else "$warn chose${if (warn > 1) "s" else ""} à corriger",
                color = if (warn == 0) Ok else Warn, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold,
            )
            Spacer(Modifier.height(Space.s))
        }
        item { WhatHappened() }
        item { DirectPlayCard() }
        items(checks, key = { it.title }) { c -> CheckRow(c) }
        item {
            Spacer(Modifier.height(Space.l))
            Text("Journal des plantages", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                if (crashes.isEmpty()) "Aucun plantage enregistré" else "Les ${crashes.size} derniers. Copie-les et envoie-les-moi pour que je trouve la cause.",
                color = Txt2, fontSize = 13.sp, lineHeight = 18.sp,
            )
            if (crashes.isNotEmpty()) {
                Spacer(Modifier.height(Space.s))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    PillButton("Tout copier") {
                        val text = crashes.joinToString("\n\n") { CrashLog.format(it) }
                        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Plantages Hush", text))
                        TidalRemote.showToast("Copié : colle-le dans la conversation")
                    }
                    PillButton("Effacer", filled = false) { CrashLog.clear(); crashes = emptyList() }
                }
            }
        }
        items(crashes, key = { it.at }) { c ->
            var open by remember { mutableStateOf(false) }
            Column(
                Modifier.fillMaxWidth().glass(Rounded(16), fill = 0.07f, edge = 0.12f)
                    .bounceClick(pressedScale = 0.98f) { open = !open }.padding(Space.m),
            ) {
                Text(SimpleDateFormat("d MMM à HH:mm", Locale.FRANCE).format(Date(c.at)), color = Txt3, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(c.title, color = Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = if (open) 3 else 1, overflow = TextOverflow.Ellipsis)
                if (open) {
                    Spacer(Modifier.height(6.dp))
                    Text(c.text.take(2500), color = Txt2, fontSize = 10.sp, fontFamily = FontFamily.Monospace, lineHeight = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun CheckRow(c: Check) {
    val ctx = LocalContext.current
    val color = when (c.lvl) { Lvl.Ok -> Ok; Lvl.Warn -> Warn; Lvl.Info -> Txt3 }
    Row(
        Modifier.fillMaxWidth().glass(Rounded(16), fill = 0.06f, edge = 0.10f).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(PillShape).background(color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(c.detail, color = Txt2, fontSize = 12.sp, lineHeight = 16.sp)
        }
        if (c.fix != null && c.action != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                c.fix, color = if (c.lvl == Lvl.Warn) Color(0xFF07080C) else Txt, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.clip(PillShape)
                    .then(if (c.lvl == Lvl.Warn) Modifier.background(Warn) else Modifier.glass(PillShape, fill = 0.10f, edge = 0.16f))
                    .bounceClick(pressedScale = 0.9f) { c.action?.invoke(ctx) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

/** Carte dans les Réglages → Données. */
@Composable
fun DiagnosticCard() {
    val n = remember { CrashLog.list().size }
    val shape = Rounded(22)
    Row(
        Modifier.fillMaxWidth().glass(shape, fill = 0.07f, edge = 0.12f)
            .bounceClick(pressedScale = 0.97f) { Panels.show(Panels.Kind.Diagnostic) }.padding(Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Diagnostic", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                "Vérifie que tout marche et dit quoi corriger" + if (n > 0) " · $n plantage${if (n > 1) "s" else ""} enregistré${if (n > 1) "s" else ""}" else "",
                color = Txt2, fontSize = 13.sp, lineHeight = 18.sp,
            )
        }
        Text("Ouvrir ›", color = Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}


/** « Ce qui s'est passé » : les sauts, rattrapages et mauvaises versions des dernières 24 h, par cause. */
@Composable
private fun WhatHappened() {
    val events by com.theo.remote.Diag.events.collectAsState()
    var range by remember { mutableIntStateOf(24) }
    var open by remember { mutableStateOf<com.theo.remote.Diag.Kind?>(null) }
    val since = System.currentTimeMillis() - range * 3600_000L
    val recent = events.filter { it.at > since }
    val groups = com.theo.remote.Diag.Kind.entries.mapNotNull { k -> recent.filter { it.kind == k }.takeIf { it.isNotEmpty() }?.let { k to it } }
    Column(
        Modifier.fillMaxWidth().glass(Rounded(16), fill = 0.07f, edge = 0.12f).padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ce qui s'est passé", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            listOf(24 to "24 h", 168 to "7 j").forEach { (h, l) ->
                Text(
                    l, color = if (range == h) Ink else Txt2, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 6.dp).clip(BtnShape)
                        .then(if (range == h) Modifier.background(Txt) else Modifier)
                        .bounceClick { range = h }.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
        if (groups.isEmpty()) Text("Rien à signaler.", color = Txt2, fontSize = 14.sp)
        groups.forEach { (k, list) ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).bounceClick(pressedScale = 0.98f) { open = if (open == k) null else k }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${list.size}", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.width(38.dp))
                    Text(k.label, color = Txt, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
                if (open == k) {
                    Text(k.explain, color = Txt2, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(start = 38.dp, top = 2.dp))
                    list.takeLast(8).asReversed().forEach { e ->
                        Text(
                            SimpleDateFormat("EEE HH:mm", Locale.FRANCE).format(Date(e.at)) + if (e.detail.isNotBlank()) " · ${e.detail}" else "",
                            color = Txt3, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 38.dp, top = 2.dp),
                        )
                    }
                }
            }
        }
        // état de l'enchaînement sans blanc
        val gap = com.theo.remote.TidalRemote.gaplessState()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Enchaînement sans blanc : " + when { gap >= 0 -> "actif"; gap == -2 -> "refusé par Tidal"; else -> "pas encore testé" },
                color = Txt2, fontSize = 13.sp, modifier = Modifier.weight(1f),
            )
            if (gap == -2) Text(
                "Retester", color = Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).bounceClick { com.theo.remote.TidalRemote.resetGapless() }.padding(4.dp),
            )
        }
    }
    Spacer(Modifier.height(Space.s))
}


/** Test : Tidal donne-t-il à Hush le son complet (pour jouer sans l'appli Tidal) ? */
@Composable
private fun DirectPlayCard() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val hasPlayback = com.theo.remote.TidalAuth.scopes().contains("playback")
    Column(
        Modifier.fillMaxWidth().glass(Rounded(16), fill = 0.07f, edge = 0.12f).padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Lecture directe (test)", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            "Vérifie si Tidal donne à Hush le son complet, pour qu'il puisse jouer sans l'appli Tidal. Lance un son avant.",
            color = Txt2, fontSize = 13.sp, lineHeight = 18.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!hasPlayback) PillButton("1. Autoriser la lecture", filled = false) {
                com.theo.remote.TidalAuth.login(ctx, "playback entitlements.read")
            }
            PillButton(if (busy) "Test…" else if (hasPlayback) "Tester" else "2. Tester") {
                if (busy) return@PillButton
                busy = true
                scope.launch { result = com.theo.remote.DirectPlay.probe(); busy = false }
            }
        }
        result?.let { r ->
            Text(r, color = Txt2, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            PillButton("Copier le résultat", filled = false) {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("Test lecture directe", r))
                com.theo.remote.TidalRemote.showToast("Copié : colle-le moi dans la conversation")
            }
        }
    }
    Spacer(Modifier.height(Space.s))
}
