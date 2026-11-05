# 回响 · 网页版网关

浏览器打不开 UDP socket，而 TeamSpeak 的语音和信令全走 UDP 9987，所以网页版必须有一个服务端替浏览器持有真正的连接。这个网关就是那一段：它跑的是 App 里**同一份**协议代码（`tsproto` 模块），不重写、不转码。

```
浏览器  ──WebSocket──▶  网关（本模块）  ──UDP 9987──▶  TeamSpeak 服务器
       状态 / 命令                 tsproto（6.5k 行，和 App 完全同一份）
       裸 Opus 帧
```

## 为什么这样切

- `tsproto` 是纯 JVM 模块，只依赖 `java.net` 和 `javax.crypto`，**没有一个 Android import**，所以能原样搬到服务端跑。
- `TsClient` 对外发的就是裸 Opus 帧（`onVoice` / `sendVoice`），浏览器有 WebCodecs 原生编解码，所以语音**不转码、不解包**：一个字节都不改地直通。
- 频道树、成员、说话状态由网关用 `ServerModel` 解析好再推给前端，前端只负责渲染，不用重写一遍协议解析。

## 跑起来

前端在 `web/client`（React + Semi Design），构建一次由网关一起托管，浏览器访问 `http://网关:8080` 就行：

```bash
cd web/client && npm install && npm run build     # 产物到 dist/
```

开发前端时用 `npm run dev`（5173 端口，`/ws` 反代到网关），细节见 [client/README.md](client/README.md)。

网关需要 JDK 21，**不需要 Android SDK**——它是独立构建（`web/gateway/settings.gradle.kts`），只引 `tsproto` 的源码。仓库自带 `.tooling/java/jdk-21.0.2` 的话直接用：

```bash
export JAVA_HOME="$(pwd)/.tooling/java/jdk-21.0.2"
export GRADLE_USER_HOME="$(pwd)/.tooling/gradle-cache"
cd web/gateway && PORT=8080 ../../gradlew run
```

健康检查：`curl http://127.0.0.1:8080/healthz` → `ok`

### 联调脚本

`test/` 下的脚本都是直接连 `ws://127.0.0.1:8080/ws` 的真实客户端，不依赖浏览器：

```bash
cd web/gateway/test

node smoke.mjs                                  # 只验证 WS 管线和地址防护
node smoke.mjs pi.animal26.site                 # 连真实服务器，打印频道树
node flow.mjs  pi.animal26.site                 # 连接 → 切频道 → 发文字，全流程
node voice.mjs pi.animal26.site                 # 两个会话同频道互发 Opus 帧，验证语音链路
node files.mjs pi.animal26.site                 # 文件：列目录 → 进文件夹 → 下载比对（只读，不写）
node chan.mjs  pi.animal26.site                 # 频道管理：发一条建频道命令，验证 chanOp 回路（有权限会真的建）
node security.mjs 你的口令                       # Origin 白名单与访问口令，逐项打勾
```

`flow.mjs`、`voice.mjs` 和 `files.mjs` 会真的在那台服务器上以随机昵称登录一次，结束后离开。

`files.mjs` 是只读的，不会在服务器上留下任何东西（上传、删除、改名这些写操作已支持，但脚本不碰）。想看别的频道的文件区：`CHANNEL=2 node files.mjs pi.animal26.site`。

网关配了口令时，前三个脚本加 `TOKEN=你的口令`，端口不同加 `WS=ws://127.0.0.1:端口/ws`：

```bash
TOKEN=你的口令 WS=ws://127.0.0.1:8081/ws node flow.mjs pi.animal26.site
```

## 当前能力

**已经能用**

