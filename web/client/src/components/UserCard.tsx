import type { Member, PersonDetails } from '../protocol';
import { avatarSources } from '../ui';
import UserAvatar from './UserAvatar';

interface Props {
  member: Member;
  /** 这个人所在频道名（舞台里就是当前频道）。 */
  channelName: string;
  ownId: number;
  accountAvatar: string;
  avatarToken: string;
  /** 服务器补问回来的公开字段；还没拿到就先只显示本地快照。 */
  details?: PersonDetails;
}

/** 连接时长：秒（连接时刻）→「1 天 2 小时」「3 小时 5 分」「12 分钟」。 */
function duration(startedAt: number, now: number): string {
  if (!startedAt || startedAt <= 0) return '';
  const total = Math.max(0, now - startedAt);
  const d = Math.floor(total / 86400);
  const h = Math.floor((total % 86400) / 3600);
  const m = Math.floor((total % 3600) / 60);
  if (d > 0) return `${d} 天 ${h} 小时`;
  if (h > 0) return `${h} 小时 ${m} 分`;
  return `${Math.max(1, m)} 分钟`;
}

/** 地区码 → 中文名（常见几个；其余原样显示）。 */
const COUNTRY: Record<string, string> = {
  CN: '中国',
  HK: '香港',
  TW: '台湾',
  MO: '澳门',
  US: '美国',
  JP: '日本',
  KR: '韩国',
  SG: '新加坡',
  GB: '英国',
  DE: '德国',
  FR: '法国',
  CA: '加拿大',
  AU: '澳大利亚',
  RU: '俄罗斯',
};

/**
 * 用户资料卡：悬停在头像上时浮出来的一张小卡片。
 * 只用「服务器已经告诉我们的事」：快照里的昵称/身份组/状态 + 点开时补问到的客户端信息。
 * 服务器不给的字段不瞎猜，缺就整行不显示。
 */
export default function UserCard({ member, channelName, ownId, accountAvatar, avatarToken, details }: Props) {
  const me = member.id === ownId;
  const groups = [member.channelGroup, ...member.serverGroups].filter((g) => !!g && g.trim().length > 0);
  const client = [details?.platform, details?.version].filter((s) => !!s && s.trim().length > 0).join(' ');
  const online = duration(details?.connectionTime ?? 0, Date.now());
  const country = details?.country
    ? (COUNTRY[details.country.toUpperCase()] ?? details.country)
    : '';

  return (
    <div className="user-card">
      <div className="user-card-head">
        <UserAvatar
          name={member.nickname}
          sources={avatarSources(member, ownId, accountAvatar, avatarToken)}
          colorKey={me ? 'me' : member.uid || member.id}
          className="user-card-avatar"
        />
        <div className="user-card-ident">
          <div className="user-card-name" title={member.nickname}>
            {member.nickname}
            {me && <span className="user-card-me">我</span>}
          </div>
          <div className="user-card-badges">
            {member.away ? (
              <span className="uc-badge uc-away">离开</span>
            ) : (
              <span className="uc-badge uc-online">在线</span>
            )}
            {member.streaming && <span className="uc-badge uc-live">直播中</span>}
            {member.inputMuted && <span className="uc-badge uc-mute">麦克风静音</span>}
            {!member.inputMuted && member.outputMuted && <span className="uc-badge uc-mute">扬声器静音</span>}
            {member.priority && <span className="uc-badge uc-priority">优先发言人</span>}
          </div>
        </div>
      </div>

      <dl className="user-card-rows">
        <div className="uc-row">
          <dt>UID</dt>
          <dd className="uc-uid" title={member.uid}>
            {member.uid || '—'}
          </dd>
        </div>
        <div className="uc-row">
          <dt>频道</dt>
          <dd>{channelName || '—'}</dd>
        </div>
        {groups.length > 0 && (
          <div className="uc-row">
            <dt>身份组</dt>
            <dd className="uc-groups">
              {groups.map((g) => (
                <span className="uc-group" key={g}>
                  {g}
                </span>
              ))}
            </dd>
          </div>
        )}
        {client && (
          <div className="uc-row">
            <dt>客户端</dt>
            <dd>{client}</dd>
          </div>
        )}
        {country && (
          <div className="uc-row">
            <dt>地区</dt>
            <dd>{country}</dd>
          </div>
        )}
        {online && (
          <div className="uc-row">
            <dt>在线</dt>
            <dd>{online}</dd>
          </div>
        )}
        {member.talkPower !== 0 && (
          <div className="uc-row">
            <dt>说话权</dt>
            <dd>{member.talkPower}</dd>
          </div>
        )}
      </dl>

      {details?.description && <div className="user-card-desc">{details.description}</div>}
    </div>
  );
}
