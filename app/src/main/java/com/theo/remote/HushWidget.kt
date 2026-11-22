package com.theo.remote

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Widget d'écran d'accueil : pochette floutée en fond, verre, et commandes. */
class HushWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(DpSize(180.dp, 90.dp), DpSize(260.dp, 110.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        History.init(context)
        TidalRemote.start(context)
        val s = TidalRemote.state.value
        val art = s.track?.art
        val cover = art?.let { scaled(it, 260) }
        val flat = Features.flags.value.flatStyle
        // nouveau design : nuit éclairée par deux lumières aux couleurs de la pochette (comme le lecteur)
        val blurred = if (flat) null else glowBitmap(art, 560, 260)
        // style Pochette : un aplat de la couleur de la pochette, assombri
        val flatColor = if (!flat) null else art?.let { a ->
            runCatching {
                val p = androidx.palette.graphics.Palette.from(a).maximumColorCount(12).generate()
                val c = p.vibrantSwatch?.rgb ?: p.dominantSwatch?.rgb ?: return@runCatching null
                androidx.core.graphics.ColorUtils.blendARGB(c, android.graphics.Color.BLACK, 0.4f)
            }.getOrNull()
        }
        provideContent { Content(s.track?.title, s.track?.artist, s.isPlaying, cover, blurred, flatColor) }
    }

    @Composable
    private fun Content(title: String?, artist: String?, playing: Boolean, cover: Bitmap?, blurred: Bitmap?, flatColor: Int? = null) {
        val wide = LocalSize.current.width >= 250.dp
        val white = ColorProvider(Color.White)
        val dim = ColorProvider(Color.White.copy(alpha = 0.7f))
        Box(GlanceModifier.fillMaxSize().cornerRadius(26.dp).background(if (flatColor != null) Color(flatColor) else Color(0xFF0B0B10))) {
            if (blurred != null) {
                Image(ImageProvider(blurred), null, GlanceModifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Row(
                GlanceModifier.fillMaxSize().padding(12.dp).clickable(actionStartActivity<MainActivity>()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val coverSize = if (wide) 90.dp else 66.dp
                if (cover != null) {
                    Image(ImageProvider(cover), null, GlanceModifier.size(coverSize).cornerRadius(16.dp), contentScale = ContentScale.Crop)
                } else {
                    Box(GlanceModifier.size(coverSize).cornerRadius(14.dp).background(Color.White.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                        Image(ImageProvider(R.drawable.ic_launcher_fg), null, GlanceModifier.size(coverSize))
                    }
                }
                Spacer(GlanceModifier.width(12.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Text(
                        title ?: "Hush",
                        maxLines = 1,
                        style = TextStyle(color = white, fontSize = if (wide) 18.sp else 15.sp, fontWeight = FontWeight.Bold),
                    )
                    Text(
                        artist ?: "Lance un son dans Tidal",
                        maxLines = 1,
                        style = TextStyle(color = dim, fontSize = if (wide) 14.sp else 12.sp),
                    )
                    Spacer(GlanceModifier.height(8.dp))
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Control(R.drawable.ic_w_prev, actionRunCallback<PrevAction>(), 38)
                        Spacer(GlanceModifier.defaultWeight())
                        Box(
                            GlanceModifier.size(44.dp).cornerRadius(22.dp).background(Color.White)
                                .clickable(actionRunCallback<PlayPauseAction>()),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                ImageProvider(if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play), "Lecture",
                                GlanceModifier.size(24.dp), colorFilter = ColorFilter.tint(ColorProvider(Color(0xFF0B0B10))),
                            )
                        }
                        Spacer(GlanceModifier.defaultWeight())
                        Control(R.drawable.ic_w_next, actionRunCallback<NextAction>(), 38)
                    }
                }
            }
        }
    }

    @Composable
    private fun Control(icon: Int, action: androidx.glance.action.Action, size: Int) {
        Box(
            GlanceModifier.size(size.dp).cornerRadius((size / 2).dp).background(ImageProvider(R.drawable.widget_button)).clickable(action),
            contentAlignment = Alignment.Center,
        ) {
            Image(ImageProvider(icon), null, GlanceModifier.size(22.dp), colorFilter = ColorFilter.tint(ColorProvider(Color.White)))
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var watching = false

        private fun scaled(b: Bitmap, max: Int): Bitmap {
            val r = max.toFloat() / maxOf(b.width, b.height)
            return if (r >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * r).toInt().coerceAtLeast(1), (b.height * r).toInt().coerceAtLeast(1), true)
        }

        /** Flou bon marché : on réduit à quelques pixels puis on agrandit en lissant. */
        private fun blur(b: Bitmap): Bitmap {
            val tiny = Bitmap.createScaledBitmap(b, 12, 12, true)
            return Bitmap.createScaledBitmap(tiny, 120, 120, true)
        }

        /** Met le widget à jour quand le son ou l'état lecture/pause change. */
        @OptIn(FlowPreview::class)
        fun watch(context: Context) {
            if (watching) return
            watching = true
            val ctx = context.applicationContext
            scope.launch {
                TidalRemote.state
                    .map { Triple(it.track?.title to it.track?.artist, it.isPlaying, it.track?.art?.let { a -> System.identityHashCode(a) }) }
                    .distinctUntilChanged()
                    .debounce(400)
                    .collect {
                        runCatching { HushWidget().updateAll(ctx) }
                        runCatching { HushSquareWidget().updateAll(ctx) }
                        runCatching { HushDiscWidget().updateAll(ctx) }
                    }
            }
            // widget stats : mis à jour quand ton historique bouge
            scope.launch {
                History.entries
                    .map { it.size to (it.firstOrNull()?.lastPlayed ?: 0L) }
                    .distinctUntilChanged()
                    .debounce(3000)
                    .collect { runCatching { HushStatsWidget().updateAll(ctx) } }
            }
        }
    }
}

/** Widget carré : juste la pochette, avec lecture / pause par-dessus. */
class HushSquareWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        History.init(context)
        TidalRemote.start(context)
        val s = TidalRemote.state.value
        val art = s.track?.art?.let { b ->
            val r = 360f / maxOf(b.width, b.height)
            if (r >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * r).toInt().coerceAtLeast(1), (b.height * r).toInt().coerceAtLeast(1), true)
        }
        val playing = s.isPlaying
        provideContent {
            Box(
                GlanceModifier.fillMaxSize().cornerRadius(26.dp).background(Color(0xFF0B0B10)).clickable(actionStartActivity<MainActivity>()),
                contentAlignment = Alignment.Center,
            ) {
                if (art != null) {
                    Image(ImageProvider(art), null, GlanceModifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Image(ImageProvider(glowBitmap(null, 360, 360)), null, GlanceModifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Image(ImageProvider(R.drawable.ic_launcher_fg), null, GlanceModifier.fillMaxSize())
                }
                if (!Features.flags.value.flatStyle) Box(GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.widget_square_glass))) {}
                // sans son : la lumière de Hush plutôt qu'un fond noir
                Box(
                    GlanceModifier.size(54.dp).cornerRadius(27.dp).background(ImageProvider(R.drawable.widget_button))
                        .clickable(actionRunCallback<PlayPauseAction>()),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        ImageProvider(if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play), "Lecture",
                        GlanceModifier.size(28.dp), colorFilter = ColorFilter.tint(ColorProvider(Color.White)),
                    )
                }
            }
        }
    }
}