| 能力 | 说明 |
|---|---|
| 匿名进入 | 游客身份，网关按需生成 Ed25519 身份并下发；前端存下来复用，才能在服务器上保留权限 |
| myTeamSpeak 登录 | 连接页可登录，也可直接以游客进入；登录状态在网关内存保留、浏览器拿 HttpOnly cookie 认领，默认 30 天不用重输密码 |
| 云书签 | 登录后读取账号的服务器书签；点一条就填入地址、昵称和服务器密码（密码不显示，也不写 localStorage） |
| 好友列表 | 登录后显示 myTeamSpeak 好友的昵称/备注；好友在本服务器在线时走 TeamSpeak 服务器私聊（官方客户端可见），不在时走 Matrix 云聊天（跨服）；列表右侧标注「在线 / 云聊天」；头像优先在线成员的服务器/myTS 头像，其次 Matrix，最后首字母 |
| 头像 | 自己的 myTeamSpeak 头像可上传/更换/删除；频道成员显示服务器头像或公开的 myTS 头像，失败退回首字母 |
| 连接 | 地址 + 端口（默认 9987）、昵称、服务器密码、直接进指定频道（含频道密码）；信息栏显示延迟（浏览器↔网关 + 网关↔服务器） |
| 频道树 | 名称、密码标记、话题、人数上限、编解码器、权限等级、分隔符频道 |
| 频道管理 | 建 / 删 / 改频道（名称、描述、类型、编解码与音质、人数上限、密码），踢人与封禁（时长、原因）；权限由服务器判定，失败以中文提示 |
| 成员列表 | 昵称、UID、说话状态、离开、输入/输出静音、talk power、优先发言人、频道组、服务器组 |
| 成员进出提示 | 有人进入或离开我所在频道时，右下角文字提示并响一声（进场/离场不同音高）；只针对自己所在频道，默认常开、无开关 |
| PWA / 安装 | 可作为 PWA 安装到桌面或手机主屏（manifest + Service Worker）；断网时能打开外壳并提示需联网；静态资源缓存、实时语音/文件等动态请求不缓存 |
| 切换频道 | 含频道密码 |
| 文字 | 频道消息和私聊的收发 |
| 改名 | |
| 静音 | 麦克风和扬声器状态同步到服务器；进服默认闭麦也会主动上报一次 `client_input_muted`；离开/掉线时记住开关，14 天内再进自动恢复（超过则回到默认） |
| 语音 | 双向裸 Opus，浏览器 WebCodecs 编解码 |
| 屏幕 / 摄像头串流 | WebRTC 直连，能看别人也能自己播；画面不经网关，这里只走信令 |
| 文件 | 频道文件区：浏览、下载、上传、新建文件夹、改名、删除（后四个带确认）。文件在服务器上，安卓 App 和官方客户端看到的是同一批；列目录走 WS，字节和写命令走 HTTP |
| 断开 | 主动离开，之后可再连 |

**还没接**

暂无。

## myTeamSpeak 账号

连接页可以登录，也可以不登录直接填地址进；登录不是连接服务器的前置条件。

- **密码只换会话，不落盘。** 邮箱和密码经这条已认证的 WebSocket 交给网关，换成 myTeamSpeak 会话；会话只存在网关内存里。浏览器不保存密码或令牌，只拿到一枚 `HttpOnly` cookie 里的随机 id，JS 读不到——登录成功后网关用它把这台浏览器「记住」30 天（`ACCOUNT_REMEMBER_DAYS` 可调，0 关闭），刷新或重新打开页面会自动恢复登录；网关重启、会话过期或在别处被撤销后仍需重新登录。退出登录会立即作废这份记忆。公开部署仍然必须走 TLS，并尽量配 `ACCESS_TOKEN`。
- **登录后连接带账号身份。** 网关把 `MyTsVoiceIdentity` 放进 `TsClient.Options`，服务器就能显示账号徽章和签名头像；连接后再登录不会追溯，重新连接一次才生效。
- **云书签在服务端加密。** 网关用登录密码派生的密钥解锁，再把地址、昵称和服务器密码发给这一个标签页。密码只用来填隐藏的输入框，不显示、不写 localStorage；解锁后的书签密钥只留在网关内存，随「记住登录」的会话一起保留，所以恢复登录后云书签照常可读。
- **好友可云聊天。** 显示昵称/备注（myTeamSpeak 联系人接口没有在线状态）；登录后可打开好友云聊天，走 Matrix 云端聊天（`MatrixCloud`），加密房间只读。详见下文「好友云聊天」一节。
- **头像。** 自己的 myTeamSpeak 在线头像可上传、更换、删除；浏览器先裁成 320×320、压到 512 KB 以内的 PNG，再走 `POST /account/avatar`。频道成员优先显示服务器头像，其次对方公开的 myTS 头像，都失败就退回首字母。
- **匿名入口保留。** 登录状态下连接会携带账号身份；要完全匿名，先在账号卡片里退出再连。
- **退出会撤销会话。** 点「退出」时网关调用 `deleteSession` 撤销它创建的会话；直接关页只丢弃内存里的会话，不等同于在服务端退出。

## 部署

> **要交给别人（或别的 agent）去部署，直接用 [deploy/DEPLOY.md](deploy/DEPLOY.md)**：那份是从空服务器到验收的完整手册，自包含、每步带验收命令，还配了 `deploy/verify.sh` 自动核对。下面这节是给熟悉项目的人看的速查。

### 用 wss 对外（域名锁定）

网关自己只听明文 `ws://`。浏览器要 `wss://`，就得在它前面放一层 TLS——Nginx 或 Caddy 都行。这一步和 `ALLOWED_ORIGINS` 是配套的：Origin 校验只有在 HTTPS 页面上才有意义。

Nginx：

