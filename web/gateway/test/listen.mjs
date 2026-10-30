#!/usr/bin/env node
// 只收不发的客户端：进指定频道，统计能收到多少语音帧。用来验证浏览器那边真的把声音发出来了。
// 用法：node listen.mjs <host> [port] [频道id] [监听秒数]

const host = process.argv[2] ?? 'pi.animal26.site';
const port = Number(process.argv[3] ?? 9987);
const wantChannel = Number(process.argv[4] ?? 0);
const seconds = Number(process.argv[5] ?? 25);
const TOKEN = process.env.TOKEN ?? null;
const ws = new WebSocket(process.env.WS ?? 'ws://127.0.0.1:8080/ws');
ws.binaryType = 'arraybuffer';

let ownChannel = 0;
let frames = 0;
let bytes = 0;
const fromWho = new Map();

const connect = () => ws.send(JSON.stringify({ t: 'connect', host, port, nickname: `listen-${Math.floor(Math.random() * 900 + 100)}` }));

ws.addEventListener('open', () => {
  if (TOKEN) ws.send(JSON.stringify({ t: 'auth', token: TOKEN }));
  else connect();
});

ws.addEventListener('message', (e) => {
  if (typeof e.data !== 'string') {
    const b = new Uint8Array(e.data);
    if (b[0] === 2) {
      frames++;
      bytes += b.length - 4;
      const id = (b[2] << 8) | b[3];
      fromWho.set(id, (fromWho.get(id) ?? 0) + 1);
    }
    return;
  }
  const m = JSON.parse(e.data);
  if (m.t === 'ready') { connect(); return; }
  if (m.t !== 'state') return;
  ownChannel = m.ownChannel;
  if (m.phase === 'connected' && wantChannel && ownChannel !== wantChannel) {
    ws.send(JSON.stringify({ t: 'join', cid: wantChannel }));
  }
});

setTimeout(() => {
  const who = [...fromWho.entries()].map(([id, n]) => `client ${id}: ${n} 帧`).join(', ');
  console.log(`监听 ${seconds}s：收到 ${frames} 帧（${bytes} 字节）${who ? ' 来自 ' + who : ''}`);
  try { ws.close(); } catch {}
  process.exit(frames > 0 ? 0 : 1);
}, seconds * 1000);
