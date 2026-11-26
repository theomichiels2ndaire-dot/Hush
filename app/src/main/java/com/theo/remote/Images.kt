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

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val ctx = context.applicationContext
            Coil.setImageLoader(
                ImageLoader.Builder(ctx)
                    .memoryCache { MemoryCache.Builder(ctx).maxSizePercent(0.25).build() }
                    .diskCache { DiskCache.Builder().directory(ctx.cacheDir.resolve("covers_cache")).maxSizeBytes(300L * 1024 * 1024).build() }
                    // les pochettes Tidal ne changent jamais : pas besoin de redemander au serveur
                    .respectCacheHeaders(false)
                    // moitié moins de mémoire par pochette (pas de transparence dans une pochette)
                    .allowRgb565(true)
                    .crossfade(120)
                    .build(),
            )
            installed = true
        }
    }
}
