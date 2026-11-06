// 网关协议的类型。字段和后端 WebSession / Gateway.kt 里推的一致，改一边要改另一边。

import { DEFAULT_GAIN, clampGain } from './audio/gain';

export type Phase = 'idle' | 'connecting' | 'connected';

export interface Member {
  id: number;
  nickname: string;
  uid: string;
  talking: boolean;
  away: boolean;
  inputMuted: boolean;
  outputMuted: boolean;
  talkPower: number;
  isTalker: boolean;
  priority: boolean;
  streaming: boolean;
  channelGroup: string;
  serverGroups: string[];
  /** 身份组图标 id（频道组 → 服务器组 → 自己的图标，按服务器顺序）；<1000 为内置。 */
  badges?: number[];
  /** 服务器头像的哈希；空表示没有。地址走网关的 /avatar 端点。 */
  avatar?: string;
  /** 服务器直接给出的头像文件名，来自 client_base64HashClientUID。 */
  avatarName?: string;
  /** 对方公开的 myTeamSpeak 头像 https 地址；空表示没有。 */
  myTeamSpeakAvatar?: string;
}

/**
 * 服务器对某人公开字段的补充查询结果（对应 TeamSpeak 的 clientgetvariables）。
 * 这些字段服务器平时不随快照推送，只有点了头像要看资料卡时才单独问一次；
 * 服务器没给的字段就是空串/缺失，前端只显示本地已经知道的那部分。
 */
export interface PersonDetails {
  id: number;
  /** 客户端自报的描述（可能是签名或状态短语）。 */
  description?: string;
  /** 地区代码，如 CN。 */
  country?: string;
  /** 客户端平台，如 Windows / Android。 */
  platform?: string;
  /** 客户端版本，如 5.3.6。 */
  version?: string;
  /** 连接时刻（Unix 秒）；0 或缺失表示服务器没给。 */
  connectionTime?: number;
}

export interface ChannelNode {
  id: number;
  parent: number;
  name: string;
  display: string;
  spacer: boolean;
  password: boolean;
  topic: string;
  /** 频道图标 id：0 无；1..999 内置（盾牌色）；1000+ 是服务器文件 /icon_<id>。 */
  iconId?: number;
  codec: number;
  neededTalkPower: number;
  maxClients: number;
  /** 频道当前描述（编辑用）。 */
  description?: string;
  /** 音质 0–10（编辑用）。 */
  quality?: number;
  /** 频道类型：永久 / 半永久 / 临时（编辑用）。 */
  kind?: ChannelKind;
  /** 在同级里的排序位（编辑用）。 */
  order?: number;
  clients: Member[];
  children: ChannelNode[];
}

/** 服务器自报的家底（virtualserver_*），网关有哪个就给哪个，没有的字段是 null。 */
export interface ServerInfo {
  maxClients: number | null;
  clientsOnline: number | null;
  channelsOnline: number | null;
  /** 连上那一刻的运行秒数；配合 receivedAt 在前端算出会跳的实时值。 */
  uptime: number | null;
  receivedAt: number | null;
  createdAt: number | null;
  platform: string | null;
  version: string | null;
  bannerUrl: string | null;
  bannerLink: string | null;
  hostbuttonUrl: string | null;
  hostbuttonTooltip: string | null;
}

export interface State {
  t: 'state';
  phase: Phase;
  error: string | null;
  address: string;
  nickname: string;
  serverName: string;
  welcome: string;
  ownId: number;
  ownChannel: number;
  inputMuted: boolean;
  outputMuted: boolean;
  /** 自己当前是否暂离（Away），由服务器的 client_away 同步而来。 */
  away: boolean;
  /** 自己的离开留言（可为空）。 */
  awayMessage: string;
  clientCount: number;
  /**
   * 文件传输的会话令牌：HTTP 端点靠它认出「这是哪个标签页的会话」，所以只能给这一个页面用。
   * 每次连接都是新的，断开就作废。
   */
  fileToken?: string;
  /** 头像地址里的会话令牌；只能读头像，不能读频道文件。 */
  avatarToken?: string;
  server?: ServerInfo | null;
  tree: ChannelNode[];
}

/** 频道类型：永久 / 半永久 / 临时。 */
export type ChannelKind = 'permanent' | 'semi' | 'temporary';

/** 建频道时提交的字段（可选字段由网关补默认值）。 */
export interface ChannelCreate {
  name: string;
  description?: string;
  kind?: ChannelKind;
  password?: boolean;
  newPassword?: string;
  codec?: number;
  quality?: number;
  maxClients?: number;
  parent?: number;
  after?: number;
}

