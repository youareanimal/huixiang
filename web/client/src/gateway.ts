import type {
  AccountState,
  BookmarksMessage,
  ChatLine,
  ConnectOptions,
  ContactsMessage,
  FilesMessage,
  State,
} from './protocol';
import type { StreamEvent } from './stream';

export interface GatewayHandlers {
  onOpen?: () => void;
  /** 口令通过（或者服务端没要求口令）。 */
  onReady?: () => void;
  onIdentity?: (identity: string) => void;
  onState?: (state: State) => void;
  onChat?: (line: ChatLine) => void;
  /** 一层目录的文件列表（或者一次失败的列表）。 */
  onFiles?: (msg: FilesMessage) => void;
  /** myTeamSpeak 账号状态：游客、登录中、已登录或失败。 */
  onAccount?: (state: AccountState) => void;
  /** 云书签。 */
  onBookmarks?: (msg: BookmarksMessage) => void;
  /** 好友列表。 */
  onContacts?: (msg: ContactsMessage) => void;
  /** 一帧语音，已去掉 4 字节头：codec 和说话的人。 */
  onVoice?: (codec: number, clientId: number, opus: Uint8Array) => void;
  /** 串流信令：谁开播了、谁要看、以及双方的 SDP 和地址。 */
  onStream?: (event: StreamEvent) => void;
  /** 网关这句话走了多久：浏览器 ↔ 网关的 WebSocket 往返（毫秒）。 */
  onRoundTrip?: (ms: number) => void;
  /** 网关到 TeamSpeak 服务器的 UDP 往返（毫秒），由服务器每秒的 keepalive ping 算出。 */
  onServerPing?: (ms: number) => void;
  /** 服务端关了连接：1008 多半是口令不对或来源不被允许。 */
  onClose?: (code: number, reason: string) => void;
  onError?: (message: string) => void;
}

/**
 * 网关的一条 WebSocket。文本帧是 JSON，二进制帧只有语音。
 *
 * 口令（如果服务端要）必须在第一帧发出去，否则 5 秒后被断。
 */
export class Gateway {
  private ws: WebSocket | null = null;
  private ready = false;
  private queue: string[] = [];
  /** 登录和连接可能同时想开这条连接；只让第一个真的握手。 */
  private opening: Promise<void> | null = null;
  /** 自己的往返探针：发出去的序号 → 发出时刻。 */
  private pingSent = new Map<number, number>();
  private pingSeq = 0;
  private pingTimer: number | null = null;

  constructor(
    private readonly url: string,
    private readonly token: string,
    private readonly handlers: GatewayHandlers,
  ) {}

  get isReady() {
    return this.ready;
  }

  /** 已连上且没被关掉；离开服务器后网关连接会留着，账号才能继续用。 */
  get isOpen() {
    return this.ws?.readyState === WebSocket.OPEN;
  }

  open(): Promise<void> {
    if (this.isOpen && this.ready) return Promise.resolve();
    if (this.opening) return this.opening;
    const opening = new Promise<void>((resolve, reject) => {
      const ws = new WebSocket(this.url);
      ws.binaryType = 'arraybuffer';
      this.ws = ws;
      let settled = false;

      ws.onopen = () => {
        this.handlers.onOpen?.();
        if (this.token) {
          ws.send(JSON.stringify({ t: 'auth', token: this.token }));
          // ready 之前不能发别的，先攒着
        } else {
          this.ready = true;
          this.handlers.onReady?.();
          settled = true;
          resolve();
          this.flush();
          this.startPing();
        }
      };

      ws.onmessage = (e) => {
        if (typeof e.data !== 'string') {
          const b = new Uint8Array(e.data as ArrayBuffer);
          // 正好 4 字节 = 空帧 = 这个人说完话了，也要往上传，不然说话提示只能等超时熄
          if (b.length >= 4 && b[0] === 2) {
            const clientId = (b[2] << 8) | b[3];
            this.handlers.onVoice?.(b[1], clientId, b.subarray(4));
          }
          return;
        }
        let m: Record<string, unknown>;
        try {
          m = JSON.parse(e.data);
        } catch {
          return;
        }
        switch (m.t) {
          case 'ready':
            this.ready = true;
            this.handlers.onReady?.();
            if (!settled) {
              settled = true;
              resolve();
            }
            this.flush();
            this.startPing();
            break;
          case 'pong': {
            const sentAt = this.pingSent.get(Number(m.v));
            if (sentAt != null) {
              this.pingSent.delete(Number(m.v));
              this.handlers.onRoundTrip?.(Math.max(0, Math.round(performance.now() - sentAt)));
            }
            break;
          }
          case 'serverPing':
            if (typeof m.v === 'number') this.handlers.onServerPing?.(m.v);
            break;
          case 'identity':
            this.handlers.onIdentity?.(String(m.v ?? ''));
            break;
          case 'state':
            this.handlers.onState?.(m as unknown as State);
            break;
          case 'chat':
            this.handlers.onChat?.(m as unknown as ChatLine);
            break;
          case 'files':
            this.handlers.onFiles?.(m as unknown as FilesMessage);
            break;
          case 'account':
            this.handlers.onAccount?.(m as unknown as AccountState);
            break;
          case 'bookmarks':
            this.handlers.onBookmarks?.(m as unknown as BookmarksMessage);
            break;
          case 'contacts':
            this.handlers.onContacts?.(m as unknown as ContactsMessage);
            break;
          case 'stream':
            this.handlers.onStream?.(m as unknown as StreamEvent);
            break;
        }
      };

      ws.onclose = (e) => {
        this.ready = false;
        this.ws = null;
        this.stopPing();
        if (!settled) {
          settled = true;
          const why = e.code === 1008 ? '网关拒绝了这次连接（口令或来源不被允许）' : e.reason || `连接关闭 ${e.code}`;
          reject(new Error(why));
        }
        this.handlers.onClose?.(e.code, e.reason ?? '');
      };

      ws.onerror = () => {
        if (!settled) {
          settled = true;
          reject(new Error('连不上网关，检查地址和网络'));
        }
      };
    });
    const tracked = opening.finally(() => {
      this.opening = null;
    });
    this.opening = tracked;
    return tracked;
  }