```nginx
server {
    listen 443 ssl http2;
    server_name ts.example.com;
    ssl_certificate     /etc/letsencrypt/live/ts.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/ts.example.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        # WebSocket 握手必须带这两个头，少了会一直 400
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        # Origin 的同源判断靠它，不传的话所有请求都会被当成跨域
        proxy_set_header Host $host;
        # 真实客户端 IP：不传的话所有人共用反代地址，单 IP 4 连接的限制会变成全局 4 个
        proxy_set_header X-Forwarded-For $remote_addr;
        # 语音是长连接，默认 60 秒会被掐断
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
}
```

然后网关按域名锁：

```bash
ACCESS_TOKEN='一长串随机字符' \
ALLOWED_ORIGINS='https://ts.example.com' \
PORT=8080 ./web/gateway/build/install/webgateway/bin/webgateway
```

Caddy 更省事（自动签证书）：

```
ts.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

Caddy 默认会传 `Host` 和 `X-Forwarded-For`，WebSocket 也自动处理。

**反代之后要注意**：`X-Forwarded-For` 只在直连方是本机或内网时才会被采信（防止客户端自己伪造地址绕过限流）。如果反代在另一台机器且不在同一内网，IP 限流会按反代地址算，需要改 `clientIp()` 里的判断。

### Docker（推荐）

```bash
docker build -f web/Dockerfile -t huixiang-web .
docker run -d --restart=always -p 8080:8080 --name huixiang-web huixiang-web
```

### 直接跑 jar

```bash
cd web/gateway && ../../gradlew installDist
PORT=8080 web/gateway/build/install/webgateway/bin/webgateway
```

systemd 单元示例：

```ini
[Unit]
Description=Huixiang web gateway
After=network.target

[Service]
ExecStart=/opt/huixiang/bin/webgateway
Environment=PORT=8080
Restart=always
User=huixiang

[Install]
WantedBy=multi-user.target
```

前面挂 Nginx 反代 `/ws`（要带 `Upgrade` / `Connection` 头）和静态前端，或者把前端构建产物放到 `web/client/dist`，网关会自动托管。

### 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `PORT` | `8080` | 监听端口 |
| `WEB_DIST` | `web/client/dist` | 前端构建产物目录，存在就托管，不存在只提供 `/ws` |
| `LOG_LEVEL` | `INFO` | `DEBUG` 会打印每条服务器命令 |
| `ACCESS_TOKEN` | 空 | 访问口令。设了就必须先认证才能用，见下一节 |
| `ALLOWED_ORIGINS` | 空 | 允许的页面来源，逗号分隔，如 `https://ts.example.com`。空表示只接受同源页面 |
| `CHAT_HISTORY_DIR` | `chat-history` | 频道聊天历史的落盘目录。每台服务器一个 JSONL，90 天 / 每频道 300 条。设成空串则只放内存，网关重启就清空 |
| `ACCOUNT_REMEMBER_DAYS` | `30` | myTeamSpeak 登录在浏览器里保留多少天：浏览器只存一个 `HttpOnly` cookie 里的随机 id，会话本身在网关内存。设 `0` 关闭「记住登录」，刷新即需重登 |

### 频道聊天历史

TeamSpeak 服务器不保存频道消息，所以「历史」只能由收消息的一方记下来。网页版是两份：

- **浏览器本地**（默认，IndexedDB，库名 `huixiang-chat`）：谁在看谁记，换浏览器就没了；
- **网关闭环**（可选）：网关连着的服务器上所有频道的消息都会记一份，同一网关下的浏览器/设备都能问到，也能补上本地没攒到的部分。

进频道时前端先读本地最近一屏（30 条），再向网关要同一页；两边的行按「时间 + 发送者 + 文本」去重合并。滚到顶时带上一页最早一条的时间继续翻，直到两边都没有更早的。只记频道消息，私聊不存——网关不区分身份地存私聊，谁连上都能读到别人的话。

## 好友云聊天

登录 myTeamSpeak 账号后，服务器页右上角（「通知」开关旁）会出现「好友」按钮：

- 点开从右侧滑出好友抽屉，列出账号的好友（昵称 / 备注），右侧标注该好友当前是「在线」还是「云聊天」；
- 点某个好友开始一对一聊天，**按对方是否在本服务器自动选通道**：
  - 对方正连在当前这台服务器 → 走 **TeamSpeak 服务器私聊**（`sendtextmessage targetmode=1`），官方 TS5/TS6 客户端收得到、也显示得出来；
  - 对方不在本服务器 → 退回 **Matrix 云端聊天**（`MatrixCloud`，官方 `chat*.tmspk.net` / `teamspeak.com` 域名），跨服务器也能聊，但要求双方都登录了 myTeamSpeak 账号；
- **只对已登录用户开放**：游客进来看不到好友按钮，网关收到 `cloud` 命令也会直接忽略。

实现要点：

