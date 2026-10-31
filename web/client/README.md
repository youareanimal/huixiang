# 回响 · 网页前端

React 18 + TypeScript + Vite + [Semi Design](https://semi.design)。构建产物输出到 `dist/`，网关启动时会自动托管，同源访问。

## 开发

```bash
cd web/client
npm install
npm run dev          # http://localhost:5173，/ws 反代到 127.0.0.1:8080
```

先起网关（见 `../README.md`），再起前端。反代后是同源请求，网关的 Origin 校验按主机名判断，`localhost` 开发照常通过。

```bash
npm run build        # tsc 类型检查 + 打包到 dist/
```

## 结构

```
src/
  App.tsx                  连接生命周期、账号与语音状态、语音开关
  protocol.ts              网关协议类型、本地存储、树查找
  gateway.ts               WebSocket 封装（含口令认证、账号/书签/好友消息）
  account.ts               账号错误码、头像裁切与 HTTP 上传/删除
  files.ts                 文件字节：HTTP 下载（列表命令仍走 gateway.ts）
  stream.ts                屏幕 / 摄像头串流（画面是 WebRTC，不经网关）
  talking.ts               谁在说话：按下行语音帧的实际音量判读（静音帧不算）
  audio/
    player.ts              下行：每人一个 AudioDecoder，Web Audio 排程播放
    mic.ts                 上行：AudioWorklet 攒 20ms → AudioEncoder 编 Opus
    capture-worklet.js     采集用的 worklet（纯 JS，不能写 TS）
  components/
    ConnectPage.tsx        地址、昵称、密码、口令、最近连接；myTeamSpeak 登录、云书签、好友
    ServerPage.tsx         整体布局 + 改名、频道密码弹窗
    ChannelTree.tsx        频道与成员
    ChatPanel.tsx          频道文字（带头像）
    Stage.tsx              当前频道成员（谁在说话、头像、单人音量）
    StreamPanel.tsx        串流画面与工具条（全屏、静音）
    FilesPanel.tsx         频道文件区（右侧抽屉，只读：浏览、下载）
    ServerInfo.tsx         右栏服务器信息 + 账号头像/好友
    ControlBar.tsx         麦克风、扬声器、直播、离开
    VolumePopover.tsx      音量滑块
    UserAvatar.tsx         圆形头像：多地址逐级回退，最后是首字母
```

## 语音是怎么走的

浏览器打不开 UDP，所以语音经过网关中转，但**不转码**：

```
麦克风 → AudioWorklet（每 960 采样 = 20ms）
       → AudioEncoder('opus', 48kHz, mono)
       → WebSocket 二进制 [1][codec][opus…]
       → 网关原样塞进 TS 的 UDP 包
```

下行反过来：`[2][codec][clientId:2][opus…]` → 每个说话人一个 `AudioDecoder` → `AudioBufferSourceNode` 按时间排程。

几个必须这么写的点：

- **每人一个解码器**。混在一起解会把时间戳搞乱，而且一个人断线不该影响别人。
- **排程要兜底**。`when` 落后当前时间超过 0.4 秒就重新对齐，否则一次卡顿会让延迟永久累积。
- **worklet 必须连到 destination** 才会被驱动，中间垫一个 `gain=0` 的节点，不然麦克风会从扬声器放出来。
- **OpusHead 那 19 字节不能省**，WebCodecs 的 opus 解码器没有它就报配置错误。

## 浏览器要求

| 能力 | 需要 |
|---|---|
| 语音 | WebCodecs（`AudioDecoder` / `AudioEncoder`）：Chrome/Edge 94+、Safari 16.4+、Firefox 130+ |
| 文字、频道 | 任何现代浏览器 |

不支持 WebCodecs 时页面顶部会提示，文字和频道功能照常可用。

## 本地存储

| 键 | 存什么 |
|---|---|
| `huixiang.identity` | TS 身份导出串。**不清它，服务器上的权限和组才会保留** |
| `huixiang.token` | 访问口令（仅当网关要求时） |
| `huixiang.nickname` | 上次用的昵称 |
| `huixiang.recent` | 最近连过的地址，最多 8 条 |
| `huixiang.micGain` / `huixiang.volumes` | 麦克风增益、按 uid 记的成员音量 |

myTeamSpeak 的邮箱、密码、会话令牌、书签密钥和云书签内容**都不进 localStorage**：登录会话只随这条 WebSocket 活在内存里，刷新或关页后要重新登录。

## 测试脚本

`test/shot.mjs` 用本机 Edge 的 headless 模式 + CDP 打开页面、执行一段脚本、截图、收集控制台报错：

```bash
node test/shot.mjs http://127.0.0.1:8080/ shot.png 3000 "document.title"
```

配合 `../gateway/test/listen.mjs` 可以验证浏览器真的把声音发出来了：

```bash
# 终端 1：监听端先进同一频道
node ../gateway/test/listen.mjs pi.animal26.site 9987 1 30

# 终端 2：用模拟麦克风打开页面、点开麦克风
FAKE_MIC=1 node test/shot.mjs http://127.0.0.1:8080/ shot.png 3000 "..."
```

`FAKE_MIC=1` 会带上 `--use-fake-device-for-media-stream` 和 `--use-fake-ui-for-media-stream`，不需要真设备也不用点授权。

## 账号与头像

连接页顶部是 myTeamSpeak 账号卡片，下面仍是原来的游客连接表单——不登录也能填地址进。

- 登录后显示账号昵称、邮箱和头像，并拉取云书签和好友列表；点云书签会把地址、昵称和服务器密码填进表单（密码不显示）。
- 头像可在连接页或右栏账号卡里设置/删除：`account.ts` 先把图片裁成 320×320，压到 512 KB 内的 PNG，再 `POST /account/avatar`。
- 频道里成员头像优先用服务器头像（`/avatar/<avatarToken>/<id>?v=<flag>`），其次对方公开的 myTS 头像，失败退回首字母；自己登录时优先用账号头像。
- 好友只做列表，不做云聊天；登录后连接会带 myTeamSpeak 语音身份，要完全匿名先退出账号。
- **刷新即退出**：账号会话只存在当前标签页的 WebSocket 里，浏览器不保存令牌或密码。

## 还没做

- 好友云聊天（Matrix）
- 文件上传与管理：传 / 删 / 改名 / 建文件夹（`FileTransfer` 都现成，缺的是一个带确认的交互）
- 私聊：**不做**（按需求砍掉了，网关侧仍支持，将来要加只需改前端）

已做：频道文字、切频道（含密码）、改名、静音同步、双向语音、**按住说话 / 常开麦切换**（按住说话支持空格键，输入框内不拦截）、单人音量、屏幕与摄像头串流、**频道文件的浏览 / 下载**、**myTeamSpeak 登录 / 云书签 / 好友列表 / 头像**。

## 文件是怎么走的

面板**只读**：浏览目录、下载。列表走 WebSocket，字节走 HTTP：

```
GET /files/download ──▶ 网关 ──ftinitdownload + TCP 30033──▶ TS 服务器
                              （文件本来就在服务器的频道文件区里）
```

- 下载用 `fetch` + `ReadableStream` 一块块读，进度是真的，中途断线立刻能发现（比 `res.blob()` 强）。
  攒够之后交给一个 `<a download>` 存盘。
- 端点要带 `X-File-Token`，值来自状态快照里的 `fileToken`——它代表这次会话，断开就作废。
- 开发模式下 `/files` 和 `/server-banner` 也要反代到网关（见 `vite.config.ts`），否则会打到 vite 自己。
