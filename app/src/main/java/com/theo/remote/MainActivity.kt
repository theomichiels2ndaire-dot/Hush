package com.theo.remote

import kotlinx.coroutines.launch

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.theo.remote.ui.RemoteApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // écran de démarrage avec l'onde animée (doit être appelé avant super.onCreate)
        installSplashScreen()
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        History.init(this)
        LastFm.init(this)
        AutoResume.init(this)
        Alarm.init(this)
        Recognizer.init(this)
        Gestures.init(this)
        Features.init(this)
        Blocked.init(this)
        LastPlay.init(this)
        com.theo.remote.ui.SmartLists.init(this)
        Follow.init(this)
        LyricQuotes.init(this)
        com.theo.remote.ui.TrackNotes.init(this)
        com.theo.remote.ui.HiddenGem.init(this)
        com.theo.remote.ui.LyricWords.init(this)
        AudioFx.init(this)
        StatsExclude.init(this)
        SkipTracker.init(this)
        PlaylistResume.init(this)
        TimeCapsule.init(this)
        com.theo.remote.ui.RatePrompt.init(this)
        Ratings.init(this)
        PlaylistCovers.init(this)
        PlaylistPlays.init(this)
        Diag.init(this)
        Rewards.init(this)
        LocalMusic.init(this)
        com.theo.remote.ui.Net.init(this)
        com.theo.remote.ui.SettingsFold.init(this)
        if (Features.flags.value.autoBackup) {
            val app = applicationContext
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { runCatching { Backup.autoBackup(app) } }
        }
        TidalAuth.init(this)
        handleAuth(intent)
        handleShare(intent)
        handleShortcut(intent)
        handleCar(intent)
        // notification discrète du service (Android 13+ demande l'autorisation)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        HushService.start(this)
        if (savedInstanceState == null) wakeTidal()
        // récompense « Son de démarrage »
        if (savedInstanceState == null && Features.flags.value.rewards && Rewards.isOn("sound:shh")) StartSound.play()
        // le reste attend que l'écran soit affiché : démarrage plus rapide
        window.decorView.post {
            com.theo.remote.ui.Setlists.init(this)
            com.theo.remote.ui.Concerts.init(this)
            Reminder.schedule(this)
            WeeklyRecap.schedule(this)
        }
        // ton son local d'avant, en pause, là où tu l'avais laissé
        if (savedInstanceState == null && Features.flags.value.localFiles) {
            android.os.Handler(mainLooper).postDelayed({ runCatching { LocalPlayer.restore(this) } }, 1500)
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            val f by Features.flags.collectAsState()
            val base = androidx.compose.ui.platform.LocalDensity.current
            // taille du texte choisie dans les Réglages
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, base.fontScale * f.textScale / 100f),
            ) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    // le noir pur change le fond partout : on redessine tout
                    androidx.compose.runtime.key(f.amoled) {
                        androidx.compose.foundation.layout.Box {
                            RemoteApp()
                            // l'ancienne fiche fixe est remplacée par les notes de version de GitHub (WhatsNewHost)
                            com.theo.remote.ui.LevelUpDialog()
                            com.theo.remote.ui.OfflineBanner()
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuth(intent)
        handleShare(intent)
        handleShortcut(intent)
        handleCar(intent)
    }

    /** Ouvert depuis la notif « voiture connectée ». */
    private fun handleCar(intent: Intent?) {
        if (intent?.getBooleanExtra(AutoResume.EXTRA_CAR, false) == true) {
            com.theo.remote.ui.CarMode.open()
            intent.removeExtra(AutoResume.EXTRA_CAR)
        }
    }

    /** « Partager → Hush » depuis Spotify, YouTube, Deezer… : on retrouve le son sur Tidal. */
    /** Raccourci de l'icône : lance la playlist choisie. */
    private fun handleShortcut(intent: Intent?) {
        if (intent?.action != PlaylistPlays.ACTION_PLAY) return
        val id = intent.getStringExtra(PlaylistPlays.EXTRA_ID) ?: return
        val name = intent.getStringExtra(PlaylistPlays.EXTRA_NAME) ?: return
        intent.action = null
        TidalRemote.start(this)
        TidalRemote.wakeInBackground(this)
        TidalRemote.setShuffle(false)
        com.theo.remote.ui.NowPlaylist.set(id)
        android.os.Handler(mainLooper).postDelayed({ TidalRemote.playPlaylist(name) }, 600)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        SharedLink.handle(text)
        intent.action = null
    }

    /** Retour de la page de connexion Tidal (hush://auth?code=…). */
    private fun handleAuth(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "hush") {
            TidalAuth.handleRedirect(data)
            intent.data = null
        }
    }

    /** Tidal fermé ? On le démarre en arrière-plan, sans quitter Hush. */
    private fun wakeTidal() {
        if (!Features.flags.value.autoOpenTidal) return
        android.os.Handler(mainLooper).postDelayed({
            val st = TidalRemote.state.value
            if (!st.hasPermission || st.connected || isFinishing) return@postDelayed
            TidalRemote.wakeInBackground(this)
        }, 600)
    }

    override fun onPause() {
        super.onPause()
        AutoResume.foreground = false
    }

    override fun onResume() {
        super.onResume()
        AutoResume.foreground = true
        // Revient des réglages ? On re-vérifie la permission et on se rebranche.
        TidalRemote.start(this)
        // revient du réglage « par-dessus les autres applis » : on affiche le panneau latéral
        runCatching { com.theo.remote.ui.EdgePanel.sync(this) }
    }
}
