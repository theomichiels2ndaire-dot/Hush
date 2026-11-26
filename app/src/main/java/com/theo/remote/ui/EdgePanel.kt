package com.theo.remote.ui

import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.theo.remote.Features
import com.theo.remote.PlaylistCovers
import com.theo.remote.PlaylistPlays
import com.theo.remote.TidalRemote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Panneau latéral Hush : une fine poignée au bord de l'écran, par-dessus n'importe quelle appli.
 * On la touche (ou on la tire vers le centre) → pochette, contrôles et tes 3 playlists les plus jouées.
 * Cachée en paysage (jeux, vidéos plein écran).
 */
object EdgePanel {
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var params: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null
    private var app: Context? = null
    private val open = MutableStateFlow(false)
    private var landscape = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var autoClose: Job? = null
    private var callbacks: ComponentCallbacks? = null

    fun canShow(context: Context) = Settings.canDrawOverlays(context)

    /** Affiche ou retire la poignée selon l'option et la permission. */
    fun sync(context: Context) {
        val c = context.applicationContext
        app = c
        val want = Features.flags.value.edgePanel && canShow(c)
        if (want && view == null) attach(c) else if (!want && view != null) detach()
    }

    private fun dp(v: Int) = (v * (app?.resources?.displayMetrics?.density ?: 3f)).toInt()

