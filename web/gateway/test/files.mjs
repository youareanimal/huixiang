#!/usr/bin/env node
// 频道文件区（只读）的端到端验证：连接 → 列目录 → 进一层文件夹 → 下载比对 → 确认写入口已经不在。
//
// 用法：node files.mjs <host> [port]
// 只读，不会在服务器上留下任何东西。
//
// 这里验的是**两条线**：列表走 WebSocket（`{"t":"file","act":"list"}` → `{"t":"files"}`），
// 字节走 HTTP（/files/download，靠状态快照里的 fileToken 认会话）。两条线在网关那头汇到同一个
// FileTransfer 上，所以任何一条断了这里都会失败。

const host = process.argv[2] ?? 'pi.animal26.site';
const port = Number(process.argv[3] ?? 9987);
const nickname = process.env.NICKNAME ?? `webtest-${Math.floor(Math.random() * 900 + 100)}`;
const WS_URL = process.env.WS ?? 'ws://127.0.0.1:8080/ws';
const TOKEN = process.env.TOKEN ?? null;
/**
 * 在哪个频道里试。默认待在自己进去的那个；想看别的频道就 `CHANNEL=2 node files.mjs`。
 */
const WANT_CHANNEL = Number(process.env.CHANNEL ?? 0);
/** HTTP 端点和 WebSocket 同一个源，从 WS 地址推出来就够。 */
const BASE = process.env.BASE ?? WS_URL.replace(/^ws/, 'http').replace(/\/ws$/, '');

const log = (...a) => console.log(...a);
const fail = (why) => {
  log(`\n失败：${why}`);
  finish(1);
};

const ws = new WebSocket(WS_URL);
let fileToken = null;
let cid = 0;
let started = false;
let ran = false;

/** 等一条 files 消息：按 cid + path 认，目录里的旧结果不会顶替新的。 */
const waiters = [];
function waitFiles(wantCid, wantPath, ms = 20_000) {
  return new Promise((resolve, reject) => {
    const w = { wantCid, wantPath, resolve, reject };
    waiters.push(w);
    setTimeout(() => {
      const i = waiters.indexOf(w);
      if (i >= 0) {
        waiters.splice(i, 1);
        reject(new Error(`等不到 ${wantPath} 的目录列表`));
      }
    }, ms);
  });
}

function settle(m) {
  const i = waiters.findIndex((w) => w.wantCid === m.cid && w.wantPath === m.path);
  if (i >= 0) {
    const w = waiters.splice(i, 1)[0];
    if (m.error) w.reject(new Error(m.error));
    else w.resolve(m.entries ?? []);
  }
}

ws.addEventListener('open', () => {
  if (TOKEN) ws.send(JSON.stringify({ t: 'auth', token: TOKEN }));
  else ready();
});

function ready() {
  if (started) return;
  started = true;
  log(`连接 ${host}:${port} 昵称 ${nickname}`);
  ws.send(JSON.stringify({ t: 'connect', host, port, nickname, identity: process.env.IDENTITY ?? null }));
}

ws.addEventListener('message', (e) => {
  if (typeof e.data !== 'string') return;
  const m = JSON.parse(e.data);

  if (m.t === 'ready') return ready();
  if (m.t === 'files') return settle(m);
  if (m.t !== 'state') return;

  if (m.error) log(`[服务器] ${m.error}`);

  if (m.phase === 'connected') {
    if (!fileToken) {
      fileToken = m.fileToken;
      if (!fileToken) return fail('状态快照里没有 fileToken，网关是不是旧版本');
      if (!m.ownChannel) return fail('还没进频道，文件区没有归属');
      cid = m.ownChannel;
      log(`已连上「${m.serverName}」，频道 #${cid}，传输令牌 ${fileToken.slice(0, 8)}…`);
      if (WANT_CHANNEL && cid !== WANT_CHANNEL) {
        log(`切到频道 #${WANT_CHANNEL}`);
        ws.send(JSON.stringify({ t: 'join', cid: WANT_CHANNEL }));
        return;
      }
      void run();
      return;
    }
    // 换频道了：文件区跟着换，得用新的 cid 才问得到
    if (m.ownChannel && m.ownChannel !== cid) {
      cid = m.ownChannel;
      if (WANT_CHANNEL && cid !== WANT_CHANNEL) return;
      log(`现在在频道 #${cid}`);
      void run();
    }
  }
});

ws.addEventListener('error', (e) => {
  console.error('WebSocket 错误', e.message ?? e);
  process.exit(1);
});

const listFiles = (path) => {
  ws.send(JSON.stringify({ t: 'file', act: 'list', cid, path }));
  return waitFiles(cid, path);
};

const downloadUrl = (path) => `${BASE}/files/download?cid=${cid}&path=${encodeURIComponent(path)}`;

async function run() {
  if (ran) return;
  ran = true;

  // 1. 根目录
  const root = await listFiles('/');
  const shown = root.map((x) => (x.folder ? `${x.name}/` : x.name)).join(' · ');
  log(`根目录 ${root.length} 项${shown ? `：${shown}` : '（空）'}`);

  // 2. 进一层文件夹：验证路径拼接和「换目录」那条命令
  const folder = root.find((x) => x.folder);
  if (folder) {
    const inner = await listFiles(`/${folder.name}`);
    log(`进「${folder.name}」看到 ${inner.length} 项`);
  }

  // 3. 下载一个现成的文件，比对字节数
  const file = root.find((x) => !x.folder && x.size > 0 && x.size < 32 * 1024 * 1024);
  if (!file) {
    log('根目录没有适合下载的文件，这一步跳过');
  } else {
    const res = await fetch(downloadUrl(`/${file.name}`), { headers: { 'X-File-Token': fileToken } });
    if (!res.ok) return fail(`下载被拒：${res.status} ${(await res.text()).trim()}`);
    const buf = Buffer.from(await res.arrayBuffer());
    if (buf.length !== file.size) return fail(`大小对不上：目录说 ${file.size}，实际下来 ${buf.length}`);
    log(`下载「${file.name}」${buf.length} 字节，与目录一致`);
  }

  // 4. 只读该是只读的：上传端点已经拆掉了
  const up = await fetch(`${BASE}/files/upload?cid=${cid}&path=/x.txt`, {
    method: 'POST',
    headers: { 'X-File-Token': fileToken, 'Content-Type': 'application/octet-stream' },
    body: Buffer.from('x'),
  });
  if (up.ok) return fail('上传端点居然还能用，只读的面板不该留着它');
  log(`上传端点确实不在（HTTP ${up.status}）`);

  finish(0);
}

function finish(code) {
  try {
    ws.send(JSON.stringify({ t: 'leave' }));
  } catch {}
  setTimeout(
    () => {
      try {
        ws.close();
      } catch {}
      log(code === 0 ? '\n全部通过' : '\n有步骤未完成');
      process.exit(code);
    },
    code === 0 ? 400 : 100,
  );
}

setTimeout(() => fail(`超时（令牌 ${fileToken ? '有' : '无'}，频道 ${cid}）`), Number(process.env.TIMEOUT ?? 45_000));
