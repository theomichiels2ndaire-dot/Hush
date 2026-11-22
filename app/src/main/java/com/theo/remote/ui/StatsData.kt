package com.theo.remote.ui

import com.theo.remote.History
import com.theo.remote.LastFm
import java.io.File
import java.util.Calendar

/** Une écoute, qu'elle vienne de Hush ou de Last.fm. */
data class Ev(val key: String, val title: String, val artist: String, val cover: Any?, val ts: Long, val durMs: Long)

private const val DEFAULT_MS = 200_000L

private object EventsCache {
    var e: List<History.Entry>? = null
    var s: List<LastFm.Scrobble>? = null
    var v = -1
    var out: List<Ev> = emptyList()
}

/**
 * Fusionne l'historique Hush et les scrobbles Last.fm, sans compter deux fois la même écoute.
 * Le résultat est gardé en mémoire : tous les écrans (Stats, défis, records, mix…) partagent le même calcul,
 * refait seulement quand l'historique ou l'import Last.fm change.
 */
fun buildEvents(entries: List<History.Entry>, scrobbles: List<LastFm.Scrobble>): List<Ev> = synchronized(EventsCache) {
    val v = com.theo.remote.StatsExclude.version.value
    if (entries === EventsCache.e && scrobbles === EventsCache.s && v == EventsCache.v) return@synchronized EventsCache.out
    val all = buildEventsRaw(entries, scrobbles)
    // sons exclus et moments en mode invité : ils ne comptent pas
    val r = if (com.theo.remote.StatsExclude.isEmpty) all else all.filterNot { com.theo.remote.StatsExclude.skip(it.key, it.ts) }
    EventsCache.e = entries; EventsCache.s = scrobbles; EventsCache.v = v; EventsCache.out = r
    r
}

private fun buildEventsRaw(entries: List<History.Entry>, scrobbles: List<LastFm.Scrobble>): List<Ev> {
    val byKey = entries.associateBy { it.key }
    val out = ArrayList<Ev>(scrobbles.size + entries.size * 4)

    val scrobTimes = HashMap<String, MutableList<Long>>()
    for (s in scrobbles) {
        val k = History.keyOf(s.title, s.artist)
        val e = byKey[k]
        out += Ev(k, s.title, s.artist, e?.coverUrl ?: s.image ?: e?.artPath?.let { File(it) }, s.ts, e?.durationMs?.takeIf { it > 0 } ?: DEFAULT_MS)
        scrobTimes.getOrPut(k) { ArrayList() }.add(s.ts)
    }
    scrobTimes.values.forEach { it.sort() }

    val window = 6 * 60_000L
    for (e in entries) {
        val times = scrobTimes[e.key]
        val cover: Any? = e.coverUrl ?: e.artPath?.let { File(it) }
        for (t in e.plays) {
            if (times != null) {
                var i = times.binarySearch(t)
                if (i < 0) i = -i - 1
                val near = (i < times.size && times[i] - t < window) || (i > 0 && t - times[i - 1] < window)
                if (near) continue
            }
            out += Ev(e.key, e.title, e.artist, cover, t, e.durationMs.takeIf { it > 0 } ?: DEFAULT_MS)
        }
    }
    out.sortBy { it.ts }
    return out
}

fun mainArtist(a: String) = a.split(",", "&", " feat", " ft.").first().trim()

data class Stats(
    val plays: Int,
    val minutes: Long,
    val topTracks: List<Pair<Ev, Int>>,
    val topArtists: List<Pair<String, Int>>,
    val perDay: List<Pair<String, Int>>,
    val perDayMinutes: List<Pair<String, Long>>,
    val perHour: IntArray,
    val streak: Int,
    val activeDays: Int,
    val distinctArtists: Int,
    val distinctTracks: Int,
)

private fun startOfDay(cal: Calendar, t: Long): Long = cal.apply {
    timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** Stats entre `from` et `to` (ms). Les barres « par jour » couvrent toujours les 7 derniers jours de la période. */
fun computeStats(all: List<Ev>, from: Long, to: Long = System.currentTimeMillis()): Stats {
    val events = all.filter { it.ts in from until to }
    val day = 24L * 60 * 60 * 1000
    val cal = Calendar.getInstance()

    val counts = events.groupingBy { it.key }.eachCount()
    val latest = HashMap<String, Ev>()
    events.forEach { e -> val old = latest[e.key]; if (old == null || old.cover == null) latest[e.key] = e }
    val topTracks = counts.entries.sortedByDescending { it.value }.take(10).mapNotNull { (k, c) -> latest[k]?.let { it to c } }
    val artistCounts = events.groupingBy { mainArtist(it.artist) }.eachCount().filterKeys { it.isNotBlank() }
    val topArtists = artistCounts.entries.sortedByDescending { it.value }.take(10).map { it.key to it.value }
    val minutes = events.sumOf { it.durMs } / 60_000

    val dayNames = listOf("dim", "lun", "mar", "mer", "jeu", "ven", "sam")
    val lastDay = startOfDay(cal, minOf(to, System.currentTimeMillis()) - 1)
    val perDay = (6 downTo 0).map { i ->
        val start = lastDay - i * day
        val label = dayNames[cal.apply { timeInMillis = start }.get(Calendar.DAY_OF_WEEK) - 1]
        val inDay = all.filter { it.ts in start until start + day }
        Triple(label, inDay.size, inDay.sumOf { it.durMs } / 60_000)
    }
    val perHour = IntArray(24)
    events.forEach { perHour[cal.apply { timeInMillis = it.ts }.get(Calendar.HOUR_OF_DAY)]++ }

    val activeAll = all.map { startOfDay(cal, it.ts) }.toHashSet()
    val today = startOfDay(cal, System.currentTimeMillis())
    var streak = 0
    var d = if (today in activeAll) today else today - day
    while (d in activeAll) { streak++; d -= day }

    return Stats(
        plays = events.size,
        minutes = minutes,
        topTracks = topTracks,
        topArtists = topArtists,
        perDay = perDay.map { it.first to it.second },
        perDayMinutes = perDay.map { it.first to it.third },
        perHour = perHour,
        streak = streak,
        activeDays = events.map { startOfDay(cal, it.ts) }.toHashSet().size,
        distinctArtists = artistCounts.size,
        distinctTracks = counts.size,
    )
}

/** Début et fin d'un mois (offset 0 = ce mois-ci, 1 = le mois dernier…). */
fun monthRange(offset: Int): Pair<Long, Long> {
    val c = Calendar.getInstance()
    c.set(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    c.add(Calendar.MONTH, -offset)
    val start = c.timeInMillis
    c.add(Calendar.MONTH, 1)
    return start to c.timeInMillis
}

private val MONTHS = listOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre")

fun monthName(offset: Int): String {
    val c = Calendar.getInstance()
    c.add(Calendar.MONTH, -offset)
    return MONTHS[c.get(Calendar.MONTH)]
}

/** « d'octobre » / « de mai » */
fun ofMonth(name: String) = if (name.first() in "aeiouyéèê") "d'$name" else "de $name"

fun fmtMinutes(min: Long): String = when {
    min >= 60 -> "${min / 60} h ${"%02d".format(min % 60)}"
    else -> "$min min"
}
