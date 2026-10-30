# Changelog

## 回响 0.5.2 (2026-10-30)

- 接入 myTeamSpeak 账号与语音连接的证明链路，每次连接独立签名，并发送签名头像、证书和徽章数据；私钥和登录令牌不发送给语音服务器。
- 首次升级后请重新登录 myTeamSpeak 并重新连接服务器，以补齐旧版本未保存的账号材料。官方 TS6 资料显示效果仍需实际服务器验证。
- 包含聊天连接及草稿保留修复：发送被拒绝或送达状态未知后，切换页面或断开重连仍可找回文本并重发。
- 安装包由 GitHub Actions 测试、构建，并使用原发行证书签名，保留现有账号、语音身份与设置。

## 回响 0.5.1 (2026-10-29)

- 修复手动连接页大标题两行文字重叠，给连接、登录及同类标题设置与字号匹配的行高，支持放大字体后的换行。
- 在线更新默认使用 PI HTTPS 直连镜像，关于页可选择 PI 或 Cloudflare，并显示当前来源；检查和下载失败时尝试备用源。
- 下载切源前重新核对版本、手机架构、长度与 SHA-256，仍检查包名、版本和原签名证书；各来源保持各自 HTTPS 主机、端口和路径限制。
- 增加 PI 只读镜像与证书续期维护脚本；发布时同步同一批签名安装包和对应源码。旧客户端的 Cloudflare 更新清单继续保留。

## 回响 0.5.0 (2026-10-29)

- 登录后所有连接入口采用当前账号昵称，历史记录和邀请昵称不能覆盖账号；最近连接优先采用当前账号书签中的服务器配置。
- 访客必须从未登录的欢迎页明确选择；登录和退出均清除旧访客授权，退出断开语音并返回登录/访客选择页。
- 频道列表、聊天与用户资料卡显示当前账号头像；其他用户显示服务器/公开 myTeamSpeak 头像，加载失败逐级回退。
- 点击频道用户、聊天昵称或头像打开资料卡，按需请求服务器的公开用户字段，展示 UID、频道、身份组、客户端、地区及状态；资料随通知更新，离开服务器或客户端 ID 被复用时关闭旧卡片。
- 自己的账号邮箱仅出现在自己的资料卡中；显示账号头像不会自动修改服务器头像或语音权限。
- 好友卡片的“发消息”按需连接云聊天，复用两人会话；没有会话时创建私人聊天并邀请好友，也可明确接受收到的邀请。
- 普通文字会话支持发送、前台同步、历史分页和阅读回执；发送确认后立即回显，网络失败保留草稿，同一条失败消息重试复用事务编号。其他会话或旧内容的发送结果不会清空当前新草稿。
- 已开启端到端加密的会话仍无法解密或发送；不会为其新建未加密替代会话。发送前再次读取成员与加密状态，阻止把两人私聊发送到成员已变化的会话。
- 首次使用独立 GitHub 仓库与 Actions 构建，GitHub Releases 分发安装包与对应源码；现有升级签名留在本机，下载 CI 产物后签名再发布，在线更新源继续兼容旧客户端。

## 回响 0.4.3 (2026-10-29) — 在线更新测试

- 仅将版本提升至 0.4.3-preview（versionCode 12），用于从 0.4.2 验证检查更新、下载及覆盖安装。
- 应用功能、界面及协议代码与 0.4.2 完全一致。

## 回响 0.4.2 (2026-10-29)

- Add manual update checking under Me / About and an optional once-daily automatic check with a dismissible banner.
- Download a compatible APK with progress and cancellation, retain completed downloads across restarts, and hand
  installation to Android after the user taps Install. Handle the system's per-app installation permission.
- Require the public HTTPS release origin, a matching size/hash/package/version, and the installed signing certificate.
  Keep TeamSpeak authentication and private cloud-drive credentials out of the update channel.
- Add a dedicated Cloudflare Pages feed and repeatable publication script, preserving versioned downloads and
  publishing the matching GPL source archive. Continue copying the arm64 APK to the existing PI cloud-drive folder.