- 好友身份用稳定的 `matrixId` 作 id（myTS 联系人里 `identifier` 常为空）：网关据此在 Matrix 上定位对方，避免认错人。
- 网关用登录会话换 `MyTsAccount.chatCredentials()`，再据此登录 Matrix；`open(friend)` **优先选「对方所在 homeserver 上、对方已加入」的房间**，其次最活跃的房间，一个都没有才新建直聊房并邀请；`send` 带幂等 `transactionId` 防重复，后台每 4 秒 `sync` 长轮询（`timeout=0` 立即返回）增量拉消息。
- **加密房间只读**：对方开了端到端加密的会话只能看到「[加密消息]」，既不能解密也不能发送。
- **token 过期自动重登**：Matrix access token 在轮询中被判 401 / `M_UNKNOWN_TOKEN` 时，网关用同一账号、同一设备后台重新登录并重定游标（不重放旧消息），用户无感；只有重登也失败才提示「会话已失效」。
- **好友头像**：好友当前在本服务器时，直接复用服务器成员的服务器头像 / 公开 myTS 头像；不在时 `GET /cloud-avatar/<matrixId>` 代取对方 Matrix 资料头像（96×96）并缓存；都没有则首字母。Matrix 域名只信任官方 `chat*.tmspk.net` / `teamspeak.com`，其余一律 404。
- 私聊消息**不落盘**：服务器私聊只在实时连接里转发，Matrix 私聊只在网关内存转发并推给当前标签页，都不像频道历史那样存 JSONL。

## 频道管理

浏览器可以直接建 / 删 / 改频道，以及踢人、封禁——权限完全由 TeamSpeak 服务器判定，网关不额外放行或拦截。

- 上行 `{"t":"channel"}`，`act` 为 `create` / `edit` / `delete` / `kick` / `ban`；网关用 `tsproto` 的 `ChannelEdits` 生成对应命令：`channelcreate`、`channeledit`（换父级时补 `channelmove`）、`channeldelete`、`clientkick`、`banclient`。
- 一次操作可能拆成多条命令（例如「改名 + 换父级」= `channeledit` + `channelmove`），它们共用一个 `return_code`；网关把结果聚合成一条下行 `{"t":"chanOp"}`，`ok` 为 `true` 才算全部成功，否则 `error` 带中文原因。
- 常见失败是「没有权限执行这个操作（insufficient client permissions）」——需要在该服务器有对应的 `i_channel_*` 权限。
- 编辑以服务器上的当前值为基准做 diff（`ChannelEdits.edit`）：提交哪些字段就改哪些，没提交的不动。

## 接入控制

网关是个 UDP 代理，谁能让它连，谁就能借这台机器访问外面的 TS 服务器。所以有两道门，挡的是不同的人。

### 第一道：Origin 白名单

浏览器的 WebSocket **没有同源策略**——任何网页的 JS 都能连你的 `/ws`，把访客的网络借去当代理。`Origin` 头由浏览器自己填，页面脚本改不了，所以它是唯一可靠的“这是不是我的页面”的判断依据。

```bash
ALLOWED_ORIGINS=https://ts.example.com
```

不设这个变量时只接受同源（Origin 的主机名与请求的 Host 一致），也就是网关把前端一起托管的情况，那种场景不用配。前后端分域部署才需要。

被拒的请求握手阶段直接 403，不会占用连接槽。

### 第二道：访问口令

```bash
ACCESS_TOKEN=你自己想一串
```

口令在哪都能挡，脚本也不例外。流程是：

1. 前端先 `GET /auth`，返回 `{"required": true|false}`，据此决定要不要弹输入框；
2. 连上 `/ws` 后**第一帧**必须是 `{"t":"auth","token":"…"}`；
3. 通过了服务端回 `{"t":"ready"}`，之后才能发 `connect` 等消息；
4. 5 秒内没认证，或者口令不对，服务端以 `1008` 关闭。

口令放在消息里而不是 URL 里，是为了不落到 Nginx 的 access log。服务端用常量时间比较，猜错和猜对耗时一样。

### 这两道门做不到什么

**它们不是登录。** 前端打包出来的 JS 是公开的，口令只要进了浏览器就一定能被拿到——区别只在于它是用户手输的还是写死在代码里。所以：

- 口令应该**由使用者输入**、存在 localStorage，不要编译进前端；
- 谁拿到口令，谁就能以他自己的身份用这个网关（他能连任意 TS 服务器，但不是“你”的身份）；
- 要做成“每个人的独立账号、能封禁、能审计”，那是账号体系 + 会话，不是加一个 token 能解决的。

按使用人数选：

| 场景 | 做法 |
|---|---|
| 只有自己 | `ACCESS_TOKEN` 设一个长随机串，前端给个输入框 |
| 小团队（同事） | 一个共享口令，人手一份；换人时换口令 |
| 对外开放 | 别只加 token，要么上账号，否则就是给人免费代理 |

