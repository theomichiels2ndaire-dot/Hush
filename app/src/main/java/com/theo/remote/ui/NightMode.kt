package com.theo.remote.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay

/**
 * Mode nuit automatique : de 21 h à 7 h, Hush adoucit tout l'écran (fonds et blancs un peu moins lumineux)
 * pour ne pas t'éblouir, au lit comme en voiture. Un voile léger qui ne bloque aucun toucher.
 */
object NightMode {
    const val FROM = 21
    const val TO = 7

    /** La nuit, selon l'heure (0..23). */
    fun isNight(hour: Int): Boolean = hour >= FROM || hour < TO
}

@Composable
fun NightDim() {
    val on = com.theo.remote.Features.flags.collectAsState().value.nightDim
    var night by remember { mutableStateOf(false) }
    LaunchedEffect(on) {
        while (on) {
            night = NightMode.isNight(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))
            delay(60_000)
        }
        night = false
    }
    // passage en douceur sur quelques secondes, pas d'un coup
    val alpha by animateFloatAsState(if (on && night) 0.26f else 0f, tween(4000), label = "nightDim")
    if (alpha > 0.001f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = alpha)))
}
