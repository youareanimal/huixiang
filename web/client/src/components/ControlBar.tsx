import { Dropdown } from '@douyinfe/semi-ui';
import {
  IconExit,
  IconMicrophone,
  IconMicrophoneOff,
  IconVolume1,
  IconVolume2,
  IconVolumnSilent,
} from '@douyinfe/semi-icons';
import type { ShareKind } from '../stream';
import { gainPercent } from '../audio/gain';
import VolumePopover from './VolumePopover';

interface Props {
  micOn: boolean;
  micLevel: number;
  /** 麦克风增益，0–2。 */
  micGain: number;
  outputMuted: boolean;
  pttMode: boolean;
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
  onTogglePttMode: () => void;
  onPttHold: (held: boolean) => void;
  onDisconnect: () => void;
}

/**
 * 底部控制栏：左边是「你在哪」，中间是「你的话筒」，右边是「走」。
 * 常开 / 按住说话改成两段式，当前模式一眼可见，不会把「点它会变成什么」当文案。
 */
export default function ControlBar({
  micOn,
  micLevel,
  micGain,
  outputMuted,
  pttMode,
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
  onTogglePttMode,
  onPttHold,
  onDisconnect,
}: Props) {
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

        {micOn && !pttMode && (
          <div className="level" title="麦克风音量" aria-hidden="true">
            <div className="level-fill" style={{ width: `${Math.min(100, micLevel * 180)}%` }} />
          </div>
        )}

        <div className="segment" role="group" aria-label="说话方式">
          <button
            type="button"
            className={pttMode ? '' : 'is-on'}
            onClick={() => pttMode && onTogglePttMode()}
          >
            常开
          </button>
          <button
            type="button"
            className={pttMode ? 'is-on' : ''}
            onClick={() => !pttMode && onTogglePttMode()}
          >
            按住说话
          </button>
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
