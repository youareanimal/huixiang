import { useEffect, useMemo, useRef, useState } from 'react';
import { Input, Modal, Spin } from '@douyinfe/semi-ui';
import { IconClose, IconEdit, IconFolder, IconMenu, IconUserGroup } from '@douyinfe/semi-icons';
import type { ChannelNode, ChatLine, FilesMessage, Member, State } from '../protocol';
import { findChannel, findMember } from '../protocol';
import type { StreamState } from '../stream';
import { avatarColor, initial } from '../ui';
import ChannelTree from './ChannelTree';
import ChatPanel from './ChatPanel';
import ControlBar from './ControlBar';
import FilesPanel from './FilesPanel';
import ServerInfoPanel from './ServerInfo';
import Stage from './Stage';
import StreamPanel from './StreamPanel';

interface Props {
  state: State;
  chat: ChatLine[];
  /** 文件区最近一次的目录列表；面板关着时也可能有旧值。 */
  files: FilesMessage | null;
  /** 文件传输的会话令牌，HTTP 端点认它。 */
  fileToken: string;
  micOn: boolean;
  micLevel: number;
  micGain: number;
  /** 按 uid 记的成员音量。 */
  volumes: Record<string, number>;
  /** 从语音帧判出来的说话人（clientId）。 */
  talkingIds: ReadonlySet<number>;
  /** 自己这一刻是否在说话（麦克风电平说了算）。 */
  ownTalking: boolean;
  pttMode: boolean;
  pttHeld: boolean;
  audioReady: boolean;
  stream: StreamState;
  onTogglePttMode: () => void;
  onPttHold: (held: boolean) => void;
  onJoinChannel: (cid: number, password?: string) => void;
  onSay: (text: string) => void;
  /** 要一层目录的文件列表。 */
  onListFiles: (cid: number, path: string) => void;
  onRename: (name: string) => void;
  onMicGain: (v: number) => void;
  onMemberVolume: (member: Member, v: number) => void;
  onToggleInput: () => void;
  onToggleOutput: () => void;
  onDisconnect: () => void;
  onReconnect: () => void;
  onShareScreen: () => void;
  onShareCamera: () => void;
  onStopShare: () => void;
  onWatch: (clientId: number) => void;
  onStopWatch: () => void;
}

/** 打字时不能把空格当按住说话，否则聊不了天。 */
function typingInField(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null;
  if (!el) return false;
  const tag = el.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || el.isContentEditable;
}

