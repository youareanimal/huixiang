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

`test/` 下三个脚本都是直接连 `ws://127.0.0.1:8080/ws` 的真实客户端，不依赖浏览器：

```bash
cd web/gateway/test

node smoke.mjs                                  # 只验证 WS 管线和地址防护
node smoke.mjs pi.animal26.site                 # 连真实服务器，打印频道树
node flow.mjs  pi.animal26.site                 # 连接 → 切频道 → 发文字，全流程
node voice.mjs pi.animal26.site                 # 两个会话同频道互发 Opus 帧，验证语音链路
node files.mjs pi.animal26.site                 # 文件：列目录 → 上传 → 下载比对 → 删除
node security.mjs 你的口令                       # Origin 白名单与访问口令，逐项打勾
```

`flow.mjs`、`voice.mjs` 和 `files.mjs` 会真的在那台服务器上以随机昵称登录一次，结束后离开。

`files.mjs` 会留一个几字节的 `web-e2e-*.txt`，验证完自己删掉。上传权限是按身份和频道给的，游客身份常常没有——被挡时它会退一步只验证下载链路，并告诉你换个频道再试：`CHANNEL=2 node files.mjs pi.animal26.site`。

网关配了口令时，前三个脚本加 `TOKEN=你的口令`，端口不同加 `WS=ws://127.0.0.1:端口/ws`：

```bash
TOKEN=你的口令 WS=ws://127.0.0.1:8081/ws node flow.mjs pi.animal26.site
```

## 当前能力

**已经能用**

| 能力 | 说明 |
|---|---|
| 匿名进入 | 游客身份，网关按需生成 Ed25519 身份并下发；前端存下来复用，才能在服务器上保留权限 |
| 连接 | 地址 + 端口（默认 9987）、昵称、服务器密码、直接进指定频道（含频道密码） |
| 频道树 | 名称、密码标记、话题、人数上限、编解码器、权限等级、分隔符频道 |
| 成员列表 | 昵称、UID、说话状态、离开、输入/输出静音、talk power、优先发言人、频道组、服务器组 |
| 切换频道 | 含频道密码 |
| 文字 | 频道消息和私聊的收发 |
| 改名 | |
| 静音 | 麦克风和扬声器状态同步到服务器 |
| 语音 | 双向裸 Opus，浏览器 WebCodecs 编解码 |
| 屏幕 / 摄像头串流 | WebRTC 直连，能看别人也能自己播；画面不经网关，这里只走信令 |
| 文件 | 频道文件区：浏览、上传、下载。文件存在服务器上，安卓 App 和官方客户端能看到同一批；命令走 WS，字节走 HTTP |
| 断开 | 主动离开，之后可再连 |

**还没接**（`tsproto` 里都有现成实现，缺的是网关这一层把它暴露出来）

| 能力 | 难度 | 说明 |
|---|---|---|
| myTeamSpeak 账号登录 | 低 | `MyTsAccount.login()`，纯 JVM，但要处理密码经手网关的问题 |
| 账号服务器书签（收藏列表） | 低 | `MyTsBookmarks.read(session)`，登录后直接拿到地址、昵称、密码 |
| 好友列表 / 云聊天 | 中 | `MyTsAccount.contacts()` + `MatrixCloud`，需要连 Matrix |
| 文件管理（删/改名/建文件夹） | 低 | `FileTransfer` 现成，缺的是一个带确认的交互（删错了别人也跟着没了） |
| 头像 | 中 | `Pictures`，需要缓存 |
| 频道管理（建/删/改/踢/封） | 低 | `ChannelEdits` 现成，加 `cmd` 白名单即可 |

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
| `{"t":"join"}` | `cid` 频道 id，`password` 可选 |
| `{"t":"say"}` | `text`，`channel` 频道 id（频道消息）或 `client` 成员 id（私聊） |
| `{"t":"rename"}` | `name` |
| `{"t":"mute"}` | `input` `output` 布尔 |
| `{"t":"stream"}` | 串流的信令，见下面的「串流」一节 |
| `{"t":"file"}` | `act`（`list` / `delete`）`cid` `path`，见下面的「文件」一节 |

`identity` 是身份导出串（`Identity.export()` 的字符串）。**第一次连接前传空，网关会生成一个并通过 `identity` 消息下发**，前端存到 localStorage，之后每次带上——不带的话每次都是新身份，服务器上的权限不会继承。

二进制上行：`[0x01][codec][opus…]`，`codec` 是 4（Opus Voice）或 5（Opus Music），取当前频道的。

### 下行（网关 → 浏览器）

