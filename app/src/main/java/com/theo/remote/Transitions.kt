package com.theo.remote

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

/**
 * Transitions entre les sons, façon Spotify : fondu, coupure nette, montée, pause,
 * et points de coupe (début / fin) par son.
 *
 * Hush ne touche pas au son de Tidal : tout passe par le volume du téléphone et par
 * « aller à » / « son suivant ». Les deux sons ne jouent donc jamais en même temps.
 */
object Transitions {

    enum class Kind(val label: String) { Auto("Auto"), Fade("Fondu"), Cut("Coupure"), Rise("Montée"), Pause("Pause") }

    /** startMs / endMs : null = début / fin normale du son. */
    data class Cue(val startMs: Long? = null, val endMs: Long? = null)

    data class Settings(
        /** fondu entre tous les sons (quand rien n'est choisi pour une paire) */
        val fadeAll: Boolean = false,
        val defaultKind: Kind = Kind.Fade,
        val fadeSec: Int = 5,
        /** DJ auto : saute les intros longues (grâce aux paroles synchronisées) */
        val autoDj: Boolean = false,
    )

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings

    private val _cues = MutableStateFlow<Map<String, Cue>>(emptyMap())
    val cues: StateFlow<Map<String, Cue>> = _cues

    /** "sonA>sonB" → transition choisie */
    private val _pairs = MutableStateFlow<Map<String, Kind>>(emptyMap())
    val pairs: StateFlow<Map<String, Kind>> = _pairs

