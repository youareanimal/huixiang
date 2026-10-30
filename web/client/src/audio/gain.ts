// 音量刻度：上行麦克风和下行每个人都用同一套，免得两个滑块的范围各说各话。
// 1 = 原始电平，2 = +6dB。再往上只会削波和放大底噪，所以封在 2。

export const MAX_GAIN = 2;
export const DEFAULT_GAIN = 1;

export const clampGain = (v: number) => (Number.isFinite(v) ? Math.min(MAX_GAIN, Math.max(0, v)) : DEFAULT_GAIN);

/** 滑块的显示值：0–200 的整数百分比。 */
export const gainPercent = (v: number) => Math.round(clampGain(v) * 100);
