package io.github.korppujauho.pocketts

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.Display
import android.view.OrientationEventListener
import org.webrtc.CapturerObserver
import org.webrtc.VideoFrame

/**
 * Keeps the camera's picture upright for viewers, as WebTS does. With the phone's auto-rotate off the screen stays
 * upright however the phone is held, and so did the camera's picture: filming sideways sent a picture lying on its
 * side. Following the phone (the default), the motion sensor says how it's held and the picture is turned to match;
 * when the screen turns with the phone, the camera already did it and nothing more is done. Locked keeps the current
 * turn (the phone lying on a table, or filming sideways on purpose).
 */
class CameraTurn(context: Context) {
    private val display: Display? = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)

    @Volatile private var phone = 0 // how the phone is held, clockwise from upright: 0, 90, 180 or 270
    @Volatile private var lockedTurn = 0
    @Volatile var locked = false
        private set

    /** Called (on the main thread) when the way the phone is held changes. */
    @Volatile var onTurn: ((Int) -> Unit)? = null

    private var seen = -1
    private var seenAt = 0L

    // Held still for 0.4 s before a new way counts; lying flat keeps the last one
    private val sensor = object : OrientationEventListener(context.applicationContext) {
        override fun onOrientationChanged(deg: Int) {
            if (deg == ORIENTATION_UNKNOWN) return
            val nearest = ((deg + 45) / 90 * 90) % 360
            val off = Math.abs(((deg - nearest + 540) % 360) - 180)
            if (off > 30 || nearest == phone) { seen = -1; return } // between two ways: no change
            val now = SystemClock.elapsedRealtime()
            if (seen != nearest) { seen = nearest; seenAt = now; return }
            if (now - seenAt < 400) return
            phone = nearest
            seen = -1
            onTurn?.invoke(nearest)
        }
    }

    fun start(lock: Boolean) {
        locked = false
        lock(lock)
        if (sensor.canDetectOrientation()) sensor.enable()
        onTurn?.invoke(phone)
    }

    fun stop() = sensor.disable()

    /** Locks the current turn, or goes back to following the phone. */
    fun lock(on: Boolean) {
        if (on) lockedTurn = current()
        locked = on
    }

    // The screen's own turn, which the camera already follows
    private fun screen() = (display?.rotation ?: 0) * 90

    private fun current() = (phone + screen()) % 360

    /** How much more a camera's picture must turn, clockwise. The front camera sees the world mirrored: the other way. */
    fun extra(front: Boolean): Int {
        val t = if (locked) lockedTurn else current()
        return if (front) (360 - t) % 360 else t
    }

    /** Passes a camera's pictures on, turned. */
    fun observer(target: CapturerObserver, front: () -> Boolean) = object : CapturerObserver {
        override fun onCapturerStarted(ok: Boolean) = target.onCapturerStarted(ok)
        override fun onCapturerStopped() = target.onCapturerStopped()
        override fun onFrameCaptured(frame: VideoFrame) {
            val extra = extra(front())
            // The same picture with a new turn: only the label changes (the caller still releases the original)
            if (extra == 0) target.onFrameCaptured(frame)
            else target.onFrameCaptured(VideoFrame(frame.buffer, (frame.rotation + extra) % 360, frame.timestampNs))
        }
    }
}
