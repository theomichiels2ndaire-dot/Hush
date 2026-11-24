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

    /**
     * Extrait audio d'une vidéo : décode la piste son et garde [seconds] s à partir de [startSec],
     * en mono 16 bits (ce qu'attend la reconnaissance : 2 à 12 s d'audio). Rien n'est gardé ensuite.
     */
    internal fun clipFromVideo(ctx: Context, video: ByteArray, startSec: Int, seconds: Int = 12): ByteArray? {
        val file = java.io.File(ctx.cacheDir, "hush_clip_src.mp4")
        try {
            file.writeBytes(video)
            val ex = android.media.MediaExtractor()
            ex.setDataSource(file.absolutePath)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: run { ex.release(); return null }
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(android.media.MediaFormat.KEY_MIME)!!
            var rate = fmt.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
            val durUs = if (fmt.containsKey(android.media.MediaFormat.KEY_DURATION)) fmt.getLong(android.media.MediaFormat.KEY_DURATION) else 0L
            // vidéo trop courte : on prend ce qu'il y a
            val start = if (durUs > 0 && startSec * 1_000_000L + seconds * 1_000_000L > durUs) ((durUs / 1_000_000L) - seconds).coerceAtLeast(0) else startSec.toLong()
            ex.seekTo(start * 1_000_000L, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val codec = android.media.MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()
            val pcm = ByteArrayOutputStream()
            val info = android.media.MediaCodec.BufferInfo()
            val startUs = start * 1_000_000L
            val endUs = startUs + seconds * 1_000_000L
            var inputDone = false
            var done = false
            var guard = 0
            while (!done && guard++ < 20_000) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0 || ex.sampleTime > endUs) {
                            codec.queueInputBuffer(i, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5_000)
                when {
                    o == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    o >= 0 -> {
                        if (info.size > 0 && info.presentationTimeUs >= startUs && info.presentationTimeUs <= endUs) {
                            val ob = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                            ob.position(info.offset); ob.limit(info.offset + info.size)
                            val shorts = ob.asShortBuffer()
                            // mélange en mono
                            val frames = shorts.remaining() / channels.coerceAtLeast(1)
                            val bb = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
                            for (fI in 0 until frames) {
                                var sum = 0
                                for (c in 0 until channels) sum += shorts.get(fI * channels + c).toInt()
                                bb.putShort((sum / channels.coerceAtLeast(1)).toShort())
                            }
                            pcm.write(bb.array())
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                    }
                }
            }
            runCatching { codec.stop() }; codec.release(); ex.release()
            val bytes = pcm.toByteArray()
            if (bytes.size < rate * 2 * 2) return null // moins de 2 s
            return wavAt(bytes, rate)
        } catch (e: Exception) {
            return null
        } finally {
            file.delete()
        }
    }

    private fun wavAt(pcm: ByteArray, rate: Int): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1); h.putInt(rate); h.putInt(rate * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(pcm.size)
        return h.array() + pcm
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1); h.putInt(RATE); h.putInt(RATE * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(pcm.size)
        return h.array() + pcm
    }

    /** Envoie l'extrait à AudD ; renvoie (titre, artiste, album) ou null si inconnu. */
    /**
     * Envoie à AudD soit un fichier (extrait micro, vidéo…), soit un lien que AudD va chercher lui-même.
     * Renvoie (titre, artiste, album) ou null si inconnu.
     */
    internal fun identify(wav: ByteArray?, fileName: String = "hush.wav", mime: String = "audio/wav", url: String? = null): Triple<String, String, String>? {
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
            if (url != null) field("url", url)
            if (wav != null) {
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\nContent-Type: $mime\r\n\r\n".toByteArray())
                out.write(wav)
                out.write("\r\n".toByteArray())
            }
            out.write("--$boundary--\r\n".toByteArray())
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
