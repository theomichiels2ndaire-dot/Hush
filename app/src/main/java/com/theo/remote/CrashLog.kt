package com.theo.remote

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Journal des plantages : garde les 5 derniers, pour pouvoir les envoyer et trouver la cause. */
object CrashLog {
    data class Crash(val at: Long, val text: String) {
        val title get() = text.lineSequence().firstOrNull()?.take(140).orEmpty()
    }

    private var file: File? = null
    private const val SEP = "\n=====HUSH-CRASH=====\n"

    fun install(context: Context) {
        if (file != null) return
        file = File(context.applicationContext.filesDir, "crashes.log")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { record(thread.name, e) }
            previous?.uncaughtException(thread, e)
        }
    }

    private fun record(thread: String, e: Throwable) {
        val f = file ?: return
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val entry = "${System.currentTimeMillis()}\n${e.javaClass.simpleName}: ${e.message}\nfil : $thread\n" +
            "version Android : ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}\n\n" + sw.toString().take(6000)
        val old = list().take(4).map { "${it.at}\n${it.text}" }
        f.writeText((listOf(entry) + old).joinToString(SEP))
    }

    fun list(): List<Crash> {
        val f = file ?: return emptyList()
        if (!f.exists()) return emptyList()
        return runCatching {
            f.readText().split(SEP).filter { it.isNotBlank() }.mapNotNull { block ->
                val nl = block.indexOf('\n')
                if (nl < 0) null else Crash(block.substring(0, nl).trim().toLongOrNull() ?: 0L, block.substring(nl + 1))
            }
        }.getOrDefault(emptyList())
    }

    fun clear() { file?.delete() }

    fun format(c: Crash): String =
        "Plantage Hush du " + SimpleDateFormat("d MMM yyyy à HH:mm:ss", Locale.FRANCE).format(Date(c.at)) + "\n" + c.text
}
