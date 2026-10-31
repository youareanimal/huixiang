import { useEffect, useRef, useState } from 'react';
import { IconGlobe, IconUserGroup } from '@douyinfe/semi-icons';
import { Toast } from '@douyinfe/semi-ui';
import { accountErrorText } from '../account';
import type { AccountState, ContactsMessage, ServerInfo } from '../protocol';
import UserAvatar from './UserAvatar';

interface Props {
  info: ServerInfo | null;
  welcome: string;
  address: string;
  /** 真实说话人数（网关按 type==0 数的），比 clientsonline（含查询客户端）更贴近直觉。 */
  clientCount: number;
  channelCount: number;
  account: AccountState;
  friends: ContactsMessage;
  onAccountLogout: () => void;
  onAccountRefresh: () => void;
  onAvatarChange: (file: File) => void;
  onAvatarRemove: () => void;
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

/**
 * 右栏：服务器自己报的家底，常驻显示。
 * 没有「关掉」这个动作——它就是这一栏的内容，和左边的频道列表对称。
 */
export default function ServerInfoPanel({
  info,
  welcome,
  address,
  clientCount,
  channelCount,
  account,
  friends,
  onAccountLogout,
  onAccountRefresh,
  onAvatarChange,
  onAvatarRemove,
}: Props) {
  const [bannerBroken, setBannerBroken] = useState(false);
  const [liveUptime, setLiveUptime] = useState<number | null>(null);
  const avatarInput = useRef<HTMLInputElement>(null);
  const signedIn = account.phase === 'signedIn';

  // 换了横幅地址就重试一次，别一直停在「图挂了」
  useEffect(() => {
    setBannerBroken(false);
  }, [info?.bannerUrl]);

  // 运行时间：以收到变量的时刻为基准往前推，开着页面它自己会走
  useEffect(() => {
    if (!info?.uptime) {
      setLiveUptime(null);
      return;
    }
    const base = info.uptime + (info.receivedAt ? (Date.now() - info.receivedAt) / 1000 : 0);
    setLiveUptime(base);
    const timer = window.setInterval(() => setLiveUptime((v) => (v ?? base) + 60), 60_000);
    return () => window.clearInterval(timer);
  }, [info?.uptime, info?.receivedAt]);

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
    <aside className="info-panel" aria-label="服务器信息">
      <div className="info-head">
        <span className="info-title">服务器信息</span>
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

        <section className="info-account" aria-label="我的账号">
          <div className="info-account-head">
            <UserAvatar
              name={signedIn ? account.username || account.email : '?'}
              sources={signedIn && account.avatarUrl ? [account.avatarUrl] : []}
              colorKey={signedIn ? account.username || account.email : 'guest'}
              className="account-avatar"
              label={signedIn ? `${account.username} 的头像` : undefined}
            />
            <div className="info-account-id">
              <span className="info-account-name">
                {signedIn ? account.username || 'myTeamSpeak 账号' : '未登录 myTeamSpeak'}
              </span>
              <span className="info-account-sub">
                {signedIn ? account.email || '已登录' : '在连接页登录后可同步好友与头像'}
              </span>
            </div>
          </div>

          {signedIn && (
            <>
              <div className="info-account-links">
                <button type="button" className="field-link" onClick={() => avatarInput.current?.click()}>
                  {account.avatarUrl ? '换头像' : '设置头像'}
                </button>
                {account.avatarUrl && (
                  <button type="button" className="field-link" onClick={onAvatarRemove}>
                    删除头像
                  </button>
                )}
                <button type="button" className="field-link" onClick={onAccountRefresh}>
                  刷新
                </button>
                <button type="button" className="field-link" onClick={onAccountLogout}>
                  退出
                </button>
                <input
                  ref={avatarInput}
                  type="file"
                  accept="image/png,image/jpeg,image/webp,image/gif"
                  hidden
                  onChange={(e) => {
                    const file = e.target.files?.[0];
                    e.target.value = '';
                    if (file) onAvatarChange(file);
                  }}
                />
              </div>

              <div className="info-account-head info-friends-head">
                <IconUserGroup size="small" />
                <span>好友</span>
                <span className="info-account-count">
                  {friends.loading ? '加载中…' : friends.items.length}
                </span>
              </div>
              {friends.error ? (
                <span className="field-error">{accountErrorText(friends.error)}</span>
              ) : friends.items.length === 0 && !friends.loading ? (
                <span className="field-help">这个账号还没有好友。</span>
              ) : (
                <div className="info-friends">
                  {friends.items.map((friend) => (
                    <div key={friend.id} className="info-friend">
                      <UserAvatar
                        name={friend.name}
                        colorKey={friend.id}
                        className="friend-avatar"
                        label={`${friend.name} 的头像`}
                      />
                      <span className="info-friend-name" title={friend.subtitle || friend.name}>
                        {friend.name}
                      </span>
                    </div>
                  ))}
                </div>
              )}
            </>
          )}
        </section>
      </div>
    </aside>
  );
}
