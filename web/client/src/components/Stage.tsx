import { Dropdown } from '@douyinfe/semi-ui';
import { IconVolume1, IconVolumnSilent, IconMicrophoneOff, IconMore } from '@douyinfe/semi-icons';
import type { Member } from '../protocol';
import { memberKey } from '../protocol';
import { DEFAULT_GAIN, gainPercent } from '../audio/gain';
import MonkeyCharacter from './MonkeyCharacter';
import VolumePopover from './VolumePopover';

interface Props {
  members: Member[];
  ownId: number;
  channelName: string;
  volumes: Record<string, number>;
  talkingIds: ReadonlySet<number>;
  ownTalking: boolean;
  micOn: boolean;
  ownOutputMuted: boolean;
  watchingId?: number;
  onWatch?: (clientId: number) => void;
  onVolume: (member: Member, v: number) => void;
  onKick?: (member: Member) => void;
  onBan?: (member: Member) => void;
}

/** 状态文案和猴子的动作使用同一份成员状态，避免 UI 不一致。 */
function subline(
  member: Member,
  me: boolean,
  talking: boolean,
  inputMuted: boolean,
  outputMuted: boolean,
): { text: string; tone: 'talk' | 'quiet' } {
  if (talking) return { text: me ? '正在说话 · 我' : '正在说话', tone: 'talk' };
  if (inputMuted && outputMuted) return { text: '嘴和耳朵都捂住了', tone: 'quiet' };
  if (inputMuted) return { text: '已闭麦 · 捂住嘴', tone: 'quiet' };
  if (outputMuted) return { text: '扬声器关闭 · 捂住耳朵', tone: 'quiet' };
  if (member.away) return { text: '离开', tone: 'quiet' };
  if (me) return { text: '我', tone: 'quiet' };
  return { text: member.channelGroup || '', tone: 'quiet' };
}

