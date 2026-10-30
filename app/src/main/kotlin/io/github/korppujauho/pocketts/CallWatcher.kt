package io.github.korppujauho.pocketts

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager

/**
 * Phone calls while connected, for [Session.onPhoneCall]: on Android 12 and later the phone's sound mode says a call
 * rings or goes on; before that, the call state. Neither needs a permission. A call in another voice app (sound mode
 * "communication", as PocketTS's own echo cancelling uses) doesn't count.
 *
 * Based on Stefan3Dx's version in his fork (see CREDITS.md).
 */
object CallWatcher {
    private val main = Handler(Looper.getMainLooper())
    private var modeListener: Any? = null
    @Suppress("DEPRECATION") // still the way before Android 12
    private var phoneListener: PhoneStateListener? = null

    private fun ringingOrCall(mode: Int) =
        mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_CALL_SCREENING

    fun start(context: Context, onCall: (Boolean) -> Unit) = main.post {
        val app = context.applicationContext
        if (modeListener != null || phoneListener != null) return@post
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val am = app.getSystemService(AudioManager::class.java)
                val l = AudioManager.OnModeChangedListener { mode -> onCall(ringingOrCall(mode)) }
                am.addOnModeChangedListener(app.mainExecutor, l)
                modeListener = l
                // Connecting during a call
                if (ringingOrCall(am.mode)) onCall(true)
            } else {
                val tm = app.getSystemService(TelephonyManager::class.java) ?: return@post
                @Suppress("DEPRECATION")
                val l = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) = onCall(state != TelephonyManager.CALL_STATE_IDLE)
                }
                // Made here, on the main thread: it answers on the thread it was made on
                @Suppress("DEPRECATION")
                tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
                phoneListener = l
            }
        } catch (e: Exception) {
            AppLog.log("Can't watch for phone calls: $e")
        }
    }

    fun stop(context: Context) = main.post {
        val app = context.applicationContext
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                (modeListener as? AudioManager.OnModeChangedListener)?.let { app.getSystemService(AudioManager::class.java).removeOnModeChangedListener(it) }
            } else {
                @Suppress("DEPRECATION")
                phoneListener?.let { app.getSystemService(TelephonyManager::class.java)?.listen(it, PhoneStateListener.LISTEN_NONE) }
            }
        } catch (_: Exception) {
        }
        modeListener = null
        phoneListener = null
    }
}
