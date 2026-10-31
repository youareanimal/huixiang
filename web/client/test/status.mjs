#!/usr/bin/env node
// 读当前页面的实时状态（不用截图也能确认进没进频道）。
// 用法：CDP_PORT=9335 node status.mjs

const PORT = Number(process.env.CDP_PORT ?? 9335);
const base = `http://127.0.0.1:${PORT}`;

const list = await fetch(`${base}/json/list`).then((r) => r.json());
const page = list.find((t) => t.type === 'page' && t.url.includes('8080'));
if (!page) {
  console.log('没有应用页面在跑，现有：', list.filter((t) => t.type === 'page').map((t) => t.url));
  process.exit(1);
}

const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((r) => ws.addEventListener('open', r));
const send = (method, params = {}) =>
  new Promise((res) => {
    const id = Math.floor(Math.random() * 1e6);
    const onMsg = (e) => {
      const d = JSON.parse(e.data);
      if (d.id === id) {
        ws.removeEventListener('message', onMsg);
        res(d.result);
      }
    };
    ws.addEventListener('message', onMsg);
    ws.send(JSON.stringify({ id, method, params }));
  });

const expr = `
JSON.stringify({
  标题: document.title,
  服务器: document.querySelector('.server-title h5')?.textContent ?? null,
  当前频道: document.querySelector('.channel-here .channel-name')?.textContent ?? null,
  频道数: document.querySelectorAll('.channel').length,
  右栏成员: [...document.querySelectorAll('.member-name')].map(e => e.textContent.trim()),
  频道树成员: [...document.querySelectorAll('.person-name')].map(e => e.textContent.trim()),
  底部: document.querySelector('.control-center')?.textContent?.trim() ?? null,
  麦克风: document.querySelector('.control-btn')?.textContent?.trim() ?? null,
  弹窗: document.querySelectorAll('.semi-modal').length,
  消息数: document.querySelectorAll('.msg-text').length
})`;

const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true });
console.log(JSON.stringify(JSON.parse(r.result.value), null, 2));
ws.close();
process.exit(0);
