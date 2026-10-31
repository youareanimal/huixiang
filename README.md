# 回响 · HuiXiang

非官方 TeamSpeak 3 / 6 客户端。一套协议实现，两个端：Android 应用，和跑在浏览器里的网页版。

## 两个端

| | Android | 网页版 |
|---|---|---|
| 安装 | APK，Android 10+ | 打开网关地址即用，无需安装 |
| 语音 | 系统音频 + Opus，可后台 | WebCodecs + Web Audio，关页即断 |
| 账号 | myTeamSpeak 登录、书签、好友文字聊天 | myTeamSpeak 登录、云书签、好友列表、头像；也可游客进入 |
| 状态 | 0.5.4 预览版 | 实验性，需自建网关 |

安装包由 Actions 构建、原证书签名后发布，按架构选或直接用通用包，可直接覆盖升级。
两端都是填地址和昵称就能连，支持 `ts3server://` 邀请链接。

网页版必须有一个网关：浏览器发不了 UDP 包，而语音和信令全在 UDP 9987 上。
网关跑的是 App 里同一份 `tsproto`，音频是裸 Opus 帧直通，不转码。

## 功能

- 频道树、成员列表、切换频道（含频道密码）、断线重连
- 语音通话：回声消除、降噪，按住说话或自动检测人声
- 频道消息与私聊；文件传输（Android）
- 屏幕与摄像头共享：WebRTC 直连，画面不经服务器，网关只转发信令
- 谁在说话：头像亮起 + 电平条，按收到的语音帧判断，不依赖服务器状态
- 在线更新（Android）

网页版的实现细节、协议、部署方式见 [web/README.md](web/README.md)。

## 结构

```
app/            Android 应用：Compose 界面、音频、WebRTC
tsproto/        TeamSpeak 协议，纯 Kotlin/JVM，两端共用同一份
web/gateway/    网页版网关：Ktor + WebSocket，代持 UDP 连接
web/client/     网页前端：React + TypeScript + Vite
scripts/        构建、发布、版本与部署脚本
docs/           设计、验证与研究文档
```

## 构建

Android（只需要 Docker）：

```bash
ACCEPT_ANDROID_SDK_LICENSE=yes scripts/dev.sh image   # 首次，装 JDK 与 Android SDK
scripts/dev.sh test
scripts/dev.sh apk                                    # 产物在 dist/
```

发行包由 GitHub Actions 构建，用 Secrets 里的原证书签名，不自动发布 Release。
完整约定见 [DEVELOPING.md](DEVELOPING.md)。

网页版（JDK 21，不需要 Android SDK，命令都在仓库根执行）：

```bash
cd web/client && npm install && npm run build && cd ../..      # 前端产物到 web/client/dist

WEB_DIST="$PWD/web/client/dist" ./gradlew -p web/gateway run   # 网关，默认 8080
```

浏览器打开 `http://127.0.0.1:8080`。不设 `WEB_DIST` 时网关只提供 `/ws`，没有页面。
对外部署要加 TLS、来源白名单和访问口令，见 [web/deploy/DEPLOY.md](web/deploy/DEPLOY.md)。

## 隐私

账号密码不存原文；登录会话、语音身份、已保存的服务器密码加密存储。
myTeamSpeak 的私钥和登录令牌不会发给语音服务器。详见[隐私说明](PRIVACY.md)。

问题请提到 [GitHub Issues](https://github.com/youareanimal/huixiang/issues)，附版本号、机型与复现步骤；
不要贴密码、验证码或身份导出文件。

## 来源与许可

基于 [PocketTS](https://github.com/KorppuJauho/pocketts) 开发，[GPL-3.0](LICENSE) 许可，致谢见 [CREDITS.md](CREDITS.md)。
本项目与 TeamSpeak 官方无关联。