`ALLOWED_ORIGINS` 挡不住 `curl`，`ACCESS_TOKEN` 挡不住有口令的人——两者一起才构成“只有我的页面 + 只有我认可的人”。

## WebSocket 协议

一个页面开一个 `/ws`。**文本帧走 JSON**（状态和命令），**二进制帧只走语音**。

如果服务端配了 `ACCESS_TOKEN`，连接后的第一帧必须是 `{"t":"auth","token":"…"}`，服务端回 `{"t":"ready"}` 之后才能发别的（见「接入控制」）。

### 上行（浏览器 → 网关）

| 消息 | 字段 |
|---|---|
| `{"t":"auth"}` | `token` — 仅在服务端要求口令时，且必须是第一帧 |
| `{"t":"connect"}` | `host` `port`(9987) `nickname` `password` `identity` `channel` `channelPassword` |
| `{"t":"leave"}` | 断开，之后可以再 connect |
| `{"t":"ping"}` | `v` 探针序号；网关原样回 `pong`，用来量浏览器 ↔ 网关的往返 |
| `{"t":"join"}` | `cid` 频道 id，`password` 可选 |
| `{"t":"say"}` | `text`，`channel` 频道 id（频道消息）或 `client` 成员 id（私聊） |
| `{"t":"history"}` | `channel` 频道 id，`before` 上一页最早一条的时间（0 = 从最新往回取），`limit` 条数 |
| `{"t":"rename"}` | `name` |
| `{"t":"mute"}` | `input` `output` 布尔 |
| `{"t":"stream"}` | 串流的信令，见下面的「串流」一节 |
| `{"t":"file"}` | `act`（只有 `list`）`cid` `path`，见下面的「文件」一节 |
| `{"t":"channel"}` | 频道管理，`act`：`create`（`name` `description` `kind` `password` `newPassword` `codec` `quality` `maxClients` `parent` `after`）、`edit`（`cid` + 上述可选字段）、`delete`（`cid`）、`kick`（`clid` `fromChannel` `reason`）、`ban`（`clid` `seconds` `reason`）；结果走下行 `chanOp` |
| `{"t":"account"}` | `act`：`login`（`email` `password` `otp`）、`logout`、`refresh`；与语音连接无关 |
| `{"t":"cloud"}` | 好友云聊天，`act`：`connect`（连 Matrix）、`disconnect`、`open`（`friend` 好友标识）、`send`（`room` `text`）、`history`（`room`）、`read`（`room` `event`）、`refresh`；仅登录后可用 |

`identity` 是身份导出串（`Identity.export()` 的字符串）。**第一次连接前传空，网关会生成一个并通过 `identity` 消息下发**，前端存到 localStorage，之后每次带上——不带的话每次都是新身份，服务器上的权限不会继承。

二进制上行：`[0x01][codec][opus…]`，`codec` 是 4（Opus Voice）或 5（Opus Music），取当前频道的。

### 下行（网关 → 浏览器）

| 消息 | 说明 |
|---|---|
| `{"t":"ready"}` | 口令通过，可以开始用了 |
| `{"t":"identity","v":"…"}` | 网关生成的身份，存起来 |
| `{"t":"state", …}` | 全量状态快照，变化时最多每 120ms 一次 |
| `{"t":"chat", …}` | 收到文字消息：`from` `name` `channel` `mode` `text` `time` |
| `{"t":"history", …}` | 一页频道历史：`channel` `lines[]`（每行 `time` `from` `name` `uid` `text`）`more`（还有更早的） |
| `{"t":"files", …}` | 一层目录的文件列表：`cid` `path` `entries[]` `error`，见「文件」 |
| `{"t":"account", …}` | 账号状态：`phase` 是 `guest` / `busy` / `signedIn`，`username` `email` `avatarUrl` `error`，以及头像上传用的 `fileToken` |
| `{"t":"remember","resume":"…"}` | 这次登录可以记住：前端把 `resume` POST 到 `/account/resume`，换成 `HttpOnly` cookie（30 天），以后打开页面自动恢复。只在密码登录成功、且还没有同 id 快照时发一次 |
| `{"t":"bookmarks", …}` | 云书签：`items[]`（`id` `name` `address` `nickname` `password`）`error` `loading` |
| `{"t":"contacts", …}` | 好友：`items[]`（`id` `name` `subtitle` `matrixId`）`error` `loading` |
| `{"t":"cloud", …}` | 云端聊天连接状态：`phase`（`idle`/`connecting`/`connected`/`error`）`connected` `userId` `error` `errorText` |
| `{"t":"cloudChat", …}` | 打开/刷新一个会话：`roomId` `peer` `name` `encrypted` `messages[]`（每项 `id` `sender` `body` `time` `encrypted`）`more` `friendId` `error` `errorText` |
| `{"t":"cloudMessage", …}` | 收到新消息：`roomId` `message`（结构同 `cloudChat.messages[]`） |
| `{"t":"cloudSend", …}` | 本条发送结果：`roomId` `ok` `error` `errorText` |
| `{"t":"pong","v":…}` | 上行 `ping` 的原样回应 |
| `{"t":"serverPing","v":18}` | 网关 ↔ TeamSpeak 服务器的 UDP 往返毫秒数，跟着每秒的 keepalive 更新 |
| `{"t":"stream", …}` | 串流事件：`event` 是 `started` / `stopped` / `viewer` / `viewerLeft` / `offer` / `answer` / `candidate` / `ended` / `refused` / `failed` |
| `{"t":"chanOp", …}` | 一次频道管理操作的结果：`id` `label`（中文动作名）`ok` `error`（失败为中文原因，成功为 null） |

