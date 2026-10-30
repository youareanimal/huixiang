// 屏幕 / 摄像头串流。
//
// 画面不走网关：浏览器和对方直接 WebRTC 对连，跟手机端跟手机端之间是一回事。网关只负责三件事——
// 把「开播 / 要加入 / 停止」这些命令转给 TeamSpeak 服务器，转交双方的 SDP 和 ICE 地址，以及在 SDP
// 上加几处 TeamSpeak 特有的修正（H.264 的 profile-level-id、session version、视频方向扩展）。
// 这里因此只剩纯粹的 WebRTC。
//
// 共享是 mesh：每个观众一条 RTCPeerConnection，各发一份。两个人看就是两份上行，五个人就是五份——
// 手机端也是这么做的，它简单、延迟低，代价是上行随人数线性增长。

export type ShareKind = 1 | 2; // 1 摄像头，2 屏幕

export type WatchStatus = 'idle' | 'connecting' | 'live' | 'paused' | 'failed';

export interface StreamState {
  /** 正在共享什么；null 表示没有。 */
  sharing: ShareKind | null;
  /** 服务器已经认下这次共享（拿到了 stream id），观众才会被放进来。 */
  live: boolean;
  viewers: number;
  /** 本地采集的画面，用来给自己看的预览。 */
  local: MediaStream | null;
  /** 正在看谁（客户端的 client id）；0 表示没在看。 */
  watching: number;
  remote: MediaStream | null;
  remoteStatus: WatchStatus;
  error: string | null;
}

export const initialStreamState: StreamState = {
  sharing: null,
  live: false,
  viewers: 0,
  local: null,
  watching: 0,
  remote: null,
  remoteStatus: 'idle',
  error: null,
};

/** 网关推来的一条流事件。字段和 StreamBridge.kt 里 event() 造的一致。 */
export interface StreamEvent {
  t: 'stream';
  event: string;
  streamId?: string;
  viewer?: number;
  count?: number;
  from?: number;
  sdp?: string;
  candidate?: string;
  mid?: string;
  mLine?: number;
  reason?: string;
  what?: string;
  audio?: boolean;
}

/** 网关那边把事件塞进 onStream 回调。 */
export type StreamSender = (msg: Record<string, unknown>) => void;

/**
 * TeamSpeak 自己的 STUN（和 tsproto 的 Streams.kt 是同一组）。
 *
 * 对称 NAT 下 STUN 打不通，需要一个 TURN 中继。构建时用 VITE_TURN_URL / VITE_TURN_USER /
 * VITE_TURN_PASS 配一个，或者在控制台里写 localStorage 的 huixiang.turn
 * （{"url":"turn:host:3478","user":"…","pass":"…"}）—— 手机端也是这么留后路的。
 */
function iceServers(): RTCIceServer[] {
  const servers: RTCIceServer[] = [
    { urls: 'stun:turn.teamspeak.com:3478' },
    { urls: 'stun:turn2.teamspeak.com:3478' },
  ];
  const env = import.meta.env as Record<string, string | undefined>;
  let url = env.VITE_TURN_URL?.trim() ?? '';
  let user = env.VITE_TURN_USER?.trim() ?? '';
  let pass = env.VITE_TURN_PASS?.trim() ?? '';
  if (!url) {
    try {
      const raw = JSON.parse(localStorage.getItem('huixiang.turn') ?? 'null');
      if (raw && typeof raw.url === 'string') {
        url = raw.url;
        user = typeof raw.user === 'string' ? raw.user : '';
        pass = typeof raw.pass === 'string' ? raw.pass : '';
      }
    } catch {
      // 坏掉的配置就当没有
    }
  }
  if (url) {
    servers.push({ urls: url, username: user || undefined, credential: pass || undefined });
  }
  return servers;
}

