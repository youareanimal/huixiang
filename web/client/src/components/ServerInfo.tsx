import { useEffect, useState } from 'react';
import { IconClose, IconGlobe } from '@douyinfe/semi-icons';
import { Toast } from '@douyinfe/semi-ui';
import type { ServerInfo } from '../protocol';

interface Props {
  open: boolean;
  info: ServerInfo | null;
  welcome: string;
  address: string;
  /** 真实说话人数（网关按 type==0 数的），比 clientsonline（含查询客户端）更贴近直觉。 */
  clientCount: number;
  channelCount: number;
  onClose: () => void;
}

/** 秒数 → 「13 天 4 小时」这种能读的话。 */
function humanUptime(seconds: number): string {
  const d = Math.floor(seconds / 86400);
  const h = Math.floor((seconds % 86400) / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  if (d > 0) return `${d} 天 ${h} 小时`;
  if (h > 0) return `${h} 小时 ${m} 分钟`;
  return `${m} 分钟`;
}

/** 欢迎语里常带 BBCode，网页上按纯文本读。 */
function stripBBCode(text: string): string {
  return text.replace(/\[\/?[a-z0-9*]+(?:=[^\]]*)?\]/gi, '').trim();
}

export default function ServerInfoDrawer({
  open,
  info,
  welcome,
  address,
  clientCount,
  channelCount,
  onClose,
}: Props) {
  const [bannerBroken, setBannerBroken] = useState(false);
  const [liveUptime, setLiveUptime] = useState<number | null>(null);

  // 换服务器 / 重开后横幅重试一次，别一直停在「图挂了」
  useEffect(() => {
    if (open) setBannerBroken(false);
  }, [open, info?.bannerUrl]);

  // 运行时间：以收到变量的时刻为基准往前推，面板开着时它自己会走。
  // Hook 必须无条件调用，所以放在提前 return 之前。
  useEffect(() => {
    if (!open || !info?.uptime) {
      setLiveUptime(null);
      return;
    }
    const base = info.uptime + (info.receivedAt ? (Date.now() - info.receivedAt) / 1000 : 0);
    setLiveUptime(base);
    const timer = window.setInterval(() => setLiveUptime((v) => (v ?? base) + 60), 60_000);
    return () => window.clearInterval(timer);
  }, [open, info?.uptime, info?.receivedAt]);

  // Esc 也能关，和别的弹层习惯一致
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;

  const usersOnline = clientCount || info?.clientsOnline || 0;
  const channels = info?.channelsOnline || channelCount;
  const maxClients = info?.maxClients ?? null;
  const intro = stripBBCode(welcome);
  const banner = info?.bannerUrl && !bannerBroken ? info.bannerUrl : null;
  // 版本号后面那个 [Build: 1790080330] 对用户没意义，和桌面端一样抹掉
  const version = info?.version?.replace(/\[Build:[^\]]*\]/gi, '').trim() || null;
  const versionLine = [info?.platform, version].filter(Boolean).join(' · ');
  const site = info?.hostbuttonUrl ?? null;

  const rows: Array<[string, string]> = [];
  rows.push(['用户', maxClients != null ? `${usersOnline} / ${maxClients}` : String(usersOnline)]);
  rows.push(['频道', String(channels)]);
  if (liveUptime != null) rows.push(['运行时间', humanUptime(liveUptime)]);
  if (info?.createdAt) {
    rows.push(['创建于', new Date(info.createdAt * 1000).toLocaleDateString('zh-CN')]);
  }

  const copyAddress = async () => {
    try {
      await navigator.clipboard.writeText(address);
      Toast.success('地址已复制');
    } catch {
      Toast.error('复制失败，浏览器不给剪贴板权限');
    }
  };

  return (
    <>
      <div className="info-scrim" role="presentation" onClick={onClose} />
      <aside className="info-drawer is-open" aria-label="服务器信息">
        <div className="info-head">
          <span className="info-title">服务器信息</span>
          <button type="button" className="icon-btn" onClick={onClose} aria-label="关闭">
            <IconClose size="small" />
          </button>
        </div>

        <div className="info-body">
          {banner && (
            <a
              className="info-banner"
              href={info?.bannerLink || undefined}
              target="_blank"
              rel="noreferrer noopener"
              title={info?.bannerLink ? '打开横幅指向的页面' : undefined}
            >
              <img src={banner} alt="服务器横幅" onError={() => setBannerBroken(true)} />
            </a>
          )}

          {intro && <p className="info-welcome">{intro}</p>}

          <dl className="info-rows">
            {rows.map(([k, v]) => (
              <div className="info-row" key={k}>
                <dt>{k}</dt>
                <dd>{v}</dd>
              </div>
            ))}
            <div className="info-row">
              <dt>地址</dt>
              <dd>
                <span className="info-addr">{address}</span>
                <button type="button" className="field-link" onClick={copyAddress}>
                  复制
                </button>
              </dd>
            </div>
          </dl>

          {site && (
            <a className="info-site" href={site} target="_blank" rel="noreferrer noopener">
              {info?.hostbuttonTooltip || site}
            </a>
          )}

          {versionLine && (
            <p className="info-version">
              <IconGlobe size="small" />
              {versionLine}
            </p>
          )}
        </div>
      </aside>
    </>
  );
}
