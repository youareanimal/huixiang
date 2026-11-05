import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Toast } from '@douyinfe/semi-ui';
import { Gateway, tokenRequired } from './gateway';
import {
  eachMember,
  emptyBookmarks,
  emptyCloud,
  emptyContacts,
  effectiveNickname,
  findChannel,
  guestAccount,
  memberKey,
  store,
  type AccountState,
  type BookmarksMessage,
  type ChatLine,
  type CloudChat,
  type CloudState,
  type ConnectOptions,
  type ContactsMessage,
  type FilesMessage,
  type Friend,
  type Member,
  type MicMode,
  type State,
} from './protocol';
import { forgetAccount, rememberAccount, removeAccountAvatar, uploadAccountAvatar } from './account';
import { appendLine, loadPage, mergeLines, serverKey } from './chatHistory';
import { clampGain, DEFAULT_GAIN } from './audio/gain';
import { TalkingTracker } from './talking';
import { StreamClient, initialStreamState, type StreamState } from './stream';
import { notifyPermission, notifySupported, requestNotifyPermission, showMessageNotice } from './notify';
import { avatarSources } from './ui';
import { VoicePlayer, webCodecsAvailable } from './audio/player';
import { MicSender, micCodecAvailable } from './audio/mic';
import ConnectPage from './components/ConnectPage';
import ServerPage from './components/ServerPage';

const MAX_CHAT = 300;

/** 一次从历史里取多少条：进频道带出最近一屏，往上翻一次加这么多。 */
const HISTORY_PAGE = 30;

/** 解码/采集电平超过这条线才算「在说话」，两侧用同一个门槛。 */
const TALK_LEVEL = 0.045;

function gatewayUrl(): string {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${proto}//${location.host}/ws`;
}

