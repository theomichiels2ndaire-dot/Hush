package com.theo.remote

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.theo.remote.ui.Badges
import com.theo.remote.ui.Challenges
import com.theo.remote.ui.buildEvents
import com.theo.remote.ui.mainArtist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Calendar

/**
 * Récompenses : tu gagnes de l'XP en écoutant et en jouant, et chaque niveau débloque
 * un truc à équiper (couleurs, icônes, effets de pochette, fonds…). Rien de bloqué dans l'appli.
 */
object Rewards {
    data class Unlock(val id: String, val cat: String, val label: String, val level: Int, val desc: String)

    data class Xp(val listen: Long, val streak: Long, val artists: Long, val challenges: Long, val badges: Long, val games: Long) {
        val total get() = listen + streak + artists + challenges + badges + games
    }

    val UNLOCKS = listOf(
        Unlock("accent:gold", "accent", "Accent or", 2, "Une couleur d'accent dorée"),
        Unlock("accent:neon", "accent", "Accent néon", 2, "Un vert néon qui claque"),
        Unlock("accent:ruby", "accent", "Accent rubis", 3, "Un rouge profond"),
        Unlock("icon:unie", "icon", "Icône Onde inversée", 2, "Onde noire sur fond crème"),
        Unlock("icon:brique", "icon", "Icône Brique", 3, "Onde crème sur rouge brique"),
        Unlock("icon:sapin", "icon", "Icône Sapin", 4, "Onde crème sur vert sapin"),
        Unlock("icon:encre", "icon", "Icône Encre", 5, "Onde crème sur bleu encre"),
        Unlock("icon:or", "icon", "Icône Onde dorée", 12, "La rare : onde dorée sur noir"),
        Unlock("icon:givree", "icon", "Icône Onde givrée", 4, "Onde blanche nacrée sur bleu nuit"),
        Unlock("icon:aurore", "icon", "Icône Onde aurore", 4, "Menthe vers lilas, point rose"),
        Unlock("icon:champagne", "icon", "Icône Onde champagne", 5, "Onde dorée sur noir"),
        Unlock("icon:cerclee", "icon", "Icône Onde cerclée", 5, "L'onde dans un cercle de verre"),
        Unlock("cover:halo", "cover", "Halo néon", 6, "Une lueur aux couleurs de l'accent autour de la pochette"),
        Unlock("cover:glass", "cover", "Reflets verre", 6, "Un reflet brillant sur la pochette"),
        Unlock("cover:vinyl", "cover", "Vinyle", 7, "La pochette ronde qui tourne pendant la lecture"),
        Unlock("cover:frame", "cover", "Pochette encadrée", 6, "Un filet fin autour de la pochette, comme un cadre de galerie"),
        Unlock("cover:disc", "cover", "Disque plat", 7, "Un disque noir qui tourne, ta pochette en étiquette au centre"),
        Unlock("time:retro", "time", "Chiffres rétro", 8, "Le temps du lecteur façon afficheur de platine"),
        Unlock("bar:groove", "bar", "Barre en sillon", 9, "La barre de lecture ondule pendant que ça joue"),
        Unlock("bg:ink", "bg", "Encre", 4, "Bleu nuit uni à la place du noir"),
        Unlock("bg:season", "bg", "Saisons", 10, "Le fond prend la teinte de la saison (roux en automne, gris bleuté l'hiver…)"),
        Unlock("bg:aurora", "bg", "Fond aurore", 8, "Des lueurs vertes et violettes"),
        Unlock("bg:stars", "bg", "Nuit étoilée", 8, "Un ciel étoilé derrière l'appli"),
        Unlock("bg:sunset", "bg", "Coucher de soleil", 9, "Orange, rose et violet"),
        Unlock("card:frame", "card", "Cadre doré", 10, "Un cadre et ton titre sur ta carte d'identité musicale"),
        Unlock("badges:card", "badges", "Badges sur ta carte", 6, "Tes badges gagnés s'affichent sur ta carte d'identité musicale"),
        Unlock("sound:shh", "sound", "Son de démarrage", 3, "Un petit « shh » discret à l'ouverture de Hush"),
        Unlock("tab:fifth", "tab", "5e onglet", 11, "Un onglet de plus en bas : Fichiers locaux, Blind test ou Outils"),
        Unlock("stats:pro", "stats", "Stats avancées", 12, "Ton écoute par jour de la semaine et ton artiste de chaque mois"),
        Unlock("widget:disc", "widget", "Widget Disque", 13, "Un widget rond : un vinyle avec ta pochette, lecture / pause au centre"),
        Unlock("title:custom", "title", "Titre perso", 14, "Tu choisis ton titre au lieu de celui de ton niveau"),
        Unlock("home:notes", "home", "Notes qui flottent", 15, "Des notes de musique sur l'accueil (hors thèmes de saison)"),
        Unlock("night:wave", "night", "Horloge Onde", 15, "Une onde animée sur l'écran de nuit"),
    )

