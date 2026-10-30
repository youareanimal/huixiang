package io.github.korppujauho.pocketts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/** Settings > Playback's test sound: two short notes, played the way voices are (call audio or media, at the voice volume). */
object TestSound {
    fun play(v: VoiceSettings) {
        Thread {
            val rate = 48000
            val notes = listOf(660.0 to 0.18, 880.0 to 0.28)
            val total = notes.sumOf { (it.second * rate).toInt() }
            val pcm = ShortArray(total)
            var pos = 0
            for ((freq, secs) in notes) {
                val n = (secs * rate).toInt()
                for (i in 0 until n) {
                    val fade = minOf(1.0, i / 480.0, (n - i) / 480.0) // no clicks
                    pcm[pos++] = (sin(2 * PI * freq * i / rate) * 0.3 * fade * v.voiceGain * Short.MAX_VALUE)
                        .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(if (v.echoCancellation) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            try {
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep((total * 1000L / rate) + 200)
            } finally {
                track.release()
            }
        }.start()
    }
}
