#!/usr/bin/env node
// 端到端验证「频道成员进出提示」：
// headless Edge 打开页面 -> 以游客 hx-A 进入 pi.animal26.site -> 等基线建立
// -> 脚本客户端 hx-B 进入 A 所在频道(触发 join 提示) -> 1.6s 后换别频道(触发 leave 提示)
// -> 抓取 A 页面 .presence-toast 文本 + 截图 + 控制台报错
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const URL = 'http://127.0.0.1:5173/';
const PORT = 9333;
const HOST = 'pi.animal26.site';
const TSPORT = 9987;
const SLEEP = (ms) => new Promise((r) => setTimeout(r, ms));
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'edge-e2e-'));
const out = process.argv[2] ?? 'presence-e2e.png';

const proc = spawn(EDGE, [
  '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
  '--disable-extensions', `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`,
  '--window-size=1440,900',
  '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream',
  '--autoplay-policy=no-user-gesture-required', 'about:blank',
], { stdio: 'ignore' });

async function cdpReady() {
  for (let i = 0; i < 80; i++) {
    try { const r = await fetch(`http://127.0.0.1:${PORT}/json/version`); if (r.ok) return true; } catch {}
    await SLEEP(250);
  }
  return false;
}
function connect(wsUrl) {
  const ws = new WebSocket(wsUrl); let seq = 0; const pending = new Map(); const events = [];
  ws.addEventListener('message', (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); p.resolve(m.result ?? m.error); }
    else if (m.method) events.push(m);
  });
  const ready = new Promise((res, rej) => { ws.addEventListener('open', () => res()); ws.addEventListener('error', () => rej(new Error('CDP fail'))); });
  const send = (method, params = {}) => new Promise((resolve) => { const id = ++seq; pending.set(id, { resolve }); ws.send(JSON.stringify({ id, method, params })); });
  return { ws, ready, send, events };
}
function flatten(tree, acc = []) { for (const c of tree ?? []) { acc.push(c); flatten(c.children, acc); } return acc; }
function channelOfNick(tree, nick) { for (const c of flatten(tree)) { if ((c.clients ?? []).some((p) => p.nickname === nick)) return c.id; } return null; }

const cleanup = () => { try { proc.kill(); } catch {} try { fs.rmSync(profile, { recursive: true, force: true }); } catch {} };
let B = null;

async function startB() {
  return new Promise((resolve) => {
    const nick = 'hx-B-' + Math.floor(Math.random() * 9000 + 1000);
    const ws = new WebSocket('ws://127.0.0.1:8080/ws'); B = ws;
    let started = false, step = 0;
    const start = () => { if (started) return; started = true; ws.send(JSON.stringify({ t: 'connect', host: HOST, port: TSPORT, nickname: nick })); };
    ws.addEventListener('open', start);
    ws.addEventListener('message', (e) => {
      if (typeof e.data !== 'string') return;
      const m = JSON.parse(e.data);
      if (m.t === 'ready') { start(); return; }
      if (m.t !== 'state' || m.phase !== 'connected' || step > 0) return;
      step = 1;
      const aCid = channelOfNick(m.tree, 'hx-A');
      const bOwn = m.ownChannel;
      const all = flatten(m.tree).filter((c) => c.id !== aCid && !c.spacer);
      const other = (all.find((c) => (c.clients ?? []).length === 0) ?? all[0])?.id ?? null;
      if (bOwn !== aCid && aCid != null) ws.send(JSON.stringify({ t: 'join', cid: aCid }));
      if (other != null) setTimeout(() => { try { ws.send(JSON.stringify({ t: 'join', cid: other })); } catch {} }, 1600);
      resolve({ nick, aCid, bOwn, other });
    });
  });
}

(async () => {
  if (!(await cdpReady())) { console.error('Edge 调试端口没起来'); cleanup(); process.exit(1); }
  const target = await fetch(`http://127.0.0.1:${PORT}/json/new?${encodeURIComponent(URL)}`, { method: 'PUT' }).then((r) => r.json());
  const { ready, send, events } = connect(target.webSocketDebuggerUrl);
  await ready;
  await send('Page.enable'); await send('Runtime.enable'); await send('Log.enable');
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  const evalJs = async (expr) => { const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true }); return r?.result?.value; };
  await SLEEP(2000);

  const fillRes = await evalJs(`(()=>{const s=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;const ins=[...document.querySelectorAll('input')];if(ins.length<2)return 'no inputs';s.call(ins[0],'${HOST}');ins[0].dispatchEvent(new Event('input',{bubbles:true}));s.call(ins[1],'hx-A');ins[1].dispatchEvent(new Event('input',{bubbles:true}));const b=[...document.querySelectorAll('button')].find(x=>x.innerText.includes('以游客身份进入'));if(!b)return 'no btn';b.click();return 'clicked';})()`);
  console.log('填表:', fillRes);

  let joined = false;
  for (let i = 0; i < 50; i++) { await SLEEP(500); const ok = await evalJs(`!!document.querySelector('.server-body')||!!document.querySelector('.channel-rail')`); if (ok) { joined = true; break; } }
  console.log('A 进服:', joined);
  if (!joined) { console.log('页面文本:', await evalJs(`document.body.innerText.replace(/\\s+/g,' ').slice(0,200)`)); cleanup(); process.exit(1); }

  await SLEEP(2200); // 让 A 建立 presence 基线
  const info = await startB();
  console.log('B:', JSON.stringify(info));

  await SLEEP(2700); // join 提示 ~+0.2s, leave 提示 ~+1.8s, 抓取 ~+2.7s
  const res = await evalJs(`JSON.stringify({toasts:[...document.querySelectorAll('.presence-toast')].map(t=>t.innerText.trim()),server:document.querySelector('.server-name')?.innerText||''})`);
  console.log('捕捉结果:', res);

  const shot = await send('Page.captureScreenshot', { format: 'png', fromSurface: true });
  if (shot?.data) { fs.writeFileSync(out, Buffer.from(shot.data, 'base64')); console.log('截图:', out); }

  const errors = events.filter((e) => e.method === 'Runtime.exceptionThrown' || (e.method === 'Log.entryAdded' && e.params?.entry?.level === 'error') || (e.method === 'Runtime.consoleAPICalled' && e.params?.type === 'error'));
  console.log('控制台报错:', errors.length);
  for (const e of errors.slice(0, 5)) { const t = e.params?.exceptionDetails?.exception?.description ?? e.params?.entry?.text ?? ''; console.log(' ·', String(t).slice(0, 200)); }

  try { B?.close(); } catch {}
  cleanup(); process.exit(0);
})();
setTimeout(() => { console.error('整体超时'); try { B?.close(); } catch {} cleanup(); process.exit(1); }, 120000);