    val CATEGORIES = listOf(
        "accent" to "Couleurs d'accent", "icon" to "Icône de l'appli", "cover" to "Effet de pochette",
        "bg" to "Fond de l'appli", "time" to "Chiffres du lecteur", "bar" to "Barre de lecture",
        "card" to "Carte d'identité", "badges" to "Badges", "home" to "Accueil", "night" to "Écran de nuit",
        "tab" to "Navigation", "widget" to "Widgets", "stats" to "Stats", "sound" to "Son", "title" to "Titre",
    )

    /** Titres au choix (récompense « Titre perso »). */
    val TITLES = listOf("Curieux", "Auditeur", "Passionné", "Mélomane", "Audiophile", "Légende", "Digger", "Insomniaque", "DJ du salon", "Chef d'orchestre", "Collectionneur", "Oreille d'or")

    /** Ce que montre le 5e onglet : "local", "blind" ou "tools". */
    fun fifthTab(): String = prefs?.getString("fifthTab", "local") ?: "local"
    fun setFifthTab(v: String) { prefs?.edit()?.putString("fifthTab", v)?.apply(); _equipped.value = _equipped.value.toMap() }

    fun customTitle(): String? = prefs?.getString("customTitle", null)
    fun setCustomTitle(v: String) { prefs?.edit()?.putString("customTitle", v)?.apply(); _equipped.value = _equipped.value.toMap() }

    private val _xp = MutableStateFlow<Xp?>(null)
    val xp: StateFlow<Xp?> = _xp
    private val _equipped = MutableStateFlow<Map<String, String>>(emptyMap())
    /** catégorie → id équipé */
    val equipped: StateFlow<Map<String, String>> = _equipped
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("hush", Context.MODE_PRIVATE)
        prefs = p
        _equipped.value = runCatching {
            val o = JSONObject(p.getString("rewardsEquipped", "{}"))
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
    }

    /** XP nécessaire pour atteindre un niveau (niv. 2 = 500, niv. 3 = 1 500, niv. 5 = 5 000, niv. 10 = 22 500…). */
    fun xpFor(level: Int): Long = 250L * (level - 1) * level

    fun levelOf(total: Long): Int {
        var l = 1
        while (total >= xpFor(l + 1)) l++
        return l
    }

    fun level(): Int = _xp.value?.let { levelOf(it.total) } ?: 1

    fun title(level: Int): String = customTitle()?.takeIf { isOn("title:custom") } ?: levelTitle(level)

    fun levelTitle(level: Int) = when {
        level >= 20 -> "Légende"
        level >= 15 -> "Audiophile"
        level >= 10 -> "Mélomane"
        level >= 5 -> "Passionné"
        level >= 3 -> "Auditeur"
        else -> "Curieux"
    }

    fun unlocked(id: String): Boolean = UNLOCKS.firstOrNull { it.id == id }?.let { level() >= it.level } ?: false

    fun equippedIn(cat: String): String? = _equipped.value[cat]?.takeIf { unlocked(it) }

    fun isOn(id: String): Boolean = equippedIn(id.substringBefore(':')) == id

