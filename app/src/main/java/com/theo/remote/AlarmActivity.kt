package com.theo.remote

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.theo.remote.ui.AlarmRingScreen

/** Écran du réveil : s'affiche par-dessus l'écran verrouillé. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // le bouton retour ne doit pas couper le réveil par erreur
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
        AlarmRinger.start(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AlarmRingScreen(
                    onWake = { AlarmRinger.stop(this, keepMusic = true); finish() },
                    onStop = { AlarmRinger.stop(this, keepMusic = false); finish() },
                    onSnooze = { AlarmRinger.snooze(this); finish() },
                )
            }
        }
    }
}