export default function App() {
  const [tokenNeeded, setTokenNeeded] = useState(false);
  // 口令要求是否已经问过网关。自动恢复登录要等它，否则会拿空口令白连一次。
  const [tokenChecked, setTokenChecked] = useState(false);
  const [token, setToken] = useState(() => store.token());
  const [state, setState] = useState<State | null>(null);
  const [chat, setChat] = useState<ChatLine[]>([]);
  // 当前频道的旧消息：进频道、重连时从本地库（必要时再问网关）取出来的一屏，往上翻继续加。
  // 实时消息仍在 chat 里，两者渲染前合并去重。
  const [historyLines, setHistoryLines] = useState<ChatLine[]>([]);
  const [historyMore, setHistoryMore] = useState(false);
  const [historyBusy, setHistoryBusy] = useState(false);
  // 更早的消息本地库一份、网关一份，任一方还有就先允许继续往上翻
  const moreRef = useRef({ local: false, gateway: false });
  // 文件区最近一次的目录列表。只留一份：面板关着的时候没人看，多留是浪费
  const [files, setFiles] = useState<FilesMessage | null>(null);
  // 账号独立于语音连接：离开服务器后这条 WebSocket 还留着，账号、书签和好友继续可用
  const [account, setAccount] = useState<AccountState>(() => guestAccount());
  const [bookmarks, setBookmarks] = useState<BookmarksMessage>(() => emptyBookmarks());
  const [friends, setFriends] = useState<ContactsMessage>(() => emptyContacts());
  // 好友云聊天：登录后才连得上；连上后和好友一对一聊
  const [cloud, setCloud] = useState<CloudState>(() => emptyCloud());
  const [cloudChat, setCloudChat] = useState<CloudChat | null>(null);
  const [cloudNotice, setCloudNotice] = useState<string | null>(null);
  const [connecting, setConnecting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // 延迟拆成两段：浏览器↔网关、网关↔服务器（TeamSpeak 的 UDP ping）
  const [ping, setPing] = useState<{ gateway: number | null; server: number | null }>({
    gateway: null,
    server: null,
  });
  const [micOn, setMicOn] = useState(false);
  const [micLevel, setMicLevel] = useState(0);
  const [audioReady, setAudioReady] = useState(false);

  // 音量都是本地增益：上行改编码前的 PCM，下行改每个人的播放节点，服务器不参与
  const [micGain, setMicGain] = useState(() => store.micGain());
  const [volumes, setVolumes] = useState<Record<string, number>>(() => store.volumes());

  // 说话方式：自动（检测到人声才发）/ 常开 / 按住说话，默认自动，存本地
  const [micMode, setMicModeState] = useState<MicMode>(() => store.micMode());
  const [pttHeld, setPttHeld] = useState(false);
  const pttMode = micMode === 'ptt';
  const setMicMode = useCallback((m: MicMode) => {
    setMicModeState(m);
    store.setMicMode(m);
  }, []);

  // 桌面通知：有新消息时弹系统通知。开关存本地，但真正的权限在浏览器手里——
  // 存了「开」而权限被撤销时按关处理，免得开关看着是开的却弹不出来。
  const [notifyOn, setNotifyOn] = useState(
    () => store.notify() && notifyPermission() === 'granted',
  );
  // 网关回调是长生命周期闭包，读不到最新的 notifyOn，用 ref 兜住
  const notifyOnRef = useRef(notifyOn);
  notifyOnRef.current = notifyOn;

  // 谁在说话：从下行语音帧判出来（服务器那套 talk status 不是每个服务器都发）
  const [voiceTalking, setVoiceTalking] = useState<ReadonlySet<number>>(() => new Set<number>());
  const talkingRef = useRef<TalkingTracker | null>(null);
  if (!talkingRef.current) talkingRef.current = new TalkingTracker(setVoiceTalking);
  const talking = talkingRef.current;

  // 串流：画面是浏览器和对方直连的 WebRTC，网关只管信令
  const [streamState, setStreamState] = useState<StreamState>(initialStreamState);

  const gatewayRef = useRef<Gateway | null>(null);
  const playerRef = useRef<VoicePlayer | null>(null);
  const micRef = useRef<MicSender | null>(null);
  const streamRef = useRef<StreamClient | null>(null);
  const lastAddress = useRef<{ address: string; nickname: string } | null>(null);
  // 回调里要读最新状态，不能靠闭包
  const stateRef = useRef<State | null>(null);

  useEffect(() => {
    void tokenRequired()
      .then(setTokenNeeded)
      .finally(() => setTokenChecked(true));
  }, []);

  // 离开页面时把连接和麦克风都收掉，别让服务端留着一条僵尸会话
  useEffect(() => {
    return () => {
      streamRef.current?.reset();
      micRef.current?.stop();
      playerRef.current?.close();
      gatewayRef.current?.close();
    };
  }, []);

  // 麦克风开关、说话方式、按住说话共同决定「这一刻到底发不发」
  useEffect(() => {
    if (!micOn) return;
    micRef.current?.setMode(micMode);
    micRef.current?.setSending(pttMode ? pttHeld : true);
  }, [micMode, pttMode, pttHeld, micOn]);

  // 名单一变就把每个人的音量对齐：新来的按记下的值起步，走掉的顺手收掉
  useEffect(() => {
    const p = playerRef.current;
    if (!state || !p) return;
    const entries: Array<{ id: number; volume: number }> = [];
    eachMember(state.tree, (m) => {
      // 自己说的话不会从网关回来，不用给自己留节点
      if (m.id === state.ownId) return;
      entries.push({ id: m.id, volume: volumes[memberKey(m)] ?? DEFAULT_GAIN });
    });
    p.syncVolumes(entries);
  }, [state, volumes]);

  // 串流出错用一条 toast 报掉，不占画面位置；同一条错误只弹一次
  const lastStreamError = useRef<string | null>(null);
  useEffect(() => {
    const e = streamState.error;
    if (e && e !== lastStreamError.current) Toast.error(e);
    lastStreamError.current = e;
  }, [streamState.error]);

  const ensurePlayer = useCallback(() => {
    if (!playerRef.current) {
      // 亮不亮看解码后的实际音量：开着麦一直发静音帧的人不算在说话
      playerRef.current = new VoicePlayer((clientId, level) => {
        if (level > TALK_LEVEL) talking.heard(clientId);
      });
      setAudioReady(webCodecsAvailable());
    }
    playerRef.current.resume();
    return playerRef.current;
  }, [talking]);

  /**
   * 打开麦克风并开始发送。silent=true 用于「进服时自动恢复上次开麦状态」：
   * 没有权限或浏览器不支持时静默失败、保持闭麦，不弹 Toast 打扰。
   */
  const startMic = useCallback(
    async (silent = false): Promise<boolean> => {
      if (micRef.current) return true;
      // 自动恢复开麦时，只有麦克风权限已经授权才真正去开，避免页面一加载就弹权限框
      if (silent) {
        try {
          const status = await navigator.permissions.query({ name: 'microphone' as unknown as PermissionName });
          if (status.state !== 'granted') return false;
        } catch {
          return false;
        }
      }
      if (!micCodecAvailable()) {
        if (!silent) Toast.error('这个浏览器不支持 WebCodecs 音频编码，换 Chrome 或 Edge');
        return false;
      }
      const s = stateRef.current;
      const codec = s ? (findChannel(s.tree, s.ownChannel)?.codec ?? 4) : 4;
      const mic = new MicSender();
      try {
        const g = gatewayRef.current;
        await mic.start(
          (opus) => g?.voice(codec, opus),
          (level) => setMicLevel(level),
        );
        micRef.current = mic;
        setMicOn(true);
        // 解除服务器端的输入静音，官方客户端里会恢复麦克风图标
        const s0 = stateRef.current;
        gatewayRef.current?.setMuted(false, s0?.outputMuted ?? false);
        if (s0) {
          const st = { ...s0, inputMuted: false };
          stateRef.current = st;
          setState(st);
          // 记下「现在是开麦」，下次短暂退出再进来照此恢复
          store.setMutePref(false, st.outputMuted);
        }
        // 记住的音量在开麦时补上，不然要再拖一次滑块才生效
        mic.setVolume(micGain);
        mic.setMode(micMode);
        mic.setSending(!pttMode);
        return true;
      } catch (e) {
        const msg = e instanceof Error ? e.message : String(e);
        if (!silent) Toast.error(msg.includes('Permission') ? '没有麦克风权限' : `麦克风打不开：${msg}`);
        mic.stop();
        return false;
      }
    },
    [micGain, micMode, pttMode],
  );

  /**
   * 收到别人的消息时弹桌面通知。
   * 规则：私聊一定提醒；频道消息在两种情况下提醒——页面不在前台，或者消息不是
   * 你当前所在频道（你看不到那条聊天）。只有「正盯着当前频道的聊天」才不打扰。
   * 自己的消息、没开开关都不提醒。
   */
  const notifyMessage = useCallback((line: ChatLine) => {
    if (!notifyOnRef.current) return;
    const s = stateRef.current;
    if (!s || line.from === s.ownId) return;
    const hidden = document.visibilityState === 'hidden' || !document.hasFocus();
    const otherChannel = line.mode === 2 && line.channel !== s.ownChannel;
    if (line.mode !== 1 && !hidden && !otherChannel) return;

    const channel = line.channel > 0 ? findChannel(s.tree, line.channel) : null;
    const src = avatarSources(
      { id: line.from, avatar: line.avatar, myTeamSpeakAvatar: line.myTeamSpeakAvatar },
      s.ownId,
      '',
      s.avatarToken ?? '',
    )[0];
    let icon: string | undefined;
    if (src) {
      try {
        icon = new URL(src, location.href).href;
      } catch {
        icon = undefined;
      }
    }
    const text = line.text.length > 120 ? `${line.text.slice(0, 120)}…` : line.text;
    showMessageNotice({
      title:
        line.mode === 1
          ? `${line.name} 发来私聊`
          : `${line.name}${channel ? ` · #${channel.display || channel.name}` : ''}`,
      body: text,
      icon,
      tag: line.mode === 1 ? `dm:${line.from}` : `ch:${line.channel}`,
    });
  }, []);

  /**
   * 一条 WebSocket 同时承载账号和语音连接。离开服务器时只断开 TeamSpeak，不关它——账号会话就活在这条连接
   * 里，关掉等于退出登录。刷新或关页才由浏览器收掉。
   */
  const openGateway = useCallback(
    async (useToken: string): Promise<Gateway> => {
      const existing = gatewayRef.current;
      if (existing) {
        try {
          await existing.open();
          return existing;
        } catch (e) {
          if (gatewayRef.current === existing) gatewayRef.current = null;
          throw e;
        }
      }
      const g = new Gateway(gatewayUrl(), useToken, {
        onIdentity: (v) => store.setIdentity(v),
        onState: (s) => {
          // 离开服务器后网关还会补推一帧 phase=idle 的快照。若照单全收，前端会停在一个空的服务器页，
          // 得再点一次才回登录页——所以 idle 一律当作「已离开」，直接回连接页（错误照常显示）。
          if (s.phase === 'idle') {
            if (s.error) setError(s.error);
            stateRef.current = null;
            setState(null);
            setConnecting(false);
            setPing((p) => (p.server == null ? p : { ...p, server: null }));
            return;
          }
          const prevPhase = stateRef.current?.phase;
          let st = s;
          // 进服/重连时：若 14 天内有「上次离开时的开关」，就按它恢复；否则默认闭麦、扬声器沿用服务器状态。
          if (s.phase === 'connected' && prevPhase !== 'connected') {
            const pref = store.mutePref();
            // 麦克风：本地采集没起来前一律先按闭麦，真正开麦由下面 startMic 成功后翻成不静音
            const wantInputMuted = micRef.current ? s.inputMuted : true;
            const wantOutputMuted = pref ? pref.outputMuted : s.outputMuted;
            st = { ...st, inputMuted: wantInputMuted, outputMuted: wantOutputMuted };
          }
          stateRef.current = st;
          setState(st);
          if (st.phase === 'connected') {
            setConnecting(false);
            if (st.address) store.addRecent(st.address);
            if (prevPhase !== 'connected') {
              // 扬声器静音先在本地播放器上落实，再把输入/输出静音都报给服务器（官方客户端据此显示图标）
              playerRef.current?.setMuted(st.outputMuted);
              gatewayRef.current?.setMuted(st.inputMuted, st.outputMuted);
              // 上次是开麦的话，自动恢复；失败就静默保持闭麦
              const pref = store.mutePref();
              if (pref && !pref.inputMuted && !micRef.current) void startMic(true);
            }
          } else {
            // 已经不在语音连接里了，服务器那半段延迟作废；网关那半段还活着
            setPing((p) => (p.server == null ? p : { ...p, server: null }));
          }
          if (s.error) setError(s.error);
        },
        onRoundTrip: (ms) => setPing((p) => (p.gateway === ms ? p : { ...p, gateway: ms })),
        onServerPing: (ms) => setPing((p) => (p.server === ms ? p : { ...p, server: ms })),
        onChat: (line) => {
          setChat((prev) => [...prev, line].slice(-MAX_CHAT));
          // 频道消息顺手落进本地库：以后进这个频道还能翻回来，哪怕当时不在这个频道
          if (line.mode === 2 && line.channel > 0) {
            const server = serverKey(stateRef.current?.address ?? '');
            if (server) {
              appendLine(server, line.channel, {
                time: line.time,
                from: line.from,
                name: line.name,
                text: line.text,
              });
            }
          }
          notifyMessage(line);
        },
        // 网关补回来的历史：只认当前频道，合并进历史那份（本地已经有的会去重）
        onHistory: (msg) => {
          const s = stateRef.current;
          if (!s || msg.channel !== s.ownChannel) return;
          const server = serverKey(s.address);
          const lines: ChatLine[] = msg.lines.map((l) => ({
            t: 'chat',
            channel: msg.channel,
            mode: 2,
            time: l.time,
            from: l.from,
            name: l.name,
            text: l.text,
          }));
          if (lines.length) setHistoryLines((prev) => mergeLines(prev, lines));
          moreRef.current.gateway = msg.more;
          setHistoryMore(moreRef.current.local || msg.more);
          if (server) for (const l of msg.lines) appendLine(server, msg.channel, l);
        },
        onFiles: setFiles,
        onAccount: (s) => {
          setAccount(s);
          // 记住的登录已经失效（过期或被别的设备撤销）：顺手删掉 cookie，别每次开页都白试一次。
          if (s.phase === 'guest' && s.error === 'MYTS_103') void forgetAccount();
        },
        onRemember: (resume) => void rememberAccount(resume),
        onBookmarks: setBookmarks,
        onContacts: setFriends,
        onCloud: setCloud,
        onCloudChat: (msg) => {
          setCloudChat(msg);
          setCloudNotice(msg.errorText ?? null);
        },
        onCloudMessage: (e) => {
          setCloudChat((prev) =>
            prev && prev.roomId === e.roomId
              ? { ...prev, messages: [...prev.messages, e.message] }
              : prev,
          );
        },
        onCloudSend: (s) => setCloudNotice(s.ok ? null : s.errorText ?? '发送失败'),
        onVoice: (codec, clientId, opus) => {
          // 空帧是「说完了」：立刻熄灯，不用等超时。非空帧交给播放器按实际音量判断
          if (opus.length === 0) talking.quiet(clientId);
          playerRef.current?.play(clientId, codec, opus);
        },
        onStream: (e) => streamRef.current?.onEvent(e),
        onClose: (code, reason) => {
          // 掉线/关页前记一笔开关，重连后恢复
          {
            const s = stateRef.current;
            if (s) store.setMutePref(s.inputMuted, s.outputMuted);
          }
          gatewayRef.current = null;
          stateRef.current = null;
          streamRef.current?.reset();
          setConnecting(false);
          talking.reset();
          setState(null);
          setChat([]);
          setFiles(null);
          setPing({ gateway: null, server: null });
          setAccount(guestAccount());
          setBookmarks(emptyBookmarks());
          setFriends(emptyContacts());
          setCloud(emptyCloud());
          setCloudChat(null);
          setCloudNotice(null);
          if (code === 1008) setError(reason || '网关拒绝了这次连接');
        },
      });
      gatewayRef.current = g;
      try {
        await g.open();
        return g;
      } catch (e) {
        if (gatewayRef.current === g) gatewayRef.current = null;
        throw e;
      }
    },
    [talking, notifyMessage, startMic],
  );

  // 页面一打开就把网关连上：浏览器若还带着「记住我」cookie，网关会在这条连接上恢复账号登录，
  // 不用等用户点连接。口令必需的部署里，没填口令就不试，免得白挨一次 1008 拒绝。
  const booted = useRef(false);
  useEffect(() => {
    if (!tokenChecked || booted.current) return;
    if (tokenNeeded && !store.token().trim()) return;
    booted.current = true;
    void openGateway(tokenNeeded ? store.token() : '').catch(() => {
      /* 自动恢复失败不该打扰用户：手动点连接时还会再试一次 */
    });
  }, [tokenChecked, tokenNeeded, openGateway]);

  const disconnect = useCallback(() => {
    // 断开前记一笔当前的麦克风/扬声器开关，短暂退出再进来能恢复
    {
      const s = stateRef.current;
      if (s) store.setMutePref(s.inputMuted, s.outputMuted);
    }
    talking.reset();
    streamRef.current?.reset();
    streamRef.current = null;
    setStreamState(initialStreamState);
    micRef.current?.stop();
    micRef.current = null;
    setMicOn(false);
    setMicLevel(0);
    setPttHeld(false);
    // 只发 leave；WebSocket 留着，账号和云书签不用重新登录
    gatewayRef.current?.leave();
    stateRef.current = null;
    setState(null);
    setChat([]);
    setFiles(null);
    setPing({ gateway: null, server: null });
    setConnecting(false);
  }, [talking]);

  const doConnect = useCallback(
    async (opts: ConnectOptions, useToken: string) => {
      setError(null);
      setConnecting(true);
      talking.reset();
      setPing({ gateway: null, server: null });
      // 记住的是「用户自己的昵称」：登录时连进来用的是账号名，退出登录后重连还要回到自定义昵称
      const remembered = opts.nickname === store.nickname() ? opts.nickname : store.nickname() || opts.nickname;
      lastAddress.current = { address: opts.host, nickname: remembered };
      setChat([]);
      try {
        ensurePlayer();
        const g = await openGateway(useToken);
        streamRef.current?.reset();
        streamRef.current = new StreamClient({
          send: (m) => g.stream(m),
          onChange: setStreamState,
          log: (m) => console.info('[stream]', m),
        });
        g.connect(opts);
      } catch (e) {
        setConnecting(false);
        setError(e instanceof Error ? e.message : String(e));
      }
    },
    [ensurePlayer, talking, openGateway],
  );

  const connect = useCallback(
    (opts: ConnectOptions) => {
      if (tokenNeeded) store.setToken(token);
      // 登录状态下昵称强制用账号名；只有真的用了用户自己的昵称才写回本地，
      // 免得账号名覆盖掉 huixiang.nickname，退出登录后自定义昵称就没了
      const nickname = effectiveNickname(account, opts.nickname);
      if (nickname === opts.nickname) store.setNickname(opts.nickname);
      void doConnect({ ...opts, nickname, identity: store.identity() }, tokenNeeded ? token : '');
    },
    [account, doConnect, token, tokenNeeded],
  );

  const reconnectSame = useCallback(() => {
    const last = lastAddress.current;
    if (!last) return;
    void doConnect(
      {
        host: last.address,
        port: 9987,
        nickname: effectiveNickname(account, last.nickname),
        password: '',
        identity: store.identity(),
      },
      tokenNeeded ? token : '',
    );
  }, [account, doConnect, token, tokenNeeded]);

  const accountLogin = useCallback(
    async (email: string, password: string, otp: string) => {
      if (tokenNeeded && !token.trim()) {
        Toast.error('先填网关访问口令');
        return;
      }
      setAccount({ ...guestAccount(), phase: 'busy' });
      try {
        const g = await openGateway(tokenNeeded ? token : '');
        g.accountLogin(email, password, otp);
      } catch (e) {
        setAccount(guestAccount());
        Toast.error(e instanceof Error ? e.message : String(e));
      }
    },
    [openGateway, token, tokenNeeded],
  );

  const accountLogout = useCallback(() => {
    // 显式退出：网关丢掉会话，浏览器删掉 cookie，下次打开就要重新输密码。
    void forgetAccount();
    const g = gatewayRef.current;
    if (g?.isOpen) g.accountLogout();
    else setAccount(guestAccount());
  }, []);

  const accountRefresh = useCallback(() => {
    gatewayRef.current?.accountRefresh();
  }, []);

  const changeAccountAvatar = useCallback(
    async (file: File) => {
      const token = account.fileToken;
      if (!token) {
        Toast.error('账号还没准备好，稍后再试');
        return;
      }
      const error = await uploadAccountAvatar(file, token);
      if (error) Toast.error(error);
      else Toast.success('头像已更新');
    },
    [account.fileToken],
  );

  const removeAccountAvatarNow = useCallback(async () => {
    const token = account.fileToken;
    if (!token) {
      Toast.error('账号还没准备好，稍后再试');
      return;
    }
    const error = await removeAccountAvatar(token);
    if (error) Toast.error(error);
    else Toast.success('账号头像已删除');
  }, [account.fileToken]);

  // 浏览器把页面放进往返缓存（bfcache，后退/前进时）会硬断开 WebSocket，
  // 控制台就是那句 Page entered Back-Forward Cache。恢复后这条连接已经是死的：
  // 界面还显示「已连接」，文字和语音却发不出去。这里在 pagehide 主动收尾，
  // 在 pageshow（persisted）恢复时自动重连一次。
  const hadSession = useRef(false);

  useEffect(() => {
    const onPageHide = () => {
      hadSession.current = !!stateRef.current;
      // 串流的 RTCPeerConnection 也要收掉，否则冻结期间还在推画面
      streamRef.current?.reset();
      micRef.current?.stop();
      micRef.current = null;
      gatewayRef.current?.close();
      gatewayRef.current = null;
    };
    const onPageShow = (e: PageTransitionEvent) => {
      if (!e.persisted || !hadSession.current) return;
      hadSession.current = false;
      setMicOn(false);
      setMicLevel(0);
      setPttHeld(false);
      if (lastAddress.current) {
        Toast.info('页面从缓存恢复，正在重新连接…');
        reconnectSame();
      } else {
        setState(null);
      }
    };
    window.addEventListener('pagehide', onPageHide);
    window.addEventListener('pageshow', onPageShow);
    return () => {
      window.removeEventListener('pagehide', onPageHide);
      window.removeEventListener('pageshow', onPageShow);
    };
  }, [reconnectSame]);

  const joinChannel = useCallback((cid: number, password = '') => {
    gatewayRef.current?.join(cid, password);
  }, []);

  const say = useCallback((text: string) => {
    const s = stateRef.current;
    if (!s || !text.trim()) return;
    gatewayRef.current?.say(text, s.ownChannel, 0);
  }, []);

  /** 服务器私聊：target 指定某个在线成员，官方客户端（TS5/TS6）收得到。 */
  const sayTo = useCallback((clientId: number, text: string) => {
    const s = stateRef.current;
    if (!s || !text.trim() || clientId <= 0) return;
    gatewayRef.current?.say(text, s.ownChannel, clientId);
  }, []);

  // 开关通知。打开时要走浏览器的权限申请（必须在点击里），拒绝/不支持都给出明确说法。
  const toggleNotify = useCallback(async () => {
    if (notifyOn) {
      store.setNotify(false);
      setNotifyOn(false);
      return;
    }
    const p = await requestNotifyPermission();
    if (p === 'granted') {
      store.setNotify(true);
      setNotifyOn(true);
    } else if (p === 'denied') {
      Toast.error('浏览器拒绝了通知权限，请在地址栏的站点设置里允许通知后再试');
    } else if (p === 'unsupported') {
      Toast.error('这个浏览器不支持通知（需要 HTTPS 或 localhost 打开）');
    } else {
      Toast.warning('没有拿到通知权限');
    }
  }, [notifyOn]);

  /**
   * 取一页频道历史：先本地库（快、离线也有），再让网关补一份（换设备、或者本地还没攒到的部分）。
   * before 传 0 表示最近一屏；往上翻时传当前最早一条的时间。
   */
  const loadHistory = useCallback(async (cid: number, before: number) => {
    const s = stateRef.current;
    if (!s || cid <= 0) return;
    const server = serverKey(s.address);
    if (!server) return;
    setHistoryBusy(true);
    try {
      const page = await loadPage(server, cid, before, HISTORY_PAGE);
      const local: ChatLine[] = page.lines.map((l) => ({
        t: 'chat',
        channel: cid,
        mode: 2,
        time: l.time,
        from: l.from,
        name: l.name,
        text: l.text,
      }));
      if (local.length) setHistoryLines((prev) => mergeLines(prev, local));
      moreRef.current.local = page.more;
      setHistoryMore(moreRef.current.local || moreRef.current.gateway);
      // 网关那份可能还有本地没有的；它到得晚一点，由 onHistory 合并
      gatewayRef.current?.history(cid, before, HISTORY_PAGE);
    } finally {
      setHistoryBusy(false);
    }
  }, []);

  // 进频道、重连或换服务器：上一个频道的历史作废，带出这个频道的最近一屏
  const connectedChannel = state?.phase === 'connected' ? state.ownChannel : 0;
  const connectedAddress = state?.phase === 'connected' ? state.address : '';
  useEffect(() => {
    setHistoryLines([]);
    setHistoryMore(false);
    moreRef.current = { local: false, gateway: false };
    if (connectedChannel > 0) void loadHistory(connectedChannel, 0);
  }, [connectedChannel, connectedAddress, loadHistory]);

  /** 滚到顶时往上翻一页。 */
  const loadMoreHistory = useCallback(() => {
    if (historyBusy || !historyMore) return;
    const s = stateRef.current;
    if (!s || s.ownChannel <= 0) return;
    const oldest = historyLines[0];
    void loadHistory(s.ownChannel, oldest ? oldest.time : 0);
  }, [historyBusy, historyMore, historyLines, loadHistory]);

  /** 主聊天面板只显示「当前频道」的频道消息；私聊（mode 1）不进这里，只在好友抽屉里按人显示。 */
  const visibleChat = useMemo(() => {
    const live = chat.filter(
      (l) => l.mode === 2 && (!(l.channel > 0) || l.channel === connectedChannel),
    );
    return mergeLines(historyLines, live);
  }, [chat, historyLines, connectedChannel]);

  /** 要一层目录的文件列表；答案从 onFiles 回来。 */
  const listFiles = useCallback((cid: number, path: string) => {
    gatewayRef.current?.listFiles(cid, path);
  }, []);

  const rename = useCallback((name: string) => {
    if (!name.trim()) return;
    gatewayRef.current?.rename(name.trim());
    store.setNickname(name.trim());
  }, []);

  /** 麦克风音量：改采集增益，开麦后立刻生效，并记住下次。 */
  const changeMicGain = useCallback((v: number) => {
    const g = clampGain(v);
    setMicGain(g);
    store.setMicGain(g);
    micRef.current?.setVolume(g);
  }, []);

  /** 个人音量：按 uid 记住，他换频道、重连回来还是这个音量。 */
  const changeMemberVolume = useCallback((member: Member, v: number) => {
    const g = clampGain(v);
    const key = memberKey(member);
    playerRef.current?.setVolume(member.id, g);
    setVolumes((prev) => {
      const next = { ...prev, [key]: g };
      store.setVolumes(next);
      return next;
    });
  }, []);

  const toggleOutput = useCallback(() => {
    const s = stateRef.current;
    if (!s) return;
    const muted = !s.outputMuted;
    gatewayRef.current?.setMuted(s.inputMuted, muted);
    playerRef.current?.setMuted(muted);
    const st = { ...s, outputMuted: muted };
    stateRef.current = st;
    setState(st);
    // 记住这次开关，14 天内再进来沿用
    store.setMutePref(st.inputMuted, muted);
  }, []);

  const toggleInput = useCallback(async () => {
    if (micOn) {
      micRef.current?.stop();
      micRef.current = null;
      setMicOn(false);
      setMicLevel(0);
      setPttHeld(false);
      // 告诉服务器「我静音了」，这样官方客户端（TS5/TS6）里也会显示麦克风静音图标
      const s = stateRef.current;
      gatewayRef.current?.setMuted(true, s?.outputMuted ?? false);
      if (s) {
        const st = { ...s, inputMuted: true };
        stateRef.current = st;
        setState(st);
        // 记住「现在是闭麦」，下次短暂退出再进来照此恢复
        store.setMutePref(true, st.outputMuted);
      }
      return;
    }
    await startMic(false);
  }, [micOn, startMic]);

  const connected = state?.phase === 'connected';

  // 自己这一侧：麦克风开着、这一刻真的在发（按住说话得按着）、电平够响。
  // 下行收不到自己的帧（服务器不回传），所以自己只能靠采集电平来亮。
  const ownTalking = micOn && (!pttMode || pttHeld) && micLevel > TALK_LEVEL;

  const shareScreen = useCallback(() => {
    void streamRef.current?.startScreen();
  }, []);

  const shareCamera = useCallback(() => {
    void streamRef.current?.startCamera();
  }, []);

  const stopShare = useCallback(() => streamRef.current?.stopShare(), []);

  const watchStream = useCallback((clientId: number) => streamRef.current?.watch(clientId), []);

  const stopWatch = useCallback(() => streamRef.current?.stopWatch(), []);

  // 好友云聊天的动作都通过网关连接发出去，用 ref 拿最新那条
  const cloudConnect = useCallback(() => gatewayRef.current?.cloudConnect(), []);
  const cloudDisconnect = useCallback(() => gatewayRef.current?.cloudDisconnect(), []);
  const cloudOpen = useCallback((friend: Friend) => gatewayRef.current?.cloudOpen(friend.id), []);
  const cloudSendTo = useCallback(
    (roomId: string, text: string) => gatewayRef.current?.cloudSend(roomId, text),
    [],
  );
  const cloudLoadHistory = useCallback((roomId: string) => gatewayRef.current?.cloudHistory(roomId), []);

  const content = useMemo(() => {
    if (!state) {
      return (
        <ConnectPage
          tokenNeeded={tokenNeeded}
          token={token}
          onTokenChange={setToken}
          connecting={connecting}
          error={error}
          account={account}
          bookmarks={bookmarks}
          onConnect={connect}
          onDismissError={() => setError(null)}
          onAccountLogin={accountLogin}
          onAccountLogout={accountLogout}
          onAccountRefresh={accountRefresh}
        />
      );
    }
    return (
      <ServerPage
        state={state}
        chat={visibleChat}
        allChat={chat}
        chatMore={historyMore}
        chatBusy={historyBusy}
        onLoadMoreChat={loadMoreHistory}
        files={files}
        fileToken={state.fileToken ?? ''}
        avatarToken={state.avatarToken ?? ''}
        account={account}
        friends={friends}
        cloud={cloud}
        cloudChat={cloudChat}
        cloudNotice={cloudNotice}
        onCloudConnect={cloudConnect}
        onCloudDisconnect={cloudDisconnect}
        onCloudOpen={cloudOpen}
        onCloudSend={cloudSendTo}
        onCloudHistory={cloudLoadHistory}
        onDismissCloudNotice={() => setCloudNotice(null)}
        onAccountLogout={accountLogout}
        onAccountRefresh={accountRefresh}
        onAvatarChange={changeAccountAvatar}
        onAvatarRemove={removeAccountAvatarNow}
        pingGateway={ping.gateway}
        pingServer={ping.server}
        micOn={micOn}
        micLevel={micLevel}
        micGain={micGain}
        volumes={volumes}
        talkingIds={voiceTalking}
        ownTalking={ownTalking}
        micMode={micMode}
        pttHeld={pttHeld}
        audioReady={audioReady}
        stream={streamState}
        notifyOn={notifyOn}
        notifySupported={notifySupported()}
        onToggleNotify={toggleNotify}
        onMicMode={setMicMode}
        onPttHold={setPttHeld}
        onJoinChannel={joinChannel}
        onSay={say}
        onPrivately={sayTo}
        onListFiles={listFiles}
        onRename={rename}
        onMicGain={changeMicGain}
        onMemberVolume={changeMemberVolume}
        onToggleInput={toggleInput}
        onToggleOutput={toggleOutput}
        onDisconnect={disconnect}
        onReconnect={reconnectSame}
        onShareScreen={shareScreen}
        onShareCamera={shareCamera}
        onStopShare={stopShare}
        onWatch={watchStream}
        onStopWatch={stopWatch}
      />
    );
  }, [
    state,
    visibleChat,
    historyMore,
    historyBusy,
    files,
    account,
    bookmarks,
    friends,
    cloud,
    cloudChat,
    cloudNotice,
    ping,
    tokenNeeded,
    token,
    connecting,
    error,
    micOn,
    micLevel,
    micGain,
    volumes,
    voiceTalking,
    ownTalking,
    micMode,
    pttHeld,
    audioReady,
    streamState,
    notifyOn,
    toggleNotify,
    connect,
    joinChannel,
    loadMoreHistory,
    say,
    listFiles,
    rename,
    changeMicGain,
    changeMemberVolume,
    toggleInput,
    toggleOutput,
    disconnect,
    reconnectSame,
    shareScreen,
    shareCamera,
    stopShare,
    watchStream,
    stopWatch,
    cloudConnect,
    cloudDisconnect,
    cloudOpen,
    cloudSendTo,
    cloudLoadHistory,
    accountLogin,
    accountLogout,
    accountRefresh,
    changeAccountAvatar,
    removeAccountAvatarNow,
  ]);

  return <div className={connected ? 'app app-connected' : 'app'}>{content}</div>;
}
