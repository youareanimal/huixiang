package io.github.korppujauho.pocketts

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Where voice goes with echo cancellation on: the phone's call audio (which the echo canceller needs), through a
 * headset when one is connected, else the loudspeaker (not the earpiece). With it off, voice plays like media.
 */
class AudioRoute(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private var on = false

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { if (on) pick() }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) { if (on) pick() }
    }

    fun start() {
        if (on) return
        on = true
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.registerAudioDeviceCallback(devices, Handler(Looper.getMainLooper()))
        pick()
    }

    @Suppress("DEPRECATION")
    fun stop() {
        if (!on) return
        on = false
        am.unregisterAudioDeviceCallback(devices)
        if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
        else am.isSpeakerphoneOn = false
        am.mode = AudioManager.MODE_NORMAL
    }

    private val preference = listOf(
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    )

    private fun pick() {
        if (Build.VERSION.SDK_INT >= 31) {
            val available = am.availableCommunicationDevices
            val best = preference.firstNotNullOfOrNull { type -> available.firstOrNull { it.type == type } } ?: return
            if (am.communicationDevice?.id != best.id) {
                am.setCommunicationDevice(best)
                AppLog.log("Voice goes to ${best.productName} (type ${best.type})")
            }
        } else {
            val headset = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type in listOf(
                    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_USB_HEADSET,
                )
            }
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = !headset
        }
    }
}