二进制下行：`[0x02][codec][clientId:2 大端][opus…]`。`opus` 为空表示这个人停止说话。

`state` 快照：

```json
{
  "t": "state",
  "phase": "idle | connecting | connected",
  "error": null,
  "address": "pi.animal26.site",
  "nickname": "…",
  "serverName": "大伙的第二个秘密基地",
  "welcome": "…",
  "ownId": 106,
  "ownChannel": 2,
  "inputMuted": false,
  "outputMuted": false,
  "clientCount": 1,
  "fileToken": "3f9c…",
  "avatarToken": "7a21…",
  "tree": [
    {
      "id": 1, "parent": 0, "name": "基地大厅", "display": "基地大厅",
      "spacer": false, "password": false, "topic": "",
      "codec": 4, "quality": 6, "description": "", "kind": "permanent", "order": 0,
      "neededTalkPower": 0, "maxClients": -1,
      "clients": [
        { "id": 106, "nickname": "webtest", "uid": "…", "talking": false,
          "away": false, "inputMuted": false, "outputMuted": false,
          "talkPower": 0, "isTalker": false, "priority": false, "streaming": false,
          "avatar": "…", "avatarName": "…", "myTeamSpeakAvatar": "",
          "channelGroup": "…", "serverGroups": ["…"] }
      ],
      "children": []
    }
  ]
}
```

`spacer: true` 的是 TeamSpeak 的分隔符频道，不要当普通频道渲染。

### 串流（屏幕 / 摄像头）

画面**不经过网关**：浏览器和对方直接 WebRTC 对连，跟两台手机之间是一回事——网关只转发命令和双方的
SDP / ICE 地址，所以一个千兆小机器能带住很多路。共享是 mesh：每个观众一条连接，各发一份，跟安卓端一样。

上行都是 `{"t":"stream","act":…}`：

| act | 字段 | 说明 |
|---|---|---|
| `share` | `kind`(1 摄像头 / 2 屏幕) `name` `audio` | 采集已经在浏览器里做完了，这里只是让服务器开一个流 |
| `stop` | | 停止共享 |
| `watch` | `streamer` 对方 client id | 不知道 stream id 也行，网关会先 `requeststreaminfo` 再 join |
| `unwatch` | | 不看了 |
| `offer` | `to` 观众 id `sdp` | 我们的 offer，给某个观众 |
| `answer` | `to` 主播 id `sdp` | 我们对别人 offer 的答案 |
| `candidate` | `to` `candidate` `mid` `mLine` | ICE 地址，两个方向共用 |

下行 `{"t":"stream","event":…}`：`started`（拿到 stream id）、`stopped`、`viewer`（有人要看，带 `viewer`）、
`viewerLeft`、`offer`（带 `from` 和 `sdp`，已改好给浏览器）、`answer`（带 `from` 和 `sdp`）、
`candidate`、`ended`、`refused` / `failed`（带 `reason`）。

**SDP 的几处修补都在网关做**（`StreamBridge`），浏览器只面对干净的 WebRTC：

- 对方发来的 H.264 是 Constrained High（`640cXX`），浏览器不一定列这个 profile，所以给浏览器之前改成
  `42e0`，answer 回来再还原；
- 我们发出去的 offer 反过来：H.264 一律标成 `640c`，并去掉 `urn:3gpp:video-orientation`（TeamSpeak 的
  客户端接受了却不转画面，手机摄像头就会躺倒）和媒体段里没列的 payload 行；
- answer 的 `o=` 行 session version 要对齐到对方 offer 的，否则 TeamSpeak 的客户端会**一声不响地丢掉**它；
- answer 的 H.264 level 抬到 5.2（`level-asymmetry-allowed=1`），不抬的话客户端按 3.1 回，把发送卡在 720p。

