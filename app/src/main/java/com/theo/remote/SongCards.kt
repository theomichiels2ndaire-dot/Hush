package com.theo.remote

import android.content.Context
import com.theo.remote.ui.Ev
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Cartes de sons : chaque son monte tout seul de Bronze à Diamant selon tes écoutes.
 * Score = écoutes des 90 derniers jours, moins 2 par fois où tu l'as passé au début.
 */
object SongCards {
    enum class Tier(val label: String, val min: Int) { Bronze("Bronze", 3), Argent("Argent", 8), Or("Or", 15), Diamant("Diamant", 30) }

    data class Card(
        val key: String,
        val title: String,
        val artist: String,
        val cover: Any?,
        val score: Int,
        val plays: Int,
        val skips: Int,
        val totalMs: Long,
        val tier: Tier,
        /** Date où la carte a atteint chaque rang (0 = pas encore). */
        val reached: Map<Tier, Long>,
        val lastPlay: Long,
        /** Numéro dans la collection (1 = la meilleure carte). */
        val number: Int = 0,
        /** Édition spéciale : « N°1 DU MOIS », « 100 ÉCOUTES »… (null = carte normale). */
        val special: String? = null,
        /** Jours avant de redescendre d'un rang (null = pas en danger). */
        val dangerDays: Int? = null,
        /** Rang où elle redescendrait. */
        val dangerTo: Tier? = null,
    ) {
        val mainArtist: String get() = mainArtistOf(artist)
        val next: Tier? get() = Tier.entries.getOrNull(tier.ordinal + 1)
        /** Points (1 à 3) : où en est la carte dans son rang. */
        val dots: Int get() {
            val lo = tier.min
            val hi = next?.min ?: (tier.min * 2)
            val f = ((score - lo).toFloat() / (hi - lo).coerceAtLeast(1)).coerceIn(0f, 0.999f)
            return 1 + (f * 3).toInt()
        }
    }

    data class Promo(val card: Card, val from: Tier?)

    /** Un artiste dans le classeur : ses cartes et combien de ses sons tu as écoutés. */
    data class ArtistBook(val name: String, val cards: List<Card>, val heard: Int)

