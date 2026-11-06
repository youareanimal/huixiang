import { useMemo, useState } from 'react';
import { Dropdown } from '@douyinfe/semi-ui';
import {
  IconChevronDown,
  IconChevronRight,
  IconDelete,
  IconEdit,
  IconLock,
  IconMicrophoneOff,
  IconMore,
  IconPlus,
  IconVolumnSilent,
} from '@douyinfe/semi-icons';
import type { ChannelNode, Member } from '../protocol';
import ChannelIcon from './ChannelIcon';

interface Props {
  tree: ChannelNode[];
  ownChannel: number;
  /** 正在切换的目标频道：置灰 + 禁止重复点 */
  joiningChannel: number | null;
  onJoin: (channel: ChannelNode) => void;
  /** 频道管理：建子频道 / 编辑 / 删除 */
  onCreateChannel: (parent: number) => void;
  onEditChannel: (channel: ChannelNode) => void;
  onDeleteChannel: (channel: ChannelNode) => void;
  /** 头像令牌：频道图标地址里带它（<img> 不能带 header）。 */
  avatarToken?: string;
  /** 成员管理（可选）：别的频道里的人也能踢 / 封 */
  onKick?: (member: Member) => void;
  onBan?: (member: Member) => void;
}

interface Row {
  channel: ChannelNode;
  depth: number;
}

/** 按折叠状态摊平成要渲染的行。父频道折起来时，整棵子树都不出。 */
function walk(list: ChannelNode[], depth: number, collapsed: Set<number>, out: Row[]) {
  for (const channel of list) {
    out.push({ channel, depth });
    if (channel.children.length > 0 && !collapsed.has(channel.id)) {
      walk(channel.children, depth + 1, collapsed, out);
    }
  }
  return out;
}

/** 别人频道里的成员：只在这里露一行。当前频道的人交给舞台，不重复画。 */
function PersonRow({
  member,
  onKick,
  onBan,
  token,
}: {
  member: Member;
  token?: string;
  onKick?: (m: Member) => void;
  onBan?: (m: Member) => void;
}) {
  const row = (
    <div className={`person${member.talking ? ' person-talking' : ''}`}>
      <span className="person-dot" />
      <span className="person-name" title={member.nickname}>
        {member.nickname}
      </span>
      {member.badges && member.badges.length > 0 && (
        <span className="person-badges">
          {member.badges.map((id) => (
            <ChannelIcon key={id} iconId={id} token={token} silent />
          ))}
        </span>
      )}
      <span className="person-marks">
        {member.priority && (
          <span className="mark mark-priority" title="优先发言人">
            P
          </span>
        )}
        {member.away && (
          <span className="mark mark-away" title="离开">
            离
          </span>
        )}
        {member.streaming && (
          <span className="mark mark-stream" title="正在共享画面">
            屏
          </span>
        )}
        {member.inputMuted && <IconMicrophoneOff size="small" />}
        {member.outputMuted && <IconVolumnSilent size="small" />}
      </span>
    </div>
  );
  if (!onKick && !onBan) return row;
  return (
    <Dropdown
      trigger="contextMenu"
      position="bottomLeft"
      menu={[
        ...(onKick ? [{ node: 'item' as const, name: '踢出成员', onClick: () => onKick(member) }] : []),
        ...(onBan
          ? [{ node: 'item' as const, name: '封禁成员', type: 'danger' as const, onClick: () => onBan(member) }]
          : []),
      ]}
    >
      {row}
    </Dropdown>
  );
}

export default function ChannelTree({
  tree,
  ownChannel,
  joiningChannel,
  onJoin,
  onCreateChannel,
  onEditChannel,
  onDeleteChannel,
  avatarToken,
  onKick,
  onBan,
}: Props) {
  const [collapsed, setCollapsed] = useState<Set<number>>(() => new Set());
  const rows = useMemo(() => walk(tree, 0, collapsed, []), [tree, collapsed]);

  const toggle = (id: number) => {
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const menuFor = (channel: ChannelNode) => [
    {
      node: 'item' as const,
      name: '新建子频道',
      icon: <IconPlus size="small" />,
      onClick: () => onCreateChannel(channel.id),
    },
    {
      node: 'item' as const,
      name: '编辑频道',
      icon: <IconEdit size="small" />,
      onClick: () => onEditChannel(channel),
    },
    {
      node: 'item' as const,
      name: '删除频道',
      icon: <IconDelete size="small" />,
      type: 'danger' as const,
      onClick: () => onDeleteChannel(channel),
    },
  ];

  if (rows.length === 0) {
    return <div className="rail-body">这个服务器还没有频道</div>;
  }

  return (
    <div className="rail-body">
      {rows.map(({ channel, depth }) => {
        // 分隔符频道：TeamSpeak 用它画横线或居中标题，不能点进去
        if (channel.spacer) {
          return (
            <div className="chan-spacer" key={channel.id} style={{ paddingLeft: 8 + depth * 12 }}>
              {channel.display ? (
                <span className="chan-spacer-text">{channel.display}</span>
              ) : (
                <span className="chan-spacer-line" />
              )}
            </div>
          );
        }

        const here = channel.id === ownChannel;
        const hasChildren = channel.children.length > 0;
        const open = !collapsed.has(channel.id);
        const joining = joiningChannel === channel.id;
        const people = channel.clients;

        const rowNode = (
          <div
            className={`chan-row${here ? ' chan-on' : ''}`}
            style={{ paddingLeft: 8 + depth * 12 }}
            role="button"
            tabIndex={0}
            aria-current={here ? 'true' : undefined}
            aria-disabled={joining || undefined}
            onClick={() => {
              if (joining) return;
              onJoin(channel);
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                if (!joining) onJoin(channel);
              }
            }}
          >
            {hasChildren ? (
              <span
                className="chan-caret"
                role="presentation"
                onClick={(e) => {
                  e.stopPropagation();
                  toggle(channel.id);
                }}
              >
                {open ? <IconChevronDown size="small" /> : <IconChevronRight size="small" />}
              </span>
            ) : (
              <span className="chan-caret-space" />
            )}
            <span className="chan-icon">
              <ChannelIcon iconId={channel.iconId} token={avatarToken} />
            </span>
            <span className="chan-name" title={channel.topic || channel.display}>
              {channel.display || channel.name}
            </span>
            {channel.password && <IconLock size="small" className="chan-lock" />}
            {people.length > 0 && <span className="chan-count">{people.length}</span>}
            <span className="chan-more" onClick={(e) => e.stopPropagation()}>
              <Dropdown trigger="click" position="bottomRight" menu={menuFor(channel)}>
                <button type="button" className="chan-more-btn" aria-label="频道操作">
                  <IconMore size="small" />
                </button>
              </Dropdown>
            </span>
          </div>
        );

        return (
          <div key={channel.id}>
            <Dropdown trigger="contextMenu" position="bottomLeft" menu={menuFor(channel)}>
              {rowNode}
            </Dropdown>

            {people.length > 0 && !here && (
              <div className="chan-people" style={{ paddingLeft: 8 + depth * 12 + 22 }}>
                {people.map((m) => (
                  <PersonRow key={m.id} member={m} token={avatarToken} onKick={onKick} onBan={onBan} />
                ))}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
