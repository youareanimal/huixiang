import { Dropdown } from '@douyinfe/semi-ui';
import {
  IconExit,
  IconMicrophone,
  IconMicrophoneOff,
  IconVolume1,
  IconVolume2,
  IconVolumnSilent,
} from '@douyinfe/semi-icons';
import type { MicMode } from '../protocol';
import type { ShareKind } from '../stream';
import { gainPercent } from '../audio/gain';
import VolumePopover from './VolumePopover';
import AwayPopover from './AwayPopover';

const MIC_MODES: Array<{ key: MicMode; label: string; hint: string }> = [
  { key: 'auto', label: '自动', hint: '检测到你在说话才发送，安静时不占用带宽' },
  { key: 'open', label: '常开', hint: '麦克风一直发送' },
  { key: 'ptt', label: '按住说话', hint: '按住按钮或空格才发送' },
];

interface Props {
  micOn: boolean;
  micLevel: number;
  /** 麦克风增益，0–2。 */
  micGain: number;
  outputMuted: boolean;
  micMode: MicMode;
  pttHeld: boolean;
  channelName: string;
  clientCount: number;
  /** 正在共享什么；null 表示没有。 */
  sharing: ShareKind | null;
  shareViewers: number;
  onShareScreen: () => void;
  onShareCamera: () => void;
  onStopShare: () => void;
  onMicGain: (v: number) => void;
  onToggleInput: () => void;
  onToggleOutput: () => void;
  /** 自己是否暂离。 */
  away: boolean;
  /** 离开留言。 */
  awayMessage: string;
  onToggleAway: (away: boolean) => void;
  onSetAwayMessage: (message: string) => void;
  onMicMode: (mode: MicMode) => void;
  onPttHold: (held: boolean) => void;
  onDisconnect: () => void;
}

/**
 * 底部控制栏：左边是「你在哪」，中间是「你的话筒」，右边是「走」。
 * 说话方式分三档：自动（人声检测）/ 常开 / 按住说话，当前模式一眼可见。
 */
export default function ControlBar({
  micOn,
  micLevel,
  micGain,
  outputMuted,
  micMode,
  pttHeld,
  channelName,
  clientCount,
  sharing,
  shareViewers,
  onShareScreen,
  onShareCamera,
  onStopShare,
  onMicGain,
  onToggleInput,
  onToggleOutput,
  away,
  awayMessage,
  onToggleAway,
  onSetAwayMessage,
  onMicMode,
  onPttHold,
  onDisconnect,
}: Props) {
  const pttMode = micMode === 'ptt';
  const live = micOn && (!pttMode || pttHeld);

  return (
    <div className="control-bar">
      <div className="control-side">
        <div className="control-place">
          <span className={`dot${live ? '' : ' dot-idle'}`} />
          <span className="control-place-name" title={channelName}>
            {channelName}
          </span>
          <span className="control-place-sub">{clientCount} 人在线</span>
        </div>
      </div>

      <div className="control-center">
        <button
          type="button"
          className={`mic-btn${micOn ? '' : ' is-off'}`}
          onClick={onToggleInput}
          title={micOn ? '关掉麦克风' : '打开麦克风（会请求权限）'}
          aria-pressed={micOn}
        >
          {micOn ? <IconMicrophone /> : <IconMicrophoneOff />}
          <span>{micOn ? '麦克风已开' : '麦克风'}</span>
        </button>

        <VolumePopover
          title="麦克风音量"
          value={micGain}
          onChange={onMicGain}
          zeroHint="为 0 时对方听不到你"
        >
          <button
            type="button"
            className={`icon-btn vol-trigger${micGain !== 1 ? ' is-set' : ''}`}
            title={`麦克风音量 ${gainPercent(micGain)}%`}
            aria-label={`麦克风音量 ${gainPercent(micGain)}%`}
          >
            {micGain === 0 ? <IconVolumnSilent /> : <IconVolume1 />}
          </button>
        </VolumePopover>

        {micOn && pttMode && (
          <button
            type="button"
            className={`ptt-btn${pttHeld ? ' is-held' : ''}`}
            onMouseDown={() => onPttHold(true)}
            onMouseUp={() => onPttHold(false)}
            onMouseLeave={() => onPttHold(false)}
            onTouchStart={(e) => {
              e.preventDefault();
              onPttHold(true);
            }}
            onTouchEnd={() => onPttHold(false)}
          >
            {pttHeld ? '正在说话…' : '按住不放（空格）'}
          </button>
        )}

        {micOn && micMode !== 'ptt' && (
          <div className="level" title="麦克风音量" aria-hidden="true">
            <div className="level-fill" style={{ width: `${Math.min(100, micLevel * 180)}%` }} />
          </div>
        )}

        <div className="segment" role="group" aria-label="说话方式">
          {MIC_MODES.map((m) => (
            <button
              key={m.key}
              type="button"
              className={micMode === m.key ? 'is-on' : ''}
              title={m.hint}
              aria-pressed={micMode === m.key}
              onClick={() => micMode !== m.key && onMicMode(m.key)}
            >
              {m.label}
            </button>
          ))}
        </div>

        <div className="v-divider" />

        <button
          type="button"
          className="ghost-btn"
          onClick={onToggleOutput}
          title={outputMuted ? '打开声音' : '静音所有人'}
          aria-pressed={outputMuted}
        >
          {outputMuted ? <IconVolumnSilent /> : <IconVolume2 />}
          <span>{outputMuted ? '已静音' : '声音'}</span>
        </button>

        <AwayPopover
          away={away}
          awayMessage={awayMessage}
          onToggleAway={onToggleAway}
          onSetAwayMessage={onSetAwayMessage}
        >
          <button
            type="button"
            className={`ghost-btn away-btn${away ? ' is-on' : ''}`}
            title={away ? '离开中，点此修改暂离设置' : '设置暂离状态'}
            aria-pressed={away}
          >
            <span className={`away-dot${away ? ' is-on' : ''}`} aria-hidden="true" />
            <span>{away ? '离开中' : '暂离'}</span>
          </button>
        </AwayPopover>

        {sharing ? (
          <button type="button" className="ghost-btn is-live" onClick={onStopShare} title="停止直播">
            <span className="live-dot" aria-hidden="true" />
            <span>
              {sharing === 2 ? '屏幕直播中' : '摄像头直播中'}
              {shareViewers > 0 ? ` · ${shareViewers} 人` : ''}
            </span>
          </button>
        ) : (
          <Dropdown
            trigger="click"
            position="top"
            render={
              <Dropdown.Menu>
                <Dropdown.Item onClick={onShareScreen}>直播屏幕</Dropdown.Item>
                <Dropdown.Item onClick={onShareCamera}>直播摄像头</Dropdown.Item>
              </Dropdown.Menu>
            }
          >
            <button type="button" className="ghost-btn" title="把你的屏幕或者摄像头播给频道里的人">
              <span>直播</span>
            </button>
          </Dropdown>
        )}
      </div>

      <div className="control-side control-side-right">
        <button type="button" className="danger-btn" onClick={onDisconnect}>
          <IconExit />
          <span>离开</span>
        </button>
      </div>
    </div>
  );
}
