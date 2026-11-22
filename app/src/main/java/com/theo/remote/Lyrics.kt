package com.theo.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Paroles via LRCLIB (lrclib.net) : base libre et gratuite, avec paroles synchronisées.
 */
object Lyrics {

    data class Line(val timeMs: Long, val text: String)

    data class Result(val synced: List<Line>?, val plain: String?) {
        val isEmpty get() = synced.isNullOrEmpty() && plain.isNullOrBlank()
    }

    private val cache = HashMap<String, Result>()

    suspend fun fetch(title: String, artist: String, album: String, durationMs: Long): Result =
        withContext(Dispatchers.IO) {
            val key = "$title|$artist"
            cache[key]?.let { return@withContext it }

            val cleanTitle = title.replace(Regex("""\s*[(\[].*?[)\]]"""), "").trim().ifBlank { title }
            val mainArtist = artist.split(",", "&", " feat", " ft.").first().trim()

            // 1) correspondance exacte (titre + artiste + album + durée)
            var result = get(
                "https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(mainArtist)}" +
                    "&album_name=${enc(album)}&duration=${durationMs / 1000}"
            )?.let { parse(JSONObject(it)) }

            // 2) sinon recherche plus large, en gardant la durée la plus proche
            if (result == null || result.isEmpty) {
                val body = get("https://lrclib.net/api/search?track_name=${enc(cleanTitle)}&artist_name=${enc(mainArtist)}")
                if (body != null) {
                    val arr = JSONArray(body)
                    val target = durationMs / 1000.0
                    val best = (0 until arr.length()).map { arr.getJSONObject(it) }
                        .filter { !it.optString("syncedLyrics").isNullOrBlank() || !it.optString("plainLyrics").isNullOrBlank() }
                        .minByOrNull { kotlin.math.abs(it.optDouble("duration", 0.0) - target) + if (it.optString("syncedLyrics").isBlank()) 30 else 0 }
                    if (best != null) result = parse(best)
                }
            }

            val r = result ?: Result(null, null)
            cache[key] = r
            r
        }


    /** Un son retrouvé grâce à un bout de ses paroles. */
    data class Match(val title: String, val artist: String, val album: String, val line: String)

    /** Recherche par paroles : « q » cherche aussi dans le texte des chansons sur LRCLIB. */
    suspend fun searchByLyrics(phrase: String): List<Match> = withContext(Dispatchers.IO) {
        val q = phrase.trim()
        if (q.length < 6) return@withContext emptyList()
        val body = get("https://lrclib.net/api/search?q=${enc(q)}") ?: return@withContext emptyList()
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return@withContext emptyList()
        fun norm(x: String) = java.text.Normalizer.normalize(x.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        val words = norm(q).split(" ").filter { it.length > 1 }
        (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { o ->
            val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
                ?: o.optString("syncedLyrics").replace(Regex("""\[\d+:\d+(?:\.\d+)?]"""), "").takeIf { it.isNotBlank() && it != "null" }
                ?: return@mapNotNull null
            val lines = plain.lines().map { it.trim() }.filter { it.isNotBlank() }
            // la ligne qui contient le plus de mots cherchés
            val best = lines.maxByOrNull { l -> val n = norm(l); words.count { n.contains(it) } } ?: return@mapNotNull null
            val score = norm(best).let { n -> words.count { n.contains(it) } }
            if (score < (words.size + 1) / 2) return@mapNotNull null
            Match(o.optString("trackName"), o.optString("artistName"), o.optString("albumName"), best) to score
        }.sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { norm(it.title) + "|" + norm(it.artist.split(",").first()) }
            .take(6)
    }

    private fun parse(o: JSONObject): Result {
        val syncedRaw = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        val re = Regex("""\[(\d+):(\d+(?:\.\d+)?)]""")
        val synced = syncedRaw?.lines()?.flatMap { line ->
            val stamps = re.findAll(line).toList()
            val text = line.replace(re, "").trim()
            stamps.map {
                val ms = (it.groupValues[1].toLong() * 60_000) + (it.groupValues[2].toDouble() * 1000).toLong()
                Line(ms, text)
            }
        }?.sortedBy { it.timeMs }
        return Result(synced?.takeIf { it.isNotEmpty() }, plain)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "Hush/0.3 (appli perso Android)")
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        if (c.responseCode in 200..299) c.inputStream.bufferedReader().readText() else null
    } catch (e: Exception) {
        null
    }
}
