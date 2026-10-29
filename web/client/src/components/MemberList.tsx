import { Avatar, Tag, Typography } from '@douyinfe/semi-ui';
import { IconMicrophoneOff, IconVolumnSilent } from '@douyinfe/semi-icons';
import type { Member } from '../protocol';

const { Text } = Typography;

interface Props {
  members: Member[];
  ownId: number;
  title: string;
}

/** 右栏：本频道的人。频道树里也能看到，但这一栏给的是身份组这类细节。 */
export default function MemberList({ members, ownId, title }: Props) {
  return (
    <div className="member-list">
      <div className="member-head">
        <Text strong size="small">
          {title}
        </Text>
        <Text type="tertiary" size="small">
          {members.length} 人
        </Text>
      </div>
      {members.map((m) => {
        const me = m.id === ownId;
        return (
          <div key={`${m.uid}-${m.id}`} className={`member${m.talking ? ' member-talking' : ''}`}>
            <Avatar size="small" color={me ? 'violet' : 'blue'}>
              {m.nickname.slice(0, 1).toUpperCase()}
            </Avatar>
            <div className="member-main">
              <div className="member-name">
                {m.nickname}
                {me && (
                  <Text type="tertiary" size="small">
                    {' '}
                    （我）
                  </Text>
                )}
              </div>
              {m.channelGroup && (
                <Tag size="small" color={me ? 'violet' : 'grey'} type="light" className="member-tag">
                  {m.channelGroup}
                </Tag>
              )}
            </div>
            <div className="member-marks">
              {m.away && <span className="mark mark-away">离</span>}
              {m.inputMuted && <IconMicrophoneOff size="small" className="mark-icon" />}
              {m.outputMuted && <IconVolumnSilent size="small" className="mark-icon" />}
            </div>
          </div>
        );
      })}
      {members.length === 0 && (
        <Text type="tertiary" size="small" style={{ padding: 12, display: 'block' }}>
          这个频道只有你
        </Text>
      )}
    </div>
  );
}