/** 改频道：只提交要改的字段，网关以服务器上的当前值为基准做 diff。 */
export interface ChannelPatch {
  name?: string;
  description?: string;
  kind?: ChannelKind;
  password?: boolean;
  newPassword?: string;
  codec?: number;
  quality?: number;
  maxClients?: number;
  parent?: number;
  after?: number;
}

/** 一次频道管理操作（建/删/改/踢/封）的结果，label 与 error 都是中文。 */
export interface ChannelOpResult {
  t: 'chanOp';
  id: string;
  ok: boolean;
  label: string;
  error: string | null;
}

export interface ChatLine {
  t: 'chat';
  from: number;
  name: string;
  channel: number;
  /** 1 = 私聊，2 = 频道。 */
  mode: number;
  text: string;
  time: number;
  avatar?: string;
  avatarName?: string;
  myTeamSpeakAvatar?: string;
  /** 私聊：发送方稳定的 TS 身份 UID（不随重连变）。 */
  fromUid?: string;
  /** 私聊：对方稳定的 TS 身份 UID（好友 identifier 即它，用来配对会话）。 */
  peerUid?: string;
  /** 私聊：对方昵称（UID 对不上时兜底配对）。 */
  peerName?: string;
  /** 这条是不是我自己发的（按发送时自己的 clientId 判定）。 */
  mine?: boolean;
}

/** 网关回来的那一页频道历史；只有网关连着这个服务器时收过消息才有。 */
export interface HistoryMessage {
  t: 'history';
  channel: number;
  lines: Array<{ time: number; from: number; name: string; uid?: string; text: string }>;
  /** 还能继续往上翻。 */
  more: boolean;
}

// ====== myTeamSpeak 账号 ======

/**
 * 账号会话只活在这个标签页的网关连接里：刷新就没了，浏览器不保存令牌或密码。
 * `guest` 也包含登录失败后的状态，错误码在 `error` 里。
 */
export type AccountPhase = 'guest' | 'busy' | 'signedIn';

export interface AccountState {
  t: 'account';
  phase: AccountPhase;
  username: string;
  email: string;
  avatarUrl: string;
  error: string | null;
  /** 头像上传的凭据（HTTP 端点认 X-File-Token）。 */
  fileToken?: string;
}

export interface Bookmark {
  id: string;
  name: string;
  address: string;
  nickname: string;
  /** 服务器密码；只用来填进隐藏的密码框，不显示、也不写进 localStorage。 */
  password: string;
}

export interface BookmarksMessage {
  t: 'bookmarks';
  items: Bookmark[];
  error: string | null;
  loading: boolean;
}

/** myTeamSpeak 的联系人：网页版用它的 matrixId 打开好友云聊天（在线状态仍没有，点开才建/开房间）。 */
export interface Friend {
  id: string;
  name: string;
  subtitle: string;
  matrixId?: string;
}

export interface ContactsMessage {
  t: 'contacts';
  items: Friend[];
  error: string | null;
  loading: boolean;
}

// ====== 好友云聊天（Matrix） ======

/** 云端聊天连接的阶段：连上 Matrix 之前是 idle/connecting，失败进 error。 */
export type CloudPhase = 'idle' | 'connecting' | 'connected' | 'error';

/** 云聊天连接本身的状态。和账号分开：账号登录了才可能连上云聊天。 */
export interface CloudState {
  t: 'cloud';
  phase: CloudPhase;
  connected: boolean;
  /** 自己的 Matrix 用户 id（@user:domain），用来判断一条消息是不是自己发的。 */
  userId: string;
  error: string | null;
  /** error 的中文说法。 */
  errorText: string | null;
}

/** 一条云端聊天消息。sender 是 Matrix 用户 id。 */
export interface CloudMessage {
  id: string;
  sender: string;
  body: string;
  /** 毫秒。 */
  time: number;
  /** 端到端加密的消息网页版读不了，body 只是一句占位文本。 */
  encrypted: boolean;
}

/** 打开一个好友会话的回包：房间信息 + 最近一屏。roomId 为 null 表示没打开（看 error）。 */
export interface CloudChat {
  t: 'cloudChat';
  roomId: string | null;
  peer: string | null;
  name: string | null;
  encrypted?: boolean;
  messages: CloudMessage[];
  more: boolean;
  friendId: string;
  error: string | null;
  errorText: string | null;
}

