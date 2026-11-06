import { useState } from 'react';
import { IconVolume2 } from '@douyinfe/semi-icons';

/** TeamSpeak 内置图标（1..999）按 WebTS 的规则画成彩色盾牌。 */
const BUILTIN: Record<number, string> = {
  100: '#22C55E',
  200: '#3B82F6',
  300: '#EF4444',
  500: '#94A3B8',
  600: '#EAB308',
};

interface Props {
  /** 频道 / 身份组图标 id：0/undefined 无；1..999 内置；1000+ 服务器文件 /icon_<id>。 */
  iconId?: number;
  /** 头像令牌（<img> 不能带 header，地址里带它）。 */
  token?: string;
  /** 没有图标时是否隐去（徽章用）：true 返回 null，而不是默认的语音图标。 */
  silent?: boolean;
}

/**
 * 频道 / 身份组图标：和安卓端一致——服务器给的图标文件优先，其次内置盾牌，最后（频道用）默认语音图标。
 * 图标文件下载失败（没权限 / 服务器没开文件传输）就退回默认图标或直接隐去，不报错。
 */
export default function ChannelIcon({ iconId = 0, token = '', silent = false }: Props) {
  const [failed, setFailed] = useState(false);
  if (!iconId || iconId <= 0) return silent ? null : <IconVolume2 size="small" />;
  if (iconId < 1000) {
    return (
      <svg className="chan-icon-svg" viewBox="0 0 24 24" width="14" height="14" aria-hidden="true">
        <path d="M12 2l8 3v6c0 5-3.5 8.5-8 10-4.5-1.5-8-5-8-10V5l8-3z" fill={BUILTIN[iconId] ?? 'var(--text-4)'} />
      </svg>
    );
  }
  if (!token || failed) return silent ? null : <IconVolume2 size="small" />;
  return (
    <img
      className="chan-icon-img"
      src={`/icon/${token}/${iconId}`}
      alt=""
      loading="lazy"
      referrerPolicy="no-referrer"
      onError={() => setFailed(true)}
    />
  );
}
