#!/usr/bin/env bash
# 部署后自检：逐项打勾。只依赖 curl 和 python3（Ubuntu/Debian 默认自带）。
#
#   ./verify.sh                                  # 查本机 8080
#   ./verify.sh https://ts.example.com           # 查线上（走 Nginx，验证完整链路）
#   ./verify.sh https://ts.example.com 你的口令    # 连带检查口令配置
#
# 退出码 0 表示全部通过。
#
# 注意：WebSocket 握手用原始 socket 测，不用 curl —— curl 会自己改写 Connection 头，
# 拿到的永远是 200，看起来像"没升级"。

set -uo pipefail

BASE="${1:-http://127.0.0.1:8080}"
TOKEN="${2:-}"
PASS=0
FAIL=0

ok()   { PASS=$((PASS+1)); printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad()  { FAIL=$((FAIL+1)); printf '  \033[31m✗\033[0m %s\n' "$1"; }
note() { printf '    %s\n' "$1"; }

# 发一次真正的 WebSocket 握手，返回状态码。
# 来源不被允许时有两种表现：装了 CORS 白名单会直接 403；只靠 Origin 校验则是
# 先 101 再立刻收到 close 帧（1008）。这里把两种情况都归一成 "1008"。
ws_status() {
  python3 - "$BASE" "$1" <<'PY' 2>/dev/null || echo "000"
import socket, ssl, sys, urllib.parse
base, origin = sys.argv[1], sys.argv[2]
u = urllib.parse.urlparse(base)
host = u.hostname
port = u.port or (443 if u.scheme == 'https' else 80)
raw = socket.create_connection((host, port), 8)
sock = ssl.create_default_context().wrap_socket(raw, server_hostname=host) if u.scheme == 'https' else raw
lines = [
    "GET /ws HTTP/1.1",
    f"Host: {u.netloc}",
    "Upgrade: websocket",
    "Connection: Upgrade",
    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==",
    "Sec-WebSocket-Version: 13",
]
if origin:
    lines.append(f"Origin: {origin}")
sock.sendall(("\r\n".join(lines) + "\r\n\r\n").encode())

data = sock.recv(4096)
code = data.split(b"\r\n")[0].split(b" ")[1].decode() if data else "000"
if code == "101":
    # 升级成功后再看有没有立刻来一帧 close(1008)
    sock.settimeout(2.5)
    try:
        extra = sock.recv(4096)
    except Exception:
        extra = b""
    if b"\x88" in data + extra:
        # close 帧：0x88 后跟长度和两字节状态码
        blob = data + extra
        idx = blob.find(b"\x88")
        tail = blob[idx:idx + 6]
        if len(tail) >= 4 and tail[2:4] == b"\x03\xf0":
            code = "1008"
sock.close()
print(code)
PY
}

echo "检查目标：$BASE"
[ -n "$TOKEN" ] && echo "（带口令）"
echo

echo "[1] 存活与配置"
HEALTH=$(curl -s --max-time 8 "$BASE/healthz" 2>/dev/null || echo "")
[ "$HEALTH" = "ok" ] && ok "healthz 返回 ok" || bad "healthz 返回 '$HEALTH'（期望 ok）"

AUTH=$(curl -s --max-time 8 "$BASE/auth" 2>/dev/null || echo "")
case "$AUTH" in
  *'"required":true'*)  ok "/auth 说需要口令"
                        [ -z "$TOKEN" ] && note "服务端要求口令，但这次没传，第 [3] 项可能仍然通过（握手不看口令）" ;;
  *'"required":false'*) ok "/auth 说不需要口令"
                        [ -n "$TOKEN" ] && bad "但你传了口令——服务端可能没读到 ACCESS_TOKEN" ;;
  *) bad "/auth 返回 '$AUTH'（期望 JSON）" ;;
esac

echo
echo "[2] 静态页面"
HTML=$(curl -s --max-time 8 "$BASE/" 2>/dev/null)
if echo "$HTML" | grep -q 'id="root"'; then
  ok "首页返回了前端 HTML"
  ASSET=$(echo "$HTML" | grep -o '/assets/[^"]*\.js' | head -1)
  if [ -n "$ASSET" ]; then
    CT=$(curl -s -o /dev/null -w '%{content_type}' --max-time 8 "$BASE$ASSET")
    case "$CT" in
      *javascript*) ok "静态资源类型正确（$CT）" ;;
      *) bad "静态资源类型是 '$CT'，应为 javascript——多半被当成 index.html 返回了" ;;
    esac
  else
    bad "首页里找不到 /assets/*.js 引用，前端可能没打包好"
  fi
else
  bad "首页不是前端页面（还没构建前端，或 WEB_DIST 指错了）"
fi

echo
echo "[3] WebSocket 与来源校验"
S=$(ws_status "")
[ "$S" = "101" ] && ok "同源握手返回 101" || bad "同源握手返回 $S（期望 101）"

S=$(ws_status "https://evil.example.com")
if [ "$S" = "403" ] || [ "$S" = "1008" ]; then
  ok "陌生来源被拒绝（$S）"
else
  bad "陌生来源没有被拒绝（$S）——ALLOWED_ORIGINS 没生效"
fi

HOSTPART=$(echo "$BASE" | sed -E 's#^https?://##; s#/.*$##')
SCHEME=$(echo "$BASE" | grep -q '^https' && echo https || echo http)
S=$(ws_status "$SCHEME://$HOSTPART")
[ "$S" = "101" ] && ok "本站来源握手正常（101）" || bad "本站来源握手返回 $S"

echo
if [ -n "$TOKEN" ]; then
  echo "[4] 口令"
  note "口令在 WebSocket 第一帧校验，这里模拟不了。"
  note "请用浏览器打开 $BASE，输入口令后确认能进入服务器。"
  echo
fi

printf '通过 %d 项，失败 %d 项\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