/** Widget « Disque » (récompense) : un vinyle rond avec ta pochette en étiquette, lecture / pause au centre. */
class HushDiscWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        History.init(context)
        TidalRemote.start(context)
        val s = TidalRemote.state.value
        val disc = discBitmap(s.track?.art)
        val playing = s.isPlaying
        provideContent {
            Box(GlanceModifier.fillMaxSize().clickable(actionStartActivity<MainActivity>()), contentAlignment = Alignment.Center) {
                Image(ImageProvider(disc), null, GlanceModifier.fillMaxSize(), contentScale = ContentScale.Fit)
                Box(
                    GlanceModifier.size(46.dp).cornerRadius(23.dp).background(Color(0xCC0E0E0E))
                        .clickable(actionRunCallback<PlayPauseAction>()),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        ImageProvider(if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play), "Lecture",
                        GlanceModifier.size(24.dp), colorFilter = ColorFilter.tint(ColorProvider(Color(0xFFF2F0EA))),
                    )
                }
            }
        }
    }

    /** Le disque dessiné en image : noir, quelques sillons, la pochette ronde au centre. */
    private fun discBitmap(art: Bitmap?): Bitmap {
        val n = 480
        val out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        val r = n / 2f
        p.color = 0xFF111111.toInt()
        c.drawCircle(r, r, r, p)
        p.style = android.graphics.Paint.Style.STROKE
        p.strokeWidth = 2f
        p.color = 0xFF262626.toInt()
        for (k in 0..5) c.drawCircle(r, r, r * (0.52f + k * 0.075f), p)
        p.style = android.graphics.Paint.Style.FILL
        val label = r * 0.44f
        if (art != null) {
            c.save()
            c.clipPath(android.graphics.Path().apply { addCircle(r, r, label, android.graphics.Path.Direction.CW) })
            c.drawBitmap(art, null, android.graphics.RectF(r - label, r - label, r + label, r + label), p)
            c.restore()
        } else {
            p.color = 0xFFC8432B.toInt()
            c.drawCircle(r, r, label, p)
        }
        return out
    }
}

class HushDiscReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HushDiscWidget()
}

class HushSquareReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HushSquareWidget()
}

class HushWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HushWidget()
}

class PrevAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        kotlinx.coroutines.withContext(Dispatchers.Main) { History.init(context); TidalRemote.start(context); TidalRemote.previous() }
    }
}

class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        kotlinx.coroutines.withContext(Dispatchers.Main) { History.init(context); TidalRemote.start(context); TidalRemote.next("widget") }
    }
}

class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        kotlinx.coroutines.withContext(Dispatchers.Main) { History.init(context); TidalRemote.start(context); TidalRemote.playPause() }
    }
}

