import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Toast } from '@douyinfe/semi-ui';
import { Gateway, tokenRequired } from './gateway';
import {
  eachMember,
  emptyBookmarks,
  emptyContacts,
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
import { clampGain, DEFAULT_GAIN } from './audio/gain';
import { TalkingTracker } from './talking';
import { StreamClient, initialStreamState, type StreamState } from './stream';
import { VoicePlayer, webCodecsAvailable } from './audio/player';
import { MicSender, micCodecAvailable } from './audio/mic';
import ConnectPage from './components/ConnectPage';
import ServerPage from './components/ServerPage';

const MAX_CHAT = 300;

function gatewayUrl(): string {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${proto}//${location.host}/ws`;
}

export default function App() {
  const [tokenNeeded, setTokenNeeded] = useState(false);
  const [token, setToken] = useState(() => store.token());
  const [state, setState] = useState<State | null>(null);
  const [chat, setChat] = useState<ChatLine[]>([]);
  // 文件区最近一次的目录列表。只留一份：面板关着的时候没人看，多留是浪费
  const [files, setFiles] = useState<FilesMessage | null>(null);
  // 账号独立于语音连接：离开服务器后这条 WebSocket 还留着，账号、书签和好友继续可用
  const [account, setAccount] = useState<AccountState>(() => guestAccount());
  const [bookmarks, setBookmarks] = useState<BookmarksMessage>(() => emptyBookmarks());
  const [friends, setFriends] = useState<ContactsMessage>(() => emptyContacts());
  const [connecting, setConnecting] = useState(false);
  const [error, setError] = useState<string | null>(null);
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
      playerRef.current = new VoicePlayer();
      setAudioReady(webCodecsAvailable());
    }
    playerRef.current.resume();
    return playerRef.current;
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
          stateRef.current = s;
          setState(s);
          if (s.phase === 'connected') {
            setConnecting(false);
            if (s.address) store.addRecent(s.address);
          }
          if (s.error) setError(s.error);
        },
        onChat: (line) => setChat((prev) => [...prev, line].slice(-MAX_CHAT)),
        onFiles: setFiles,
        onAccount: setAccount,
        onBookmarks: setBookmarks,
        onContacts: setFriends,
        onVoice: (codec, clientId, opus) => {
          // 空帧是「说完了」：立刻熄灯，不用等超时
          if (opus.length === 0) talking.quiet(clientId);
          else talking.heard(clientId);
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
    setConnecting(false);
  }, [talking]);

  const doConnect = useCallback(
    async (opts: ConnectOptions, useToken: string) => {
      setError(null);
      setConnecting(true);
      talking.reset();
      lastAddress.current = { address: opts.host, nickname: opts.nickname };
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
      store.setNickname(opts.nickname);
      void doConnect({ ...opts, identity: store.identity() }, tokenNeeded ? token : '');
    },
    [doConnect, token, tokenNeeded],
  );

  const reconnectSame = useCallback(() => {
    const last = lastAddress.current;
    if (!last) return;
    void doConnect(
      { host: last.address, port: 9987, nickname: last.nickname, password: '', identity: store.identity() },
      tokenNeeded ? token : '',
    );
  }, [doConnect, token, tokenNeeded]);

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
  const ownTalking = micOn && (!pttMode || pttHeld) && micLevel > 0.045;

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
        chat={chat}
        files={files}
        fileToken={state.fileToken ?? ''}
        avatarToken={state.avatarToken ?? ''}
        account={account}
        friends={friends}
        onAccountLogout={accountLogout}
        onAccountRefresh={accountRefresh}
        onAvatarChange={changeAccountAvatar}
        onAvatarRemove={removeAccountAvatarNow}
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
    chat,
    files,
    account,
    bookmarks,
    friends,
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