    fun mainArtistOf(a: String) = a.split(",", "&", " feat", " Feat", " ft.", " x ").first().trim()
    private fun n(x: String) = x.lowercase(java.util.Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")
    private fun lookupKey(title: String, artist: String) = n(title.substringBefore(" (").substringBefore(" - ")) + "|" + n(mainArtistOf(artist))

    private val _artists = MutableStateFlow<List<ArtistBook>>(emptyList())
    val artists: StateFlow<List<ArtistBook>> = _artists
    @Volatile private var index: Map<String, Card> = emptyMap()

    /** La carte d'un son (lecteur, listes), même si le titre est écrit un peu différemment. */
    fun find(title: String, artist: String): Card? = index[lookupKey(title, artist)]

    private const val DAY = 24L * 60 * 60 * 1000
    private var prefs: android.content.SharedPreferences? = null
    /** rang mémorisé + dates d'arrivée à chaque rang, par son */
    private val saved = HashMap<String, Pair<Int, LongArray>>()

    private val _cards = MutableStateFlow<List<Card>>(emptyList())
    val cards: StateFlow<List<Card>> = _cards
    private val _promo = MutableStateFlow<Promo?>(null)
    val promo: StateFlow<Promo?> = _promo
    private val pending = ArrayDeque<Promo>()
    /** Pochettes trouvées dans le catalogue Tidal, par son. */
    private val covers = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val coverMiss = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private val resolving = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Une bonne pochette : celle de Tidal d'abord, sinon une image web, sinon un fichier qui existe vraiment. */
    private fun bestCover(key: String, list: List<Ev>): Any? {
        covers[key]?.let { return it }
        val urls = list.mapNotNull { it.cover as? String }.filter { it.startsWith("http") }
        urls.firstOrNull { it.contains("tidal") }?.let { return it }
        urls.firstOrNull()?.let { return it }
        return list.mapNotNull { it.cover as? java.io.File }.firstOrNull { it.exists() && it.length() > 0 }
    }

    /** Va chercher sur Tidal les pochettes qui manquent (un son à la fois, pour ne pas se faire bloquer). */
    suspend fun resolveCovers() {
        if (!TidalApi.isConfigured || !resolving.compareAndSet(false, true)) return
        try {
            while (true) {
                val c = _cards.value.firstOrNull { (it.cover !is String) && it.key !in coverMiss && !covers.containsKey(it.key) } ?: break
                val url = runCatching {
                    fun n(x: String) = x.lowercase(java.util.Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")
                    val r = TidalApi.search("${c.title} ${c.artist.split(",", "&").first()}")
                    val an = n(c.artist.split(",", "&").first())
                    (r.firstOrNull { (n(it.fullTitle) == n(c.title) || n(it.title) == n(c.title)) && it.artists.any { a -> n(a).contains(an) || an.contains(n(a)) } }
                        ?: r.firstOrNull { n(it.title) == n(c.title) })?.coverUrl
                }.getOrNull()
                if (url == null) coverMiss += c.key
                else {
                    covers[c.key] = url
                    _cards.value = _cards.value.map { if (it.key == c.key) it.copy(cover = url) else it }
                    index = _cards.value.associateBy { lookupKey(it.title, it.artist) }
                    prefs?.edit()?.putString("covers", JSONObject(covers as Map<*, *>).toString())?.apply()
                }
                kotlinx.coroutines.delay(400)
            }
        } finally { resolving.set(false) }
    }

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("hush_cards", Context.MODE_PRIVATE)
        prefs = p
        runCatching { val o = JSONObject(p.getString("covers", "{}") ?: "{}"); for (k in o.keys()) covers[k] = o.getString(k) }
        runCatching {
            val o = JSONObject(p.getString("tiers", "{}") ?: "{}")
            for (k in o.keys()) {
                val x = o.getJSONObject(k)
                val arr = x.optJSONArray("d")
                val d = LongArray(4) { i -> arr?.optLong(i) ?: 0L }
                saved[k] = x.optInt("t", -1) to d
            }
        }
    }

    private fun tierOf(score: Int, recentSkip: Boolean): Tier? {
        var t: Tier? = null
        for (x in Tier.entries) if (score >= x.min) t = x
        // Diamant : il faut en plus ne pas l'avoir passé ce mois-ci
        if (t == Tier.Diamant && recentSkip) t = Tier.Or
        return t
    }

    /** Recalcule toutes les cartes ; les montées de rang sont mises en file pour l'annonce. */
    fun compute(events: List<Ev>, skips: Map<String, SkipTracker.Skip>, announce: Boolean) = synchronized(this) {
        val now = System.currentTimeMillis()
        val from = now - 90 * DAY
        val by = HashMap<String, MutableList<Ev>>()
        val allCount = HashMap<String, Int>()
        val month = HashMap<String, Int>()
        // tous les sons écoutés de chaque artiste (pour « 4 cartes sur 12 sons »)
        val heardBy = HashMap<String, MutableSet<String>>()
        for (e in events) {
            allCount[e.key] = (allCount[e.key] ?: 0) + 1
            if (e.ts >= now - 30 * DAY) month[e.key] = (month[e.key] ?: 0) + 1
            heardBy.getOrPut(n(mainArtistOf(e.artist))) { HashSet() }.add(e.key)
            if (e.ts >= from) by.getOrPut(e.key) { ArrayList() }.add(e)
        }
        val monthTop = month.maxByOrNull { it.value }?.takeIf { it.value >= 5 }?.key
        val first = prefs?.getBoolean("init", false) != true
        val out = ArrayList<Card>()
        var changed = false
        for ((k, list) in by) {
            val sk = skips[k]
            val skipN = sk?.count ?: 0
            val score = list.size - 2 * skipN
            val last = list.maxOf { it.ts }
            var tier = tierOf(score, sk != null && now - sk.at < 30 * DAY)
            // pas écouté depuis 2 mois : un cran en dessous
            if (tier != null && now - last > 60 * DAY) tier = Tier.entries.getOrNull(tier.ordinal - 1)
            val old = saved[k]
            val dates = old?.second?.copyOf() ?: LongArray(4)
            if (tier != null) {
                for (i in 0..tier.ordinal) if (dates[i] == 0L) dates[i] = now
            }
            val newT = tier?.ordinal ?: -1
            if (old == null || old.first != newT || !old.second.contentEquals(dates)) { saved[k] = newT to dates; changed = true }
            if (tier == null) continue
            val any = list.maxBy { it.ts }
            // en danger : dans combien de jours elle redescend (plus écoutée, ou écoutes qui sortent des 90 jours)
            var danger: Int? = null
            var dangerTo: Tier? = null
            val idle = (60 - (now - last) / DAY).toInt()
            if (now - last <= 60 * DAY && idle <= 14) { danger = idle.coerceAtLeast(0); dangerTo = Tier.entries.getOrNull(tier.ordinal - 1) }
            for (x in 1..14) {
                if (danger != null && x >= danger) break
                val cut = now + x * DAY - 90 * DAY
                val sc = list.count { it.ts >= cut } - 2 * skipN
                val t2 = tierOf(sc, sk != null && now - sk.at < 30 * DAY)
                if (t2 == null || t2 < tier) { danger = x; dangerTo = t2; break }
            }
            val total = allCount[k] ?: list.size
            val special = when {
                k == monthTop -> "N°1 DU MOIS"
                total >= 100 -> "${(total / 100) * 100} ÉCOUTES"
                else -> null
            }
            val card = Card(
                k, any.title, any.artist, bestCover(k, list), score.coerceAtLeast(0), list.size, skipN,
                list.sumOf { it.durMs }, tier, Tier.entries.associateWith { dates[it.ordinal] }, last,
                special = special, dangerDays = danger, dangerTo = dangerTo,
            )
            out += card
            if (announce && !first && newT > (old?.first ?: -1) && newT >= Tier.Argent.ordinal) {
                pending.addLast(Promo(card, old?.first?.let { Tier.entries.getOrNull(it) }))
            }
        }
        out.sortWith(compareByDescending<Card> { it.tier.ordinal }.thenByDescending { it.score })
        val numbered = out.mapIndexed { i, c -> c.copy(number = i + 1) }
        _cards.value = numbered
        index = numbered.associateBy { lookupKey(it.title, it.artist) }
        _artists.value = numbered.groupBy { n(it.mainArtist) }.map { (ak, l) ->
            ArtistBook(l.first().mainArtist, l, (heardBy[ak]?.size ?: l.size).coerceAtLeast(l.size))
        }.sortedWith(compareByDescending<ArtistBook> { it.cards.size }.thenByDescending { b -> b.cards.sumOf { it.score } })
        if (changed || first) save()
        if (_promo.value == null) _promo.value = pending.removeFirstOrNull()
    }

    fun dismissPromo() { synchronized(this) { _promo.value = pending.removeFirstOrNull() } }

    private fun save() {
        val o = JSONObject()
        for ((k, v) in saved) {
            o.put(k, JSONObject().put("t", v.first).put("d", org.json.JSONArray(v.second.toList())))
        }
        prefs?.edit()?.putString("tiers", o.toString())?.putBoolean("init", true)?.apply()
    }
}