    private var prefs: android.content.SharedPreferences? = null
    private var am: AudioManager? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun init(context: Context) {
        if (prefs != null) return
        val ctx = context.applicationContext
        prefs = ctx.getSharedPreferences("hush", Context.MODE_PRIVATE)
        am = ctx.getSystemService(AudioManager::class.java)
        load(prefs!!.getString("transitions", null))
        save()
        // si Hush a été coupé en plein fondu, on remet le volume d'avant
        val saved = prefs!!.getInt("transVol", -1)
        if (saved >= 0) { runCatching { am?.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0) }; prefs!!.edit().remove("transVol").apply() }
    }

    // ---------------------------------------------------------------- réglages

    fun keyOf(title: String, artist: String): String {
        fun n(x: String) = x.lowercase(Locale.ROOT).replace(Regex("\\s*[(\\[].*?[)\\]]"), "").replace(Regex("[^\\p{L}\\p{N}]"), "")
        return n(title) + "|" + n(artist.split(",", "&", " feat", " ft.", " x ").first())
    }

    fun keyOf(r: TidalApi.Result) = keyOf(r.title, r.artistLine)

    fun setSettings(s: Settings) { _settings.value = s; save() }

    fun setCue(key: String, cue: Cue?) {
        _cues.value = if (cue == null || (cue.startMs == null && cue.endMs == null)) _cues.value - key else _cues.value + (key to cue)
        save()
    }

    fun setPair(from: String, to: String, kind: Kind) {
        val k = "$from>$to"
        _pairs.value = if (kind == Kind.Auto) _pairs.value - k else _pairs.value + (k to kind)
        save()
    }

    fun pairKind(from: String, to: String?): Kind {
        if (to != null) _pairs.value["$from>$to"]?.let { return it }
        val s = _settings.value
        return if (s.fadeAll) s.defaultKind else Kind.Auto
    }

    // ---------------------------------------------------------------- moteur (appelé 4×/s par TidalRemote)

    private var curKey: String? = null
    private var trackStartedAt = 0L
    private var outKind: Kind? = null // transition en cours de sortie du son actuel
    private var baseVolume = -1
    private var lastSetVolume = -1
    private var skippedFor: String? = null
    private var pendingIn: Kind? = null // transition à appliquer à l'entrée du son suivant
    private var inStartedAt = 0L
    private var pausedForGap = false

    fun tick() {
        if (prefs == null) return
        if (AlarmRinger.ringing.value || BlindTest.active) { abortFade(); return }
        val st = TidalRemote.state.value
        val t = st.track ?: return
        val key = keyOf(t.title, t.artist)
        val now = SystemClock.elapsedRealtime()

        // ---- nouveau son
        if (key != curKey) {
            val previous = curKey
            curKey = key
            trackStartedAt = now
            skippedFor = null
            outKind = null
            onTrackStart(key, t, previous)
            return
        }

        // ---- entrée (fondu / montée) en cours
        pendingIn?.let { k ->
            val dur = if (k == Kind.Rise) 1500L else fadeMs()
            val p = ((now - inStartedAt).toFloat() / dur).coerceIn(0f, 1f)
            val from = if (k == Kind.Rise) 0.25f else 0.05f
            setVolumeFraction(from + (1f - from) * easeOut(p))
            if (p >= 1f) finishFade()
        }

        if (!st.isPlaying || t.durationMs <= 0) return
        val pos = st.livePosition()
        val cue = _cues.value[key]
        val end = (cue?.endMs ?: t.durationMs).coerceAtMost(t.durationMs)
        val kind = pairKind(key, nextKey())

        // ---- sortie
        val hasCutEnd = cue?.endMs != null && cue.endMs < t.durationMs - 1500
        // on finit le fondu 1 s avant la fin : Hush enchaîne parfois la file à 0,9 s de la fin
        val fadeEnd = if (hasCutEnd) end else end - 1000
        when (kind) {
            Kind.Fade -> {
                val start = fadeEnd - fadeMs()
                if (outKind == Kind.Fade && pos < start - 1500) {
                    // tu es revenu en arrière pendant le fondu : on remet le son
                    finishFade(); outKind = null
                } else if (pos >= start && pendingIn == null && outKind != Kind.Cut) {
                    if (outKind == null) beginFade(Kind.Fade)
                    val p = ((pos - start).toFloat() / fadeMs()).coerceIn(0f, 1f)
                    setVolumeFraction(1f - 0.95f * easeIn(p))
                }
            }
            Kind.Rise -> if (pos >= fadeEnd - 400 && outKind == null) beginFade(Kind.Rise)
            Kind.Pause -> if (pos >= fadeEnd - 1200 && outKind == null) beginFade(Kind.Pause)
            else -> {}
        }

        // ---- fin coupée plus tôt : on passe au suivant nous-mêmes
        if (hasCutEnd && pos >= end && skippedFor != key) {
            skippedFor = key
            if (kind == Kind.Pause) gapThenNext() else TidalRemote.next("fin coupée (Transitions)")
        } else if (!hasCutEnd && kind == Kind.Pause && pos >= t.durationMs - 1200 && skippedFor != key) {
            skippedFor = key
            gapThenNext()
        }
    }

    private fun onTrackStart(key: String, t: TidalRemote.Track, previous: String?) {
        // fin de la sortie précédente → entrée du nouveau son
        val incoming = outKind ?: if (previous != null) pairKind(previous, key).takeIf { it == Kind.Fade || it == Kind.Rise } else null
        if (pausedForGap) pausedForGap = false
        if (baseVolume >= 0 && (incoming == Kind.Fade || incoming == Kind.Rise)) {
            pendingIn = incoming
            inStartedAt = SystemClock.elapsedRealtime()
        } else {
            finishFade()
        }
        outKind = null

        // point de départ choisi, sinon DJ auto (sauter une intro longue)
        val cueStart = _cues.value[key]?.startMs
        if (cueStart != null && cueStart > 0) {
            seekSoon(cueStart)
        } else if (_settings.value.autoDj) {
            scope.launch {
                val ly = runCatching { Lyrics.fetch(t.title, t.artist, t.album, t.durationMs) }.getOrNull()
                val first = ly?.synced?.firstOrNull { it.text.isNotBlank() }?.timeMs ?: return@launch
                val pos = TidalRemote.state.value.livePosition()
                // intro de plus de 20 s, et on n'a pas déjà bougé : on arrive 4 s avant la voix
                if (first > 20_000 && pos < 15_000 && curKey == key) TidalRemote.seekTo(first - 4000)
            }
        }
    }

    private fun seekSoon(ms: Long) {
        scope.launch {
            kotlinx.coroutines.delay(350) // Tidal doit d'abord avoir chargé le son
            // seulement si le son vient de démarrer (pas quand tu rouvres Hush au milieu d'un son)
            val pos = TidalRemote.state.value.livePosition()
            if (pos < ms && pos < 8000) TidalRemote.seekTo(ms)
        }
    }

    private fun gapThenNext() {
        val gap = (_settings.value.fadeSec.coerceIn(1, 4)) * 1000L
        pausedForGap = true
        if (TidalRemote.state.value.isPlaying) TidalRemote.playPause()
        scope.launch {
            kotlinx.coroutines.delay(gap)
            finishFade()
            TidalRemote.next()
            kotlinx.coroutines.delay(1500)
            if (!TidalRemote.state.value.isPlaying) TidalRemote.playPause()
        }
    }

    // ---------------------------------------------------------------- volume

    private fun fadeMs() = _settings.value.fadeSec.coerceIn(1, 12) * 1000L

    private fun beginFade(k: Kind) {
        outKind = k
        if (baseVolume < 0) {
            baseVolume = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
            lastSetVolume = baseVolume
            if (baseVolume >= 0) prefs?.edit()?.putInt("transVol", baseVolume)?.apply()
        }
        if (k == Kind.Rise) setVolumeFraction(0.25f)
    }

    private fun setVolumeFraction(f: Float) {
        val m = am ?: return
        if (baseVolume < 0) {
            baseVolume = m.getStreamVolume(AudioManager.STREAM_MUSIC)
            lastSetVolume = baseVolume
            prefs?.edit()?.putInt("transVol", baseVolume)?.apply()
        }
        // tu as touché au volume pendant le fondu : on te laisse la main
        val cur = m.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (lastSetVolume >= 0 && cur != lastSetVolume) { abortFade(keep = true); return }
        val v = (baseVolume * f).toInt().coerceIn(if (f > 0.5f) 1 else 0, baseVolume)
        if (v != cur) runCatching { m.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
        lastSetVolume = v
    }

    /** Remet le volume d'avant le fondu. */
    private fun finishFade() {
        pendingIn = null
        if (baseVolume >= 0) runCatching { am?.setStreamVolume(AudioManager.STREAM_MUSIC, baseVolume, 0) }
        baseVolume = -1
        lastSetVolume = -1
        prefs?.edit()?.remove("transVol")?.apply()
    }

    private fun abortFade(keep: Boolean = false) {
        if (baseVolume < 0 && pendingIn == null) return
        if (keep) {
            pendingIn = null
            baseVolume = -1
            lastSetVolume = -1
            outKind = Kind.Cut
            prefs?.edit()?.remove("transVol")?.apply()
        } else finishFade()
    }

    private fun easeIn(p: Float) = p * p
    private fun easeOut(p: Float) = 1f - (1f - p) * (1f - p)

    /** Le son qui vient après : d'abord la file Hush, sinon celle de Tidal. */
    private fun nextKey(): String? {
        TidalRemote.peekNext()?.let { (t, a) -> return keyOf(t, a) }
        val st = TidalRemote.state.value
        val i = st.queue.indexOfFirst { it.id == st.activeQueueId }
        val n = if (i >= 0) st.queue.getOrNull(i + 1) else null
        return n?.let { keyOf(it.title, it.subtitle) }
    }

    // ---------------------------------------------------------------- sauvegarde

    private fun save() {
        val s = _settings.value
        val o = JSONObject()
            .put("g", JSONObject().put("on", s.fadeAll).put("k", s.defaultKind.name).put("s", s.fadeSec).put("dj", s.autoDj))
        val cues = JSONObject()
        _cues.value.forEach { (k, c) -> cues.put(k, JSONObject().put("s", c.startMs ?: -1).put("e", c.endMs ?: -1)) }
        val pairs = JSONObject()
        _pairs.value.forEach { (k, v) -> pairs.put(k, v.name) }
        o.put("cues", cues).put("pairs", pairs)
        prefs?.edit()?.putString("transitions", o.toString())?.apply()
    }

    private fun load(s: String?) {
        val o = runCatching { JSONObject(s ?: "{}") }.getOrNull() ?: return
        o.optJSONObject("g")?.let { g ->
            _settings.value = Settings(
                fadeAll = g.optBoolean("on"),
                defaultKind = runCatching { Kind.valueOf(g.optString("k", "Fade")) }.getOrDefault(Kind.Fade),
                fadeSec = g.optInt("s", 5),
                autoDj = g.optBoolean("dj"),
            )
        }
        // « Début et fin de son » a été retiré du menu : les anciens points de départ / de fin
        // sont effacés une fois (sinon certains sons démarraient encore après leur début)
        if (prefs?.getBoolean("cuesWiped", false) == true) o.optJSONObject("cues")?.let { c ->
            val m = HashMap<String, Cue>()
            c.keys().forEach { k ->
                val x = c.getJSONObject(k)
                val st = x.optLong("s", -1).takeIf { it >= 0 }
                val en = x.optLong("e", -1).takeIf { it >= 0 }
                m[k] = Cue(st, en)
            }
            _cues.value = m
        } else {
            _cues.value = emptyMap()
            prefs?.edit()?.putBoolean("cuesWiped", true)?.apply()
        }
        o.optJSONObject("pairs")?.let { p ->
            val m = HashMap<String, Kind>()
            p.keys().forEach { k -> runCatching { Kind.valueOf(p.getString(k)) }.getOrNull()?.let { m[k] = it } }
            _pairs.value = m
        }
    }
}
