#!/usr/bin/env node
// 接入防护验证：Origin 白名单 + 访问口令。
// 用法：node security.mjs [token]
//   token 要和网关的 ACCESS_TOKEN 一致；不传则按"服务端没配口令"来测。

import crypto from 'node:crypto';
import http from 'node:http';

const HOST = process.env.GW_HOST ?? '127.0.0.1';
const PORT = Number(process.env.GW_PORT ?? 8080);
const TOKEN = process.argv[2] ?? null;

let passed = 0;
let failed = 0;
const check = (name, ok, detail = '') => {
  if (ok) { passed++; console.log(`  ✓ ${name}${detail ? ' — ' + detail : ''}`); }
  else { failed++; console.log(`  ✗ ${name}${detail ? ' — ' + detail : ''}`); }
};

// ---------- 原始 WebSocket 握手，用来伪造 Origin（浏览器 JS 改不了它，脚本可以） ----------
function rawUpgrade(origin) {
  return new Promise((resolve) => {
    const key = crypto.randomBytes(16).toString('base64');
    const req = http.request({
      host: HOST, port: PORT, path: '/ws',
      headers: {
        Connection: 'Upgrade',
        Upgrade: 'websocket',
        'Sec-WebSocket-Key': key,
        'Sec-WebSocket-Version': '13',
        ...(origin ? { Origin: origin } : {}),
      },
    });
    let settled = false;
    const done = (v) => { if (!settled) { settled = true; resolve(v); } };
    req.on('upgrade', (res, socket) => {
      let buf = Buffer.alloc(0);
      socket.on('data', (d) => {
        buf = Buffer.concat([buf, d]);
        const f = parseFrame(buf);
        if (f) { done({ upgraded: true, opcode: f.opcode, code: f.code }); socket.destroy(); }
      });
      socket.on('close', () => done({ upgraded: true, closed: true }));
      setTimeout(() => done({ upgraded: true, timeout: true }), 1500);
    });
    req.on('response', (res) => done({ upgraded: false, status: res.statusCode }));
    req.on('error', (e) => done({ error: e.message }));
    req.end();
  });
}

function parseFrame(buf) {
  if (buf.length < 2) return null;
  const opcode = buf[0] & 0x0f;
  const masked = (buf[1] & 0x80) !== 0;
  let len = buf[1] & 0x7f;
  let off = 2;
  if (len === 126) { if (buf.length < 4) return null; len = buf.readUInt16BE(2); off = 4; }
  else if (len === 127) { if (buf.length < 10) return null; len = Number(buf.readBigUInt64BE(2)); off = 10; }
  if (masked) off += 4;
  if (buf.length < off + len) return null;
  const payload = buf.subarray(off, off + len);
  return { opcode, code: opcode === 0x8 && payload.length >= 2 ? payload.readUInt16BE(0) : null };
}

// ---------- 普通客户端：验证口令 ----------
function wsConnect(hello) {
  return new Promise((resolve) => {
    const ws = new WebSocket(`ws://${HOST}:${PORT}/ws`);
    let settled = false;
    const done = (v) => { if (!settled) { settled = true; try { ws.close(); } catch {} resolve(v); } };
    ws.addEventListener('open', () => { if (hello) ws.send(JSON.stringify(hello)); });
    ws.addEventListener('message', (e) => {
      if (typeof e.data === 'string') { const m = JSON.parse(e.data); if (m.t === 'ready') done({ ready: true }); }
    });
    ws.addEventListener('close', (e) => done({ closed: true, code: e.code, reason: e.reason }));
    ws.addEventListener('error', () => done({ error: true }));
    setTimeout(() => done({ timeout: true }), 7000);
  });
}

(async () => {
  console.log(`网关 ${HOST}:${PORT}\n`);

  const auth = await fetch(`http://${HOST}:${PORT}/auth`).then((r) => r.json()).catch(() => null);
  console.log(`[1] /auth 自述：${JSON.stringify(auth)}`);
  check('/auth 端点可用', auth !== null);
  check('口令要求与服务端配置一致', auth?.required === (TOKEN !== null), `required=${auth?.required}`);

  console.log('\n[2] Origin 白名单');
  const evil = await rawUpgrade('https://evil.example.com');
  check('外部网站被拒绝', evil.upgraded === false || evil.code === 1008,
    evil.upgraded === false ? `HTTP ${evil.status}` : `close ${evil.code}`);

  const same = await rawUpgrade(`http://${HOST}:${PORT}`);
  check('同源页面被放行', same.upgraded === true && same.code !== 1008,
    same.code ? `close ${same.code}` : 'upgraded, 无拒绝帧');

  const none = await rawUpgrade(null);
  check('无 Origin（脚本/命令行）不被 Origin 规则拦', none.upgraded === true && none.code !== 1008,
    none.code ? `close ${none.code}` : 'upgraded');

  console.log('\n[3] 访问口令');
  if (TOKEN) {
    const noAuth = await wsConnect(null);
    check('不发口令被断开', noAuth.code === 1008 || noAuth.closed === true, `code=${noAuth.code}`);

    const badAuth = await wsConnect({ t: 'auth', token: 'wrong-token' });
    check('错口令被断开', badAuth.code === 1008 || badAuth.closed === true, `code=${badAuth.code}`);

    const goodAuth = await wsConnect({ t: 'auth', token: TOKEN });
    check('正确口令拿到 ready', goodAuth.ready === true, goodAuth.ready ? 'ready' : `code=${goodAuth.code}`);
  } else {
    const direct = await wsConnect(null);
    check('未配置口令时直接可用', direct.closed === false || direct.timeout === true, '连接保持');
  }

  console.log(`\n通过 ${passed} 项，失败 ${failed} 项`);
  process.exit(failed === 0 ? 0 : 1);
})();