## 回响 0.4.1 (2026-10-27)

- Show the account email and online avatar on Me, with automatic independent profile refresh and an encrypted,
  account-bound metadata cache. Existing sessions can retrieve their email without signing in again.
- Add separate account/server avatar management. Preview a selected image before uploading, re-encode it as a
  bounded sRGB PNG and register it only after a successful signed PUT. Update/remove only the online account avatar;
  keep away, DND, offline and server avatars unchanged. Require confirmation before removal.
- Add reveal/hide eye buttons to the common password input, masking again after focus loss or app backgrounding.
- Account metadata retrieval was verified read-only. Avatar writes were tested with synthetic transport responses;
  no real account avatar was changed during development, and phone verification is still required.

## 回响 0.4.0 (2026-10-27)

- Rebuild the main shell with Servers, Friends, Messages and Me navigation, dedicated login and connection routes,
  grouped settings pages and a persistent voice channel bar. Add a youthful violet/mint theme with rounded surfaces.
- Move account work into an activity ViewModel. Load bookmarks and friends independently of authentication and
  preserve work across navigation. Slow contact requests no longer hold the account operation lock.
- Seal the account-bound friend-name cache, preserve it on network failures and clear it when changing accounts.
  Keep diagnostic codes behind an expandable error detail action.
- Keep recent successful servers without passwords or invitation URLs, confirm server switches, and preserve voice
  while browsing other pages. Keep the existing package, certificate, voice identity and encrypted storage identifiers.
- Preserve file transfer, streaming, channel administration and identity tools through their corresponding menus.

## 回响 0.3.5 (2026-10-26)

- Add a login-first entry screen with explicit account and guest actions. Restore saved accounts into their server
  and friend page; guests can open the voice-server connection screen. Automatic favorites wait until entry.
- Incoming server invitations can show their confirmation from the entry screen without opening account login.
- Rename the app to 回响 and use the purple one-eyed mascot selected from the user's supplied image. Add the
  transparent asset, lossless WebP resource, adaptive launcher layer, themed mark and notification mark. Earlier
  icon concepts remain as design references in `docs/branding/`.
- Decode only enough of structured notes to keep internal Base64/JSON metadata out of display names. Prefer a
  plain-text alias or a public profile display name, with a generic contact label when no readable name is available.
- Fetch public names without Matrix login or chat synchronization; failed lookups preserve the friend list.
- Load friends after account login and when reopening the account page. Separate loading and empty states replace
  the old instruction to refresh manually. Protocol and Android unit regressions pass.

## 声桥 TS 0.3.4 (2026-10-26)

- Accept version 2 account key envelopes instead of rejecting them before decryption. A synthetic version 2
  envelope and seed were independently accepted by the installed official native reader; the new protocol and
  controller regressions cover password login, bookmark synchronization, sealed persistence and restoration.
- Unsupported formats now report only envelope length, version and derived-key length. Authentication tags are
  still verified; unrecognized versions, truncated envelopes and corrupted contents are rejected.
- Old saved format errors prompt a fresh login with the updated reader. No account sync data is modified.

## 声桥 TS 0.3.3 (2026-10-26)

- Bookmark unlocking now retains separate errors for a missing key, unsupported envelope, failed authentication,
  password-key derivation and unavailable crypto services. Closing/reopening the page preserves the exact error;
  only an old saved login without diagnostic information asks for an initial password login.
- Account authentication remains successful even if the phone's bookmark key derivation fails. The signed-in
  account page displays its installed version. This build improves diagnosis; it does not claim the reported
  phone-specific bookmark unlocking failure has been resolved.

## 声桥 TS 0.3.2 (2026-10-26)

- Password login now unlocks and reads the account's encrypted server bookmarks through the independent account
  synchronization API. The server list separates account bookmarks from local favorites and supports tap-to-connect.
- Account sync keys, bookmark caches and saved server passwords are encrypted with Android Keystore. Failed refreshes
  preserve cached bookmarks; switching accounts clears the previous account's cache. Cloud chat remains optional.
