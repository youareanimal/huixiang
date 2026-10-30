import { IconVolume1, IconVolumnSilent, IconMicrophoneOff } from '@douyinfe/semi-icons';
import type { Member } from '../protocol';
import { memberKey } from '../protocol';
import { DEFAULT_GAIN, gainPercent } from '../audio/gain';
import { avatarColor, initial } from '../ui';
import VolumePopover from './VolumePopover';

interface Props {
  members: Member[];
  ownId: number;
  channelName: string;
  /** 按 uid 记的成员音量；没调过的不在表里。 */
  volumes: Record<string, number>;
  /** 从语音帧判出来的说话人；服务器的 talking 不可靠，以这个为准。 */
  talkingIds: ReadonlySet<number>;
  /** 自己是否在说话（麦克风电平）。 */
  ownTalking: boolean;
  /** 正在看谁（0 = 没在看），用来把按钮标成「看着」。 */
  watchingId?: number;
  onWatch?: (clientId: number) => void;
  onVolume: (member: Member, v: number) => void;
}

/** 状态行：说话 > 静音 > 离开 > 身份组，只显示一句，别堆在一起。 */
function subline(member: Member, me: boolean, talking: boolean): { text: string; tone: 'talk' | 'quiet' } {
  if (talking) return { text: me ? '正在说话 · 我' : '正在说话', tone: 'talk' };
  if (member.inputMuted) return { text: '已静音', tone: 'quiet' };
  if (member.away) return { text: '离开', tone: 'quiet' };
  if (me) return { text: '我', tone: 'quiet' };
  return { text: member.channelGroup || '', tone: 'quiet' };
}

/**
 * 频道舞台：主区的第一主角是「谁在说话」，不是输入框。
 * 头像 + 说话环 + 音量条，三重反馈，一眼看出话筒在谁那。
 */
export default function Stage({
  members,
  ownId,
  channelName,
  volumes,
  talkingIds,
  ownTalking,
  watchingId = 0,
  onWatch,
  onVolume,
}: Props) {
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
          // 自己：麦克风电平说了算；别人：语音帧说了算。服务器的 talking 只作兜底。
          const talking = m.talking || (me ? ownTalking : talkingIds.has(m.id));
          const sub = subline(m, me, talking);
          const watching = watchingId === m.id;
          const vol = volumes[memberKey(m)] ?? DEFAULT_GAIN;
          return (
            <div
              className={`member-tile${m.streaming ? ' is-streaming' : ''}${talking ? ' is-talking' : ''}`}
              key={`${m.uid}-${m.id}`}
            >
              {!me && (
                <VolumePopover
                  title={`${m.nickname} 的音量`}
                  value={vol}
                  onChange={(v) => onVolume(m, v)}
                  zeroHint={`听不到 ${m.nickname}`}
                >
                  <button
                    type="button"
                    className={`tile-vol${vol !== DEFAULT_GAIN ? ' is-set' : ''}${vol === 0 ? ' is-mute' : ''}`}
                    title={`${m.nickname} 的音量 ${gainPercent(vol)}%`}
                    aria-label={`${m.nickname} 的音量 ${gainPercent(vol)}%`}
                  >
                    {vol === 0 ? <IconVolumnSilent size="small" /> : <IconVolume1 size="small" />}
                  </button>
                </VolumePopover>
              )}
              <div className={`avatar-ring${talking ? ' is-talking' : ''}`}>
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
              ) : talking ? (
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
