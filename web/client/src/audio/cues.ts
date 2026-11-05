// 频道成员进出的提示音：用 Web Audio 现场合成，不依赖任何音频文件。
// 进入：两个上行音（E5 → A5）；离开：两个下行音（A5 → D5）。短促、不刺耳。
//
// 浏览器要求「用户手势」之后才允许出声，所以 AudioContext 懒创建；进服/点按钮
// 本身已经是一次手势，通常能正常播放。若仍是 suspended，就尝试 resume，失败静默。

let ctx: AudioContext | null = null;

function ensureCtx(): AudioContext | null {
  if (typeof window === 'undefined') return null;
  if (!ctx) {
    const Ctor: typeof AudioContext | undefined =
      (window as any).AudioContext ?? (window as any).webkitAudioContext;
    if (!Ctor) return null;
    ctx = new Ctor();
  }
  if (ctx.state === 'suspended') void ctx.resume().catch(() => {});
  return ctx;
}

/** 叠一个正弦音。start 是 AudioContext 时间轴上的起点，dur 是时长，gain 是峰值音量。 */
function tone(freq: number, start: number, dur: number, gain: number) {
  const c = ctx!;
  const osc = c.createOscillator();
  const g = c.createGain();
  osc.type = 'sine';
  osc.frequency.value = freq;
  // 简单包络：快速起音 + 指数衰减，避免「咔」的爆音
  g.gain.setValueAtTime(0.0001, start);
  g.gain.exponentialRampToValueAtTime(gain, start + 0.015);
  g.gain.exponentialRampToValueAtTime(0.0001, start + dur);
  osc.connect(g).connect(c.destination);
  osc.start(start);
  osc.stop(start + dur + 0.02);
}

/** 有人进入频道。 */
export function playJoinCue() {
  const c = ensureCtx();
  if (!c) return;
  const t = c.currentTime;
  tone(659.25, t, 0.12, 0.16); // E5
  tone(880.0, t + 0.11, 0.16, 0.16); // A5
}

/** 有人离开频道。 */
export function playLeaveCue() {
  const c = ensureCtx();
  if (!c) return;
  const t = c.currentTime;
  tone(880.0, t, 0.12, 0.14); // A5
  tone(587.33, t + 0.11, 0.16, 0.14); // D5
}