/** Widget stats : tes minutes du jour, ta série et le son de la semaine. */
class HushStatsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        History.init(context); LastFm.init(context); StatsExclude.init(context)
        val ev = com.theo.remote.ui.buildEvents(History.entries.value, LastFm.scrobbles.value)
        val cal = java.util.Calendar.getInstance()
        fun dayStart(t: Long) = cal.apply {
            timeInMillis = t; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val day = 24L * 3600_000
        val today = dayStart(System.currentTimeMillis())
        val minutes = ev.filter { it.ts >= today }.sumOf { it.durMs } / 60_000
        val days = ev.map { dayStart(it.ts) }.toHashSet()
        var streak = 0
        var d = if (today in days) today else today - day
        while (d in days) { streak++; d -= day }
        val week = ev.filter { it.ts >= System.currentTimeMillis() - 7 * day }
        val topKey = week.groupingBy { it.key }.eachCount().maxByOrNull { it.value }?.key
        val top = week.lastOrNull { it.key == topKey }
        val goal = Features.flags.value.goalMin
        // fond aux couleurs du son de la semaine (sinon la lumière de Hush)
        val topArt = (top?.cover as? String)?.let { url -> runCatching {
            val req = coil.request.ImageRequest.Builder(context).data(url).size(160).allowHardware(false).build()
            (coil.Coil.imageLoader(context).execute(req).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        }.getOrNull() }
        val glow = glowBitmap(topArt, 420, 420)
        provideContent {
            Box(GlanceModifier.fillMaxSize().cornerRadius(26.dp)) {
            if (!Features.flags.value.flatStyle) Image(ImageProvider(glow), null, GlanceModifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Column(
                GlanceModifier.fillMaxSize().then(if (Features.flags.value.flatStyle) GlanceModifier.background(Color(0xFF161616)) else GlanceModifier).padding(14.dp)
                    .clickable(actionStartActivity<MainActivity>()),
            ) {
                Text("AUJOURD'HUI", style = TextStyle(color = ColorProvider(Color(0x99FFFFFF)), fontSize = 10.sp, fontWeight = FontWeight.Bold))
                Text(
                    if (minutes >= 60) "${minutes / 60} h ${"%02d".format(minutes % 60)}" else "$minutes min",
                    style = TextStyle(color = ColorProvider(Color.White), fontSize = 32.sp, fontWeight = FontWeight.Bold),
                )
                if (goal > 0) Text(
                    if (minutes >= goal) "Objectif atteint ✓" else "Objectif : ${goal} min",
                    style = TextStyle(color = ColorProvider(Color(0xFFB9A6FF)), fontSize = 11.sp),
                )
                Spacer(GlanceModifier.height(6.dp))
                Text(
                    if (streak >= 1) "$streak jour${if (streak > 1) "s" else ""} d'affilée" else "Pas encore de musique aujourd'hui",
                    style = TextStyle(color = ColorProvider(Color.White), fontSize = 12.sp),
                )
                top?.let {
                    Spacer(GlanceModifier.height(4.dp))
                    Text("Son de la semaine : ${it.title}", style = TextStyle(color = ColorProvider(Color(0xBBFFFFFF)), fontSize = 11.sp), maxLines = 1)
                }
            }
            }
        }
    }
}

class HushStatsReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HushStatsWidget()
}

/**
 * Fond des widgets (nouveau design) : une nuit profonde éclairée par deux grosses lumières floues
 * aux couleurs de la pochette, comme le fond du lecteur. Sans pochette : rouge et violet, comme l'icône.
 */
internal fun glowBitmap(art: Bitmap?, w: Int, h: Int): Bitmap {
    var a = 0xFFE0533D.toInt()
    var b = 0xFF6A2E9A.toInt()
    if (art != null) runCatching {
        val p = androidx.palette.graphics.Palette.from(art).maximumColorCount(16).generate()
        a = p.vibrantSwatch?.rgb ?: p.dominantSwatch?.rgb ?: a
        b = p.darkVibrantSwatch?.rgb ?: p.darkMutedSwatch?.rgb ?: p.mutedSwatch?.rgb ?: b
    }
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    c.drawColor(0xFF0B0710.toInt())
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    fun blob(cx: Float, cy: Float, r: Float, color: Int, alpha: Int) {
        val solid = androidx.core.graphics.ColorUtils.setAlphaComponent(color, alpha)
        val clear = androidx.core.graphics.ColorUtils.setAlphaComponent(color, 0)
        p.shader = android.graphics.RadialGradient(cx, cy, r, intArrayOf(solid, androidx.core.graphics.ColorUtils.setAlphaComponent(color, alpha / 3), clear), floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }
    val m = maxOf(w, h).toFloat()
    blob(w * 0.18f, h * 0.10f, m * 0.85f, a, 235)
    blob(w * 0.92f, h * 0.95f, m * 0.80f, b, 220)
    p.shader = null
    p.color = 0x38000000 // léger voile pour que le texte blanc reste lisible
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    return out
}