NAT 打不通时要 TURN。构建时给 `VITE_TURN_URL` / `VITE_TURN_USER` / `VITE_TURN_PASS`，或者往 localStorage
写 `huixiang.turn`（`{"url":"turn:host:3478","user":"…","pass":"…"}`）。默认只用 TeamSpeak 自己的两个 STUN。

### 文件

**文件在 TeamSpeak 服务器的频道文件区里，不是网关的磁盘，也不是浏览器缓存。** 网页这边可浏览、下载，
也能上传、新建文件夹、改名、删除——后四个都动服务器上的东西，同频道的人都会跟着变，所以每个都带一次
确认，而且需要登录后、在这个频道有对应的文件权限。

正因为文件本来就在服务器上，安卓 App 和官方客户端在自己的文件浏览器里看到的是同一批，网关不需要磁盘、
不需要清理，重启也不丢东西。

命令和字节分两条线走：

| | 走哪 | 为什么 |
|---|---|---|
| 列目录 | WebSocket `{"t":"file","act":"list"}` → `{"t":"files"}` | 本来就短，往返快，一个命令不值得开一次 HTTP |
| 字节 | HTTP `/files/download` | WS 一帧上限 64 KB，一个大文件要拆成上千帧，分片、重组、流控都得自己写；HTTP 这三样都是现成的，还顺手给了进度、取消和准确的错误码 |
| 上传 | HTTP `POST /files/upload` | 大文件要流式进服务器，一条 TCP 直传；WS 帧太小，也不好带进度 |
| 删除 / 改名 / 建目录 | HTTP `DELETE /files`、`POST /files/rename`、`POST /files/mkdir` | 短命令，但都要带确认和准确错误码；和下载共用同一个会话令牌 |

```jsonc
// 上行：要某一层
{ "t": "file", "act": "list", "cid": 2, "path": "/" }

// 下行
{ "t": "files", "cid": 2, "path": "/", "error": null,
  "entries": [{ "name": "report.pdf", "folder": false, "size": 245760, "time": 1761900000000 }] }
```

`error` 非空时 `entries` 是空的：服务器拒绝（没权限、要频道密码）和「这个文件夹是空的」是两回事，得分开说。

下载端点：

```
GET /files/download?cid=2&path=/report.pdf
    带 X-File-Token: <state 里的 fileToken>
```

- **令牌认会话，不认服务器。** 它随状态快照下发、每个标签页各不相同、断开就作废，网关靠它在内存的
  `WebSession.fileSessions` 里找回「这次请求属于哪个标签页」。
- **放在头里而不是 URL 里**，所以不会落进 Nginx 的 access log；也不用 cookie，cookie 会跟着人进这个站的
  其它标签页，活得比它所代表的会话还长。
- 下载会**先查一次目录**再开流：`respondOutputStream` 一开头 200 就已经发出去了，此后再发现文件不存在，
  浏览器只能收到半截下载。多一次往返，换一个说得清的 `404` / `400` / `413`。
- 单次下载上限 1 GiB（`WebSession.MAX_FILE_BYTES`），防止一个请求把浏览器内存撑爆。
- **下载权限也是按身份和频道给的。** 被拒时服务器回 `2568`，消息里带着卡在哪一项，网关把原始原因记进日志、
  给浏览器的是一句人话（例如「服务器卡在 `i_ft_needed_file_download_power`」）——那一项正是服务器管理员
  要改的东西。
- 频道密码：进频道时用的是明文，文件区要的是它的哈希，所以网关在 `clientmove` 那一步把哈希记下来
  （`Crypto.hashPassword`，和安卓端一样），之后传文件自动带上。

写操作端点（都带同一个 `X-File-Token`）：

```
POST   /files/upload?cid=2&path=/report.pdf[&overwrite=1]   请求体是文件原始字节，需 Content-Length
DELETE /files?cid=2&path=/report.pdf
POST   /files/rename?cid=2&path=/report.pdf&name=report-v2.pdf
POST   /files/mkdir?cid=2&path=/归档
```

- **上传不落网关磁盘**：网关把请求体直接流进 `FileTransfer.upload`（`ftinitupload` 之后与服务器开一条 TCP
  写字节），本机不留副本。同名文件且没带 `overwrite=1` 时回 `409`，前端弹「覆盖」确认后重传。
- **改名先判重**：服务器会静默覆盖同名目标，所以网关先列一次目录，撞名就回 `409`。
- **删除文件夹会连同里面内容一起删**，前端因此要求二次确认。
- 写操作和下载一样受服务器权限（`2568`）与频道密码约束，错误文案一致；单次上传上限同为 1 GiB。

### 头像

成员头像是服务器频道 0 里的文件，`<img>` 又没法带 `X-File-Token` 头，所以地址里放一个**只认头像**的会话令牌：

