# 回响网页版 · 部署手册

> **给执行这个文档的人（或 agent）**：这份文档是自包含的，从头到尾按顺序做就行，不需要先读别的。
> 每一节末尾有「验收」。**验收不过不要往下走**，后面每一步都依赖前一步正确。
> 所有命令可以整段复制。文档里出现 `$DOMAIN`、`$ACCESS_TOKEN` 这类变量，在第 0 节设好，后面直接引用。

---

## 0. 开始之前

### 需要准备的东西

| 项 | 说明 | 谁来定 |
|---|---|---|
| Linux 服务器 | Ubuntu 22.04 / 24.04 或 Debian 12，x86_64，**内存 ≥ 2 GB** | 已有 |
| 域名 | 一个 A 记录指向这台服务器，例如 `ts.example.com` | 需要确认 |
| 访问口令 | 网关的共享口令，决定谁能用。没有它任何人都能拿你的服务器当 UDP 代理 | 需要确认 |
| TS 服务器地址 | 例如 `pi.animal26.site:9987`，只用于验收时测试连接 | 需要确认 |

### 架构一句话

```
浏览器 ──wss://域名──▶ Nginx（TLS 终止，443）──ws──▶ 网关（127.0.0.1:8080）──UDP 9987──▶ TeamSpeak 服务器
```

浏览器打不开 UDP，所以语音和信令都由网关代持。**网关只监听 127.0.0.1，不直接对外**，所有外部流量必须经过 Nginx 的 TLS。

### 先设好这几个变量

在服务器上打开 shell，先执行（把值换成实际的）：

```bash
export DOMAIN=ts.example.com
export TS_HOST=pi.animal26.site
export ACCESS_TOKEN=$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)
echo "口令是：$ACCESS_TOKEN   ← 记下来，等下要交给使用者"
```

后面所有命令都用这几个变量，不用再手改。

---

## 1. 检查服务器

```bash
cat /etc/os-release | head -2
nproc
free -h
df -h /
```

**验收**

- 系统是 Ubuntu 22.04/24.04 或 Debian 12。
- `free -h` 的 **Mem total ≥ 2 GB**。

