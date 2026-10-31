#!/usr/bin/env node
// 文件传输的端到端验证：连接 → 列目录 → 上传 → 再列确认 → 下载比对 → 删除 → 再列确认。
//
// 用法：node files.mjs <host> [port]
// 会真的在服务器上留一个几字节的说明文件，验证完自己删掉；中途失败时请手动清掉 web-e2e-*.txt。
//
// 注意这里验的是**两条线**：列表走 WebSocket（`{"t":"file","act":"list"}` → `{"t":"files"}`），
// 字节走 HTTP（/files/upload、/files/download，靠状态快照里的 fileToken 认会话）。两条线在网关那头
// 汇到同一个 FileTransfer 上，所以任何一条断了这里都会失败。

const host = process.argv[2] ?? 'pi.animal26.site';
const port = Number(process.argv[3] ?? 9987);
const nickname = process.env.NICKNAME ?? `webtest-${Math.floor(Math.random() * 900 + 100)}`;
const WS_URL = process.env.WS ?? 'ws://127.0.0.1:8080/ws';
const TOKEN = process.env.TOKEN ?? null;
/**
 * 在哪个频道里试。默认待在自己进去的那个；上传权限是按频道（和身份）给的，游客往往只有其中几个能写，
 * 所以换一个再试是常事：`CHANNEL=2 node files.mjs`。
 */
const WANT_CHANNEL = Number(process.env.CHANNEL ?? 0);
/** HTTP 端点和 WebSocket 同一个源，从 WS 地址推出来就够。 */
const BASE = process.env.BASE ?? WS_URL.replace(/^ws/, 'http').replace(/\/ws$/, '');

const log = (...a) => console.log(...a);
const fail = (why) => {
  log(`\n失败：${why}`);
  finish(1);
};

const name = `web-e2e-${Date.now()}.txt`;
const content = `huixiang web file transfer ${new Date().toISOString()}\n`;
const remote = `/${name}`;

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

const uploadUrl = (path, overwrite) =>
  `${BASE}/files/upload?cid=${cid}&path=${encodeURIComponent(path)}&overwrite=${overwrite ? '1' : '0'}`;
const downloadUrl = (path) => `${BASE}/files/download?cid=${cid}&path=${encodeURIComponent(path)}`;

/**
 * 下行链路的单独验证：从目录里挑一个不大的现成文件下载，比字节数。
 * 上传没权限时靠它证明 /files/download 那一半是通的。
 */
async function probeExisting(entries) {
  const pick = entries.find((x) => !x.folder && x.size > 0 && x.size < 8 * 1024 * 1024);
  if (!pick) {
    log('目录里没有合适的文件，下行链路这次跳过');
    return;
  }
  const res = await fetch(downloadUrl(`/${pick.name}`), { headers: { 'X-File-Token': fileToken } });
  if (!res.ok) {
    log(`下行链路验证失败：${res.status} ${(await res.text()).trim()}`);
    return;
  }
  const buf = Buffer.from(await res.arrayBuffer());
  const same = buf.length === pick.size;
  log(
    `下行链路验证：下载「${pick.name}」${buf.length} 字节，` +
      (same ? '与目录里的大小一致' : `目录说 ${pick.size}，不一致`),
  );
}

async function run() {
  if (ran) return;
  ran = true;

  // 1. 当前目录先看一眼：这一步只验证命令线，服务器拒绝（没权限）也要如实报出来
  const before = await listFiles('/');
  log(`根目录现有 ${before.length} 项`);

  // 2. 上传
  const up = await fetch(uploadUrl(remote, false), {
    method: 'POST',
    headers: { 'X-File-Token': fileToken, 'Content-Type': 'application/octet-stream' },
    body: Buffer.from(content, 'utf8'),
  });
  if (!up.ok) {
    const why = (await up.text()).trim();
    // 上传被服务器权限挡住时，至少还能验证下行：下载现成的一个文件，看看字节能不能原样下来
    await probeExisting(before);
    return fail(
      why.includes('权限')
        ? `上传被拒：${why}\n  这个身份在频道 #${cid} 没有传文件的权限（服务器的文件权限位）。\n  换一个频道再试：CHANNEL=<频道号> node files.mjs ${host}`
        : `上传被拒：${up.status} ${why}`,
    );
  }
  log(`上传成功：${name}（${content.length} 字节）`);

  // 3. 列表里应该出现
  const after = await listFiles('/');
  const entry = after.find((x) => x.name === name);
  if (!entry) return fail('上传报成功，但目录里没有这个文件');
  if (entry.size !== content.length) return fail(`大小对不上：服务器 ${entry.size}，本地 ${content.length}`);
  log(`目录里出现了它，大小 ${entry.size}`);

  // 4. 下载回来逐字节比对
  const down = await fetch(downloadUrl(remote), { headers: { 'X-File-Token': fileToken } });
  if (!down.ok) return fail(`下载被拒：${down.status} ${(await down.text()).trim()}`);
  const back = await down.text();
  if (back !== content) return fail(`内容对不上：${JSON.stringify(back)}`);
  log(`下载回来 ${back.length} 字节，内容一致`);

  // 5. 同名再传一次应当被服务器挡住（不覆盖）
  const again = await fetch(uploadUrl(remote, false), {
    method: 'POST',
    headers: { 'X-File-Token': fileToken, 'Content-Type': 'application/octet-stream' },
    body: Buffer.from('x'),
  });
  if (again.ok) return fail('同名文件本来该被拒绝，却传上去了');
  log(`同名重传被拒（${again.status}）：${(await again.text()).trim()}`);

  // 6. 删掉，恢复原样
  ws.send(JSON.stringify({ t: 'file', act: 'delete', cid, path: remote }));
  const cleaned = await waitFiles(cid, '/');
  if (cleaned.some((x) => x.name === name)) return fail('删了还在');
  log('删除完成，目录已复原');

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