- The connection form automatically uses the signed-in username, including when login happens while the form is
  already open. Users can still choose another nickname; a bookmark-specific nickname takes precedence.
- Upgrading from earlier versions requires one password login to unlock bookmark synchronization. Voice identities
  and channel settings are not imported from the account.

## 声桥 TS 0.3.1 (2026-10-26)

- Account authentication now succeeds and saves the username/session independently of cloud chat. Opening the
  account page or restoring a saved account never automatically calls Matrix or `tschat.createAccount`.
- Cloud chat has its own explicit connect button and error state. Chat 503 responses and contact-list failures
  no longer undo a successful account login.
- The account page shows this phone's saved servers with a connect action. Official cloud bookmarks are encrypted;
  cloud bookmark synchronization remains unsupported and is clearly distinguished from local favorites.

## 声桥 TS 0.3.0 (2026-10-26)

- **Account login:** “Account & cloud chat” now signs in directly with the myTeamSpeak email address, password and
  optional six-digit OTP. The app exchanges this login for the account's cloud-chat credentials, opens Matrix, and
  syncs friends and existing conversations without a running desktop client.
- **Safer sessions:** raw email passwords are never stored. The myTeamSpeak session, cloud-chat credentials and
  Matrix session are sealed with Android Keystore and excluded from Android backup and device transfer. A failed
  login keeps the previous account usable, and a newly created myTeamSpeak session is revoked when setup fails.
- **Migration:** `.tsaccount` import remains available as a separate recovery path for an old installation or an
  already exported configuration. Normal use no longer requires a computer.
- **Version:** the compact preview packages of 声桥 TS are now 0.3.0 for `arm64-v8a`, `armeabi-v7a`, and universal
  devices.

## 1.4.0 (2026-10-24)

- **Channel info:** holding a channel shows its topic, how many are in it (and the limit), its type, voice codec and
  quality, talk power needed, password, and its description, with a Join button. With Settings › UI › Show admin
  functions on, it also has Edit (the channel's settings) and Arrange (the edit mode that holding a channel
  opened before).
- **A channel's files:** browse a channel's files (folders first, with sizes and dates) and save any file where you
  choose on the phone, with progress and Cancel. Upload sends any file from the phone into the open folder, New
  folder makes one, and holding a file or folder gives Download, Rename and Delete. Deleting always asks first (a
  folder goes with everything in it), and a name already there is only replaced when you say so. The server decides
  what you may see and change; a password channel's files need its remembered password.
- **Server nicknames:** a server's TeamSpeak nickname (such as `myclan`, without a dot) works as its address, looked
  up from TeamSpeak's nickname service as the TeamSpeak apps do. A name it doesn't know is tried as a normal address.
