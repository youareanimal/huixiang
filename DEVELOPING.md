# Developing PocketTS

## 回响发布约定

本项目仓库为 [youareanimal/huixiang](https://github.com/youareanimal/huixiang)。GitHub Actions 构建未签名 APK 后，使用本机 Agent 写入 GitHub Secrets 的原发行密钥进行 zipalign 对齐与 apksigner 签名，产物名为 `HuiXiang-<版本号>-signed`。密钥只在签名步骤写入 runner 临时目录并在退出时删除，不进入源码、缓存或产物。证书 SHA-256 固定校验为原发行证书，包名沿用 `app.shengqiao.ts.debug`。Secret 包括 HUIXIANG_KEYSTORE_BASE64、HUIXIANG_STORE_PASSWORD、HUIXIANG_KEY_PASSWORD、HUIXIANG_KEY_ALIAS；不得显示其值。此约定取代此前仅本机签名的约定。工作流不自动发布 Release 或同步更新源。

0.5.1 客户端内置 PI 与 Cloudflare 两个 HTTPS 源，每个源的清单与安装包必须保持同主机、同端口、同路径前缀。PI 默认优先，也允许在关于页选择 Cloudflare；失败时尝试备用源，下载切源前必须匹配版本、架构、长度及 SHA-256，继续验证包名和原签名。GitHub Release 的下载会跨域跳转，因此仍将 GitHub 构建并在本机签名的同一 APK 同步到两个源，保留旧版不可变文件。旧客户端继续使用 Cloudflare 清单，不直接替换其中的下载域名。PI 运维说明见 `scripts/pi-mirror/README.md`。

## The parts

用户要求后续不再进行本地编译或测试。发行统一使用 GitHub Actions 的构建与检查，发行电脑配置 Secrets，后续从云端下载已签名产物；不要再启动本机 Gradle、Robolectric 或本地截图验证。上游通用构建说明保留作参考，不代表本项目发行时使用本机打包。

- `tsproto/`: the TeamSpeak connection in plain Kotlin (no Android): packets, encryption, commands, the channel and
  people model, chat, file transfer and streams. Most of the tests are here.
- `app/`: the Android app, in Jetpack Compose: screens, voice (Opus), streams (WebRTC), settings and favourites.
- `scripts/dev.sh`: every build, test and helper command.
- `docs/TESTING.md`: what to test by hand on a phone.

## Building

Everything builds in Docker, so the only things needed are Docker and a Bash shell (on Windows: Docker inside
WSL). There's no need to install Java or the Android SDK.

```bash
ACCEPT_ANDROID_SDK_LICENSE=yes scripts/dev.sh image   # the build image: Java and the Android SDK (once)
scripts/dev.sh test                                   # tests
scripts/dev.sh apk                                    # a test build in dist/
scripts/dev.sh screenshots                            # pictures of the screens (example data) in docs/screenshots/
scripts/dev.sh gradle <task>                          # any other Gradle task
```

The build image accepts the Android SDK license, which is why that variable has to be set.

`scripts/dev.sh screenshots` also makes the Google Play pictures (`docs/screenshots/play/`) and GitHub's social
preview (`docs/screenshots/social-preview.png`, the picture shown when the repository is shared or found in search).
When the social preview changes, upload it again in the repository's Settings › General › Social preview.

`dev.env` holds this computer's own settings (addresses, container names) and is never committed.

## Testing on a phone

`scripts/dev.sh devserver up` starts a small page on port 8686. Open `http://<this computer's address>:8686` on the
phone, tap the newest build and install it. Android asks once to allow installing apps from the browser.
`scripts/dev.sh devserver down` stops it.

Test builds send their log to that page (`dist/phone.log`) when `DEV_LOG_URL` is set in `dev.env`. Release builds
never do.

## Tests against a real server

`scripts/dev.sh it` connects two clients to a TeamSpeak server and checks the following:

- they see each other and each other's channel moves
- one hears exactly what the other sends
- chat messages arrive
- both leave cleanly

Put the test server's address in `dev.env` as `TS_TEST_SERVER=host:port`. If the server runs in a container, add
`TS_CONTAINER=<name>` as well, so `scripts/dev.sh ts-server up` and `down` can start and stop it.

## Testing streams

`scripts/dev.sh viewer up` starts a test viewer on the test server (`TS_CONTAINER`): WebTS and a headless Chromium,
inside the server container's own network, so nothing is opened to the outside. It joins as "Stream viewer", goes
to the channel of whoever starts a stream and watches it, taking the video as H.264 like the TeamSpeak app
(`scripts/dev.sh viewer up webts` lets WebTS choose instead). `scripts/dev.sh viewer down` stops it.

The test page shows what it sees at `/viewer`: a log line a second (picture size, frame rate, data rate, losses,
freezes, sound, route) and a picture every few seconds (also in `dist/viewer/`). To measure the delay, open the test
page's `/clock` on the phone and share the screen: the viewer reads the clock from the stream and logs the delay from
the phone's screen to its own.

## Releasing

A release is a signed APK attached to a GitHub release, built by GitHub Actions from the tagged commit
(`.github/workflows/release.yml`). Test builds are a separate app (their ID ends in `.debug`), so both can be on one
phone.

### A release

1. In `app/build.gradle.kts`, raise `versionCode` by one and set `versionName` (for example `1.1.0`).
2. Move the changelog's "Unreleased" lines under a new heading `## <version> (<date>)`.
3. Merge that to `main`, then tag the merged commit and push the tag:
   ```bash
   git tag v1.1.0 origin/main
   git push origin v1.1.0
   ```
4. The workflow checks that the tag matches `versionName`, runs the tests, builds and signs the APK and the Google
   Play bundle, checks their signatures, records build attestations and makes a **draft** release with the APK, its
   SHA-256 and the changelog lines. The Play bundle stays with the run (Artifacts).
5. Download the APK from the draft, install it on a phone and try the basics: connect, talk, chat, a picture, a
   stream. If the release crashes where a test build doesn't, shrinking removed something: `scripts/dev.sh apk-shrunk`
   makes the same shrunk app with the test key, sending its log to the test page like a test build, so the error
   shows (a crash in native code leaves no line: then look for a class the native code needs, see
   `app/proguard-rules.pro`).
6. Publish the draft.
7. Google Play: the workflow uploads the bundle to the closed test as a draft (once set up); send it for review in
   Play Console. See [docs/play/README.md](docs/play/README.md).

Anyone can check that a release APK was built by this repository's workflow:

```bash
gh attestation verify PocketTS-1.1.0.apk --repo KorppuJauho/pocketts
```

The GitHub version checks `releases/latest` once a day, so a published release reaches users who have update checks
on. Drafts and pre-releases aren't offered.

### The release key

The workflow signs with the key in the `release` environment's secrets (Settings › Environments › release), which
only `v*` tags can use:

- `POCKETTS_KEYSTORE_BASE64`: the key file as base64
- `POCKETTS_KEYSTORE_PASSWORD`: its password (the key's password is the same)

The key was made once with `scripts/dev.sh keystore`, and the file is kept outside the repo.

**Back up the key file and its password somewhere safe.** Every update has to be signed with the same key: without
it, people can't update to a new version and would have to uninstall first, which loses their settings.

To put the key into the secrets (from Windows, with the key in WSL; it goes straight to GitHub, not through the
screen or the clipboard):

```bash
wsl -e bash -c "base64 -w0 ~/.pocketts/release.keystore" | gh secret set POCKETTS_KEYSTORE_BASE64 --env release --repo KorppuJauho/pocketts
gh secret set POCKETTS_KEYSTORE_PASSWORD --env release --repo KorppuJauho/pocketts
```

### Building a release on this machine

`scripts/dev.sh release` still builds the same signed APK locally (for example if GitHub Actions is unavailable).

### Making the release key (once)

```bash
scripts/dev.sh keystore
```

This asks for a password and makes the key at `~/.pocketts/release.keystore` (inside WSL on Windows; set
`POCKETTS_KEYSTORE` in `dev.env` to keep it somewhere else). It never goes into the repo.
