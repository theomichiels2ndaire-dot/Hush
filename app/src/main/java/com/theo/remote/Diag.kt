package com.theo.remote

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ce qui s'est passé pendant l'écoute, rangé par cause (pour l'écran Diagnostic),
 * au lieu d'un journal brut à copier.
 */
object Diag {
    enum class Kind(val label: String, val explain: String) {
        EarlyCut("Sons coupés avant la fin", "Un son est passé au suivant alors qu'il restait plus de 20 s. La cause est notée à côté."),
        SkipHeadset("Sauts par le casque ou la notification", "Un appui sur tes écouteurs, la notification ou l'écran verrouillé. Ce n'est pas Hush qui a décidé."),
        SkipGesture("Sauts par un geste", "Secousse, glissé sur le mini-lecteur, widget ou horloge de nuit."),
        AutoEnd("Fins de son enchaînées", "Le son était fini, Hush a lancé le suivant de ta playlist. Normal."),
        CatchUp("Rattrapages", "Tidal a enchaîné un autre son tout seul, Hush a remis le bon."),
        Gapless("Enchaînés sans blanc", "Le son suivant était déjà dans la file de Tidal : aucune coupure."),
        OtherVersion("Mauvaises versions", "Tidal a lancé une autre version que celle demandée (slowed, live, remix…)."),
        Fixed("Versions corrigées", "Hush a relancé la bonne version tout seul."),
        NotFound("Sons non trouvés sur Tidal", "Tidal n'a rien lancé pour ce son (recherche sans résultat)."),
        LocalError("Erreurs de fichiers locaux", "Un fichier n'a pas pu être lu ; Hush a réessayé ou est passé au suivant."),
        GhostSkip("Sauts automatiques bloqués", "Des « suivant » arrivaient en rafale, écran éteint, sans que tu touches à rien : Hush les a ignorés."),
    }

    data class E(val at: Long, val kind: Kind, val detail: String)

    private val _events = MutableStateFlow<List<E>>(emptyList())
    val events: StateFlow<List<E>> = _events
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("hush_diag", Context.MODE_PRIVATE)
        prefs = p
        _events.value = runCatching {
            val a = JSONArray(p.getString("events", "[]"))
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val k = runCatching { Kind.valueOf(o.getString("k")) }.getOrNull() ?: return@mapNotNull null
                E(o.getLong("t"), k, o.optString("d"))
            }
        }.getOrDefault(emptyList())
    }

    fun add(kind: Kind, detail: String = "") {
        val week = System.currentTimeMillis() - 7L * 24 * 3600_000
        _events.value = (_events.value.filter { it.at > week } + E(System.currentTimeMillis(), kind, detail)).takeLast(300)
        val a = JSONArray()
        _events.value.forEach { a.put(JSONObject().put("t", it.at).put("k", it.kind.name).put("d", it.detail)) }
        prefs?.edit()?.putString("events", a.toString())?.apply()
    }

    /** Les sauts demandés par « Suivant ← … » rangés par cause. */
    fun skipFrom(from: String) {
        val f = from.lowercase()
        when {
            "casque" in f || "notification" in f || "auto" in f -> add(Kind.SkipHeadset, from)
            "secousse" in f || "glissé" in f || "widget" in f || "horloge" in f -> add(Kind.SkipGesture, from)
            "transitions" in f -> add(Kind.AutoEnd, from)
        }
    }

    fun clear() { _events.value = emptyList(); prefs?.edit()?.remove("events")?.apply() }
}
