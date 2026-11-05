// 频道成员进出的比对逻辑。
//
// 网关每次推的状态是「全量快照」，ChannelNode.clients 里带完整成员。前端只要把
// 上一份「我所在频道」的成员和这一份比一比，就能知道这一次谁进来了、谁离开了，
// 完全不用改后端协议。
//
// 注意两个容易误报的点，这份逻辑都处理了：
// 1. 首次拿到快照（没有上一份）→ 只建基线，不报。
// 2. 我自己换了频道（channelId 变了）→ 整批人都会「变」，只重置基线，不报。

import type { ChannelNode } from './protocol';
import { findChannel } from './protocol';

/** 上一份快照里「我所在频道」的成员：clientId → 昵称（已排除自己）。 */
export interface PresenceBaseline {
  channelId: number;
  members: Map<number, string>;
}

export interface PresenceDelta {
  /** 这一次新进来的人（昵称）。 */
  joins: string[];
  /** 这一次离开的人（昵称）。 */
  leaves: string[];
  /** 供下一次比对用的新基线。 */
  baseline: PresenceBaseline;
}

/** 取某个频道的成员（昵称表，排除指定 clientId，通常是自己）。 */
export function channelMembers(
  tree: ChannelNode[],
  channelId: number,
  selfId: number,
): Map<number, string> {
  const out = new Map<number, string>();
  const ch = findChannel(tree, channelId);
  if (!ch) return out;
  for (const m of ch.clients) {
    if (m.id !== selfId) out.set(m.id, m.nickname);
  }
  return out;
}

/**
 * 用新快照跟上一份基线比，得出进出名单。
 * prev 为 null、或频道和上一份不同（我自己刚换频道）时，joins/leaves 一律为空，只重置基线。
 */
export function diffPresence(
  prev: PresenceBaseline | null,
  tree: ChannelNode[],
  channelId: number,
  selfId: number,
): PresenceDelta {
  const members = channelMembers(tree, channelId, selfId);
  const joins: string[] = [];
  const leaves: string[] = [];
  if (prev && prev.channelId === channelId) {
    for (const [id, name] of members) {
      if (!prev.members.has(id)) joins.push(name);
    }
    for (const [id, name] of prev.members) {
      if (!members.has(id)) leaves.push(name);
    }
  }
  return { joins, leaves, baseline: { channelId, members } };
}