/**
 * 只留一种 H.264，再加一种 VP8 当退路。
 *
 * Chrome 默认会把每个 profile/level 组合各算一个 H.264 变体，一条 offer 里能塞进七八个 payload
 * （42e01f、4d001f、640c1f… 外加各自的 rtx）。TeamSpeak 的客户端见到这么多重复的 H.264，干脆不回话，
 * 表现就是「点了观看没反应」。手机端没这毛病，因为 libwebrtc 生成的 offer 本来就只带一两个。
 *
 * VP8 留着：万一对面是解不了 H.264 的浏览器，它还有个能选的。
 */
function preferH264(transceiver: RTCRtpTransceiver) {
  const caps = typeof RTCRtpSender !== 'undefined' ? RTCRtpSender.getCapabilities('video') : null;
  if (!caps) return;
  const h264 = caps.codecs.filter((c) => /H264/i.test(c.mimeType));
  const pick =
    h264.find((c) => /profile-level-id=640c/i.test(c.sdpFmtpLine ?? '')) ??
    h264.find((c) => /profile-level-id=42e0/i.test(c.sdpFmtpLine ?? '')) ??
    h264[0];
  const vp8 = caps.codecs.find((c) => /VP8/i.test(c.mimeType));
  const chosen: NonNullable<typeof pick>[] = [];
  if (pick) chosen.push(pick);
  if (vp8) chosen.push(vp8);
  if (!chosen.length) return;
  try {
    transceiver.setCodecPreferences(chosen);
  } catch {
    // 老浏览器没有这个能力，走默认顺序
  }
}

/** 一个观众一条连接时，把上行码率和帧率卡住：屏幕字清楚比帧率高更重要。 */
async function applyLimits(pc: RTCPeerConnection, videoBitrateKbps: number) {
  for (const sender of pc.getSenders()) {
    if (!sender.track) continue;
    try {
      const p = sender.getParameters();
      p.degradationPreference = 'maintain-resolution';
      p.encodings = p.encodings.length ? p.encodings : [{}];
      for (const e of p.encodings) {
        e.maxBitrate = videoBitrateKbps * 1000;
        e.maxFramerate = 30;
      }
      await sender.setParameters(p);
    } catch {
      // 某些浏览器在协商前不给设，忽略
    }
  }
}

export class StreamClient {
  private state: StreamState = { ...initialStreamState };
  private send: StreamSender;
  private onChange: (s: StreamState) => void;
  private log: (msg: string) => void;

  /** 共享时：每个观众一条连接。 */
  private viewers = new Map<number, RTCPeerConnection>();
  /** 观看时：和主播的那一条。 */
  private watchPc: RTCPeerConnection | null = null;
  /** 观看时暂存的候选：它们常常比 offer 先到。 */
  private watchPending: RTCIceCandidateInit[] = [];
  /** 观看时先收下、等 setRemoteDescription 之后再生效。 */
  private local: MediaStream | null = null;
  private remote: MediaStream | null = null;
  private watchTo = 0;
  private starting = false;

  constructor(opts: { send: StreamSender; onChange: (s: StreamState) => void; log?: (msg: string) => void }) {
    this.send = opts.send;
    this.onChange = opts.onChange;
    this.log = opts.log ?? (() => {});
  }

  get snapshot(): StreamState {
    return this.state;
  }

  private update(patch: Partial<StreamState>) {
    this.state = { ...this.state, ...patch };
    this.onChange(this.state);
  }

  // ====== 共享 ======

  /** 共享屏幕：Chrome 会先问要共享哪一块，系统声音是那个弹窗里的一个勾选框。 */
  async startScreen(withAudio = true) {
    if (this.state.sharing || this.starting) return;
    if (!navigator.mediaDevices?.getDisplayMedia) {
      this.update({ error: '这个浏览器不支持直播屏幕，换 Chrome 或 Edge' });
      return;
    }
    this.starting = true;
    let stream: MediaStream;
    try {
      stream = await navigator.mediaDevices.getDisplayMedia({
        video: { frameRate: { max: 30 }, width: { max: 1920 }, height: { max: 1080 } },
        audio: withAudio,
      });
    } catch (e) {
      this.starting = false;
      const why = e instanceof Error ? e.message : String(e);
      // 用户自己点了取消，不是错误
      if (!/denied|cancel|NotAllowed/i.test(why)) this.update({ error: `打不开屏幕共享：${why}` });
      return;
    }
    this.begin(stream, 2);
  }

