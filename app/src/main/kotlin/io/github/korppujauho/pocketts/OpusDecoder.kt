package io.github.korppujauho.pocketts

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Opus frames to 48 kHz 16-bit PCM, with Android's own decoder. One per person talking (it keeps state). */
class OpusDecoder(channels: Int) : AutoCloseable {
    private val codec: MediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var pts = 0L

    /** The decoder's output channels (what it reports, which may differ from what was asked). */
    var outChannels = channels
        private set

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, channels)
        // The Opus header a stream would start with: version 1, channels, no pre-skip, 48 kHz, no gain, mapping 0
        val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusHead".toByteArray()).put(1).put(channels.toByte())
            .putShort(0).putInt(48000).putShort(0).put(0)
        head.flip()
        format.setByteBuffer("csd-0", head)
        format.setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0).flip() as ByteBuffer)
        format.setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80_000_000).flip() as ByteBuffer)
        codec.configure(format, null, null, 0)
        codec.start()
    }

    /** Decodes one frame; [out] gets the PCM (interleaved when stereo). */
    fun decode(frame: ByteArray, out: (ShortArray) -> Unit) {
        val i = codec.dequeueInputBuffer(10_000)
        if (i < 0) return
        codec.getInputBuffer(i)!!.apply { clear(); put(frame) }
        codec.queueInputBuffer(i, 0, frame.size, pts, 0)
        pts += 20_000
        var timeout = 10_000L
        while (true) {
            val o = codec.dequeueOutputBuffer(info, timeout)
            when {
                o >= 0 -> {
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(o)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val pcm = ShortArray(info.size / 2)
                        buf.order(ByteOrder.nativeOrder()).asShortBuffer().get(pcm)
                        out(pcm)
                    }
                    codec.releaseOutputBuffer(o, false)
                    timeout = 0
                }
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    outChannels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                else -> return
            }
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