    fun equip(context: Context, cat: String, id: String?) {
        init(context)
        _equipped.value = if (id == null) _equipped.value - cat else _equipped.value + (cat to id)
        prefs?.edit()?.putString("rewardsEquipped", JSONObject(_equipped.value).toString())?.apply()
        if (cat == "icon") applyIcon(context, id)
        if (cat == "widget") runCatching {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, HushDiscReceiver::class.java),
                if (id != null) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            if (id != null) TidalRemote.showToast("Le widget « Hush disque » est dans la liste des widgets")
        }
        if (cat == "accent") {
            val f = Features.flags.value
            Features.set(f.copy(accent = id?.substringAfter(':') ?: "violet"))
        }
    }

    /** Change l'icône de l'écran d'accueil (alias du lanceur). */
    private val ICONS = listOf(null to "LauncherOnde", "icon:unie" to "LauncherUnie", "icon:givree" to "LauncherGivree", "icon:aurore" to "LauncherAurore", "icon:champagne" to "LauncherChampagne", "icon:cerclee" to "LauncherCerclee", "icon:brique" to "LauncherBrique", "icon:sapin" to "LauncherSapin", "icon:encre" to "LauncherEncre", "icon:or" to "LauncherOr")

    private fun applyIcon(context: Context, id: String?) {
        val pm = context.packageManager
        val target = ICONS.firstOrNull { it.first == id }?.second ?: "LauncherOnde"
        // on active d'abord la nouvelle, puis on coupe les autres (jamais zéro icône)
        ICONS.sortedBy { if (it.second == target) 0 else 1 }.forEach { (_, alias) ->
            runCatching {
                pm.setComponentEnabledSetting(
                    ComponentName(context, "com.theo.remote.$alias"),
                    if (alias == target) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
        TidalRemote.showToast("Nouvelle icône : elle apparaît sur ton écran d'accueil dans quelques secondes")
    }

    /** XP gagnée en jeu (blind test, quiz paroles) : tes points ÷ 10. */
    fun addGameXp(points: Int, context: Context? = null) {
        context?.let { init(it) }
        val gained = (points / 10).coerceAtLeast(0)
        if (gained == 0) return
        val p = prefs ?: return
        p.edit().putLong("xpGames", p.getLong("xpGames", 0) + gained).apply()
        _xp.value = _xp.value?.let { it.copy(games = it.games + gained) }
        TidalRemote.showToast("+$gained XP")
    }

    /** Recalcule toute l'XP depuis ton historique (hors du fil principal). */
    suspend fun recompute(context: Context) = withContext(Dispatchers.Default) {
        val ctx = context.applicationContext
        init(ctx)
        val ev = buildEvents(History.entries.value, LastFm.scrobbles.value)
        val listen = ev.sumOf { it.durMs } / 60_000
        // série : chaque jour d'affilée rapporte un peu plus (+10, +20… jusqu'à +100)
        val cal = Calendar.getInstance()
        val days = ev.map { cal.timeInMillis = it.ts; cal.get(Calendar.YEAR) * 400 + cal.get(Calendar.DAY_OF_YEAR) }.distinct().sorted()
        var streak = 0L; var run = 0; var prev = -10
        for (d in days) {
            run = if (d == prev + 1 || (d % 400 == 1 && prev % 400 >= 365)) run + 1 else 1
            streak += 10L * run.coerceAtMost(10)
            prev = d
        }
        val artists = ev.map { mainArtist(it.artist).lowercase() }.toHashSet().size * 20L
        val challenges = Challenges.totalDone(ctx) * 150L
        val badges = runCatching { Badges.compute(ctx, ev).count { it.unlocked } * 200L }.getOrDefault(0L)
        val games = prefs?.getLong("xpGames", 0) ?: 0
        _xp.value = Xp(listen, streak, artists, challenges, badges, games)
    }

    /** Niveau gagné depuis la dernière fois (null si rien de neuf). */
    fun levelUp(): Pair<Int, Int>? {
        val x = _xp.value ?: return null
        val p = prefs ?: return null
        val now = levelOf(x.total)
        val last = p.getInt("lastLevel", 0)
        if (now <= last) return null
        return (if (last == 0) 0 else last) to now
    }

    fun ackLevel(level: Int) { prefs?.edit()?.putInt("lastLevel", level)?.apply() }
}
