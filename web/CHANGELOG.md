# 网页版 Changelog

本文件记录 `web/`（浏览器客户端 `web/client` 与网关 `web/gateway`）的改动；
Android 应用的版本记录在仓库根目录的 [CHANGELOG.md](../CHANGELOG.md)。

## 网页版网关（未发布）

- 新增 `web/gateway`：一个纯 JVM 服务，直接复用 `tsproto` 协议模块（与 App 完全同一份代码），通过 WebSocket 把 TeamSpeak 连接交给浏览器。
- 浏览器打不开 UDP socket，所以语音和信令由网关代为持有；音频是裸 Opus 帧直通，不转码，浏览器用 WebCodecs 自行编解码。
- 频道树、成员与说话状态由网关用 `ServerModel` 解析后以全量快照推送（变化时最多每 120ms 一次），前端无需重写协议解析。
- 支持连接（含服务器密码）、断开、切换频道（含频道密码）、频道与私聊文字、改名、静音状态同步。
- 拒绝内网、回环、链路本地等地址，避免这台机器被当成探测内网的代理；全局 96 个连接上限，单 IP 4 个。
- 接入控制：`ALLOWED_ORIGINS` 用浏览器强制的 Origin 头做白名单（WebSocket 本身不受同源策略约束，此前任何网页都能连），`ACCESS_TOKEN` 要求连接后第一帧先发口令并通过 `/auth` 自述是否需要，超时或错误以 1008 关闭；口令走消息不走 URL，避免落进访问日志，比较用常量时间。
- 移除了 `anyHost()` 的宽松 CORS 配置，改为按 `ALLOWED_ORIGINS` 精确放行，不配则完全不开跨域。
- `TsClient` 新增 `close()`，并让所有跨线程入口在实例释放后安静丢弃；此前每次连接结束后会留下一个协议线程。
- 新增频道文件区：浏览、下载、上传、新建文件夹、改名、删除。文件本来就在 TeamSpeak 服务器的频道文件区里，所以 App 与官方客户端读到的是同一批，网关不需要磁盘和清理；上传、删除、改名、建目录这些都动服务器上的东西，同频道的人都跟着变，所以每个都带确认。
- 文件字节**不走 WebSocket 而走 HTTP**（`/files/download`）：一帧上限 64 KB，搬大文件要自己写分片、重组和流控，而 HTTP 这三样现成、还能给出真进度和取消。WS 只负责列目录。
- 端点用状态快照里的 `fileToken`（`X-File-Token` 头，`WebSession.fileSessions`）认会话：令牌只发给对应标签页、断开即作废，放头里不会落进反代的访问日志。
- 下载先查一次目录再开流，好让「文件不存在 / 是文件夹 / 太大 / 没权限」回一个说得清的状态码，而不是已经发了 200 的半截响应；没权限时把服务器卡住的那一项（如 `i_ft_needed_file_download_power`）一并带出来。
- 密码频道的文件区：进频道时记下密码的哈希（`Crypto.hashPassword`，与安卓端一致），之后读文件自动带上。
- 修了空文件夹被当成会话级错误弹出横幅的问题：服务器对空目录回 `1281 database empty result set`，带 `return_code` 的错误是某条命令的应答，该由发起方处理，不该污染会话状态。
- 新增 `web/gateway/test/files.mjs`：列目录 → 进一层文件夹 → 下载比对，把命令线和字节线一起验。全程只读，不在服务器上留东西（写操作已另行支持，脚本不碰）。
- 频道文件区新增写端点：`POST /files/upload`（流式上传，`overwrite=1` 覆盖）、`DELETE /files`、`POST /files/rename`、`POST /files/mkdir`；都走 `X-File-Token`，同名上传/改名冲突回 `409`，上传不落网关磁盘。
- 部署提供 `web/Dockerfile`、`installDist` 产物与 systemd 示例，前端构建产物放在 `web/client/dist` 时由网关一并托管。

### 网页版 myTeamSpeak 账号（未发布）

