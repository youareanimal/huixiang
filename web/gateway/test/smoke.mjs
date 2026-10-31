#!/usr/bin/env node
// 网关联调脚本：模拟浏览器连上 /ws，发 connect，把状态快照打印出来。
// 用法：node smoke.mjs [host] [port] [nickname]
//   node smoke.mjs                      只验证 WebSocket 管线与本地防护
//   node smoke.mjs pi.animal26.site      连真实服务器（会把昵称挂上去）

const url = process.env.WS ?? 'ws://127.0.0.1:8080/ws';
const host = process.argv[2] ?? '';
const port = Number(process.argv[3] ?? 9987);
const nickname = process.argv[4] ?? 'webtest';

const ws = new WebSocket(url);
const TOKEN = process.env.TOKEN ?? null;
let started = false;
// 服务端要求口令时，先认证；拿到 ready 再发 connect
function start() {
  if (started) return;
  started = true;
  onReady();
}

let states = 0;
let chat = 0;
let voiceFrames = 0;
let voiceBytes = 0;

const done = (code) => {
  console.log(`\n--- 汇总 ---`);
  console.log(`state 快照: ${states}`);
  console.log(`文字消息: ${chat}`);
  console.log(`语音帧: ${voiceFrames}（${voiceBytes} 字节 Opus）`);
  try { ws.close(); } catch {}
  setTimeout(() => process.exit(code), 100);
};

ws.addEventListener('open', () => {
  if (TOKEN) ws.send(JSON.stringify({ t: 'auth', token: TOKEN })); else start();
});

function onReady() {
  console.log(`已连上 ${url}`);
  if (!host) {
    // 没有目标服务器时，先验证地址防护：内网地址必须被拒绝
    console.log('未指定目标服务器，测试地址防护：');
    ws.send(JSON.stringify({ t: 'connect', host: '127.0.0.1', port: 9987, nickname }));
    ws.send(JSON.stringify({ t: 'connect', host: '../etc/passwd', port: 9987, nickname }));
    ws.send(JSON.stringify({ t: 'connect', host: 'localhost', port: 9987, nickname }));
    setTimeout(() => done(0), 1500);
    return;
  }
  console.log(`连接 ${host}:${port} 昵称 ${nickname}`);
  ws.send(JSON.stringify({ t: 'connect', host, port, nickname }));
}

ws.addEventListener('message', (e) => {
  if (typeof e.data !== 'string') {
    voiceFrames++;
    voiceBytes += e.data.size ?? 0;
    return;
  }
  const m = JSON.parse(e.data);
  if (m.t === 'ready') { start(); return; }
  if (m.t === 'state') {
    states++;
    if (states === 1 || m.phase !== 'connecting') {
      console.log(`[state] phase=${m.phase} server=${JSON.stringify(m.serverName)} own=${m.ownId} ` +
        `clients=${m.clientCount} channels=${countChannels(m.tree)} err=${JSON.stringify(m.error)}`);
    }
    if (m.phase === 'connected' && states > 0) {
      printTree(m.tree, 0);
    }
  } else if (m.t === 'identity') {
    console.log(`[identity] 服务端生成了身份，长度 ${m.v.length}`);
  } else if (m.t === 'chat') {
    chat++;
    console.log(`[chat] ${m.name}: ${m.text}`);
  }
});

ws.addEventListener('close', (e) => {
  console.log(`连接关闭 ${e.code} ${e.reason ?? ''}`);
  process.exit(0);
});

ws.addEventListener('error', (e) => {
  console.error('WebSocket 错误', e.message ?? e);
  process.exit(1);
});

setTimeout(() => done(0), Number(process.env.TIMEOUT ?? 12000));

function countChannels(tree) {
  let n = 0;
  for (const c of tree ?? []) n += 1 + countChannels(c.children);
  return n;
}

function printTree(tree, depth) {
  for (const c of tree ?? []) {
    const people = (c.clients ?? []).map((p) => `${p.nickname}${p.talking ? '(说)' : ''}${p.outputMuted ? '(静)' : ''}`);
    console.log(`${'  '.repeat(depth)}- ${c.display || c.name}${people.length ? ' :: ' + people.join(', ') : ''}`);
    printTree(c.children, depth + 1);
  }
}
