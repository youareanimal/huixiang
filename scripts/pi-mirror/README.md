# 回响 PI 直连镜像

镜像首页：<https://pi.animal26.site:8443/huixiang/>

版本清单：<https://pi.animal26.site:8443/huixiang/latest.json>

## 已部署网络配置

- 小米路由器：公网 **TCP 8443 → 192.168.31.24:8443**，规则名 `HuiXiang-HTTPS`。
- PI 的 UFW：放行 `8443/tcp`；OpenResty host 网络直接监听 8443。
- 公网 443 被运营商屏蔽，不依赖该端口；DNS 验证证书也不需要公网 80。
- 保留原有路由器转发与站点；原镜像内部 443 监听仍可使用。

## HTTPS 与文件

可信证书由 Let's Encrypt 签发，使用 PI 已有 Cloudflare DDNS 凭据完成 DNS 验证。凭据仍在原 DDNS 配置中，脚本不复制或输出它。证书私钥仅保存在 PI。

- 站点配置：`/opt/1panel/apps/openresty/openresty/conf/conf.d/huixiang-mirror.conf`
- 公开文件目录：`/opt/1panel/apps/openresty/openresty/www/sites/huixiang-mirror/public`
- 证书：`/etc/letsencrypt/live/huixiang-mirror/`
- 维护脚本：`/usr/local/lib/huixiang-mirror/`
- `certbot.timer` 自动续期，证书服务单独使用 PI 本地代理出网，续期后复制证书并校验、平滑重载 OpenResty。

`sync-release.py` 只同步已公开的 APK、源码 ZIP 和校验文件；先写版本化文件，再原子替换清单，不公开私人网盘目录。`scripts/publish-update.ps1` 在发布新版后自动同步本镜像。

## 验证与客户端状态

2026-10-29 已从外网直接验证可信 HTTPS、清单 HTTP 200 和 APK Range HTTP 206；1 MiB 抽样约 5.1 MB/s，这不是手机测速或全程下载速度保证。PI 本机首页与清单均为 HTTP 200。

0.5.0 App 内更新仍使用固定 Cloudflare 源。0.5.1 起默认使用 PI，关于页可以选择 Cloudflare 并查看当前来源，失败时尝试另一来源；下载切源先核对版本、架构、大小和哈希。旧版可先用浏览器从镜像下载新包并覆盖安装。
