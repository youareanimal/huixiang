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
  App.tsx                  连接生命周期、状态、语音开关
  protocol.ts              网关协议类型、本地存储、树查找
  gateway.ts               WebSocket 封装（含口令认证）
  audio/
    player.ts              下行：每人一个 AudioDecoder，Web Audio 排程播放
    mic.ts                 上行：AudioWorklet 攒 20ms → AudioEncoder 编 Opus
    capture-worklet.js     采集用的 worklet（纯 JS，不能写 TS）
  components/
    ConnectPage.tsx        地址、昵称、密码、口令、最近连接
    ServerPage.tsx         整体布局 + 改名、频道密码弹窗
    ChannelTree.tsx        频道与成员
    ChatPanel.tsx          频道文字
    MemberList.tsx         右栏当前频道成员
    ControlBar.tsx         麦克风、扬声器、离开
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

## 还没做

- 文件传输、头像、屏幕串流（网关也还没接）
- 每个人的音量调节
- 私聊：**不做**（按需求砍掉了，网关侧仍支持，将来要加只需改前端）

已做：频道文字、切频道（含密码）、改名、静音同步、双向语音、**按住说话 / 常开麦切换**（按住说话支持空格键，输入框内不拦截）。