- 网关新增 `AccountSession`，把 myTeamSpeak 登录、云书签、好友列表和账号头像接到网页版；登录与语音连接解耦，可以只登录不连服务器，离开服务器也不会退出账号。
- 账号会话只存在网关内存里：浏览器不保存密码、令牌或书签密钥；退出登录会撤销网关创建的 myTeamSpeak 会话。
- 新增「记住登录」：密码登录成功后，网关把会话（令牌、书签密钥、语音身份材料）留在自己的内存表 `RememberedAccounts`，只把一枚随机 id 交给浏览器，落在 `huixiang_account` 这个 `HttpOnly` + `SameSite=Lax` 的 cookie 里（JS 读不到）。之后打开或刷新页面会自动恢复登录和云书签，不必再输密码；有效期 30 天，`ACCOUNT_REMEMBER_DAYS` 可改，设为 0 关闭。网关重启、会话在别处被撤销或 id 过期后仍需重新登录，此时前端会顺手删掉失效 cookie；只有显式「退出登录」才提前作废（`DELETE /account/resume`），关标签页不影响。
- 新增 `POST /account/resume`：用 remember 消息里的 id 换 cookie，只认网关自己发出、尚未过期的 id；`DELETE /account/resume` 忘掉会话并回 `Max-Age=0`。cookie 在 HTTPS（或反代 `X-Forwarded-Proto: https`）下带 `Secure`。
- 登录后连接会携带 `MyTsVoiceIdentity`，服务器可以显示账号徽章；连接成功后再拉取签名证书、头像和徽章（`updatemytsdata`）。连接后再登录不会追溯，重新连接才生效。
- 云书签由 `MyTsBookmarks` 在网关内解密，地址、昵称和服务器密码随 `bookmarks` 消息下发；密码只用于填隐藏输入框，不显示、不写 localStorage。
- 好友列表用 `MyTsAccount.contacts()`，并用公开 Matrix 资料补显示名；无在线状态。
- 新增好友云聊天（Matrix）：网关新增 `CloudChatSession`，用登录会话换 `MyTsAccount.chatCredentials()` 后登录 `MatrixCloud`，按好友 Matrix id 复用/新建直聊房，后台每 4 秒 `sync` 长轮询（`timeout=0`）增量拉消息；`send` 带幂等 `transactionId`；私聊只在内存转发、不落盘。
- 云聊天 token 过期自动重登：轮询遇 401 / `M_UNKNOWN_TOKEN` 时用同账号、同设备后台 `relogin()` 并重定游标（不重放旧消息），失败才提示会话失效。
- 加密房间只读：对方端到端加密的会话只能显示「[加密消息]」，不能解密也不能发送。
- 新增好友头像代理 `GET /cloud-avatar/<matrixId>`：网关代取对方 Matrix 资料的 96×96 头像缩略图并缓存，无头像/失败回退首字母；域名只信任官方 `chat*.tmspk.net` / `teamspeak.com`，其余 404。
- 消息协议新增上行 `cloud`（connect/disconnect/open/send/history/read/refresh）与下行 `cloud` / `cloudChat` / `cloudMessage` / `cloudSend`；`contacts` 每项新增 `matrixId`。
- 新增账号头像 HTTP 端点：`POST /account/avatar`（PNG，≤ 512 KB）和 `DELETE /account/avatar`，凭账号消息里的 `fileToken` 认会话；浏览器先裁成 320×320 的 PNG。
- 成员快照新增 `avatar` / `avatarName` / `myTeamSpeakAvatar`，聊天消息也带上头像字段；新增 `GET /avatar/<avatarToken>/<clientId>?v=<flag>`，按会话下载并缓存服务器头像。该令牌只能读头像，读不到频道文件。
- 消息协议新增上行 `account`（login/logout/refresh），下行 `account` / `bookmarks` / `contacts`；状态快照新增 `avatarToken`。CORS 增加 `DELETE` 与头像令牌头，开发代理补上 `/avatar` 与 `/account`。

### 网页前端（web/client）

