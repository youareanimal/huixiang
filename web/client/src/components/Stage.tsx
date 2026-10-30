import { IconMicrophoneOff, IconVolumnSilent } from '@douyinfe/semi-icons';
import type { Member } from '../protocol';
import { avatarColor, initial } from '../ui';

interface Props {
  members: Member[];
  ownId: number;
  channelName: string;
  /** 正在看谁（0 = 没在看），用来把按钮标成「看着」。 */
  watchingId?: number;
  onWatch?: (clientId: number) => void;
}

/** 状态行：说话 > 静音 > 离开 > 身份组，只显示一句，别堆在一起。 */
function subline(member: Member, me: boolean): { text: string; tone: 'talk' | 'quiet' } {
  if (member.talking) return { text: me ? '正在说话 · 我' : '正在说话', tone: 'talk' };
  if (member.inputMuted) return { text: '已静音', tone: 'quiet' };
  if (member.away) return { text: '离开', tone: 'quiet' };
  if (me) return { text: '我', tone: 'quiet' };
  return { text: member.channelGroup || '', tone: 'quiet' };
}

/**
 * 频道舞台：主区的第一主角是「谁在说话」，不是输入框。
 * 头像 + 说话环 + 音量条，三重反馈，一眼看出话筒在谁那。
 */
export default function Stage({ members, ownId, channelName, watchingId = 0, onWatch }: Props) {
  if (members.length === 0) {
    return (
      <div className="stage">
        <div className="stage-empty">
          <span className="stage-empty-title">「{channelName}」还没有人</span>
          <span>你就是第一个人，说句话试试</span>
        </div>
      </div>
    );
  }

  return (
    <div className="stage">
      <div className="stage-grid">
        {members.map((m) => {
          const me = m.id === ownId;
          const sub = subline(m, me);
          const watching = watchingId === m.id;
          return (
            <div className={`member-tile${m.streaming ? ' is-streaming' : ''}`} key={`${m.uid}-${m.id}`}>
              <div className={`avatar-ring${m.talking ? ' is-talking' : ''}`}>
                <div
                  className="avatar"
                  style={{ background: avatarColor(me ? 'me' : m.uid || m.id) }}
                  aria-hidden="true"
                >
                  {initial(m.nickname)}
                </div>
              </div>
              <div className="tile-name-row">
                <span className="tile-name" title={m.nickname}>
                  {m.nickname}
                </span>
                {m.streaming && !me && onWatch && (
                  <button
                    type="button"
                    className={`watch-btn${watching ? ' is-on' : ''}`}
                    onClick={() => onWatch(m.id)}
                    title={watching ? '正在看 ta 的直播' : '看 ta 的直播'}
                  >
                    {watching ? '看着' : '观看'}
                  </button>
                )}
              </div>
              {m.streaming ? (
                <div className="tile-sub tile-live">
                  <span className="live-dot" aria-hidden="true" />
                  <span>正在直播</span>
                </div>
              ) : m.talking ? (
                <div className="bars is-live" aria-label="正在说话">
                  <i />
                  <i />
                  <i />
                  <i />
                </div>
              ) : sub.text ? (
                <div className="tile-sub">
                  {m.inputMuted && <IconMicrophoneOff size="small" />}
                  {m.outputMuted && !m.inputMuted && <IconVolumnSilent size="small" />}
                  <span>{sub.text}</span>
                </div>
              ) : null}
            </div>
          );
        })}
      </div>
    </div>
  );
}
