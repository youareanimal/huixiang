#!/usr/bin/env node
// 打开一个看得见的浏览器窗口，自动填表、连接、进指定频道，然后退出脚本但不关浏览器。
// 用法：node open.mjs [频道名] [昵称] [服务器]
//   node open.mjs 挂机角落 webui pi.animal26.site

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const channelName = process.argv[2] ?? '挂机角落';
const nickname = process.argv[3] ?? 'webui';
const server = process.argv[4] ?? 'pi.animal26.site';
const url = process.env.URL ?? 'http://127.0.0.1:8080/';
const PORT = Number(process.env.CDP_PORT ?? 9333);

const profile = path.join(os.tmpdir(), 'huixiang-live-profile');
fs.mkdirSync(profile, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const args = [
  `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${profile}`,
  '--no-first-run',
  '--no-default-browser-check',
  '--disable-features=msEdgeFirstRunExperience',
  '--start-maximized',
  '--autoplay-policy=no-user-gesture-required',
  url,
];

// ATTACH=1 表示浏览器已经由外部启动（bash 结束会连带杀掉子进程，所以必须从外面起）
if (!process.env.ATTACH) {
  const proc = spawn(EDGE, args, { detached: true, stdio: 'ignore' });
  proc.unref();
}

async function ready() {
  for (let i = 0; i < 100; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${PORT}/json/version`);
      if (r.ok) return true;
    } catch {
      // 还在启动
    }
    await sleep(300);
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
      pending.get(m.id)(m.result ?? m.error);
      pending.delete(m.id);
    }
  });
  const opened = new Promise((res, rej) => {
    ws.addEventListener('open', () => res());
    ws.addEventListener('error', () => rej(new Error('CDP 连接失败')));
  });
  const send = (method, params = {}) =>
    new Promise((resolve) => {
      const id = ++seq;
      pending.set(id, resolve);
      ws.send(JSON.stringify({ id, method, params }));
    });
  return { opened, send };
}

/** 页面里的动作：填表 → 连接 → 等进频道 → 点目标频道。 */
const script = (channel, nick, host) => `(async () => {
  const setInput = (el, v) => {
    const proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, v);
    el.dispatchEvent(new Event('input', { bubbles: true }));
  };
  const waitFor = async (fn, ms) => {
    const end = Date.now() + ms;
    while (Date.now() < end) { const v = fn(); if (v) return v; await new Promise(r => setTimeout(r, 200)); }
    return null;
  };

  const inputs = await waitFor(() => {
    const list = [...document.querySelectorAll('input')];
    return list.length >= 2 ? list : null;
  }, 15000);
  if (!inputs) return '连接表单没出现';

  setInput(inputs[0], ${JSON.stringify(host)});
  setInput(inputs[1], ${JSON.stringify(nick)});
  await new Promise(r => setTimeout(r, 400));

  const btn = [...document.querySelectorAll('button')].find(b => b.textContent.includes('进入服务器'));
  if (!btn) return '找不到进入按钮';
  btn.click();

  const entered = await waitFor(() => document.querySelector('.channel'), 25000);
  if (!entered) {
    const err = document.querySelector('.semi-banner-description')?.textContent?.trim();
    return '没进服务器：' + (err || '未知原因');
  }
  await new Promise(r => setTimeout(r, 800));

  const target = [...document.querySelectorAll('.channel')].find(c => c.textContent.includes(${JSON.stringify(channel)}));
  if (!target) return '服务器上没有「' + ${JSON.stringify(channel)} + '」这个频道';
  if (target.classList.contains('channel-here')) return '本来就在 ' + ${JSON.stringify(channel)};
  target.click();

  const moved = await waitFor(() => {
    const here = document.querySelector('.channel-here .channel-name')?.textContent?.trim();
    return here && here.includes(${JSON.stringify(channel)}) ? here : null;
  }, 12000);
  return moved ? '已进入：' + moved : '点击了但没切过去，当前：' + (document.querySelector('.channel-here .channel-name')?.textContent || '未知');
})()`;

(async () => {
  if (!(await ready())) {
    console.error('浏览器调试端口没起来');
    process.exit(1);
  }

  const targets = await fetch(`http://127.0.0.1:${PORT}/json/list`).then((r) => r.json());
  const page = targets.find((t) => t.type === 'page');
  if (!page) {
    console.error('没有可用标签页', targets);
    process.exit(1);
  }

  const { opened, send } = connect(page.webSocketDebuggerUrl);
  await opened;
  await send('Page.enable');
  await send('Runtime.enable');
  // Edge 首次运行会自己弹同步引导页，先把它导航掉
  await send('Page.navigate', { url });
  await sleep(3500);

  const r = await send('Runtime.evaluate', {
    expression: script(channelName, nickname, server),
    awaitPromise: true,
    returnByValue: true,
  });
  console.log('结果：', r?.result?.value ?? JSON.stringify(r));

  const shot = await send('Page.captureScreenshot', { format: 'png' });
  if (shot?.data) {
    fs.writeFileSync(path.join(process.cwd(), 'live.png'), Buffer.from(shot.data, 'base64'));
    console.log('截图：live.png');
  }

  console.log('浏览器窗口已保留，脚本退出。');
  process.exit(0);
})();
