package com.theo.remote

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Traduction des paroles en français, directement sur le téléphone (Google ML Kit).
 * La première fois pour une langue, Android télécharge un petit modèle (~30 Mo).
 */
object LyricsTranslate {

    sealed class Result {
        data object Loading : Result()
        data object AlreadyFrench : Result()
        data class Done(val lines: List<String>, val from: String) : Result()
        data class Failed(val message: String) : Result()
    }

    /** Affiche-t-on la traduction sous les paroles ? (bouton dans l'écran des paroles) */
    private val _show = MutableStateFlow(false)
    val show: StateFlow<Boolean> = _show
    fun toggle() { _show.value = !_show.value }

    private val cache = HashMap<String, Result>()

    suspend fun translate(key: String, lines: List<String>): Result {
        cache[key]?.let { return it }
        // on ne traduit que les vraies lignes, puis on les remet à leur place
        val idx = lines.indices.filter { lines[it].isNotBlank() }
        val text = idx.map { lines[it].trim() }
        if (text.isEmpty()) return Result.Failed("Rien à traduire")

        // 1) en ligne : toutes les paroles d'un coup, avec le contexte → vraies phrases
        val online = runCatching { withContext(Dispatchers.IO) { google(text) } }.getOrNull()
        if (online != null) {
            val (out, lang) = online
            if (lang == "fr") return Result.AlreadyFrench.also { cache[key] = it }
            val full = MutableList(lines.size) { "" }
            idx.forEachIndexed { k, i -> full[i] = out.getOrElse(k) { "" } }
            return Result.Done(full, languageName(lang)).also { cache[key] = it }
        }

        // 2) hors ligne : traduction sur le téléphone (moins naturelle)
        return try {
            val lang = LanguageIdentification.getClient().identifyLanguage(text.take(20).joinToString("\n")).await()
            if (lang == "fr") return Result.AlreadyFrench.also { cache[key] = it }
            val src = TranslateLanguage.fromLanguageTag(lang) ?: return Result.Failed("Langue non reconnue")
            val tr = Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(TranslateLanguage.FRENCH).build(),
            )
            try {
                tr.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                val joined = tr.translate(text.joinToString("\n")).await().split("\n")
                val out = if (joined.size == text.size) joined else text.map { runCatching { tr.translate(it).await() }.getOrDefault("") }
                val full = MutableList(lines.size) { "" }
                idx.forEachIndexed { k, i -> full[i] = out.getOrElse(k) { "" } }
                Result.Done(full, languageName(lang) + " (hors ligne)")
            } finally {
                tr.close()
            }
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Traduction impossible")
        }
    }

    /** Traduction Google en ligne, par paquets de lignes (pour garder le sens des phrases). */
    private fun google(lines: List<String>): Pair<List<String>, String> {
        val chunks = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        var size = 0
        for (l in lines) {
            if (size + l.length > 3500 && cur.isNotEmpty()) { chunks += cur; cur = ArrayList(); size = 0 }
            cur += l; size += l.length + 1
        }
        if (cur.isNotEmpty()) chunks += cur
        val out = ArrayList<String>()
        var lang = "auto"
        for (c in chunks) {
            val conn = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=fr&dt=t").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            conn.outputStream.use { it.write(("q=" + URLEncoder.encode(c.joinToString("\n"), "UTF-8")).toByteArray()) }
            if (conn.responseCode !in 200..299) throw IllegalStateException("Traduction indisponible (${conn.responseCode})")
            val root = JSONArray(conn.inputStream.bufferedReader().readText())
            val parts = root.getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until parts.length()) sb.append(parts.getJSONArray(i).optString(0))
            if (root.length() > 2) lang = root.optString(2, lang)
            val got = sb.toString().split("\n").map { it.trim() }
            // si Google a fusionné des lignes, on garde l'ordre au mieux
            out += if (got.size == c.size) got else c.indices.map { got.getOrElse(it) { "" } }
        }
        return out to lang
    }

    private fun languageName(tag: String): String = when (tag) {
        "en" -> "anglais"; "es" -> "espagnol"; "pt" -> "portugais"; "it" -> "italien"; "de" -> "allemand"
        "ko" -> "coréen"; "ja" -> "japonais"; "ar" -> "arabe"; "nl" -> "néerlandais"; "tr" -> "turc"
        else -> tag
    }
}
