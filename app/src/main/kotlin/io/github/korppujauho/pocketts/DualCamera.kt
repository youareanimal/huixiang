package io.github.korppujauho.pocketts

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.CapturerObserver
import org.webrtc.EglBase
import org.webrtc.GlRectDrawer
import org.webrtc.GlTextureFrameBuffer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.ThreadUtils
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoFrameDrawer

/**
 * Both cameras at once: one camera's picture big, the other's small in the top right corner, drawn together into
 * the one picture a stream carries. [frontBig] swaps them. Only on phones that can run both cameras at the same
 * time ([pair]). Each picture is turned by [turn] (see [CameraTurn]) before it's drawn, so both stay upright.
 */
class DualCamera(
    private val context: Context,
    private val shared: EglBase.Context,
    private val ids: Pair<String, String>, // back, front
    private val turn: (front: Boolean) -> Int,
) : VideoCapturer {

    companion object {
        /** A back and a front camera this phone can run at the same time, or null when it can't. */
        fun pair(context: Context): Pair<String, String>? {
            if (Build.VERSION.SDK_INT < 30) return null
            val cm = context.getSystemService(CameraManager::class.java) ?: return null
            fun facing(id: String) = try { cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) } catch (_: Exception) { null }
            return try {
                cm.concurrentCameraIds.firstNotNullOfOrNull { set ->
                    val back = set.firstOrNull { facing(it) == CameraCharacteristics.LENS_FACING_BACK }
                    val front = set.firstOrNull { facing(it) == CameraCharacteristics.LENS_FACING_FRONT }
                    if (back != null && front != null) back to front else null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    @Volatile var frontBig = false
        set(value) {
            field = value
            handler.post { hasSmall = false } // the old small picture is the wrong camera now
        }

    private lateinit var out: SurfaceTextureHelper
    private lateinit var observer: CapturerObserver
    private val thread = HandlerThread("dual-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val cameras = ArrayList<Pair<CameraVideoCapturer, SurfaceTextureHelper>>()

    // Used on our thread only
    private var running = false
    private var egl: EglBase? = null
    private var drawer: GlRectDrawer? = null
    private val frameDrawer = VideoFrameDrawer()
    private val identity = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
    private var small: GlTextureFrameBuffer? = null
    private var hasSmall = false
    private var outW = 0
    private var outH = 0

    override fun initialize(helper: SurfaceTextureHelper, ctx: Context, obs: CapturerObserver) {
        out = helper
        observer = obs
        out.startListening { frame -> observer.onFrameCaptured(frame) }
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        ThreadUtils.invokeAtFrontUninterruptibly(handler) {
            out.surfaceTexture.setDefaultBufferSize(height, width)
            val e = EglBase.create(shared, EglBase.CONFIG_PLAIN)
            e.createSurface(out.surfaceTexture)
            e.makeCurrent()
            egl = e
            drawer = GlRectDrawer()
            running = true
        }
        val cams = Camera2Enumerator(context)
        for ((id, front) in listOf(ids.first to false, ids.second to true)) {
            val helper = SurfaceTextureHelper.create(if (front) "front-camera" else "back-camera", shared)
            val cam = cams.createCapturer(id, null)
            cam.initialize(helper, context.applicationContext, object : CapturerObserver {
                override fun onCapturerStarted(ok: Boolean) {
                    if (!ok) AppLog.log("[Streams] The ${if (front) "front" else "back"} camera didn't start")
                }
                override fun onCapturerStopped() {}
                override fun onFrameCaptured(frame: VideoFrame) {
                    // Kept until drawn: the camera waits for it (so a slow moment drops pictures, not memory)
                    frame.retain()
                    if (!handler.post { draw(frame, front) }) frame.release()
                }
            })
            // The small one needs less
            if (front == frontBig) cam.startCapture(width, height, framerate) else cam.startCapture(640, 480, framerate)
            cameras.add(cam to helper)
        }
        observer.onCapturerStarted(true)
    }

    private fun draw(frame: VideoFrame, front: Boolean) {
        try {
            if (!running) return
            val turned = VideoFrame(frame.buffer, (frame.rotation + turn(front)) % 360, frame.timestampNs)
            if (front == frontBig) drawAll(turned) else drawSmall(turned)
        } catch (e: Exception) {
            AppLog.log("[Streams] Drawing both cameras: $e")
        } finally {
            frame.release()
        }
    }

    // The small picture is copied into a texture of our own, so its camera can go on
    private fun drawSmall(f: VideoFrame) {
        val scale = minOf(1.0, 480.0 / maxOf(f.rotatedWidth, f.rotatedHeight))
        val w = (f.rotatedWidth * scale).toInt() / 2 * 2
        val h = (f.rotatedHeight * scale).toInt() / 2 * 2
        val fb = small ?: GlTextureFrameBuffer(GLES20.GL_RGBA).also { small = it }
        fb.setSize(w, h)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb.frameBufferId)
        frameDrawer.drawFrame(f, drawer, null, 0, 0, w, h)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        hasSmall = true
    }

    private fun drawAll(f: VideoFrame) {
        val e = egl ?: return
        val w = f.rotatedWidth / 2 * 2
        val h = f.rotatedHeight / 2 * 2
        if (w != outW || h != outH) {
            // A new shape (the picture turned): the output surface is made again at the new size
            outW = w
            outH = h
            out.surfaceTexture.setDefaultBufferSize(w, h)
            out.setTextureSize(w, h)
            e.releaseSurface()
            e.createSurface(out.surfaceTexture)
            e.makeCurrent()
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        frameDrawer.drawFrame(f, drawer, null, 0, 0, w, h)
        val fb = small
        if (hasSmall && fb != null && fb.width > 0) {
            // A third of the shorter side, in the top right corner with a thin light edge
            val side = minOf(w, h)
            val sw = (side * 0.32).toInt()
            val sh = sw * fb.height / fb.width
            val margin = (side * 0.03).toInt()
            val x = w - sw - margin
            val y = h - sh - margin // GL counts from the bottom
            val edge = maxOf(2, side / 240)
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            GLES20.glScissor(x - edge, y - edge, sw + 2 * edge, sh + 2 * edge)
            GLES20.glClearColor(1f, 1f, 1f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            drawer?.drawRgb(fb.textureId, identity, fb.width, fb.height, x, y, sw, sh)
        }
        e.swapBuffers(f.timestampNs)
    }

    override fun stopCapture() {
        for ((cam, _) in cameras) {
            try { cam.stopCapture() } catch (_: Exception) {}
        }
        ThreadUtils.invokeAtFrontUninterruptibly(handler) {
            running = false
            small?.release(); small = null
            drawer?.release(); drawer = null
            egl?.release(); egl = null
            outW = 0
            outH = 0
        }
        for ((cam, helper) in cameras) {
            cam.dispose()
            helper.dispose()
        }
        cameras.clear()
        out.stopListening()
        observer.onCapturerStopped()
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {}

    override fun dispose() {
        thread.quitSafely()
    }

    override fun isScreencast() = false
}
