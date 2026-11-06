import { useEffect, useMemo, useRef, useState } from 'react';
import { Input, InputNumber, Modal, Select, Spin, Switch } from '@douyinfe/semi-ui';
import { IconClose, IconFolder, IconMenu, IconPlus, IconSetting, IconUserGroup } from '@douyinfe/semi-icons';
import type {
  AccountState,
  ChannelCreate,
  ChannelKind,
  ChannelNode,
  ChannelPatch,
  ChatLine,
  CloudChat,
  CloudState,
  ContactsMessage,
  FilesMessage,
  Friend,
  Member,
  MicMode,
  PersonDetails,
  State,
} from '../protocol';
import { eachMember, findChannel, findMember } from '../protocol';
import type { StreamState } from '../stream';
import { avatarSources } from '../ui';
import ChannelTree from './ChannelTree';
import ChatPanel from './ChatPanel';
import ControlBar from './ControlBar';
import FilesPanel from './FilesPanel';
import FriendsPanel from './FriendsPanel';
import ServerInfoPanel from './ServerInfo';
import SettingsPanel from './SettingsPanel';
import Stage from './Stage';
import StreamPanel from './StreamPanel';
import UserAvatar from './UserAvatar';

interface Props {
  state: State;
  chat: ChatLine[];
  /** 完整的实时聊天行（含私聊），好友抽屉按人筛私聊用；主面板则只显示 chat。 */
  allChat: ChatLine[];
  /** 聊天记录里还有更早的可以往上翻。 */
  chatMore: boolean;
  /** 正在取更早的一页。 */
  chatBusy: boolean;
  onLoadMoreChat: () => void;
  /** 文件区最近一次的目录列表；面板关着时也可能有旧值。 */
  files: FilesMessage | null;
  /** 文件传输的会话令牌，HTTP 端点认它。 */
  fileToken: string;
  /** 成员头像地址里的会话令牌。 */
  avatarToken: string;
  /** myTeamSpeak 账号：头像和账号操作。 */
  account: AccountState;
  /** 好友列表（myTeamSpeak 联系人），好友云聊天的入口。 */
  friends: ContactsMessage;
  /** 云聊天连接状态。 */
  cloud: CloudState;
  /** 当前打开的好友会话；没打开是 null。 */
  cloudChat: CloudChat | null;
  /** 云聊天的一次性提示（发送失败等），可为空。 */
  cloudNotice: string | null;
  onCloudConnect: () => void;
  onCloudDisconnect: () => void;
  onCloudOpen: (friend: Friend) => void;
  onCloudSend: (roomId: string, text: string) => void;
  onCloudHistory: (roomId: string) => void;
  onDismissCloudNotice: () => void;
  /** 浏览器 ↔ 网关的往返（毫秒），还没测到是 null。 */
  pingGateway: number | null;
  /** 网关 ↔ TeamSpeak 服务器的往返（毫秒），还没测到是 null。 */
  pingServer: number | null;
  micOn: boolean;
  micLevel: number;
  micGain: number;
  /** 按 uid 记的成员音量。 */
  volumes: Record<string, number>;
  /** 从语音帧判出来的说话人（clientId）。 */
  talkingIds: ReadonlySet<number>;
  /** 自己这一刻是否在说话（麦克风电平说了算）。 */
  ownTalking: boolean;
  micMode: MicMode;
  pttHeld: boolean;
  audioReady: boolean;
  stream: StreamState;
  /** 桌面消息通知开关。 */
  notifyOn: boolean;
  /** 这个浏览器/环境是否支持通知（http 非 localhost 就不支持）。 */
  notifySupported: boolean;
  onToggleNotify: () => void;
  /** 自己当前是否暂离。 */
  away: boolean;
  /** 自己的离开留言。 */
  awayMessage: string;
  onToggleAway: (away: boolean) => void;
  onSetAwayMessage: (message: string) => void;
  onMicMode: (mode: MicMode) => void;
  onPttHold: (held: boolean) => void;
  onJoinChannel: (cid: number, password?: string) => void;
  onSay: (text: string) => void;
  /** 给服务器上某个人发私聊（官方客户端认的服务器文本消息）。 */
  onPrivately: (clientId: number, text: string) => void;
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
  /** 频道管理：建 / 改 / 删 */
  onCreateChannel: (patch: ChannelCreate) => void;
  onEditChannel: (cid: number, patch: ChannelPatch) => void;
  onDeleteChannel: (cid: number) => void;
  /** 成员管理：踢 / 封（非自己） */
  onKickMember: (member: Member, reason: string) => void;
  onBanMember: (member: Member, seconds: number, reason: string) => void;
  /** 用户资料卡：按 clientId 缓存的补充资料（服务器公开字段），以及向服务器补问一次的请求。 */
  personDetails: Record<number, PersonDetails>;
  onPerson: (clientId: number) => void;
  onAccountLogout: () => void;
  onAccountRefresh: () => void;
  onAvatarChange: (file: File) => void;
  onAvatarRemove: () => void;
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
  allChat,
  chatMore,
  chatBusy,
  onLoadMoreChat,
  files,
  fileToken,
  avatarToken,
  account,
  friends,
  cloud,
  cloudChat,
  cloudNotice,
  onCloudConnect,
  onCloudDisconnect,
  onCloudOpen,
  onCloudSend,
  onCloudHistory,
  onDismissCloudNotice,
  pingGateway,
  pingServer,
  micOn,
  micLevel,
  micGain,
  volumes,
  talkingIds,
  ownTalking,
  micMode,
  pttHeld,
  audioReady,
  stream,
  notifyOn,
  notifySupported,
  onToggleNotify,
  away,
  awayMessage,
  onToggleAway,
  onSetAwayMessage,
  onMicMode,
  onPttHold,
  onJoinChannel,
  onSay,
  onPrivately,
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
  onCreateChannel,
  onEditChannel,
  onDeleteChannel,
  onKickMember,
  onBanMember,
  personDetails,
  onPerson,
  onAccountLogout,
  onAccountRefresh,
  onAvatarChange,
  onAvatarRemove,
}: Props) {
  const pttMode = micMode === 'ptt';
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [passwordFor, setPasswordFor] = useState<ChannelNode | null>(null);
  const [password, setPassword] = useState('');
  const [joiningChannel, setJoiningChannel] = useState<number | null>(null);
  const [chatCollapsed, setChatCollapsed] = useState(false);
  const [railOpen, setRailOpen] = useState(false);
  const [filesOpen, setFilesOpen] = useState(false);
  const [friendsOpen, setFriendsOpen] = useState(false);
  const [errorDismissed, setErrorDismissed] = useState(false);
  const joinTimer = useRef<number | null>(null);
  const [channelForm, setChannelForm] = useState<ChannelForm | null>(null);
  const [kickTarget, setKickTarget] = useState<Member | null>(null);
  const [kickReason, setKickReason] = useState('');
  const [banTarget, setBanTarget] = useState<Member | null>(null);
  const [banReason, setBanReason] = useState('');
  const [banMinutes, setBanMinutes] = useState(0);
  const [deleteTarget, setDeleteTarget] = useState<ChannelNode | null>(null);

  const current = useMemo(
    () => (state.ownChannel ? findChannel(state.tree, state.ownChannel) : null),
    [state.tree, state.ownChannel],
  );

  const accountAvatar = account.phase === 'signedIn' ? account.avatarUrl : '';
  const ownMember = useMemo(() => findMember(state.tree, state.ownId), [state.tree, state.ownId]);
  const ownAvatar = useMemo(() => {
    if (ownMember) return avatarSources(ownMember, state.ownId, accountAvatar, avatarToken);
    return accountAvatar ? [accountAvatar] : [];
  }, [ownMember, state.ownId, accountAvatar, avatarToken]);

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

  const openCreateChannel = (parent: number) => {
    setChannelForm({
      mode: 'create',
      parent,
      name: '',
      description: '',
      kind: 'permanent',
      password: false,
      newPassword: '',
      codec: current?.codec ?? 4,
      quality: 6,
      maxClients: -1,
    });
  };

  const openEditChannel = (ch: ChannelNode) => {
    setChannelForm({
      mode: 'edit',
      cid: ch.id,
      parent: ch.parent,
      name: ch.name,
      description: ch.description ?? '',
      kind: (ch.kind ?? 'permanent') as ChannelKind,
      password: ch.password,
      newPassword: '',
      codec: ch.codec,
      quality: ch.quality ?? 6,
      maxClients: ch.maxClients,
    });
  };

  const submitChannelForm = () => {
    const f = channelForm;
    if (!f || !f.name.trim()) return;
    if (f.mode === 'create') {
      onCreateChannel({
        name: f.name.trim(),
        description: f.description,
        kind: f.kind,
        password: f.password,
        newPassword: f.newPassword,
        codec: f.codec,
        quality: f.quality,
        maxClients: f.maxClients,
        parent: f.parent,
      });
    } else if (f.cid != null) {
      onEditChannel(f.cid, {
        name: f.name.trim(),
        description: f.description,
        kind: f.kind,
        password: f.password,
        newPassword: f.newPassword,
        codec: f.codec,
        quality: f.quality,
        maxClients: f.maxClients,
        parent: f.parent,
      });
    }
    setChannelForm(null);
  };

  const doKick = () => {
    if (kickTarget) onKickMember(kickTarget, kickReason.trim());
    setKickTarget(null);
    setKickReason('');
  };

  const doBan = () => {
    if (banTarget) onBanMember(banTarget, Math.max(0, Math.floor(banMinutes)) * 60, banReason.trim());
    setBanTarget(null);
    setBanReason('');
    setBanMinutes(0);
  };

  const channelTitle = current?.display || current?.name || '频道';
  const people = current?.clients ?? [];
  // 好友是不是正连在这台服务器上：uid → clientId；昵称兜底（好友的 identifier 就是 TS 身份 UID）
  const onlineUids = useMemo(() => {
    const m = new Map<string, number>();
    eachMember(state.tree, (c) => {
      if (c.uid) m.set(c.uid, c.id);
    });
    return m;
  }, [state.tree]);
  const onlineByName = useMemo(() => {
    const m = new Map<string, number>();
    eachMember(state.tree, (c) => {
      if (c.nickname) m.set(c.nickname, c.id);
    });
    return m;
  }, [state.tree]);
  const memberById = useMemo(() => {
    const m = new Map<number, Member>();
    eachMember(state.tree, (c) => m.set(c.id, c));
    return m;
  }, [state.tree]);
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
          <button
            type="button"
            className={`notice-btn${settingsOpen ? ' is-on' : ''}`}
            onClick={() => setSettingsOpen(true)}
            aria-pressed={settingsOpen}
            title="设置：账号、昵称、通知、关于"
          >
            <IconSetting size="small" />
            <span>设置</span>
          </button>
          {account.phase === 'signedIn' && (
            <button
              type="button"
              className={`notice-btn${friendsOpen ? ' is-on' : ''}`}
              onClick={() => setFriendsOpen((v) => !v)}
              aria-pressed={friendsOpen}
              title="好友与云聊天"
            >
              <IconUserGroup size="small" />
              <span>好友</span>
            </button>
          )}
          <span className="pill" title="服务器总在线人数">
            <IconUserGroup size="small" />
            全服 {state.clientCount} 人
          </span>
          <button
            type="button"
            className="user-chip"
            onClick={() => setSettingsOpen(true)}
            title="个人设置"
          >
            <UserAvatar
              name={state.nickname}
              sources={ownAvatar}
              colorKey="me"
              style={{ width: 24, height: 24 }}
            />
            <span className="user-chip-name">{state.nickname}</span>
            <IconSetting size="small" />
          </button>
        </div>
      </header>

      <FriendsPanel
        open={friendsOpen}
        onClose={() => setFriendsOpen(false)}
        friends={friends}
        account={account}
        cloud={cloud}
        chat={cloudChat}
        notice={cloudNotice}
        onlineUids={onlineUids}
        onlineByName={onlineByName}
        memberById={memberById}
        avatarToken={avatarToken}
        accountAvatar={accountAvatar}
        serverLines={allChat}
        ownId={state.ownId}
        onSendServer={onPrivately}
        onConnect={onCloudConnect}
        onDisconnect={onCloudDisconnect}
        onOpenFriend={onCloudOpen}
        onSend={onCloudSend}
        onHistory={onCloudHistory}
        onDismissNotice={onDismissCloudNotice}
      />

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
              <button
                type="button"
                className="icon-btn rail-add"
                title="新建频道"
                aria-label="新建频道"
                onClick={() => openCreateChannel(0)}
              >
                <IconPlus size="small" />
              </button>
            </div>
            <ChannelTree
              tree={state.tree}
              ownChannel={state.ownChannel}
              joiningChannel={joiningChannel}
              onJoin={join}
              onCreateChannel={openCreateChannel}
              onEditChannel={openEditChannel}
              onDeleteChannel={setDeleteTarget}
              avatarToken={avatarToken}
              onKick={setKickTarget}
              onBan={setBanTarget}
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
                avatarToken={avatarToken}
                accountAvatar={accountAvatar}
                onWatch={onWatch}
                onVolume={onMemberVolume}
                onKick={setKickTarget}
                onBan={setBanTarget}
                personDetails={personDetails}
                onPerson={onPerson}
              />
            )}

            <ChatPanel
              lines={chat}
              hasMore={chatMore}
              loading={chatBusy}
              onLoadMore={onLoadMoreChat}
              ownId={state.ownId}
              title={channelTitle}
              hint="频道消息，服务器上所有人都能看到"
              placeholder={`发消息到 ${channelTitle}`}
              collapsed={chatCollapsed}
              onToggleCollapsed={() => setChatCollapsed((v) => !v)}
              avatarToken={avatarToken}
              accountAvatar={accountAvatar}
              onSend={onSay}
            />
          </main>

          <ServerInfoPanel
            info={state.server ?? null}
            welcome={state.welcome}
            address={state.address}
            clientCount={state.clientCount}
            channelCount={countChannels(state.tree)}
            pingGateway={pingGateway}
            pingServer={pingServer}
          />
        </div>
      )}

      <ControlBar
        micOn={micOn}
        micLevel={micLevel}
        micGain={micGain}
        outputMuted={state.outputMuted}
        micMode={micMode}
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
        away={away}
        awayMessage={awayMessage}
        onToggleAway={onToggleAway}
        onSetAwayMessage={onSetAwayMessage}
        onMicMode={onMicMode}
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

      <SettingsPanel
        open={settingsOpen}
        onClose={() => setSettingsOpen(false)}
        account={account}
        nickname={state.nickname}
        server={state.server ?? null}
        address={state.address}
        pingGateway={pingGateway}
        pingServer={pingServer}
        notifyOn={notifyOn}
        notifySupported={notifySupported}
        onToggleNotify={onToggleNotify}
        onRename={onRename}
        onAccountRefresh={onAccountRefresh}
        onAccountLogout={onAccountLogout}
        onAvatarChange={onAvatarChange}
        onAvatarRemove={onAvatarRemove}
      />

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

      <Modal
        title={channelForm?.mode === 'edit' ? '编辑频道' : '新建频道'}
        visible={!!channelForm}
        onCancel={() => setChannelForm(null)}
        onOk={submitChannelForm}
        okText={channelForm?.mode === 'edit' ? '保存' : '创建'}
        cancelText="取消"
        width={480}
      >
        {channelForm && (
          <div className="chan-form">
            <label className="chan-field">
              <span>名称</span>
              <Input
                value={channelForm.name}
                maxLength={40}
                onChange={(v) => setChannelForm({ ...channelForm, name: v })}
                placeholder="频道名称"
              />
            </label>
            <label className="chan-field">
              <span>描述</span>
              <Input
                value={channelForm.description}
                maxLength={200}
                onChange={(v) => setChannelForm({ ...channelForm, description: v })}
                placeholder="可选"
              />
            </label>
            <label className="chan-field">
              <span>类型</span>
              <Select
                value={channelForm.kind}
                style={{ width: '100%' }}
                onChange={(v) => setChannelForm({ ...channelForm, kind: v as ChannelKind })}
              >
                <Select.Option value="permanent">永久</Select.Option>
                <Select.Option value="semi">半永久</Select.Option>
                <Select.Option value="temporary">临时</Select.Option>
              </Select>
            </label>
            <label className="chan-field">
              <span>编解码</span>
              <Select
                value={channelForm.codec}
                style={{ width: '100%' }}
                onChange={(v) => setChannelForm({ ...channelForm, codec: Number(v) })}
              >
                <Select.Option value={4}>Opus Voice</Select.Option>
                <Select.Option value={5}>Opus Music</Select.Option>
              </Select>
            </label>
            <label className="chan-field">
              <span>音质 0–10</span>
              <InputNumber
                min={0}
                max={10}
                value={channelForm.quality}
                onChange={(v) => setChannelForm({ ...channelForm, quality: Number(v ?? 6) })}
                style={{ width: '100%' }}
              />
            </label>
            <label className="chan-field">
              <span>人数上限（-1 不限）</span>
              <InputNumber
                min={-1}
                value={channelForm.maxClients}
                onChange={(v) => setChannelForm({ ...channelForm, maxClients: Number(v ?? -1) })}
                style={{ width: '100%' }}
              />
            </label>
            <div className="chan-field chan-field-row">
              <span>设置密码</span>
              <Switch
                checked={channelForm.password}
                onChange={(v) => setChannelForm({ ...channelForm, password: v })}
              />
            </div>
            {channelForm.password && (
              <label className="chan-field">
                <span>{channelForm.mode === 'edit' ? '新密码（留空保持不变）' : '密码'}</span>
                <Input
                  mode="password"
                  value={channelForm.newPassword}
                  onChange={(v) => setChannelForm({ ...channelForm, newPassword: v })}
                  placeholder={channelForm.mode === 'edit' ? '留空则不修改' : ''}
                />
              </label>
            )}
          </div>
        )}
      </Modal>

      <Modal
        title={`踢出「${kickTarget?.nickname ?? ''}」`}
        visible={!!kickTarget}
        onCancel={() => setKickTarget(null)}
        onOk={doKick}
        okText="踢出"
        cancelText="取消"
      >
        <p className="chan-hint">把 ta 从服务器上踢出去（可以重新连进来）。</p>
        <Input value={kickReason} maxLength={100} onChange={setKickReason} placeholder="原因（可选）" />
      </Modal>

      <Modal
        title={`封禁「${banTarget?.nickname ?? ''}」`}
        visible={!!banTarget}
        onCancel={() => setBanTarget(null)}
        onOk={doBan}
        okText="封禁"
        cancelText="取消"
      >
        <p className="chan-hint">封禁后 ta 将无法再连入本服务器。</p>
        <label className="chan-field">
          <span>时长（分钟，0 = 永久）</span>
          <InputNumber
            min={0}
            value={banMinutes}
            onChange={(v) => setBanMinutes(Number(v ?? 0))}
            style={{ width: '100%' }}
          />
        </label>
        <Input
          style={{ marginTop: 12 }}
          value={banReason}
          maxLength={100}
          onChange={setBanReason}
          placeholder="原因（可选）"
        />
      </Modal>

      <Modal
        title={`删除「${deleteTarget?.display || deleteTarget?.name || ''}」？`}
        visible={!!deleteTarget}
        onCancel={() => setDeleteTarget(null)}
        onOk={() => {
          if (deleteTarget) onDeleteChannel(deleteTarget.id);
          setDeleteTarget(null);
        }}
        okText="删除"
        cancelText="取消"
      >
        <p className="chan-hint">这个频道和它下面的子频道都会删除，里面的人会被移到默认频道。</p>
      </Modal>
    </div>
  );
}

type ChannelForm = {
  mode: 'create' | 'edit';
  cid?: number;
  parent: number;
  name: string;
  description: string;
  kind: ChannelKind;
  password: boolean;
  newPassword: string;
  codec: number;
  quality: number;
  maxClients: number;
};

function countChannels(tree: ChannelNode[]): number {
  let n = 0;
  for (const c of tree) {
    if (!c.spacer) n += 1;
    n += countChannels(c.children);
  }
  return n;
}
