package com.theo.remote

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Les options qu'on peut couper dans les Réglages (toutes actives au départ). */
object Features {

    /** Ordre par défaut des sections de l'accueil. */
    val HOME_DEFAULT = listOf("tools", "resume", "now", "foryou", "mix", "growing", "gem", "yearAgo", "skips", "chartsAgo", "shortcuts", "mood", "weekly", "moment", "releases", "upcoming", "playlists", "forgotten", "recents")

    data class Flags(
        val translate: Boolean = true,
        val squareWidget: Boolean = true,
        val voice: Boolean = true,
        val timeTheme: Boolean = true,
        val autoBackup: Boolean = true,
        val animatedCover: Boolean = true,
        val eco: Boolean = false,
        val reminder: Boolean = true,
        val reminderHour: Int = 21,
        /** sections de l'accueil masquées */
        val hidden: Set<String> = emptySet(),
        val richNotif: Boolean = true,
        val mediaCard: Boolean = true,
        val resumeLast: Boolean = true,
        val autoOpenTidal: Boolean = true,
        val playlistRecs: Boolean = true,
        val localFiles: Boolean = true,
        val haptics: Boolean = true,
        val flatStyle: Boolean = false,
        val antiRepeat: Boolean = true,
        /** "violet" (lilas Hush), "green", "blue", "pink", "orange" ou "cover" (couleur de la pochette) */
        val accent: String = "violet",
        val fullCover: Boolean = true,
        val compactHome: Boolean = false,
        /** objectif d'écoute en minutes par jour (0 = pas d'objectif) */
        val goalMin: Int = 60,
        val compareMonths: Boolean = true,
        val heatmap: Boolean = true,
        val smartLists: Boolean = false,
        val follow: Boolean = true,
        val quotes: Boolean = true,
        val challenges: Boolean = true,
        val eras: Boolean = true,
        val yearAgo: Boolean = true,
        val firstListen: Boolean = true,
        val records: Boolean = true,
        val idCard: Boolean = true,
        val notes: Boolean = true,
        val hiddenGem: Boolean = true,
        val discography: Boolean = true,
        val feats: Boolean = true,
        val lyricWords: Boolean = true,
        val bass: Boolean = true,
        val spatial: Boolean = true,
        val trip: Boolean = true,
        /** ordre des sections de l'accueil */
        val homeOrder: List<String> = Features.HOME_DEFAULT,
        val whatsNew: Boolean = true,
        val wrappedYear: Boolean = true,
        val weeklyRecap: Boolean = true,
        val albums: Boolean = true,
        val statsExclude: Boolean = true,
        val ambient: Boolean = true,
        val credits: Boolean = true,
        val clip: Boolean = true,
        val concerts: Boolean = true,
        val chartsAgo: Boolean = true,
        val amoled: Boolean = false,
        val leftHanded: Boolean = false,
        val statsWidget: Boolean = true,
        val oneSong: Boolean = true,
        val seasonal: Boolean = true,
        /** "auto", "halloween", "noel", "ete" */
        val season: String = "auto",
        val setlists: Boolean = true,
        val skipDetect: Boolean = true,
        val smartShuffle: Boolean = true,
        val lyricsQuiz: Boolean = true,
        val rewards: Boolean = true,
        /** taille du texte en % (90, 100, 110, 125) */
        val textScale: Int = 100,
        val localFade: Boolean = true,
        val localNormalize: Boolean = true,
        val resumePlaylist: Boolean = true,
        val dupWarn: Boolean = true,
        val autoLocalArt: Boolean = true,
        val folders: Boolean = true,
        val playlistSort: Boolean = true,
        val shareImage: Boolean = true,
        val adaptiveColor: Boolean = true,
        val qsTile: Boolean = true,
        val hideTidalNotif: Boolean = true,
        val gapless: Boolean = true,
        val fixVersions: Boolean = true,
        val timeCapsule: Boolean = true,
        val ratings: Boolean = true,
        val playlistCovers: Boolean = true,
        val focusAlbum: Boolean = true,
        val edgePanel: Boolean = false,
        val playlistWidget: Boolean = true,
        val iconShortcuts: Boolean = true,
        val whereIsIt: Boolean = true,
        val deviceProfiles: Boolean = true,
        val ratingsEverywhere: Boolean = true,
        val ratePrompt: Boolean = true,
        val discover: Boolean = true,
        val videoCovers: Boolean = true,
        val silentHandoff: Boolean = true,
        val liveSearch: Boolean = true,
        val coverWall: Boolean = true,
        val smoothChange: Boolean = true,
        val doubleTapLike: Boolean = true,
        val upcoming: Boolean = true,
        val playlistDoctor: Boolean = true,
        val growing: Boolean = true,
        val liquidGlass: Boolean = true,
        val playerGestures: Boolean = true,
        val smartHome: Boolean = true,
        val glassPanels: Boolean = true,
        val coverMorph: Boolean = true,
        val deviceChip: Boolean = true,
        val songCards: Boolean = true,
        val cardPromo: Boolean = true,
        val cardHolo: Boolean = true,
        val cardSpecial: Boolean = true,
        val cardDanger: Boolean = true,
        val cardBadges: Boolean = true,
        val statusPill: Boolean = true,
        val wavyBar: Boolean = true,
        val queueCovers: Boolean = true,
        val instantPlaylist: Boolean = true,
        val glassPlayer: Boolean = true,
        val glassTube: Boolean = true,
        val searchReset: Boolean = true,
        val simpleMode: Boolean = false,
        val crispShapes: Boolean = true,
        val threeTabs: Boolean = false,
        val undoBar: Boolean = true,
        val earlyCutAlert: Boolean = true,
        val autoUpdate: Boolean = true,
        val noDuplicates: Boolean = true,
        val instantUi: Boolean = true,
        val gestureHints: Boolean = true,
        val homeCache: Boolean = true,
        val clearStates: Boolean = true,
        val setupCheck: Boolean = true,
    )

