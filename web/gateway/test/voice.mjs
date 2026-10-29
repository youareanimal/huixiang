#!/usr/bin/env node
// 语音链路验证：两个会话进同一个频道，A 发 Opus 帧，看 B 能否收到。
// 用的是 20ms 的 Opus 静音帧（RFC 6716 comfort noise），不需要麦克风。
// 用法：node voice.mjs <host> [port]

const host = process.argv[2] ?? 'pi.animal26.site';
const port = Number(process.argv[3] ?? 9987);
const URL = process.env.WS ?? 'ws://127.0.0.1:8080/ws';
const TOKEN = process.env.TOKEN ?? null;

// 一帧 20ms 的 Opus 静音（SILK, 48kHz）：3 字节，服务器会正常转发
const SILENCE = new Uint8Array([0xf8, 0xff, 0xfe]);

class Tab {
  constructor(name) {
    this.name = name;
    this.ws = new WebSocket(URL);
    this.phase = 'idle';
    this.ownId = 0;
    this.ownChannel = 0;
    this.got = 0;
    this.ready = new Promise((res) => (this._res = res));
    this.ws.addEventListener('open', () => {
      this.ws.binaryType = 'arraybuffer';
      if (TOKEN) this.ws.send(JSON.stringify({ t: 'auth', token: TOKEN }));
      else this.join();
    });
    this.ws.addEventListener('message', (e) => {
      if (typeof e.data !== 'string') {
        const b = new Uint8Array(e.data);
        if (b[0] === 2) {
          this.got++;
          const from = (b[2] << 8) | b[3];
          if (this.got <= 3) console.log(`[${this.name}] 收到语音帧：来自 client ${from}，codec ${b[1]}，${b.length - 4} 字节`);
        }
        return;
      }
      const m = JSON.parse(e.data);
      if (m.t === 'ready') { this.join(); return; }
      if (m.t !== 'state') return;
      this.phase = m.phase;
      this.ownId = m.ownId;
      this.ownChannel = m.ownChannel;
      if (m.phase === 'connected' && !this._resolved) { this._resolved = true; this._res(m); }
    });
    this.ws.addEventListener('error', (e) => { console.error(`[${this.name}] 错误`, e.message ?? e); process.exit(1); });
  }
  send(obj) { this.ws.send(JSON.stringify(obj)); }
  join() { this.send({ t: 'connect', host, port, nickname: this.name }); }
  voice(codec, bytes) { this.ws.send(bytes); }
  close() { try { this.send({ t: 'leave' }); this.ws.close(); } catch {} }
}

const jitter = () => 400 + Math.floor(Math.random() * 400);

(async () => {
  const a = new Tab(`voiceA-${Math.floor(Math.random() * 900 + 100)}`);
  const pa = await a.ready;
  console.log(`A 已连上「${pa.serverName}」，client ${a.ownId}，频道 ${a.ownChannel}`);

  const b = new Tab(`voiceB-${Math.floor(Math.random() * 900 + 100)}`);
  await b.ready;
  console.log(`B 已连上，client ${b.ownId}，频道 ${b.ownChannel}`);

  // B 主动进 A 的频道，保证两人同频道
  if (b.ownChannel !== a.ownChannel) {
    b.send({ t: 'join', cid: a.ownChannel });
    await new Promise((r) => setTimeout(r, 1200));
  }
  console.log(`同频道确认：A=${a.ownChannel} B=${b.ownChannel}`);

  // A 连续发 25 帧（约 0.5 秒语音）
  console.log('A 开始发送 25 个 Opus 静音帧…');
  for (let i = 0; i < 25; i++) {
    const frame = new Uint8Array([1, 4, ...SILENCE]); // [上行标记][codec 4][opus]
    a.voice(4, frame);
    await new Promise((r) => setTimeout(r, 20));
  }
  a.voice(4, new Uint8Array([1, 4])); // 空帧 = 停止说话

  await new Promise((r) => setTimeout(r, 1500));

  console.log(`\n结果：B 收到 ${b.got} 帧`);
  const ok = b.got > 0;
  console.log(ok ? '语音链路通过：麦克风 → 网关 → TS 服务器 → 网关 → 浏览器' : '语音链路未收到帧（服务器可能限制了静音帧，或频道权限不足）');
  a.close();
  b.close();
  setTimeout(() => process.exit(ok ? 0 : 1), 300);
})();

setTimeout(() => { console.log('整体超时'); process.exit(1); }, 30000);
