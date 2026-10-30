package io.github.korppujauho.pocketts

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The app's log. Test builds also send it to the development machine (BuildConfig.DEV_LOG_URL), so problems on a
 * phone can be seen there; release builds don't.
 */
object AppLog {
    private const val TAG = "PocketTS"
    private val queue = LinkedBlockingQueue<String>(5000)
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
    @Volatile private var started = false

    fun start(context: Context) {
        val url = BuildConfig.DEV_LOG_URL
        if (started || url.isEmpty()) return
        started = true
        Thread({ sendLoop("$url/log") }, "log-sender").apply { isDaemon = true }.start()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            log("CRASH in ${t.name}: ${Log.getStackTraceString(e)}")
            flushNow("$url/log")
            previous?.uncaughtException(t, e)
        }
        log("Started ${BuildConfig.VERSION_NAME} on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        lastStops(context)
    }

    // Why the app stopped the last times, as Android recorded it: a crash in native code (WebRTC's) or Android
    // stopping the app leaves no line of its own
    private fun lastStops(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val stops = try {
            context.getSystemService(ActivityManager::class.java)?.getHistoricalProcessExitReasons(null, 0, 5)
        } catch (_: Exception) {
            null
        } ?: return
        for (s in stops) {
            val at = synchronized(time) { time.format(Date(s.timestamp)) }
            log("Stopped before at $at: reason ${s.reason}, ${s.description.orEmpty()} (status ${s.status}, importance ${s.importance}, ${s.pss / 1024} MB)")
            val trace = when (s.reason) {
                ApplicationExitInfo.REASON_ANR -> readTrace(s)?.toString(Charsets.UTF_8)?.lineSequence()?.take(60)?.toList()
                // A tombstone, in a binary format: its readable parts (the stack's functions and libraries) are enough
                ApplicationExitInfo.REASON_CRASH_NATIVE -> readTrace(s)?.let { bytes ->
                    Regex("[ -~]{8,}").findAll(String(bytes, Charsets.ISO_8859_1)).map { it.value }.take(150).toList()
                }
                else -> null
            }
            trace?.let { log("  " + it.joinToString("\n  ")) }
        }
    }

    @RequiresApi(30)
    private fun readTrace(s: ApplicationExitInfo): ByteArray? = try {
        s.traceInputStream?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (out.size() < 512 * 1024) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
    } catch (_: Exception) {
        null
    }

    fun log(msg: String) {
        Log.i(TAG, msg)
        if (started) {
            val line = synchronized(time) { time.format(Date()) } + " " + msg
            queue.offer(line)
        }
    }

    private fun sendLoop(url: String) {
        while (true) {
            val first = queue.poll(2, TimeUnit.SECONDS) ?: continue
            val lines = mutableListOf(first)
            queue.drainTo(lines, 500)
            post(url, lines)
        }
    }

    private fun flushNow(url: String) {
        val lines = mutableListOf<String>()
        queue.drainTo(lines)
        if (lines.isNotEmpty()) post(url, lines)
    }

    private fun post(url: String, lines: List<String>) {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 3000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            c.outputStream.use { it.write(lines.joinToString("\n", postfix = "\n").toByteArray()) }
            c.responseCode
            c.disconnect()
        } catch (_: Exception) {
            // The development machine isn't reachable: these lines are only in the phone's own log.
        }
    }
}
