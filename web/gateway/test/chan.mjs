// 频道管理冒烟：连上网关 → 进入 TS 服务器 → 发一条「建频道」命令，验证 chanOp 回路。
// 用 Node 22+ 自带 WebSocket，不依赖第三方包。
//
//   node chan.mjs pi.animal26.site
//   TOKEN=你的口令 node chan.mjs pi.animal26.site
//   WS=ws://127.0.0.1:8081/ws node chan.mjs pi.animal26.site
//
// 有权限时会真的在服务器上建一个频道（脚本不负责删）；无权限会回一条中文错误，同样说明链路是通的。

const host = process.argv[2] || 'pi.animal26.site';
const port = Number(process.env.PORT || 9987);
const wsUrl = process.env.WS || 'ws://127.0.0.1:8080/ws';
const token = process.env.TOKEN || '';
const nickname = 'chan-smoke-' + Math.random().toString(36).slice(2, 6);

const ws = new WebSocket(wsUrl);
let connected = false;
let started = false;
let finished = false;
const log = (...a) => console.log(...a);

function sendConnect() {
  ws.send(JSON.stringify({ t: 'connect', host, port, nickname, password: '', identity: null }));
}

function finish(code) {
  if (finished) return;
  finished = true;
  try { ws.send(JSON.stringify({ t: 'leave' })); } catch {}
  setTimeout(() => { try { ws.close(); } catch {} ; process.exit(code); }, 300);
}

ws.addEventListener('open', () => {
  log('socket open', wsUrl, '→', host + ':' + port);
  if (token) {
    ws.send(JSON.stringify({ t: 'auth', token }));
  } else {
    sendConnect();
  }
});

ws.addEventListener('message', (ev) => {
  let m;
  try { m = JSON.parse(ev.data); } catch { return; }
  if (m.t === 'ready') { sendConnect(); return; }
  if (m.t === 'state') {
    if (m.phase === 'connected' && !connected && m.ownId > 0) {
      connected = true;
      log('connected ownId=', m.ownId, 'channels=', (m.tree || []).length);
      if (!started) {
        started = true;
        setTimeout(() => {
          log('sending channel create...');
          ws.send(JSON.stringify({ t: 'channel', act: 'create', name: 'smoke-' + nickname, description: 'smoke test' }));
        }, 400);
      }
    }
    if (m.error) log('state error:', m.error);
  }
  if (m.t === 'chanOp') {
    log('CHANOP =>', JSON.stringify(m));
    finish(0);
  }
});

ws.addEventListener('error', () => log('ws error'));
setTimeout(() => {
  log('TIMEOUT (connected=', connected, ')');
  finish(connected ? 2 : 3);
}, 25000);