| 消息 | 说明 |
|---|---|
| `{"t":"ready"}` | 口令通过，可以开始用了 |
| `{"t":"identity","v":"…"}` | 网关生成的身份，存起来 |
| `{"t":"state", …}` | 全量状态快照，变化时最多每 120ms 一次 |
| `{"t":"chat", …}` | 收到文字消息：`from` `name` `channel` `mode` `text` `time` |
| `{"t":"files", …}` | 一层目录的文件列表：`cid` `path` `entries[]` `error`，见「文件」 |
| `{"t":"stream", …}` | 串流事件：`event` 是 `started` / `stopped` / `viewer` / `viewerLeft` / `offer` / `answer` / `candidate` / `ended` / `refused` / `failed` |

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
  "tree": [
    {
      "id": 1, "parent": 0, "name": "基地大厅", "display": "基地大厅",
      "spacer": false, "password": false, "topic": "",
      "codec": 4, "neededTalkPower": 0, "maxClients": -1,
      "clients": [
        { "id": 106, "nickname": "webtest", "uid": "…", "talking": false,
          "away": false, "inputMuted": false, "outputMuted": false,
          "talkPower": 0, "isTalker": false, "priority": false, "streaming": false,
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

**文件存在 TeamSpeak 服务器上，不是网关的磁盘，也不是浏览器缓存。** 上传走
`ftinitupload`（虚拟路径，`/` 是频道文件区的根），网关只当搬运工——所以安卓 App 和官方客户端在自己的文件
浏览器里能看到同一批文件，网关也不需要磁盘、不需要清理，重启不丢东西。

命令和字节分两条线走：

| | 走哪 | 为什么 |
|---|---|---|
| 列目录 / 删除 | WebSocket `{"t":"file"}` → `{"t":"files"}` | 本来就短，往返快，一个命令不值得开一次 HTTP |
| 字节 | HTTP `/files/upload`、`/files/download` | WS 一帧上限 64 KB，200 MB 要拆三千多帧，分片、重组、流控都得自己写；HTTP 这三样都是现成的，还顺手给了进度、取消和准确的错误码 |

```jsonc
// 上行：要某一层
{ "t": "file", "act": "list", "cid": 2, "path": "/" }
// 上行：删（面板这一版没做按钮，留给联调脚本收尾）
{ "t": "file", "act": "delete", "cid": 2, "path": "/旧文件.zip" }

// 下行
{ "t": "files", "cid": 2, "path": "/", "error": null,
  "entries": [{ "name": "report.pdf", "folder": false, "size": 245760, "time": 1761900000000 }] }
```

`error` 非空时 `entries` 是空的：服务器拒绝（没权限、要频道密码）和「这个文件夹是空的」是两回事，得分开说。

两个 HTTP 端点：

```
POST /files/upload?cid=2&path=/report.pdf&overwrite=0    body 就是文件本身
GET  /files/download?cid=2&path=/report.pdf
     都得带 X-File-Token: <state 里的 fileToken>
```

- **令牌认会话，不认服务器。** 它随状态快照下发、每个标签页各不相同、断开就作废，网关靠它在内存的
  `WebSession.fileSessions` 里找回「这次请求属于哪个标签页」。
- **放在头里而不是 URL 里**，所以不会落进 Nginx 的 access log；也不用 cookie，cookie 会跟着人进这个站的
  其它标签页，活得比它所代表的会话还长。
- 上传必须带 `Content-Length`（要先告诉 TeamSpeak 尺寸才能开传），chunked 会被挡回 `411`。
- 下载会**先查一次目录**再开流：`respondOutputStream` 一开头 200 就已经发出去了，此后再发现文件不存在，
  浏览器只能收到半截下载。多一次往返，换一个说得清的 `404` / `400` / `413`。
- 单次传输上限 1 GiB（`WebSession.MAX_FILE_BYTES`）。真正的配额由服务器管（`virtualserver_upload_quota`），
  这里只是个防滥用的天花板。
- **上传权限是按身份和频道给的。** 游客身份在大多数服务器上只有下载权，此时上传会拿回 `2568`
  并显示「没有权限操作这个频道的文件」—— 这不是网关的问题，是服务器的文件权限位。
- 频道密码：进频道时用的是明文，文件区要的是它的哈希，所以网关在 `clientmove` 那一步把哈希记下来
  （`Crypto.hashPassword`，和安卓端一样），之后传文件自动带上。

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
- 全局最多 96 个并发连接，单 IP 最多 4 个。反代后面所有请求都来自同一个 IP 时，这条限制会按反代地址算，需要按实际情况调整。
- 每个连接是一个 UDP socket 加一个线程，扩到几百人需要改成分片或协程。
- 身份安全等级低于服务器要求时（错误 519）只提示、不自动升级——升级很吃 CPU，放到后台跑要单独设计。
- 网页关掉连接就断，没有 App 那种后台保活。
- 头像这一版没接，`tsproto` 里有现成实现（服务器横幅已经在用它下载了）。
- 文件传输的两个 HTTP 端点靠 `X-File-Token` 认会话，令牌只发给对应的那个标签页、断开即失效；但它和
  `ACCESS_TOKEN` 一样不是身份认证——拿到令牌的人可以在那次会话的有效期内传文件，就这么长的时间窗。
  上传有 1 GiB 的单次上限，真正的配额由服务器管。
- 串流的画面不经过网关，但共享是 mesh：N 个人看就有 N 份上行从**浏览器**出去（不是网关的带宽）。
  5 个人看 4 Mbps 的屏幕，发布者要 20 Mbps 上行，家宽基本到顶了。