export default function Stage({
  members,
  ownId,
  channelName,
  volumes,
  talkingIds,
  ownTalking,
  micOn,
  ownOutputMuted,
  watchingId = 0,
  onWatch,
  onVolume,
  onKick,
  onBan,
}: Props) {
  const memberMenu = (m: Member) => [
    ...(onKick ? [{ node: 'item' as const, name: '踢出成员', onClick: () => onKick(m) }] : []),
    ...(onBan
      ? [{ node: 'item' as const, name: '封禁成员', type: 'danger' as const, onClick: () => onBan(m) }]
      : []),
  ];

  return (
    <div className="stage">
      <div className="monkey-scene" aria-label={channelName + ' 的语音成员舞台'}>
        <svg className="monkey-branch-art" viewBox="0 0 1200 128" preserveAspectRatio="none" aria-hidden="true">
          <path className="branch-trunk" d="M-35 132 C9 94 18 51 43 -18 L119 -18 C91 25 91 50 150 63Z" />
          <path className="branch-main-outline" d="M28 54 C205 39 347 58 503 48 C704 35 903 43 1235 17" />
          <path className="branch-main" d="M28 54 C205 39 347 58 503 48 C704 35 903 43 1235 17" />
          <path className="branch-bark" d="M72 48 C220 42 328 55 452 48 M530 45 C680 37 806 42 930 34 M968 31 C1050 28 1120 22 1192 19" />
          <path className="branch-bark branch-bark-light" d="M120 39 C244 35 342 47 420 41 M585 36 C710 29 818 34 910 28" />
          <g className="branch-leaves">
            <path d="M92 20 C52 -10 41 -43 68 -56 C105 -48 119 -14 92 20Z" />
            <path d="M119 13 C122 -31 153 -55 179 -34 C177 2 148 23 119 13Z" />
            <path d="M252 35 C220 -2 223 -36 256 -43 C283 -18 282 14 252 35Z" />
            <path d="M280 34 C297 -8 334 -20 354 6 C339 36 310 48 280 34Z" />
            <path d="M450 42 C426 8 439 -28 472 -29 C496 1 485 32 450 42Z" />
            <path d="M481 38 C506 -1 544 -5 559 25 C535 51 507 54 481 38Z" />
            <path d="M690 35 C670 1 685 -31 718 -32 C738 -1 725 27 690 35Z" />
            <path d="M719 34 C746 -5 781 -7 800 22 C779 48 747 52 719 34Z" />
            <path d="M972 20 C947 -14 963 -47 996 -47 C1018 -17 1007 11 972 20Z" />
            <path d="M1001 19 C1028 -20 1065 -18 1080 13 C1056 40 1027 42 1001 19Z" />
            <path d="M1124 10 C1105 -23 1121 -52 1152 -50 C1173 -22 1158 5 1124 10Z" />
          </g>
        </svg>

        {members.length === 0 ? (
          <div className="stage-empty">
            <span className="stage-empty-title">「{channelName}」还没有人</span>
            <span>等你进来，树梢就会多一只猴子</span>
          </div>
        ) : (
          <div className="stage-grid">
            {members.map((m, index) => {
              const me = m.id === ownId;
              const inputMuted = me ? !micOn : m.inputMuted;
              const outputMuted = me ? ownOutputMuted : m.outputMuted;
              const talking = !inputMuted && (me ? ownTalking : talkingIds.has(m.id));
              const sub = subline(m, me, talking, inputMuted, outputMuted);
              const watching = watchingId === m.id;
              const vol = volumes[memberKey(m)] ?? DEFAULT_GAIN;
              const manageable = !me && (!!onKick || !!onBan);

              const tile = (
                <div
                  className={
                    'member-tile monkey-member' +
                    (m.streaming ? ' is-streaming' : '') +
                    (talking ? ' is-talking' : '')
                  }
                  key={m.uid + '-' + m.id}
                >
                  {manageable && (
                    <span className="tile-more" onClick={(e) => e.stopPropagation()}>
                      <Dropdown trigger="click" position="bottomRight" menu={memberMenu(m)}>
                        <button type="button" className="tile-more-btn" aria-label={m.nickname + ' 的成员操作'}>
                          <IconMore size="small" />
                        </button>
                      </Dropdown>
                    </span>
                  )}

                  {!me && (
                    <VolumePopover
                      title={m.nickname + ' 的音量'}
                      value={vol}
                      onChange={(v) => onVolume(m, v)}
                      zeroHint={'听不到 ' + m.nickname}
                    >
                      <button
                        type="button"
                        className={
                          'tile-vol' +
                          (vol !== DEFAULT_GAIN ? ' is-set' : '') +
                          (vol === 0 ? ' is-mute' : '')
                        }
                        title={m.nickname + ' 的音量 ' + gainPercent(vol) + '%'}
                        aria-label={m.nickname + ' 的音量 ' + gainPercent(vol) + '%'}
                      >
                        {vol === 0 ? <IconVolumnSilent size="small" /> : <IconVolume1 size="small" />}
                      </button>
                    </VolumePopover>
                  )}

                  <MonkeyCharacter
                    nickname={m.nickname}
                    micOpen={!inputMuted}
                    inputMuted={inputMuted}
                    outputMuted={outputMuted}
                    talking={talking}
                    variant={index}
                  />

                  <div className="tile-name-row">
                    <span className="tile-name" title={m.nickname}>
                      {m.nickname}
                    </span>
                    {m.streaming && !me && onWatch && (
                      <button
                        type="button"
                        className={'watch-btn' + (watching ? ' is-on' : '')}
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
                    <div className={'tile-sub' + (sub.tone === 'talk' ? ' is-talking' : '')}>
                      {inputMuted && <IconMicrophoneOff size="small" />}
                      {outputMuted && <IconVolumnSilent size="small" />}
                      <span>{sub.text}</span>
                    </div>
                  ) : null}
                </div>
              );

              if (!manageable) return tile;
              return (
                <Dropdown key={m.uid + '-' + m.id} trigger="contextMenu" position="bottomLeft" menu={memberMenu(m)}>
                  {tile}
                </Dropdown>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
