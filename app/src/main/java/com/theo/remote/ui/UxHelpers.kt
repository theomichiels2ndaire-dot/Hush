package com.theo.remote.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.theo.remote.BuildConfig
import com.theo.remote.CrashLog
import com.theo.remote.Diag
import com.theo.remote.Features
import com.theo.remote.HushService
import com.theo.remote.TidalAuth
import com.theo.remote.TidalRemote
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// ================================================================ bulles d'aide

/** Les gestes cachés, expliqués une seule fois chacun. */
object Hints {
    private var seen: MutableSet<String>? = null
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("hush_hints", Context.MODE_PRIVATE)

    fun shouldShow(ctx: Context, id: String): Boolean {
        if (!Features.flags.value.gestureHints) return false
        val s = seen ?: prefs(ctx).getStringSet("seen", emptySet())!!.toMutableSet().also { seen = it }
        return id !in s
    }

    fun markSeen(ctx: Context, id: String) {
        val s = seen ?: prefs(ctx).getStringSet("seen", emptySet())!!.toMutableSet().also { seen = it }
        if (s.add(id)) prefs(ctx).edit().putStringSet("seen", s.toSet()).apply()
    }

    /** Pour les Réglages : tout remontrer. */
    fun reset(ctx: Context) { seen = mutableSetOf(); prefs(ctx).edit().remove("seen").apply() }
}

/** Petite bulle qui explique un geste, montrée une fois (disparaît seule ou en la touchant). */
@Composable
fun HintBubble(id: String, text: String, modifier: Modifier = Modifier, delayMs: Long = 900) {
    val ctx = LocalContext.current
    var show by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        if (!Hints.shouldShow(ctx, id)) return@LaunchedEffect
        delay(delayMs)
        show = true
        Hints.markSeen(ctx, id)
        delay(7000)
        show = false
    }
    AnimatedVisibility(visible = show, enter = fadeIn() + slideInVertically { it / 3 }, exit = fadeOut(), modifier = modifier) {
        Row(
            Modifier.padding(8.dp).clip(Rounded(14)).background(Txt).clickable { show = false }
                .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.Close, contentDescription = "Fermer l'aide", tint = Ink.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
        }
    }
}

// ================================================================ vérification au premier lancement

private data class SetupItem(val ok: Boolean, val required: Boolean, val title: String, val detail: String, val fix: String? = null, val action: ((Context) -> Unit)? = null)

private fun openSafe(ctx: Context, i: Intent) {
    runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

private fun setupItems(ctx: Context): List<SetupItem> {
    val st = TidalRemote.state.value
    val tidal = ctx.packageManager.getLaunchIntentForPackage(TidalRemote.TIDAL_PACKAGE) != null
    val listener = TidalRemote.hasPermission(ctx)
    val notifs = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    val battery = ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true
    return listOf(
        SetupItem(tidal, true, "L'appli Tidal", if (tidal) "Installée" else "Hush pilote l'appli Tidal : installe-la d'abord", if (tidal) null else "Installer") {
            openSafe(it, Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${TidalRemote.TIDAL_PACKAGE}")))
        },
        SetupItem(listener, true, "Accès aux notifications", if (listener) "Hush voit ce que joue Tidal" else "Indispensable : c'est comme ça que Hush voit et pilote Tidal. Active Hush dans la liste.", if (listener) null else "Autoriser") {
            openSafe(it, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        },
        SetupItem(st.connected, false, "Un son lancé dans Tidal", if (st.connected) "Hush est branché sur le lecteur" else "Lance un son une fois dans Tidal (même en pause ensuite)", if (st.connected || !tidal) null else "Ouvrir Tidal") {
            TidalRemote.openTidal(it)
        },
        SetupItem(TidalAuth.loggedIn.value, false, "Compte Tidal", if (TidalAuth.loggedIn.value) "Connecté : playlists et favoris" else "Pour voir tes playlists et favoris : connecte-toi dans la Biblio"),
        SetupItem(notifs, false, "Notifications de Hush", if (notifs) "Autorisées" else "Pour la notif de lecture et les mises à jour", if (notifs) null else "Autoriser") {
            openSafe(it, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, it.packageName))
        },
        SetupItem(battery, false, "Batterie", if (battery) "Android ne met pas Hush en veille" else "Sinon Android peut couper ta file quand l'appli est fermée. Choisis « Non restreinte ».", if (battery) null else "Réglages") {
            openSafe(it, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + it.packageName)))
        },
    )
}

/** Faut-il montrer la vérification ? Au tout premier lancement, et tant que l'indispensable manque. */
object SetupGate {
    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("hush_setup", Context.MODE_PRIVATE)

    fun evaluate(ctx: Context) {
        if (!Features.flags.value.setupCheck) return
        val done = prefs(ctx).getBoolean("done", false)
        val missing = !TidalRemote.hasPermission(ctx)
        if (!done || missing) _open.value = true
    }

    fun show() { _open.value = true }
    fun close(ctx: Context) { prefs(ctx).edit().putBoolean("done", true).apply(); _open.value = false }
}

