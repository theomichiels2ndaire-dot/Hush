package com.theo.remote.ui

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.TidalRemote
import kotlinx.coroutines.delay

/** Sur quoi le son sort en ce moment : nom à afficher + icône. */
private data class Output(val name: String, val icon: ImageVector)

private fun currentOutput(ctx: Context): Output {
    // Tidal joue ailleurs (Tidal Connect, Chromecast…)
    TidalRemote.remoteDevice()?.let { return Output(it, Icons.Rounded.Cast) }
    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    // priorité à ce qui est branché ou connecté (comme Android le fait pour la musique)
    val order = listOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
    )
    for (type in order) {
        val d = outs.firstOrNull { it.type == type } ?: continue
        val name = d.productName?.toString()?.takeIf { it.isNotBlank() && it != android.os.Build.MODEL } ?: "Écouteurs"
        val icon = when (type) {
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> Icons.Rounded.Speaker
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET -> Icons.Rounded.Bluetooth
            else -> Icons.Rounded.Headphones
        }
        return Output(name, icon)
    }
    return Output("Ce téléphone", Icons.Rounded.PhoneAndroid)
}

/**
 * Ouvre le sélecteur de sortie d'Android pour Tidal (écouteurs, enceinte Bluetooth, Cast…).
 * Selon le téléphone, ça passe par la fenêtre « Sortie multimédia » ; sinon on ouvre Tidal.
 */
fun openOutputSwitcher(ctx: Context) {
    val pkg = TidalRemote.TIDAL_PACKAGE
    // Android 12+ (AOSP / One UI) : fenêtre système « Sortie multimédia » pour l'appli Tidal
    val sent = runCatching {
        ctx.sendBroadcast(
            Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
                .setPackage("com.android.systemui")
                .putExtra("package_name", pkg),
        )
        true
    }.getOrDefault(false)
    if (sent) return
    runCatching {
        ctx.startActivity(
            Intent("android.settings.panel.action.MEDIA_OUTPUT")
                .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { TidalRemote.openTidal(ctx) }
}

/** Petite pastille dans le lecteur : sur quoi Tidal joue. Toucher = changer d'appareil. */
@Composable
fun DeviceChip(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var out by remember { mutableStateOf(currentOutput(ctx)) }
    LaunchedEffect(Unit) {
        while (true) {
            out = currentOutput(ctx)
            delay(2000)
        }
    }
    Row(
        modifier.clip(PillShape).background(Color.White.copy(alpha = 0.10f))
            .bounceClick(pressedScale = 0.95f) { openOutputSwitcher(ctx) }
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(out.icon, contentDescription = null, tint = Txt, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(out.name, color = Txt, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Rounded.KeyboardArrowRight, contentDescription = "Changer d'appareil", tint = Txt2, modifier = Modifier.size(18.dp))
    }
}

/** Petit bouton rond à côté de la qualité : l'icône de l'appareil où sort le son. Toucher = changer. */
@Composable
fun DeviceButton() {
    val ctx = LocalContext.current
    var out by remember { mutableStateOf(currentOutput(ctx)) }
    LaunchedEffect(Unit) {
        while (true) {
            out = currentOutput(ctx)
            delay(2000)
        }
    }
    androidx.compose.foundation.layout.Box(
        Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(Color.White.copy(alpha = 0.10f))
            .bounceClick(pressedScale = 0.88f) { openOutputSwitcher(ctx) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(out.icon, contentDescription = "Appareil : ${out.name}. Toucher pour changer", tint = Txt.copy(alpha = 0.85f), modifier = Modifier.size(17.dp))
    }
}
