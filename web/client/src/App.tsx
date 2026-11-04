import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Toast } from '@douyinfe/semi-ui';
import { Gateway, tokenRequired } from './gateway';
import {
  eachMember,
  emptyBookmarks,
  emptyContacts,
  effectiveNickname,
  findChannel,
  guestAccount,
  memberKey,
  store,
  type AccountState,
  type BookmarksMessage,
  type ChatLine,
  type ConnectOptions,
  type ContactsMessage,
  type FilesMessage,
  type Member,
  type State,
} from './protocol';
import { removeAccountAvatar, uploadAccountAvatar } from './account';
import { appendLine, loadPage, mergeLines, serverKey } from './chatHistory';
import { clampGain, DEFAULT_GAIN } from './audio/gain';
import { TalkingTracker } from './talking';
import { StreamClient, initialStreamState, type StreamState } from './stream';
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

  // 按住说话：默认关（常开麦），打开后按才出声
  const [pttMode, setPttMode] = useState(false);
  const [pttHeld, setPttHeld] = useState(false);

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
    void tokenRequired().then(setTokenNeeded);
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

  // 麦克风开关和按住说话共同决定「这一刻到底发不发」
  useEffect(() => {
    if (!micOn) return;
    micRef.current?.setSending(pttMode ? pttHeld : true);
  }, [pttMode, pttHeld, micOn]);

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
          stateRef.current = s;
          setState(s);
          if (s.phase === 'connected') {
            setConnecting(false);
            if (s.address) store.addRecent(s.address);
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
        onAccount: setAccount,
        onBookmarks: setBookmarks,
        onContacts: setFriends,
        onVoice: (codec, clientId, opus) => {
          // 空帧是「说完了」：立刻熄灯，不用等超时。非空帧交给播放器按实际音量判断
          if (opus.length === 0) talking.quiet(clientId);
          playerRef.current?.play(clientId, codec, opus);
        },
        onStream: (e) => streamRef.current?.onEvent(e),
        onClose: (code, reason) => {
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
    [talking],
  );

  const disconnect = useCallback(() => {
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

  /** 屏幕上要显示的消息：当前频道的（历史 + 实时），私聊照旧混排。 */
  const visibleChat = useMemo(() => {
    // 没有频道 id 的行（解析不出来、或服务器只给了点名不认频道）一律留着，别悄悄吞消息
    const live = chat.filter(
      (l) => l.mode !== 2 || !(l.channel > 0) || l.channel === connectedChannel,
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
    setState({ ...s, outputMuted: muted });
  }, []);

  const toggleInput = useCallback(async () => {
    if (micOn) {
      micRef.current?.stop();
      micRef.current = null;
      setMicOn(false);
      setMicLevel(0);
      setPttHeld(false);
      return;
    }
    if (!micCodecAvailable()) {
      Toast.error('这个浏览器不支持 WebCodecs 音频编码，换 Chrome 或 Edge');
      return;
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
      // 记住的音量在开麦时补上，不然要再拖一次滑块才生效
      mic.setVolume(micGain);
      // 按住说话模式下，开麦不等于立刻出声
      mic.setSending(!pttMode);
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      Toast.error(msg.includes('Permission') ? '没有麦克风权限' : `麦克风打不开：${msg}`);
      mic.stop();
    }
  }, [micOn, pttMode, micGain]);

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
          friends={friends}
          onConnect={connect}
          onDismissError={() => setError(null)}
          onAccountLogin={accountLogin}
          onAccountLogout={accountLogout}
          onAccountRefresh={accountRefresh}
          onAvatarChange={changeAccountAvatar}
          onAvatarRemove={removeAccountAvatarNow}
        />
      );
    }
    return (
      <ServerPage
        state={state}
        chat={visibleChat}
        chatMore={historyMore}
        chatBusy={historyBusy}
        onLoadMoreChat={loadMoreHistory}
        files={files}
        fileToken={state.fileToken ?? ''}
        avatarToken={state.avatarToken ?? ''}
        account={account}
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
        pttMode={pttMode}
        pttHeld={pttHeld}
        audioReady={audioReady}
        stream={streamState}
        onTogglePttMode={() => setPttMode((v) => !v)}
        onPttHold={setPttHeld}
        onJoinChannel={joinChannel}
        onSay={say}
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
    pttMode,
    pttHeld,
    audioReady,
    streamState,
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
    accountLogin,
    accountLogout,
    accountRefresh,
    changeAccountAvatar,
    removeAccountAvatarNow,
  ]);

  return <div className={connected ? 'app app-connected' : 'app'}>{content}</div>;
}
