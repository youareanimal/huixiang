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
  /** 服务器头像的哈希；空表示没有。地址走网关的 /avatar 端点。 */
  avatar?: string;
  /** 服务器直接给出的头像文件名，来自 client_base64HashClientUID。 */
  avatarName?: string;
  /** 对方公开的 myTeamSpeak 头像 https 地址；空表示没有。 */
  myTeamSpeakAvatar?: string;
}

export interface ChannelNode {
  id: number;
  parent: number;
  name: string;
  display: string;
  spacer: boolean;
  password: boolean;
  topic: string;
  codec: number;
  neededTalkPower: number;
  maxClients: number;
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

/** 好友只做展示：myTeamSpeak 的联系人列表没有在线状态，网页版也不接 Matrix 聊天。 */
export interface Friend {
  id: string;
  name: string;
  subtitle: string;
}

export interface ContactsMessage {
  t: 'contacts';
  items: Friend[];
  error: string | null;
  loading: boolean;
}

export const guestAccount = (): AccountState => ({
  t: 'account',
  phase: 'guest',
  username: '',
  email: '',
  avatarUrl: '',
  error: null,
});

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

const KEY = {
  identity: 'huixiang.identity',
  token: 'huixiang.token',
  recent: 'huixiang.recent',
  nickname: 'huixiang.nickname',
  micGain: 'huixiang.micGain',
  volumes: 'huixiang.volumes',
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
