#!/usr/bin/env node
// 完整流程验证：连接 → 切频道 → 发文字 → 退出。
// 用法：node flow.mjs <host> [port] [nickname]
// 会真的在那台服务器上以该昵称登录一次并发言，结束后离开。

const host = process.argv[2] ?? 'pi.animal26.site';
const port = Number(process.argv[3] ?? 9987);
const nickname = process.argv[4] ?? `webtest-${Math.floor(Math.random() * 900 + 100)}`;
const ws = new WebSocket(process.env.WS ?? 'ws://127.0.0.1:8080/ws');
const TOKEN = process.env.TOKEN ?? null;
let started = false;
// 服务端要求口令时，先认证；拿到 ready 再发 connect
function start() {
  if (started) return;
  started = true;
  onReady();
}


let phase = 'idle';
let ownId = 0;
let ownChannel = 0;
let targetChannel = 0;
let didJoin = false;
let didSay = false;
let saidAt = 0;
const seen = new Set();

const log = (...a) => console.log(...a);

ws.addEventListener('open', () => {
  if (TOKEN) ws.send(JSON.stringify({ t: 'auth', token: TOKEN })); else start();
});

function onReady() {
  log(`连接 ${host}:${port} 昵称 ${nickname}`);
  ws.send(JSON.stringify({ t: 'connect', host, port, nickname, identity: process.env.IDENTITY ?? null }));
}

ws.addEventListener('message', (e) => {
  if (typeof e.data !== 'string') return;
  const m = JSON.parse(e.data);

  if (m.t === 'ready') { start(); return; }
  if (m.t === 'identity') {
    log(`身份已生成（${m.v.length} 字符），可存到 localStorage 复用`);
    return;
  }

  if (m.t === 'chat') {
    seen.add(`chat:${m.text}`);
    log(`[文字] ${m.name}: ${m.text}`);
    if (didSay && Date.now() - saidAt < 8000) {
      log('文字消息已验证（自己发的消息回到了自己这里）');
      finish(0);
    }
    return;
  }

  if (m.t !== 'state') return;
  phase = m.phase;
  ownId = m.ownId;
  ownChannel = m.ownChannel;

  if (phase === 'connected' && !didJoin) {
    const flat = [];
    const walk = (nodes, d) => { for (const c of nodes ?? []) { flat.push({ ...c, depth: d }); walk(c.children, d + 1); } };
    walk(m.tree, 0);
    log(`已连上「${m.serverName}」，我是 client ${ownId}，在频道 ${ownChannel}`);
    log(`频道: ${flat.map((c) => `${c.display || c.name}(#${c.id})`).join(' · ')}`);

    // 切到另一个真实频道（跳过 spacer 和自己已在的那个）
    const target = flat.find((c) => !c.spacer && c.id !== ownChannel);
    if (!target) { log('只有自己所在的频道，跳过切换测试'); return finish(1); }
    targetChannel = target.id;
    didJoin = true;
    log(`切到「${target.display || target.name}」(#${targetChannel})`);
    ws.send(JSON.stringify({ t: 'join', cid: targetChannel }));
    return;
  }

  if (didJoin && !didSay && ownChannel === targetChannel) {
    didSay = true;
    saidAt = Date.now();
    const text = `网关联调 ${new Date().toLocaleTimeString('zh-CN')}`;
    log(`切换成功，当前频道 ${ownChannel}；发送文字：${text}`);
    ws.send(JSON.stringify({ t: 'say', text, channel: ownChannel }));
    return;
  }

  if (m.error) log(`[错误] ${m.error}`);
});

ws.addEventListener('error', (e) => { console.error('WebSocket 错误', e.message ?? e); process.exit(1); });
ws.addEventListener('close', (e) => { if (e.code !== 1000) log(`连接关闭 ${e.code}`); });

function finish(code) {
  try { ws.send(JSON.stringify({ t: 'leave' })); } catch {}
  setTimeout(() => { try { ws.close(); } catch {} ; log(code === 0 ? '\n全部通过' : '\n有步骤未完成'); process.exit(code); }, 400);
}

setTimeout(() => { log(`\n超时：phase=${phase} own=${ownId} channel=${ownChannel} join=${didJoin} say=${didSay}`); finish(1); }, Number(process.env.TIMEOUT ?? 20000));
