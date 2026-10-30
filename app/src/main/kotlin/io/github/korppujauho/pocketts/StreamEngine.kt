package io.github.korppujauho.pocketts

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import io.github.korppujauho.pocketts.proto.Command
import io.github.korppujauho.pocketts.proto.ServerModel
import io.github.korppujauho.pocketts.proto.Streams
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.webrtc.Camera2Enumerator
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Screen and camera streams over WebRTC, the TeamSpeak 6 way (as WebTS does it): watching someone's stream, and
 * sharing ours with everyone who asks. Everything happens on one thread of its own; [send] sends a command.
 */
class StreamEngine(
    private val context: Context,
    private val sendNow: (String) -> Unit,
    private val settings: () -> VoiceSettings,
    private val onState: (State) -> Unit,
    /** Whether the stream's sound should be silent now: the microphone muted, or you talking in the channel. */
    private val soundPaused: () -> Boolean = { false },
) {
    /** What the screen shows about streams. */
    data class State(
        val watching: Int = 0, // the streamer's client id (0 = none)
        val watchStatus: String = "",
        val watchVideo: VideoTrack? = null,
        val watchAudio: org.webrtc.AudioTrack? = null,
        val watchMuted: Boolean = false,
        val sharing: Streams.Kind? = null,
        val shareLive: Boolean = false,
        val shareSound: Boolean = false, // our stream carries sound (a camera's: the microphone; the screen's: what the phone plays)
        val soundPaused: Boolean = false, // ... silent just now (microphone muted, or talking in the channel)
        val sharePaused: Boolean = false, // our stream paused (as the TeamSpeak app pauses): nothing sent, viewers see it paused
        val shareFront: Boolean = true, // the front camera (with both: the front one is the big picture)
        val shareBoth: Boolean = false, // both cameras at once
        val rotateLocked: Boolean = false, // the camera's turn is locked (else it follows how the phone is held)
        val phoneTurn: Int = 0, // while sharing the camera: how the phone is held, clockwise from upright
        val lockedTurn: Int = 0, // the turn kept while locked (the phone's, from the screen's upright, when locked)
        val watchVolume: Float = 1f, // the watched stream's sound, 0 - 1
        val viewers: Int = 0,
        val localVideo: VideoTrack? = null,
        val message: String? = null, // why a share stopped or couldn't start
        val phoneSoundBlocked: Boolean = false, // a screen share can't have the phone's sound now (watching a stream: factoryForSound)
    )

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "streams").apply { isDaemon = true } }
    private val counter = AtomicInteger(0)
    private fun rc(what: String) = "stream-$what-${counter.incrementAndGet()}"

    private fun send(cmd: String) {
        sendNow(cmd)
    }

    val egl: EglBase = EglBase.create()
    // The factory, made on first use. Its microphone (a stream's sound) opens the way the voice's does: in call mode
    // with echo cancellation, else as the ordinary microphone. Two different ones at once, Android gives the call
    // one all the sound (the voice then hears nothing), and the call one alone hears only what's close to it.
    private var made: PeerConnectionFactory? = null
    private var madeAudio: JavaAudioDeviceModule? = null
    private var madeEcho = false
    private var madeRecordless = false // without WebRTC's microphone: the phone's sound fills in (factoryForSound)
    private var wantRecordless = false
    private var initialized = false
    private val factory: PeerConnectionFactory get() = made ?: makeFactory()

    private fun makeFactory(): PeerConnectionFactory {
        if (!initialized) {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
            initialized = true
        }
        val echo = settings().echoCancellation
        val audio = JavaAudioDeviceModule.builder(context.applicationContext)
            .setAudioSource(if (echo) android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION else android.media.MediaRecorder.AudioSource.MIC)
            .setUseHardwareAcousticEchoCanceler(echo)
            .setUseHardwareNoiseSuppressor(false) // the surroundings are heard (as WebTS does it)
            // Sharing the screen with its sound, the microphone is off and the phone's sound is put in its place. With
            // no microphone WebRTC says it recorded nothing (bytes 0) and nothing else keeps its loop to time: the
            // whole buffer is one piece, the phone's sound or silence, handed out at the pace sound plays (unpaced,
            // the loop ran flat out and filled the memory)
            .setAudioBufferCallback { buffer, encoding, channels, rate, bytes, time ->
                if (bytes == 0) {
                    val size = buffer.capacity()
                    phoneSound?.fill(buffer, encoding, channels, rate, size) ?: run {
                        for (i in 0 until size) buffer.put(i, 0)
                        idlePace.wait(size, encoding, channels, rate)
                    }
                }
                time
            }
            .createAudioDeviceModule()
        if (wantRecordless) audio.setAudioRecordEnabled(false)
        val f = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .setAudioDeviceModule(audio)
            .createPeerConnectionFactory()
        made = f
        madeAudio = audio
        madeEcho = echo
        madeRecordless = wantRecordless
        return f
    }

    /**
     * The factory for a share's sound: [phone] for the phone's sound, with WebRTC's microphone never opened (one it
     * had opened before and was then told to leave alone made its recording loop fail), else with the microphone and
     * echo cancellation as set now. A new one when the old one differs and nothing uses it. False when the phone's
     * sound can't be had now (the factory is in use, with a microphone).
     */
    private fun factoryForSound(phone: Boolean): Boolean {
        wantRecordless = phone
        val f = made ?: return true
        if (madeEcho == settings().echoCancellation && madeRecordless == phone) return true
        if (state.watching != 0 || viewers.isNotEmpty()) {
            if (madeRecordless == phone) return true // only the echo setting differs: it waits
            if (phone) return false
            // Back to the microphone: WebRTC opens it itself when it next records (it had none open)
            madeAudio?.setAudioRecordEnabled(true)
            madeRecordless = false
            return true
        }
        f.dispose()
        madeAudio?.release()
        made = null
        madeAudio = null
        return true
    }
    private val decoders by lazy {
        factory // loads WebRTC's library first
        DefaultVideoDecoderFactory(egl.eglBaseContext).supportedCodecs.toList()
    }

    @Volatile var state = State()
        private set

    private fun update(f: (State) -> State) {
        state = f(state).let { it.copy(phoneSoundBlocked = made != null && !madeRecordless && (it.watching != 0 || viewers.isNotEmpty())) }
        onState(state)
    }

    private fun log(s: String) = AppLog.log("[Streams] $s")

    /** A text shown to the person, in the phone's language. */
    private fun text(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun config(): PeerConnection.RTCConfiguration {
        val v = settings()
        val servers = Streams.STUN.map { PeerConnection.IceServer.builder(it).createIceServer() }.toMutableList()
        val relay = v.turnUrl.trim()
        if (relay.isNotEmpty()) {
            servers.add(PeerConnection.IceServer.builder(relay).setUsername(v.turnUser).setPassword(v.turnPassword).createIceServer())
        }
        return PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            if (v.relayOnly && relay.isNotEmpty()) iceTransportsType = PeerConnection.IceTransportsType.RELAY
        }
    }

    // ====== Small waits for WebRTC's callbacks (on our own thread) ======

    private fun <T> await(what: String, start: (ok: (T) -> Unit, fail: (String) -> Unit) -> Unit): T {
        val done = CountDownLatch(1)
        var result: T? = null
        var error: String? = null
        start({ result = it; done.countDown() }, { error = it; done.countDown() })
        if (!done.await(10, TimeUnit.SECONDS)) throw IllegalStateException("$what took too long")
        error?.let { throw IllegalStateException("$what: $it") }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun sdpObserver(ok: (SessionDescription?) -> Unit, fail: (String) -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(sd: SessionDescription) = ok(sd)
        override fun onSetSuccess() = ok(null)
        override fun onCreateFailure(e: String) = fail(e)
        override fun onSetFailure(e: String) = fail(e)
    }

    private fun setRemote(pc: PeerConnection, type: SessionDescription.Type, sdp: String) =
        await<SessionDescription?>("Setting their description") { ok, fail -> pc.setRemoteDescription(sdpObserver(ok, fail), SessionDescription(type, sdp)) }

    private fun setLocal(pc: PeerConnection, sd: SessionDescription) =
        await<SessionDescription?>("Setting our description") { ok, fail -> pc.setLocalDescription(sdpObserver(ok, fail), sd) }

    private fun answer(pc: PeerConnection): SessionDescription =
        await<SessionDescription?>("Making the answer") { ok, fail -> pc.createAnswer(sdpObserver(ok, fail), MediaConstraints()) }!!

    private fun offer(pc: PeerConnection, restart: Boolean = false): SessionDescription =
        await<SessionDescription?>("Making the offer") { ok, fail ->
            pc.createOffer(sdpObserver(ok, fail), MediaConstraints().apply {
                if (restart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
            })
        }!!

    private open inner class Observer(val name: String) : PeerConnection.Observer {
        override fun onSignalingChange(s: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) = log("$name: ice $s")
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
        override fun onIceCandidate(c: IceCandidate) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
        override fun onAddStream(s: MediaStream) {}
        override fun onRemoveStream(s: MediaStream) {}
        override fun onDataChannel(d: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
    }

    // ====== Watching ======

    private class Watch(val streamerId: Int, var streamId: String?) {
        var pc: PeerConnection? = null
        var paused = false // the streamer paused (their last offer sends nothing)
        val pending = ArrayList<IceCandidate>()
        var original: Map<String, String> = emptyMap()
    }

    private var watch: Watch? = null

    /** Starts watching [streamer]'s stream ([streamId] when known; else asked from the server first). */
    fun watch(streamer: ServerModel.Client, streamId: String?) = exec.execute {
        closeWatch(sendLeave = true)
        watch = Watch(streamer.id, streamId)
        update { it.copy(watching = streamer.id, watchStatus = text(R.string.stream_requesting), watchVideo = null, watchAudio = null, watchMuted = false) }
        if (streamId == null) send(Streams.requestInfo(streamer.id, rc("info"))) else send(Streams.join(streamId, streamer.id, rc("join")))
    }

    fun stopWatching() = exec.execute { closeWatch(sendLeave = true) }

    /** How loud the watched stream is, 0 - 1. */
    fun setWatchVolume(volume: Float) = exec.execute {
        state.watchAudio?.setVolume(volume.toDouble())
        update { it.copy(watchVolume = volume) }
    }

    /** The stream's sound off or on (kept while the player becomes a small window and back). */
    fun setWatchMuted(muted: Boolean) = exec.execute {
        state.watchAudio?.setEnabled(!muted)
        update { it.copy(watchMuted = muted) }
    }

    private fun closeWatch(sendLeave: Boolean) {
        val w = watch ?: return
        watch = null
        if (sendLeave) w.streamId?.let { send(Streams.leave(it, w.streamerId, rc("leave"))) }
        w.pc?.dispose()
        update { it.copy(watching = 0, watchStatus = "", watchVideo = null, watchAudio = null) }
    }

    private fun endWatch(message: String) {
        val w = watch ?: return
        update { it.copy(watchStatus = message) }
        exec.execute {
            Thread.sleep(2500)
            if (watch === w) closeWatch(sendLeave = false)
        }
    }

    /** The streamer's offer: answered, on a new connection or (a further offer) the same one. */
    private fun onOffer(streamId: String, offerSdp: String) {
        val w = watch ?: return
        if (w.streamId != null && w.streamId != streamId) return
        w.streamId = streamId
        try {
            val profiles = decoders.filter { it.name.equals("H264", true) }
                .mapNotNull { it.params["profile-level-id"]?.take(4)?.lowercase() }
            val profile = if ("640c" in profiles) null else listOf("6400", "42e0", "4200").firstOrNull { it in profiles }
            val relabel = Streams.relabelOfferH264(offerSdp, profile, Streams.offerHasOther(offerSdp, decoders.map { it.name }))
            w.original = relabel.original
            val pc = w.pc ?: createViewerConnection(w) ?: return
            setRemote(pc, SessionDescription.Type.OFFER, relabel.sdp)
            w.pending.forEach { pc.addIceCandidate(it) }
            w.pending.clear()
            val local = answer(pc)
            setLocal(pc, local)
            val sdp = Streams.withOfferSessionVersion(Streams.restoreH264(pc.localDescription.description, w.original), offerSdp)
            send(Streams.answer(streamId, w.streamerId, sdp, rc("answer")))
            // A new offer on a running stream is the streamer pausing or going on; the first one is the connecting
            val wasPaused = w.paused
            w.paused = Streams.isPausedOffer(offerSdp)
            if (w.paused != wasPaused) log(if (w.paused) "Stream paused by the streamer" else "Stream going on")
            val connected = pc.connectionState() == PeerConnection.PeerConnectionState.CONNECTED
            update { it.copy(watchStatus = watchStatus(w, connected, it.watchVideo != null)) }
        } catch (e: Throwable) {
            log("Watching failed: $e")
            endWatch(text(R.string.stream_open_failed))
        }
    }

    /** What the player says over the picture: paused, connecting, waiting for the picture, or nothing. */
    private fun watchStatus(w: Watch, connected: Boolean, hasVideo: Boolean) = when {
        w.paused -> text(R.string.stream_paused)
        !connected -> text(R.string.stream_connecting)
        !hasVideo -> text(R.string.stream_waiting_picture)
        else -> ""
    }

    private fun createViewerConnection(w: Watch): PeerConnection? {
        val pc = factory.createPeerConnection(config(), object : Observer("watch") {
            override fun onIceCandidate(c: IceCandidate) {
                val id = w.streamId ?: return
                send(Streams.candidate(id, w.streamerId, c.sdp, c.sdpMid, c.sdpMLineIndex, rc("ice")))
            }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
                log("watch: $s")
                exec.execute {
                    if (watch !== w) return@execute
                    when (s) {
                        PeerConnection.PeerConnectionState.CONNECTED -> update { it.copy(watchStatus = watchStatus(w, true, it.watchVideo != null)) }
                        PeerConnection.PeerConnectionState.FAILED -> {
                            update { it.copy(watchStatus = text(R.string.stream_trying)) }
                            exec.execute {
                                Thread.sleep(20_000)
                                if (watch === w && w.pc?.connectionState() == PeerConnection.PeerConnectionState.FAILED) endWatch(text(R.string.stream_connection_lost))
                            }
                        }
                        else -> {}
                    }
                }
            }
            override fun onTrack(t: RtpTransceiver) {
                val track = t.receiver.track()
                exec.execute {
                    if (watch !== w) return@execute
                    when (track) {
                        is VideoTrack -> update { it.copy(watchVideo = track, watchStatus = if (w.paused) text(R.string.stream_paused) else "") }
                        is org.webrtc.AudioTrack -> {
                            track.setEnabled(!state.watchMuted)
                            track.setVolume(state.watchVolume.toDouble())
                            update { it.copy(watchAudio = track) }
                        }
                    }
                }
            }
        }) ?: return null
        w.pc = pc
        return pc
    }

    // ====== Sharing ======

    private class Viewer(val id: Int, val pc: PeerConnection) {
        val joined = System.nanoTime()
        val pending = ArrayList<IceCandidate>()
        val outgoing = ArrayList<IceCandidate>()
        var answered = false
        var offerSent = false
        var original: Map<String, String> = emptyMap()
    }

    private var shareId: String? = null
    private var sharePending = false
    private val viewers = HashMap<Int, Viewer>()
    private var capturer: VideoCapturer? = null
    private var source: VideoSource? = null
    private var helper: SurfaceTextureHelper? = null
    private var track: VideoTrack? = null
    private var soundSource: org.webrtc.AudioSource? = null
    private var sound: org.webrtc.AudioTrack? = null
    private var bitrate = 2500
    private var fps = 30
    private val turn by lazy {
        CameraTurn(context).also { t -> t.onTurn = { deg -> exec.execute { if (state.sharing == Streams.Kind.CAMERA || deg == 0) update { it.copy(phoneTurn = deg) } } } }
    }
    private var dual: DualCamera? = null
    @Volatile private var phoneSound: ScreenSound? = null
    private val idlePace = SoundPace() // WebRTC's sound loop without a microphone and without the phone's sound

    /** Starts sharing the camera ([front] or back, [hd] 720p else 480p). */
    fun shareCamera(front: Boolean, quality: Int, name: String, withSound: Boolean = false) = exec.execute {
        if (state.sharing != null) return@execute
        val cams = Camera2Enumerator(context)
        val device = cams.deviceNames.firstOrNull { cams.isFrontFacing(it) == front } ?: cams.deviceNames.firstOrNull() ?: run {
            log("No camera")
            return@execute
        }
        bitrate = when { quality >= 1080 -> 4500; quality >= 720 -> 2500; else -> 1000 }
        update { it.copy(shareFront = cams.isFrontFacing(device), shareBoth = false) }
        turn.start(settings().cameraRotateLock)
        startCapture(cams.createCapturer(device, null), false, widthFor(quality), quality, Streams.Kind.CAMERA, name, withSound) {
            turn.observer(it) { state.shareFront }
        }
    }

    /** Both cameras at once: the back one big, the front one small in a corner (Flip swaps them). */
    fun shareBothCameras(quality: Int, name: String, withSound: Boolean = false) = exec.execute {
        if (state.sharing != null) return@execute
        val ids = DualCamera.pair(context) ?: run {
            update { it.copy(message = text(R.string.share_both_unsupported)) }
            return@execute
        }
        bitrate = when { quality >= 1080 -> 5000; quality >= 720 -> 3000; else -> 1200 }
        update { it.copy(shareFront = false, shareBoth = true) }
        turn.start(settings().cameraRotateLock)
        val cam = DualCamera(context, egl.eglBaseContext, ids) { front -> turn.extra(front) }
        dual = cam
        startCapture(cam, false, widthFor(quality), quality, Streams.Kind.CAMERA, name, withSound)
    }

    // The phone's sound (a screen share's) goes on while you talk; the microphone's (a camera's) doesn't
    private fun paused() = phoneSound == null && soundPaused()

    /** The camera stream's sound follows the microphone: silent while it's muted or you talk in the channel (no echo). */
    fun refreshSound() = exec.execute {
        if (sound == null) return@execute
        val paused = paused()
        // The microphone muted, not the track: muted, it still delivers (silence) at full time. A track switched off
        // sent nothing, so each pause dropped out of the sound's timeline and viewers heard it that much later.
        madeAudio?.setMicrophoneMute(paused)
        if (state.soundPaused != paused) {
            log(if (paused) "Stream sound quiet" else "Stream sound on")
            update { it.copy(soundPaused = paused) }
        }
    }

    // 16:9 for HD and Full HD, 4:3 for the data saver (as cameras offer them)
    private fun widthFor(height: Int) = when {
        height >= 1080 -> 1920
        height >= 720 -> 1280
        else -> 640
    }

    /** The warning about a share was closed. */
    fun clearMessage() = exec.execute {
        if (state.message != null) update { it.copy(message = null) }
    }

    /** The camera's turn locked, or following how the phone is held. */
    fun setRotateLock(on: Boolean) = exec.execute {
        turn.lock(on)
        log("Rotate ${if (on) "locked at ${turn.extra(false)}°" else "follows the phone"}")
        update { it.copy(rotateLocked = on, lockedTurn = turn.extra(false)) }
    }

    /** Starts sharing the screen, with the permission Android gave ([projection]: its result). */
    fun shareScreen(projection: Intent, width: Int, height: Int, name: String, withSound: Boolean = false) = exec.execute {
        if (state.sharing != null) return@execute
        val cap = ScreenCapturerAndroid(projection, object : MediaProjection.Callback() {
            override fun onStop() = stopShare()
        })
        // At most 1920 on the long side, the screen's shape kept
        val scale = minOf(1.0, 1920.0 / maxOf(width, height))
        bitrate = 4000
        startCapture(cap, true, (width * scale).toInt() / 2 * 2, (height * scale).toInt() / 2 * 2, Streams.Kind.SCREEN, name, withSound)
    }

    private fun startCapture(
        cap: VideoCapturer, screen: Boolean, w: Int, h: Int, kind: Streams.Kind, name: String, withSound: Boolean = false,
        observer: (org.webrtc.CapturerObserver) -> org.webrtc.CapturerObserver = { it },
    ) {
        try {
            val phoneWanted = withSound && cap is ScreenCapturerAndroid
            val phoneReady = !withSound || factoryForSound(phone = phoneWanted)
            val src = factory.createVideoSource(screen)
            val help = SurfaceTextureHelper.create("capture", egl.eglBaseContext)
            cap.initialize(help, context.applicationContext, observer(src.capturerObserver))
            cap.startCapture(w, h, fps)
            capturer = cap; source = src; helper = help
            track = factory.createVideoTrack("share", src)
            // The screen's sound is what the phone plays: recorded through the share's own permission
            val phone = if (phoneWanted && phoneReady) (cap as ScreenCapturerAndroid).mediaProjection?.let { ScreenSound(it) } else null
            if (phone != null) {
                phoneSound = phone
            } else if (phoneWanted) {
                log(if (phoneReady) "No screen to record the sound with: sharing without it" else "Watching a stream: the phone's sound can't be shared now, sharing without it")
            }
            if (withSound && (cap !is ScreenCapturerAndroid || phone != null)) {
                fun option(key: String, on: Boolean) = org.webrtc.MediaConstraints.KeyValuePair(key, "$on")
                val constraints = org.webrtc.MediaConstraints().apply {
                    // The phone's sound as it is: no echo cancelling, levelling or filtering meant for a voice
                    mandatory.add(option("googEchoCancellation", phone == null && settings().echoCancellation))
                    mandatory.add(option("googNoiseSuppression", false))
                    mandatory.add(option("googAutoGainControl", phone == null))
                    if (phone != null) mandatory.add(option("googHighpassFilter", false))
                }
                soundSource = factory.createAudioSource(constraints)
                sound = factory.createAudioTrack("share-sound", soundSource)
            }
            // Without stream sound, the stream's microphone stays muted too (WebRTC would send it on the sound line)
            madeAudio?.setMicrophoneMute(sound == null || paused())
            sharePending = true
            update {
                it.copy(
                    sharing = kind, shareLive = false, viewers = 0, localVideo = track, message = null, rotateLocked = turn.locked,
                    lockedTurn = turn.extra(false), shareSound = sound != null, soundPaused = sound != null && paused(),
                )
            }
            send(Streams.setup(name, kind, bitrate, audio = sound != null, returnCode = rc("setup")))
            log("Share starting: $kind ${w}x$h${if (sound != null) " with sound" else ""}")
        } catch (e: Exception) {
            log("Share failed to start: $e")
            releaseCapture()
            update { it.copy(sharing = null, localVideo = null, message = text(R.string.share_failed)) }
        }
    }

    /** The other camera (front ↔ back) while sharing it. */
    fun flipCamera() = exec.execute {
        dual?.let { d ->
            d.frontBig = !d.frontBig
            update { it.copy(shareFront = d.frontBig) }
            return@execute
        }
        val cam = capturer as? org.webrtc.CameraVideoCapturer ?: return@execute
        cam.switchCamera(object : org.webrtc.CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(front: Boolean) = exec.execute { update { it.copy(shareFront = front) } }
            override fun onCameraSwitchError(e: String) = log("Camera switch failed: $e")
        })
    }

    fun stopShare() = exec.execute {
        shareId?.let { send(Streams.stop(it)) }
        endShare()
    }

    /**
     * Our stream paused or going on, as the TeamSpeak app's pause does it: a new offer to each viewer with every line
     * `inactive` (nothing is sent, and they show it paused), or `sendonly` again. The capture goes on, so going on
     * needs no new permission.
     */
    fun setSharePaused(paused: Boolean) = exec.execute {
        if (state.sharing == null || state.sharePaused == paused) return@execute
        update { it.copy(sharePaused = paused) }
        log(if (paused) "Share paused" else "Share going on")
        val id = shareId ?: return@execute
        for (v in viewers.values.toList()) {
            try {
                v.pc.transceivers.forEach { it.setDirection(shareDirection()) }
                val local = offer(v.pc)
                setLocal(v.pc, local)
                applyLimits(v.pc)
                val relabel = Streams.relabelShareOfferH264(Streams.withoutVideoOrientation(Streams.withoutStrayPayloadLines(v.pc.localDescription.description)))
                v.original = relabel.original
                send(Streams.renegotiateOffer(id, v.id, relabel.sdp, rc("pause")))
            } catch (e: Throwable) {
                log("Couldn't ${if (paused) "pause" else "go on"} for ${v.id}: $e")
            }
        }
    }

    private fun shareDirection() =
        if (state.sharePaused) RtpTransceiver.RtpTransceiverDirection.INACTIVE else RtpTransceiver.RtpTransceiverDirection.SEND_ONLY

    // While sharing: every 5 s, how the stream keeps time, in the phone's log. The sound captured should keep
    // up with the clock and the camera's frames with the encoder's; a gap growing shows which side drifts. Packets
    // waiting long to be sent show the encoder making more than the connection is thought to take.
    private val ticker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "stream-stats").apply { isDaemon = true } }
    private var statsTask: java.util.concurrent.ScheduledFuture<*>? = null

    private fun startStats() {
        statsTask?.cancel(false)
        lastVideo = emptyMap()
        statsTask = ticker.scheduleWithFixedDelay({ exec.execute { logStats() } }, 5, 5, java.util.concurrent.TimeUnit.SECONDS)
    }

    // The previous stats' video counters, for what happened in between
    private var lastVideo: Map<String, Double> = emptyMap()

    private fun logStats() {
        val v = viewers.values.firstOrNull() ?: return
        val seconds = (System.nanoTime() - v.joined) / 1e9
        v.pc.getStats { report ->
            val parts = ArrayList<String>()
            for (s in report.statsMap.values) {
                val m = s.members
                fun num(key: String) = (m[key] as? Number)?.toDouble() ?: 0.0
                when {
                    s.type == "media-source" && m["kind"] == "audio" ->
                        parts.add("sound captured %.1f s".format((m["totalSamplesDuration"] as? Number)?.toDouble() ?: -1.0))
                    s.type == "media-source" && m["kind"] == "video" -> parts.add("camera ${m["frames"]} frames")
                    s.type == "outbound-rtp" && m["kind"] == "audio" -> parts.add("sound ${m["packetsSent"]} packets")
                    s.type == "outbound-rtp" && m["kind"] == "video" -> {
                        val now = mapOf(
                            "time" to s.timestampUs / 1e6, "bytes" to num("bytesSent"), "packets" to num("packetsSent"),
                            "delay" to num("totalPacketSendDelay"), "frames" to num("framesEncoded"), "keys" to num("keyFramesEncoded"),
                            "encode" to num("totalEncodeTime"),
                        )
                        val last = lastVideo
                        lastVideo = now
                        fun d(key: String) = (now[key] ?: 0.0) - (last[key] ?: 0.0)
                        val span = d("time").takeIf { it > 0 } ?: 1.0
                        parts.add(
                            "video ${m["frameWidth"]}x${m["frameHeight"]} ${m["encoderImplementation"]}, " +
                                "%.0f fps, %.0f kbit/s (aim %.0f), %d key frames, ".format(d("frames") / span, d("bytes") * 8 / span / 1000, num("targetBitrate") / 1000, d("keys").toInt()) +
                                "encoding %.0f ms a frame, waiting to be sent %.0f ms a packet, ".format(
                                    if (d("frames") > 0) d("encode") / d("frames") * 1000 else 0.0,
                                    if (d("packets") > 0) d("delay") / d("packets") * 1000 else 0.0,
                                ) +
                                "limit ${m["qualityLimitationReason"]}, asked to resend ${m["nackCount"]}, asked for a key frame ${m["pliCount"]}",
                        )
                    }
                    s.type == "candidate-pair" && m["nominated"] == true && m["state"] == "succeeded" ->
                        parts.add("connection allows %.0f kbit/s".format(num("availableOutgoingBitrate") / 1000))
                    s.type == "remote-inbound-rtp" -> parts.add("${m["kind"]} round trip ${m["roundTripTime"]} s, lost ${m["packetsLost"]}")
                }
            }
            log("Share stats after %.0f s: ".format(seconds) + parts.joinToString(", "))
        }
    }

    private fun endShare() {
        statsTask?.cancel(false)
        statsTask = null
        shareId = null
        sharePending = false
        // First, so WebRTC's recording loop isn't left waiting for the phone's sound while the viewers are let go
        phoneSound?.stop()
        viewers.values.forEach { it.pc.dispose() }
        viewers.clear()
        releaseCapture()
        update { it.copy(sharing = null, shareLive = false, viewers = 0, localVideo = null, phoneTurn = 0, sharePaused = false) }
    }

    private fun releaseCapture() {
        phoneSound?.stop()
        turn.stop()
        try { capturer?.stopCapture() } catch (_: Exception) {}
        capturer?.dispose(); capturer = null
        dual = null
        track?.dispose(); track = null
        source?.dispose(); source = null
        sound?.dispose(); sound = null
        soundSource?.dispose(); soundSource = null
        helper?.dispose(); helper = null
        phoneSound?.let { p ->
            phoneSound = null
            p.release()
        }
    }

    /** Someone asks to watch our share: everyone may, so they get our offer right away. */
    private fun addViewer(clid: Int) {
        val id = shareId ?: return
        if (clid in viewers) return
        val t = track ?: return
        lateinit var v: Viewer
        val pc = factory.createPeerConnection(config(), object : Observer("viewer $clid") {
            override fun onIceCandidate(c: IceCandidate) = exec.execute {
                // TeamSpeak refuses a viewer's addresses sent before our offer let them in: they wait until it's gone
                if (viewers[clid] !== v) return@execute
                if (v.offerSent) send(Streams.candidate(id, clid, c.sdp, c.sdpMid, c.sdpMLineIndex, rc("ice"))) else v.outgoing.add(c)
            }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
                log("viewer $clid: $s")
                if (s == PeerConnection.PeerConnectionState.FAILED) exec.execute {
                    Thread.sleep(20_000)
                    if (viewers[clid] === v && v.pc.connectionState() == PeerConnection.PeerConnectionState.FAILED) dropViewer(clid)
                }
            }
        }) ?: return
        v = Viewer(clid, pc)
        viewers[clid] = v
        try {
            val video = pc.addTransceiver(t, RtpTransceiver.RtpTransceiverInit(shareDirection(), listOf("share")))
            preferH264(video)
            // The sound line: the microphone when the stream has sound, else one without a track (the TeamSpeak app
            // never answers an offer with video only)
            val withSound = sound
            if (withSound != null) pc.addTransceiver(withSound, RtpTransceiver.RtpTransceiverInit(shareDirection(), listOf("share")))
            else {
                // WebRTC sends the recorded microphone on every sound line, with a track or without: this one sends nothing
                val quiet = pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, RtpTransceiver.RtpTransceiverInit(shareDirection()))
                val p = quiet.sender.parameters
                p.encodings.forEach { it.active = false }
                quiet.sender.parameters = p
            }
            val local = offer(pc)
            setLocal(pc, local)
            applyLimits(pc)
            val relabel = Streams.relabelShareOfferH264(Streams.withoutVideoOrientation(Streams.withoutStrayPayloadLines(pc.localDescription.description)))
            v.original = relabel.original
            send(Streams.accept(id, clid, relabel.sdp, rc("accept")))
            v.offerSent = true
            v.outgoing.forEach { send(Streams.candidate(id, clid, it.sdp, it.sdpMid, it.sdpMLineIndex, rc("ice"))) }
            v.outgoing.clear()
            update { it.copy(viewers = viewers.size) }
        } catch (e: Throwable) {
            log("Couldn't offer the share to $clid: $e")
            dropViewer(clid)
        }
    }

    /** H.264 first when this phone can encode it in hardware (as WebTS prefers it for the TeamSpeak app). */
    private fun preferH264(t: RtpTransceiver) {
        try {
            val caps = factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
            val order = listOf("H264", "VP8", "VP9", "AV1")
            val sorted = caps.sortedBy { c -> order.indexOfFirst { it.equals(c.name, true) }.let { if (it < 0) order.size else it } }
            t.setCodecPreferences(sorted)
        } catch (e: Throwable) {
            log("Codec order not applied: ${e.message}")
        }
    }

    /** The real limits are on the connection: bitrate and frame rate, keeping the resolution when it struggles. */
    private fun applyLimits(pc: PeerConnection) {
        for (sender in pc.senders) {
            if (sender.track() == null) continue
            val p = sender.parameters
            p.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            p.encodings.forEach {
                it.maxBitrateBps = bitrate * 1000
                it.maxFramerate = fps
            }
            sender.parameters = p
        }
    }

    private fun dropViewer(clid: Int) {
        viewers.remove(clid)?.pc?.dispose()
        update { it.copy(viewers = viewers.size) }
    }

    private fun onShareSignal(s: Streams.Signal) {
        val v = viewers[s.from] ?: return
        val id = shareId ?: return
        try {
            when (s.cmd) {
                "answer" -> {
                    val sdp = s.sdp ?: return
                    setRemote(v.pc, SessionDescription.Type.ANSWER, Streams.withShareSendLevel(Streams.restoreH264(sdp, v.original)))
                    v.answered = true
                    v.pending.forEach { v.pc.addIceCandidate(it) }
                    v.pending.clear()
                }
                "iceCandidate" -> {
                    val c = IceCandidate(s.mid ?: "0", s.mLine ?: 0, s.candidate ?: return)
                    if (v.answered) v.pc.addIceCandidate(c) else v.pending.add(c)
                }
                "reconnect" -> {
                    v.offerSent = false
                    val local = offer(v.pc, restart = true)
                    setLocal(v.pc, local)
                    applyLimits(v.pc)
                    val relabel = Streams.relabelShareOfferH264(Streams.withoutVideoOrientation(Streams.withoutStrayPayloadLines(v.pc.localDescription.description)))
                    v.original = relabel.original
                    v.answered = false
                    send(Streams.reconnectOffer(id, s.from, relabel.sdp, rc("reoffer")))
                    v.offerSent = true
                    v.outgoing.forEach { send(Streams.candidate(id, s.from, it.sdp, it.sdpMid, it.sdpMLineIndex, rc("ice"))) }
                    v.outgoing.clear()
                }
                else -> log("Unhandled signal from ${s.from}: ${s.cmd}")
            }
        } catch (e: Exception) {
            log("Viewer ${s.from}: ${e.message}")
            dropViewer(s.from)
        }
    }

    // ====== From the server ======

    /** Every command (any thread); [model] is read on our thread only for ids, so copies are passed. */
    fun onCommand(cmd: Command, ownId: Int, ownChannel: () -> Int, channelOf: (Int) -> Int?) {
        when (cmd.name) {
            "notifystreaminfo", "notifystreamstarted", "notifystreamstopped", "notifyrespondjoinstreamrequest",
            "notifystreamsignaling", "notifyjoinstreamrequest", "notifystreamclientleft", "notifyclientmoved", "notifyclientleftview", "error",
            -> exec.execute { handle(cmd, ownId, ownChannel, channelOf) }
        }
    }

    private fun handle(cmd: Command, ownId: Int, ownChannel: () -> Int, channelOf: (Int) -> Int?) {
        val g = cmd.params
        when (cmd.name) {
            "notifystreaminfo", "notifystreamstarted" -> {
                val id = g["id"] ?: g["stream_id"] ?: return
                val clid = g["clid"]?.toIntOrNull() ?: return
                if (cmd.name == "notifystreamstarted" && clid == ownId && sharePending) {
                    sharePending = false
                    shareId = id
                    update { it.copy(shareLive = true) }
                    startStats()
                    log("Share live as $id")
                }
                val w = watch
                if (w != null && w.streamerId == clid && w.streamId == null) {
                    w.streamId = id
                    send(Streams.join(id, clid, rc("join")))
                }
            }
            "notifystreamstopped" -> {
                val id = g["id"] ?: g["stream_id"]
                val clid = g["clid"]?.toIntOrNull() ?: 0
                if (id != null && id == shareId) endShare()
                val w = watch
                if (w != null && (id == w.streamId || clid == w.streamerId)) endWatch(text(R.string.stream_ended))
            }
            "notifyrespondjoinstreamrequest" -> {
                val w = watch ?: return
                val offer = g["offer"]
                if (g["decision"] == "1" && !offer.isNullOrEmpty()) onOffer(g["id"] ?: g["stream_id"] ?: w.streamId ?: return, offer)
                else endWatch(g["msg"]?.ifEmpty { null } ?: text(R.string.stream_denied))
            }
            "notifystreamsignaling" -> {
                val s = Streams.readSignal(cmd) ?: return
                if (shareId != null && s.streamId == shareId) return onShareSignal(s)
                val w = watch ?: return
                val streamId = s.streamId.ifEmpty { w.streamId ?: return }
                when (s.cmd) {
                    "offer", "reconnectOffer" -> s.sdp?.let { onOffer(streamId, it) }
                    "joinResponse" -> if (s.args["decision"] != false) (s.args["offer"] as? String)?.let { onOffer(streamId, it) }
                    "iceCandidate" -> {
                        val c = IceCandidate(s.mid ?: "0", s.mLine ?: 0, s.candidate ?: return)
                        val pc = w.pc
                        if (pc?.remoteDescription != null) pc.addIceCandidate(c) else w.pending.add(c)
                    }
                }
            }
            "notifyjoinstreamrequest" -> {
                val clid = g["clid"]?.toIntOrNull() ?: return
                if (g["id"] != shareId || shareId == null) return
                if (g["is_remove"] == "1") dropViewer(clid) else addViewer(clid)
            }
            "notifystreamclientleft" -> {
                if (g["id"] == shareId) g["clid"]?.toIntOrNull()?.let { dropViewer(it) }
            }
            "notifyclientmoved", "notifyclientleftview" -> {
                // Like the desktop app: watching ends when you and the streamer are no longer in the same channel
                val w = watch ?: return
                val here = ownChannel()
                val theirs = channelOf(w.streamerId)
                if (here > 0 && theirs != here) endWatch(text(R.string.stream_ended_left_channel))
            }
            "error" -> {
                val code = g["return_code"].orEmpty()
                if (g["id"] == "0" || !code.startsWith("stream-")) return
                log("Refused: ${g["msg"]} ($code)")
                when {
                    code.startsWith("stream-setup") -> {
                        endShare()
                        update { it.copy(message = text(R.string.share_refused, g["msg"].orEmpty())) }
                    }
                    code.startsWith("stream-join") || code.startsWith("stream-info") -> endWatch(g["msg"]?.ifEmpty { null } ?: text(R.string.stream_open_failed))
                }
            }
        }
    }

    /** The connection to the server ended: everything stops. */
    fun reset() = exec.execute {
        closeWatch(sendLeave = false)
        endShare()
    }

    /** Lets a picture sink show the stream we watch (or stop). */
    fun attach(sink: VideoSink, track: VideoTrack) = exec.execute { track.addSink(sink) }
    fun detach(sink: VideoSink, track: VideoTrack) = exec.execute { try { track.removeSink(sink) } catch (_: Exception) {} }
}
