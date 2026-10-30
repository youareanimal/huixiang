package io.github.korppujauho.pocketts

import android.content.Context

/** How talking works, as in WebTS's Settings: activation, levels and the microphone's processing. */
data class VoiceSettings(
    val activation: Activation = Activation.PTT,
    val vadThreshold: Float = 0.01f, // voice activation level: the microphone's RMS, 0 - 0.1
    val vadTailMs: Int = 600, // delay before deactivation
    val voiceVolumeDb: Int = 0, // everyone's voice, -20 to +12 dB
    val micVolume: Float = 1f, // the microphone, 0 - 3
    val noiseSuppression: Boolean = true,
    val echoCancellation: Boolean = true,
    val autoGainControl: Boolean = false,
    val floatingPtt: Boolean = true, // the round push-to-talk button
    val volumeKeysPtt: Boolean = false, // volume buttons talk while held (app on screen)
    val headsetPtt: Boolean = false, // the headset's button starts and stops talking
    val muteOnCalls: Boolean = true, // the microphone and sound go off while a phone call rings or goes on
    val joinSpacers: Boolean = false, // tapping a spacer channel joins it (some servers' AFK channel is one)
    val keepScreenOn: Boolean = false, // the screen stays on while connected (always while sharing or watching a stream)
    val checkUpdates: Boolean = true, // the GitHub version asks GitHub once a day whether a newer one is out
    val turnUrl: String = "", // a relay (TURN) server for streams, e.g. turn:relay.example.com:3478
    val turnUser: String = "",
    val turnPassword: String = "",
    val relayOnly: Boolean = false, // streams only through the relay
    val cameraRotateLock: Boolean = false, // the camera stream's turn is locked (else it follows how the phone is held)
    val cameraSound: Boolean = false, // the camera stream carries what the microphone hears
    val screenSound: Boolean = true, // the screen share carries what the phone plays
    val overlayPtt: Boolean = false, // the push-to-talk button stays on screen over other apps
    val uiSounds: Boolean = true, // sounds for events (people coming and going, messages, pokes, streams, the buttons)
    val uiSoundVolume: Float = 0.6f, // their volume, 0 - 1
    val adminTools: Boolean = false, // the person menu offers move, kick and ban (the server decides who may)
    val controlsAtBottom: Boolean = true, // the buttons and the Channels/Chat switch at the bottom of the screen (within thumb reach)
) {
    enum class Activation { VAD, PTT, CONTINUOUS }

    val voiceGain: Float get() = Math.pow(10.0, voiceVolumeDb / 20.0).toFloat()

    companion object {
        private const val TURN_PASSWORD = "voice/turnPassword"

        fun load(context: Context): VoiceSettings {
            val p = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
            val d = VoiceSettings()
            return VoiceSettings(
                activation = p.getString("activation", null)?.let { runCatching { Activation.valueOf(it) }.getOrNull() } ?: d.activation,
                vadThreshold = p.getFloat("vadThreshold", d.vadThreshold),
                vadTailMs = p.getInt("vadTailMs", d.vadTailMs),
                voiceVolumeDb = p.getInt("voiceVolumeDb", d.voiceVolumeDb),
                micVolume = p.getFloat("micVolume", d.micVolume),
                noiseSuppression = p.getBoolean("noiseSuppression", d.noiseSuppression),
                echoCancellation = p.getBoolean("echoCancellation", d.echoCancellation),
                autoGainControl = p.getBoolean("autoGainControl", d.autoGainControl),
                floatingPtt = p.getBoolean("floatingPtt", d.floatingPtt),
                volumeKeysPtt = p.getBoolean("volumeKeysPtt", d.volumeKeysPtt),
                headsetPtt = p.getBoolean("headsetPtt", d.headsetPtt),
                muteOnCalls = p.getBoolean("muteOnCalls", d.muteOnCalls),
                joinSpacers = p.getBoolean("joinSpacers", d.joinSpacers),
                keepScreenOn = p.getBoolean("keepScreenOn", d.keepScreenOn),
                checkUpdates = p.getBoolean("checkUpdates", d.checkUpdates),
                turnUrl = p.getString("turnUrl", d.turnUrl).orEmpty(),
                turnUser = p.getString("turnUser", d.turnUser).orEmpty(),
                turnPassword = try {
                    secretPrefs(context).getSecret("turnPassword", TURN_PASSWORD, old = p) ?: d.turnPassword
                } catch (e: SecretBox.Unreadable) {
                    AppLog.log("The relay password can't be read: ${e.message}")
                    d.turnPassword
                },
                relayOnly = p.getBoolean("relayOnly", d.relayOnly),
                cameraRotateLock = p.getBoolean("cameraRotateLock", d.cameraRotateLock),
                cameraSound = p.getBoolean("cameraSound", d.cameraSound),
                screenSound = p.getBoolean("screenSound", d.screenSound),
                overlayPtt = p.getBoolean("overlayPtt", d.overlayPtt),
                uiSounds = p.getBoolean("uiSounds", d.uiSounds),
                uiSoundVolume = p.getFloat("uiSoundVolume", d.uiSoundVolume),
                adminTools = p.getBoolean("adminTools", d.adminTools),
                controlsAtBottom = p.getBoolean("controlsAtBottom", d.controlsAtBottom),
            )
        }
    }

    fun save(context: Context) {
        context.getSharedPreferences("voice", Context.MODE_PRIVATE).edit()
            .putString("activation", activation.name)
            .putFloat("vadThreshold", vadThreshold)
            .putInt("vadTailMs", vadTailMs)
            .putInt("voiceVolumeDb", voiceVolumeDb)
            .putFloat("micVolume", micVolume)
            .putBoolean("noiseSuppression", noiseSuppression)
            .putBoolean("echoCancellation", echoCancellation)
            .putBoolean("autoGainControl", autoGainControl)
            .putBoolean("floatingPtt", floatingPtt)
            .putBoolean("volumeKeysPtt", volumeKeysPtt)
            .putBoolean("headsetPtt", headsetPtt)
            .putBoolean("muteOnCalls", muteOnCalls)
            .putBoolean("joinSpacers", joinSpacers)
            .putBoolean("keepScreenOn", keepScreenOn)
            .putBoolean("checkUpdates", checkUpdates)
            .putString("turnUrl", turnUrl.trim())
            .putString("turnUser", turnUser.trim())
            .remove("turnPassword") // kept with the other secrets (see secretPrefs)
            .putBoolean("relayOnly", relayOnly)
            .putBoolean("cameraRotateLock", cameraRotateLock)
            .putBoolean("cameraSound", cameraSound)
            .putBoolean("screenSound", screenSound)
            .putBoolean("overlayPtt", overlayPtt)
            .putBoolean("uiSounds", uiSounds)
            .putFloat("uiSoundVolume", uiSoundVolume)
            .putBoolean("adminTools", adminTools)
            .putBoolean("controlsAtBottom", controlsAtBottom)
            .apply()
        secretPrefs(context).edit().putSecret("turnPassword", TURN_PASSWORD, turnPassword).apply()
    }
}
