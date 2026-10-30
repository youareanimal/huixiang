import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Toast } from '@douyinfe/semi-ui';
import { Gateway, tokenRequired } from './gateway';
import {
  eachMember,
  findChannel,
  memberKey,
  store,
  type ChatLine,
  type ConnectOptions,
  type Member,
  type State,
} from './protocol';
import { clampGain, DEFAULT_GAIN } from './audio/gain';
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

  const disconnect = useCallback(() => {
    streamRef.current?.reset();
    streamRef.current = null;
    setStreamState(initialStreamState);
    micRef.current?.stop();
    micRef.current = null;
    setMicOn(false);
    setMicLevel(0);
    setPttHeld(false);
    gatewayRef.current?.leave();
    gatewayRef.current?.close();
    gatewayRef.current = null;
    setState(null);
    setChat([]);
    setConnecting(false);
  }, []);

  const doConnect = useCallback(
    async (opts: ConnectOptions, useToken: string) => {
      setError(null);
      setConnecting(true);
      lastAddress.current = { address: opts.host, nickname: opts.nickname };
      setChat([]);
      try {
        const player = ensurePlayer();
        const g = new Gateway(gatewayUrl(), useToken, {
          onIdentity: (v) => store.setIdentity(v),
          onState: (s) => {
            stateRef.current = s;
            setState(s);
            if (s.phase === 'connected') {
              setConnecting(false);
              store.addRecent(s.address || opts.host);
            }
            if (s.error) setError(s.error);
          },
          onChat: (line) => setChat((prev) => [...prev, line].slice(-MAX_CHAT)),
          onVoice: (codec, clientId, opus) => player.play(clientId, codec, opus),
          onStream: (e) => streamRef.current?.onEvent(e),
          onClose: (code, reason) => {
            setConnecting(false);
            if (code === 1008) {
              setError(reason || '网关拒绝了这次连接');
              setState(null);
            }
          },
        });
        gatewayRef.current = g;
        streamRef.current?.reset();
        streamRef.current = new StreamClient({
          send: (m) => g.stream(m),
          onChange: setStreamState,
          log: (m) => console.info('[stream]', m),
        });
        await g.open();
        g.connect(opts);
      } catch (e) {
        setConnecting(false);
        setError(e instanceof Error ? e.message : String(e));
        gatewayRef.current?.close();
        gatewayRef.current = null;
      }
    },
    [ensurePlayer],
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
          onConnect={connect}
          onDismissError={() => setError(null)}
        />
      );
    }
    return (
      <ServerPage
        state={state}
        chat={chat}
        micOn={micOn}
        micLevel={micLevel}
        micGain={micGain}
        volumes={volumes}
        pttMode={pttMode}
        pttHeld={pttHeld}
        audioReady={audioReady}
        stream={streamState}
        onTogglePttMode={() => setPttMode((v) => !v)}
        onPttHold={setPttHeld}
        onJoinChannel={joinChannel}
        onSay={say}
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
    tokenNeeded,
    token,
    connecting,
    error,
    micOn,
    micLevel,
    micGain,
    volumes,
    pttMode,
    pttHeld,
    audioReady,
    streamState,
    connect,
    joinChannel,
    say,
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
  ]);

  return <div className={connected ? 'app app-connected' : 'app'}>{content}</div>;
}
