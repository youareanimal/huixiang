package io.github.korppujauho.pocketts

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * The push-to-talk button over other apps: while connected in push-to-talk mode and the app isn't on screen (or is
 * only the small stream window), a round button stays where you put it. Hold it to talk; drag it to move it.
 * Android asks once for "Display over other apps" (Settings > General).
 */
object TalkOverlay {
    private lateinit var app: Context
    private var button: TalkButton? = null
    private var started = false

    /** Follows the connection and the settings from now on (main thread). */
    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        MainScope().launch { Session.state.collect { refresh() } }
    }

    fun allowed(context: Context) = android.provider.Settings.canDrawOverlays(context)

    /** Shows, updates or hides the button (main thread). */
    fun refresh() {
        if (!started) return
        val s = Session.state.value
        val want = s.voice.overlayPtt && s.voice.activation == VoiceSettings.Activation.PTT &&
            s.phase == Session.Phase.CONNECTED && (!Session.foreground || Session.pip) && allowed(app)
        val b = button
        if (!want) {
            if (b != null) {
                button = null
                if (b.held) Session.setPtt(false)
                try { wm().removeView(b) } catch (_: Exception) {}
            }
            return
        }
        if (b == null) show() else b.held = s.pttHeld
    }

    private fun wm() = app.getSystemService(WindowManager::class.java)

    private fun show() {
        val size = (60 * app.resources.displayMetrics.density).toInt()
        val prefs = app.getSharedPreferences("overlay", Context.MODE_PRIVATE)
        val m = app.resources.displayMetrics
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", m.widthPixels - size - size / 3)
            y = prefs.getInt("y", m.heightPixels * 2 / 3)
        }
        val b = TalkButton(app, params)
        try {
            wm().addView(b, params)
            button = b
        } catch (e: Exception) {
            AppLog.log("Talk button over other apps not shown: $e")
        }
    }

    @SuppressLint("ViewConstructor")
    private class TalkButton(context: Context, val params: WindowManager.LayoutParams) : View(context) {
        var held = false
            set(v) {
                if (field != v) {
                    field = v
                    invalidate()
                }
            }
        private val density = context.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        private val icon = context.getDrawable(R.drawable.ic_talk)!!.mutate()

        // Same colours as the floating button in the app
        override fun onDraw(c: Canvas) {
            val r = width / 2f
            fill.color = if (held) 0xEB0E7490.toInt() else 0xE01E1F22.toInt()
            c.drawCircle(r, r, r - ring.strokeWidth, fill)
            ring.color = if (held) 0xFF38BDF8.toInt() else 0x40FFFFFF
            c.drawCircle(r, r, r - ring.strokeWidth, ring)
            val i = (14 * density).toInt()
            icon.setTint(if (held) 0xFFE0F2FE.toInt() else 0xFFDBDEE1.toInt())
            icon.setBounds(width / 2 - i, height / 2 - i, width / 2 + i, height / 2 + i)
            icon.draw(c)
        }

        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                    Session.setPtt(true)
                    held = true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    // Moved clearly: it's a drag, not talking
                    if (!dragging && Math.hypot(dx.toDouble(), dy.toDouble()) > 24 * density) {
                        dragging = true
                        Session.setPtt(false)
                        held = false
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        try { context.getSystemService(WindowManager::class.java).updateViewLayout(this, params) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        context.getSharedPreferences("overlay", Context.MODE_PRIVATE).edit()
                            .putInt("x", params.x).putInt("y", params.y).apply()
                    } else {
                        Session.setPtt(false)
                    }
                    held = false
                    dragging = false
                }
            }
            return true
        }
    }
}
