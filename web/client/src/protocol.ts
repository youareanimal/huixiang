// 网关协议的类型。字段和后端 WebSession / Gateway.kt 里推的一致，改一边要改另一边。

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
};

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
