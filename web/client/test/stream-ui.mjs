#!/usr/bin/env node
// 串流 UI 冒烟：不连真服务器，也不碰真屏幕。
//
// 假 WebSocket 顶替网关（记录前端发出去的每条消息，也能随时推一条流事件回来），
// canvas.captureStream() 顶替 getDisplayMedia —— 于是「开播 → 服务器认下 → 有人要看 → 生成 offer」
// 这条链路能在 headless 里整条跑完，且不依赖任何设备。
//
// 用法：node stream-ui.mjs [url] [截图.png]
//   node stream-ui.mjs http://127.0.0.1:4173/ stream.png

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const url = process.argv[2] ?? 'http://127.0.0.1:4173/';
const out = process.argv[3] ?? 'stream.png';

const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'edge-stream-'));
const proc = spawn(
  EDGE,
  [
    '--headless=new',
    '--disable-gpu',
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-extensions',
    '--remote-debugging-port=9223',
    `--user-data-dir=${profile}`,
    '--window-size=1440,900',
    '--autoplay-policy=no-user-gesture-required',
    'about:blank',
  ],
  { stdio: 'ignore' },
);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function cdpReady() {
  for (let i = 0; i < 80; i++) {
    try {
      const r = await fetch('http://127.0.0.1:9223/json/version');
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
  const events = [];
  ws.addEventListener('message', (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pending.has(m.id)) {
      const p = pending.get(m.id);
      pending.delete(m.id);
      p.resolve(m.result ?? m.error);
    } else if (m.method) {
      events.push(m);
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
  return { ready, send, events };
}

/** 注入到页面里的假网关。只用字符串传，省得为了闭包折腾 CDP 的序列化。 */
const FAKE = `
(() => {
  const sent = [];
  window.__sent = sent;
  window.__errors = [];
  window.addEventListener('error', (e) => window.__errors.push(String(e.message)));

  const state = {
    t: 'state', phase: 'connected', error: null, address: 'test:9987', nickname: '昕某人',
    serverName: '测试服', welcome: '', ownId: 1, ownChannel: 10,
    inputMuted: false, outputMuted: false, clientCount: 2,
    server: { maxClients: 32, clientsOnline: 2, channelsOnline: 1, uptime: 100,
      receivedAt: Date.now(), createdAt: 0, platform: 'Linux', version: '6.0',
      bannerUrl: null, bannerLink: null, hostbuttonUrl: null, hostbuttonTooltip: null },
    tree: [{ id: 10, parent: 0, name: '大厅', display: '大厅', spacer: false, password: false,
      topic: '', codec: 4, neededTalkPower: 0, maxClients: -1,
      clients: [
        { id: 1, nickname: '昕某人', uid: 'me', talking: false, away: false, inputMuted: false,
          outputMuted: false, talkPower: 0, isTalker: false, priority: false, streaming: false,
          channelGroup: '', serverGroups: [] },
        { id: 2, nickname: '小明', uid: 'u2', talking: false, away: false, inputMuted: false,
          outputMuted: false, talkPower: 0, isTalker: false, priority: false, streaming: true,
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
      sent.push(m);
      if (m.t === 'connect') setTimeout(() => this.push(state), 5);
    }
    push(obj) { this.onmessage && this.onmessage({ data: JSON.stringify(obj) }); }
    close() { this.readyState = 3; }
  }
  window.WebSocket = FakeSocket;

  // 假屏幕：一块画了字的画布，5 fps 就够验证管线
  if (navigator.mediaDevices) {
    navigator.mediaDevices.getDisplayMedia = async () => {
      const c = document.createElement('canvas');
      c.width = 640; c.height = 360;
      const g = c.getContext('2d');
      g.fillStyle = '#1b2130'; g.fillRect(0, 0, 640, 360);
      g.fillStyle = '#14b89a'; g.font = '20px sans-serif';
      g.fillText('screen share test', 40, 60);
      return c.captureStream(5);
    };
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

  const target = await fetch(`http://127.0.0.1:9223/json/new?about:blank`, { method: 'PUT' }).then((r) => r.json());
  const { ready, send, events } = connect(target.webSocketDebuggerUrl);
  await ready;
  await send('Page.enable');
  await send('Runtime.enable');
  await send('Log.enable');
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 2, mobile: false });
  await send('Page.addScriptToEvaluateOnNewDocument', { source: FAKE });
  await send('Page.navigate', { url });
  await sleep(2600);

  const evaluate = async (expression, awaitPromise = false, userGesture = false) => {
    const r = await send('Runtime.evaluate', { expression, awaitPromise, returnByValue: true, userGesture });
    return r?.result?.value;
  };

  // 0. 走一遍连接页：受控输入不能靠 setter 改，得让浏览器自己敲进去
  const filled = await evaluate(`(() => {
    const ins = [...document.querySelectorAll('input')];
    const type = (el, text) => {
      el.focus();
      document.execCommand('selectAll', false, null);
      document.execCommand('insertText', false, text);
    };
    if (ins[0]) type(ins[0], 'test:9987');
    if (ins[1]) type(ins[1], '昕某人');
    return ins.length;
  })()`);
  await sleep(400);
  await evaluate(
    `[...document.querySelectorAll('button')].find(b => b.textContent.includes('进入服务器'))?.click()`,
  );
  await sleep(1500);
  check('连接页能填能进', (await evaluate(`!!document.querySelector('.server-page')`)) === true, `input=${filled}`);

  // 1. 主界面起来了，串流入口在
  const hasShareBtn = await evaluate(
    `[...document.querySelectorAll('button')].some(b => b.textContent.trim() === '直播')`,
  );
  check('主界面出现「直播」按钮', hasShareBtn === true);

  // 2. 正在串流的成员有观看入口
  const watchBtn = await evaluate(
    `(() => { const b = document.querySelector('.watch-btn'); return b ? b.textContent.trim() : ''; })()`,
  );
  check('直播中的成员显示「观看」', watchBtn === '观看', watchBtn);

  // 3. 点观看 → 前端发出 watch 请求
  await evaluate(`document.querySelector('.watch-btn').click()`);
  await sleep(400);
  const watchMsg = await evaluate(
    `(() => { const m = window.__sent.find(x => x.t === 'stream' && x.act === 'watch'); return m || null; })()`,
  );
  check('点击观看后发出 watch', watchMsg?.streamer === 2, JSON.stringify(watchMsg));

  // 4. 服务端认下这次观看、推来一份（这里给的是空 offer，只为验证状态流转不崩）
  await evaluate(`window.__ws.push({ t: 'stream', event: 'refused', reason: '对方没在播' })`);
  await sleep(500);
  const errShown = await evaluate(
    `!!document.querySelector('.semi-toast') || [...document.querySelectorAll('div')].some(e => e.textContent === '对方没在播')`,
  );
  check('被拒时弹出提示', errShown === true);

  // 4.5 真来一份 offer：前端应当答上，并把上半部分换成对方的画面
  await evaluate(`document.querySelector('.watch-btn').click()`);
  await sleep(300);
  const offerLen = await evaluate(`(async () => {
    const pc = new RTCPeerConnection();
    const c = document.createElement('canvas'); c.width = 640; c.height = 360;
    const g = c.getContext('2d');
    g.fillStyle = '#20304a'; g.fillRect(0, 0, 640, 360);
    g.fillStyle = '#ffffff'; g.font = '20px sans-serif';
    g.fillText('对方的屏幕', 40, 60);
    const s = c.captureStream(5);
    for (const t of s.getTracks()) pc.addTrack(t, s);
    const o = await pc.createOffer();
    await pc.setLocalDescription(o);
    window.__remotePc = pc;
    window.__ws.push({ t: 'stream', event: 'offer', from: 2, streamId: 'abc', sdp: pc.localDescription.sdp });
    return pc.localDescription.sdp.length;
  })()`, true);
  await sleep(1500);
  const answerMsg = await evaluate(
    `(() => { const m = window.__sent.find(x => x.t === 'stream' && x.act === 'answer'); return m || null; })()`,
  );
  check('收到 offer 后答回去', !!answerMsg && answerMsg.to === 2, `${offerLen} 字节的 offer`);
  const remoteShown = await evaluate(
    `!!document.querySelector('.stream-stage video') && !document.querySelector('.stage')`,
  );
  check('上半部分换成对方的画面', remoteShown === true);
  const titleText = await evaluate(`document.querySelector('.stream-title')?.textContent || ''`);
  check('标题写着在看谁', titleText.includes('小明'), titleText);

  const fsBtn = await evaluate(
    `[...document.querySelectorAll('.stream-btn')].map(b => b.textContent.trim()).join('|')`,
  );
  check('工具条上有全屏按钮', String(fsBtn).includes('全屏'), String(fsBtn));

  // 全屏要用户手势，headless 里不一定能开；能开就顺手截一张，开不了不算失败
  const fsOn = await evaluate(
    `(async () => {
       const b = [...document.querySelectorAll('.stream-btn')].find(x => x.textContent.trim() === '全屏');
       if (!b) return 'no-button';
       b.click();
       await new Promise(r => setTimeout(r, 700));
       return !!document.fullscreenElement;
     })()`,
    true,
    true,
  );
  console.log(`  全屏（headless 里仅供参考）：${fsOn}`);
  if (fsOn === true) {
    const shotFs = await send('Page.captureScreenshot', { format: 'png', fromSurface: true });
    if (shotFs?.data) fs.writeFileSync('stream-fullscreen.png', Buffer.from(shotFs.data, 'base64'));
    await evaluate(`document.exitFullscreen && document.exitFullscreen().catch(() => {})`, true, true);
    await sleep(300);
  }

  const shotWatch = await send('Page.captureScreenshot', { format: 'png', fromSurface: true });
  if (shotWatch?.data) fs.writeFileSync('stream-watch.png', Buffer.from(shotWatch.data, 'base64'));

  // 看完收掉，再测自己开播（工具条上不止一个按钮，按文字找那个「关掉」）
  await evaluate(
    `[...document.querySelectorAll('.stream-btn')].find(b => b.textContent.trim() === '关掉')?.click()`,
  );
  await sleep(400);

  // 5. 开播：点串流 → 共享屏幕
  await evaluate(`[...document.querySelectorAll('button')].find(b => b.textContent.trim() === '直播').click()`);
  await sleep(300);
  const menu = await evaluate(
    `(() => { const items = [...document.querySelectorAll('.semi-dropdown-item, li')].map(e => e.textContent.trim()); return items; })()`,
  );
  check('直播菜单出现两项', Array.isArray(menu) && menu.includes('直播屏幕'), JSON.stringify(menu));

  await evaluate(
    `[...document.querySelectorAll('.semi-dropdown-item, li')].find(e => e.textContent.trim() === '直播屏幕')?.click()`,
  );
  await sleep(900);

  const shareMsg = await evaluate(
    `(() => { const m = window.__sent.find(x => x.t === 'stream' && x.act === 'share'); return m || null; })()`,
  );
  check('点了直播屏幕后发出 share', shareMsg?.kind === 2, JSON.stringify(shareMsg));

  const panel = await evaluate(`!!document.querySelector('.stream-stage video')`);
  check('本地预览占住上半部分', panel === true);
  const stageGone = await evaluate(`!document.querySelector('.stage')`);
  check('成员舞台让位给画面', stageGone === true);

  // 6. 服务器认下 → 面板变成「还没有人看」
  await evaluate(`window.__ws.push({ t: 'stream', event: 'started', streamId: 'abc', audio: false })`);
  await sleep(300);
  const liveText = await evaluate(`document.querySelector('.stream-sub')?.textContent || ''`);
  check('开播后显示观众数', liveText.includes('还没有人看'), liveText);

  // 7. 有人要看 → 前端建连接、生成 offer
  await evaluate(`window.__ws.push({ t: 'stream', event: 'viewer', viewer: 42, count: 1 })`);
  await sleep(1200);
  const offerMsg = await evaluate(
    `(() => { const m = window.__sent.find(x => x.t === 'stream' && x.act === 'offer'); return m || null; })()`,
  );
  check('观众加入后生成 offer', !!offerMsg && offerMsg.to === 42 && offerMsg.sdp.includes('m=video'),
    offerMsg ? `${offerMsg.sdp.length} 字节` : '没有 offer');

  // 8. offer 里应当有 H.264（TeamSpeak 客户端最认它），也要有一条音频 m-line
  //    （TeamSpeak 的客户端不会回答只有视频的 offer）
  check('offer 里带 H.264', /H264\/90000/i.test(offerMsg?.sdp ?? ''));
  check('offer 里带音频 m-line', /m=audio/i.test(offerMsg?.sdp ?? ''));
  // Chrome 默认会塞七八个 H.264 变体，TeamSpeak 的客户端见到就不回话了，所以只该留一个
  const h264Count = (offerMsg?.sdp.match(/a=rtpmap:\d+ H264\/90000/gi) ?? []).length;
  check('offer 里只有一种 H.264', h264Count === 1, `${h264Count} 个`);
  check('offer 里留了 VP8 当退路', /VP8\/90000/i.test(offerMsg?.sdp ?? ''));

  const viewerCount = await evaluate(`document.querySelector('.stream-sub')?.textContent || ''`);
  check('面板显示 1 人在看', viewerCount.includes('1 人'), viewerCount);

  const shot = await send('Page.captureScreenshot', { format: 'png', fromSurface: true });
  if (shot?.data) {
    fs.writeFileSync(out, Buffer.from(shot.data, 'base64'));
    console.log(`截图写到 ${out}`);
  }

  const jsErrors = await evaluate(`window.__errors`);
  check('页面没有运行时异常', !jsErrors || jsErrors.length === 0, JSON.stringify(jsErrors ?? []));

  const consoleErrors = events.filter(
    (e) =>
      e.method === 'Runtime.exceptionThrown' ||
      (e.method === 'Log.entryAdded' && e.params?.entry?.level === 'error'),
  );
  if (consoleErrors.length) {
    console.log(`控制台报错 ${consoleErrors.length} 条：`);
    for (const e of consoleErrors.slice(0, 5)) {
      const t =
        e.params?.exceptionDetails?.exception?.description ??
        e.params?.entry?.text ??
        '';
      console.log('  ·', String(t).slice(0, 200));
    }
  }

  const failed = results.filter((r) => !r.ok);
  console.log(`\n--- ${results.length - failed.length}/${results.length} 项通过 ---`);
  cleanup();
  process.exit(failed.length ? 1 : 0);
})();

setTimeout(() => {
  console.error('整体超时');
  cleanup();
  process.exit(1);
}, 120_000);