    private val _flags = MutableStateFlow(Flags())
    val flags: StateFlow<Flags> = _flags
    private var prefs: android.content.SharedPreferences? = null
    private var appContext: Context? = null

    fun init(context: Context) {
        if (prefs != null) return
        appContext = context.applicationContext
        val p = context.applicationContext.getSharedPreferences("hush", Context.MODE_PRIVATE)
        prefs = p
        // nouveau design (relief, flou et pochettes) : on quitte le style Pochette une fois
        if (!p.getBoolean("designV2", false)) p.edit().putBoolean("fFlat", false).putBoolean("designV2", true).apply()
        // première ouverture (pas encore de réglages enregistrés) : l'appli démarre en mode simple
        val fresh = !p.contains("fTranslate")
        _flags.value = read(p)
        if (fresh) set(simplified(_flags.value))
    }

    private fun read(p: android.content.SharedPreferences): Flags =
            Flags(
                translate = p.getBoolean("fTranslate", true),
                squareWidget = p.getBoolean("fSquare", true),
                voice = p.getBoolean("fVoice", true),
                timeTheme = p.getBoolean("fTime", true),
                autoBackup = p.getBoolean("fBackup", true),
                animatedCover = p.getBoolean("fCover", true),
                eco = p.getBoolean("fEco", false),
                reminder = p.getBoolean("fReminder", true),
                reminderHour = p.getInt("fReminderHour", 21),
                hidden = p.getStringSet("fHidden", emptySet()).orEmpty().toSet(),
                richNotif = p.getBoolean("fRichNotif", true),
                mediaCard = p.getBoolean("fMediaCard", true),
                resumeLast = p.getBoolean("fResume", true),
                autoOpenTidal = p.getBoolean("fAutoTidal", true),
                playlistRecs = p.getBoolean("fPlRecs", true),
                localFiles = p.getBoolean("fLocal", true),
                haptics = p.getBoolean("fHaptics", true),
                flatStyle = p.getBoolean("fFlat", false),
                antiRepeat = p.getBoolean("fAntiRepeat", true),
                // nouvelle identité : le lilas devient l'accent par défaut (une seule fois, si tu avais gardé l'ancien vert)
                accent = (p.getString("fAccent", "violet") ?: "violet").let { a ->
                    if (!p.getBoolean("rebrandOnde", false)) { val n = if (a == "green") "violet" else a; p.edit().putBoolean("rebrandOnde", true).putString("fAccent", n).apply(); n } else a
                },
                fullCover = p.getBoolean("fFullCover", true),
                compactHome = p.getBoolean("fCompact", false),
                goalMin = p.getInt("fGoal", 60),
                compareMonths = p.getBoolean("fCompare", true),
                heatmap = p.getBoolean("fHeatmap", true),
                smartLists = p.getBoolean("fSmart2", false),
                follow = p.getBoolean("fFollow", true),
                quotes = p.getBoolean("fQuotes", true),
                challenges = p.getBoolean("fChallenges", true),
                eras = p.getBoolean("fEras", true),
                yearAgo = p.getBoolean("fYearAgo", true),
                firstListen = p.getBoolean("fFirst", true),
                records = p.getBoolean("fRecords", true),
                idCard = p.getBoolean("fIdCard", true),
                notes = p.getBoolean("fNotes", true),
                hiddenGem = p.getBoolean("fGem", true),
                discography = p.getBoolean("fDisco", true),
                feats = p.getBoolean("fFeats", true),
                lyricWords = p.getBoolean("fWords", true),
                bass = p.getBoolean("fBass", true),
                spatial = p.getBoolean("fSpatial", true),
                trip = p.getBoolean("fTrip", true),
                homeOrder = p.getString("fHomeOrder", null)?.split(',')?.filter { it in HOME_DEFAULT }.orEmpty()
                    .let { saved ->
                        // une nouvelle section se place juste après celle qui la précède dans l'ordre par défaut
                        val out = saved.toMutableList()
                        HOME_DEFAULT.forEachIndexed { i, id ->
                            if (id !in out) {
                                val prev = HOME_DEFAULT.take(i).lastOrNull { it in out }
                                out.add(if (prev == null) 0 else out.indexOf(prev) + 1, id)
                            }
                        }
                        out
                    },
                whatsNew = p.getBoolean("fWhatsNew", true),
                wrappedYear = p.getBoolean("fWrappedYear", true),
                weeklyRecap = p.getBoolean("fWeekly", true),
                albums = p.getBoolean("fAlbums", true),
                statsExclude = p.getBoolean("fExclude", true),
                ambient = p.getBoolean("fAmbient", true),
                credits = p.getBoolean("fCredits", true),
                clip = p.getBoolean("fClip", true),
                concerts = p.getBoolean("fConcerts", true),
                chartsAgo = p.getBoolean("fCharts", true),
                amoled = p.getBoolean("fAmoled", false),
                leftHanded = p.getBoolean("fLeft", false),
                statsWidget = p.getBoolean("fStatsWidget", true),
                oneSong = p.getBoolean("fOneSong", true),
                seasonal = p.getBoolean("fSeasonal", true),
                season = p.getString("fSeason", "auto") ?: "auto",
                setlists = p.getBoolean("fSetlists", true),
                skipDetect = p.getBoolean("fSkips", true),
                smartShuffle = p.getBoolean("fSmartShuffle", true),
                lyricsQuiz = p.getBoolean("fLyricsQuiz", true),
                rewards = p.getBoolean("fRewards", true),
                textScale = p.getInt("fTextScale", 100),
                localFade = p.getBoolean("fLocalFade", true),
                localNormalize = p.getBoolean("fLocalNorm", true),
                resumePlaylist = p.getBoolean("fResumePl", true),
                dupWarn = p.getBoolean("fDupWarn", true),
                autoLocalArt = p.getBoolean("fLocalArt", true),
                folders = p.getBoolean("fFolders", true),
                playlistSort = p.getBoolean("fPlSort", true),
                shareImage = p.getBoolean("fShareImg", true),
                adaptiveColor = p.getBoolean("fAdaptive", true),
                qsTile = p.getBoolean("fQsTile", true),
                hideTidalNotif = p.getBoolean("fHideTidalNotif", true),
                gapless = p.getBoolean("fGapless", true),
                fixVersions = p.getBoolean("fFixVersions", true),
                timeCapsule = p.getBoolean("fCapsule", true),
                ratings = p.getBoolean("fRatings", true),
                playlistCovers = p.getBoolean("fPlCovers", true),
                focusAlbum = p.getBoolean("fFocusAlbum", true),
                edgePanel = p.getBoolean("fEdgePanel", false),
                playlistWidget = p.getBoolean("fPlWidget", true),
                iconShortcuts = p.getBoolean("fIconShortcuts", true),
                whereIsIt = p.getBoolean("fWhereIsIt", true),
                deviceProfiles = p.getBoolean("fDevProfiles", true),
                ratingsEverywhere = p.getBoolean("fRatingsAll", true),
                ratePrompt = p.getBoolean("fRatePrompt", true),
                discover = p.getBoolean("fDiscover", true),
                videoCovers = p.getBoolean("fVideoCovers", true),
                silentHandoff = p.getBoolean("fSilentHandoff", true),
                liveSearch = p.getBoolean("fLiveSearch", true),
                coverWall = p.getBoolean("fCoverWall", true),
                smoothChange = p.getBoolean("fSmoothChange", true),
                doubleTapLike = p.getBoolean("fDoubleTapLike", true),
                upcoming = p.getBoolean("fUpcoming", true),
                playlistDoctor = p.getBoolean("fPlDoctor", true),
                growing = p.getBoolean("fGrowing", true),
                liquidGlass = p.getBoolean("fLiquidGlass", true),
                playerGestures = p.getBoolean("fPlayerGestures", true),
                smartHome = p.getBoolean("fSmartHome", true),
                glassPanels = p.getBoolean("fGlassPanels", true),
                coverMorph = p.getBoolean("fCoverMorph", true),
                deviceChip = p.getBoolean("fDeviceChip", true),
                songCards = p.getBoolean("fSongCards", true),
                cardPromo = p.getBoolean("fCardPromo", true),
                cardHolo = p.getBoolean("fCardHolo", true),
                cardSpecial = p.getBoolean("fCardSpecial", true),
                cardDanger = p.getBoolean("fCardDanger", true),
                cardBadges = p.getBoolean("fCardBadges", true),
                statusPill = p.getBoolean("fStatusPill", true),
                wavyBar = p.getBoolean("fWavyBar", true),
                queueCovers = p.getBoolean("fQueueCovers", true),
                instantPlaylist = p.getBoolean("fInstantPl", true),
                glassPlayer = p.getBoolean("fGlassPlayer", true),
                glassTube = p.getBoolean("fGlassTube", true),
                searchReset = p.getBoolean("fSearchReset", true),
                simpleMode = p.getBoolean("fSimple", false),
                crispShapes = p.getBoolean("fCrisp", true),
                threeTabs = p.getBoolean("fThreeTabs", false),
                undoBar = p.getBoolean("fUndo", true),
                earlyCutAlert = p.getBoolean("fEarlyCut", true),
                autoUpdate = p.getBoolean("fAutoUpdate", true),
                noDuplicates = p.getBoolean("fNoDup", true),
                instantUi = p.getBoolean("fInstantUi", true),
                gestureHints = p.getBoolean("fHints", true),
                homeCache = p.getBoolean("fHomeCache", true),
                clearStates = p.getBoolean("fClearStates", true),
                setupCheck = p.getBoolean("fSetupCheck", true),
            )

