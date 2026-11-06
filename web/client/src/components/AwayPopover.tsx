import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { Input, Popover, Switch } from '@douyinfe/semi-ui';

interface Props {
  away: boolean;
  awayMessage: string;
  onToggleAway: (away: boolean) => void;
  onSetAwayMessage: (message: string) => void;
  children: ReactNode;
}

/**
 * 暂离弹层：底栏按钮点开，开关切换暂离，开启时可填离开留言。
 * 和音量弹层一样，触发器只是 children，样式与逻辑都收在这里。
 */
export default function AwayPopover({ away, awayMessage, onToggleAway, onSetAwayMessage, children }: Props) {
  const [msg, setMsg] = useState(awayMessage);

  useEffect(() => {
    setMsg(awayMessage);
  }, [awayMessage]);

  const save = () => {
    if (away) onSetAwayMessage(msg.trim());
  };

  return (
    <Popover
      trigger="click"
      position="top"
      showArrow
      content={
        <div className="away-pop">
          <div className="away-pop-head">
            <span className="away-pop-title">暂离（Away）</span>
            <Switch checked={away} onChange={onToggleAway} />
          </div>
          <div className="away-pop-desc">开启后别人会看到你处于「离开」状态</div>
          {away && (
            <Input
              className="away-pop-input"
              value={msg}
              onChange={setMsg}
              maxLength={120}
              placeholder="离开留言（可选）"
              onEnterPress={save}
              onBlur={save}
            />
          )}
        </div>
      }
    >
      {children}
    </Popover>
  );
}
