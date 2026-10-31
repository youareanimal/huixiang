import { useCallback, useEffect, useRef, useState } from 'react';
import { IconVolume2, IconVolumnSilent } from '@douyinfe/semi-icons';
import type { StreamState } from '../stream';

interface Props {
  stream: StreamState;
  /** 正在看的人叫什么（拿不到就显示 id）。 */
  streamerName: string;
  ownName: string;
  onStopShare: () => void;
  onStopWatch: () => void;
}

/**
 * 直播的画面，直接占掉上半部分（成员舞台那一块），不再另外挤出一条面板。
 *
 * 一块 <video> 换 srcObject 就够；自动播放要过浏览器那道关，先试有声播放，被拦下来再退回静音
 * （人可以从静音键上开回来）。
 *
 * 全屏整的是容器而不是 <video>：视频自己全屏的话工具条就跟着没了，人连「退出」都没得点。
 */
export default function StreamPanel({ stream, streamerName, ownName, onStopShare, onStopWatch }: Props) {
  const stageRef = useRef<HTMLDivElement>(null);
  const videoRef = useRef<HTMLVideoElement>(null);
  const watching = stream.watching > 0;
  const sharing = stream.sharing !== null;
  const media = watching ? stream.remote : stream.local;
  // 自己共享时绝不回声，所以自己这一侧永远静音
  const [muted, setMuted] = useState(sharing);
  const [fullscreen, setFullscreen] = useState(false);

  useEffect(() => {
    setMuted(sharing);
  }, [sharing]);

  // 全屏可能被 Esc、被浏览器自己退掉，所以状态以 document 为准
  useEffect(() => {
    const onChange = () => setFullscreen(document.fullscreenElement === stageRef.current);
    document.addEventListener('fullscreenchange', onChange);
    return () => document.removeEventListener('fullscreenchange', onChange);
  }, []);

  const toggleFullscreen = useCallback(() => {
    const el = stageRef.current;
    if (!el) return;
    if (document.fullscreenElement) {
      void document.exitFullscreen().catch(() => undefined);
      return;
    }
    // 某些环境（iframe 没有 allowfullscreen、或者浏览器不支持）会直接抛，退回去就好
    void el.requestFullscreen?.().catch((e) => console.info('[stream] 全屏没打开', e));
  }, []);

  useEffect(() => {
    const v = videoRef.current;
    if (!v) return;
    if (v.srcObject !== media) v.srcObject = media;
    if (!media) return;
    const play = async () => {
      try {
        await v.play();
      } catch {
        // 有声音的自动播放被拦：静音再试一次，人可以从键上开回来
        v.muted = true;
        setMuted(true);
        await v.play().catch(() => undefined);
      }
    };
    void play();
  }, [media]);

  if (!watching && !sharing) return null;

  const title = watching ? `${streamerName} 的直播` : `${ownName} 的${stream.sharing === 2 ? '屏幕' : '摄像头'}`;
  const subtitle = watching
    ? stream.remoteStatus === 'connecting'
      ? '正在连接…'
      : stream.remoteStatus === 'paused'
        ? '对方暂停了'
        : stream.remoteStatus === 'failed'
          ? '连接失败'
          : stream.remoteStatus === 'live'
            ? ''
            : '等待画面…'
    : !stream.live
      ? '正在开播…'
      : stream.viewers > 0
        ? `${stream.viewers} 人在看`
        : '还没有人看';

  return (
    <div className="stream-stage" ref={stageRef}>
      <video
        ref={videoRef}
        autoPlay
        playsInline
        muted={muted}
        onDoubleClick={toggleFullscreen}
      />

      {/* 还没出画面时给一句话，别让人对着一块黑屏猜 */}
      {watching && stream.remoteStatus !== 'live' && (
        <div className="stream-hint">{subtitle || '等待画面…'}</div>
      )}

      <div className="stream-bar">
        <span className={`live-dot${stream.live || stream.remoteStatus === 'live' ? '' : ' is-idle'}`} aria-hidden="true" />
        <span className="stream-title" title={title}>
          {title}
        </span>
        {subtitle && <span className="stream-sub">{subtitle}</span>}
        <span className="stream-spacer" />
        <button
          type="button"
          className="stream-btn"
          onClick={toggleFullscreen}
          title={fullscreen ? '退出全屏（也可以按 Esc）' : '全屏看（双击画面也行）'}
        >
          {fullscreen ? '退出全屏' : '全屏'}
        </button>
        {watching && (
          <button
            type="button"
            className="stream-btn"
            onClick={() => {
              const v = videoRef.current;
              const next = !muted;
              if (v) v.muted = next;
              setMuted(next);
            }}
            title={muted ? '打开声音' : '静音'}
            aria-pressed={muted}
          >
            {muted ? <IconVolumnSilent /> : <IconVolume2 />}
          </button>
        )}
        <button type="button" className="stream-btn" onClick={watching ? onStopWatch : onStopShare}>
          {watching ? '关掉' : '停止直播'}
        </button>
      </div>
    </div>
  );
}
