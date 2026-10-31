# 从 GitHub Actions 发布回响到 更新服务器

工作流：`.github/workflows/deploy-pi.yml`，仅 `workflow_dispatch`，仅允许 `main`。
不重新构建 App、不执行产物中的代码、不创建 GitHub Release。

## 入口与 Secrets

仓库：`youareanimal/huixiang`。

| Actions Secret | 用途 / 非敏感固定配置 |
| --- | --- |
| `PI_SSH_HOST` | `updates.example.invalid` |
| `PI_SSH_PORT` | `22` |
| `PI_SSH_USER` | `huixiang-deploy` |
| `PI_SSH_PRIVATE_KEY` | 此仓库专用的受限部署私钥，通过本机 stdin 加密写入 Secrets |
| `PI_SSH_KNOWN_HOSTS` | 更新服务器 本机 `/etc/ssh/ssh_host_ed25519_key.pub` 生成的可信主机记录 |

私钥、密码、令牌不得放入仓库、聊天、日志或命令行参数。不要以未验证的 `ssh-keyscan` 结果替换可信主机记录。

## 日常使用

1. 由现有 `Build HuiXiang` 工作流在 `main` 完成构建、测试和原证书签名。
2. 打开 Actions → Deploy HuiXiang to 更新服务器 → Run workflow，选择 `main`。
3. `build_run_id` 可指定成功构建的 run ID，留空则选最新受信成功构建。
4. `dry_run=true`（默认）完整校验产物后只运行 SSH 状态查询，不修改镜像。
5. 确认目标版本后，另一次手动运行将 `dry_run=false`，才会上传并切换清单。

云端 Codex 可调用已有 GitHub 权限运行以下命令；不应在云端重新构建来代替已有 CI 产物：

```bash
gh workflow run deploy-pi.yml -R youareanimal/huixiang --ref main -f build_run_id=BUILD_RUN_ID -f dry_run=true
# 明确需要发布后再运行：
gh workflow run deploy-pi.yml -R youareanimal/huixiang --ref main -f build_run_id=BUILD_RUN_ID -f dry_run=false
```

## 校验与权限边界

- 构建必须来自本仓库 `.github/workflows/huixiang-build.yml`，事件为 `push` 或 `workflow_dispatch`、分支为 `main`、结论成功；提交必须仍在当前 `main` 历史上。
- 成对使用同一构建中的 `HuiXiang-<版本>-signed` 与 `HuiXiang-<版本>-online-update`。
- 硬校验 GitHub API 记录的 SHA-256 产物 ZIP 摘要，任何不匹配均失败。
- 核对签名构建记录中的仓库、run ID、提交、原签名证书摘要、三种 ABI；两套产物的 APK/源代码 ZIP 必须一致，APK 大小和清单/校验表哈希必须匹配。
- 产物只按数据读取和白名单文件复制；不运行其脚本，不解开源代码 ZIP 或 APK，不执行 PR 构建内容。
- SSH 使用独立账号，无口令登录、交互终端、转发或用户启动脚本；只允许 `huixiang-check` 和有大小/摘要约束的 `huixiang-publish`。
- 远端接收包拒绝链接、目录、路径穿越、重复文件和超限数据；发布脚本及目录由 root 控制。
- 连接时核对 更新服务器 安装脚本与当前受信部署代码的 SHA-256，避免服务端规则漂移。
- 镜像进程只可写回响发布目录和私有状态；无 sudo、Docker 或其他服务权限。

## 更新服务器 文件位置

- 固定入口：`/usr/local/lib/huixiang-mirror/deploy-gate.py`
- 发布脚本：`/usr/local/lib/huixiang-mirror/sync-release.py`
- 账号及临时上传：`/var/lib/huixiang-deploy/`
- 私有发布历史及锁：`/var/lib/huixiang-deploy/state/`
- 受限 SSH 配置：`/etc/ssh/sshd_config.d/60-huixiang-deploy.conf`
- 公共镜像：`/opt/1panel/apps/openresty/openresty/www/sites/huixiang-mirror/public/`
- 下载入口：`https://updates.example.invalid:8443/huixiang/`

发布时先校验整个批次和已有文件，再写不可变版本文件；使用原子文件链接避免覆盖竞态、文件锁串行发布，最后 `fsync` 并原子替换 `latest.json`。旧安装包和历史清单均保留。

拒绝低于当前或历史高水位的版本码，拒绝同版本码/版本名对应不同清单，拒绝已有文件名对应不同哈希。现有 `0.5.1-preview` 不允许以不同哈希覆盖。

`test-publisher.py` 只在 GitHub 上运行，覆盖回退、同版本变更、已有资产冲突、源代码哈希不匹配和发布中断后的高水位规则。本机不运行 App 编译或测试。

部署代码维护提交使用 `[skip ci]`，避免因纯部署改动触发旧的全量 App 构建；之后手动触发本发布工作流完成 GitHub 保护检查和 dry-run。CI 提交不得因此省略 App 功能变更本来需要的构建测试。
