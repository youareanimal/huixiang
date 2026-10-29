package io.github.korppujauho.pocketts

import android.content.Context
import io.github.korppujauho.pocketts.proto.Chat
import io.github.korppujauho.pocketts.proto.Command
import io.github.korppujauho.pocketts.proto.ChannelEdits
import io.github.korppujauho.pocketts.proto.Commands
import io.github.korppujauho.pocketts.proto.Crypto
import io.github.korppujauho.pocketts.proto.FileTransfer
import io.github.korppujauho.pocketts.proto.MessageText
import io.github.korppujauho.pocketts.proto.ServerAddress
import io.github.korppujauho.pocketts.proto.ServerModel
import io.github.korppujauho.pocketts.proto.TsClient
import io.github.korppujauho.pocketts.UiSounds.Cue
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** The one connection the app has (or not), and what the screen shows about it. */
object Session : TsClient.Listener {
    private const val WRONG_CHANNEL_PASSWORD = "781" // "invalid channel password"
    private const val CLONE_STILL_ON = "521" // "too many clones already connected": our identity is still on
    private const val NO_PERMISSION = "2568" // "insufficient client permissions"
    private const val LEVEL_TOO_LOW = "519" // "could not validate client identity": its extra_msg is the level needed
    private const val AVATAR_TOO_BIG = 2565 // over the server's max avatar file size


    enum class Phase { IDLE, CONNECTING, CONNECTED }

    /** A private conversation's tab: whose (by identity) and their name. */
    data class PrivateTab(val uid: String, val name: String)

    /** A file being saved from the file browser ([upload]: sent to it): [done] bytes of [size]. */
    data class FileDownload(val name: String, val done: Long, val size: Long, val upload: Boolean = false)

