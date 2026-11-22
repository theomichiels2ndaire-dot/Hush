package com.theo.remote

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Widget « Tes playlists » : une grille de pochettes, une touche = la playlist se lance. */
class PlaylistsWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Features.init(context)
        PlaylistCovers.init(context)
        PlaylistPlays.init(context)
        // assez de pochettes pour la plus grande grille (3 × 2)
        val items = PlaylistPlays.forWidget(6)
        val covers = withContext(Dispatchers.IO) { items.associate { it.id to PlaylistPlays.coverBitmap(it.id, it.cover, 220) } }
        // fond aux couleurs de ta playlist la plus écoutée
        val glow = glowBitmap(items.firstOrNull()?.let { covers[it.id] }, 520, 360)
        provideContent { Grid(items, covers, glow) }
    }

    @Composable
    private fun Grid(items: List<PlaylistPlays.Entry>, covers: Map<String, Bitmap?>, glow: Bitmap) {
        val size = LocalSize.current
        val cols = if (size.width >= 250.dp) 3 else 2
        val rows = if (size.height >= 190.dp) 2 else 1
        val shown = items.take(cols * rows)
        val white = ColorProvider(Color(0xFFF2F0EA))
        Box(GlanceModifier.fillMaxSize().cornerRadius(26.dp)) {
        Image(ImageProvider(glow), null, GlanceModifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(GlanceModifier.fillMaxSize().padding(10.dp)) {
            if (shown.isEmpty()) {
                Box(GlanceModifier.fillMaxSize().clickable(actionStartActivity<MainActivity>()), contentAlignment = Alignment.Center) {
                    Text("Ouvre Hush une fois pour voir tes playlists", style = TextStyle(color = white, fontSize = 13.sp))
                }
                return@Box
            }
            Column(GlanceModifier.fillMaxSize()) {
                for (r in 0 until rows) {
                    if (r > 0) Spacer(GlanceModifier.height(8.dp))
                    Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                        for (c in 0 until cols) {
                            if (c > 0) Spacer(GlanceModifier.width(8.dp))
                            val e = shown.getOrNull(r * cols + c)
                            Box(GlanceModifier.defaultWeight().fillMaxSize()) {
                                if (e != null) Cell(e, covers[e.id], white)
                            }
                        }
                    }
                }
            }
        }
        }
    }

    @Composable
    private fun Cell(e: PlaylistPlays.Entry, cover: Bitmap?, white: ColorProvider) {
        val action = actionRunCallback<PlayPlaylistAction>(actionParametersOf(PlayPlaylistAction.ID to e.id, PlayPlaylistAction.NAME to e.name))
        Column(GlanceModifier.fillMaxSize().clickable(action), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(GlanceModifier.defaultWeight().fillMaxWidth().cornerRadius(14.dp).background(Color(0x22FFFFFF)), contentAlignment = Alignment.Center) {
                if (cover != null) Image(ImageProvider(cover), e.name, GlanceModifier.fillMaxSize().cornerRadius(14.dp), contentScale = ContentScale.Crop)
                else Text(e.name.take(1).uppercase(), style = TextStyle(color = white, fontSize = 22.sp, fontWeight = FontWeight.Bold))
            }
            Spacer(GlanceModifier.height(4.dp))
            Text(e.name, maxLines = 1, style = TextStyle(color = white, fontSize = 11.sp, fontWeight = FontWeight.Bold))
        }
    }

    companion object {
        suspend fun refresh(context: Context) {
            runCatching { PlaylistsWidget().updateAll(context) }
        }
    }
}

class PlaylistsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlaylistsWidget()
}

class PlayPlaylistAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[ID] ?: return
        val name = parameters[NAME] ?: return
        withContext(Dispatchers.Main) {
            History.init(context); Features.init(context); PlaylistPlays.init(context)
            TidalRemote.start(context)
            TidalRemote.wakeInBackground(context)
            TidalRemote.setShuffle(false)
            com.theo.remote.ui.NowPlaylist.set(id)
            kotlinx.coroutines.delay(600)
            TidalRemote.playPlaylist(name)
        }
    }

    companion object {
        val ID = ActionParameters.Key<String>("plId")
        val NAME = ActionParameters.Key<String>("plName")
    }
}
