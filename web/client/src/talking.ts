// 谁在说话：听语音帧，不看服务器。
//
// 服务器的 notifytalkstatuschange 不是每个服务器都发（官服默认就很少发），所以 `member.talking`
// 经常一直是 false，头像永远不亮。但语音帧一定会到本地——能听到他，就说明他在说。
// 于是这里按「最后一帧的时间」自己算，断流超过 holdMs 就熄灯，和耳朵里的声音同步。

/** 断流多久算说完。帧是 20ms 一帧，600ms 给了网络抖动和句间停顿的余量。 */
const HOLD_MS = 600;

/** 多久扫一次过期。比一帧粗、比人眼快，够了。 */
const TICK_MS = 120;

export class TalkingTracker {
  /** 每个人最后一帧的时间戳。 */
  private last = new Map<number, number>();
  /** 当前亮着的（对外唯一的真相，变化时才回调）。 */
  private live = new Set<number>();
  private timer: number | null = null;

  constructor(
    private readonly onChange: (live: ReadonlySet<number>) => void,
    private readonly holdMs = HOLD_MS,
  ) {}

  /** 收到一帧非空语音：这个人开口了。 */
  heard(clientId: number) {
    if (clientId <= 0) return;
    this.last.set(clientId, performance.now());
    if (!this.live.has(clientId)) {
      this.live.add(clientId);
      this.emit();
    }
    if (this.timer === null) this.timer = window.setInterval(() => this.tick(), TICK_MS);
  }

  /** 收到空帧：这个人明确说完了，不用等超时。 */
  quiet(clientId: number) {
    this.last.delete(clientId);
    if (this.live.delete(clientId)) this.emit();
  }

  /** 换服务器、断开、重连：全部熄灯。 */
  reset() {
    this.last.clear();
    if (this.timer !== null) {
      window.clearInterval(this.timer);
      this.timer = null;
    }
    if (this.live.size > 0) {
      this.live.clear();
      this.emit();
    }
  }

  private tick() {
    const now = performance.now();
    let changed = false;
    for (const [id, at] of this.last) {
      if (now - at > this.holdMs) {
        this.last.delete(id);
        if (this.live.delete(id)) changed = true;
      }
    }
    if (changed) this.emit();
    // 没人说话了就别空转；有残留的半衰条目也一并收摊
    if (this.live.size === 0 && this.last.size === 0 && this.timer !== null) {
      window.clearInterval(this.timer);
      this.timer = null;
    }
  }

  private emit() {
    this.onChange(new Set(this.live));
  }
}