```
GET /avatar/<avatarToken>/<clientId>?v=<avatarFlag>
```

- `avatarToken` 来自状态快照，只对这一个标签页有效，断开即作废；它只能读头像，不能读频道文件，所以就算落进反代日志也拿不到文件区。
- `?v=` 是服务器给出的头像哈希，头像一换地址就变，可以放心 `Cache-Control: immutable`。
- 网关按 `uid` / `avatarName` 算出几个可能的文件名逐个尝试，校验内容是图片后缓存在这个会话里。
- 自己登录了账号时，前端优先用账号头像（直接是公开的 https 地址），再退回服务器头像。

账号头像的写操作走 HTTP，凭账号消息里的 `fileToken`（`X-File-Token` 头）：

```
POST   /account/avatar      Content-Type: image/png，最多 512 KB
DELETE /account/avatar
```

浏览器先把图片裁成正方形、压到 320×320 / 512 KB 以内的 PNG；网关再交给 `MyTsProfile.updateOnlineAvatar()`。这和安卓端用的是同一套 myTeamSpeak 接口。

## 浏览器侧怎么解 Opus

下行帧的 payload 是标准 Opus 包，48kHz。用 WebCodecs：

```js
const decoder = new AudioDecoder({
  output: (data) => { /* AudioData → Web Audio 播放 */ },
  error: (e) => console.error(e),
});
decoder.configure({
  codec: 'opus',
  sampleRate: 48000,
  numberOfChannels: 2,
  description: opusHead, // 19 字节 OpusHead，见下
});
decoder.decode(new EncodedAudioChunk({
  type: 'key',
  timestamp: t,           // 微秒，自己按 20ms 递推
  duration: 20000,
  data: opusFrame,        // 去掉 4 字节头之后的部分
}));
```

`opusHead` 是 19 字节：

```js
const opusHead = new Uint8Array([
  0x4f,0x70,0x75,0x73,0x48,0x65,0x61,0x64, // "OpusHead"
  1,        // version
  2,        // channel count
  0,0,      // pre-skip (LE)
  0x80,0xbb,0,0, // 48000 (LE)
  0,0,      // output gain
  0,        // mapping family
]);
```

上行同理，用 `AudioEncoder` 编成 `'opus'`，48kHz，20ms 一帧（960 采样），前面加 `[1][codec]` 发过来。

## 安全与限制

- **这是一个 UDP 代理**：谁能让它连，谁就能让这台机器连任意 TS 服务器。所以要配 `ACCESS_TOKEN`，不配时启动日志会警告。内网、回环、链路本地地址一律拒绝，免得被拿来探测内网。
- `ALLOWED_ORIGINS` 挡网页，`ACCESS_TOKEN` 挡脚本和人；两者都不是身份认证，原因见「接入控制」。
- **myTeamSpeak 密码会经过网关**：网关必须代浏览器完成登录和书签解密，所以它能在这台机器的内存里看到密码。对外部署时 TLS 不是可选项；运行网关的人也因此被信任。浏览器侧不保存密码、令牌或书签密钥，关页即失；「记住登录」存在浏览器里的只是一枚 `HttpOnly` 随机 id，脚本读不到，真正的会话在网关内存。
- 账号会话只存在网关内存里，退出登录会撤销它创建的 myTeamSpeak 会话并删除浏览器 cookie；直接关页不会远程撤销，只丢弃这条标签页的连接（记住的会话保留到过期或退出）。
- `/avatar/<token>/…` 的头像令牌只能读头像（频道 0），读不到频道文件；`/account/avatar` 的写接口仍然只认 `fileToken`。
- 全局最多 96 个并发连接，单 IP 最多 4 个。反代后面所有请求都来自同一个 IP 时，这条限制会按反代地址算，需要按实际情况调整。
- 每个连接是一个 UDP socket 加一个线程，扩到几百人需要改成分片或协程。
- 身份安全等级低于服务器要求时（错误 519）只提示、不自动升级——升级很吃 CPU，放到后台跑要单独设计。
- 网页关掉连接就断，没有 App 那种后台保活。
- 文件的 HTTP 端点（下载、上传、删除、改名、建目录）都靠 `X-File-Token` 认会话，令牌只发给对应的那个
  标签页、断开即失效；但它和 `ACCESS_TOKEN` 一样不是身份认证——拿到令牌的人可以在那次会话的有效期内
  对这个频道文件区做对应的事（能写是因为面板现在支持写操作），能不能真正落地仍由服务器按身份和频道权限
  裁决。单次传输上限 1 GiB。
- 串流的画面不经过网关，但共享是 mesh：N 个人看就有 N 份上行从**浏览器**出去（不是网关的带宽）。
  5 个人看 4 Mbps 的屏幕，发布者要 20 Mbps 上行，家宽基本到顶了。
