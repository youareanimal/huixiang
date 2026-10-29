import { Button, Tooltip, Typography } from '@douyinfe/semi-ui';
import {
  IconExit,
  IconMicrophone,
  IconMicrophoneOff,
  IconVolume2,
  IconVolumnSilent,
} from '@douyinfe/semi-icons';

const { Text } = Typography;

interface Props {
  micOn: boolean;
  micLevel: number;
  outputMuted: boolean;
  pttMode: boolean;
  pttHeld: boolean;
  channelName: string;
  clientCount: number;
  onToggleInput: () => void;
  onToggleOutput: () => void;
  onTogglePttMode: () => void;
  onPttHold: (held: boolean) => void;
  onDisconnect: () => void;
}

/** 底部控制条：麦克风（可切按住说话）、扬声器、当前频道、离开。 */
export default function ControlBar({
  micOn,
  micLevel,
  outputMuted,
  pttMode,
  pttHeld,
  channelName,
  clientCount,
  onToggleInput,
  onToggleOutput,
  onTogglePttMode,
  onPttHold,
  onDisconnect,
}: Props) {
  const live = micOn && (!pttMode || pttHeld);

  return (
    <div className="control-bar">
      <div className="control-left">
        <Tooltip content={micOn ? '关掉麦克风' : '打开麦克风（会请求权限）'}>
          <Button
            className={`control-btn${micOn ? ' control-btn-on' : ''}`}
            theme={micOn ? 'solid' : 'light'}
            type={micOn ? 'primary' : 'tertiary'}
            icon={micOn ? <IconMicrophone /> : <IconMicrophoneOff />}
            onClick={onToggleInput}
          >
            {micOn ? '麦克风已开' : '麦克风'}
          </Button>
        </Tooltip>

        {micOn && pttMode && (
          <button
            className={`ptt${pttHeld ? ' ptt-held' : ''}`}
            onMouseDown={() => onPttHold(true)}
            onMouseUp={() => onPttHold(false)}
            onMouseLeave={() => onPttHold(false)}
            onTouchStart={(e) => {
              e.preventDefault();
              onPttHold(true);
            }}
            onTouchEnd={() => onPttHold(false)}
          >
            {pttHeld ? '正在说话…' : '按住说话（或按住空格）'}
          </button>
        )}

        {micOn && !pttMode && (
          <div className="level" title="麦克风音量">
            <div className="level-fill" style={{ width: `${Math.min(100, micLevel * 180)}%` }} />
          </div>
        )}

        <Tooltip content={pttMode ? '改成一直开麦' : '改成按住说话'}>
          <Button theme="borderless" size="small" type={pttMode ? 'primary' : 'tertiary'} onClick={onTogglePttMode}>
            {pttMode ? '按住说话' : '常开'}
          </Button>
        </Tooltip>

        <Tooltip content={outputMuted ? '打开声音' : '静音所有人'}>
          <Button
            theme="borderless"
            type={outputMuted ? 'danger' : 'tertiary'}
            icon={outputMuted ? <IconVolumnSilent /> : <IconVolume2 />}
            onClick={onToggleOutput}
          >
            {outputMuted ? '已静音' : '声音'}
          </Button>
        </Tooltip>
      </div>

      <div className="control-center">
        <Text type="tertiary" size="small">
          {channelName} · {clientCount} 人在线
          {live && micOn ? ' · 正在发送' : ''}
        </Text>
      </div>

      <div className="control-right">
        <Button theme="borderless" type="danger" icon={<IconExit />} onClick={onDisconnect}>
          离开
        </Button>
      </div>
    </div>
  );
}
