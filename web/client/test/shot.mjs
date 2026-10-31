#!/usr/bin/env node
// 用本机 Edge 的 headless 模式 + CDP 打开页面、截图、收集控制台报错。
// 用法：node shot.mjs <url> <输出png> [等待毫秒] [要执行的JS]
//   node shot.mjs http://127.0.0.1:8080/ first.png 3000

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const url = process.argv[2] ?? 'http://127.0.0.1:8080/';
const out = process.argv[3] ?? 'shot.png';
const waitMs = Number(process.argv[4] ?? 3000);
const script = process.argv[5] ?? '';

const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'edge-shot-'));
const proc = spawn(
  EDGE,
  [
    '--headless=new',
    '--disable-gpu',
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-extensions',
    '--remote-debugging-port=9222',
    `--user-data-dir=${profile}`,
    '--window-size=1440,900',
    // 测试时用假麦克风，不需要真设备也不需要点授权
    ...(process.env.FAKE_MIC
      ? ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', '--autoplay-policy=no-user-gesture-required']
      : []),
    'about:blank',
  ],
  { stdio: 'ignore' },
);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function cdpReady() {
  for (let i = 0; i < 80; i++) {
    try {
      const r = await fetch('http://127.0.0.1:9222/json/version');
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
  return { ws, ready, send, events };
}

const cleanup = () => {
  try {
    proc.kill();
  } catch {
    // 已经退了
  }
  try {
    fs.rmSync(profile, { recursive: true, force: true });
  } catch {
    // 文件还被占着，系统会自己清
  }
};

(async () => {
  if (!(await cdpReady())) {
    console.error('Edge 的调试端口没起来');
    cleanup();
    process.exit(1);
  }

  const target = await fetch(
    `http://127.0.0.1:9222/json/new?${encodeURIComponent(url)}`,
    { method: 'PUT' },
  ).then((r) => r.json());
  if (!target.webSocketDebuggerUrl) {
    console.error('没能新建标签页', target);
    cleanup();
    process.exit(1);
  }

  const { ready, send, events } = connect(target.webSocketDebuggerUrl);
  await ready;
  await send('Page.enable');
  await send('Runtime.enable');
  await send('Log.enable');
  await send('Emulation.setDeviceMetricsOverride', {
    width: 1440,
    height: 900,
    deviceScaleFactor: 2,
    mobile: false,
  });

  await sleep(waitMs);

  if (script) {
    const r = await send('Runtime.evaluate', {
      expression: script,
      awaitPromise: true,
      returnByValue: true,
    });
    console.log('脚本结果:', JSON.stringify(r?.result?.value ?? r).slice(0, 800));
    await sleep(1500);
  }

  const shot = await send('Page.captureScreenshot', { format: 'png', fromSurface: true });
  if (shot?.data) {
    fs.writeFileSync(out, Buffer.from(shot.data, 'base64'));
    console.log(`截图写到 ${out}`);
  } else {
    console.error('截图失败', shot);
  }

  const errors = events.filter(
    (e) =>
      (e.method === 'Runtime.exceptionThrown') ||
      (e.method === 'Log.entryAdded' && e.params?.entry?.level === 'error') ||
      (e.method === 'Runtime.consoleAPICalled' && e.params?.type === 'error'),
  );
  if (errors.length === 0) {
    console.log('控制台没有报错');
  } else {
    console.log(`控制台有 ${errors.length} 条报错：`);
    for (const e of errors.slice(0, 6)) {
      const t =
        e.params?.exceptionDetails?.exception?.description ??
        e.params?.entry?.text ??
        e.params?.args?.map((a) => a.value ?? a.description).join(' ');
      console.log('  ·', String(t).slice(0, 240));
    }
  }

  cleanup();
  process.exit(0);
})();

setTimeout(() => {
  console.error('整体超时');
  cleanup();
  process.exit(1);
}, 90_000);