> **内存不足 2 GB 怎么办**：构建 Gradle 会 OOM。走 [附录 D：低配服务器](#附录-d低配服务器本地构建后上传)，在一台内存够的机器上构建，只把产物传上来。

---

## 2. 安装 JDK 21

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk-headless unzip curl git
java -version
```

**验收**：`java -version` 输出里有 `openjdk version "21`。

> 如果提示找不到 `openjdk-21-jdk-headless`（老一点的 22.04 镜像可能没有），改用 Adoptium 源：
> ```bash
> sudo apt install -y wget gpg lsb-release
> wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public \
>   | sudo gpg --dearmor -o /usr/share/keyrings/adoptium.gpg
> echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb \
> $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/adoptium.list
> sudo apt update && sudo apt install -y temurin-21-jdk
> ```

**注意**：这台机器**不需要 Android SDK**。网关是独立构建，只编译 `tsproto` 的源码，和安卓客户端那套构建没有关系。

---

## 3. 安装 Node.js（构建前端用）

```bash
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt install -y nodejs
node -v && npm -v
```

**验收**：`node -v` 输出 `v22.x` 或更高（最低 20）。

---

## 4. 拿代码

```bash
sudo mkdir -p /opt/huixiang
sudo chown "$USER:$USER" /opt/huixiang
git clone https://github.com/youareanimal/huixiang.git /opt/huixiang
cd /opt/huixiang
git log --oneline -1
```

**验收**：`git log` 能打出一条提交记录，`ls` 能看到 `web/`、`tsproto/`、`app/` 目录。

> 如果仓库是私有的，换成对应的 SSH 地址或先配好访问凭据。

---

## 5. 构建后端

```bash
cd /opt/huixiang/web/gateway
../../gradlew installDist --no-daemon --console=plain
```

首次运行会下载 Gradle 9.8.2（约 130 MB）和依赖（约 50 MB），**5～10 分钟**，之后是秒级。

**验收**

```bash
ls /opt/huixiang/web/gateway/build/install/webgateway/bin/webgateway
```

文件存在，且末尾输出 `BUILD SUCCESSFUL`。

> 构建的是独立工程（`web/gateway/settings.gradle.kts`），不会碰 `app/`，也不需要 `local.properties`。

---

## 6. 构建前端

```bash
cd /opt/huixiang/web/client
npm ci
npm run build
```

**验收**

```bash
ls /opt/huixiang/web/client/dist/index.html
ls /opt/huixiang/web/client/dist/assets/ | head -3
```

`dist/` 里有 `index.html` 和 `assets/`。末尾输出 `✓ built in ...`。

---

## 7. 配置成系统服务

### 7.1 建运行用户和配置目录

```bash
sudo useradd --system --home /opt/huixiang --shell /usr/sbin/nologin huixiang 2>/dev/null || true
sudo mkdir -p /etc/huixiang
sudo tee /etc/huixiang/env >/dev/null <<EOF
PORT=8080
HOST=127.0.0.1
WEB_DIST=/opt/huixiang/web/client/dist
ACCESS_TOKEN=$ACCESS_TOKEN
ALLOWED_ORIGINS=https://$DOMAIN
LOG_LEVEL=INFO
EOF
sudo chmod 600 /etc/huixiang/env
sudo chown root:root /etc/huixiang/env
```

`HOST=127.0.0.1` 是故意的：8080 不对外，只能通过 Nginx 进来。

### 7.2 写 systemd 单元

```bash
sudo tee /etc/systemd/system/huixiang.service >/dev/null <<'EOF'
[Unit]
Description=Huixiang web gateway
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=huixiang
Group=huixiang
WorkingDirectory=/opt/huixiang
EnvironmentFile=/etc/huixiang/env
ExecStart=/opt/huixiang/web/gateway/build/install/webgateway/bin/webgateway
Restart=always
RestartSec=3
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadOnlyPaths=/opt/huixiang
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
EOF

sudo chown -R huixiang:huixiang /opt/huixiang
sudo systemctl daemon-reload
sudo systemctl enable --now huixiang
```

### 7.3 验收

```bash
systemctl is-active huixiang          # 期望 active
curl -s http://127.0.0.1:8080/healthz # 期望 ok
sudo ss -lntp | grep 8080             # 期望 127.0.0.1:8080，不是 0.0.0.0
```

三项都对再往下。如果 `active` 但 healthz 不通：

```bash
sudo journalctl -u huixiang -n 50 --no-pager
```

日志里会写清楚是端口占用、`WEB_DIST` 路径不对，还是别的。

---

## 8. 配置 Nginx 和 HTTPS

```bash
sudo apt install -y nginx certbot python3-certbot-nginx
```

先写站点配置：

```bash
sudo tee /etc/nginx/sites-available/huixiang >/dev/null <<EOF
server {
    listen 80;
    server_name $DOMAIN;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;

        # 这四行不能少：
        proxy_set_header Upgrade \$http_upgrade;      # WebSocket 升级
        proxy_set_header Connection "upgrade";        # 同上，缺了会一直 400
        proxy_set_header Host \$host;                 # 网关靠它判断同源
        proxy_set_header X-Forwarded-For \$remote_addr; # 网关靠它识别真实 IP 做限流
        proxy_set_header X-Forwarded-Proto \$scheme;

        # 语音是长连接，默认 60 秒会被掐断
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_buffering off;
    }
}
EOF

sudo ln -sf /etc/nginx/sites-available/huixiang /etc/nginx/sites-enabled/huixiang
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```

签证书（自动改配置加上 443）：

```bash
sudo certbot --nginx -d "$DOMAIN" --non-interactive --agree-tos -m "admin@$DOMAIN" --redirect
```

**验收**

```bash
curl -sI https://$DOMAIN/healthz | head -1     # 期望 HTTP/2 200 或 HTTP/1.1 200
```

> 如果域名还没解析到这台机器，certbot 会失败。先加 A 记录、等 DNS 生效（`dig +short $DOMAIN` 能看到本机 IP），再重跑 certbot。

---

## 9. 跑自检脚本

```bash
cd /opt/huixiang/web/deploy
bash verify.sh https://$DOMAIN "$ACCESS_TOKEN"
```

**期望输出**：7 项全勾，最后一行 `通过 7 项，失败 0 项`，退出码 0。

它检查的是：

1. `/healthz` 返回 `ok`
2. `/auth` 报告需要口令（说明 `ACCESS_TOKEN` 读到了）
3. 首页返回前端 HTML
4. `/assets/*.js` 的 Content-Type 是 javascript（不是 text/html）
5. 同源 WebSocket 握手拿到 101
6. **陌生来源被拒绝**（403 或 1008）——这条最关键，不过就说明白名单没生效
7. 本站来源握手正常

任何一项失败，对照 [附录 A](#附录-a故障排查) 处理，不要跳过。

---

## 10. 浏览器验收（人工做一遍）

用浏览器打开 `https://$DOMAIN`，依次确认：

- [ ] 出现「回响」连接页，不是空白页、不是 404
- [ ] 填服务器地址（`$TS_HOST`）和昵称
- [ ] 出现「访问口令」输入框，填入 `$ACCESS_TOKEN`
- [ ] 点「进入服务器」，能看到服务器名和频道树
- [ ] 点别的频道能切过去（左侧高亮跟着变）
- [ ] 输入框打字回车能发出去，消息出现在聊天区
- [ ] 点底部「麦克风」，浏览器弹权限请求，允许后按钮变成「麦克风已开」
- [ ] 页头没有「这个浏览器不支持 WebCodecs」的黄色提示

**最后一项**：让另一个人（或另一台设备）同时进服务器，验证能互相听到。

---

## 11. 日常运维

### 看日志

```bash
sudo journalctl -u huixiang -f              # 实时
sudo journalctl -u huixiang -n 200 --no-pager  # 最近 200 行
```

正常启动会打印：

```
Listening on 127.0.0.1:8080, serving /opt/huixiang/web/client/dist
A shared access token is required to open a socket
Origins accepted: https://ts.example.com
```

如果看到 `ACCESS_TOKEN is not set` 或 `ALLOWED_ORIGINS is not set` 的 **warn**，说明 `/etc/huixiang/env` 没被读到。

### 更新到新版本

```bash
cd /opt/huixiang
sudo systemctl stop huixiang
git pull
cd web/gateway && ../../gradlew installDist --no-daemon --console=plain
cd ../client && npm ci && npm run build
cd /opt/huixiang
sudo chown -R huixiang:huixiang /opt/huixiang
sudo systemctl start huixiang
cd web/deploy && bash verify.sh https://$DOMAIN "$ACCESS_TOKEN"
```

> **必须先停服务再构建**：Windows 上会锁 jar，Linux 上虽然能覆盖，但升级过程中如果有请求进来会读到半个文件。

### 回滚

```bash
cd /opt/huixiang
sudo systemctl stop huixiang
git log --oneline -5                      # 找到上一个好的提交
git checkout <提交号>
cd web/gateway && ../../gradlew installDist --no-daemon
cd ../client && npm ci && npm run build
sudo systemctl start huixiang
```

### 换口令

```bash
sudo sed -i "s|^ACCESS_TOKEN=.*|ACCESS_TOKEN=新口令|" /etc/huixiang/env
sudo systemctl restart huixiang
```

换完要通知所有使用者更新浏览器里的口令（它存在 localStorage 里，旧口令会一直被拒）。

---

## 附录 A：故障排查

| 现象 | 原因 | 怎么修 |
|---|---|---|
| 首页返回 JSON 或 404 | `WEB_DIST` 路径不对，或前端没构建 | `ls /opt/huixiang/web/client/dist/index.html`；确认 `/etc/huixiang/env` 里的路径是绝对路径 |
| 页面空白，控制台报 `Failed to load module script: ... MIME type of "text/html"` | 静态资源被当成 index.html 返回了 | 确认用的是当前版本代码（这个 bug 已修）；`/opt/huixiang/web/deploy/verify.sh` 的第 4 项会直接指出来 |
| 连不上，浏览器控制台 `WebSocket connection failed`，Nginx 日志 400 | Nginx 缺 `Upgrade` / `Connection` 头 | 对照第 8 节，四个 `proxy_set_header` 一个都不能少 |
| 页面能开，连服务器时提示「网关拒绝了这次连接」 | Origin 不在白名单，或口令错 | `ALLOWED_ORIGINS` 必须和浏览器地址栏的协议+域名**完全一致**（`https://` 不能写成 `http://`，也不能带路径） |
| 输入口令后一直进不去 | 口令里有特殊字符被 systemd 解析掉了 | `/etc/huixiang/env` 里给值加双引号：`ACCESS_TOKEN="含特殊字符的口令"` |
| 能进服务器但听不到声音 | 浏览器不支持 WebCodecs，或对方没说话 | 页头有黄色提示就是浏览器问题；换 Chrome/Edge 94+ |
| 自己说话别人听不到 | 没给麦克风权限，或麦克风被静音 | 地址栏左边的图标里看权限 |
| 连不上任何 TS 服务器 | 服务器出站 UDP 被防火墙拦 | `sudo ufw status`；确认允许出站；或临时 `sudo ufw allow out 9987/udp` |
| 所有人共用 1 个 IP 被限流（第 5 个人连不上） | Nginx 没传 `X-Forwarded-For` | 检查第 8 节的配置；确认反代和内网在同一台机器 |
| 构建时 `OutOfMemoryError` | 内存不足 2 GB | 走附录 D，或者先加 swap：`sudo fallocate -l 2G /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile` |
| `systemctl status` 显示 failed | 启动即崩 | `sudo journalctl -u huixiang -n 80 --no-pager`，最常见是端口被占（`ss -lntp \| grep 8080`） |

---

## 附录 B：环境变量全表

配置写在 `/etc/huixiang/env`，改完 `sudo systemctl restart huixiang`。

| 变量 | 默认 | 说明 |
|---|---|---|
| `PORT` | `8080` | 监听端口 |
| `HOST` | `127.0.0.1` | 监听地址。**不要改成 0.0.0.0**，那会绕过 Nginx 的 TLS 直接暴露 |
| `WEB_DIST` | `web/client/dist` | 前端产物目录。生产环境用绝对路径 |
| `ACCESS_TOKEN` | 空 | 共享访问口令。**不设等于把服务器当免费代理** |
| `ALLOWED_ORIGINS` | 空 | 允许的页面来源，逗号分隔。留空只接受同源 |
| `LOG_LEVEL` | `INFO` | 改成 `DEBUG` 会打印每条服务器命令，排障用，平时别开 |

---

## 附录 C：Docker 方式（可选）

> **这份 Dockerfile 没有在真实 Docker 环境里跑过**（开发环境没装 Docker）。如果下面的命令失败，请回到第 5～8 节的 systemd 路径，那条是实测过的。

`web/Dockerfile` 是三阶段构建：node 编前端 → JDK 编后端 → JRE 运行时。

```bash
cd /opt/huixiang
sudo docker build -f web/Dockerfile -t huixiang-web .
sudo docker run -d --name huixiang \
  --restart=always \
  -p 127.0.0.1:8080:8080 \
  -e HOST=0.0.0.0 \
  -e ACCESS_TOKEN="$ACCESS_TOKEN" \
  -e ALLOWED_ORIGINS="https://$DOMAIN" \
  huixiang-web
```

容器内必须 `HOST=0.0.0.0`（否则端口映射不进去），但 `-p` 绑在 `127.0.0.1` 上，外面照样只能经 Nginx。

Nginx 部分和第 8 节完全一样。

---

## 附录 D：低配服务器（本地构建后上传）

服务器内存 < 2 GB 时用这条路。在一台内存够的机器上（Linux/macOS/Windows 都行，需 JDK 21 + Node 20）：

```bash
# 本地，在仓库根目录
cd web/gateway && ../../gradlew installDist --no-daemon
cd ../client && npm ci && npm run build
cd /path/to/huixiang
tar czf huixiang-web.tar.gz \
  web/gateway/build/install/webgateway \
  web/client/dist
```

上传并铺开：

```bash
scp huixiang-web.tar.gz user@server:/tmp/
ssh user@server
sudo mkdir -p /opt/huixiang/web/gateway/build/install /opt/huixiang/web/client
sudo tar xzf /tmp/huixiang-web.tar.gz -C /opt/huixiang
```

服务器上**不需要装 JDK 和 Node**（只有 `installDist` 的启动脚本要 JAVA_HOME）：

```bash
sudo apt install -y openjdk-21-jre-headless
```

然后从第 7 节（配 systemd）继续。注意 `installDist` 拷过去的 `bin/webgateway` 里写死了 JAVA_HOME 探测逻辑，装 JRE 就能跑。

后续更新重复这套流程，不用在服务器上编译。

---

## 附录 E：这套东西的边界（交付时可以说明）

- **不是 TeamSpeak 官方产品**，是第三方客户端，与 TeamSpeak Systems GmbH 无关。
- 网关是一个 **UDP 代理**：谁能连上它，谁就能让这台服务器替他连任意 TS 服务器。`ACCESS_TOKEN` 是必须的。
- `ALLOWED_ORIGINS` 挡得住别的网站，**挡不住 `curl`**；口令挡得住没有口令的人，**挡不住拿到口令的人**。要做到"每人独立账号、能封禁、能审计"，那是账号体系，不是加一个 token 的事。
- 网页关掉连接就断，没有手机 App 那种后台保活。
- 语音依赖浏览器 WebCodecs：Chrome/Edge 94+、Safari 16.4+、Firefox 130+。低版本只能用文字和频道。
- 单个连接是一个 UDP socket 加一个线程，网关限制全局 96 个、单 IP 4 个。要撑更多人得改架构，不是调参数能解决的。