    private fun attach(c: Context) {
        val w = c.getSystemService(WindowManager::class.java) ?: return
        wm = w
        val prefs = c.getSharedPreferences("hush_edge", Context.MODE_PRIVATE)
        val p = WindowManager.LayoutParams(
            dp(22), dp(120),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            y = prefs.getInt("y", dp(260))
        }
        params = p
        val o = OverlayOwner().also { it.start() }
        owner = o
        val v = ComposeView(c).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent { MaterialTheme(colorScheme = darkColorScheme()) { EdgeContent() } }
        }
        view = v
        landscape = c.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        v.visibility = if (landscape) View.GONE else View.VISIBLE
        runCatching { w.addView(v, p) }.onFailure { view = null; o.stop(); return }
        callbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
                if (landscape) close()
                view?.visibility = if (landscape) View.GONE else View.VISIBLE
            }
            @Deprecated("") override fun onLowMemory() {}
        }.also { c.registerComponentCallbacks(it) }
    }

    private fun detach() {
        runCatching { wm?.removeView(view) }
        callbacks?.let { app?.unregisterComponentCallbacks(it) }
        callbacks = null
        owner?.stop()
        view = null; owner = null
        open.value = false
    }

    fun show() {
        val p = params ?: return
        open.value = true
        p.width = WindowManager.LayoutParams.MATCH_PARENT
        p.height = WindowManager.LayoutParams.MATCH_PARENT
        p.y = 0
        runCatching { wm?.updateViewLayout(view, p) }
        bumpAutoClose()
    }

    fun close() {
        val p = params ?: return
        if (!open.value) return
        open.value = false
        autoClose?.cancel()
        p.width = dp(22); p.height = dp(120)
        p.y = app?.getSharedPreferences("hush_edge", Context.MODE_PRIVATE)?.getInt("y", dp(260)) ?: dp(260)
        runCatching { wm?.updateViewLayout(view, p) }
    }

    /** se referme tout seul après 8 s sans toucher */
    fun bumpAutoClose() {
        autoClose?.cancel()
        autoClose = scope.launch { delay(8000); close() }
    }

    fun moveBy(dy: Float) {
        val p = params ?: return
        if (open.value) return
        val h = app?.resources?.displayMetrics?.heightPixels ?: 2400
        p.y = (p.y + dy.toInt()).coerceIn(dp(60), h - dp(200))
        runCatching { wm?.updateViewLayout(view, p) }
    }

    fun saveY() {
        val y = params?.y ?: return
        app?.getSharedPreferences("hush_edge", Context.MODE_PRIVATE)?.edit()?.putInt("y", y)?.apply()
    }

    fun openApp() {
        val c = app ?: return
        close()
        c.packageManager.getLaunchIntentForPackage(c.packageName)?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    @Composable
    private fun EdgeContent() {
        val isOpen by open.collectAsState()
        if (!isOpen) Handle() else Panel()
    }

    @Composable
    private fun Handle() {
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { show() } }
                .pointerInput(Unit) {
                    var dx = 0f
                    detectDragGestures(
                        onDragStart = { dx = 0f },
                        onDragEnd = { saveY(); if (dx < -dp(24)) show() },
                    ) { change, d ->
                        change.consume()
                        dx += d.x
                        if (kotlin.math.abs(d.y) > kotlin.math.abs(d.x)) moveBy(d.y)
                    }
                },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(Modifier.padding(end = 4.dp).width(5.dp).height(72.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.55f)))
        }
    }

    @Composable
    private fun Panel() {
        val s by TidalRemote.state.collectAsState()
        val plays by PlaylistPlays.list.collectAsState()
        val store by PlaylistStore.list.collectAsState()
        val covers by PlaylistCovers.map.collectAsState()
        val shown = remember { MutableTransitionState(false).apply { targetState = true } }
        LaunchedEffect(Unit) { runCatching { PlaylistStore.load() } }
        val top = remember(plays, store) {
            val t = PlaylistPlays.top(3).map { Triple(it.id, it.name, it.cover) }
            if (t.size >= 3) t else (t + store.orEmpty().filter { p -> t.none { it.first == p.id } }.map { Triple(it.id, it.name, it.coverUrl) }).take(3)
        }
        val t = s.track
        val colors = rememberCoverColors(t?.art)
        val band = androidx.compose.ui.graphics.lerp(colors.b, Color.Black, 0.72f)

        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).pointerInput(Unit) { detectTapGestures { close() } },
            contentAlignment = Alignment.CenterEnd,
        ) {
            AnimatedVisibility(
                shown,
                enter = slideInHorizontally(spring(dampingRatio = 0.86f, stiffness = 420f)) { it } + fadeIn(),
                exit = slideOutHorizontally { it } + fadeOut(),
            ) {
                Column(
                    Modifier.padding(end = 10.dp).width(290.dp).clip(Rounded(18)).background(Surface2)
                        .pointerInput(Unit) { detectTapGestures { bumpAutoClose() } },
                ) {
                    // son en cours, sur un aplat de la couleur de sa pochette
                    Column(Modifier.fillMaxWidth().background(band).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Cover(bitmap = t?.art, radius = 8.dp, modifier = Modifier.size(58.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t?.title ?: "Rien en cours", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(t?.artist.orEmpty(), color = Txt.copy(alpha = 0.72f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            EdgeButton(Icons.Rounded.SkipPrevious, "Précédent", Color.White.copy(alpha = 0.10f), Txt, Modifier.weight(1f)) { TidalRemote.previous() }
                            EdgeButton(if (s.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Lecture", Txt, Ink, Modifier.weight(1f)) { TidalRemote.playPause() }
                            EdgeButton(Icons.Rounded.SkipNext, "Suivant", Color.White.copy(alpha = 0.10f), Txt, Modifier.weight(1f)) { TidalRemote.next() }
                        }
                    }
                    // tes playlists les plus jouées
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        if (top.isNotEmpty()) Text("Tes playlists", color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                        top.forEach { (id, name, cover) ->
                            val art = remember(covers, id, cover) { PlaylistCovers.art(id, cover) }
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).bounceClick(pressedScale = 0.97f) {
                                    TidalRemote.setShuffle(false); NowPlaylist.set(id); TidalRemote.playPlaylist(name, id); close()
                                }.padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (art != null) Cover(model = art, radius = 6.dp, modifier = Modifier.size(42.dp))
                                else Box(Modifier.size(42.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.08f)))
                                Spacer(Modifier.width(10.dp))
                                Text(name, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(12.dp)).bounceClick(pressedScale = 0.97f) { openApp() }.padding(horizontal = 4.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = Txt2, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Ouvrir Hush", color = Txt2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun EdgeButton(icon: ImageVector, label: String, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit) {
        Box(
            modifier.height(46.dp).clip(RoundedCornerShape(12.dp)).background(bg).bounceClick { bumpAutoClose(); onClick() },
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(26.dp)) }
    }
}

/** Cycle de vie minimal pour afficher du Compose dans une fenêtre hors activité. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    fun start() {
        saved.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }
    fun stop() { registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY) }
}