- **TeamSpeak links:** `ts3server://` and `teamspeak://` links from web pages and other apps open PocketTS, which
  asks before connecting (leaving the server you're on first). A link's port, server password and channel are used.
  A privilege key in a link is used only when you tick it, since it gives rights on that server.
- **Muted during phone calls:** when a phone call rings or starts, the microphone and sound go off (and the sound of
  a stream you watch), and come back on when it ends (what you muted yourself stays muted). A stream you share
  (screen or camera) pauses, the way TeamSpeak's own pause works, so viewers don't see the call or the phone at your
  ear. It needs no permission; Settings › General can turn it off.
- **Paused streams:** watching a stream its streamer paused says "Paused" instead of "Connecting…".
- **Notification buttons:** the connection's notification has Mute / Unmute (the microphone), Sound off / on and
  Disconnect, so you can use them without opening the app.
- **Credits:** [CREDITS.md](CREDITS.md) lists the people who have helped. The phone call muting, the share pause,
  the notification buttons and the links are based on Stefan3Dx's work.

## 1.3.0 (2026-10-23)

- **In seven languages:** English, Finnish, German, Spanish, French, Simplified Chinese and Russian. PocketTS follows
  the phone's language, or the one chosen in Settings › UI › Language (on Android 13 and later, the same choice as
  in Android's app settings).
  TeamSpeak's own words follow the TeamSpeak 5 client's translations. See [TRANSLATIONS.md](TRANSLATIONS.md) to
  help with corrections.
- **Screen share sound:** sharing the screen can carry what the phone plays, such as a video or a game ("Phone sound"
  in the share window, on by default). Some apps don't allow their sound to be recorded and stay silent. The
  channel's voices and the streams you watch are left out, so viewers don't hear the channel twice. The microphone
  isn't used for it, it keeps playing while you talk, and viewers hear it even with the phone's volume at zero. It
  can't be used while you watch a stream (the option is greyed out then).
- **Editing channels** (with Settings › UI › Show admin functions on): holding a channel opens edit mode. The channels
  wiggle; drag one to move it among its neighbours, tap one to change its name, description, type (permanent,
  semi-permanent or temporary), password, voice codec and quality, max users, or where it is in the tree. Add makes
  a new channel, and Delete always asks first, saying which sub-channels go too and how many people are moved out.
  The server decides who may.
- The channel list stays in the server's order when an admin elsewhere adds or moves a channel between others.
- **A stream's size:** drag the handle under a stream you watch to make it bigger or smaller (remembered). Pulled on
  past the top it shrinks to a small picture over the app that you can drag anywhere (tap it to bring it back). At
  first it takes at most 40 % of the screen, so the channels and the chat stay in view.
- **Buttons at the bottom** (Settings › UI): the microphone, sound, push-to-talk and share buttons and the
  Channels/Chat switch move to the bottom of the screen, within reach of your thumb. The round push-to-talk button
  stays above them, and they step aside while you type in the chat.
- The push-to-talk button is now the rightmost of the buttons, where a thumb reaches it most easily. The round
  push-to-talk buttons (in the app and over other apps) show the same talking person, since the microphone means
  muting.
- **For developers:** `scripts/dev.sh viewer up` starts a test viewer that watches every stream on the test
  server, logs what arrives and measures the delay (see [DEVELOPING.md](DEVELOPING.md)).

## 1.2.0 (2026-10-22)

- **Google Play:** a [privacy policy](PRIVACY.md), and a Play version built with each release (the same app and key,
  without the GitHub update check, since Play updates it).
- **Admin functions:** with Settings › UI › Show admin functions on (off by default), a person's menu also offers
  Move (to a channel from the server's tree; admins need no channel password), Kick (from the channel or from the
  server, with a reason they see) and Ban (10 minutes to permanently, with a reason). A ban works as in the TeamSpeak
  app: it covers the person's identity and IP address, and removes them at once. The server decides who may: without
  the permission the app says "You don't have permission to …" instead of the server's raw error.
- A new **UI** tab in Settings, before Advanced.
- **Security level:** a server that needs a higher identity security level than yours no longer just refuses. PocketTS
  says which level it needs, offers to raise yours, shows the progress with a Stop button, and connects again when it
  gets there. Settings › Advanced shows your level, and Raise level raises it to a level you choose, with an estimate
  of how long it takes on your phone. Raising keeps the same identity (groups and permissions stay), uses all but one
  of the phone's cores, and Stop keeps the level reached so far.
- All of the app's text is in string resources (`res/values/strings.xml`), with placeholders and plural forms, ready
  for translations. How a connection ended (kicked, banned, no answer…) is now said by the app, so it can be
  translated too.

## 1.1.0 (2026-10-22)

- **Several pictures at once:** choose up to 10 from the gallery. They show in a row above the message box, each can
  be taken off, and a photo from the camera joins the row too. They're sent one message each in order, with the
  words on the first, and the chat shows which one is being sent.
- **Save and share pictures:** a picture opened on the whole screen has Save (into the gallery, under
  Pictures/PocketTS) and Share (Android's share menu) next to the close button.
- **Smoother chat with pictures:**
  - A picture's place is kept for it while it loads (from its size the time before), so the chat no longer jumps.
  - The chat shows pictures only as big as it draws them, and tapping one opens the whole picture, sharp when zoomed
    in.
  - Chat lines are no longer drawn again each time someone starts or stops talking.
  - The same picture is downloaded only once at a time, and big pictures are read from the phone's copy as they're
    drawn instead of first all into memory.
- The chat opens at its newest line straight away, instead of scrolling down past the older ones.
- A wrong server address or port now says "Couldn't connect: no answer from the server. Check the address and port."
  instead of "Connection lost", and a name that doesn't exist says that no server was found at it.
- Releases are built by GitHub Actions from the tagged commit, with a build attestation: anyone can check that a
  release APK came from this repository (see SECURITY.md). Security problems can be reported privately.
- Test builds log how smoothly the screen draws (frames, late frames, the slowest) every 10 seconds.

## 1.0.0 (2026-10-21)

The first release.

- Release builds: signed with the release key and shrunk (about 23 MB instead of 86). Making the key and the release
  are `scripts/dev.sh keystore` and `scripts/dev.sh release` (see DEVELOPING.md).
- The GitHub version checks for updates once a day and shows a card on the connect screen when a newer one is out
  (Download opens its release page, Not now hides it until a later one). Off in Settings > General > Check for
  updates. Test builds don't check.
- Reworked sounds for switching channel, being moved, people joining and leaving, messages in and out, and streams
  starting and stopping: low, short and soft, in the style of the sound buttons' (no sliding pitch, which stuttered).
- Settings > General > "Keep the screen on": the screen stays on while connected and the app is open. While you share
  or watch a stream, it stays on anyway.
- A ring light when sharing the front camera: the share bar's sun button turns the screen to full brightness with a
  white frame around it, lighting your face.
- A camera stream without "Stream sound" sends no sound at all: the microphone went out on its sound line anyway.
- A lost connection is noticed within seconds: at once when the phone's network goes or changes (Wi-Fi off,
  airplane mode), else after 10 seconds without a word from the server (it was 30, and took about a minute).
- Reconnecting while the server still has the old connection (it allows your identity on only once, and drops a
  dead connection only after a while) tries again every 3 seconds, for up to two minutes, and says why it's waiting.
- The big share preview is drawn by a renderer of its own, fitted while drawing: it was still cut and stretched.
  The front camera's preview also stays upright when the phone turns.
- Camera streams can carry sound: tick "Stream sound" when sharing the camera (remembered). Viewers hear your
  microphone with the picture (echo removed, surroundings kept). It goes quiet while your microphone is muted and
  while you talk in the channel, so you're not heard twice; the share bar's microphone mark shows which. Without it,
  the stream has no sound and you talk in the channel as usual. The stream opens the microphone the same way the
  voice does (call mode with echo cancellation, else the ordinary microphone), so both hear you, from as far away.
- The big share preview isn't stretched any more (the picture kept the small preview's shape).
- Settings > Advanced > Identity: Wipe (with a warning first), Export and Import. Buttons lost their "…".
- Pictures (profile pictures and ones sent to the chat) are saved in plain sRGB colours, without a phone photo's
  embedded colour profile; with it, the TeamSpeak 6 app didn't show the profile picture.
