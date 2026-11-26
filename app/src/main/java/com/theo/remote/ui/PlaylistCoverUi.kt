package com.theo.remote.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.theo.remote.PlaylistCovers
import com.theo.remote.TidalApi
import com.theo.remote.TidalRemote
import kotlinx.coroutines.launch

/** Pochette à afficher pour une playlist : ta photo, null (= mosaïque) ou la pochette Tidal. */
@Composable
fun rememberPlaylistArt(p: TidalApi.Playlist): Any? {
    val map by PlaylistCovers.map.collectAsState()
    val flags by com.theo.remote.Features.flags.collectAsState()
    return remember(map, flags.playlistCovers, p.id, p.coverUrl) { PlaylistCovers.art(p.id, p.coverUrl) }
}

/** Choisir la pochette d'une playlist. */
@Composable
fun PlaylistCoverDialog() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val e by PlaylistCovers.editing.collectAsState()
    var pendingId by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val id = pendingId ?: return@rememberLauncherForActivityResult
        pendingId = null
        if (uri != null) scope.launch {
            val ok = PlaylistCovers.setPhoto(ctx, id, uri)
            TidalRemote.showToast(if (ok) "Pochette changée" else "Impossible de lire cette image")
        }
    }
    val (id, name) = e ?: return
    fun close() { PlaylistCovers.editing.value = null }
    Dialog(onDismissRequest = { close() }) {
        Column(
            Modifier.fillMaxWidth().clip(Rounded(18)).background(Surface2).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Pochette", color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(name, color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Visible seulement dans Hush", color = Txt3, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            CoverOption(Icons.Rounded.Image, "Choisir une photo") {
                pendingId = id; close()
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            CoverOption(Icons.Rounded.GridView, "Mosaïque des sons") { PlaylistCovers.setMosaic(id); close() }
            if (PlaylistCovers.has(id)) CoverOption(Icons.Rounded.Restore, "Pochette d'origine") { PlaylistCovers.reset(id); close() }
        }
    }
}

@Composable
private fun CoverOption(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).bounceClick(pressedScale = 0.97f, onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Txt, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = Txt, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

/** Petite pochette d'une playlist, la même que dans la Biblio (pochette choisie dans Hush, sinon Tidal, sinon mosaïque). */
@Composable
fun PlaylistThumb(p: TidalApi.Playlist, size: androidx.compose.ui.unit.Dp = 48.dp, radius: androidx.compose.ui.unit.Dp = 8.dp) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val art = rememberPlaylistArt(p)
    if (art != null) {
        Cover(model = art, radius = radius, modifier = Modifier.size(size))
    } else {
        var mosaic by remember(p.id) { mutableStateOf<List<String>>(emptyList()) }
        androidx.compose.runtime.LaunchedEffect(p.id) { mosaic = MosaicCache.get(ctx, p.id) }
        Mosaic(mosaic, radius, Modifier.size(size))
    }
}
