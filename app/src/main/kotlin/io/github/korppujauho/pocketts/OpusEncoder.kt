package io.github.korppujauho.pocketts

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteOrder

/** 20 ms of 48 kHz mono PCM in, one Opus frame out, with Android's own encoder. */
class OpusEncoder(bitrate: Int = 40_000) : AutoCloseable {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var pts = 0L

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, 1)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
        format.setInteger(MediaFormat.KEY_COMPLEXITY, 10)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 960 * 2 * 4)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** Encodes one frame (960 samples); [out] gets each Opus packet that comes out (usually exactly one). */
    fun encode(pcm: ShortArray, out: (ByteArray) -> Unit) {
        val i = codec.dequeueInputBuffer(10_000)
        if (i >= 0) {
            val buf = codec.getInputBuffer(i)!!
            buf.clear()
            buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm)
            codec.queueInputBuffer(i, 0, pcm.size * 2, pts, 0)
            pts += 20_000
        }
        var timeout = 10_000L
        while (true) {
            val o = codec.dequeueOutputBuffer(info, timeout)
            if (o < 0) return
            timeout = 0
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                val b = codec.getOutputBuffer(o)!!
                b.position(info.offset)
                b.limit(info.offset + info.size)
                val packet = ByteArray(info.size)
                b.get(packet)
                out(packet)
            }
            codec.releaseOutputBuffer(o, false)
        }
    }

    override fun close() {
        try {
            codec.stop()
        } catch (_: Exception) {
        }
        codec.release()
    }
}