- Sharing the front camera: your preview shows exactly what viewers see (it was mirrored like a selfie camera).
- A profile picture too big for the server (its max avatar file size, 200 KB as it comes) is sent again smaller,
  down to 96 × 96, before giving up with "This server allows only a very small profile picture, or none."
- A file or picture sent from the TeamSpeak app through myTeamSpeak shows as one short line ("TeamSpeak images
  aren't supported in PocketTS") instead of its raw note, in the chat and in notifications.
- Pictures sent from the app go into the channel's /PocketTS folder (they went into /webTS). Pictures in any folder
  still show.
- Channel passwords can be remembered: tick "Remember the password" when joining, and next time the channel joins with
  one tap. They're kept encrypted on the phone, per server. A refused password (mistyped, or changed on the server)
  shows the usual warning, is forgotten, and the password is asked again. Settings > Advanced > Channel passwords
  forgets them all.
- A password-protected channel is joined without asking when you're allowed to skip channel passwords (such as a
  server admin), as in the TeamSpeak app: the app tries without one first, and asks only if the server refuses.
- Channel banners fill their row: as tall as it, in their own proportions (never stretched), with what doesn't fit cut
  off on the right, which fades out.
- Settings fill nearly the whole screen and sit closer together (smaller checkboxes, sliders and gaps), so there's
  less scrolling.
