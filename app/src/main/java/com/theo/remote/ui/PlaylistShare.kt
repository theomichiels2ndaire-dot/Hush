package com.theo.remote.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.theo.remote.TidalApi
import com.theo.remote.TidalRemote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Une playlist en image (format story) : mosaïque des pochettes, nom et premiers sons. */
object PlaylistShare {

    private suspend fun bitmap(ctx: Context, url: String?, size: Int): Bitmap? {
        if (url == null) return null
        return runCatching {
            val req = coil.request.ImageRequest.Builder(ctx).data(url).size(size).allowHardware(false).build()
            (coil.Coil.imageLoader(ctx).execute(req).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        }.getOrNull()
    }

    suspend fun share(ctx: Context, name: String, tracks: List<TidalApi.Result>, coverUrl: String?) {
        if (tracks.isEmpty()) { TidalRemote.showToast("La playlist est encore en chargement"); return }
        TidalRemote.showToast("Image en préparation…")
        val w = 1080; val h = 1920
        val urls = tracks.mapNotNull { it.coverUrl }.distinct().take(4)
        val main = bitmap(ctx, coverUrl, 900)
        val tiles = if (main == null) urls.map { bitmap(ctx, it, 450) } else emptyList()

        // couleur de fond tirée de la pochette, assombrie (style Pochette : un aplat)
        val ref = main ?: tiles.firstOrNull()
        val base = ref?.let { b ->
            val pal = androidx.palette.graphics.Palette.from(b).generate()
            pal.getDarkVibrantColor(pal.getDominantColor(0xFF1C1C1C.toInt()))
        } ?: 0xFF1C1C1C.toInt()
        val bg = androidx.core.graphics.ColorUtils.blendARGB(base, 0xFF000000.toInt(), 0.45f)

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(bg)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // pochette ou mosaïque 2×2
        val side = 760
        val left = (w - side) / 2f
        val top = 200f
        val box = RectF(left, top, left + side, top + side)
        c.save()
        val clip = android.graphics.Path().apply { addRoundRect(box, 28f, 28f, android.graphics.Path.Direction.CW) }
        c.clipPath(clip)
        if (main != null) {
            c.drawBitmap(main, null, box, p)
        } else {
            val half = side / 2f
            for (i in 0 until 4) {
                val b = tiles.getOrNull(i) ?: tiles.firstOrNull() ?: continue
                val r = RectF(left + (i % 2) * half, top + (i / 2) * half, left + (i % 2 + 1) * half, top + (i / 2 + 1) * half)
                c.drawBitmap(b, null, r, p)
            }
        }
        c.restore()

        // nom et nombre de sons
        val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2F0EA.toInt(); textSize = 76f; typeface = bold }
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99F2F0EA.toInt(); textSize = 38f }
        val row = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2F0EA.toInt(); textSize = 42f; typeface = bold }
        val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x80F2F0EA.toInt(); textSize = 42f; typeface = bold }
        var y = top + side + 120f
        c.drawText(fit(name, title, w - 160f), 80f, y, title)
        y += 60f
        c.drawText("${tracks.size} son${if (tracks.size > 1) "s" else ""}", 80f, y, sub)
        y += 50f
        // filet
        c.drawRect(Rect(80, y.toInt(), w - 80, y.toInt() + 2), Paint().apply { color = 0x26FFFFFF })
        y += 80f
        tracks.take(7).forEachIndexed { i, t ->
            c.drawText("${i + 1}", 80f, y, num)
            c.drawText(fit(t.fullTitle, row, w - 260f), 150f, y, row)
            c.drawText(fit(t.artistLine, sub, w - 260f), 150f, y + 44f, sub)
            y += 112f
        }
        if (tracks.size > 7) c.drawText("et ${tracks.size - 7} autres", 150f, y, sub)
        c.drawText("Hush", w - 80f - sub.measureText("Hush"), h - 80f, sub)

        val file = withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
            File(dir, "hush-playlist.png").also { f -> f.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".share", file)
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Partager la playlist").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Coupe le texte avec « … » s'il dépasse. */
    private fun fit(text: String, paint: Paint, max: Float): String {
        if (paint.measureText(text) <= max) return text
        var t = text
        while (t.isNotEmpty() && paint.measureText("$t…") > max) t = t.dropLast(1)
        return "$t…"
    }
}