- React 18 + TypeScript + Vite + Semi Design，构建产物由网关同源托管，一个进程同时提供页面和 `/ws`。
- 连接页新增 myTeamSpeak 账号卡片：可登录/退出/刷新、查看云书签和好友、设置或删除头像；游客表单保留，不登录直接填地址即可进入。
- 登录后默认填入账号昵称；状态栏和提示明确说明登录状态下连接会携带账号身份，要完全匿名需先退出。
- 云书签点一条即填入地址、昵称和服务器密码；好友列表显示昵称/备注，可从服务器页顶栏「好友」打开云聊天。
- 新增 `UserAvatar` 组件，舞台、聊天、顶栏和右栏统一按「账号头像 > 服务器头像 > myTS 头像 > 首字母」回退。
- 右栏新增账号卡：头像、邮箱、换/删头像、刷新、退出和好友列表；手机上隐藏，账号入口仍在连接页。
- 服务器页顶栏新增「好友」按钮（仅登录后显示，位于「通知」开关旁）：点开从右侧滑出好友抽屉，列出好友并用 Matrix 头像（`/cloud-avatar`）或首字母展示；点好友即开一对一云聊天，含消息气泡、加载更早、刷新、断开；加密会话只读。新增 `FriendsPanel` 组件与配套样式。
- 离开服务器只发 `leave`，不关 WebSocket，账号、书签和好友在本次页面会话内保留；页面刷新或关闭才断开。
- 连接页支持地址（含 `ts3server://` 邀请）、昵称、服务器密码、访问口令与最近连接；地址或昵称不合法时按钮不可点。
- 服务器页为三栏布局：频道树（带成员与说话高亮）、频道文字、当前频道成员，底部麦克风/扬声器/离开控制条；频道密码、改昵称走弹窗。
- 语音上行用 AudioWorklet 攒 20ms 一帧交给 `AudioEncoder`，下行每个说话人一个 `AudioDecoder` 加按时间排程的播放队列，落后 0.4 秒自动重新对齐避免延迟累积；全程不转码。
- 身份、口令、昵称、最近连接存 localStorage；身份留着才能保住服务器上的权限组。
- 修了网关静态文件路由取不到 tailcard 参数的问题：此前所有 `/assets/*` 都返回 index.html，脚本因 MIME 不匹配加载失败。
- 新增 `web/client/test/shot.mjs`：用本机 Edge headless + CDP 打开页面、执行脚本、截图并收集控制台报错，可配 `FAKE_MIC=1` 用模拟麦克风验证语音。
- 频道头部新增「文件」入口，右侧抽屉浏览当前频道的文件区：面包屑穿目录、下载带真实进度；登录后还可上传（带进度）、新建文件夹、改名、删除，写操作都带确认，同名上传弹「覆盖」确认后重传。
- 新增桌面消息通知：服务器页顶栏的「通知」开关，开启后收到私聊、或页面不在前台/不在当前频道时收到频道消息会弹系统通知；权限在点击开关时申请，被拒绝或不支持会提示；同会话的连续消息按 `tag` 替换不刷屏，点通知把页面拉回前台。
- 说话方式从两档扩成三档：**自动（本地人声检测）/ 常开 / 按住说话**，默认自动并存 `huixiang.micMode`。自动档在 `mic.ts` 里按帧算 RMS（`VAD_THRESHOLD = 0.015`、`VAD_TAIL_MS = 600`），安静时只监听不编码，不再像常开那样把静音帧也发给服务器；按住说话松手时才把采集静音。

### 部署（web/deploy）

- 网关改为**独立 Gradle 构建**（`web/gateway/settings.gradle.kts`，直接引 `tsproto` 源码），不再加入主工程：服务器上不需要 Android SDK，Docker 镜像也不必复制整个 `app/`。
- `HOST` 环境变量，默认改为 `127.0.0.1`：8080 不再默认对公网暴露，外部必须经 Nginx 的 TLS。确实要直连才设 `HOST=0.0.0.0`。
- 新增 `web/deploy/DEPLOY.md`：从空服务器到验收的完整手册，含 systemd、Nginx 四个必需的头、证书、低配服务器的本地构建上传方案、故障排查表与回滚流程。
- 新增 `web/deploy/verify.sh`：部署后自检 7 项（存活、口令配置、首页、静态资源 MIME、同源握手、陌生来源拒绝、本站来源握手）。WebSocket 握手用原始 socket 测——`curl` 会自己改写 `Connection` 头，拿到的永远是 200。
- 修正 `web/Dockerfile`：原版没有 COPY `app/`，而 `settings.gradle.kts` 里 include 了 `:app`，配置阶段必然失败；改为三阶段构建（node 编前端 → JDK 编后端 → JRE 运行）并补 `.dockerignore`。