- Typing in the chat: while the keyboard is open, the control buttons and the Channels / Chat switch step aside (and an
  attached picture gets smaller), so the message box stays in view.
- Spacer channels can be joined by tapping them when Settings > General > "Allow joining spacer channels" is on
  (some servers use one as their AFK channel). It's off at first, so a stray tap on a divider doesn't move you.
- The identity's import and export moved to Settings > Advanced.
- A picture for the channel chat shows as attached above the message box (with a button to remove it) and goes with
  the usual Send button, with any words typed under it, in one message (as the TeamSpeak app sends them). Taking a photo asks for the camera first
  instead of closing the app.
- The headset's button can be push-to-talk (Settings > General): one press starts talking, the next stops, also with
  the screen off.
- Commands to the server are spaced out a little, so busy servers don't refuse them as flooding. When one still is
  refused, it's sent again after the wait the server asks for.
- The server name has an icon like the channels: the server's own, or a plain server icon. Server info shows the
  server's banner also when it's a picture in the server's files.
- A new app icon: a headset peeking out of a blue pocket, with a one-colour version for Android's themed icons and a
  matching notification icon. The header shows it too.
- Exporting the identity warns first that the file is you on every server, then saves it as a file you choose (in
  the .ini format TeamSpeak apps open) instead of sharing it to an app. Import hides the identity while it's pasted.
