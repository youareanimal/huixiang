// 采集用的 AudioWorklet：把麦克风的浮点采样攒成 20ms（960 采样）一块，转成 16 位整数发回主线程。
// AudioWorklet 的全局环境里没有 TS、没有 import，所以这是个纯 JS 文件。

class CaptureProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.block = new Float32Array(960);
    this.filled = 0;
    this.sending = true;
    this.port.onmessage = (e) => {
      if (e.data === 'mute') {
        this.sending = false;
        this.filled = 0;
      } else if (e.data === 'unmute') {
        this.sending = true;
      }
    };
  }

  process(inputs) {
    const input = inputs[0];
    const channel = input && input[0];
    if (!channel) return true;
    if (!this.sending) {
      this.filled = 0;
      return true;
    }

    let i = 0;
    while (i < channel.length) {
      const take = Math.min(960 - this.filled, channel.length - i);
      this.block.set(channel.subarray(i, i + take), this.filled);
      this.filled += take;
      i += take;

      if (this.filled === 960) {
        const pcm = new Int16Array(960);
        for (let k = 0; k < 960; k++) {
          const v = this.block[k] < -1 ? -1 : this.block[k] > 1 ? 1 : this.block[k];
          pcm[k] = v < 0 ? v * 0x8000 : v * 0x7fff;
        }
        this.port.postMessage(pcm.buffer, [pcm.buffer]);
        this.filled = 0;
      }
    }
    return true;
  }
}

registerProcessor('huixiang-capture', CaptureProcessor);
