package com.theo.remote

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * « Shazam » de Hush : écoute 8 secondes au micro, reconnaît le son (service AudD),
 * puis le retrouve dans Tidal pour le lancer ou l'ajouter.
 */
object Recognizer {

    data class Found(val title: String, val artist: String, val album: String, val tidal: TidalApi.Result?, val at: Long)

    sealed class Status {
        data object Idle : Status()
        data class Listening(val progress: Float) : Status()
        data object Searching : Status()
        data class Match(val found: Found) : Status()
        data object NoMatch : Status()
        data class Failed(val message: String) : Status()
    }

    private const val RATE = 16_000
    private const val SECONDS = 8

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var prefs: android.content.SharedPreferences? = null

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    /** Niveau sonore en direct (0..1), pour animer les cercles. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    private val _history = MutableStateFlow<List<Found>>(emptyList())
    val history: StateFlow<List<Found>> = _history

    val token get() = prefs?.getString("auddToken", "").orEmpty()

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("hush", Context.MODE_PRIVATE)
        _history.value = decode(prefs!!.getString("recognized", null))
    }

    fun setToken(t: String) { prefs?.edit()?.putString("auddToken", t.trim())?.apply() }

    fun reset() { if (job?.isActive != true) _status.value = Status.Idle }

    fun cancel() {
        job?.cancel()
        _level.value = 0f
        _status.value = Status.Idle
    }

    @SuppressLint("MissingPermission") // l'écran demande le micro avant d'appeler
    fun listen(context: Context) {
        if (job?.isActive == true) return
        init(context)
        job = scope.launch {
            try {
                _status.value = Status.Listening(0f)
                val wav = withContext(Dispatchers.IO) { record() } ?: run {
                    _status.value = Status.Failed("Le micro n'est pas disponible")
                    return@launch
                }
                _level.value = 0f
                _status.value = Status.Searching
                val res = withContext(Dispatchers.IO) { identify(wav) }
                if (res == null) {
                    _status.value = Status.NoMatch
                    return@launch
                }
                val (title, artist, album) = res
                val tidal = runCatching { TidalApi.search("$title ${artist.split(",", "&", " feat").first()}") }.getOrNull()
                fun n(x: String) = x.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
                val best = tidal?.firstOrNull { n(it.title) == n(title) } ?: tidal?.firstOrNull()
                val found = Found(title, artist, album, best, System.currentTimeMillis())
                _history.value = (listOf(found) + _history.value.filterNot { n(it.title) == n(title) && n(it.artist) == n(artist) }).take(30)
                save()
                _status.value = Status.Match(found)
            } catch (e: Exception) {
                if (isActive) _status.value = Status.Failed(e.message ?: "Erreur réseau")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun record(): ByteArray? {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4)
        }.getOrNull() ?: return null
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }
        val total = RATE * SECONDS * 2
        val pcm = ByteArrayOutputStream(total)
        val buf = ShortArray(min)
        try {
            rec.startRecording()
            var bytes = 0
            while (bytes < total && job?.isActive == true) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) { bb.putShort(buf[i]); sum += buf[i].toDouble() * buf[i] }
                pcm.write(bb.array())
                bytes += n * 2
                _level.value = (sqrt(sum / n) / 6000.0).toFloat().coerceIn(0f, 1f)
                _status.value = Status.Listening(bytes.toFloat() / total)
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        return wav(pcm.toByteArray())
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1); h.putInt(RATE); h.putInt(RATE * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(pcm.size)
        return h.array() + pcm
    }

    /** Envoie l'extrait à AudD ; renvoie (titre, artiste, album) ou null si inconnu. */
    private fun identify(wav: ByteArray): Triple<String, String, String>? {
        val boundary = "----hush" + System.currentTimeMillis()
        val c = URL("https://api.audd.io/").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.outputStream.use { out ->
            fun field(name: String, value: String) {
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            }
            if (token.isNotBlank()) field("api_token", token)
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"hush.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
            out.write(wav)
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        val o = runCatching { JSONObject(body) }.getOrNull() ?: throw IllegalStateException("Service de reconnaissance injoignable ($code)")
        if (o.optString("status") == "error") {
            val err = o.optJSONObject("error")
            val ec = err?.optInt("error_code") ?: 0
            throw IllegalStateException(
                when (ec) {
                    900, 901 -> "Clé AudD manquante ou invalide : ajoute ta clé gratuite ci-dessous"
                    902 -> "Limite gratuite AudD atteinte pour aujourd'hui"
                    else -> err?.optString("error_message")?.ifBlank { null } ?: "Reconnaissance impossible"
                },
            )
        }
        val r = o.optJSONObject("result") ?: return null
        val title = r.optString("title")
        if (title.isBlank()) return null
        return Triple(title, r.optString("artist"), r.optString("album"))
    }

    private fun save() {
        val arr = JSONArray()
        _history.value.forEach { f ->
            val o = JSONObject().put("t", f.title).put("a", f.artist).put("al", f.album).put("at", f.at)
            f.tidal?.let { o.put("r", History.encodeResults(listOf(it))) }
            arr.put(o)
        }
        prefs?.edit()?.putString("recognized", arr.toString())?.apply()
    }

    private fun decode(s: String?): List<Found> = runCatching {
        val arr = JSONArray(s ?: return emptyList())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Found(o.optString("t"), o.optString("a"), o.optString("al"), History.decodeResults(o.optString("r").ifBlank { null }).firstOrNull(), o.optLong("at"))
        }
    }.getOrDefault(emptyList())
}
