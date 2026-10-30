#!/usr/bin/env node
// 说话提示冒烟：不连真服务器，也不碰真麦克风。
//
// 假 WebSocket 顶替网关，直接往页面里塞二进制语音帧——前端只认帧，不认服务器状态，
// 所以这条链路（收到帧 → 头像亮 → 断流/空帧 → 熄）能在 headless 里整条跑完。
//
// 用法：node talking-ui.mjs [url] [截图.png] [宽] [高]
//   node talking-ui.mjs http://127.0.0.1:4173/ talking.png
//   node talking-ui.mjs http://127.0.0.1:4173/ talking-390.png 390 844

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const url = process.argv[2] ?? 'http://127.0.0.1:4173/';
const out = process.argv[3] ?? 'talking.png';
const W = Number(process.argv[4] ?? 1440);
const H = Number(process.argv[5] ?? 900);

const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'edge-talk-'));
const proc = spawn(
  EDGE,
  [
    '--headless=new',
    '--disable-gpu',
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-extensions',
    '--remote-debugging-port=9224',
    `--user-data-dir=${profile}`,
    `--window-size=${W},${H}`,
    '--autoplay-policy=no-user-gesture-required',
    'about:blank',
  ],
  { stdio: 'ignore' },
);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function cdpReady() {
  for (let i = 0; i < 80; i++) {
    try {
      const r = await fetch('http://127.0.0.1:9224/json/version');
      if (r.ok) return true;
    } catch {
      // 还没起来
    }
    await sleep(250);
  }
  return false;
}

function connect(wsUrl) {
  const ws = new WebSocket(wsUrl);
  let seq = 0;
  const pending = new Map();
  ws.addEventListener('message', (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pending.has(m.id)) {
      const p = pending.get(m.id);
      pending.delete(m.id);
      p.resolve(m.result ?? m.error);
    }
  });
  const ready = new Promise((res, rej) => {
    ws.addEventListener('open', () => res());
    ws.addEventListener('error', () => rej(new Error('CDP 连接失败')));
  });
  const send = (method, params = {}) =>
    new Promise((resolve) => {
      const id = ++seq;
      pending.set(id, { resolve });
      ws.send(JSON.stringify({ id, method, params }));
    });
  return { ready, send };
}

/** 假网关：state 里两个人的 talking 全是 false——亮不亮只由语音帧决定。 */
const FAKE = `
(() => {
  window.__sent = [];
  window.__errors = [];
  window.addEventListener('error', (e) => window.__errors.push(String(e.message)));

  const state = {
    t: 'state', phase: 'connected', error: null, address: 'test:9987', nickname: '昕某人',
    serverName: '测试服', welcome: '', ownId: 1, ownChannel: 10,
    inputMuted: false, outputMuted: false, clientCount: 2,
    server: null,
    tree: [{ id: 10, parent: 0, name: '大厅', display: '大厅', spacer: false, password: false,
      topic: '', codec: 4, neededTalkPower: 0, maxClients: -1,
      clients: [
        { id: 1, nickname: '昕某人', uid: 'me', talking: false, away: false, inputMuted: false,
          outputMuted: false, talkPower: 0, isTalker: false, priority: false, streaming: false,
          channelGroup: '', serverGroups: [] },
        { id: 2, nickname: '小明', uid: 'u2', talking: false, away: false, inputMuted: false,
          outputMuted: false, talkPower: 0, isTalker: false, priority: false, streaming: false,
          channelGroup: '', serverGroups: [] }
      ], children: [] }]
  };

  class FakeSocket {
    constructor(url) {
      this.url = url;
      this.readyState = 0;
      window.__ws = this;
      setTimeout(() => { this.readyState = 1; this.onopen && this.onopen({}); }, 5);
    }
    send(data) {
      if (typeof data !== 'string') return;
      let m;
      try { m = JSON.parse(data); } catch { return; }
      window.__sent.push(m);
      if (m.t === 'connect') setTimeout(() => this.push(state), 5);
    }
    push(obj) { this.onmessage && this.onmessage({ data: JSON.stringify(obj) }); }
    /** 一帧下行语音：[2][codec][id 高][id 低][opus…]；bytes = 0 就是空帧（说完了）。 */
    voice(id, bytes = 40) {
      const buf = new Uint8Array(4 + bytes);
      buf[0] = 2; buf[1] = 4;
      buf[2] = (id >> 8) & 255; buf[3] = id & 255;
      for (let i = 0; i < bytes; i++) buf[4 + i] = 0x5a;
      this.onmessage && this.onmessage({ data: buf });
    }
    close() { this.readyState = 3; }
  }
  window.WebSocket = FakeSocket;

  if (navigator.mediaDevices) {
    navigator.mediaDevices.getUserMedia = async () => {
      const c = document.createElement('canvas');
      c.width = 320; c.height = 240;
      c.getContext('2d').fillRect(0, 0, 320, 240);
      return c.captureStream(5);
    };
  }
})();
`;

const cleanup = () => {
  try {
    proc.kill();
  } catch {
    // 已经退了
  }
  try {
    fs.rmSync(profile, { recursive: true, force: true });
  } catch {
    // 文件还被占着，系统自己会清
  }
};

