package com.theo.remote

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Sauvegarde complète de Hush : historique, stats Last.fm, réglages, transitions, réveil, badges,
 * notes, paroles favorites, artistes suivis, défis, playlists intelligentes, réglages du son,
 * pochettes enregistrées… (pas ta connexion Tidal : tu te reconnectes en 2 secondes après une restauration).
 */
object Backup {

    private const val PREFS = "hush"
    private val FILES = listOf("lastfm.tsv", "playlist_index.json")
    /**
     * Les autres carnets de Hush, sauvegardés eux aussi : tes notes (étoiles), tes messages au futur toi,
     * tes pochettes de playlists, tes playlists les plus jouées, la poignée du panneau latéral…
     * (pas « hush_auth » : ta connexion Tidal reste sur ce téléphone)
     */
    private val EXTRA_PREFS = listOf(
        "hush_ratings", "hush_capsules", "hush_pl_covers", "hush_pl_plays",
        "hush_rate_prompt", "hush_edge", "hush_settings_use", "hush_tools_use",
    )
    /** réglages propres à ce téléphone, qu'on ne recopie pas */
    private val SKIP = setOf("lastAutoBackup", "fxSession")
    /** taille max des pochettes incluses (au-delà, elles seront retéléchargées) */
    private const val COVERS_MAX = 15L * 1024 * 1024

    /** Ce que contient la sauvegarde, pour l'afficher dans les Réglages. */
    fun summary(context: Context): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun countJson(key: String) = runCatching { JSONArray(p.getString(key, "[]")).length() }.getOrDefault(0)
        val parts = listOf(
            History.entries.value.size to "sons dans l'historique",
            LastFm.scrobbles.value.size to "écoutes Last.fm",
            countJson("trackNotes") to "notes",
            Ratings.items.value.size to "sons et albums notés",
            TimeCapsule.list.value.count { !it.opened } to "messages au futur toi",
            PlaylistCovers.map.value.size to "pochettes de playlists",
            countJson("quotes") to "paroles favorites",
            countJson("followed") to "artistes suivis",
            countJson("smartLists") to "playlists intelligentes",
            (p.getStringSet("challengesDone", emptySet())?.size ?: 0) to "défis réussis",
        ).filter { it.first > 0 }
        val last = p.getLong("lastAutoBackup", 0)
        val when_ = if (last > 0) " · dernière sauvegarde auto le " + java.text.SimpleDateFormat("d MMM à HH:mm", java.util.Locale.FRANCE).format(java.util.Date(last)) else ""
        return parts.joinToString(", ") { "${it.first} ${it.second}" }.ifBlank { "Rien à sauvegarder pour l'instant" } + " + tous tes réglages" + when_
    }

    suspend fun export(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val json = build(context).toString()
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) }
            ?: throw IllegalStateException("Impossible d'écrire le fichier")
    }

    /** Restaure une sauvegarde puis relance Hush. */
    suspend fun import(context: Context, uri: Uri) {
        val text = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
        } ?: throw IllegalStateException("Fichier illisible")
        val root = runCatching { JSONObject(text) }.getOrNull()
        if (root == null || root.optString("app") != "hush") throw IllegalStateException("Ce n'est pas une sauvegarde Hush")
        withContext(Dispatchers.IO) { restore(context, root) }
        restart(context)
    }

    /** Sauvegarde automatique (une fois par jour) dans Téléchargements/Hush. */
    suspend fun autoBackup(context: Context) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) return@withContext
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = p.getLong("lastAutoBackup", 0)
        if (System.currentTimeMillis() - last < 24 * 3600_000L) return@withContext
        val json = build(context).toString().toByteArray()
        val resolver = context.contentResolver
        val name = "Hush-sauvegarde.json"
        val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        // on remplace la sauvegarde précédente (créée par Hush) plutôt que d'en empiler
        val existing = runCatching {
            resolver.query(
                coll, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf(name, "%Hush%"), null,
            )?.use { c -> if (c.moveToFirst()) Uri.withAppendedPath(coll, c.getLong(0).toString()) else null }
        }.getOrNull()
        val target = existing ?: resolver.insert(coll, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Hush")
        }) ?: return@withContext
        runCatching {
            resolver.openOutputStream(target, "wt")?.use { it.write(json) }
            p.edit().putLong("lastAutoBackup", System.currentTimeMillis()).apply()
        }
    }

    private fun dump(context: Context, name: String): JSONObject {
        val prefs = JSONObject()
        context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) ->
            if (k in SKIP) return@forEach
            val o = when (v) {
                is String -> JSONObject().put("t", "s").put("v", v)
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.filterIsInstance<String>()))
                else -> null
            }
            if (o != null) prefs.put(k, o)
        }
        return prefs
    }

    private fun load(context: Context, name: String, prefs: JSONObject) {
        val e = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        prefs.keys().forEach { k ->
            val o = prefs.getJSONObject(k)
            when (o.optString("t")) {
                "s" -> e.putString(k, o.getString("v"))
                "b" -> e.putBoolean(k, o.getBoolean("v"))
                "i" -> e.putInt(k, o.getInt("v"))
                "l" -> e.putLong(k, o.getLong("v"))
                "f" -> e.putFloat(k, o.getDouble("v").toFloat())
                "set" -> {
                    val a = o.getJSONArray("v")
                    e.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet())
                }
            }
        }
        e.commit()
    }

    private fun build(context: Context): JSONObject {
        val root = JSONObject().put("app", "hush").put("v", 3).put("at", System.currentTimeMillis())
        root.put("prefs", dump(context, PREFS))
        val extra = JSONObject()
        EXTRA_PREFS.forEach { extra.put(it, dump(context, it)) }
        root.put("extraPrefs", extra)
        val files = JSONObject()
        FILES.forEach { name ->
            val f = File(context.filesDir, name)
            if (f.exists()) files.put(name, f.readText())
        }
        root.put("files", files)
        // pochettes enregistrées par Hush (si ça reste raisonnable) — tes pochettes de playlists perso passent en premier
        val covers = JSONObject()
        var total = 0L
        val mine = PlaylistCovers.map.value.values.map { File(it).name }.toSet()
        File(context.filesDir, "covers").listFiles()?.sortedWith(compareByDescending<File> { it.name in mine }.thenByDescending { it.lastModified() })?.forEach { f ->
            if (!f.isFile || total + f.length() > COVERS_MAX) return@forEach
            total += f.length()
            covers.put(f.name, android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP))
        }
        root.put("covers", covers)
        return root
    }

    private fun restore(context: Context, root: JSONObject) {
        load(context, PREFS, root.optJSONObject("prefs") ?: JSONObject())
        // les carnets en plus (sauvegardes v3) ; une ancienne sauvegarde ne les efface pas
        root.optJSONObject("extraPrefs")?.let { extra ->
            EXTRA_PREFS.forEach { name -> extra.optJSONObject(name)?.let { load(context, name, it) } }
        }
        val files = root.optJSONObject("files") ?: JSONObject()
        files.keys().forEach { name -> if (name in FILES) File(context.filesDir, name).writeText(files.getString(name)) }
        val covers = root.optJSONObject("covers") ?: JSONObject()
        val dir = File(context.filesDir, "covers").apply { mkdirs() }
        covers.keys().forEach { name ->
            if (name.contains('/') || name.contains("..")) return@forEach
            runCatching { File(dir, name).writeBytes(android.util.Base64.decode(covers.getString(name), android.util.Base64.NO_WRAP)) }
        }
    }

    private fun restart(context: Context) {
        val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(i)
        Runtime.getRuntime().exit(0)
    }
}