  /** 共享摄像头。不带麦克风：频道语音已经在用，再采一次会回声。 */
  async startCamera() {
    if (this.state.sharing || this.starting) return;
    if (!navigator.mediaDevices?.getUserMedia) {
      this.update({ error: '这个浏览器打不开摄像头' });
      return;
    }
    this.starting = true;
    let stream: MediaStream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({
        video: { width: { ideal: 1280 }, height: { ideal: 720 }, frameRate: { ideal: 30, max: 30 } },
        audio: false,
      });
    } catch (e) {
      this.starting = false;
      const why = e instanceof Error ? e.message : String(e);
      this.update({ error: `打不开摄像头：${why}` });
      return;
    }
    this.begin(stream, 1);
  }

  private begin(stream: MediaStream, kind: ShareKind) {
    this.starting = false;
    this.local = stream;
    const withAudio = stream.getAudioTracks().length > 0;
    this.update({ sharing: kind, live: false, viewers: 0, local: stream, error: null });
    // 浏览器的「停止共享」按钮和我们的按钮要一致
    for (const t of stream.getVideoTracks()) {
      t.addEventListener('ended', () => this.stopShare());
    }
    this.send({ act: 'share', kind, name: kind === 2 ? '网页屏幕' : '网页摄像头', audio: withAudio });
  }

  stopShare() {
    if (!this.state.sharing) return;
    for (const pc of this.viewers.values()) pc.close();
    this.viewers.clear();
    this.stopLocal();
    this.send({ act: 'stop' });
    this.update({ sharing: null, live: false, viewers: 0, local: null });
  }

  private stopLocal() {
    this.local?.getTracks().forEach((t) => t.stop());
    this.local = null;
  }

  /** 有人要看：给他单独建一条连接，把我们的画面推过去。 */
  private async addViewer(viewer: number) {
    const stream = this.local;
    if (!stream || this.viewers.has(viewer)) return;
    const pc = new RTCPeerConnection({ iceServers: iceServers() });
    this.viewers.set(viewer, pc);

    pc.onicecandidate = (e) => {
      if (e.candidate) {
        this.send({
          act: 'candidate',
          to: viewer,
          candidate: e.candidate.candidate,
          mid: e.candidate.sdpMid ?? '0',
          mLine: e.candidate.sdpMLineIndex ?? 0,
        });
      }
    };
    pc.onconnectionstatechange = () => {
      const s = pc.connectionState;
      this.log(`viewer ${viewer}: ${s}`);
      if (s === 'failed' || s === 'closed') this.dropViewer(viewer);
    };

    // 视频在前、声音在后，和手机端发的顺序一致
    const video = stream.getVideoTracks()[0];
    const audio = stream.getAudioTracks()[0];
    if (video) {
      const vt = pc.addTransceiver(video, { direction: 'sendonly', streams: [stream] });
      preferH264(vt);
    }
    if (audio) {
      pc.addTransceiver(audio, { direction: 'sendonly', streams: [stream] });
    } else {
      // 没有声音也要给一条音频：TeamSpeak 的客户端不会回答只有视频的 offer
      const t = pc.addTransceiver('audio', { direction: 'sendonly' });
      try {
        const p = t.sender.getParameters();
        p.encodings = p.encodings.length ? p.encodings.map((e) => ({ ...e, active: false })) : [{ active: false }];
        await t.sender.setParameters(p);
      } catch {
        // 设不了就让它是静音，反正没有 track
      }
    }
    try {
      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);
      await applyLimits(pc, this.state.sharing === 2 ? 4000 : 2500);
      const sdp = pc.localDescription?.sdp;
      if (!sdp) throw new Error('没有生成 offer');
      this.send({ act: 'offer', to: viewer, sdp });
    } catch (e) {
      this.log(`offer to ${viewer} failed: ${e}`);
      this.dropViewer(viewer);
    }
  }

  private dropViewer(viewer: number) {
    const pc = this.viewers.get(viewer);
    if (!pc) return;
    this.viewers.delete(viewer);
    try {
      pc.close();
    } catch {
      // 已经关了
    }
    this.update({ viewers: this.viewers.size });
  }

  // ====== 观看 ======

  watch(streamer: number) {
    if (streamer <= 0) return;
    if (this.state.watching === streamer) return;
    this.stopWatch(true);
    this.watchTo = streamer;
    this.update({ watching: streamer, remote: null, remoteStatus: 'connecting', error: null });
    // 不知道 stream id 也行：网关会先问服务器要，然后才 join
    this.send({ act: 'watch', streamer });
  }

  /**
   * 不看了。默认要告诉服务器一声：它记着「谁在看谁的流」，自己这边悄悄清掉的话，下次再点同一个人
   * 会收到 "already joined this stream"。
   */
  stopWatch(notify = true) {
    const was = this.state.watching > 0;
    this.watchTo = 0;
    this.watchPending.length = 0;
    this.remote = null;
    if (this.watchPc) {
      try {
        this.watchPc.close();
      } catch {
        // 已经关了
      }
      this.watchPc = null;
    }
    if (notify && was) this.send({ act: 'unwatch' });
    if (was) this.update({ watching: 0, remote: null, remoteStatus: 'idle' });
  }

  /** 这条流看不下去了：退回成员列表，用一条 toast 说明，并把服务器那边的登记也撤掉。 */
  private failWatch(reason: string) {
    this.stopWatch();
    this.update({ error: reason });
  }

  private ensureWatchPc(): RTCPeerConnection {
    if (this.watchPc) return this.watchPc;
    const pc = new RTCPeerConnection({ iceServers: iceServers() });
    this.remote = new MediaStream();
    pc.ontrack = (e) => {
      this.remote?.addTrack(e.track);
      this.update({ remote: this.remote });
    };
    pc.onicecandidate = (e) => {
      if (e.candidate && this.watchTo) {
        this.send({
          act: 'candidate',
          to: this.watchTo,
          candidate: e.candidate.candidate,
          mid: e.candidate.sdpMid ?? '0',
          mLine: e.candidate.sdpMLineIndex ?? 0,
        });
      }
    };
    pc.onconnectionstatechange = () => {
      this.log(`watch: ${pc.connectionState}`);
      if (pc.connectionState === 'connected') {
        this.update({ remoteStatus: this.state.remoteStatus === 'paused' ? 'paused' : 'live' });
      } else if (pc.connectionState === 'failed') {
        this.update({ remoteStatus: 'failed' });
      }
    };
    this.watchPc = pc;
    return pc;
  }

  /** 主播的 offer：第一个是开始，后来的可能是暂停（全部 inactive）或者继续。 */
  private async onOffer(sdp: string) {
    const pc = this.ensureWatchPc();
    try {
      await pc.setRemoteDescription({ type: 'offer', sdp });
      for (const c of this.watchPending.splice(0)) {
        await pc.addIceCandidate(c).catch(() => undefined);
      }
      // 每个媒体段都标了 inactive 就是暂停了（和安卓端的 isPausedOffer 一个判断）：还是要回 answer，
      // 只是不显示画面
      const sections = sdp.split(/\r?\n(?=m=)/).slice(1);
      const paused =
        sections.length > 0 &&
        sections.every((sec) => sec.split(/\r?\n/).some((line) => line.trim() === 'a=inactive'));
      if (paused) this.update({ remoteStatus: 'paused' });
      else this.update({ remoteStatus: pc.connectionState === 'connected' ? 'live' : 'connecting' });

      const answer = await pc.createAnswer();
      await pc.setLocalDescription(answer);
      const out = pc.localDescription?.sdp;
      if (!out) throw new Error('没有生成 answer');
      this.send({ act: 'answer', to: this.watchTo, sdp: out });
    } catch (e) {
      this.log(`watching failed: ${e}`);
      // 连接搞坏了就整个重来：关掉这条 PC，也把服务器那边的 join 撤掉
      this.failWatch(`直播打不开：${e instanceof Error ? e.message : String(e)}`);
    }
  }

  private async onRemoteCandidate(from: number, candidate: string, mid: string, mLine: number) {
    const c: RTCIceCandidateInit = { candidate, sdpMid: mid, sdpMLineIndex: mLine };
    if (this.state.watching && from === this.watchTo) {
      const pc = this.watchPc;
      if (pc && pc.remoteDescription) await pc.addIceCandidate(c).catch(() => undefined);
      else this.watchPending.push(c);
      return;
    }
    const pc = this.viewers.get(from);
    if (pc) await pc.addIceCandidate(c).catch(() => undefined);
  }

  // ====== 网关推来的事件 ======

  onEvent(e: StreamEvent) {
    switch (e.event) {
      case 'started':
        this.update({ live: true, error: null });
        break;
      case 'stopped':
        // 服务器那边结束了这次共享（或者我们自己停的，已经清过了）
        if (this.state.sharing) {
          for (const pc of this.viewers.values()) pc.close();
          this.viewers.clear();
          this.stopLocal();
          this.update({ sharing: null, live: false, viewers: 0, local: null });
        }
        break;
      case 'viewer':
        if (typeof e.viewer === 'number') {
          this.update({ viewers: this.viewers.size + 1 });
          void this.addViewer(e.viewer);
        }
        break;
      case 'viewerLeft':
        if (typeof e.viewer === 'number') this.dropViewer(e.viewer);
        else if (typeof e.count === 'number') this.update({ viewers: e.count });
        break;
      case 'offer':
        if (e.sdp) void this.onOffer(e.sdp);
        break;
      case 'answer': {
        const pc = typeof e.from === 'number' ? this.viewers.get(e.from) : undefined;
        if (pc && e.sdp) {
          void pc
            .setRemoteDescription({ type: 'answer', sdp: e.sdp })
            .catch((err) => this.log(`answer from ${e.from} rejected: ${err}`));
        }
        break;
      }
      case 'candidate':
        if (typeof e.from === 'number' && e.candidate) {
          void this.onRemoteCandidate(e.from, e.candidate, e.mid ?? '0', e.mLine ?? 0);
        }
        break;
      case 'reconnect':
        this.log(`viewer ${e.from} asked to reconnect`);
        break;
      case 'ended':
        if (this.state.watching) {
          // 流已经没了，服务器那边的登记也跟着消失，不用再打个招呼
          this.stopWatch(false);
          this.update({ error: '对方的直播结束了' });
        }
        break;
      case 'refused':
      case 'failed':
        if (e.what === 'share' || this.state.sharing) {
          for (const pc of this.viewers.values()) pc.close();
          this.viewers.clear();
          this.stopLocal();
          this.update({ sharing: null, live: false, viewers: 0, local: null, error: e.reason ?? '服务器拒绝了这次直播' });
        } else {
          // 打不开就把登记撤掉，否则服务器还认为我们在看，下次点同一个人会报 already joined
          this.failWatch(e.reason ?? '直播打不开');
        }
        break;
      default:
        break;
    }
  }

  /** 断开连接时收掉一切，别留一条僵尸 PC。 */
  reset() {
    for (const pc of this.viewers.values()) pc.close();
    this.viewers.clear();
    if (this.watchPc) {
      try {
        this.watchPc.close();
      } catch {
        // 已经关了
      }
      this.watchPc = null;
    }
    this.stopLocal();
    this.watchPending.length = 0;
    this.remote = null;
    this.watchTo = 0;
    this.state = { ...initialStreamState };
    this.onChange(this.state);
  }

  clearError() {
    if (this.state.error) this.update({ error: null });
  }
}
