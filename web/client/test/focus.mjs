#!/usr/bin/env node
// 清理浏览器里多余的标签页，只留应用本身并把它切到前台。
// 用法：node focus.mjs [应用URL片段]

const PORT = Number(process.env.CDP_PORT ?? 9334);
const needle = process.argv[2] ?? '127.0.0.1:8080';
const base = `http://127.0.0.1:${PORT}`;

const list = await fetch(`${base}/json/list`).then((r) => r.json());
const pages = list.filter((t) => t.type === 'page');
const keep = pages.find((t) => t.url.includes(needle));

if (!keep) {
  console.log('没找到应用页面，现有：', pages.map((p) => p.url).join(', '));
  process.exit(1);
}

for (const p of pages) {
  if (p.id === keep.id) continue;
  await fetch(`${base}/json/close/${p.id}`).catch(() => {});
  console.log('关掉：', p.title || p.url);
}

// 切到前台
const ws = new WebSocket(keep.webSocketDebuggerUrl);
await new Promise((res) => ws.addEventListener('open', res));
ws.send(JSON.stringify({ id: 1, method: 'Page.bringToFront', params: {} }));
await new Promise((r) => setTimeout(r, 500));
ws.close();

console.log('保留并激活：', keep.title, '|', keep.url);
process.exit(0);
