import { useMemo } from 'react';
import { Tooltip, Typography } from '@douyinfe/semi-ui';
import {
  IconLock,
  IconMicrophoneOff,
  IconVolumnSilent,
} from '@douyinfe/semi-icons';
import type { ChannelNode, Member } from '../protocol';
import { flatten } from '../protocol';

const { Text } = Typography;

interface Props {
  tree: ChannelNode[];
  ownId: number;
  ownChannel: number;
  onJoin: (channel: ChannelNode) => void;
}

/** 频道里的一个人。说话时整行会亮起来，静音/离开用小图标标出来。 */
function PersonRow({ member, ownId }: { member: Member; ownId: number }) {
  const me = member.id === ownId;
  return (
    <div className={`person${member.talking ? ' person-talking' : ''}${me ? ' person-me' : ''}`}>
      <span className="person-dot" />
      <span className="person-name" title={member.nickname}>
        {member.nickname}
        {me && <Text type="tertiary" size="small"> （我）</Text>}
      </span>
      <span className="person-marks">
        {member.priority && (
          <Tooltip content="优先发言人">
            <span className="mark mark-priority">P</span>
          </Tooltip>
        )}
        {member.away && (
          <Tooltip content="离开">
            <span className="mark mark-away">离</span>
          </Tooltip>
        )}
        {member.streaming && (
          <Tooltip content="正在共享画面">
            <span className="mark mark-stream">屏</span>
          </Tooltip>
        )}
        {member.inputMuted && <IconMicrophoneOff size="small" className="mark-icon" />}
        {member.outputMuted && <IconVolumnSilent size="small" className="mark-icon" />}
      </span>
    </div>
  );
}

export default function ChannelTree({ tree, ownId, ownChannel, onJoin }: Props) {
  const rows = useMemo(() => flatten(tree), [tree]);

  return (
    <div className="channel-tree">
      {rows.map(({ channel, depth }) => {
        const here = channel.id === ownChannel;
        const people = channel.clients;

        // 分隔符频道：TeamSpeak 用它画横线或居中标题，不能点进去
        if (channel.spacer) {
          return (
            <div key={channel.id} className="spacer" style={{ paddingLeft: 12 + depth * 14 }}>
              {channel.display ? (
                <Text type="tertiary" size="small" className="spacer-text">
                  {channel.display}
                </Text>
              ) : (
                <span className="spacer-line" />
              )}
            </div>
          );
        }

        return (
          <div key={channel.id}>
            <div
              className={`channel${here ? ' channel-here' : ''}`}
              style={{ paddingLeft: 8 + depth * 14 }}
              onClick={() => onJoin(channel)}
              role="button"
              tabIndex={0}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') onJoin(channel);
              }}
            >
              <span className="channel-name" title={channel.topic || channel.display}>
                {channel.display || channel.name}
              </span>
              {channel.password && <IconLock size="small" className="channel-lock" />}
              {people.length > 0 && <span className="channel-count">{people.length}</span>}
            </div>
            {people.map((m) => (
              <div key={m.id} style={{ paddingLeft: depth * 14 + 20 }}>
                <PersonRow member={m} ownId={ownId} />
              </div>
            ))}
          </div>
        );
      })}
      {rows.length === 0 && (
        <Text type="tertiary" size="small" style={{ padding: 12, display: 'block' }}>
          这个服务器还没有频道
        </Text>
      )}
    </div>
  );
}
