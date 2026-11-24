package com.theo.remote.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.foundation.background
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.CrashLog
import com.theo.remote.Features
import com.theo.remote.TidalRemote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// ---------------------------------------------------------------- réseau

/** En ligne ou pas (pour prévenir au lieu d'afficher des écrans vides). */
object Net {
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online
    private var started = false

    fun init(context: Context) {
        if (started) return
        started = true
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        fun check() {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            _online.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
        runCatching {
            check()
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { check() }
                override fun onLost(network: Network) { _online.value = false }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { check() }
            })
        }
    }
}

/** Bandeau « hors ligne », avec un raccourci vers tes fichiers locaux. */
@Composable
fun OfflineBanner() {
    val online by Net.online.collectAsState()
    val f by Features.flags.collectAsState()
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Space.gutter, vertical = 6.dp)) {
        AnimatedVisibility(!online, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
            val shape = PillShape
            Row(
                Modifier.fillMaxWidth().clip(shape).glass(shape, fill = 0.16f, edge = 0.2f)
                    .bounceClick(pressedScale = 0.98f) { if (f.localFiles) Panels.show(Panels.Kind.Local) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = Txt, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (f.localFiles) "Hors ligne · tes fichiers locaux restent dispo ›" else "Hors ligne · Tidal reprendra au retour du réseau",
                    color = Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- rapport de bug

object BugReport {
    /** Tout ce qu'il faut pour comprendre un bug, prêt à coller. */
    fun build(context: Context): String {
        val pm = context.packageManager
        val ver = runCatching { pm.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
        val tidal = runCatching { pm.getPackageInfo(TidalRemote.TIDAL_PACKAGE, 0).versionName }.getOrNull() ?: "absent"
        val st = TidalRemote.state.value
        val sb = StringBuilder()
        sb.appendLine("=== Rapport Hush ===")
        sb.appendLine("Hush $ver · Tidal $tidal · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}")
        sb.appendLine("Session : ${st.sessionPackage ?: "aucune"} · connecté ${st.connected} · en lecture ${st.isPlaying} · local ${com.theo.remote.LocalPlayer.active}")
        sb.appendLine("En ligne : ${Net.online.value}")
        sb.appendLine()
        sb.appendLine("--- Journal ---")
        st.log.forEach { sb.appendLine(it) }
        val crashes = CrashLog.list()
        sb.appendLine()
        sb.appendLine("--- Plantages (${crashes.size}) ---")
        crashes.take(3).forEach { sb.appendLine(CrashLog.format(it).take(4000)); sb.appendLine() }
        return sb.toString()
    }

    fun copy(context: Context) {
        val cm = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Rapport Hush", build(context)))
        TidalRemote.showToast("Rapport copié : colle-le dans ton message")
    }
}

@Composable
fun BugReportCard() {
    val ctx = LocalContext.current
    val shape = Rounded(22)
    Row(
        Modifier.fillMaxWidth().glass(shape, fill = 0.07f, edge = 0.12f)
            .bounceClick(pressedScale = 0.97f) { BugReport.copy(ctx) }.padding(Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Rapport de bug", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            Text("Copie le journal et les plantages en un tap, pour me l'envoyer", color = Txt2, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Text("Copier", color = Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------- réglages repliables

/** Sections ouvertes dans les Réglages, et réglages que tu touches le plus. */
object SettingsFold {
    private val _open = MutableStateFlow<Set<String>>(emptySet())
    val open: StateFlow<Set<String>> = _open
    private var prefs: android.content.SharedPreferences? = null
    private val counts = HashMap<String, Int>()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("hush_settings_use", Context.MODE_PRIVATE)
        prefs = p
        p.all.forEach { (k, v) -> if (v is Int) counts[k] = v }
    }

    /** Venu d'une recherche dans les Réglages : on montre tout, même les options repliées. */
    val showAll = MutableStateFlow(false)

    fun used(title: String) = (counts[title] ?: 0) > 0

    fun toggle(title: String) { _open.value = if (title in _open.value) _open.value - title else _open.value + title }
    fun expand(title: String) { _open.value = _open.value + title }

    fun bump(title: String) {
        val n = (counts[title] ?: 0) + 1
        counts[title] = n
        prefs?.edit()?.putInt(title, n)?.apply()
    }

    /** Les réglages les plus touchés (sinon une sélection par défaut). */
    fun mostUsed(): List<SettingsIndex.Flag> {
        val flags = SettingsIndex.items.filterIsInstance<SettingsIndex.Flag>()
        val used = flags.filter { (counts[it.title] ?: 0) > 0 }.sortedByDescending { counts[it.title] ?: 0 }
        val defaults = listOf("Mode économie", "Notification enrichie", "Fichiers locaux", "Ouvrir Tidal avec Hush", "Noir pur (AMOLED)", "Retour haptique")
        val fill = defaults.mapNotNull { d -> flags.firstOrNull { it.title == d } }
        return (used + fill).distinctBy { it.title }.take(6)
    }
}

/** Un thème des Réglages qu'on ouvre et ferme d'un tap. */
@Composable
fun FoldSection(title: String, content: @Composable () -> Unit) {
    val open by SettingsFold.open.collectAsState()
    val isOpen = title in open
    val angle by animateFloatAsState(if (isOpen) 180f else 0f, label = "fold")
    val anchors = LocalSettingsAnchors.current
    Column(
        Modifier.fillMaxWidth().then(
            if (anchors != null) Modifier.onGloballyPositioned { anchors[title] = it.positionInParent().y.toInt() } else Modifier
        ),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        // ligne façon iOS : pastille colorée + titre + flèche, dans un bloc arrondi
        val (icon, tint) = sectionStyle(title)
        val shape = Rounded(18)
        Row(
            Modifier.fillMaxWidth().glass(shape, fill = if (isOpen) 0.12f else 0.08f)
                .bounceClick(pressedScale = 0.98f) { SettingsFold.toggle(title) }
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(tint, androidx.compose.ui.graphics.lerp(tint, androidx.compose.ui.graphics.Color.Black, 0.25f)))),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(12.dp))
            Text(
                title, color = Txt, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Rounded.KeyboardArrowDown, contentDescription = if (isOpen) "Replier" else "Déplier", tint = Txt2,
                modifier = Modifier.size(22.dp).rotate(angle),
            )
        }
        AnimatedVisibility(isOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) { content() }
        }
    }
}

/** En haut des Réglages : les interrupteurs que tu touches le plus. */
@Composable
fun MostUsedCard() {
    val f by Features.flags.collectAsState()
    val list = SettingsFold.mostUsed()
    if (list.isEmpty()) return
    SettingsHeader("Les plus utilisés")
    Column(
        Modifier.fillMaxWidth().glass(Rounded(22), fill = 0.07f, edge = 0.12f).padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        list.forEach { fl ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(fl.title, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(fl.sub, color = Txt2, fontSize = 13.sp, lineHeight = 17.sp)
                }
                Spacer(Modifier.width(Space.s))
                GlassSwitch(fl.get(f)) { v -> SettingsFold.bump(fl.title); Features.set(fl.set(f, v)) }
            }
        }
    }
}

/** Icône et couleur de chaque thème des Réglages (pastilles façon iOS). */
private fun sectionStyle(title: String): Pair<androidx.compose.ui.graphics.vector.ImageVector, androidx.compose.ui.graphics.Color> {
    fun C(v: Long) = androidx.compose.ui.graphics.Color(v)
    return when (title) {
        "Lecture" -> Icons.Rounded.PlayArrow to C(0xFFFF3B5C)
        "Affichage" -> Icons.Rounded.Palette to C(0xFF5E5CE6)
        "Tes notes" -> Icons.Rounded.Star to C(0xFFFFB020)
        "Playlists" -> Icons.Rounded.LibraryMusic to C(0xFFFF2D87)
        "Fichiers locaux" -> Icons.Rounded.Folder to C(0xFF0A84FF)
        "Fun et découverte" -> Icons.Rounded.Celebration to C(0xFFFF8A00)
        "Stats" -> Icons.Rounded.BarChart to C(0xFF30C85E)
        "Paroles et recherche" -> Icons.Rounded.Search to C(0xFFAF52DE)
        "Widgets et notifications" -> Icons.Rounded.Widgets to C(0xFF00B5C8)
        "Tes goûts" -> Icons.Rounded.Favorite to C(0xFFFF453A)
        "Données", "Avancé" -> Icons.Rounded.Storage to C(0xFF8E8E93)
        "Notifications" -> Icons.Rounded.Widgets to C(0xFF00B5C8)
        else -> Icons.Rounded.Settings to C(0xFF8E8E93)
    }
}