/** 一条新到的云端聊天消息（带房间）。 */
export interface CloudMessageEvent {
  t: 'cloudMessage';
  roomId: string;
  message: CloudMessage;
}

/** 一次发送的结果；成功时消息本身会另走 cloudMessage 回来。 */
export interface CloudSend {
  t: 'cloudSend';
  roomId: string;
  ok: boolean;
  error: string | null;
  errorText: string | null;
}

export const emptyCloud = (): CloudState => ({
  t: 'cloud',
  phase: 'idle',
  connected: false,
  userId: '',
  error: null,
  errorText: null,
});

export const guestAccount = (): AccountState => ({
  t: 'account',
  phase: 'guest',
  username: '',
  email: '',
  avatarUrl: '',
  error: null,
});

/**
 * 登录状态下必须用的账号名：没登录、或者网关还没把名字推下来时是空串。
 * 空串就说明「这次不该强制用账号名」，昵称仍由用户自己填。
 */
export function accountNickname(account: AccountState | null): string {
  return account?.phase === 'signedIn' ? account.username.trim() : '';
}

/**
 * 连接时实际用的昵称：登录了 myTeamSpeak 就一定用账号名（服务器会按账号认人），
 * 游客才用本地保存的自定义昵称。判断只写这一处，App 和连接页用同一套规则。
 */
export function effectiveNickname(account: AccountState | null, fallback: string): string {
  return accountNickname(account) || fallback;
}

export const emptyBookmarks = (): BookmarksMessage => ({
  t: 'bookmarks',
  items: [],
  error: null,
  loading: false,
});

export const emptyContacts = (): ContactsMessage => ({
  t: 'contacts',
  items: [],
  error: null,
  loading: false,
});

/** 频道文件区里的一个文件或文件夹，字段与 FileTransfer.Entry 一致。 */
export interface FileEntry {
  name: string;
  folder: boolean;
  size: number;
  /** 毫秒；服务器没给就是 0。 */
  time: number;
}

/**
 * 一次目录列表的回复。`error` 非空时 `entries` 是空的——服务器拒绝（没权限、要密码）比「空文件夹」
 * 更需要说清楚。
 */
export interface FilesMessage {
  t: 'files';
  cid: number;
  path: string;
  entries: FileEntry[];
  error: string | null;
}

/** 下行语音帧的头部长度：[2][codec][clientId 高][clientId 低] */
export const VOICE_HEADER = 4;
export const UP_VOICE = 1;
export const DOWN_VOICE = 2;

export interface ConnectOptions {
  host: string;
  port: number;
  nickname: string;
  password: string;
  identity: string | null;
}

// ====== 本地保存 ======

/** 说话方式。auto = 本地做人声检测（VAD），安静时不发送。 */
export type MicMode = 'auto' | 'open' | 'ptt';

/** 「记住上次麦克风/扬声器开关」的时效：14 天。超过就回到默认（闭麦、扬声器开）。 */
const MUTE_PREF_TTL_MS = 14 * 24 * 60 * 60 * 1000;

const KEY = {
  identity: 'huixiang.identity',
  token: 'huixiang.token',
  recent: 'huixiang.recent',
  nickname: 'huixiang.nickname',
  micGain: 'huixiang.micGain',
  micMode: 'huixiang.micMode',
  volumes: 'huixiang.volumes',
  notify: 'huixiang.notify',
  mutePref: 'huixiang.mutePref',
};

