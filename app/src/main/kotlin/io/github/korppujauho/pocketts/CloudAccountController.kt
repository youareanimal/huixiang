package io.github.korppujauho.pocketts

import android.content.Context
import android.net.Uri
import io.github.korppujauho.pocketts.proto.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID

/** Cloud account state is separate from the voice identity. Raw passwords are never persisted. */
class CloudAccountController(
    context: Context,
    private val http: CloudTransport = HttpsCloudTransport,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val store: CloudAccountStore = CloudAccountStore(cloudSecretPrefs(context.applicationContext)),
) {
    data class State(
        val restored: Boolean = true,
        val hasAccount: Boolean = false, val username: String = "", val busy: Boolean = false,
        val error: String? = null, val contactError: String? = null,
        val contacts: List<MyTsAccount.Contact> = emptyList(), val rooms: List<MatrixCloud.Room> = emptyList(),
        val contactsLoaded: Boolean = false, val contactsLoading: Boolean = false,
        val directRooms: Map<String, List<String>> = emptyMap(), val sent: Int = 0, val authRevision: Int = 0,
        val notice: String? = null,
        val cloudConnected: Boolean = false, val cloudError: String? = null,
        val cloudUserId: String = "", val openRoomId: String? = null,
        val sending: Boolean = false, val sendError: String? = null, val sendErrorRoomId: String? = null,
        val lastSentRoomId: String? = null, val lastSentText: String? = null,
        val localServers: List<Favorite> = emptyList(),
        val bookmarks: List<MyTsBookmarks.Bookmark> = emptyList(),
        val bookmarksLoaded: Boolean = false, val bookmarkError: String? = null, val bookmarksLoading: Boolean = false,
        val email: String = "", val avatarUrl: String = "", val profileLoading: Boolean = false,
        val profileError: String? = null, val avatarBusy: Boolean = false,
    )
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("cloud-account", Context.MODE_PRIVATE)
    private val secretPrefs = cloudSecretPrefs(app)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Mutex()
    private val mutable = MutableStateFlow(State(restored = false))
    val state = mutable.asStateFlow()
    private var account: CloudAccountTransfer? = null
    @Volatile private var identity: MyTsAccount.Session? = null
    private var contactsJob: Job? = null
    private var bookmarksJob: Job? = null
    private var profileJob: Job? = null
    private val contactGeneration = java.util.concurrent.atomic.AtomicLong()
    private val bookmarkGeneration = java.util.concurrent.atomic.AtomicLong()
    private val profileGeneration = java.util.concurrent.atomic.AtomicLong()
    private var api: MatrixCloud? = null
    private var session: MatrixCloud.Session? = null
    private var cursor = ""
    private val pending = mutableMapOf<Pair<String, String>, String>()
    private var sendJob: Job? = null
    private val readEvents = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val deviceId: String = prefs.getString("device", null) ?: ("SQ_" + UUID.randomUUID().toString().replace("-", "")).also {
        prefs.edit().putString("device", it).apply()
    }

    init { operation {
        migrateLegacySecrets()
        account = store.account()
        identity = store.identity() ?: account?.takeIf { it.myTsSession.isNotBlank() }?.let {
            MyTsAccount.Session(it.myTsSession, it.username)
        }
        // Opening this screen must never implicitly connect to Matrix, including after upgrading from 0.3.0.
        mutable.value = State(
            hasAccount = identity != null || account != null,
            username = identity?.username ?: account?.username.orEmpty(),
            email = identity?.email.orEmpty(),
            localServers = Favorites.load(app),
        )
        if (mutable.value.hasAccount) {
            ConnectionEntry(app).clearGuest()
            Settings(app).applyAccountUsername(mutable.value.username)
        }
        identity?.let { current ->
            try { store.profile(current)?.let { profile -> mutable.update { it.copy(email = profile.email.ifBlank { it.email },
                avatarUrl = profile.avatarUrl, username = profile.username.ifBlank { it.username }) } } } catch (_: Exception) { }
            try { store.contacts(current)?.let { items -> mutable.update { it.copy(contacts = items, contactsLoaded = true) } } }
            catch (_: Exception) { /* A cache error must not invalidate account restoration. */ }
            try { store.bookmarks(current)?.let { items -> mutable.update { it.copy(bookmarks = items, bookmarksLoaded = true) } } }
            catch (_: Exception) { mutable.update { it.copy(bookmarkError = "LOCAL_STORE") } }
            if (!mutable.value.bookmarksLoaded && current.syncKey == null && mutable.value.bookmarkError == null)
                mutable.update { it.copy(bookmarkError = current.bookmarkKeyError ?: "BOOKMARK_KEY_REQUIRED") }
        }
        if (mutable.value.hasAccount) { refreshContacts(); refreshBookmarks(); refreshProfile() }
    } }

    /** 0.2 preview builds before the separate file used this preferences file. Never import a plaintext entry. */
    private fun migrateLegacySecrets() {
        val legacy = listOf("account", "matrix-session").mapNotNull { key -> prefs.getString(key, null)?.let { key to it } }
        if (legacy.isEmpty()) return
        if (legacy.any { (_, value) -> !SecretBox.isSealed(value) }) throw CloudFailure("LOCAL_STORE")
        if (secretPrefs.contains("account") || secretPrefs.contains("matrix-session")) throw CloudFailure("LOCAL_STORE")
        val editor = secretPrefs.edit()
        for ((key, value) in legacy) editor.putString(key, value)
        if (!editor.commit()) throw CloudFailure("LOCAL_STORE")
        if (!prefs.edit().remove("account").remove("matrix-session").commit()) throw CloudFailure("LOCAL_STORE")
    }

    fun importAccount(uri: Uri) = operation {
        val bytes = app.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
            while (true) { val n = input.read(buffer); if (n < 0) break
                if (out.size() + n > 32_768) throw CloudFailure("INVALID_TRANSFER")
                out.write(buffer, 0, n)
            }; out.toByteArray()
        } ?: throw CloudFailure("INVALID_TRANSFER")
        val imported = CloudAccountTransfer.parse(bytes.toString(Charsets.UTF_8))
        // An existing account remains usable when parsing, encryption or connection of the replacement fails.
        val candidate = MatrixCloud(imported.domain, http)
        candidate.discoverServer()
        val candidateSession = candidate.login(imported, deviceId)
        val first = candidate.sync(candidateSession)
        store.write(imported, candidateSession)
        cancelAccountLoads()
        account = imported; api = candidate; session = candidateSession; cursor = first.cursor
        pending.clear(); readEvents.clear()
        ConnectionEntry(app).clearGuest()
        Settings(app).applyAccountUsername(imported.username, overwrite = true)
        identity = imported.myTsSession.takeIf { it.isNotBlank() }?.let { MyTsAccount.Session(it, imported.username) }
        mutable.value = State(
            hasAccount = true, username = imported.username, busy = true, notice = "聊天配置已导入并安全保存。",
            rooms = first.rooms, directRooms = first.directRooms,
            cloudConnected = true, cloudUserId = candidateSession.userId, localServers = Favorites.load(app),
        )
        refreshContacts()
        refreshProfile()
    }

    fun dismissNotice() = mutable.update { it.copy(notice = null) }

    private fun saveSession(value: MatrixCloud.Session) {
        store.write(account ?: throw CloudFailure("LOCAL_STORE"), value)
    }

    private fun refreshNow() {
        val account = account ?: return
        val api = api ?: return
        if (session == null) { session = api.login(account, deviceId); saveSession(session!!) }
        val snapshot = try { api.sync(session!!, cursor) } catch (e: CloudFailure) {
            if (e.code != "M_UNKNOWN_TOKEN") throw e
            session = api.login(account, deviceId); saveSession(session!!); cursor = ""
            api.sync(session!!)
        }
        cursor = snapshot.cursor
        mutable.update { it.copy(rooms = snapshot.rooms, directRooms = snapshot.directRooms, cloudError = null) }
    }

    private suspend fun loadContacts() {
        val current = identity
        if (current == null) { mutable.update { it.copy(contactError = "MYTS_103") }; return }
        val generation = contactGeneration.incrementAndGet()
        fun currentRequest() = identity === current && contactGeneration.get() == generation
        mutable.update { it.copy(contactsLoading = true) }
        try {
            val auth = MyTsAccount(http)
            val cached = mutable.value.contacts.associateBy { it.matrixId }
            val contacts = auth.contacts(current).map { contact ->
                contact.copy(profileName = cached[contact.matrixId]?.profileName.orEmpty())
            }
            if (!currentRequest()) return
            mutable.update { it.copy(contacts = contacts, contactsLoaded = true, contactError = null) }
            val slots = Semaphore(4)
            coroutineScope {
                for (contact in contacts) if (contact.needsProfileName) launch {
                    val named = slots.withPermit { auth.withContactProfile(contact) }
                    if (currentRequest() && named != contact) mutable.update { state -> state.copy(contacts = state.contacts.map {
                        if (it.matrixId == contact.matrixId && it.identifier == contact.identifier) named else it
                    }) }
                }
            }
            if (currentRequest()) try { store.writeContacts(current, mutable.value.contacts) } catch (_: Exception) { }
        } catch (e: CancellationException) { throw e }
        catch (e: CloudFailure) { if (currentRequest()) mutable.update { it.copy(contactError = e.code) } }
        catch (_: Exception) { if (currentRequest()) mutable.update { it.copy(contactError = "CONTACTS_INVALID_REPLY") } }
        finally { if (currentRequest()) mutable.update { it.copy(contactsLoading = false) } }
    }

    fun refresh() { if (!lock.isLocked && mutable.value.cloudConnected) cloudOperation(showBusy = false) { refreshNow() } }
    fun refreshFriends() {
        mutable.update { it.copy(localServers = Favorites.load(app)) }
        refreshBookmarks()
        refreshContacts()
    }

    fun refreshContacts() { contactsJob?.cancel(); contactsJob = scope.launch { loadContacts() } }
    fun refreshBookmarks() { bookmarksJob?.cancel(); bookmarksJob = scope.launch { loadBookmarks() } }
    fun refreshProfile() { profileJob?.cancel(); profileJob = scope.launch { loadProfile() } }
    private fun loadProfile() {
        val current = identity ?: return
        val generation = profileGeneration.incrementAndGet()
        fun currentRequest() = identity === current && profileGeneration.get() == generation
        mutable.update { it.copy(profileLoading = true) }
        try {
            val profile = MyTsProfile(http).read(current)
            if (!currentRequest()) return
            store.writeProfile(current, profile)
            mutable.update { if (!currentRequest()) it else it.copy(email = profile.email.ifBlank { it.email }, avatarUrl = profile.avatarUrl,
                username = profile.username.ifBlank { it.username }, profileError = null) }
        } catch (e: CloudFailure) { if (currentRequest()) mutable.update { it.copy(profileError = e.code) } }
        catch (_: Exception) { if (currentRequest()) mutable.update { it.copy(profileError = "PROFILE_UNAVAILABLE") } }
        finally { if (currentRequest()) mutable.update { it.copy(profileLoading = false) } }
    }
    fun updateAccountAvatar(uri: Uri) = avatarOperation {
        val png = try { ServerFiles.prepareAccountAvatar(app, uri) }
            catch (e: CloudFailure) { throw e }
            catch (_: Exception) { throw CloudFailure("AVATAR_IMAGE") }
        try { MyTsProfile(http).updateOnlineAvatar(it, png) } finally { png.fill(0) }
    }
    fun removeAccountAvatar() = avatarOperation { MyTsProfile(http).removeOnlineAvatar(it) }
    private fun avatarOperation(change: (MyTsAccount.Session) -> Unit) = operation {
        val current = identity ?: throw CloudFailure("MYTS_103")
        profileGeneration.incrementAndGet(); profileJob?.cancel()
        mutable.update { it.copy(avatarBusy = true, profileLoading = false, notice = null) }
        try {
            change(current)
            mutable.update { it.copy(notice = "账号在线头像已保存。其他状态头像保持原样。") }
            loadProfile()
        } finally { mutable.update { it.copy(avatarBusy = false) } }
    }
    private fun cancelAccountLoads() {
        contactGeneration.incrementAndGet(); bookmarkGeneration.incrementAndGet(); profileGeneration.incrementAndGet()
        contactsJob?.cancel(); bookmarksJob?.cancel(); profileJob?.cancel()
    }

    private fun loadBookmarks() {
        val current = identity ?: return
        val generation = bookmarkGeneration.incrementAndGet()
        fun currentRequest() = identity === current && bookmarkGeneration.get() == generation
        mutable.update { it.copy(bookmarksLoading = true) }
        try {
            val items = MyTsBookmarks(http).read(current)
            if (!currentRequest()) return
            store.writeBookmarks(current, items)
            mutable.update { it.copy(bookmarks = items, bookmarksLoaded = true, bookmarkError = null) }
        } catch (e: CloudFailure) { if (currentRequest()) mutable.update { it.copy(bookmarkError = e.code) } }
        catch (_: Exception) { if (currentRequest()) mutable.update { it.copy(bookmarkError = "LOCAL_STORE") } }
        finally { if (currentRequest()) mutable.update { it.copy(bookmarksLoading = false) } }
    }
    fun history(roomId: String) = cloudOperation {
        val client = api ?: throw CloudFailure("M_UNKNOWN_TOKEN")
        client.history(session ?: throw CloudFailure("M_UNKNOWN_TOKEN"), roomId)
        mutable.update { it.copy(rooms = client.current()) }
    }
    fun send(roomId: String, text: String) {
        if (sendJob?.isActive == true || text.isBlank()) return
        mutable.update { it.copy(sending = true, sendError = null, sendErrorRoomId = roomId) }
        sendJob = cloudOperation(showBusy = false) {
            val client = api
            try {
                val key = roomId to text
                val transaction = pending.getOrPut(key) { UUID.randomUUID().toString() }
                (client ?: throw CloudFailure("M_UNKNOWN_TOKEN")).sendText(session ?: throw CloudFailure("M_UNKNOWN_TOKEN"), roomId, text, transaction)
                pending.remove(key)
                mutable.update { it.copy(sent = it.sent + 1, lastSentRoomId = roomId, lastSentText = text,
                    rooms = client.current(), sendError = null, sendErrorRoomId = null) }
                try { refreshNow() } catch (e: CloudFailure) {
                    mutable.update { it.copy(cloudError = e.code, notice = "消息已发送，后续同步暂未完成，可稍后刷新。") }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: CloudFailure) { mutable.update { it.copy(sendError = e.code) } }
            catch (_: Exception) { mutable.update { it.copy(sendError = "CHAT_INVALID_MESSAGE") } }
            finally { mutable.update { it.copy(sending = false, rooms = client?.current() ?: it.rooms) } }
        }
    }

    fun openFriendChat(contact: MyTsAccount.Contact) = cloudOperation {
        val friend = mutable.value.contacts.firstOrNull { it.matrixId == contact.matrixId && it.identifier == contact.identifier }
            ?: throw CloudFailure("CONTACT_UNAVAILABLE")
        if (friend.matrixId.isBlank()) throw CloudFailure("CONTACT_NO_CHAT")
        if (!mutable.value.cloudConnected) connectCloudNow() else refreshNow()
        currentCoroutineContext().ensureActive()
        val client = api ?: throw CloudFailure("M_UNKNOWN_TOKEN")
        try {
            val room = client.openDirect(session ?: throw CloudFailure("M_UNKNOWN_TOKEN"), friend.matrixId)
            mutable.update { it.copy(openRoomId = room.id, rooms = client.current(), directRooms = client.currentDirectRooms(), sendError = null) }
        } finally { mutable.update { it.copy(rooms = client.current(), directRooms = client.currentDirectRooms()) } }
    }
    fun consumeOpenRoom(id: String) { mutable.update { if (it.openRoomId == id) it.copy(openRoomId = null) else it } }
    fun acceptRoom(roomId: String) = cloudOperation {
        val client = api ?: throw CloudFailure("M_UNKNOWN_TOKEN")
        val room = client.joinRoom(session ?: throw CloudFailure("M_UNKNOWN_TOKEN"), roomId)
        mutable.update { it.copy(rooms = client.current(), directRooms = client.currentDirectRooms(), openRoomId = room.id) }
    }
    fun markRead(roomId: String, eventId: String) {
        if (readEvents[roomId] == eventId || !mutable.value.cloudConnected) return
        readEvents[roomId] = eventId
        cloudOperation(showBusy = false) {
            try {
                val client = api ?: return@cloudOperation
                client.markRead(session ?: return@cloudOperation, roomId, eventId)
                mutable.update { it.copy(rooms = client.current()) }
            } catch (_: CloudFailure) { readEvents.remove(roomId, eventId) }
        }
    }

    /**
     * Authentication and durable storage alone decide login success. Matrix is a separate, explicitly requested step.
     */
    fun loginAccount(email: String, password: String, otp: String) = operation {
        val auth = MyTsAccount(http)
        val fresh = auth.login(email, password, otp, deviceId)
        try {
            store.writeIdentity(fresh)
        } catch (e: Exception) { try { auth.revokeOwnSession(fresh) } catch (_: Exception) {}; throw e }
        cancelAccountLoads()
        identity = fresh
        ConnectionEntry(app).clearGuest()
        Settings(app).applyAccountUsername(fresh.username, overwrite = true)
        account = null; api = null; session = null; cursor = ""; pending.clear()
        readEvents.clear()
        mutable.value = State(
            hasAccount = true, username = fresh.username, email = fresh.email,
            authRevision = mutable.value.authRevision + 1,
            localServers = Favorites.load(app),
            notice = "TeamSpeak 账号登录成功。云聊天尚未连接。",
        )
        refreshBookmarks()
        refreshContacts()
        refreshProfile()
    }

    fun connectCloud() = cloudOperation { connectCloudNow() }
    private fun connectCloudNow() {
        fun freshCredentials(): CloudAccountTransfer {
            val current = identity ?: throw CloudFailure("MYTS_103")
            val credentials = chatStage("CREDENTIALS") { MyTsAccount(http).chatCredentials(current) }
            return CloudAccountTransfer(credentials.domain, credentials.userId, credentials.password, current.token, current.username)
        }
        fun connect(value: CloudAccountTransfer): Triple<MatrixCloud, MatrixCloud.Session, MatrixCloud.Snapshot> {
            val candidate = MatrixCloud(value.domain, http)
            candidate.discoverServer()
            val saved = store.session(value.userId, deviceId)
            var candidateSession = saved ?: chatStage("LOGIN") { candidate.login(value, deviceId) }
            val first = try { chatStage("SYNC") { candidate.sync(candidateSession) } } catch (e: CloudFailure) {
                if (e.code != "M_UNKNOWN_TOKEN" || saved == null) throw e
                candidateSession = chatStage("LOGIN") { candidate.login(value, deviceId) }
                chatStage("SYNC") { candidate.sync(candidateSession) }
            }
            return Triple(candidate, candidateSession, first)
        }
        val cached = account
        var updated = cached ?: freshCredentials()
        val (candidate, candidateSession, first) = try { connect(updated) } catch (e: CloudFailure) {
            // Keep valid saved Matrix sessions usable even if an imported myTS session has expired. Only a failed
            // explicit connection needs to refresh persisted routing/credentials from the account service.
            if (cached == null || identity == null || !e.code.matches(Regex("CHAT_(LOGIN|SYNC)_(HTTP_50[234]|NETWORK)"))) throw e
            val fresh = freshCredentials()
            if (fresh.domain == updated.domain && fresh.userId == updated.userId && fresh.password == updated.password) throw e
            updated = fresh
            connect(updated)
        }
        store.write(updated, candidateSession)
        account = updated; api = candidate; session = candidateSession; cursor = first.cursor
        mutable.update { it.copy(cloudConnected = true, cloudUserId = candidateSession.userId, cloudError = null,
            rooms = first.rooms, directRooms = first.directRooms) }
    }

    private inline fun <T> chatStage(stage: String, block: () -> T): T = try { block() } catch (e: CloudFailure) {
        if (e.code in setOf("HTTP_502", "HTTP_503", "HTTP_504", "NETWORK"))
            throw CloudFailure("CHAT_${stage}_${e.code}")
        throw e
    }

    fun forget() = operation {
        // Imported myTS sessions may belong to the desktop client; do not revoke them remotely.
        cancelAccountLoads()
        store.forget()
        ConnectionEntry(app).clearGuest()
        Settings(app).clearAccountUsername()
        prefs.edit().remove("account").remove("matrix-session").commit()
        identity = null; account = null; api = null; session = null; cursor = ""; pending.clear()
        readEvents.clear()
        mutable.value = State()
    }
    fun close() { scope.cancel() }

    private fun cloudOperation(showBusy: Boolean = true, block: suspend () -> Unit) =
        operation(showBusy, cloud = true, block = block)

    private fun operation(showBusy: Boolean = true, cloud: Boolean = false, block: suspend () -> Unit) =
        scope.launch { lock.withLock {
            if (showBusy) mutable.update { if (cloud) it.copy(busy = true, cloudError = null) else it.copy(busy = true, error = null) }
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: CloudFailure) { mutable.update { if (cloud) it.copy(cloudError = e.code) else it.copy(error = e.code) } }
            catch (_: Exception) { mutable.update { if (cloud) it.copy(cloudError = "LOCAL_STORE") else it.copy(error = "LOCAL_STORE") } }
            finally { mutable.update { it.copy(busy = false, restored = true) } }
        } }
}