    /** Mode simple : juste l'essentiel (accueil, recherche, biblio, lecteur), le reste se rallume dans les Réglages. */
    fun simplified(f: Flags) = f.copy(
        simpleMode = true,
        songCards = false, cardPromo = false, cardBadges = false, instantPlaylist = false, playlistDoctor = false,
        discover = false, growing = false, challenges = false, rewards = false, lyricsQuiz = false, eras = false,
        quotes = false, records = false, idCard = false, hiddenGem = false, timeCapsule = false, ratePrompt = false,
        ratings = false, ratingsEverywhere = false, coverWall = false, trip = false, setlists = false, seasonal = false,
        deviceProfiles = false, smartHome = false, chartsAgo = false, yearAgo = false, weeklyRecap = false,
        firstListen = false, compareMonths = false, heatmap = false, whereIsIt = false, deviceChip = false, bass = false,
        homeOrder = listOf("resume", "now", "foryou", "mix", "playlists", "recents"),
    )

    /** Allume / coupe le mode simple. En le coupant, on retrouve exactement les réglages d'avant. */
    fun setSimple(on: Boolean) {
        val p = prefs ?: return
        val ctx = appContext ?: return
        val backup = ctx.getSharedPreferences("hush_full", Context.MODE_PRIVATE)
        if (on) {
            if (_flags.value.simpleMode) return
            val e = backup.edit().clear()
            for ((k, v) in p.all) if (k.startsWith("f")) when (v) {
                is Boolean -> e.putBoolean(k, v); is Int -> e.putInt(k, v); is Long -> e.putLong(k, v)
                is String -> e.putString(k, v); is Float -> e.putFloat(k, v)
                is Set<*> -> e.putStringSet(k, v.filterIsInstance<String>().toSet())
            }
            e.apply()
            set(simplified(_flags.value))
        } else {
            if (!_flags.value.simpleMode) return
            val saved = backup.all
            if (saved.isEmpty()) { set(Flags(homeOrder = HOME_DEFAULT)); return }
            val e = p.edit()
            for ((k, v) in saved) when (v) {
                is Boolean -> e.putBoolean(k, v); is Int -> e.putInt(k, v); is Long -> e.putLong(k, v)
                is String -> e.putString(k, v); is Float -> e.putFloat(k, v)
                is Set<*> -> e.putStringSet(k, v.filterIsInstance<String>().toSet())
            }
            e.commit()
            set(read(p).copy(simpleMode = false))
            backup.edit().clear().apply()
        }
    }

