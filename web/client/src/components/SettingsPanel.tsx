import { useEffect, useRef, useState } from 'react';
import { Input, Modal, Switch, Toast } from '@douyinfe/semi-ui';
import type { AccountState, ServerInfo } from '../protocol';
import UserAvatar from './UserAvatar';

interface Props {
  open: boolean;
  onClose: () => void;
  account: AccountState;
  nickname: string;
  server: ServerInfo | null;
  address: string;
  pingGateway: number | null;
  pingServer: number | null;
  notifyOn: boolean;
  notifySupported: boolean;
  onToggleNotify: () => void;
  away: boolean;
  awayMessage: string;
  onToggleAway: (away: boolean) => void;
  onSetAwayMessage: (message: string) => void;
  onRename: (name: string) => void;
  onAccountRefresh: () => void;
  onAccountLogout: () => void;
  onAvatarChange: (file: File) => void;
  onAvatarRemove: () => void;
}

/**
 * 统一设置面板：账号、昵称、通知、关于都在这里。
 * 底部的麦克风/扬声器/说话方式是实时操作，留在控制栏里，不搬进来。
 */
export default function SettingsPanel({
  open,
  onClose,
  account,
  nickname,
  server,
  address,
  pingGateway,
  pingServer,
  notifyOn,
  notifySupported,
  onToggleNotify,
  away,
  awayMessage,
  onToggleAway,
  onSetAwayMessage,
  onRename,
  onAccountRefresh,
  onAccountLogout,
  onAvatarChange,
  onAvatarRemove,
}: Props) {
  const signedIn = account.phase === 'signedIn';
  const avatarInput = useRef<HTMLInputElement>(null);
  const [name, setName] = useState(nickname);
  const [awayMsg, setAwayMsg] = useState(awayMessage);

  // 每次打开或外部昵称变化，把输入框对齐到当前值
  useEffect(() => {
    setName(nickname);
  }, [nickname, open]);

  useEffect(() => {
    setAwayMsg(awayMessage);
  }, [awayMessage, open]);

  const version = server?.version?.replace(/\[Build:[^\]]*\]/gi, '').trim() || '';
  const versionLine = [server?.platform, version].filter(Boolean).join(' · ');
  const pingTotal = pingGateway != null && pingServer != null ? pingGateway + pingServer : null;

  const saveName = () => {
    const n = name.trim();
    if (!n) {
      Toast.warning('昵称不能为空');
      return;
    }
    if (n === nickname) return;
    onRename(n);
    Toast.success('昵称已更新');
  };

  return (
    <Modal title="设置" visible={open} onCancel={onClose} footer={null} width={520}>
      <div className="settings-body">
        <section className="settings-section">
          <h3 className="settings-h">账号</h3>
          <div className="settings-account">
            <UserAvatar
              name={signedIn ? account.username || account.email : '?'}
              sources={signedIn && account.avatarUrl ? [account.avatarUrl] : []}
              colorKey={signedIn ? account.username || account.email : 'guest'}
              className="account-avatar"
            />
            <div className="settings-account-id">
              <span className="settings-account-name">
                {signedIn ? account.username || 'myTeamSpeak 账号' : '未登录 myTeamSpeak'}
              </span>
              <span className="settings-account-sub">
                {signedIn ? account.email || '已登录' : '在连接页登录后可同步好友与头像'}
              </span>
            </div>
          </div>
          {signedIn && (
            <div className="settings-links">
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
              <button
                type="button"
                className="field-link"
                onClick={() => {
                  onAccountLogout();
                  onClose();
                }}
              >
                退出登录
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
          )}
        </section>

        <section className="settings-section">
          <h3 className="settings-h">昵称</h3>
          <div className="settings-row">
            <Input
              value={name}
              onChange={setName}
              maxLength={30}
              placeholder="显示给别人的名字"
              onEnterPress={saveName}
            />
            <button
              type="button"
              className="ghost-btn"
              onClick={saveName}
              disabled={!name.trim() || name.trim() === nickname}
            >
              保存
            </button>
          </div>
        </section>

        <section className="settings-section">
          <h3 className="settings-h">状态</h3>
          <div className="settings-row settings-row-between">
            <div>
              <div className="settings-label">暂离（Away）</div>
              <div className="settings-desc">开启后别人会看到你处于「离开」状态</div>
            </div>
            <Switch checked={away} onChange={onToggleAway} />
          </div>
          {away && (
            <div className="settings-row" style={{ marginTop: 10 }}>
              <Input
                value={awayMsg}
                onChange={setAwayMsg}
                maxLength={120}
                placeholder="离开留言（可选）"
                onEnterPress={() => onSetAwayMessage(awayMsg.trim())}
                onBlur={() => onSetAwayMessage(awayMsg.trim())}
              />
            </div>
          )}
        </section>

        <section className="settings-section">
          <h3 className="settings-h">通知</h3>
          <div className="settings-row settings-row-between">
            <div>
              <div className="settings-label">桌面消息通知</div>
              <div className="settings-desc">
                {notifySupported ? '收到新消息时弹系统通知' : '当前环境不支持（需 HTTPS 或 localhost）'}
              </div>
            </div>
            <Switch checked={notifyOn} disabled={!notifySupported} onChange={onToggleNotify} />
          </div>
        </section>

        <section className="settings-section">
          <h3 className="settings-h">关于</h3>
          <dl className="settings-rows">
            <div className="settings-kv">
              <dt>服务器</dt>
              <dd>{address}</dd>
            </div>
            {versionLine && (
              <div className="settings-kv">
                <dt>版本</dt>
                <dd>{versionLine}</dd>
              </div>
            )}
            {pingTotal != null && (
              <div className="settings-kv">
                <dt>延迟</dt>
                <dd>{pingTotal} ms</dd>
              </div>
            )}
          </dl>
        </section>
      </div>
    </Modal>
  );
}
