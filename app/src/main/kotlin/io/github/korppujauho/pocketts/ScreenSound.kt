package io.github.korppujauho.pocketts

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Debug
import android.os.Process
import java.nio.ByteBuffer

/**
 * What the phone plays (a video, a game) as a screen share's sound. While the screen is shared, Android lets an app
 * record what other apps play; an app can refuse it (it's then silent for viewers). Our own sound, the channel's
 * voices and the streams being watched, is left out, so viewers don't hear the channel twice.
 *
 * WebRTC's microphone is off meanwhile: its recording loop still asks for a piece of sound every 10 ms, and [fill]
 * gives it the phone's. A thread of our own reads the phone's sound into a short buffer, and [fill] hands it out by
 * the clock: never faster than it plays, whatever the recording does (a loop running ahead filled the memory with
 * sound waiting to be sent).
 */
class ScreenSound(private val projection: MediaProjection) {
    @Volatile private var record: AudioRecord? = null
    private var reader: Thread? = null
    private var shape = Triple(0, 0, 0) // the encoding, channels and sample rate it was made for
    private var failed = false
    @Volatile private var stopped = false

    // What has been read and not handed out yet, oldest first (a ring)
    private val lock = Object()
    private var ring = ByteArray(0)
    private var ringStart = 0
    private var ringSize = 0

    // The pace, and the most kept waiting
    private val pace = SoundPace()
    private var keep = 0

    // Every 10 s, how it went (in the log)
    private var pieces = 0
    private var heard = 0L
    private var short = 0
    private var loudest = 0
    private var dropped = 0L // with the ring's lock
    private var countedSince = 0L

    /** Fills [buffer] with the next [bytes] of the phone's sound, in the shape WebRTC asks for (silence when there's none). */
    @Synchronized
    fun fill(buffer: ByteBuffer, encoding: Int, channels: Int, sampleRate: Int, bytes: Int) {
        if (!stopped) start(encoding, channels, sampleRate, bytes)
        pace.wait(bytes, encoding, channels, sampleRate)
        var got = 0
        synchronized(lock) {
            got = minOf(ringSize, bytes)
            for (i in 0 until got) buffer.put(i, ring[(ringStart + i) % ring.size])
            ringStart = if (ring.isEmpty()) 0 else (ringStart + got) % ring.size
            ringSize -= got
        }
        for (i in got until bytes) buffer.put(i, 0)
        if (encoding == AudioFormat.ENCODING_PCM_16BIT) {
            // The loudest sample, to tell sound from silence (an app that doesn't allow recording gives silence)
            for (i in 0 until got - 1 step 2) {
                val sample = kotlin.math.abs(((buffer.get(i).toInt() and 0xff) or (buffer.get(i + 1).toInt() shl 8)).toShort().toInt())
                if (sample > loudest) loudest = sample
            }
        }
        count(got, bytes)
    }

    private fun count(got: Int, bytes: Int) {
        pieces++
        heard += got
        if (got < bytes) short++
        val now = System.nanoTime()
        if (countedSince == 0L) countedSince = now
        if (now - countedSince >= 10_000_000_000L) {
            val (waiting, drops) = synchronized(lock) { (ringSize to dropped).also { dropped = 0 } }
            AppLog.log(
                "[Streams] Phone sound: $pieces pieces in %.1f s, %d short, %d bytes heard (loudest %d %%), %d dropped, %d waiting; memory %d MB native, %d MB Java".format(
                    (now - countedSince) / 1e9, short, heard, loudest * 100 / 32768, drops, waiting,
                    Debug.getNativeHeapAllocatedSize() / 1_048_576,
                    (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_048_576,
                ),
            )
            pieces = 0; heard = 0; short = 0; loudest = 0
            countedSince = now
        }
    }

    // Started on the first piece asked for, in its shape
    @SuppressLint("MissingPermission") // only used with the microphone allowed (Android's rule for recording other apps)
    private fun start(encoding: Int, channels: Int, sampleRate: Int, bytes: Int) {
        val wanted = Triple(encoding, channels, sampleRate)
        if (shape == wanted || failed) return
        stopReading()
        shape = wanted
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .excludeUid(Process.myUid())
                .build()
            val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val format = AudioFormat.Builder().setEncoding(encoding).setSampleRate(sampleRate).setChannelMask(mask).build()
            val min = AudioRecord.getMinBufferSize(sampleRate, mask, encoding)
            val r = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(min, bytes * 10)) // 100 ms
                .setAudioPlaybackCaptureConfig(config)
                .build()
            synchronized(lock) {
                ring = ByteArray(bytes * 25) // 250 ms
                ringStart = 0
                ringSize = 0
            }
            keep = bytes * 10 // more than 100 ms waiting: the oldest goes (the sound stays close to the picture)
            r.startRecording()
            record = r
            reader = Thread({ read(r, bytes) }, "phone-sound").apply { isDaemon = true; start() }
            AppLog.log("[Streams] Phone sound: ${sampleRate / 1000} kHz, $channels channel(s)")
        } catch (e: Exception) {
            failed = true
            AppLog.log("[Streams] The phone's sound can't be recorded: $e")
        }
    }

    // On the reader's thread: the phone's sound into the ring, as it comes
    private fun read(r: AudioRecord, bytes: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val piece = ByteArray(bytes)
        while (!stopped && record === r) {
            val n = r.read(piece, 0, bytes, AudioRecord.READ_BLOCKING)
            if (n <= 0) {
                if (stopped) break
                try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                continue
            }
            synchronized(lock) {
                for (i in 0 until n) ring[(ringStart + ringSize + i) % ring.size] = piece[i]
                ringSize += n
                if (ringSize > keep) {
                    val drop = ringSize - keep / 2
                    ringStart = (ringStart + drop) % ring.size
                    ringSize -= drop
                    dropped += drop
                }
            }
        }
    }

    private fun stopReading() {
        val r = record ?: return
        record = null
        try { r.stop() } catch (_: Exception) {}
        reader?.join(500)
        reader = null
        r.release()
    }

    /** Stops recording: [fill] gives silence from now on. */
    fun stop() {
        stopped = true
        try { record?.stop() } catch (_: Exception) {}
    }

    /** Lets go of the recording, after [stop] (waits for a [fill] still running). */
    @Synchronized
    fun release() {
        stopReading()
    }
}

/** Keeps a sound loop to the clock: each piece waits until it's due, one piece per piece's length of time. */
class SoundPace {
    private var nextDue = 0L

    fun wait(bytes: Int, encoding: Int, channels: Int, sampleRate: Int) {
        val perSecond = sampleRate.toLong() * channels * bytesPerSample(encoding)
        val pieceNs = if (perSecond > 0) bytes * 1_000_000_000L / perSecond else 10_000_000L
        val now = System.nanoTime()
        if (nextDue == 0L || now - nextDue > 100_000_000L) nextDue = now // the start, or after a pause: not catching up
        val wait = nextDue - now
        if (wait > 0) {
            try { Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt()) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        nextDue += pieceNs
    }

    private fun bytesPerSample(encoding: Int) = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> 4
        AudioFormat.ENCODING_PCM_8BIT -> 1
        else -> 2
    }
}