- Sounds for events, made for the app (low, soft tones in the style of chat apps, not TeamSpeak's own): connecting and disconnecting, switching channel,
  people coming and going in your channel, messages, pokes, the microphone and sound buttons, and streams starting
  and stopping. On/off and volume in Settings > Playback.
- Popups and the person menu have a new rounded look, with an icon, pill buttons and softer fields.
- The app is now called **PocketTS** (it was MobileTS6), with a new app ID, `io.github.korppujauho.pocketts`. Android
  sees it as a new app: remove the old test build, and import your identity again if you exported it.
- Server names work as in TeamSpeak apps: the server's DNS entry first, then TeamSpeak's name service (TSDNS), then
  the name itself on port 9987.
- A dark look: a slim header, control buttons (microphone and sound on/off), the server box, channels as boxes
  with the people inside, a "YOU" badge, a glow for whoever is talking, mute, "can't talk here" and AFK marks, and
  channel spacers drawn as TeamSpeak draws them.
- Chat, under a Channels / Chat switch:
  - tabs for the channel and private conversations, with unread marks (no server chat: the TeamSpeak 6 app has none)
  - times and names, links you can tap, and **bold** and other styles (TeamSpeak's tags and Markdown-style marks)
  - join and leave lines worded like the TeamSpeak app's
- Tap a person for their menu: their volume for you (kept per person), poke, and private chat.
- Pokes show as cards until dismissed. When the app isn't on screen, pokes and private messages come as
  notifications.
- Talking:
  - the microphone, encoded as Opus with Android's own encoder, in the channel's codec
  - voice activation (with a level and a delay), push-to-talk, or continuous transmission
  - push-to-talk: a "Hold to talk" button and a floating button, and optionally the volume buttons
  - optionally the round talk button stays on screen over other apps (hold to talk, drag to move)
  - it keeps working with the screen off (the connection notification now also covers the microphone)
- Settings (General, Playback, Capture), behind the gear in the top right corner (also before connecting):
  - nickname
  - voice volume and a test sound
  - microphone volume, activation, and a level meter with the activation threshold
  - noise removal, echo cancellation and automatic gain
  - With echo cancellation on, voices play like a phone call (loudspeaker or headset).
- Pictures, through TeamSpeak's file transfer (the server's files):
  - avatars in the channel tree, the chat and the person menu (a myTeamSpeak picture when there's no avatar)
  - the server's icons for channels and the server, group badges, and TeamSpeak's built-in shields
  - channel banners, and the server's banner in its info
  - pictures in the chat (the TeamSpeak app's format), shown full screen with zoom
  - links to pictures shown as pictures, other links as an "Open" card
- Sending pictures to the channel chat: choose one or take a photo, see it, then send. Photos are turned the right
  way up and saved again, which leaves out what the camera wrote in them (such as where the photo was taken).
- Your own profile picture (Settings > General), fitted inside 320 × 320, or removed.
- Favourites: name, address, the nickname used there, the password (kept on this phone only), and one
  that connects automatically when the app opens (set in its edit window). The list shows a favourite's name when it has one.
  The star menu on the server screen switches servers or adds the one you're on.
- A lost connection (Wi-Fi to mobile data, a dead spot) reconnects by itself, keeping the chat; sooner when a
  network comes back.
- Away, with a message (tap yourself).
- Warnings (such as a refused password) show on a background of their own, can be closed, and go away by themselves.
- A favourite can have its own file transfer port (its Advanced part), for servers where it isn't the usual one.
- The TeamSpeak identity can be exported (shared) and imported from another TeamSpeak app (Settings > Advanced):
  pasted, or as the .ini file a TeamSpeak app saves (chosen with Android's file picker).
- The identity and the passwords the app keeps (the remembered server password, favourites' passwords and the relay
  password) are encrypted with a key that stays in the phone's Android Keystore. Ones saved by an earlier version are
  encrypted the first time they're read, so nothing is lost on update. If the saved identity can't be opened, the app
  says so and keeps it instead of quietly making a new one; Settings then offers Import or New identity.
- Moving to a new phone with Android's copy of app data brings the nickname, favourites and settings, but not the
  identity and passwords: they can't be opened on another phone. Export the identity first and import it on the new
  phone to stay the same user; otherwise the new phone makes a new identity.
- Chat history: channel chats, private chats (by the other person's identity) and join and leave lines
  are kept on the phone per server and shown again when you connect to the same server, with the day on lines from
  before today ("21:36 - Mon", "21:36 - 6 Oct"). At most 90 days and 300 lines per conversation; older lines go.
  Pokes aren't kept. It's encrypted with the same Keystore key as the passwords, left out of Android's backups and
  moves to a new phone, and simply starts over if it can't be opened. Settings > General > Clear chat history removes
  it. Closing a private chat's tab keeps its lines: they're back when the tab opens again.
- Screen and camera streams (TeamSpeak 6's):
  - WATCH someone streaming in your channel: in a box on the server screen with their sound (on/off, volume), a
    small window, and full screen turned the way the picture is (wide: sideways, tall: upright)
  - share your camera (front or back, 480p, 720p or 1080p, flip while sharing) or your screen; tap your small picture to
    make it bigger inside the share bar (a green bar with a live dot and round buttons)
  - the camera's picture stays upright for viewers however the phone is held, also with auto-rotate off; the
    rotate button locks the current turn instead. Your preview stays the right way up to you, and the
    round buttons' icons turn with the phone, as in a camera app
  - both cameras at once on phones that can: the back camera big and the front camera small in a corner (Swap
    switches them)
  - a watched stream keeps playing in a small window over other apps when you leave the app (or with the player's
    small-window button); closing the window stops watching
  - the TeamSpeak app can watch and be watched; the stream mark shows in every channel
  - streams use TeamSpeak's own STUN servers, plus an optional relay (TURN) server in Settings > Advanced
- Tapping the server name shows the server's info: welcome message, users, channels, age, website and version.
- Screenshots of the screens are made with example data (`scripts/dev.sh screenshots`).

- Connects to a TeamSpeak server with a nickname, an address and an optional server password. The
  TeamSpeak identity is created on first use and kept, in the format TeamSpeak apps export.
- Shows the channel tree and who is in each channel, with talking, away and muted states. Tap a channel to join it;
  password-protected channels ask for the password.
- Plays voice (Opus), also with the screen off or another app open. A notification shows the connection and has a
  Disconnect button.
- Builds and tests in Docker. The tests check the connection code against known-good results and against a
  real TeamSpeak server.
