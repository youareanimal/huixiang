# 回响 · HuiXiang

非官方 TeamSpeak 3 / 6 Android 客户端，基于 [PocketTS](https://github.com/KorppuJauho/pocketts) 开发。

**网页版已迁移至独立仓库： [回响 Web（huixiang-web）](https://github.com/MaXin-noob/huixiang-web)。** Web 的前端、网关、部署和构建请前往该仓库。本仓库仅维护 Android 客户端及其共用协议模块。

## Android 客户端

- 支持 TeamSpeak 服务器连接、频道树、成员列表、频道切换与断线重连
- 支持语音通话、回声消除、降噪、按键发言及自动语音检测
- 支持频道消息、私聊、文件传输与屏幕/摄像头串流
- 支持 myTeamSpeak 账号与书签、在线更新

Android 10 及以上可使用。安装包由 GitHub Actions 构建并使用原发行证书签名，可覆盖升级；下载请前往 [Releases](https://github.com/youareanimal/huixiang/releases)。

## 项目结构

```text
app/            Android 应用（Jetpack Compose、音频、WebRTC）
tsproto/        TeamSpeak 协议（纯 Kotlin/JVM）
scripts/        构建、发布、版本与部署脚本
docs/           设计、验证与研究文档
```

## 构建

```bash
ACCEPT_ANDROID_SDK_LICENSE=yes scripts/dev.sh image
scripts/dev.sh test
scripts/dev.sh apk
```

发行与开发说明见 [DEVELOPING.md](DEVELOPING.md)。

## 隐私与反馈

账号密码不以原文存储；登录会话、语音身份、已保存的服务器密码加密保存。详细说明见 [PRIVACY.md](PRIVACY.md)。

问题请到 [GitHub Issues](https://github.com/youareanimal/huixiang/issues) 反馈，并附版本、机型和复现步骤；请勿公开密码、验证码或身份导出文件。

## 来源与许可

基于 [PocketTS](https://github.com/KorppuJauho/pocketts)，遵循 [GPL-3.0](LICENSE)。其他致谢见 [CREDITS.md](CREDITS.md)。本项目与 TeamSpeak 官方无关联。
