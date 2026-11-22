package com.theo.remote

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Mode soirée : un mini site servi par le téléphone sur le Wi-Fi.
 * Tes potes scannent le QR code, cherchent un son et l'ajoutent à ta file « À suivre ».
 */
object PartyServer {

    data class Added(val by: String, val title: String, val artist: String, val at: Long)

    data class Status(val running: Boolean = false, val url: String? = null, val error: String? = null, val guests: Int = 0)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val _added = MutableStateFlow<List<Added>>(emptyList())
    val added: StateFlow<List<Added>> = _added

    private var server: ServerSocket? = null
    private var pool = Executors.newFixedThreadPool(6)
    private var token = ""
    private var page = ""
    private val main = Handler(Looper.getMainLooper())
    private val known = ConcurrentHashMap<String, TidalApi.Result>() // résultats de recherche déjà vus
    private val byWho = ConcurrentHashMap<String, String>() // id du son → prénom de celui qui l'a ajouté
    private val guests = ConcurrentHashMap<String, Long>() // ip → dernière visite
    private val lastAdd = ConcurrentHashMap<String, Long>()

    fun start(context: Context) {
        if (server != null) return
        page = runCatching { context.assets.open("party.html").bufferedReader().readText() }.getOrDefault("<h1>Hush</h1>")
        val ip = localIp()
        if (ip == null) {
            _status.value = Status(error = "Connecte ton téléphone au Wi-Fi (ou active ton partage de connexion) pour lancer la soirée")
            return
        }
        token = randomToken()
        val socket = (8642..8650).firstNotNullOfOrNull { p -> runCatching { ServerSocket(p) }.getOrNull() }
        if (socket == null) {
            _status.value = Status(error = "Impossible d'ouvrir le serveur de la soirée")
            return
        }
        server = socket
        context.getSharedPreferences("hush", Context.MODE_PRIVATE).edit().putBoolean("usedParty", true).apply()
        if (pool.isShutdown) pool = Executors.newFixedThreadPool(6)
        _status.value = Status(running = true, url = "http://$ip:${socket.localPort}/?k=$token")
        Thread({
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                pool.execute { runCatching { handle(client) }; runCatching { client.close() } }
            }
        }, "hush-party").start()
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        guests.clear()
        _status.value = Status()
    }

    /** Adresse Wi-Fi (ou point d'accès) du téléphone. */
    private fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("swlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    private fun randomToken(): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val r = SecureRandom()
        return (1..6).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    // ---------------------------------------------------------------- HTTP (très simple)

    private fun handle(s: Socket) {
        s.soTimeout = 8000
        val input = BufferedInputStream(s.getInputStream())
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val parts = lines.first().split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val target = parts[1]
        val headers = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))
        val body = headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..20_000 }?.let { n ->
            val buf = ByteArray(n); var read = 0
            while (read < n) { val r = input.read(buf, read, n - read); if (r < 0) break; read += r }
            String(buf, 0, read)
        }
        val out = s.getOutputStream()
        val ip = s.inetAddress.hostAddress ?: "?"

        if (query["k"] != token) return send(out, 403, "text/html; charset=utf-8", "<meta name=viewport content='width=device-width'><body style='background:#0b0b10;color:#fff;font-family:sans-serif;padding:24px'><h2>Lien expiré</h2><p>Re-scanne le QR code sur le téléphone de l'hôte.</p>".toByteArray())
        guests[ip] = System.currentTimeMillis()
        val active = guests.values.count { System.currentTimeMillis() - it < 10 * 60_000 }
        if (active != _status.value.guests) _status.value = _status.value.copy(guests = active)

        when {
            method == "GET" && path == "/" -> send(out, 200, "text/html; charset=utf-8", page.toByteArray())
            method == "GET" && path == "/api/state" -> send(out, 200, "application/json", state().toString().toByteArray())
            method == "GET" && path == "/api/cover" -> {
                val art = TidalRemote.state.value.track?.art
                if (art == null) send(out, 404, "text/plain", ByteArray(0)) else {
                    val bos = ByteArrayOutputStream()
                    val small = if (art.width > 400) Bitmap.createScaledBitmap(art, 400, 400 * art.height / art.width, true) else art
                    small.compress(Bitmap.CompressFormat.JPEG, 85, bos)
                    send(out, 200, "image/jpeg", bos.toByteArray())
                }
            }
            method == "GET" && path == "/api/search" -> {
                val q = query["q"].orEmpty().trim().take(100)
                val res = if (q.isBlank()) emptyList() else runCatching { runBlocking { TidalApi.search(q) } }.getOrDefault(emptyList())
                res.forEach { known[it.id] = it }
                val arr = JSONArray()
                res.take(15).forEach { r ->
                    arr.put(JSONObject().put("id", r.id).put("title", r.fullTitle).put("artist", r.artistLine).put("album", r.album ?: "")
                        .put("cover", r.coverUrl ?: "").put("explicit", r.explicit).put("dur", r.durationMs / 1000))
                }
                send(out, 200, "application/json", arr.toString().toByteArray())
            }
            method == "POST" && path == "/api/add" -> {
                val o = runCatching { JSONObject(body ?: "{}") }.getOrDefault(JSONObject())
                val r = known[o.optString("id")]
                val by = o.optString("by").trim().take(24).ifBlank { "Un invité" }
                val now = System.currentTimeMillis()
                when {
                    r == null -> send(out, 400, "application/json", """{"error":"Son introuvable, recherche-le à nouveau"}""".toByteArray())
                    now - (lastAdd[ip] ?: 0) < 8_000 -> send(out, 429, "application/json", """{"error":"Doucement ! Attends quelques secondes"}""".toByteArray())
                    TidalRemote.upNext.value.count { byWho[it.id] == by } >= 3 ->
                        send(out, 429, "application/json", """{"error":"Tu as déjà 3 sons dans la file, attends qu'ils passent"}""".toByteArray())
                    TidalRemote.upNext.value.any { it.id == r.id } -> send(out, 409, "application/json", """{"error":"Ce son est déjà dans la file"}""".toByteArray())
                    else -> {
                        lastAdd[ip] = now
                        byWho[r.id] = by
                        main.post {
                            TidalRemote.addToQueue(r)
                            TidalRemote.showToast("$by a ajouté ${r.fullTitle}")
                        }
                        _added.value = (listOf(Added(by, r.fullTitle, r.artistLine, now)) + _added.value).take(30)
                        send(out, 200, "application/json", """{"ok":true}""".toByteArray())
                    }
                }
            }
            method == "GET" && path == "/api/karaoke" -> send(out, 200, "application/json", Karaoke.json().toString().toByteArray())
            method == "GET" && path == "/api/quiz" -> send(out, 200, "application/json", quiz(query["by"].orEmpty().trim().take(24)).toString().toByteArray())
            method == "POST" && path == "/api/answer" -> {
                val o = runCatching { JSONObject(body ?: "{}") }.getOrDefault(JSONObject())
                val by = o.optString("by").trim().take(24)
                val ok = by.isNotBlank() && by != BlindTest.HOST &&
                    (if (o.has("text")) BlindTest.answerText(by, o.optString("text")) else BlindTest.answer(by, o.optInt("choice", -1)))
                send(out, if (ok) 200 else 409, "application/json", (if (ok) """{"ok":true}""" else """{"error":"Trop tard !"}""").toByteArray())
            }
            else -> send(out, 404, "text/plain", "introuvable".toByteArray())
        }
    }

    /** État du blind test vu par un invité. */
    private fun quiz(by: String): JSONObject {
        val o = JSONObject()
        if (!BlindTest.party) return o.put("on", false)
        if (by.isNotBlank() && by != BlindTest.HOST) BlindTest.join(by)
        o.put("on", true)
        val mine = BlindTest.answered.value[by]
        fun choices(r: BlindTest.Round) = JSONArray().apply { r.choices.forEach { put(JSONObject().put("t", it.title).put("a", if (r.level.showArtist) it.artist else "")) } }
        when (val p = BlindTest.phase.value) {
            is BlindTest.Phase.Loading -> o.put("phase", "loading").put("index", p.index).put("total", p.total)
            is BlindTest.Phase.Playing -> o.put("phase", "playing").put("index", p.round.index).put("total", p.round.total)
                .put("choices", choices(p.round)).put("left", p.round.remaining()).put("dur", p.round.durationMs)
                .put("picked", if (mine == null) -1 else if (p.round.level.typed) 99 else mine.first).put("typed", p.round.level.typed)
            is BlindTest.Phase.Reveal -> o.put("phase", "reveal").put("index", p.round.index).put("total", p.round.total)
                .put("choices", choices(p.round)).put("correct", p.round.correct).put("picked", if (mine == null) -1 else if (p.round.level.typed) 99 else mine.first)
                .put("gained", mine?.second ?: 0).put("title", p.round.answer.title).put("artist", p.round.answer.artist)
                .put("typed", p.round.level.typed).put("text", BlindTest.typedAnswers.value[by].orEmpty())
            is BlindTest.Phase.Finished -> o.put("phase", "finished")
            else -> o.put("phase", "waiting")
        }
        val sc = JSONArray()
        BlindTest.scores.value.entries.sortedByDescending { it.value }.take(10).forEach { sc.put(JSONObject().put("n", it.key).put("s", it.value)) }
        o.put("scores", sc).put("me", BlindTest.scores.value[by] ?: 0)
        return o
    }

    private fun state(): JSONObject {
        val st = TidalRemote.state.value
        val o = JSONObject()
        st.track?.let { o.put("now", JSONObject().put("title", it.title).put("artist", it.artist).put("playing", st.isPlaying).put("key", it.title + it.artist)) }
        val q = JSONArray()
        TidalRemote.upNext.value.take(20).forEach { r ->
            q.put(JSONObject().put("title", r.fullTitle).put("artist", r.artistLine).put("cover", r.coverUrl ?: "").put("by", byWho[r.id] ?: ""))
        }
        o.put("queue", q)
        return o
    }

    private fun readHead(input: BufferedInputStream): String? {
        try {
            val buf = ByteArrayOutputStream()
            var last4 = 0
            while (true) {
                val b = input.read()
                if (b < 0) return null
                buf.write(b)
                last4 = (last4 shl 8) or b
                if (last4 == 0x0D0A0D0A) break
                if (buf.size() > 16_000) return null
            }
            return buf.toString("UTF-8")
        } catch (e: SocketTimeoutException) {
            return null
        }
    }

    private fun parseQuery(q: String): Map<String, String> = q.split('&').filter { it.isNotBlank() }.associate {
        val k = it.substringBefore('=')
        val v = it.substringAfter('=', "")
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"; 429 -> "Too Many Requests"; else -> "OK" }
        val h = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(h.toByteArray())
        out.write(body)
        out.flush()
    }
}
