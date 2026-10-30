package io.github.korppujauho.pocketts

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window

/**
 * How smoothly the screen draws, in test builds: every 10 seconds with drawing, a line in the log with the frames,
 * how many were late (missed the screen's refresh) and the slowest. Release builds don't measure.
 */
object FrameLog {
    private const val EVERY_MS = 10_000L
    private var frames = 0
    private var late = 0
    private var frozen = 0 // over 700 ms, as Android counts them
    private var worstNs = 0L
    private var since = 0L
    private val handler by lazy { Handler(HandlerThread("frame-log").apply { start() }.looper) }

    /** For each new window (the activity is made again when the phone turns). */
    fun start(activity: Activity) {
        if (BuildConfig.DEV_LOG_URL.isEmpty()) return
        // The screen's refresh (Activity.display is Android 11+; Android 10 has only the older way)
        @Suppress("DEPRECATION")
        val hz = (if (android.os.Build.VERSION.SDK_INT >= 30) activity.display?.refreshRate else activity.windowManager.defaultDisplay.refreshRate) ?: 60f
        val refreshNs = (1_000_000_000 / hz).toLong()
        activity.window.addOnFrameMetricsAvailableListener(Window.OnFrameMetricsAvailableListener { _, m, _ ->
            if (m.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
            val took = m.getMetric(FrameMetrics.TOTAL_DURATION)
            val deadline = if (android.os.Build.VERSION.SDK_INT >= 31) m.getMetric(FrameMetrics.DEADLINE) else refreshNs
            frames++
            if (took > deadline) late++
            if (took > 700_000_000L) frozen++
            if (took > worstNs) worstNs = took
            val now = System.currentTimeMillis()
            if (since == 0L) since = now
            if (now - since >= EVERY_MS) {
                AppLog.log("Frames: $frames, late $late (${late * 100 / frames} %), frozen $frozen, slowest ${worstNs / 1_000_000} ms")
                frames = 0; late = 0; frozen = 0; worstNs = 0; since = now
            }
        }, handler)
    }
}
