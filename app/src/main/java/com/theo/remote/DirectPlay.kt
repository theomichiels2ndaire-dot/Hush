package com.theo.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Test « Hush joue lui-même » : est-ce que Tidal donne à Hush le flux COMPLET d'un son
 * (et pas seulement l'extrait de 30 s) ? On demande le manifeste de lecture du son en cours
 * avec ton compte, et on rapporte ce que Tidal répond, sans rien lire.
 */
object DirectPlay {
    private const val API = "https://openapi.tidal.com/v2"

    suspend fun probe(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.appendLine("=== Test lecture directe ===")
        if (!TidalAuth.loggedIn.value) return@withContext sb.appendLine("Pas connecté à ton compte Tidal (Réglages → Compte).").toString()
        sb.appendLine("Droits du compte : ${TidalAuth.scopes().ifBlank { "?" }}")
        val token = runCatching { TidalAuth.accessToken() }.getOrElse { return@withContext sb.appendLine("Jeton : ${it.message}").toString() }

        // le son à tester : celui en cours (sinon un son connu du catalogue)
        val st = TidalRemote.state.value
        val id = st.launch?.trackId?.takeIf { it.all(Char::isDigit) }
            ?: st.track?.let { t -> runCatching { TidalApi.search("${t.title} ${t.artist}").firstOrNull()?.id }.getOrNull() }
            ?: runCatching { TidalApi.search("Bonbon à la menthe Jok'Air").firstOrNull()?.id }.getOrNull()
            ?: return@withContext sb.appendLine("Aucun son à tester (lance un son puis réessaie).").toString()
        sb.appendLine("Son testé : $id (${st.track?.title ?: "?"})")

        val tries = listOf(
            "HLS · AAC" to "manifestType=HLS&formats=AACLC&uriScheme=HTTPS&usage=PLAYBACK&adaptive=true",
            "HLS · FLAC" to "manifestType=HLS&formats=FLAC,AACLC&uriScheme=HTTPS&usage=PLAYBACK&adaptive=false",
            "MPEG-DASH" to "manifestType=MPEG_DASH&formats=FLAC,AACLC,HEAACV1&uriScheme=DATA&usage=PLAYBACK&adaptive=false",
        )
        for ((label, q) in tries) {
            val (code, body) = call("$API/trackManifests/$id?$q", token)
            sb.appendLine()
            sb.appendLine("--- $label → HTTP $code")
            val attrs = runCatching { JSONObject(body).optJSONObject("data")?.optJSONObject("attributes") }.getOrNull()
            if (attrs != null) {
                attrs.keys().forEach { k ->
                    val v = attrs.opt(k).toString()
                    // l'adresse du flux est coupée : on veut juste savoir si elle existe
                    sb.appendLine("$k : ${if (k.contains("uri", true) || v.length > 140) v.take(60) + "… (${v.length} car.)" else v}")
                }
            } else sb.appendLine(body.take(500))
        }
        sb.toString()
    }

    private fun call(url: String, token: String): Pair<Int, String> = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/vnd.api+json")
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        code to body
    }.getOrElse { -1 to (it.message ?: "erreur réseau") }
}
