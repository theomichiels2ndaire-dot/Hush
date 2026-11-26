package com.theo.remote

import android.content.Context
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * Chargement des pochettes : gardées en mémoire et sur le téléphone (elles s'affichent même
 * sans réseau), décodées en format léger pour que les longues listes défilent sans à-coups.
 */
object Images {
    @Volatile private var installed = false
    private var app: Context? = null

    /** Données mobiles (réseau facturé) avec l'option activée : on charge plus léger. */
    fun saveData(): Boolean {
        if (!Features.flags.value.dataSaver) return false
        val cm = app?.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        return runCatching { cm.isActiveNetworkMetered }.getOrDefault(false)
    }

    /** Petite pochette sur données mobiles : la version 160 px suffit largement pour une vignette. */
    private val smallCovers = coil.intercept.Interceptor { chain ->
        val req = chain.request
        val data = req.data as? String
        val w = (chain.size.width as? coil.size.Dimension.Pixels)?.px ?: Int.MAX_VALUE
        if (data != null && data.contains("resources.tidal.com/images/") && data.contains("320x320") && w <= 200 && saveData()) {
            chain.proceed(req.newBuilder().data(data.replace("320x320", "160x160")).build())
        } else chain.proceed(req)
    }

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val ctx = context.applicationContext
            app = ctx
            Coil.setImageLoader(
                ImageLoader.Builder(ctx)
                    .memoryCache { MemoryCache.Builder(ctx).maxSizePercent(0.25).build() }
                    .diskCache { DiskCache.Builder().directory(ctx.cacheDir.resolve("covers_cache")).maxSizeBytes(300L * 1024 * 1024).build() }
                    // les pochettes Tidal ne changent jamais : pas besoin de redemander au serveur
                    .respectCacheHeaders(false)
                    // moitié moins de mémoire par pochette (pas de transparence dans une pochette)
                    .allowRgb565(true)
                    .crossfade(120)
                    .components { add(smallCovers) }
                    .build(),
            )
            installed = true
        }
    }
}
