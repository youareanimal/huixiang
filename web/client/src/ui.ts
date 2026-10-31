// 头像配色和首字母：一份 uid 永远拿到同一个颜色，别每帧换脸。

const AVATAR_COLORS = [
  '#6c4bf6',
  '#3f6fe0',
  '#de8a2b',
  '#14b89a',
  '#0e9e86',
  '#7a5af8',
  '#2f8fbf',
  '#b25e00',
];

export function avatarColor(key: string | number): string {
  const s = String(key ?? '');
  let h = 0;
  for (let i = 0; i < s.length; i += 1) h = (h * 31 + s.charCodeAt(i)) % 9973;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}

export function initial(name: string): string {
  const t = (name ?? '').trim();
  return t ? t.slice(0, 1).toUpperCase() : '?';
}

/**
 * 一个人的头像地址，按顺序尝试：自己的账号头像 > 服务器头像 > 对方公开的 myTeamSpeak 头像。
 * 服务器头像要经过网关，地址里带这个标签页的头像令牌；令牌为空（还没连上）就跳过。
 */
export function avatarSources(
  person: { id: number; avatar?: string; myTeamSpeakAvatar?: string },
  ownId: number,
  accountAvatar: string,
  avatarToken: string,
): string[] {
  const out: string[] = [];
  if (ownId > 0 && person.id === ownId && accountAvatar) out.push(accountAvatar);
  if (person.avatar && avatarToken) {
    out.push(`/avatar/${avatarToken}/${person.id}?v=${encodeURIComponent(person.avatar)}`);
  }
  if (person.myTeamSpeakAvatar) out.push(person.myTeamSpeakAvatar);
  return out;
}