    fun set(f: Flags) {
        val old = _flags.value
        _flags.value = f
        prefs?.edit()
            ?.putBoolean("fTranslate", f.translate)?.putBoolean("fSquare", f.squareWidget)
            ?.putBoolean("fVoice", f.voice)?.putBoolean("fTime", f.timeTheme)?.putBoolean("fBackup", f.autoBackup)
            ?.putBoolean("fCover", f.animatedCover)?.putBoolean("fEco", f.eco)
            ?.putBoolean("fReminder", f.reminder)?.putInt("fReminderHour", f.reminderHour)
            ?.putStringSet("fHidden", f.hidden)
            ?.putBoolean("fRichNotif", f.richNotif)?.putBoolean("fMediaCard", f.mediaCard)?.putBoolean("fResume", f.resumeLast)?.putBoolean("fAutoTidal", f.autoOpenTidal)?.putBoolean("fPlRecs", f.playlistRecs)?.putBoolean("fLocal", f.localFiles)?.putBoolean("fHaptics", f.haptics)?.putBoolean("fFlat", f.flatStyle)?.putBoolean("fAntiRepeat", f.antiRepeat)
            ?.putString("fAccent", f.accent)?.putBoolean("fFullCover", f.fullCover)?.putBoolean("fCompact", f.compactHome)
            ?.putInt("fGoal", f.goalMin)?.putBoolean("fCompare", f.compareMonths)?.putBoolean("fHeatmap", f.heatmap)?.putBoolean("fSmart2", f.smartLists)
            ?.putBoolean("fFollow", f.follow)?.putBoolean("fQuotes", f.quotes)?.putBoolean("fChallenges", f.challenges)?.putBoolean("fEras", f.eras)
            ?.putBoolean("fYearAgo", f.yearAgo)?.putBoolean("fFirst", f.firstListen)?.putBoolean("fRecords", f.records)?.putBoolean("fIdCard", f.idCard)?.putBoolean("fNotes", f.notes)
            ?.putBoolean("fGem", f.hiddenGem)?.putBoolean("fDisco", f.discography)?.putBoolean("fFeats", f.feats)?.putBoolean("fWords", f.lyricWords)
            ?.putBoolean("fBass", f.bass)?.putBoolean("fSpatial", f.spatial)?.putBoolean("fTrip", f.trip)?.putString("fHomeOrder", f.homeOrder.joinToString(","))
            ?.putBoolean("fWhatsNew", f.whatsNew)?.putBoolean("fWrappedYear", f.wrappedYear)?.putBoolean("fWeekly", f.weeklyRecap)?.putBoolean("fAlbums", f.albums)?.putBoolean("fExclude", f.statsExclude)?.putBoolean("fAmbient", f.ambient)?.putBoolean("fCredits", f.credits)?.putBoolean("fClip", f.clip)?.putBoolean("fConcerts", f.concerts)?.putBoolean("fCharts", f.chartsAgo)?.putBoolean("fAmoled", f.amoled)?.putBoolean("fLeft", f.leftHanded)?.putBoolean("fStatsWidget", f.statsWidget)?.putBoolean("fOneSong", f.oneSong)?.putBoolean("fSeasonal", f.seasonal)?.putString("fSeason", f.season)?.putBoolean("fSetlists", f.setlists)?.putBoolean("fSkips", f.skipDetect)?.putBoolean("fSmartShuffle", f.smartShuffle)?.putBoolean("fLyricsQuiz", f.lyricsQuiz)?.putBoolean("fRewards", f.rewards)?.putInt("fTextScale", f.textScale)?.putBoolean("fLocalFade", f.localFade)?.putBoolean("fLocalNorm", f.localNormalize)?.putBoolean("fResumePl", f.resumePlaylist)?.putBoolean("fDupWarn", f.dupWarn)?.putBoolean("fLocalArt", f.autoLocalArt)?.putBoolean("fFolders", f.folders)?.putBoolean("fPlSort", f.playlistSort)?.putBoolean("fShareImg", f.shareImage)?.putBoolean("fAdaptive", f.adaptiveColor)?.putBoolean("fQsTile", f.qsTile)?.putBoolean("fHideTidalNotif", f.hideTidalNotif)?.putBoolean("fGapless", f.gapless)?.putBoolean("fFixVersions", f.fixVersions)?.putBoolean("fCapsule", f.timeCapsule)?.putBoolean("fRatings", f.ratings)?.putBoolean("fPlCovers", f.playlistCovers)?.putBoolean("fFocusAlbum", f.focusAlbum)?.putBoolean("fEdgePanel", f.edgePanel)?.putBoolean("fPlWidget", f.playlistWidget)?.putBoolean("fIconShortcuts", f.iconShortcuts)?.putBoolean("fWhereIsIt", f.whereIsIt)?.putBoolean("fDevProfiles", f.deviceProfiles)?.putBoolean("fRatingsAll", f.ratingsEverywhere)?.putBoolean("fRatePrompt", f.ratePrompt)?.putBoolean("fDiscover", f.discover)?.putBoolean("fVideoCovers", f.videoCovers)?.putBoolean("fSilentHandoff", f.silentHandoff)?.putBoolean("fLiveSearch", f.liveSearch)?.putBoolean("fCoverWall", f.coverWall)?.putBoolean("fSmoothChange", f.smoothChange)?.putBoolean("fDoubleTapLike", f.doubleTapLike)?.putBoolean("fUpcoming", f.upcoming)?.putBoolean("fPlDoctor", f.playlistDoctor)?.putBoolean("fGrowing", f.growing)?.putBoolean("fLiquidGlass", f.liquidGlass)?.putBoolean("fPlayerGestures", f.playerGestures)?.putBoolean("fSmartHome", f.smartHome)?.putBoolean("fGlassPanels", f.glassPanels)?.putBoolean("fCoverMorph", f.coverMorph)?.putBoolean("fDeviceChip", f.deviceChip)?.putBoolean("fSongCards", f.songCards)?.putBoolean("fCardPromo", f.cardPromo)?.putBoolean("fCardHolo", f.cardHolo)?.putBoolean("fCardSpecial", f.cardSpecial)?.putBoolean("fCardDanger", f.cardDanger)?.putBoolean("fCardBadges", f.cardBadges)?.putBoolean("fStatusPill", f.statusPill)?.putBoolean("fWavyBar", f.wavyBar)?.putBoolean("fQueueCovers", f.queueCovers)?.putBoolean("fInstantPl", f.instantPlaylist)?.putBoolean("fGlassPlayer", f.glassPlayer)?.putBoolean("fGlassTube", f.glassTube)?.putBoolean("fSearchReset", f.searchReset)?.putBoolean("fSimple", f.simpleMode)?.putBoolean("fCrisp", f.crispShapes)?.putBoolean("fThreeTabs", f.threeTabs)?.putBoolean("fUndo", f.undoBar)?.putBoolean("fEarlyCut", f.earlyCutAlert)?.putBoolean("fAutoUpdate", f.autoUpdate)?.putBoolean("fNoDup", f.noDuplicates)?.putBoolean("fInstantUi", f.instantUi)?.putBoolean("fHints", f.gestureHints)?.putBoolean("fHomeCache", f.homeCache)?.putBoolean("fClearStates", f.clearStates)?.putBoolean("fSetupCheck", f.setupCheck)
            ?.apply()
        if (old.squareWidget != f.squareWidget) appContext?.let { setSquareWidget(it, f.squareWidget) }
        if (old.playlistWidget != f.playlistWidget) appContext?.let { ctx ->
            runCatching {
                ctx.packageManager.setComponentEnabledSetting(
                    ComponentName(ctx, PlaylistsWidgetReceiver::class.java),
                    if (f.playlistWidget) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
        if (old.iconShortcuts != f.iconShortcuts) PlaylistPlays.refreshShortcuts()
        if (old.reminder != f.reminder || old.reminderHour != f.reminderHour) appContext?.let { Reminder.schedule(it) }
        if (old.follow != f.follow || old.concerts != f.concerts || old.upcoming != f.upcoming) appContext?.let { Follow.schedule(it) }
        if (old.bass != f.bass || old.spatial != f.spatial) AudioFx.apply()
        if (old.weeklyRecap != f.weeklyRecap) appContext?.let { WeeklyRecap.schedule(it) }
        if (old.statsWidget != f.statsWidget) appContext?.let { setStatsWidget(it, f.statsWidget) }
        if (old.qsTile != f.qsTile) appContext?.let { ctx ->
            runCatching {
                ctx.packageManager.setComponentEnabledSetting(
                    ComponentName(ctx, HushTile::class.java),
                    if (f.qsTile) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
        if (!old.hideTidalNotif && f.hideTidalNotif || !old.richNotif && f.richNotif) MediaListenerService.refresh()
        if (old.localNormalize != f.localNormalize || old.localFade != f.localFade) LocalPlayer.refreshFx()
    }

    /** Le widget stats n'apparaît dans la liste des widgets que s'il est activé. */
    private fun setStatsWidget(ctx: Context, on: Boolean) {
        runCatching {
            ctx.packageManager.setComponentEnabledSetting(
                ComponentName(ctx, HushStatsReceiver::class.java),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    /** Le widget carré n'apparaît dans la liste des widgets que s'il est activé. */
    private fun setSquareWidget(ctx: Context, on: Boolean) {
        runCatching {
            ctx.packageManager.setComponentEnabledSetting(
                ComponentName(ctx, HushSquareReceiver::class.java),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}

/** Sons que tu ne veux plus qu'on te propose (Mix du jour, humeurs, blind test, accueil). */
object Blocked {
    data class Item(val key: String, val title: String, val artist: String)

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("hush", Context.MODE_PRIVATE)
        prefs = p
        _items.value = runCatching {
            val a = org.json.JSONArray(p.getString("blocked", "[]"))
            (0 until a.length()).map { val o = a.getJSONObject(it); Item(o.getString("k"), o.optString("t"), o.optString("a")) }
        }.getOrDefault(emptyList())
    }

    fun isBlocked(title: String, artist: String): Boolean {
        val l = _items.value
        if (l.isEmpty()) return false
        val k = Transitions.keyOf(title, artist)
        return l.any { it.key == k }
    }

    fun block(title: String, artist: String) {
        val k = Transitions.keyOf(title, artist)
        if (_items.value.any { it.key == k }) return
        _items.value = listOf(Item(k, title, artist)) + _items.value
        save()
    }

    fun unblock(key: String) {
        _items.value = _items.value.filterNot { it.key == key }
        save()
    }

    private fun save() {
        val a = org.json.JSONArray()
        _items.value.forEach { a.put(org.json.JSONObject().put("k", it.key).put("t", it.title).put("a", it.artist)) }
        prefs?.edit()?.putString("blocked", a.toString())?.apply()
    }
}

/** Couleur d'accent de l'appli (vert par défaut, ou la couleur de la pochette). */
object Accent {
    /** Couleur tirée de la pochette en cours (mise à jour par l'écran principal). */
    @Volatile var cover: Long = 0xFF5BE3A2

    val presets = listOf(
        "violet" to 0xFFB9A6FF, "green" to 0xFF5BE3A2, "blue" to 0xFF60A5FA,
        "pink" to 0xFFF472B6, "orange" to 0xFFFB923C, "cover" to 0L,
        // couleurs à débloquer avec les récompenses
        "gold" to 0xFFE8C48A, "neon" to 0xFF39FFB4, "ruby" to 0xFFFF4D6D,
    )

    fun current(): Long {
        val a = Features.flags.value.accent
        // style Pochette : la couleur par défaut devient celle de la pochette en cours
        if (a == "cover" || (a == "violet" && Features.flags.value.flatStyle)) return cover
        return presets.firstOrNull { it.first == a }?.second ?: 0xFFB9A6FF
    }
}

/** Anti-répétition : sons déjà passés dans les 2 dernières heures. */
object AntiRepeat {
    fun recentKeys(): Set<String> {
        val since = System.currentTimeMillis() - 2 * 3600_000L
        return History.entries.value.filter { it.lastPlayed >= since }.map { Transitions.keyOf(it.title, it.artist) }.toSet()
    }

    /** Enlève les sons trop récents (sauf s'il n'en resterait presque plus). */
    fun <T> filter(list: List<T>, title: (T) -> String, artist: (T) -> String): List<T> {
        if (!Features.flags.value.antiRepeat) return list
        val recent = recentKeys()
        if (recent.isEmpty()) return list
        val kept = list.filterNot { Transitions.keyOf(title(it), artist(it)) in recent }
        return if (kept.size >= 5 || kept.size >= list.size / 2) kept else list
    }
}

/** Reprendre où j'en étais : on retient le dernier son et sa position. */
object LastPlay {
    data class Spot(val title: String, val artist: String, val posMs: Long, val at: Long)

    private var prefs: android.content.SharedPreferences? = null
    private var lastSaved = 0L

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("hush", Context.MODE_PRIVATE)
    }

    /** Appelé souvent pendant la lecture : on n'écrit que toutes les 10 s. */
    fun track(st: TidalRemote.State) {
        val p = prefs ?: return
        val t = st.track ?: return
        if (!st.isPlaying || t.durationMs <= 0) return
        val now = System.currentTimeMillis()
        if (now - lastSaved < 10_000) return
        lastSaved = now
        p.edit().putString("lpTitle", t.title).putString("lpArtist", t.artist)
            .putLong("lpPos", st.livePosition()).putLong("lpAt", now).apply()
    }

    fun get(): Spot? {
        val p = prefs ?: return null
        val t = p.getString("lpTitle", null) ?: return null
        val at = p.getLong("lpAt", 0)
        if (System.currentTimeMillis() - at > 3 * 24 * 3600_000L) return null
        return Spot(t, p.getString("lpArtist", "").orEmpty(), p.getLong("lpPos", 0), at)
    }

    fun dismiss() { prefs?.edit()?.remove("lpTitle")?.apply() }

    /** Relance le son et saute à l'endroit où tu t'étais arrêté. */
    suspend fun resume(context: Context, spot: Spot) {
        fun k(t: String, a: String) = Transitions.keyOf(t, a)
        val st = TidalRemote.state.value
        val same = st.track?.let { k(it.title, it.artist) == k(spot.title, spot.artist) } == true
        if (!same) {
            TidalRemote.playTitle(spot.title, spot.artist)
            var waited = 0
            while (waited < 8000) {
                kotlinx.coroutines.delay(250); waited += 250
                val t = TidalRemote.state.value.track
                if (t != null && k(t.title, t.artist) == k(spot.title, spot.artist)) break
            }
            kotlinx.coroutines.delay(400)
        } else if (!st.isPlaying) {
            TidalRemote.resume(context)
        }
        TidalRemote.seekTo((spot.posMs - 3000).coerceAtLeast(0))
        dismiss()
    }
}