    data class UiState(
        val phase: Phase = Phase.IDLE,
        val error: String? = null,
        val address: String = "",
        val serverName: String = "",
        val tree: List<ServerModel.Node> = emptyList(),
        val ownId: Int = 0,
        val ownChannel: Int = 0,
        val talking: Set<Int> = emptySet(),
        val cannotTalk: Set<Int> = emptySet(), // people without the talk power for their channel
        val inputMuted: Boolean = false,
        val outputMuted: Boolean = false,
        val chat: Map<Chat.Key, List<Chat.Entry>> = emptyMap(),
        val privateTabs: List<PrivateTab> = emptyList(),
        val unread: Set<Chat.Key> = emptySet(),
        val pokes: List<Chat.Poke> = emptyList(),
        val server: Map<String, String> = emptyMap(), // virtualserver_* details
        val channelCount: Int = 0,
        val clientCount: Int = 0,
        val volumes: Map<String, Float> = emptyMap(), // per person (by identity), 1 = as is
        val voice: VoiceSettings = VoiceSettings(),
        val micAllowed: Boolean = false, // the microphone permission
        val pttHeld: Boolean = false,
        val badges: Map<Int, List<Long>> = emptyMap(), // per person: their group and own icons
        val serverGroupNames: Map<Int, String> = emptyMap(),
        val channelGroupNames: Map<Int, String> = emptyMap(),
        val serverIcon: Long = 0,
        val sendingPicture: Boolean = false,
        val sendingProgress: Pair<Int, Int>? = null, // sending several pictures: which one (from 1), of how many
        val waitingForOld: Boolean = false, // reconnecting, but the server still has our old connection
        val retryPassword: Int = 0, // a channel whose password was just refused: its password is asked again
        val retryRemember: Boolean = false, // ... and whether that one was to be remembered
        val avatarStatus: String? = null, // the profile picture's last change, as words
        val reconnecting: Int = 0, // the try, while getting a lost connection back (0 = not)
        val stream: StreamEngine.State = StreamEngine.State(),
        val streams: Map<Int, String> = emptyMap(), // running streams: whose and their id
        val away: Boolean = false,
        val awayMessage: String = "",
        val neededLevel: Int = 0, // the server refused our identity's security level: the level it needs
        val download: FileDownload? = null, // a file from the file browser being saved (or one being sent to it)
        val downloadResult: String? = null, // ... and how the last one went, as words
    ) {
        /** Files on this server (for pictures). */
        val serverKey: String get() = address
        val ownChannelName: String get() = findChannel(tree, ownChannel)?.displayName.orEmpty()

        fun client(id: Int): ServerModel.Client? = findClient(tree, id)
        fun channel(id: Int): ServerModel.Channel? = findChannel(tree, id)
        fun clientByUid(uid: String): ServerModel.Client? = findClient(tree) { it.uid == uid && it.id != ownId }

        private companion object {
            fun findChannel(nodes: List<ServerModel.Node>, id: Int): ServerModel.Channel? {
                for (n in nodes) {
                    if (n.channel.id == id) return n.channel
                    findChannel(n.children, id)?.let { return it }
                }
                return null
            }

            fun findClient(nodes: List<ServerModel.Node>, id: Int) = findClient(nodes) { it.id == id }

            fun findClient(nodes: List<ServerModel.Node>, match: (ServerModel.Client) -> Boolean): ServerModel.Client? {
                for (n in nodes) {
                    n.clients.firstOrNull(match)?.let { return it }
                    findClient(n.children, match)?.let { return it }
                }
                return null
            }
        }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private lateinit var app: Context
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "session").apply { isDaemon = true } }
    private var client: TsClient? = null
    @Volatile private var files: FileTransfer? = null
    private var engine: StreamEngine? = null

    /** The stream engine (made once, kept across connections: WebRTC is slow to start). */
    fun streams(): StreamEngine {
        engine?.let { return it }
        return StreamEngine(
            app,
            sendNow = { client?.sendCommand(it) },
            soundPaused = { streamSoundPaused() },
            settings = { _state.value.voice },
            onState = { st ->
                val before = _state.value.stream
                _state.update { it.copy(stream = st) }
                if (before.sharing != st.sharing) refreshService()
            },
        ).also { engine = it }
    }

    /** The service's kinds follow what we do: the microphone, and the camera or the screen while shared. */
    private fun refreshService() {
        if (!::app.isInitialized || _state.value.phase == Phase.IDLE) return
        val kind = _state.value.stream.sharing
        ConnectionService.start(
            app, app.getString(R.string.notification_connected, _state.value.serverName.ifEmpty { _state.value.address }),
            camera = kind == io.github.korppujauho.pocketts.proto.Streams.Kind.CAMERA,
            projection = kind == io.github.korppujauho.pocketts.proto.Streams.Kind.SCREEN,
        )
    }

    fun watch(person: ServerModel.Client) = streams().watch(person, _state.value.streams[person.id])
    fun stopWatching() = engine?.stopWatching()
    fun stopShare() = engine?.stopShare()
    fun flipCamera() = engine?.flipCamera()
    fun setWatchMuted(muted: Boolean) = engine?.setWatchMuted(muted)
    fun setWatchVolume(volume: Float) = engine?.setWatchVolume(volume)

    /** The warning on screen was closed (or its time ran out). */
    fun clearError() {
        _state.update { it.copy(error = null) }
        engine?.clearMessage()
    }

    /** The camera's turn locked or following the phone; remembered for the next share. */
    fun setRotateLock(on: Boolean) {
        updateVoice(_state.value.voice.copy(cameraRotateLock = on))
        engine?.setRotateLock(on)
    }

    fun shareCamera(front: Boolean, quality: Int, sound: Boolean) {
        val name = _state.value.client(_state.value.ownId)?.nickname ?: "Camera"
        if (_state.value.voice.cameraSound != sound) updateVoice(_state.value.voice.copy(cameraSound = sound))
        // The service says "camera" before the camera opens (Android's rule for using it in the background)
        ConnectionService.start(app, app.getString(R.string.notification_sharing_camera), camera = true)
        streams().shareCamera(front, quality, name, sound && micPermitted())
    }

    fun shareBothCameras(quality: Int, sound: Boolean) {
        val name = _state.value.client(_state.value.ownId)?.nickname ?: "Camera"
        if (_state.value.voice.cameraSound != sound) updateVoice(_state.value.voice.copy(cameraSound = sound))
        ConnectionService.start(app, app.getString(R.string.notification_sharing_cameras), camera = true)
        streams().shareBothCameras(quality, name, sound && micPermitted())
    }

    // The camera stream's sound is silent while the microphone is muted or we talk in the channel (else viewers
    // would hear us twice, a moment apart)
    private fun streamSoundPaused(): Boolean {
        val s = _state.value
        return s.inputMuted || !s.micAllowed || s.ownId in s.talking
    }

    /** Whether a screen share carries what the phone plays. */
    fun setScreenSound(on: Boolean) {
        if (_state.value.voice.screenSound != on) updateVoice(_state.value.voice.copy(screenSound = on))
    }

    /** Screen sharing, with Android's permission ([projection]); the service must say "screen" before it starts. */
    fun shareScreen(projection: android.content.Intent, width: Int, height: Int) {
        val name = _state.value.client(_state.value.ownId)?.nickname ?: "Screen"
        val sound = _state.value.voice.screenSound
        // Recording what other apps play needs the microphone's permission too
        ConnectionService.onForeground = { streams().shareScreen(projection, width, height, name, sound && micPermitted()) }
        ConnectionService.start(app, app.getString(R.string.notification_sharing_screen), projection = true)
    }
    private var player: VoicePlayer? = null
    private var sender: MicSender? = null
    private var route: AudioRoute? = null
    @Volatile private var ownCodec = 4 // our channel's (Opus Voice 4 or Opus Music 5)
    // Used on the connection's thread only
    private val model = ServerModel()
    private val chat = Chat()

    // The chat history: the server whose history is loaded (null while none is), and its writes, one at a time
    private val historyLock = Any()
    private var historyServer: String? = null
    private var historySave: java.util.concurrent.ScheduledFuture<*>? = null
    private val historyWriter = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "chat-history").apply { isDaemon = true } }
    private val history by lazy { ChatHistoryStore(java.io.File(app.noBackupFilesDir, "chat")) }

    /** The conversation on screen (null: the chat isn't showing): it gets no unread mark. */
    @Volatile var viewing: Chat.Key? = null
        set(value) {
            field = value
            if (value != null) _state.update { it.copy(unread = it.unread - value) }
        }

    /** The saved settings, for the screens before any connection. */
    fun load(context: Context) {
        app = context.applicationContext
        if (_state.value.phase == Phase.IDLE) _state.update { it.copy(voice = VoiceSettings.load(app), micAllowed = micPermitted()) }
    }

    /** The favourite marked to connect when the app opens has been tried (once per app start). */
    @Volatile var autoConnected = false

    /** Leaves this server and then runs [next] (connecting to another). */
    fun switchTo(next: () -> Unit) {
        if (_state.value.phase == Phase.IDLE) {
            next()
            return
        }
        pendingSwitch = next
        disconnect()
    }

    @Volatile private var pendingSwitch: (() -> Unit)? = null

    /** Whether the app is on screen; away, private messages and pokes also come as notifications. */
    @Volatile var foreground = false
        set(value) {
            field = value
            TalkOverlay.refresh()
            if (!value) flushHistory()
        }

    /** The app is only the small stream window (picture-in-picture). */
    @Volatile var pip = false
        set(value) {
            field = value
            TalkOverlay.refresh()
        }

    /** What the last connect asked for, to get a lost connection back. */
    private data class Target(
        val address: String, val nickname: String, val password: String, val filePort: Int? = null,
        // From a TeamSpeak link: the channel to join first, and a privilege key the person agreed to use (once)
        val channel: String = "", val channelPassword: String = "", val privilegeKey: String = "",
    )
    private var target: Target? = null
    private var retries = 0
    private var oldWaits = 0 // tries refused because the server still had our old connection
    @Volatile private var wasConnected = false // only a connection that worked is tried again
    private val retryTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "reconnect").apply { isDaemon = true } }
    private var retryTask: java.util.concurrent.ScheduledFuture<*>? = null
    private var network: android.net.ConnectivityManager.NetworkCallback? = null

    fun connect(
        context: Context, address: String, nickname: String, serverPassword: String, filePort: Int? = null,
        channel: String = "", channelPassword: String = "", privilegeKey: String = "",
    ) {
        app = context.applicationContext
        if (_state.value.phase != Phase.IDLE) return
        retries = 0
        oldWaits = 0
        wasConnected = false
        target = Target(address, nickname, serverPassword, filePort, channel, channelPassword, privilegeKey)
        start(keepChat = false)
    }

    // The server that refused our security level (see UiState.neededLevel), until we connect to it again or let it be
    @Volatile private var neededLevel = 0
    private var levelTarget: Target? = null

    /** After raising the security level: the server that asked for it again. */
    fun connectAgain(nickname: String? = null) {
        val t = levelTarget ?: return
        levelTarget = null
        _state.update { it.copy(neededLevel = 0) }
        connect(app, t.address, nickname ?: t.nickname, t.password, t.filePort)
    }

    /** The security level wasn't raised (enough): the server's need is let be, and said why we're not connected. */
    fun dismissNeededLevel() {
        levelTarget = null
        _state.update {
            it.copy(neededLevel = 0, error = app.getString(R.string.level_not_connected, it.neededLevel))
        }
    }

    /** Connects to [target]; a reconnect keeps the chat and the pokes. */
    private fun start(keepChat: Boolean) {
        val t = this.target ?: return
        val (address, nickname, serverPassword, filePort) = t
        val typed = ServerAddress.parse(address) ?: run {
            _state.update { it.copy(error = app.getString(R.string.not_an_address)) }
            return
        }
        val prev = _state.value
        if (!keepChat) synchronized(historyLock) { historyServer = null }
        _state.value = UiState(
            phase = Phase.CONNECTING, address = address, inputMuted = prev.inputMuted, outputMuted = prev.outputMuted,
            volumes = Settings(app).volumes(), voice = VoiceSettings.load(app), micAllowed = micPermitted(),
            reconnecting = if (keepChat) retries else 0,
            chat = if (keepChat) prev.chat else emptyMap(), privateTabs = if (keepChat) prev.privateTabs else emptyList(),
            pokes = if (keepChat) prev.pokes else emptyList(), away = prev.away && keepChat, awayMessage = prev.awayMessage,
        )
        watchNetwork()
        CallWatcher.start(app, ::onPhoneCall)
        ConnectionService.start(app, app.getString(R.string.notification_connecting, address))
        worker.execute {
            val identity = try {
                Settings(app).identity() // made on first use, which takes a moment
            } catch (e: Settings.IdentityUnreadable) {
                finish(e.message)
                return@execute
            }
            val target = ServerAddress.resolve(typed, AndroidDns, ServerAddress.MyTeamSpeakNicknames) { AppLog.log(it) }
            AppLog.log("Connecting to ${target.host}:${target.port} (${target.how})")
            model.clear()
            if (!keepChat) {
                chat.clear()
                restoreHistory(address)
            }
            startAudio()
            client = TsClient(this) { AppLog.log(it) }.also {
                files = FileTransfer(it, portOverride = filePort)
                it.connect(
                    TsClient.Options(
                        host = target.host, port = target.port, identity = identity,
                        nickname = nickname.ifBlank { "PocketTS user" }, serverPassword = serverPassword,
                        defaultChannel = t.channel, defaultChannelPassword = t.channelPassword,
                    )
                )
            }
        }
    }

    fun disconnect() {
        target = null // we leave: no reconnecting
        retryTask?.cancel(false)
        if (_state.value.reconnecting > 0 && client == null) {
            finish(null)
            return
        }
        val c = client
        if (c == null) {
            finish(null)
            return
        }
        c.disconnect()
    }

    /** Optional public profile fields arrive through notifyclientupdated; refusal leaves known data usable. */
    fun personDetails(person: ServerModel.Client) {
        val current = _state.value
        if (current.phase != Phase.CONNECTED || current.client(person.id)?.uid != person.uid) return
        client?.sendCommand(Commands.build("clientgetvariables", "clid" to person.id, "return_code" to "quiet-client-details"))
    }

    // The last join asked for, until the server answers it (by its return code)
    private class PendingJoin(val channel: Int, val password: String, val remember: Boolean, val code: String)
    @Volatile private var pendingJoin: PendingJoin? = null
    private var joins = 0

    /**
     * Joins a channel; with [remember], its [password] is saved (encrypted) once the server lets us in. A [quiet] one
     * shows no warning when refused: it's the try without a password, which works for those allowed to skip
     * channel passwords (a refusal then asks for the password).
     */
    fun joinChannel(channelId: Int, password: String = "", remember: Boolean = false, quiet: Boolean = false) {
        val c = client ?: return
        val code = (if (quiet) "quiet-join-" else "join-") + (++joins)
        pendingJoin = PendingJoin(channelId, password, remember, code)
        c.sendCommand(
            Commands.build(
                "clientmove", "cid" to channelId, "clid" to c.clientId, "cpw" to Crypto.hashPassword(password).ifEmpty { null },
                "return_code" to code,
            )
        )
    }

    /** The password remembered for a channel on this server, if any. */
    fun savedChannelPassword(channelId: Int): String? =
        if (::app.isInitialized) ChannelPasswords(app).get(_state.value.address, channelId) else null

    /** The refused channel's password is being asked again. */
    fun passwordAsked() = _state.update { it.copy(retryPassword = 0) }

    fun forgetChannelPasswords() {
        if (::app.isInitialized) ChannelPasswords(app).clear()
    }

    // The server's answer to a join: a password that worked is kept (when asked to); a refused one is forgotten and
    // asked again, so a mistyped or changed password gets replaced
    private fun joinAnswered(join: PendingJoin, cmd: Command) {
        if (!::app.isInitialized) return
        val saved = ChannelPasswords(app)
        val address = _state.value.address
        val id = cmd["id"]
        when {
            id == "0" -> when {
                join.password.isEmpty() -> {}
                join.remember -> saved.put(address, join.channel, join.password)
                else -> saved.remove(address, join.channel) // remembering was turned off
            }
            id == WRONG_CHANNEL_PASSWORD || cmd["msg"].orEmpty().contains("password", ignoreCase = true) -> {
                saved.remove(address, join.channel)
                _state.update { it.copy(retryPassword = join.channel, retryRemember = join.remember) }
            }
            // The quiet try's other refusals (a full channel, no permission) are shown as any other
            join.code.startsWith("quiet-") -> {
                AppLog.log("Server error $id: ${cmd["msg"]}")
                _state.update { it.copy(error = cmd["msg"]) }
            }
        }
    }

    /** Our microphone on/off, as the others see it. */
    fun toggleMic() = setMic(!_state.value.inputMuted, cue = true)

    private fun setMic(muted: Boolean, cue: Boolean) {
        if (_state.value.inputMuted == muted) return
        _state.update { it.copy(inputMuted = muted) }
        if (cue) UiSounds.play(if (muted) Cue.MIC_OFF else Cue.MIC_ON, _state.value.voice, _state.value.outputMuted)
        sender?.muted = muted
        engine?.refreshSound()
        client?.sendCommand(Commands.build("clientupdate", "client_input_muted" to muted))
        if (::app.isInitialized) ConnectionService.refresh(app) // the notification's buttons
    }

    // ====== Phone calls ======

    /**
     * What a phone call turned off, to turn back on after it: the microphone, the sound, a watched stream's sound, and
     * our share (paused: viewers don't see the call's screen, with who is calling, or the phone at your ear).
     */
    private data class CallMuted(val mic: Boolean, val sound: Boolean, val stream: Boolean, val share: Boolean)

    @Volatile private var callMuted: CallMuted? = null // null: no call

    /** A phone call rang or started ([active]), or ended: quiet during it, as before after it. */
    private fun onPhoneCall(active: Boolean) {
        if (!active) return endCall()
        val s = _state.value
        if (callMuted != null || s.phase == Phase.IDLE || !s.voice.muteOnCalls) return
        val stream = s.stream.watching != 0 && !s.stream.watchMuted
        val share = s.stream.sharing != null && !s.stream.sharePaused
        callMuted = CallMuted(mic = !s.inputMuted, sound = !s.outputMuted, stream = stream, share = share)
        AppLog.log("Phone call: microphone and sound off${if (stream) ", and the stream's sound" else ""}${if (share) ", share paused" else ""}")
        // The share first: the call's screen comes up right away (and a camera would show the phone at your ear)
        if (share) engine?.setSharePaused(true)
        setMic(true, cue = false)
        setSound(true, cue = false)
        if (stream) setWatchMuted(true)
    }

    /** The call ended (or the connection did): what the call turned off comes back; what you turned off stays off. */
    private fun endCall() {
        val m = callMuted ?: return
        callMuted = null
        AppLog.log("Phone call over: microphone and sound as before")
        if (m.mic) setMic(false, cue = false)
        if (m.sound) setSound(false, cue = false)
        // The same stream still on (a new one starts with its sound on anyway)
        if (m.stream && _state.value.stream.watching != 0) setWatchMuted(false)
        if (m.share) engine?.setSharePaused(false)
    }

    // ====== Talking ======

    private fun micPermitted() = ::app.isInitialized &&
        app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** The microphone permission was answered. */
    fun micPermission(context: Context, granted: Boolean) {
        app = context.applicationContext
        _state.update { it.copy(micAllowed = granted) }
        if (granted && _state.value.phase != Phase.IDLE) {
            // Now the service may use the microphone in the background too
            ConnectionService.start(app, app.getString(R.string.notification_connected, _state.value.serverName.ifEmpty { _state.value.address }))
            if (sender == null && player != null) startSender()
        }
    }

    /** A new nickname, now (also kept for next time by the caller). */
    fun setNickname(name: String) {
        client?.sendCommand(Commands.build("clientupdate", "client_nickname" to name))
    }

    /** Push-to-talk held or let go (the button, or a volume button). */
    fun setPtt(held: Boolean) {
        sender?.pttHeld = held
        if (_state.value.pttHeld != held) _state.update { it.copy(pttHeld = held) }
    }

    /** The microphone's level and whether it's "on", for the settings' meter. */
    fun micLevel(): Pair<Float, Boolean> = sender?.let { it.level to it.active } ?: (0f to false)

    /** Measuring only (settings open): nothing is sent. */
    fun micTest(on: Boolean) {
        sender?.testing = on
    }

    fun updateVoice(v: VoiceSettings) {
        val old = _state.value.voice
        v.save(app)
        _state.update { it.copy(voice = v) }
        player?.voiceGain = v.voiceGain
        if (v.activation != VoiceSettings.Activation.PTT) setPtt(false)
        if (_state.value.phase == Phase.IDLE) return
        if (v.echoCancellation != old.echoCancellation) {
            // Call audio or media: playback and the microphone start again
            worker.execute { stopAudio(); startAudio() }
        } else if (v.noiseSuppression != old.noiseSuppression || v.autoGainControl != old.autoGainControl) {
            sender?.reconfigure()
        }
    }

    private fun startAudio() {
        val v = _state.value.voice
        if (v.echoCancellation) route = AudioRoute(app).also { it.start() }
        player = VoicePlayer(v.echoCancellation, ::setTalking).also {
            it.muted = _state.value.outputMuted
            it.voiceGain = v.voiceGain
        }
        updateGains()
        if (micPermitted()) startSender()
    }

    private fun startSender() {
        sender = MicSender(
            settings = { _state.value.voice },
            canSend = { _state.value.phase == Phase.CONNECTED && _state.value.ownId !in _state.value.cannotTalk },
            send = { client?.sendVoice(ownCodec, it) },
            sendStop = { client?.sendVoiceStop(ownCodec) },
            onTalking = { talking -> _state.value.ownId.takeIf { it > 0 }?.let { setTalking(it, talking) } },
        ).also { it.muted = _state.value.inputMuted }
    }

    private fun stopAudio() {
        sender?.release()
        sender = null
        player?.release()
        player = null
        route?.stop()
        route = null
    }

    /** Our sound on/off: nothing is played, and the others see it. */
    fun toggleSound() = setSound(!_state.value.outputMuted, cue = true)

    private fun setSound(muted: Boolean, cue: Boolean) {
        if (_state.value.outputMuted == muted) return
        _state.update { it.copy(outputMuted = muted) }
        if (cue) UiSounds.play(if (muted) Cue.SOUND_OFF else Cue.SOUND_ON, _state.value.voice, muted)
        player?.muted = muted
        client?.sendCommand(Commands.build("clientupdate", "client_output_muted" to muted))
        if (::app.isInitialized) ConnectionService.refresh(app) // the notification's buttons
    }

    // ====== Chat ======

    /** Sends a message to a conversation: our channel, the server, or a person (who must be on the server). */
    fun send(key: Chat.Key, text: String) {
        val c = client ?: return
        val msg = text.trim().take(1024)
        if (msg.isEmpty()) return
        when (key) {
            is Chat.Key.Channel -> c.sendCommand(Commands.build("sendtextmessage", "targetmode" to 2, "target" to key.id, "msg" to msg))
            is Chat.Key.Private -> c.post {
                val person = model.clients.values.firstOrNull { it.uid == key.uid && it.id != model.ownClientId } ?: run {
                    _state.update { it.copy(error = chat.privateNames[key.uid]?.let { n -> app.getString(R.string.private_left, n) } ?: app.getString(R.string.private_left_unknown)) }
                    return@post
                }
                chat.sendingPrivate(person.id, key.uid)
                c.sendCommand(Commands.build("sendtextmessage", "targetmode" to 1, "target" to person.id, "msg" to msg))
            }
        }
    }

    /** Opens a private conversation with someone (their tab). */
    fun openPrivate(person: ServerModel.Client) {
        val c = client ?: return
        c.post {
            chat.open(person.uid, person.nickname)
            publishChat()
        }
    }

    fun closePrivate(uid: String) {
        client?.post {
            chat.close(uid)
            publishChat()
        } ?: _state.update { s -> s.copy(privateTabs = s.privateTabs.filter { it.uid != uid }) }
    }

    fun poke(person: ServerModel.Client, text: String) {
        client?.sendCommand(Commands.build("clientpoke", "clid" to person.id, "msg" to text.trim().take(100)))
    }

    fun dismissPoke(id: Long) = _state.update { s -> s.copy(pokes = s.pokes.filter { it.id != id }) }

    // ====== Admin: moving, kicking and banning people, and changing channels (the server decides who may) ======

    /** An admin action: what the log calls it, and its messages when the server says no (%1$s: the person or channel). */
    private enum class AdminAction(val log: String, val noPermission: Int, val failed: Int) {
        MOVE("move", R.string.admin_no_permission_move, R.string.admin_failed_move),
        KICK_CHANNEL("kick from the channel", R.string.admin_no_permission_kick_channel, R.string.admin_failed_kick_channel),
        KICK_SERVER("kick from the server", R.string.admin_no_permission_kick_server, R.string.admin_failed_kick_server),
        BAN("ban", R.string.admin_no_permission_ban, R.string.admin_failed_ban),
        CREATE_CHANNEL("create the channel", R.string.channel_no_permission_create, R.string.channel_failed_create),
        EDIT_CHANNEL("change the channel", R.string.channel_no_permission_edit, R.string.channel_failed_edit),
        REORDER_CHANNEL("move the channel", R.string.channel_no_permission_reorder, R.string.channel_failed_reorder),
        DELETE_CHANNEL("delete the channel", R.string.channel_no_permission_delete, R.string.channel_failed_delete),
    }

    // Asked and not answered yet: the return code -> what it was, and to whom
    private val adminAsked = java.util.concurrent.ConcurrentHashMap<String, Pair<AdminAction, String>>()
    private val adminCount = java.util.concurrent.atomic.AtomicInteger()

    private fun admin(action: AdminAction, person: ServerModel.Client, name: String, vararg params: Pair<String, Any?>) =
        admin(action, person.nickname, name, *params)

    private fun admin(action: AdminAction, who: String, name: String, vararg params: Pair<String, Any?>) {
        val c = client ?: return
        val code = "admin-${adminCount.incrementAndGet()}"
        adminAsked[code] = action to who
        c.sendCommand(Commands.build(name, *params, "return_code" to code))
    }

    private fun admin(action: AdminAction, who: String, edit: ChannelEdits.Edit) = admin(action, who, edit.name, *edit.params.toTypedArray())

    /** A new channel. A temporary one is entered at once (the server moves its maker in, or it would be gone). */
    fun createChannel(s: ChannelEdits.Settings) = admin(AdminAction.CREATE_CHANNEL, s.name.trim(), ChannelEdits.create(s))

    /** The channel changed to [s]: only what differs is sent. */
    fun editChannel(ch: ServerModel.Channel, s: ChannelEdits.Settings) {
        for (e in ChannelEdits.edit(ch, s)) admin(AdminAction.EDIT_CHANNEL, ch.displayName, e)
    }

    /** The channel to come after [after] (0 = first) among its siblings. */
    fun reorderChannel(ch: ServerModel.Channel, after: Int) = admin(AdminAction.REORDER_CHANNEL, ch.displayName, ChannelEdits.reorder(ch, after))

    /** Deleted with its sub-channels; the people in them go to the default channel. */
    fun deleteChannel(ch: ServerModel.Channel) = admin(AdminAction.DELETE_CHANNEL, ch.displayName, ChannelEdits.delete(ch))

    /** Asks for a channel's description, which the server sends only when asked (it arrives as a channel edit). */
    fun channelDescription(id: Int) {
        val e = ChannelEdits.description(id)
        client?.sendCommand(Commands.build(e.name, *e.params.toTypedArray(), "return_code" to "quiet-description"))
    }

    /** Into another channel. Admins need no password there; a saved one goes along for a server that still asks. */
    fun moveClient(person: ServerModel.Client, channelId: Int) = admin(
        AdminAction.MOVE, person, "clientmove", "clid" to person.id, "cid" to channelId,
        "cpw" to savedChannelPassword(channelId)?.let { Crypto.hashPassword(it) }?.ifEmpty { null },
    )

    /** Out of their channel (into the default one), or off the server; the reason is shown to them. */
    fun kick(person: ServerModel.Client, fromServer: Boolean, reason: String) = admin(
        if (fromServer) AdminAction.KICK_SERVER else AdminAction.KICK_CHANNEL, person,
        "clientkick", "clid" to person.id, "reasonid" to if (fromServer) 5 else 4, "reasonmsg" to reason.trim().take(REASON_MAX).ifEmpty { null },
    )

    /**
     * Banned for [seconds] (0: for good), as the TeamSpeak app bans: the server bans both their identity and their
     * address (so a new identity doesn't get them back in) and removes them at once.
     */
    fun ban(person: ServerModel.Client, seconds: Long, reason: String) = admin(
        AdminAction.BAN, person, "banclient", "clid" to person.id, "time" to seconds,
        "banreason" to reason.trim().take(REASON_MAX).ifEmpty { null },
    )

    /** The longest reason given with a kick or a ban (TeamSpeak's limit). */
    const val REASON_MAX = 40

    private fun adminAnswered(asked: Pair<AdminAction, String>, cmd: Command) {
        val (action, who) = asked
        val id = cmd["id"] ?: return
        if (id == "0") return AppLog.log("Admin: ${action.log} $who")
        AppLog.log("Admin: couldn't ${action.log} $who, error $id: ${cmd["msg"]} (permission ${cmd["failed_permid"]})")
        val message = when (id) {
            NO_PERMISSION -> app.getString(action.noPermission, who)
            WRONG_CHANNEL_PASSWORD -> app.getString(R.string.admin_move_needs_password, who)
            else -> app.getString(action.failed, who, cmd["msg"].orEmpty())
        }
        _state.update { it.copy(error = message) }
    }

    // ====== Chat history ======

    /** The server's chat from before, into the chat (before the connection starts, on the worker). */
    private fun restoreHistory(address: String) {
        val server = io.github.korppujauho.pocketts.proto.ChatHistory.serverKey(address) ?: return
        val saved = history.load(server, System.currentTimeMillis())
        if (saved.isNotEmpty()) {
            chat.restore(saved)
            publishChat()
            AppLog.log("Chat history: ${saved.values.sumOf { it.size }} lines from before")
        }
        synchronized(historyLock) { historyServer = server }
    }

    /** New lines are written together, at most every few seconds (and when the connection ends). */
    private fun saveHistorySoon(delaySeconds: Long = 10) {
        synchronized(historyLock) {
            if (historyServer == null || historySave?.isDone == false) return
            historySave = historyWriter.schedule({ saveHistory() }, delaySeconds, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private fun saveHistory() {
        val (server, lines) = synchronized(historyLock) { (historyServer ?: return) to _state.value.chat }
        history.save(server, lines, System.currentTimeMillis())
    }

    /** Now, and no more for this server (the connection ended). Before the chat on screen is emptied. */
    private fun closeHistory() {
        synchronized(historyLock) {
            val server = historyServer ?: return
            val lines = _state.value.chat
            historyServer = null
            historySave?.cancel(false)
            historyWriter.execute { history.save(server, lines, System.currentTimeMillis()) }
        }
    }

    /** Settings › Clear chat history: every server's, and the lines on screen now. */
    fun clearChatHistory(context: Context) {
        app = context.applicationContext
        val c = client
        if (c != null) {
            c.post {
                chat.clearLines()
                publishChat()
                historyWriter.execute { history.clear() } // after any write of the lines just cleared
            }
        } else {
            // Between connections, or waiting to reconnect: the chat is the worker's then
            worker.execute {
                chat.clearLines()
                publishChat()
                historyWriter.execute { history.clear() }
            }
        }
        AppLog.log("Chat history cleared")
    }

    /** Leaving the app: lines waiting to be written go now (Android may stop the app later). */
    private fun flushHistory() {
        synchronized(historyLock) {
            val pending = historySave ?: return
            if (pending.isDone || !pending.cancel(false)) return
            historySave = historyWriter.schedule({ saveHistory() }, 0, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    /** How loud someone is for us (0 to 2), kept by their identity. */
    fun setVolume(person: ServerModel.Client, volume: Float) {
        if (person.uid.isEmpty()) return
        val v = volume.coerceIn(0f, 2f)
        _state.update { it.copy(volumes = it.volumes + (person.uid to v)) }
        if (::app.isInitialized) Settings(app).setVolume(person.uid, v)
        updateGains()
    }

    // ====== From the connection (its own thread) ======

    override fun onConnected(clientId: Int) {
        AppLog.log("Connected as client $clientId")
        val s = _state.value
        retries = 0
        oldWaits = 0
        if (s.waitingForOld) _state.update { it.copy(waitingForOld = false) }
        wasConnected = true
        if (s.reconnecting > 0) _state.update { it.copy(reconnecting = 0) }
        if (s.away) client?.sendCommand(Commands.build("clientupdate", "client_away" to true, "client_away_message" to s.awayMessage))
        if (s.inputMuted || s.outputMuted) {
            client?.sendCommand(Commands.build("clientupdate", "client_input_muted" to s.inputMuted, "client_output_muted" to s.outputMuted))
        }
        // A link's privilege key the person agreed to use: once (a key works only once anyway); a refusal shows as an error
        target?.takeIf { it.privilegeKey.isNotEmpty() }?.let { t ->
            target = t.copy(privilegeKey = "")
            AppLog.log("Using the link's privilege key")
            client?.sendCommand(Commands.build("privilegekeyuse", "token" to t.privilegeKey, "return_code" to "token"))
        }
        // The groups (for their icons) and the rest of the server's info; refused ones just stay out
        for ((i, cmd) in listOf("servergrouplist", "channelgrouplist", "servergetvariables").withIndex()) {
            client?.sendCommand(Commands.build(cmd, "return_code" to "quiet-$i"))
        }
        publish(Phase.CONNECTED)
        ConnectionService.start(app, app.getString(R.string.notification_connected, model.serverName.ifEmpty { _state.value.address }))
        kicked = false
        soundsFrom = System.currentTimeMillis() + 3000
        UiSounds.play(Cue.CONNECTED, s.voice, s.outputMuted)
    }

    // ====== Sounds for events ======

    // Quiet for the first seconds after connecting, while the server lists who is already there (as in WebTS)
    @Volatile private var soundsFrom = 0L
    @Volatile private var kicked = false

    private fun sound(cue: Cue) {
        if (System.currentTimeMillis() < soundsFrom) return
        UiSounds.play(cue, _state.value.voice, _state.value.outputMuted)
    }

    /** The sounds a command brings: read before the model takes it in (it still knows where everyone was). */
    private fun eventCues(cmd: Command): List<Cue> {
        val me = model.ownClientId
        val here = model.ownClient?.channel ?: 0
        val out = ArrayList<Cue>()
        var reason = 0
        fun person(id: Int) = model.clients[id]?.takeIf { it.type == 0 }
        when (cmd.name) {
            "notifyclientmoved" -> for (g in cmd.groups) {
                g["reasonid"]?.toIntOrNull()?.let { reason = it }
                val clid = g["clid"]?.toIntOrNull() ?: continue
                val to = g["ctid"]?.toIntOrNull() ?: continue
                if (clid == me) {
                    // Switched yourself, moved by someone, or kicked from the channel
                    out += when (reason) { 1 -> Cue.MOVED; 4 -> Cue.KICKED; else -> Cue.CHANNEL_SWITCHED }
                } else {
                    val p = person(clid) ?: continue
                    if (to == here && here > 0) out += Cue.SOMEONE_JOINED
                    else if (p.channel == here) out += Cue.SOMEONE_LEFT
                }
            }
            // Connected into your channel, or came from a channel you don't see
            "notifycliententerview" -> for (g in cmd.groups) {
                val clid = g["clid"]?.toIntOrNull() ?: continue
                if (clid == me || g["client_type"] == "1") continue
                if (g["ctid"]?.toIntOrNull() == here && here > 0) out += Cue.SOMEONE_JOINED
            }
            // Left the server from your channel; or you were kicked or banned
            "notifyclientleftview" -> for (g in cmd.groups) {
                g["reasonid"]?.toIntOrNull()?.let { reason = it }
                val clid = g["clid"]?.toIntOrNull() ?: continue
                if (clid == me) {
                    if (reason == 5 || reason == 6) { kicked = true; out += Cue.KICKED }
                } else if (person(clid)?.channel == here) out += Cue.SOMEONE_LEFT
            }
            // A stream in your channel (yours too) starts or stops
            "notifystreamstarted", "notifystreamstopped" -> {
                val g = cmd.params
                val id = g["id"] ?: g["stream_id"]
                val clid = g["clid"]?.toIntOrNull()?.takeIf { it > 0 } ?: model.streams.entries.firstOrNull { it.value == id }?.key ?: 0
                if (clid > 0 && (clid == me || model.clients[clid]?.channel == here)) {
                    out += if (cmd.name == "notifystreamstarted") Cue.STREAM_STARTED else Cue.STREAM_STOPPED
                }
            }
        }
        return out.distinct()
    }

    // ====== The file browser ======

    // Its own threads: a big download mustn't hold up the session's work (or the pictures)
    private val fileWork = Executors.newFixedThreadPool(2) { r -> Thread(r, "files").apply { isDaemon = true } }
    @Volatile private var downloadCancelled = false

    // A password channel's files need its password, hashed as when joining: the remembered one
    private fun filePassword(cid: Int) = savedChannelPassword(cid)?.let { Crypto.hashPassword(it) }.orEmpty()

    /** Why a channel's files couldn't be read, saved or [changed], in the app's language. */
    private fun fileProblem(e: Exception, changed: Boolean = false): String = when ((e as? FileTransfer.Error)?.code) {
        FileTransfer.WRONG_PASSWORD -> app.getString(R.string.files_needs_password)
        FileTransfer.NO_PERMISSION -> app.getString(if (changed) R.string.files_no_permission_change else R.string.files_no_permission)
        FileTransfer.FILE_NOT_FOUND, FileTransfer.INVALID_PATH -> app.getString(if (changed) R.string.files_gone else R.string.files_not_found)
        FileTransfer.FILE_EXISTS -> app.getString(R.string.files_exists)
        else -> e.message.orEmpty()
    }

    /** A folder of channel [cid]'s files ("/" for the top); [done] gets them, or why not, on a background thread. */
    fun listFiles(cid: Int, path: String, done: (Result<List<FileTransfer.Entry>>) -> Unit) {
        val f = files ?: return done(Result.failure(IllegalStateException(app.getString(R.string.files_not_connected))))
        fileWork.execute {
            done(
                try {
                    Result.success(f.list(cid, path, filePassword(cid)))
                } catch (e: Exception) {
                    AppLog.log("File list of $path in channel $cid failed: ${e.message}")
                    Result.failure(IllegalStateException(fileProblem(e)))
                },
            )
        }
    }

    /** Saves [path] from channel [cid]'s files into [target] (where the person chose, through Android's Save window). */
    fun downloadFile(context: Context, cid: Int, path: String, target: android.net.Uri) {
        val f = files ?: return
        val name = path.substringAfterLast('/')
        downloadCancelled = false
        _state.update { it.copy(download = FileDownload(name, 0, 0), downloadResult = null) }
        fileWork.execute {
            var lastShown = 0L
            val result = try {
                val out = context.contentResolver.openOutputStream(target, "w") ?: throw java.io.IOException("no file")
                val size = out.use { o ->
                    f.download(
                        cid, path, o, filePassword(cid),
                        progress = { done, size ->
                            // At most a few times a second: the screen needn't follow every piece
                            val now = System.nanoTime()
                            if (done == size || now - lastShown > 200_000_000L) {
                                lastShown = now
                                _state.update { it.copy(download = FileDownload(name, done, size)) }
                            }
                        },
                        cancelled = { downloadCancelled },
                    )
                }
                AppLog.log("Saved a file of ${size / 1024} KB from the channel's files")
                app.getString(R.string.files_saved, name)
            } catch (e: FileTransfer.Cancelled) {
                // Half a file isn't kept
                try { android.provider.DocumentsContract.deleteDocument(context.contentResolver, target) } catch (_: Exception) {}
                app.getString(R.string.files_download_cancelled, name)
            } catch (e: Exception) {
                AppLog.log("Saving a file failed: ${e.message}")
                try { android.provider.DocumentsContract.deleteDocument(context.contentResolver, target) } catch (_: Exception) {}
                app.getString(R.string.files_download_failed, name, fileProblem(e))
            }
            _state.update { it.copy(download = null, downloadResult = result) }
        }
    }

    fun cancelDownload() {
        downloadCancelled = true
    }

    /** The last download's result was seen. */
    fun downloadResultSeen() = _state.update { it.copy(downloadResult = null) }

    /**
     * Sends [size] bytes of [source] (a file the person picked) as [path] in channel [cid]'s files; [overwrite] replaces
     * a file already there (the person said yes). Shown like a download, and stopped the same way.
     */
    fun uploadFile(context: Context, cid: Int, path: String, source: android.net.Uri, size: Long, overwrite: Boolean) {
        val f = files ?: return
        val name = path.substringAfterLast('/')
        downloadCancelled = false
        _state.update { it.copy(download = FileDownload(name, 0, size, upload = true), downloadResult = null) }
        fileWork.execute {
            var lastShown = 0L
            val result = try {
                val input = context.contentResolver.openInputStream(source) ?: throw java.io.IOException("no file")
                input.use { i ->
                    f.upload(
                        cid, path, i, size, filePassword(cid), overwrite,
                        progress = { done, all ->
                            val now = System.nanoTime()
                            if (done == all || now - lastShown > 200_000_000L) {
                                lastShown = now
                                _state.update { it.copy(download = FileDownload(name, done, all, upload = true)) }
                            }
                        },
                        cancelled = { downloadCancelled },
                    )
                }
                AppLog.log("Sent a file of ${size / 1024} KB to the channel's files")
                app.getString(R.string.files_uploaded, name)
            } catch (e: FileTransfer.Cancelled) {
                app.getString(R.string.files_upload_cancelled, name)
            } catch (e: Exception) {
                AppLog.log("Sending a file failed: ${e.message}")
                app.getString(R.string.files_upload_failed, name, fileProblem(e, changed = true))
            }
            _state.update { it.copy(download = null, downloadResult = result) }
        }
    }

    /** One change to channel [cid]'s files on the file threads; [done] gets null when it went, or why not. */
    private fun changeFiles(cid: Int, what: String, done: (String?) -> Unit, change: (FileTransfer, String) -> Unit) {
        val f = files ?: return done(app.getString(R.string.files_not_connected))
        fileWork.execute {
            done(
                try {
                    change(f, filePassword(cid))
                    null
                } catch (e: Exception) {
                    AppLog.log("$what in channel $cid failed: ${e.message}")
                    fileProblem(e, changed = true)
                },
            )
        }
    }

    /** Deletes [path] (a folder with everything in it) from channel [cid]'s files. */
    fun deleteFile(cid: Int, path: String, done: (String?) -> Unit) =
        changeFiles(cid, "Deleting a file", done) { f, cpw -> f.delete(cid, path, cpw) }

    /** Renames [from] to [to] in channel [cid]'s files (a name already there is replaced: ask first). */
    fun renameFile(cid: Int, from: String, to: String, done: (String?) -> Unit) =
        changeFiles(cid, "Renaming a file", done) { f, cpw -> f.rename(cid, from, to, cpw) }

    /** A new folder [path] in channel [cid]'s files. */
    fun createFolder(cid: Int, path: String, done: (String?) -> Unit) =
        changeFiles(cid, "Making a folder", done) { f, cpw -> f.createDirectory(cid, path, cpw) }

    // ====== Files: pictures, avatars, icons ======

    /** A file from the server's files (blocking; for pictures on screen). */
    fun downloadFile(server: String, cid: Int, path: String, maxBytes: Long): ByteArray {
        val f = files
        if (f == null || server != _state.value.serverKey || _state.value.phase != Phase.CONNECTED) throw IllegalStateException("not connected")
        return f.download(cid, path, maxBytes)
    }

    /**
     * Sends a picture into our channel's chat: into the channel's files (/PocketTS), then one message with the picture and
     * [text] (if any) under it, as the TeamSpeak app sends them.
     */
    /**
     * Pictures to the channel, one message each in the order chosen (as the TeamSpeak app shows them), the words with
     * the first. A picture that fails stops the rest (the reason, like no permission, would stop them too).
     */
    fun sendPictures(context: Context, uris: List<android.net.Uri>, text: String = "") {
        val c = client ?: return
        val f = files ?: return
        if (uris.isEmpty()) return
        val cid = _state.value.ownChannel
        _state.update { it.copy(sendingPicture = true, sendingProgress = if (uris.size > 1) 1 to uris.size else null) }
        worker.execute {
            var n = 0
            // Which one failed, when there were several
            fun notSent(why: String) = if (uris.size > 1) app.getString(R.string.pictures_not_sent, n, uris.size, why) else app.getString(R.string.picture_not_sent, why)
            try {
                f.createDirectory(cid, io.github.korppujauho.pocketts.proto.Pictures.PICTURE_FOLDER)
                for (uri in uris) {
                    n++
                    if (uris.size > 1) _state.update { it.copy(sendingProgress = n to uris.size) }
                    val p = ServerFiles.prepareChatPicture(context, uri)
                    val name = io.github.korppujauho.pocketts.proto.Pictures.pictureName(p.type, java.util.UUID.randomUUID().toString().take(6))
                    f.upload(cid, "${io.github.korppujauho.pocketts.proto.Pictures.PICTURE_FOLDER}/$name", p.bytes)
                    val picture = io.github.korppujauho.pocketts.proto.Pictures.pictureMessage(name, cid)
                    val words = if (n == 1) text.trim().take(1000 - picture.length) else ""
                    val msg = if (words.isEmpty()) picture else "$picture\n$words"
                    c.sendCommand(Commands.build("sendtextmessage", "targetmode" to 2, "target" to cid, "msg" to msg))
                    AppLog.log("Sent a picture (${p.bytes.size / 1024} KB)")
                }
            } catch (e: FileTransfer.Error) {
                _state.update { it.copy(error = notSent(if (e.code == 2568) app.getString(R.string.picture_no_permission, e.message.orEmpty()) else e.message.orEmpty())) }
            } catch (e: Exception) {
                _state.update { it.copy(error = notSent(e.message.orEmpty())) }
            } finally {
                _state.update { it.copy(sendingPicture = false, sendingProgress = null) }
            }
        }
    }

    /** A new profile picture: uploaded as the server's /avatar, then everyone is told (its MD5). */
    fun setAvatar(context: Context, uri: android.net.Uri) {
        val c = client ?: return
        val f = files ?: return
        _state.update { it.copy(avatarStatus = app.getString(R.string.avatar_uploading)) }
        worker.execute {
            try {
                // Over the server's size limit, the next smaller one is tried (as the TeamSpeak app asks for a smaller one)
                val versions = ServerFiles.prepareAvatar(context, uri)
                var sent: ByteArray? = null
                for (data in versions) {
                    try {
                        f.upload(0, io.github.korppujauho.pocketts.proto.Pictures.AVATAR_UPLOAD_NAME, data, overwrite = true)
                        sent = data
                        break
                    } catch (e: FileTransfer.Error) {
                        if (e.code != AVATAR_TOO_BIG || data === versions.last()) throw e
                        AppLog.log("Profile picture of ${data.size / 1024} KB too big for the server, trying a smaller one")
                    }
                }
                val data = sent ?: return@execute
                c.sendCommand(Commands.build("clientupdate", "client_flag_avatar" to io.github.korppujauho.pocketts.proto.Pictures.avatarFlag(data)))
                AppLog.log("Profile picture set (${data.size / 1024} KB)")
                _state.update { it.copy(avatarStatus = app.getString(R.string.avatar_set)) }
            } catch (e: FileTransfer.Error) {
                _state.update {
                    it.copy(avatarStatus = when (e.code) {
                        AVATAR_TOO_BIG -> app.getString(R.string.avatar_too_big)
                        2568 -> app.getString(R.string.avatar_not_allowed)
                        else -> app.getString(R.string.avatar_failed, e.message.orEmpty())
                    })
                }
            } catch (e: Exception) {
                _state.update { it.copy(avatarStatus = app.getString(R.string.avatar_failed, e.message.orEmpty())) }
            }
        }
    }

    /** No profile picture: the flag is cleared, so nobody shows it (the file itself can't be deleted). */
    fun removeAvatar() {
        client?.sendCommand(Commands.build("clientupdate", "client_flag_avatar" to ""))
        _state.update { it.copy(avatarStatus = app.getString(R.string.avatar_removed)) }
    }

    override fun onCommand(cmd: Command) {
        when (cmd.name) {
            // See everyone on the server, not only our own channel; a new channel gets subscribed too.
            "channellistfinished" -> client?.sendCommand("channelsubscribeall")
            "notifychannelcreated" -> client?.sendCommand(Commands.build("channelsubscribe", "cid" to cmd["cid"]))
            "error" -> {
                // Refused while connecting: our identity's security level is under the server's
                if (cmd["id"] == LEVEL_TOO_LOW && _state.value.phase == Phase.CONNECTING) {
                    neededLevel = cmd["extra_msg"]?.toIntOrNull() ?: 0
                }
                pendingJoin?.takeIf { it.code == cmd["return_code"] }?.let {
                    pendingJoin = null
                    joinAnswered(it, cmd)
                }
                cmd["return_code"]?.let { adminAsked.remove(it) }?.let { adminAnswered(it, cmd) }
                if (cmd["id"] != "0" && cmd["return_code"]?.let { it.startsWith("ft-") || it.startsWith("quiet-") || it.startsWith("admin-") } != true) {
                    AppLog.log("Server error ${cmd["id"]}: ${cmd["msg"]}")
                    if (_state.value.phase == Phase.CONNECTED) _state.update { it.copy(error = cmd["msg"]) }
                }
            }
        }
        files?.onCommand(cmd)
        engine?.onCommand(cmd, model.ownClientId, { _state.value.ownChannel }, { id -> _state.value.client(id)?.channel })
        val cues = eventCues(cmd)
        val result = chat.handle(cmd, model) // before the model: a move's line needs where they were
        val changed = model.apply(cmd)
        result.poke?.let { poked(it) }
        val message = result.message
        cues.forEach { sound(it) }
        if (result.poke != null) sound(Cue.POKE)
        if (message != null) sound(if (message.second.outgoing) Cue.MESSAGE_OUT else Cue.MESSAGE_IN)
        if (result.changed.isNotEmpty() || message != null) {
            val view = viewing
            // Unread: a message in a conversation not on screen (not our own, not join and leave lines)
            if (message != null && !message.second.outgoing && message.first != view) _state.update { it.copy(unread = it.unread + message.first) }
            if (message != null && !message.second.outgoing) messageArrived(message.first, message.second)
            publishChat()
            saveHistorySoon()
        }
        if (changed) {
            publish(_state.value.phase)
            if (cmd.name.startsWith("notifycliententerview") || cmd.name == "notifyclientleftview") updateGains()
        }
    }

    override fun onVoice(voice: TsClient.Voice) {
        player?.onVoice(voice)
    }

    override fun onDisconnected(reason: String?) {
        AppLog.log("Disconnected" + (reason?.let { ": $it" } ?: ""))
        val text = endText(client, reason)
        // The server still has our old connection, which it drops only after a while (the same identity may be on
        // once): tried again every few seconds until it lets go, for up to two minutes
        val oldStillThere = client?.refusal == CLONE_STILL_ON && oldWaits < 40
        // Lost (no answer, or the network itself went: a failed try while reconnecting counts too)
        val lost = oldStillThere || client?.lostConnection == true || _state.value.reconnecting > 0
        if (lost && (wasConnected || oldStillThere) && target != null && (retries < 20 || oldStillThere)) {
            // The network went away (Wi-Fi to mobile data, a tunnel...): try again, sooner when a network comes back
            files?.onDisconnected()
            files = null
            engine?.reset()
            stopAudio()
            client = null
            if (oldStillThere) {
                oldWaits++
                if (retries == 0) retries = 1
                AppLog.log("The server still has the old connection; trying again shortly")
            } else {
                retries++
                if (retries == 1) sound(Cue.CONNECTION_LOST)
            }
            _state.update {
                it.copy(
                    phase = Phase.CONNECTING, reconnecting = retries, waitingForOld = oldStillThere,
                    error = if (oldStillThere) null else text, talking = emptySet(),
                )
            }
            ConnectionService.start(app, app.getString(R.string.notification_reconnecting, _state.value.address))
            scheduleRetry(if (oldStillThere) 3L else minOf(30L, 2L * retries))
            return
        }
        finish(text)
    }

    /** Why the connection ended, in the phone's language; the client's English when it can't say more. */
    private fun endText(c: TsClient?, english: String?): String? {
        val d = c?.endDetail.orEmpty()
        fun withReason(plain: Int, reason: Int) = if (d.isEmpty()) app.getString(plain) else app.getString(reason, d)
        return when (c?.end ?: return english) {
            TsClient.End.NO_ANSWER -> app.getString(R.string.end_no_answer)
            TsClient.End.NO_SUCH_HOST -> app.getString(R.string.end_no_such_host, d)
            TsClient.End.CANT_CONNECT -> app.getString(R.string.end_cant_connect, d)
            TsClient.End.NETWORK_ERROR -> app.getString(R.string.end_network_error, d)
            TsClient.End.NETWORK_GONE -> d // the app's own words already
            TsClient.End.SERVER_SILENT -> app.getString(R.string.end_server_silent)
            TsClient.End.SERVER_STOPPED -> app.getString(R.string.end_server_stopped)
            TsClient.End.LOST -> app.getString(R.string.end_lost)
            TsClient.End.KICKED_CHANNEL -> withReason(R.string.end_kicked_channel, R.string.end_kicked_channel_reason)
            TsClient.End.KICKED -> withReason(R.string.end_kicked, R.string.end_kicked_reason)
            TsClient.End.BANNED -> withReason(R.string.end_banned, R.string.end_banned_reason)
            TsClient.End.SHUT_DOWN -> withReason(R.string.end_shut_down, R.string.end_shut_down_reason)
            TsClient.End.BY_SERVER -> d.ifEmpty { app.getString(R.string.end_by_server) }
            TsClient.End.REFUSED -> d.ifEmpty { english } // the server's own words
            TsClient.End.HANDSHAKE -> app.getString(R.string.end_handshake)
        }
    }

    private fun scheduleRetry(seconds: Long) {
        retryTask?.cancel(false)
        retryTask = retryTimer.schedule({ retryNow() }, seconds, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun retryNow() {
        if (target == null || client != null || _state.value.reconnecting == 0) return
        AppLog.log("Reconnecting (try $retries)")
        start(keepChat = true)
    }

    /**
     * The phone's network: when it goes or changes (Wi-Fi to mobile data, airplane mode), the connection is lost at
     * once, instead of after the server's silence; a network coming back hurries a waiting reconnect.
     */
    @Volatile private var defaultNetwork: android.net.Network? = null

    private fun watchNetwork() {
        if (network != null) return
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: android.net.Network) {
                val before = defaultNetwork
                defaultNetwork = n
                // Another network took over: the connection went out the old one, and the server won't follow
                if (before != null && before != n) client?.networkGone(app.getString(R.string.end_network_changed))
                if (_state.value.reconnecting > 0 && client == null) scheduleRetry(1)
            }

            override fun onLost(n: android.net.Network) {
                if (n != defaultNetwork) return
                defaultNetwork = null
                client?.networkGone(app.getString(R.string.end_network_gone))
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            network = cb
        } catch (_: Exception) {
        }
    }

    /** Away (with a message) or back, as the others see it. */
    fun setAway(away: Boolean, message: String = "") {
        _state.update { it.copy(away = away, awayMessage = if (away) message else "") }
        client?.sendCommand(Commands.build("clientupdate", "client_away" to away, "client_away_message" to if (away) message else ""))
    }

    private fun finish(reason: String?) {
        if (_state.value.phase != Phase.IDLE && !kicked) UiSounds.play(Cue.DISCONNECTED, _state.value.voice, _state.value.outputMuted)
        kicked = false
        engine?.reset()
        retryTask?.cancel(false)
        // A server that needs a higher security level: it's offered to raise ours, then to connect again
        val level = neededLevel
        neededLevel = 0
        levelTarget = if (level > 0) target else null
        target = null
        files?.onDisconnected()
        files = null
        stopAudio()
        client = null
        closeHistory()
        // A call that muted us: as before (a reconnect during the call mutes again)
        if (::app.isInitialized) CallWatcher.stop(app)
        endCall()
        val s = _state.value
        _state.value = UiState(
            phase = Phase.IDLE, error = if (level > 0) null else reason, address = s.address, inputMuted = s.inputMuted,
            outputMuted = s.outputMuted, volumes = s.volumes, voice = s.voice, micAllowed = s.micAllowed, neededLevel = level,
        )
        if (::app.isInitialized) ConnectionService.stop(app)
        pendingSwitch?.let { next ->
            pendingSwitch = null
            android.os.Handler(android.os.Looper.getMainLooper()).post { next() }
        }
    }

    private fun setTalking(clientId: Int, talking: Boolean) {
        _state.update { s -> s.copy(talking = if (talking) s.talking + clientId else s.talking - clientId) }
        if (clientId == _state.value.ownId) engine?.refreshSound()
    }

    private fun poked(p: Chat.Poke) {
        _state.update { s -> s.copy(pokes = (s.pokes + p).takeLast(5)) }
        if (!foreground && ::app.isInitialized) {
            ConnectionService.notifyMessage(app, app.getString(R.string.poked_you, p.fromName), MessageText.plain(p.text))
        }
    }

    private fun messageArrived(key: Chat.Key, e: Chat.Entry) {
        if (foreground || !::app.isInitialized) return
        // Away from the app: private messages come as notifications (channel chat doesn't, like the desktop app)
        if (key is Chat.Key.Private) {
            val text = io.github.korppujauho.pocketts.proto.Pictures.teamSpeakFile(e.text)?.text ?: MessageText.plain(e.text)
            ConnectionService.notifyMessage(app, e.senderName, text)
        }
    }

    private fun updateGains() {
        val volumes = _state.value.volumes
        val c = client ?: return
        c.post {
            player?.gains = model.clients.values.mapNotNull { p -> volumes[p.uid]?.let { p.id to it } }.toMap()
        }
    }

    private fun publishChat() {
        val keys = chat.keys()
        _state.update { s ->
            s.copy(
                chat = keys.associateWith { chat.entries(it) },
                privateTabs = chat.privateNames.map { (uid, name) -> PrivateTab(uid, name) },
            )
        }
    }

    private fun publish(phase: Phase) {
        val tree = model.tree()
        val me = model.ownClient
        ownCodec = model.ownChannel?.codec?.takeIf { it == 4 || it == 5 } ?: 4
        _state.update {
            it.copy(
                phase = phase, serverName = model.serverName, tree = tree,
                ownId = model.ownClientId, ownChannel = me?.channel ?: 0,
                cannotTalk = model.clients.values.filter { c -> !model.canTalk(c) }.map { c -> c.id }.toSet(),
                server = HashMap(model.serverVars),
                badges = model.clients.values.associate { c -> c.id to model.badges(c) },
                serverGroupNames = model.serverGroups.mapValues { it.value.name },
                channelGroupNames = model.channelGroups.mapValues { it.value.name },
                streams = HashMap(model.streams),
                serverIcon = model.serverVars["virtualserver_icon_id"]?.toLongOrNull()?.let { v -> if (v < 0) v + (1L shl 32) else v } ?: 0,
                channelCount = model.channels.size,
                clientCount = model.clients.values.count { c -> c.type == 0 },
            )
        }
    }
}
