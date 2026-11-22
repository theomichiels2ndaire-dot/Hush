package com.theo.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.theo.remote.ui.CarMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reprise auto : quand un casque ou la voiture se connecte, Hush relance la musique,
 * et peut ouvrir le mode voiture. Pas besoin d'autorisation Bluetooth : on écoute
 * simplement les sorties audio qu'Android ajoute.
 */
object AutoResume {

    data class Device(
        val name: String, val kind: String, val resume: Boolean = true, val car: Boolean = false, val playlist: String? = null,
        /** profil de l'appareil (null = on ne touche pas) : volume en %, niveau de basses 0–3, son 8D */
        val volume: Int? = null, val bass: Int? = null, val spatial: Boolean? = null,
    )

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled

    private var prefs: android.content.SharedPreferences? = null
    private var appContext: Context? = null
    private var am: AudioManager? = null
    private val main = Handler(Looper.getMainLooper())
    private var primed = false
    private val lastSeen = HashMap<String, Long>()

    /** L'appli est-elle à l'écran ? (mis à jour par MainActivity) */
    @Volatile var foreground = false

    private val CAR_HINTS = listOf(
        "car", "auto", "voiture", "vw", "volkswagen", "renault", "peugeot", "citro", "ds ", "bmw", "audi", "mercedes", "toyota",
        "ford", "kia", "hyundai", "tesla", "seat", "skoda", "opel", "dacia", "nissan", "mazda", "fiat", "volvo", "mini",
        "sync", "uconnect", "mylink", "carplay", "android auto", "mmi", "r-link", "easylink", "smeg",
    )

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            if (!primed) return // Android liste d'abord ce qui est déjà branché : on l'ignore
            added.filter { it.isSink }.mapNotNull { describe(it) }.distinctBy { it.first }.forEach { (name, kind) -> onConnected(name, kind) }
        }
    }

    fun init(context: Context) {
        if (prefs != null) return
        val ctx = context.applicationContext
        appContext = ctx
        prefs = ctx.getSharedPreferences("hush", Context.MODE_PRIVATE)
        _enabled.value = prefs!!.getBoolean("autoResume", true)
        _devices.value = decode(prefs!!.getString("audioDevices", null))
        am = ctx.getSystemService(AudioManager::class.java)
        am?.registerAudioDeviceCallback(callback, main)
        main.postDelayed({ primed = true }, 1500)
    }

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs?.edit()?.putBoolean("autoResume", on)?.apply()
    }

    fun update(d: Device) {
        _devices.value = _devices.value.map { if (it.name == d.name) d else it }
        save()
    }

    fun forget(d: Device) {
        _devices.value = _devices.value.filterNot { it.name == d.name }
        save()
    }

    private fun describe(d: AudioDeviceInfo): Pair<String, String>? {
        val kind = when (d.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Filaire"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
            else -> if (android.os.Build.VERSION.SDK_INT >= 31 &&
                (d.type == AudioDeviceInfo.TYPE_BLE_HEADSET || d.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
            ) "Bluetooth" else null
        } ?: return null
        val raw = d.productName?.toString()?.trim().orEmpty()
        val name = when {
            raw.isNotBlank() && raw != android.os.Build.MODEL -> raw
            kind == "Filaire" -> "Casque filaire"
            kind == "USB" -> "Casque USB"
            else -> "Appareil Bluetooth"
        }
        return name to kind
    }

    private fun onConnected(name: String, kind: String) {
        val now = System.currentTimeMillis()
        if (now - (lastSeen[name] ?: 0) < 10_000) return // même appareil annoncé plusieurs fois
        lastSeen[name] = now

        var dev = _devices.value.firstOrNull { it.name == name }
        if (dev == null) {
            val lower = name.lowercase()
            dev = Device(name, kind, resume = true, car = kind == "Bluetooth" && CAR_HINTS.any { lower.contains(it) })
            _devices.value = (listOf(dev) + _devices.value).take(20)
            save()
        }
        val ctx = appContext ?: return
        applyProfile(dev)
        if (!_enabled.value) return
        // petite attente : Android met une seconde à basculer le son vers le nouvel appareil
        val d: Device = dev
        main.postDelayed({
            if (d.resume) {
                val pl = d.playlist
                if (pl != null && !TidalRemote.state.value.isPlaying) {
                    // une playlist choisie pour cet appareil : on la lance (Tidal réveillé en arrière-plan si besoin)
                    TidalRemote.wakeInBackground(ctx)
                    main.postDelayed({ TidalRemote.playPlaylist(pl) }, 1200)
                } else TidalRemote.resume(ctx)
            }
            if (d.car) openCarMode(ctx, name)
        }, 1800)
    }

    /** Volume, basses et son 8D réglés pour cet appareil, une fois le son basculé dessus. */
    private fun applyProfile(d: Device) {
        if (!Features.flags.value.deviceProfiles) return
        if (d.volume == null && d.bass == null && d.spatial == null) return
        main.postDelayed({
            d.volume?.let { v ->
                runCatching {
                    val max = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: return@runCatching
                    am?.setStreamVolume(AudioManager.STREAM_MUSIC, (max * v / 100f).toInt().coerceIn(0, max), 0)
                }
            }
            if (Features.flags.value.bass) d.bass?.let { AudioFx.setBass(it) }
            if (Features.flags.value.spatial) d.spatial?.let { AudioFx.setSpatial(it) }
            TidalRemote.showToast("Profil « ${d.name} » appliqué")
        }, 1500)
    }

    private fun openCarMode(ctx: Context, name: String) {
        CarMode.open()
        if (foreground) return
        // l'appli est fermée : Android interdit de l'ouvrir toute seule, on propose une notif à toucher
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Mode voiture", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Proposé quand ta voiture se connecte"
                setSound(null, null)
            })
        }
        val open = PendingIntent.getActivity(
            ctx, 2,
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_CAR, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("$name connecté")
            .setContentText("Touche pour ouvrir le mode voiture")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
    }

    private fun save() {
        val arr = JSONArray()
        _devices.value.forEach { arr.put(JSONObject().put("n", it.name).put("k", it.kind).put("r", it.resume).put("c", it.car).put("p", it.playlist ?: "").put("v", it.volume ?: -1).put("b", it.bass ?: -1).put("s", when (it.spatial) { null -> -1; true -> 1; false -> 0 })) }
        prefs?.edit()?.putString("audioDevices", arr.toString())?.apply()
    }

    private fun decode(s: String?): List<Device> = runCatching {
        val arr = JSONArray(s ?: return emptyList())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Device(
                o.getString("n"), o.optString("k"), o.optBoolean("r", true), o.optBoolean("c", false), o.optString("p").ifBlank { null },
                o.optInt("v", -1).takeIf { it >= 0 }, o.optInt("b", -1).takeIf { it >= 0 },
                when (o.optInt("s", -1)) { 1 -> true; 0 -> false; else -> null },
            )
        }
    }.getOrDefault(emptyList())

    const val EXTRA_CAR = "hush.car"
    private const val CHANNEL = "hush_car"
    private const val NOTIF_ID = 8
}
