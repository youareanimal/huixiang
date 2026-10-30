import type { ReactNode } from 'react';
import { Popover, Slider } from '@douyinfe/semi-ui';
import { clampGain, gainPercent } from '../audio/gain';

interface Props {
  /** 调的是谁：麦克风叫「麦克风音量」，成员就叫昵称。 */
  title: string;
  /** 0–2 的增益。 */
  value: number;
  onChange: (v: number) => void;
  /** 拖到 0 时的提醒，说明「听不到」的具体后果。 */
  zeroHint?: string;
  children: ReactNode;
}

/**
 * 音量弹层：麦克风和每个成员共用一个滑块，刻度、文案、复原动作都只写一遍。
 * 0 = 听不到，100% = 原始电平，200% = +6dB。
 */
export default function VolumePopover({ title, value, onChange, zeroHint, children }: Props) {
  const pct = gainPercent(value);
  const hint =
    pct === 0 ? (zeroHint ?? '0% 等于静音') : pct > 100 ? '超过 100% 会连底噪一起放大' : '100% 是原始音量';

  return (
    <Popover
      trigger="click"
      position="top"
      showArrow
      content={
        <div className="vol-pop">
          <div className="vol-pop-head">
            <span className="vol-pop-title" title={title}>
              {title}
            </span>
            <b className="vol-pop-value">{pct}%</b>
          </div>
          <Slider
            className="vol-slider"
            value={pct}
            min={0}
            max={200}
            step={5}
            tooltipFormatter={(v: number | string) => `${v}%`}
            onChange={(v) => onChange(clampGain(Number(v) / 100))}
          />
          <div className="vol-pop-foot">
            <span className={`vol-pop-hint${pct === 0 ? ' is-zero' : ''}`}>{hint}</span>
            {pct !== 100 && (
              <button type="button" className="vol-reset" onClick={() => onChange(1)}>
                复原
              </button>
            )}
          </div>
        </div>
      }
    >
      {children}
    </Popover>
  );
}