/** L'écran de vérification : chaque point avec ✓ ou un bouton pour le régler. */
@Composable
fun SetupScreen() {
    val ctx = LocalContext.current
    val open by SetupGate.open.collectAsState()
    LaunchedEffect(Unit) { SetupGate.evaluate(ctx) }
    if (!open) return
    BackHandler { SetupGate.close(ctx) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } } // se met à jour quand on revient des réglages
    val items = remember(tick) { setupItems(ctx) }
    val ready = items.filter { it.required }.all { it.ok }
    Box(Modifier.fillMaxSize().background(Ink).clickable(enabled = false) {}) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Bienvenue sur Hush", color = Txt, fontSize = 30.sp, fontWeight = FontWeight.Black)
            Text("Hush pilote l'appli Tidal avec son propre design. On vérifie que tout est prêt :", color = Txt2, fontSize = 15.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(4.dp))
            items.forEach { it ->
                Row(
                    Modifier.fillMaxWidth().clip(Rounded(16)).background(Color.White.copy(alpha = 0.06f)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(28.dp).clip(PillShape).background(
                            when {
                                it.ok -> Color(0xFF3DDC84)
                                it.required -> Color(0xFFFF6B57)
                                else -> Color.White.copy(alpha = 0.18f)
                            },
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (it.ok) Icon(Icons.Rounded.Check, contentDescription = "OK", tint = Ink, modifier = Modifier.size(18.dp))
                        else Text(if (it.required) "!" else "–", color = if (it.required) Ink else Txt, fontWeight = FontWeight.Black, fontSize = 15.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(it.title + if (it.required && !it.ok) " · indispensable" else "", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(it.detail, color = Txt2, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                    val action = it.action
                    if (it.fix != null && action != null) {
                        Spacer(Modifier.width(8.dp))
                        PillButton(it.fix) { action(ctx) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            PillButton(if (ready) "C'est parti" else "Continuer quand même", filled = ready) { SetupGate.close(ctx) }
        }
    }
}

// ================================================================ signaler un problème

/** Le rapport à copier / envoyer : version, téléphone, état, derniers événements et plantages. */
fun buildReport(ctx: Context): String {
    val st = TidalRemote.state.value
    val f = Features.flags.value
    val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.FRANCE)
    val sb = StringBuilder()
    sb.appendLine("=== Rapport Hush ===")
    sb.appendLine("Version : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    sb.appendLine("Téléphone : ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
    sb.appendLine("Accès notifs : ${TidalRemote.hasPermission(ctx)} · Tidal branché : ${st.connected} · Compte : ${TidalAuth.loggedIn.value}")
    sb.appendLine("Service : ${HushService.running} · Internet : ${Net.online.value}")
    sb.appendLine("Options : simple=${f.simpleMode} verre=${f.glassPlayer} formesNettes=${f.crispShapes} 3onglets=${f.threeTabs}")
    sb.appendLine()
    sb.appendLine("--- Derniers événements ---")
    Diag.events.value.takeLast(40).asReversed().forEach { e -> sb.appendLine("${fmt.format(java.util.Date(e.at))} · ${e.kind.name} · ${e.detail}") }
    val crashes = runCatching { CrashLog.list() }.getOrDefault(emptyList())
    if (crashes.isNotEmpty()) {
        sb.appendLine()
        sb.appendLine("--- Plantages ---")
        crashes.take(2).forEach { c -> sb.appendLine(fmt.format(java.util.Date(c.at))); sb.appendLine(c.text.lineSequence().take(25).joinToString("\n")) }
    }
    return sb.toString()
}

/** Carte des Réglages : décrire le souci, copier ou envoyer le rapport. */
@Composable
fun ReportCard() {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().clip(Rounded(20)).background(Color.White.copy(alpha = 0.06f)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Signaler un problème", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("Prépare un rapport avec la version, l'état de Hush et les derniers événements. Colle-le à Claude ou envoie-le.", color = Txt2, fontSize = 13.sp, lineHeight = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Copier") {
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("Rapport Hush", buildReport(ctx)))
                TidalRemote.showToast("Rapport copié")
            }
            PillButton("Envoyer", filled = false) {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Rapport Hush ${BuildConfig.VERSION_NAME}")
                    .putExtra(Intent.EXTRA_TEXT, buildReport(ctx))
                ctx.startActivity(Intent.createChooser(i, "Envoyer le rapport").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        PillButton("Revoir la vérification de départ", filled = false) { SetupGate.show() }
    }
}

// ================================================================ quoi de neuf

/** Fiche « Quoi de neuf » après une mise à jour. */
@Composable
fun WhatsNewHost() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { com.theo.remote.WhatsNew.check(ctx) }
    val notes by com.theo.remote.WhatsNew.notes.collectAsState()
    if (notes.isEmpty()) return
    BackHandler { com.theo.remote.WhatsNew.dismiss() }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable { com.theo.remote.WhatsNew.dismiss() })
        GlassPlate(
            Rounded(30), 30.dp,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(10.dp).fillMaxWidth(),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Quoi de neuf", color = Txt, fontSize = 26.sp, fontWeight = FontWeight.Black)
                notes.forEach { n ->
                    Text("Hush ${n.version}", color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    n.lines.forEach { l ->
                        Row {
                            Text("•", color = Txt2, fontSize = 15.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(l, color = Txt, fontSize = 15.sp, lineHeight = 21.sp)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                PillButton("Compris") { com.theo.remote.WhatsNew.dismiss() }
            }
        }
    }
}