export const store = {
  identity(): string | null {
    return localStorage.getItem(KEY.identity);
  },
  setIdentity(v: string) {
    localStorage.setItem(KEY.identity, v);
  },
  token(): string {
    return localStorage.getItem(KEY.token) ?? '';
  },
  setToken(v: string) {
    if (v) localStorage.setItem(KEY.token, v);
    else localStorage.removeItem(KEY.token);
  },
  nickname(): string {
    return localStorage.getItem(KEY.nickname) ?? '';
  },
  setNickname(v: string) {
    localStorage.setItem(KEY.nickname, v);
  },
  /** 最近连过的服务器，最近的在前面。 */
  recent(): string[] {
    try {
      const raw = JSON.parse(localStorage.getItem(KEY.recent) ?? '[]');
      return Array.isArray(raw) ? raw.filter((x) => typeof x === 'string').slice(0, 8) : [];
    } catch {
      return [];
    }
  },
  addRecent(address: string) {
    const list = [address, ...store.recent().filter((x) => x !== address)].slice(0, 8);
    localStorage.setItem(KEY.recent, JSON.stringify(list));
  },
  forgetRecent(address: string) {
    localStorage.setItem(KEY.recent, JSON.stringify(store.recent().filter((x) => x !== address)));
  },
  /** 麦克风增益，0–2。跟这台机器的麦克风绑着，所以只存本地，不跟账号走。 */
  micGain(): number {
    const raw = localStorage.getItem(KEY.micGain);
    if (raw === null) return DEFAULT_GAIN;
    return clampGain(Number(raw));
  },
  setMicGain(v: number) {
    localStorage.setItem(KEY.micGain, String(clampGain(v)));
  },
  /** 说话方式：自动（检测到人声才发）/ 常开 / 按住说话。跟着这台机器走。 */
  micMode(): MicMode {
    const raw = localStorage.getItem(KEY.micMode);
    return raw === 'open' || raw === 'ptt' ? raw : 'auto';
  },
  setMicMode(v: MicMode) {
    localStorage.setItem(KEY.micMode, v);
  },
  /** 成员音量：key 见 memberKey（uid 而不是 clientId）。 */
  volumes(): Record<string, number> {
    try {
      const raw = JSON.parse(localStorage.getItem(KEY.volumes) ?? '{}');
      if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return {};
      const out: Record<string, number> = {};
      for (const [k, v] of Object.entries(raw as Record<string, unknown>)) {
        out[k] = clampGain(Number(v));
      }
      return out;
    } catch {
      return {};
    }
  },
  setVolumes(v: Record<string, number>) {
    localStorage.setItem(KEY.volumes, JSON.stringify(v));
  },
  /** 是否开启桌面消息通知。跟浏览器权限绑着，所以只存本地，不跟账号走。 */
  notify(): boolean {
    return localStorage.getItem(KEY.notify) === '1';
  },
  setNotify(v: boolean) {
    localStorage.setItem(KEY.notify, v ? '1' : '0');
  },
  /**
   * 上次离开时麦克风/扬声器的开关，用于「短暂退出再进入」时恢复（14 天内有效）。
   * inputMuted=true 表示上次是闭麦；outputMuted=true 表示上次扬声器静音。
   * 超过时效或数据损坏就返回 null，调用方按默认处理。
   */
  mutePref(): { inputMuted: boolean; outputMuted: boolean } | null {
    try {
      const raw = JSON.parse(localStorage.getItem(KEY.mutePref) ?? 'null');
      if (!raw || typeof raw !== 'object') return null;
      const at = Number((raw as { at?: unknown }).at);
      if (!Number.isFinite(at) || at > Date.now() || Date.now() - at > MUTE_PREF_TTL_MS) return null;
      return {
        inputMuted: Boolean((raw as { inputMuted?: unknown }).inputMuted),
        outputMuted: Boolean((raw as { outputMuted?: unknown }).outputMuted),
      };
    } catch {
      return null;
    }
  },
  setMutePref(inputMuted: boolean, outputMuted: boolean) {
    localStorage.setItem(KEY.mutePref, JSON.stringify({ inputMuted, outputMuted, at: Date.now() }));
  },
  clearMutePref() {
    localStorage.removeItem(KEY.mutePref);
  },
};

/**
 * 音量的持久化键。clientId 每次连接都重新分配，uid 才是「同一个人」，
 * 所以按 uid 记，换个频道、掉线重连都还是自己调过的音量。
 */
export function memberKey(m: Member): string {
  return m.uid || `id:${m.id}`;
}

/** 把整棵频道树里的人挨个过一遍：算音量、看谁还在，都用它。 */
export function eachMember(tree: ChannelNode[], fn: (m: Member) => void) {
  for (const c of tree) {
    c.clients.forEach(fn);
    eachMember(c.children, fn);
  }
}

/** 把树摊平，按显示顺序，带深度。渲染频道列表和找人时都用它。 */
export function flatten(tree: ChannelNode[], depth = 0, out: Array<{ channel: ChannelNode; depth: number }> = []) {
  for (const c of tree) {
    out.push({ channel: c, depth });
    flatten(c.children, depth + 1, out);
  }
  return out;
}

export function findMember(tree: ChannelNode[], id: number): Member | null {
  for (const c of tree) {
    const hit = c.clients.find((m) => m.id === id);
    if (hit) return hit;
    const deep = findMember(c.children, id);
    if (deep) return deep;
  }
  return null;
}

export function findChannel(tree: ChannelNode[], id: number): ChannelNode | null {
  for (const c of tree) {
    if (c.id === id) return c;
    const deep = findChannel(c.children, id);
    if (deep) return deep;
  }
  return null;
}