  private flush() {
    const items = this.queue.splice(0);
    for (const s of items) this.ws?.send(s);
  }

  /** 每两秒探一次浏览器 ↔ 网关的往返；只有连上之后才探，免得占着队列。 */
  private startPing() {
    if (this.pingTimer != null) return;
    const probe = () => {
      if (!this.ready) return;
      const id = ++this.pingSeq;
      this.pingSent.set(id, performance.now());
      // 丢了的探针别一直留着
      for (const key of this.pingSent.keys()) {
        if (key <= id - 8) this.pingSent.delete(key);
      }
      this.send({ t: 'ping', v: id });
    };
    probe();
    this.pingTimer = window.setInterval(probe, 2000);
  }

  private stopPing() {
    if (this.pingTimer != null) {
      window.clearInterval(this.pingTimer);
      this.pingTimer = null;
    }
    this.pingSent.clear();
  }

  private send(msg: unknown) {
    const text = JSON.stringify(msg);
    if (!this.ws) return;
    if (!this.ready) {
      this.queue.push(text);
      return;
    }
    this.ws.send(text);
  }

  connect(opts: ConnectOptions) {
    this.send({ t: 'connect', ...opts, identity: opts.identity ?? undefined });
  }

  leave() {
    this.send({ t: 'leave' });
  }

  join(cid: number, password = '') {
    this.send({ t: 'join', cid, password });
  }

  say(text: string, channel: number, client = 0) {
    this.send({ t: 'say', text, channel, client });
  }

  rename(name: string) {
    this.send({ t: 'rename', name });
  }

  setMuted(input: boolean, output: boolean) {
    this.send({ t: 'mute', input, output });
  }

  /** 串流的信令。画面不经过这里：那是浏览器和对方之间的 WebRTC。 */
  stream(msg: Record<string, unknown>) {
    this.send({ t: 'stream', ...msg });
  }

  /**
   * 要一层目录的文件列表。字节不走这里（WebSocket 一帧只有 64 KB），答案从 `onFiles` 回来，
   * 文件本身的收发在 files.ts 里走 HTTP。
   */
  listFiles(cid: number, path: string) {
    this.send({ t: 'file', act: 'list', cid, path });
  }

  /** myTeamSpeak 登录：密码只经过这条已认证的 WebSocket，不写本地存储。 */
  accountLogin(email: string, password: string, otp: string) {
    this.send({ t: 'account', act: 'login', email, password, otp });
  }

  accountLogout() {
    this.send({ t: 'account', act: 'logout' });
  }

  accountRefresh() {
    this.send({ t: 'account', act: 'refresh' });
  }

  /** 一帧 Opus（960 采样 / 20ms）。空数组表示停止说话。 */
  voice(codec: number, opus: Uint8Array) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
    const frame = new Uint8Array(2 + opus.length);
    frame[0] = 1;
    frame[1] = codec;
    frame.set(opus, 2);
    this.ws.send(frame);
  }

  close() {
    this.ready = false;
    this.queue.length = 0;
    this.stopPing();
    this.ws?.close();
    this.ws = null;
  }
}

/** 网关要不要口令，连之前先问一句。 */
export async function tokenRequired(): Promise<boolean> {
  try {
    const r = await fetch('/auth', { headers: { Accept: 'application/json' } });
    if (!r.ok) return false;
    const j = await r.json();
    return j?.required === true;
  } catch {
    return false;
  }
}
