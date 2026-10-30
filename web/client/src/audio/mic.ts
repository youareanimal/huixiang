// 上行语音：麦克风 → AudioWorklet 攒 20ms → WebCodecs 编成 Opus → 交给网关。
// 编码在浏览器做，服务端一个字节都不用碰，也就没有转码延迟。

import workletUrl from './capture-worklet.js?url';
import { DEFAULT_GAIN, clampGain } from './gain';

const AudioEncoderCtor: any = (globalThis as any).AudioEncoder;
const AudioDataCtor: any = (globalThis as any).AudioData;

export const micCodecAvailable = () => !!AudioEncoderCtor && !!AudioDataCtor;

export class MicSender {
  private ctx: AudioContext | null = null;
  private stream: MediaStream | null = null;
  private node: AudioWorkletNode | null = null;
  private encoder: any = null;
  private source: MediaStreamAudioSourceNode | null = null;
  /** 采集增益：在源和工作节点之间，改的是编码前的 PCM。 */
  private input: GainNode | null = null;
  private sink: GainNode | null = null;
  private volume = DEFAULT_GAIN;
  private ts = 0;
  private sending = true;

  /** 每 20ms 一帧 Opus 交给 [onFrame]；空数组表示这一帧没有声音可发。 */
  async start(onFrame: (opus: Uint8Array) => void, onLevel?: (level: number) => void): Promise<void> {
    this.stop();

    this.stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        channelCount: 1,
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true,
      },
    });

    const Ctx: any = (globalThis as any).AudioContext;
    const ctx: AudioContext = new Ctx({ sampleRate: 48000, latencyHint: 'interactive' });
    this.ctx = ctx;
    await ctx.audioWorklet.addModule(workletUrl);

    this.source = ctx.createMediaStreamSource(this.stream);
    this.input = ctx.createGain();
    this.input.gain.value = this.volume;
    this.node = new AudioWorkletNode(ctx, 'huixiang-capture');
    // worklet 只有连到 destination 才会被处理；中间垫一个 0 音量的增益，免得麦克风从扬声器放出来
    this.sink = ctx.createGain();
    this.sink.gain.value = 0;
    this.source.connect(this.input);
    this.input.connect(this.node);
    this.node.connect(this.sink);
    this.sink.connect(ctx.destination);

    this.encoder = new AudioEncoderCtor({
      output: (chunk: any) => {
        const bytes = new Uint8Array(chunk.byteLength);
        chunk.copyTo(bytes);
        onFrame(bytes);
      },
      error: () => {
        // 编码器出错就静音，不要让它反复抛
        this.sending = false;
      },
    });
    this.encoder.configure({
      codec: 'opus',
      sampleRate: 48000,
      numberOfChannels: 1,
      bitrate: 40_000,
    });

    this.ts = 0;
    this.node.port.onmessage = (e) => {
      const pcm = e.data as ArrayBuffer;
      if (onLevel) {
        const view = new Int16Array(pcm);
        let peak = 0;
        for (let i = 0; i < view.length; i += 16) {
          const v = Math.abs(view[i]) / 32768;
          if (v > peak) peak = v;
        }
        onLevel(peak);
      }
      if (!this.sending) return;
      try {
        const data = new AudioDataCtor({
          format: 's16',
          sampleRate: 48000,
          numberOfFrames: 960,
          numberOfChannels: 1,
          timestamp: this.ts,
          data: pcm,
        });
        this.encoder.encode(data);
        data.close();
        this.ts += 20_000;
      } catch {
        this.sending = false;
      }
    };

    if (ctx.state === 'suspended') await ctx.resume();
    this.setSending(true);
  }

  /** 关掉采集但不释放麦克风：按住说话松开时用这个，省掉反复申请权限的开销。 */
  setSending(on: boolean) {
    this.sending = on;
    this.node?.port.postMessage(on ? 'unmute' : 'mute');
  }

  /**
   * 上行音量：0 = 对方听不到，1 = 原始电平，2 = 放大一倍。
   * 改的是编码前的 PCM，所以对方听到的就是这个音量，也连带影响自己的电平条。
   */
  setVolume(v: number) {
    this.volume = clampGain(v);
    if (this.input && this.ctx) {
      // 直接赋值会有「咔」的一下，20ms 斜坡过去
      this.input.gain.setTargetAtTime(this.volume, this.ctx.currentTime, 0.02);
    }
  }

  get active() {
    return this.sending;
  }

  stop() {
    try {
      this.node?.port.postMessage('mute');
      this.node?.disconnect();
      this.source?.disconnect();
      this.input?.disconnect();
      this.sink?.disconnect();
    } catch {
      // 已经断了
    }
    this.node = null;
    this.source = null;
    this.input = null;
    this.sink = null;
    try {
      this.encoder?.close();
    } catch {
      // 已经关了
    }
    this.encoder = null;
    this.stream?.getTracks().forEach((t) => t.stop());
    this.stream = null;
    void this.ctx?.close();
    this.ctx = null;
  }
}
