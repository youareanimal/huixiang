package io.github.korppujauho.pocketts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.github.korppujauho.pocketts.proto.TsClient
import java.util.TreeMap

/**
 * Plays everyone talking: per person a small buffer that puts packets back in order and evens out network jitter,
 * an Opus decoder, and one mixer thread writing 20 ms at a time to the speaker.
 */
class VoicePlayer(
    /** Through the phone's call audio (for echo cancellation) rather than as media. */
    private val communication: Boolean,
    private val onTalking: (clientId: Int, talking: Boolean) -> Unit,
) {
    private companion object {
        const val RATE = 48000
        const val FRAME = 960 // 20 ms
        const val START_PACKETS = 3 // wait for 60 ms of voice before starting someone (jitter)
        const val GIVE_UP_FRAMES = 15 // 300 ms with nothing: they stopped
        const val IDLE_PAUSE_MS = 2000L
        const val MAX_QUEUE = 50 // 1 s; more means we fell behind: drop the oldest
    }

    private class Talker(val id: Int, val codec: Int) {
        val packets = TreeMap<Long, ByteArray>()
        var lastSeq = -1L // the last packet's sequence number (the 16-bit id, unwrapped)
        var playedSeq = -1L
        var started = false
        var stopped = false
        var missing = 0
        var decoder: OpusDecoder? = null
        var pcm = ShortArray(0) // decoded, not yet played (interleaved by decoder channels)
        var pcmChannels = 1
    }

    private val lock = Object()
    private val talkers = HashMap<Int, Talker>()
    @Volatile private var running = true
    /** Sound off: incoming voice is dropped (people still show as talking). */
    @Volatile var muted = false
    /** Per person (client id): how loud, 1 = as is. */
    @Volatile var gains: Map<Int, Float> = emptyMap()
    /** Everyone's voice (Settings > Playback), 1 = as is. */
    @Volatile var voiceGain = 1f
    private val thread = Thread({ mixLoop() }, "voice-mixer").apply { start() }

    fun onVoice(v: TsClient.Voice) {
        if (v.codec != 4 && v.codec != 5) return // only Opus
        synchronized(lock) {
            var t = talkers[v.clientId]
            if (t == null || t.codec != v.codec) {
                t?.decoder?.close()
                t = Talker(v.clientId, v.codec)
                talkers[v.clientId] = t
            }
            if (v.data.isEmpty()) {
                t.stopped = true
                return
            }
            if (t.stopped && t.packets.isEmpty() && t.pcm.isEmpty()) {
                t.stopped = false
                t.started = false
                t.playedSeq = -1
            }
            val seq = unwrap(v.packetId, t.lastSeq)
            t.lastSeq = maxOf(t.lastSeq, seq)
            if (seq <= t.playedSeq) return // too late
            t.packets[seq] = v.data
            while (t.packets.size > MAX_QUEUE) t.packets.pollFirstEntry()
            lock.notifyAll()
        }
    }

    fun release() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        thread.join(1000)
    }

    private fun unwrap(id: Int, last: Long): Long {
        if (last < 0) return id.toLong() + 65536 // room below for packets that were overtaken
        var seq = (last and 0xffff.inv().toLong()) or id.toLong()
        if (seq < last - 32768) seq += 65536 else if (seq > last + 32768) seq -= 65536
        return seq
    }

    private fun mixLoop() {
        val bufferBytes = maxOf(
            AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT),
            FRAME * 2 * 2 * 4,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (communication) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()

        val mix = IntArray(FRAME * 2)
        val out = ShortArray(FRAME * 2)
        var idleSince = System.currentTimeMillis()
        var playing = false

        try {
            while (running) {
                val active = synchronized(lock) {
                    if (talkers.isEmpty() && System.currentTimeMillis() - idleSince > IDLE_PAUSE_MS) {
                        if (playing) { track.pause(); track.flush(); playing = false }
                        while (running && talkers.isEmpty()) lock.wait(1000)
                    }
                    talkers.values.toList()
                }
                if (!running) break
                if (!playing) { track.play(); playing = true }

                mix.fill(0)
                for (t in active) addFrame(t, mix)
                if (active.isNotEmpty()) idleSince = System.currentTimeMillis()

                if (muted) mix.fill(0)
                for (i in mix.indices) out[i] = mix[i].coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                track.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING) // blocks: this paces the loop
            }
        } catch (e: Exception) {
            AppLog.log("Voice playback stopped: $e")
        } finally {
            track.release()
            synchronized(lock) {
                talkers.values.forEach { it.decoder?.close() }
                talkers.clear()
            }
        }
    }

    /** Adds 20 ms of [t] to [mix] (stereo). */
    private fun addFrame(t: Talker, mix: IntArray) {
        var finished = false
        synchronized(lock) {
            if (!t.started) {
                if (t.packets.size >= START_PACKETS || (t.stopped && t.packets.isNotEmpty())) {
                    t.started = true
                    t.missing = 0
                    onTalking(t.id, true)
                } else {
                    if (t.stopped && t.packets.isEmpty()) talkers.remove(t.id)?.decoder?.close()
                    return
                }
            }
            // Decode until there's a frame's worth (or nothing more has come)
            while (t.pcm.size < FRAME * t.pcmChannels) {
                val next = t.packets.pollFirstEntry() ?: break
                t.playedSeq = next.key
                decode(t, next.value)
            }
            if (t.pcm.isEmpty()) {
                t.missing++
                if ((t.stopped && t.packets.isEmpty()) || t.missing > GIVE_UP_FRAMES) finished = true
            } else {
                t.missing = 0
            }
        }
        if (finished) {
            synchronized(lock) { if (talkers[t.id] === t) talkers.remove(t.id) }
            t.decoder?.close()
            onTalking(t.id, false)
            return
        }

        val ch = t.pcmChannels
        val samples = minOf(FRAME, t.pcm.size / ch)
        val gain = (gains[t.id] ?: 1f) * voiceGain
        for (i in 0 until samples) {
            if (ch == 1) {
                val s = (t.pcm[i] * gain).toInt()
                mix[i * 2] += s
                mix[i * 2 + 1] += s
            } else {
                mix[i * 2] += (t.pcm[i * 2] * gain).toInt()
                mix[i * 2 + 1] += (t.pcm[i * 2 + 1] * gain).toInt()
            }
        }
        t.pcm = t.pcm.copyOfRange(samples * ch, t.pcm.size)
    }

    private fun decode(t: Talker, frame: ByteArray) {
        try {
            val d = t.decoder ?: OpusDecoder(if (t.codec == 5) 2 else 1).also { t.decoder = it }
            d.decode(frame) { pcm ->
                t.pcmChannels = d.outChannels
                t.pcm += pcm
            }
        } catch (e: Exception) {
            AppLog.log("Couldn't decode voice from client ${t.id}: $e")
            t.decoder?.close()
            t.decoder = null
        }
    }
}