export default function ServerPage({
  state,
  chat,
  files,
  fileToken,
  micOn,
  micLevel,
  micGain,
  volumes,
  talkingIds,
  ownTalking,
  pttMode,
  pttHeld,
  audioReady,
  stream,
  onTogglePttMode,
  onPttHold,
  onJoinChannel,
  onSay,
  onListFiles,
  onRename,
  onMicGain,
  onMemberVolume,
  onToggleInput,
  onToggleOutput,
  onDisconnect,
  onReconnect,
  onShareScreen,
  onShareCamera,
  onStopShare,
  onWatch,
  onStopWatch,
}: Props) {
  const [renaming, setRenaming] = useState(false);
  const [newName, setNewName] = useState('');
  const [passwordFor, setPasswordFor] = useState<ChannelNode | null>(null);
  const [password, setPassword] = useState('');
  const [joiningChannel, setJoiningChannel] = useState<number | null>(null);
  const [chatCollapsed, setChatCollapsed] = useState(false);
  const [railOpen, setRailOpen] = useState(false);
  const [filesOpen, setFilesOpen] = useState(false);
  const [errorDismissed, setErrorDismissed] = useState(false);
  const joinTimer = useRef<number | null>(null);

  const current = useMemo(
    () => (state.ownChannel ? findChannel(state.tree, state.ownChannel) : null),
    [state.tree, state.ownChannel],
  );

  // 进了频道就撤掉行内 loading；卡住也别一直转，3 秒兜底
  useEffect(() => {
    setJoiningChannel(null);
    if (joinTimer.current) {
      window.clearTimeout(joinTimer.current);
      joinTimer.current = null;
    }
  }, [state.ownChannel]);

  useEffect(() => {
    setErrorDismissed(false);
  }, [state.error]);

  useEffect(() => {
    return () => {
      if (joinTimer.current) window.clearTimeout(joinTimer.current);
    };
  }, []);

  // 按住说话：空格键也要能用，但输入框里要放过
  useEffect(() => {
    if (!pttMode || !micOn) return;
    const down = (e: KeyboardEvent) => {
      if (e.code !== 'Space' || typingInField(e.target) || e.repeat) return;
      e.preventDefault();
      onPttHold(true);
    };
    const up = (e: KeyboardEvent) => {
      if (e.code !== 'Space' || typingInField(e.target)) return;
      onPttHold(false);
    };
    window.addEventListener('keydown', down);
    window.addEventListener('keyup', up);
    return () => {
      window.removeEventListener('keydown', down);
      window.removeEventListener('keyup', up);
      onPttHold(false);
    };
  }, [pttMode, micOn, onPttHold]);

  const join = (channel: ChannelNode) => {
    if (channel.id === state.ownChannel) return;
    setRailOpen(false);
    if (channel.password) {
      setPasswordFor(channel);
      setPassword('');
      return;
    }
    setJoiningChannel(channel.id);
    if (joinTimer.current) window.clearTimeout(joinTimer.current);
    joinTimer.current = window.setTimeout(() => setJoiningChannel(null), 3000);
    onJoinChannel(channel.id);
  };

  const channelTitle = current?.display || current?.name || '频道';
  const people = current?.clients ?? [];
  const connecting = state.phase === 'connecting';
  const channelTag = current?.password ? '有密码' : '公共';
  const watchingName = stream.watching
    ? findMember(state.tree, stream.watching)?.nickname || `#${stream.watching}`
    : '';

  return (
    <div className="server-page">
      <header className="server-head">
        <div className="head-left">
          <button
            type="button"
            className="icon-btn rail-toggle"
            onClick={() => setRailOpen((v) => !v)}
            aria-label="频道列表"
            aria-expanded={railOpen}
          >
            <IconMenu />
          </button>
          <img className="brand-mark-sm" src="/icons/icon-96.png" alt="" aria-hidden="true" />
          <span className="server-name" title={state.serverName || state.address}>
            {state.serverName || '正在连接…'}
          </span>
          <span className="head-addr">
            <span className="dot" />
            {state.address}
          </span>
        </div>

        <div className="head-right">
          <span className="pill" title="服务器总在线人数">
            <IconUserGroup size="small" />
            全服 {state.clientCount} 人
          </span>
          <button
            type="button"
            className="user-chip"
            onClick={() => {
              setNewName(state.nickname);
              setRenaming(true);
            }}
            title="改昵称"
          >
            <span
              className="msg-avatar"
              style={{ background: avatarColor('me'), width: 24, height: 24 }}
              aria-hidden="true"
            >
              {initial(state.nickname)}
            </span>
            <span className="user-chip-name">{state.nickname}</span>
            <IconEdit size="small" />
          </button>
        </div>
      </header>

      {(state.error && !errorDismissed) || !audioReady ? (
        <div className="banner-stack">
          {state.error && !errorDismissed && (
            <div className="banner-line" role="alert">
              <div
                style={{
                  flex: '1 1 260px',
                  minWidth: 0,
                  padding: '8px 12px',
                  borderRadius: 10,
                  background: 'var(--danger-soft)',
                  color: '#a3242b',
                  fontSize: 12.5,
                  lineHeight: 1.5,
                }}
              >
                {state.error}
              </div>
              <button type="button" className="ghost-btn" onClick={onReconnect}>
                重连
              </button>
              <button
                type="button"
                className="icon-btn"
                onClick={() => setErrorDismissed(true)}
                aria-label="关闭提示"
              >
                <IconClose size="small" />
              </button>
            </div>
          )}
          {!audioReady && (
            <div
              style={{
                padding: '8px 12px',
                borderRadius: 10,
                background: 'var(--warn-soft)',
                color: '#8a5300',
                fontSize: 12,
                lineHeight: 1.5,
              }}
            >
              这个浏览器不支持 WebCodecs 音频，听不到也说不了话。请用 Chrome 94+、Edge 94+、Safari 16.4+
              或 Firefox 130+。
            </div>
          )}
        </div>
      ) : null}

      {connecting ? (
        <div className="server-loading">
          <Spin size="large" />
          <span>正在和服务器握手…</span>
        </div>
      ) : (
        <div className="server-body">
          <aside className={`channel-rail${railOpen ? ' is-open' : ''}`} aria-label="频道">
            <div className="rail-head">
              <span className="rail-title">频道</span>
              <span className="rail-count">{countChannels(state.tree)}</span>
            </div>
            <ChannelTree
              tree={state.tree}
              ownChannel={state.ownChannel}
              joiningChannel={joiningChannel}
              onJoin={join}
            />
            <div className="rail-foot">
              <span className="dot" />
              <span>已连接</span>
              <span style={{ color: 'var(--text-4)' }}>· Opus 48k</span>
            </div>
          </aside>

          {railOpen && (
            <div className="rail-scrim" role="presentation" onClick={() => setRailOpen(false)} />
          )}

          <main className="server-content">
            <div className="channel-head">
              <div className="channel-head-main">
                <div className="channel-title-row">
                  <h1 className="channel-title" title={channelTitle}>
                    {channelTitle}
                  </h1>
                  <span className="chan-tag">{channelTag}</span>
                </div>
                <span className="channel-topic">{current?.topic || `${people.length} 人在这个频道`}</span>
              </div>
              <div className="channel-head-right">
                <button
                  type="button"
                  className="pill pill-action"
                  onClick={() => setFilesOpen(true)}
                  disabled={!state.ownChannel}
                  title="频道文件：文件存在服务器上，同频道的人都能取"
                >
                  <IconFolder size="small" />
                  文件
                </button>
                <span className="pill">
                  <IconUserGroup size="small" />
                  {people.length} 人
                </span>
              </div>
            </div>

            {stream.watching > 0 || stream.sharing !== null ? (
              <StreamPanel
                stream={stream}
                streamerName={watchingName}
                ownName={state.nickname}
                onStopShare={onStopShare}
                onStopWatch={onStopWatch}
              />
            ) : (
              <Stage
                members={people}
                ownId={state.ownId}
                channelName={channelTitle}
                volumes={volumes}
                talkingIds={talkingIds}
                ownTalking={ownTalking}
                watchingId={stream.watching}
                onWatch={onWatch}
                onVolume={onMemberVolume}
              />
            )}

            <ChatPanel
              lines={chat}
              ownId={state.ownId}
              title={channelTitle}
              hint="频道消息，服务器上所有人都能看到"
              placeholder={`发消息到 ${channelTitle}`}
              collapsed={chatCollapsed}
              onToggleCollapsed={() => setChatCollapsed((v) => !v)}
              onSend={onSay}
            />
          </main>

          <ServerInfoPanel
            info={state.server ?? null}
            welcome={state.welcome}
            address={state.address}
            clientCount={state.clientCount}
            channelCount={countChannels(state.tree)}
          />
        </div>
      )}

      <ControlBar
        micOn={micOn}
        micLevel={micLevel}
        micGain={micGain}
        outputMuted={state.outputMuted}
        pttMode={pttMode}
        pttHeld={pttHeld}
        channelName={channelTitle}
        clientCount={people.length}
        sharing={stream.sharing}
        shareViewers={stream.viewers}
        onShareScreen={onShareScreen}
        onShareCamera={onShareCamera}
        onStopShare={onStopShare}
        onMicGain={onMicGain}
        onToggleInput={onToggleInput}
        onToggleOutput={onToggleOutput}
        onTogglePttMode={onTogglePttMode}
        onPttHold={onPttHold}
        onDisconnect={onDisconnect}
      />

      <FilesPanel
        open={filesOpen}
        cid={state.ownChannel}
        channelName={channelTitle}
        token={fileToken}
        listing={files}
        onList={onListFiles}
        onClose={() => setFilesOpen(false)}
      />

      <Modal
        title="改昵称"
        visible={renaming}
        onCancel={() => setRenaming(false)}
        onOk={() => {
          onRename(newName);
          setRenaming(false);
        }}
        okText="改"
        cancelText="算了"
      >
        <Input
          value={newName}
          onChange={setNewName}
          maxLength={30}
          onEnterPress={() => {
            onRename(newName);
            setRenaming(false);
          }}
        />
      </Modal>

      <Modal
        title={`「${passwordFor?.display || passwordFor?.name || ''}」有密码`}
        visible={!!passwordFor}
        onCancel={() => setPasswordFor(null)}
        onOk={() => {
          if (passwordFor) {
            setJoiningChannel(passwordFor.id);
            onJoinChannel(passwordFor.id, password);
          }
          setPasswordFor(null);
        }}
        okText="进入"
        cancelText="取消"
      >
        <Input
          mode="password"
          value={password}
          onChange={setPassword}
          placeholder="频道密码"
          onEnterPress={() => {
            if (passwordFor) {
              setJoiningChannel(passwordFor.id);
              onJoinChannel(passwordFor.id, password);
            }
            setPasswordFor(null);
          }}
        />
      </Modal>
    </div>
  );
}

function countChannels(tree: ChannelNode[]): number {
  let n = 0;
  for (const c of tree) {
    if (!c.spacer) n += 1;
    n += countChannels(c.children);
  }
  return n;
}
