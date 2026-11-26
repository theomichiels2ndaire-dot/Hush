package com.theo.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mises à jour depuis GitHub : Hush regarde la dernière version publiée du dépôt,
 * la télécharge et ouvre l'installation (par-dessus, sans perdre les données).
 */
object Updater {
    private const val REPO = "theomichiels2ndaire-dot/Hush"

    data class Release(val number: Int, val name: String, val apkUrl: String, val size: Long)

    private val _available = MutableStateFlow<Release?>(null)
    val available: StateFlow<Release?> = _available
    /** Progression du téléchargement (0..1), null quand rien ne se télécharge. */
    private val _progress = MutableStateFlow<Float?>(null)
    val progress: StateFlow<Float?> = _progress
    private var lastCheck = 0L

    /** Numéro de cette version (1.N → N). */
    fun current(): Int = number(BuildConfig.VERSION_NAME)

    /** "v1.23" ou "1.23" → 23. */
    fun number(tag: String): Int = tag.trim().removePrefix("v").substringAfter('.', "0").toIntOrNull() ?: 0

    fun dismiss() { _available.value = null }

    suspend fun check(force: Boolean = false): Unit = withContext(Dispatchers.IO) {
        if (!force && !Features.flags.value.autoUpdate) return@withContext
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < 10 * 60_000L) return@withContext
        lastCheck = now
        runCatching {
            val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "Hush/${BuildConfig.VERSION_NAME}")
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            if (c.responseCode != 200) {
                if (force) TidalRemote.showToast("Impossible de vérifier (erreur ${c.responseCode})")
                return@runCatching
            }
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val n = Updater.number(j.optString("tag_name"))
            val assets = j.optJSONArray("assets")
            val apk = (0 until (assets?.length() ?: 0)).mapNotNull { assets?.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") }
            if (apk != null && n > current()) {
                val rel = Release(n, j.optString("name", "Hush 1.$n"), apk.optString("browser_download_url"), apk.optLong("size"))
                _available.value = rel
                // en Wi-Fi : on télécharge déjà en fond, « Installer » sera immédiat
                TidalRemote.appContext?.let { prefetch(it, rel) }
            } else if (force) TidalRemote.showToast("Hush est à jour")
        }.onFailure { if (force) TidalRemote.showToast("Impossible de vérifier : pas de connexion ?") }
    }

    private fun apkFile(app: Context, r: Release) = java.io.File(java.io.File(app.cacheDir, "share").apply { mkdirs() }, "Hush-update-${r.number}.apk")

    /** La version déjà téléchargée d'avance, si elle est complète. */
    private fun ready(app: Context, r: Release): java.io.File? =
        apkFile(app, r).takeIf { it.exists() && it.length() > 0 && (r.size <= 0 || it.length() == r.size) }

    @Volatile private var prefetching = false

    /** Téléchargement discret en fond, seulement en Wi-Fi (et si l'option est activée). */
    private fun prefetch(ctx: Context, r: Release) {
        val app = ctx.applicationContext
        if (!Features.flags.value.prefetchUpdate || prefetching || ready(app, r) != null) return
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        if (runCatching { cm.isActiveNetworkMetered }.getOrDefault(true)) return
        prefetching = true
        try {
            // les anciennes versions téléchargées ne servent plus
            apkFile(app, r).parentFile?.listFiles()?.filter { it.name.startsWith("Hush-update") && it.name != apkFile(app, r).name }?.forEach { it.delete() }
            val tmp = java.io.File(apkFile(app, r).path + ".part")
            if (download(r, tmp, progress = false) && (r.size <= 0 || tmp.length() == r.size)) tmp.renameTo(apkFile(app, r)) else tmp.delete()
        } catch (_: Exception) {
        } finally { prefetching = false }
    }

    /** Télécharge l'APK de GitHub (en suivant ses redirections). */
    private fun download(r: Release, out: java.io.File, progress: Boolean): Boolean = runCatching {
        var url = URL(r.apkUrl)
        var c: HttpURLConnection
        var hops = 0
        while (true) {
            c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            val code = c.responseCode
            if (code in 300..399 && hops < 5) { url = URL(url, c.getHeaderField("Location")); hops++; continue }
            break
        }
        val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
        c.inputStream.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val k = input.read(buf); if (k < 0) break
                    o.write(buf, 0, k); done += k
                    if (progress && total > 0) _progress.value = (done.toFloat() / total).coerceIn(0f, 1f)
                }
            }
        }
        true
    }.getOrDefault(false)

    /** Télécharge l'APK (sauf s'il est déjà prêt) puis ouvre l'écran d'installation d'Android. */
    suspend fun install(ctx: Context, r: Release) {
        if (_progress.value != null) return
        val app = ctx.applicationContext
        val pre = ready(app, r)
        if (pre == null) _progress.value = 0f
        val file = pre ?: withContext(Dispatchers.IO) {
            runCatching {
                val out = apkFile(app, r)
                if (!download(r, out, progress = true)) error("échec")
                out
            }.getOrNull()
        }
        _progress.value = null
        if (file == null) { TidalRemote.showToast("Téléchargement raté, réessaie"); return }
        withContext(Dispatchers.Main) {
            // Android demande une fois l'autorisation d'installer depuis Hush
            if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
                TidalRemote.showToast("Autorise Hush à installer des applis, puis retouche Installer")
                app.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return@withContext
            }
            val uri = FileProvider.getUriForFile(app, app.packageName + ".share", file)
            app.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/** « Quoi de neuf » : après une mise à jour, les nouveautés des versions qu'on vient de sauter. */
object WhatsNew {
    data class Notes(val version: String, val lines: List<String>)
    private val _notes = MutableStateFlow<List<Notes>>(emptyList())
    val notes: StateFlow<List<Notes>> = _notes

    suspend fun check(ctx: Context) = withContext(Dispatchers.IO) {
        if (!Features.flags.value.whatsNew) return@withContext
        val p = ctx.applicationContext.getSharedPreferences("hush_whatsnew", Context.MODE_PRIVATE)
        val cur = Updater.current()
        val seen = p.getInt("seen", 0)
        // première installation : rien à raconter, on retient juste la version
        if (seen == 0 || cur <= seen) { if (cur > seen) p.edit().putInt("seen", cur).apply(); return@withContext }
        runCatching {
            val c = URL("https://api.github.com/repos/theomichiels2ndaire-dot/Hush/releases?per_page=30").openConnection() as HttpURLConnection
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "Hush/${BuildConfig.VERSION_NAME}")
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            if (c.responseCode != 200) return@runCatching
            val a = org.json.JSONArray(c.inputStream.bufferedReader().readText())
            val out = (0 until a.length()).mapNotNull { i ->
                val r = a.getJSONObject(i)
                val n = Updater.number(r.optString("tag_name")).takeIf { it > 0 } ?: return@mapNotNull null
                if (n <= seen || n > cur) return@mapNotNull null
                val lines = notesLines(r.optString("body"))
                if (lines.isEmpty()) null else n to Notes("1.$n", lines)
            }.sortedByDescending { it.first }.map { it.second }
            _notes.value = out
        }
        p.edit().putInt("seen", cur).apply()
    }

    fun dismiss() { _notes.value = emptyList() }

    /** Les lignes « - … » des notes d'une version. */
    fun notesLines(body: String): List<String> =
        body.lines().map { it.trim() }.filter { it.startsWith("- ") }.map { it.removePrefix("- ").trim() }.filter { it.isNotBlank() }
}

/** Petite notif quand un son est coupé avant la fin, avec qui l'a passé. */
object EarlyCutAlert {
    private const val CHANNEL = "early_cut"
    private var lastAt = 0L

    fun post(ctx: Context, title: String, remainingMs: Long, cause: String) {
        if (!Features.flags.value.earlyCutAlert) return
        val now = System.currentTimeMillis()
        if (now - lastAt < 20_000) return // pas de rafale
        lastAt = now
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sons coupés avant la fin", NotificationManager.IMPORTANCE_LOW))
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("« $title » coupé ${remainingMs / 1000} s avant la fin")
            .setContentText("Passé par : $cause")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Passé par : $cause\nDétails dans Réglages › Diagnostic."))
            .setAutoCancel(true)
            .build()
        nm.notify(4711, n)
    }
}