const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail });
  console.log(`${ok ? '✓' : '✗'} ${name}${detail ? `：${detail}` : ''}`);
};

(async () => {
  if (!(await cdpReady())) {
    console.error('Edge 的调试端口没起来');
    cleanup();
    process.exit(1);
  }

  const target = await fetch(`http://127.0.0.1:9224/json/new?about:blank`, { method: 'PUT' }).then((r) => r.json());
  const { ready, send } = connect(target.webSocketDebuggerUrl);
  await ready;
  await send('Page.enable');
  await send('Runtime.enable');
  await send('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: 2, mobile: W < 600 });
  await send('Page.addScriptToEvaluateOnNewDocument', { source: FAKE });
  await send('Page.navigate', { url });
  await sleep(2400);

  const evaluate = async (expression, awaitPromise = false) => {
    const r = await send('Runtime.evaluate', { expression, awaitPromise, returnByValue: true });
    return r?.result?.value;
  };

  // 进服务器
  await evaluate(`(() => {
    const ins = [...document.querySelectorAll('input')];
    const type = (el, text) => {
      el.focus();
      document.execCommand('selectAll', false, null);
      document.execCommand('insertText', false, text);
    };
    if (ins[0]) type(ins[0], 'test:9987');
    if (ins[1]) type(ins[1], '昕某人');
  })()`);
  await sleep(300);
  await evaluate(`[...document.querySelectorAll('button')].find(b => b.textContent.includes('进入服务器'))?.click()`);
  await sleep(1400);

  const tiles = await evaluate(`document.querySelectorAll('.member-tile').length`);
  check('舞台上有两个成员', tiles === 2, `tile=${tiles}`);

  const quietAtStart = await evaluate(`document.querySelectorAll('.avatar-ring.is-talking').length`);
  check('一开始没人说话，头像不亮', quietAtStart === 0, `亮着 ${quietAtStart} 个`);

  // 1. 小明发 300ms 语音 → 头像亮
  await evaluate(`(() => {
    window.__burst = setInterval(() => window.__ws.voice(2), 20);
    setTimeout(() => clearInterval(window.__burst), 300);
  })()`);
  await sleep(200);
  const lit = await evaluate(`(() => {
    const on = [...document.querySelectorAll('.member-tile.is-talking')];
    return {
      count: on.length,
      names: on.map(x => x.querySelector('.tile-name')?.textContent),
      rings: document.querySelectorAll('.avatar-ring.is-talking').length,
      bars: document.querySelectorAll('.bars.is-live').length,
      sub: on.map(x => x.querySelector('.tile-sub')?.textContent || '').join('|'),
    };
  })()`);
  check('收到语音帧后头像亮起', lit.count === 1 && lit.names?.[0] === '小明', JSON.stringify(lit.names));
  check('亮的是说话那个人的环', lit.rings === 1, `ring=${lit.rings}`);
  check('同时出现跳动的电平条', lit.bars === 1, `bars=${lit.bars}`);

  // 2. 断流 → 超过 hold（600ms）后自己熄
  await sleep(500);
  const stillLit = await evaluate(`document.querySelectorAll('.avatar-ring.is-talking').length`);
  check('断流 500ms 内还亮着（不闪）', stillLit === 1, `亮着 ${stillLit} 个`);
  await sleep(500);
  const afterHold = await evaluate(`document.querySelectorAll('.avatar-ring.is-talking').length`);
  check('断流超过 600ms 自动熄灯', afterHold === 0, `亮着 ${afterHold} 个`);

  // 3. 空帧（明确的「说完了」）应当立刻熄，不等超时
  await evaluate(`(() => {
    for (let i = 0; i < 5; i++) window.__ws.voice(2);
    window.__ws.voice(2, 0);
  })()`);
  await sleep(120);
  const afterStop = await evaluate(`document.querySelectorAll('.avatar-ring.is-talking').length`);
  check('收到空帧立刻熄灯', afterStop === 0, `亮着 ${afterStop} 个`);

  // 4. 回到静默态不残留
  await sleep(400);
  const clean = await evaluate(`(() => ({
    talking: document.querySelectorAll('.is-talking').length,
    errors: window.__errors,
  }))()`);
  check('全程没有页面报错', Array.isArray(clean.errors) && clean.errors.length === 0, JSON.stringify(clean.errors));

  // 截图留档：亮着的时候拍一张
  await evaluate(`(() => {
    window.__burst = setInterval(() => window.__ws.voice(2), 20);
    setTimeout(() => clearInterval(window.__burst), 2500);
  })()`);
  await sleep(300);
  const shot = await send('Page.captureScreenshot', { format: 'png', fromSurface: true });
  if (shot?.data) fs.writeFileSync(out, Buffer.from(shot.data, 'base64'));

  const pass = results.filter((r) => r.ok).length;
  console.log(`\n${pass}/${results.length} 通过${pass === results.length ? '' : ' —— 有失败项'}`);
  cleanup();
  process.exit(pass === results.length ? 0 : 1);
})();
