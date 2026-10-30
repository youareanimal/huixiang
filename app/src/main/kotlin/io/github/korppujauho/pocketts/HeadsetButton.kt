package io.github.korppujauho.pocketts

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * The headset's button as push-to-talk (Settings > General): one press starts talking, the next press stops. It
 * works with the screen off and in other apps, through a media session that's there only while connected in
 * push-to-talk mode with the option on; music apps get the button back as soon as it's gone.
 */
object HeadsetButton {
    private lateinit var app: Context
    private var session: MediaSession? = null
    private var started = false

    /** Follows the connection and the settings from now on (main thread). */
    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        MainScope().launch { Session.state.collect { refresh() } }
    }

    private fun refresh() {
        val s = Session.state.value
        val want = s.voice.headsetPtt && s.voice.activation == VoiceSettings.Activation.PTT && s.phase == Session.Phase.CONNECTED
        if (want == (session != null)) return
        if (want) open() else close()
    }

    private fun open() {
        val s = MediaSession(app, "PocketTS push-to-talk")
        s.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(intent: Intent): Boolean {
                @Suppress("DEPRECATION")
                val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                if (key.keyCode !in BUTTONS) return false
                // Talking toggles on the press; holding the button (wired headsets repeat it) changes nothing
                if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) Session.setPtt(!Session.state.value.pttHeld)
                return true
            }
        })
        // Android sends the button to the session that's playing
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build()
        )
        s.isActive = true
        session = s
        AppLog.log("Headset button: push-to-talk on")
    }

    private fun close() {
        val s = session ?: return
        session = null
        if (Session.state.value.pttHeld) Session.setPtt(false)
        s.isActive = false
        s.release()
        AppLog.log("Headset button: push-to-talk off")
    }

    private val BUTTONS = setOf(
        KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
    )
}
