import { useEffect, useMemo, useRef, useState } from 'react';
import { Input, Toast } from '@douyinfe/semi-ui';
import { IconClose, IconGlobe, IconKey, IconServer } from '@douyinfe/semi-icons';
import { accountErrorText } from '../account';
import {
  accountNickname,
  effectiveNickname,
  store,
  type AccountState,
  type Bookmark,
  type BookmarksMessage,
  type ConnectOptions,
} from '../protocol';
import UserAvatar from './UserAvatar';

/** 从用户输入里拿地址和端口：支持 `host`、`host:port`、`[v6]:port`，也认 ts3server:// 邀请开头。 */
function parseAddress(raw: string): { host: string; port: number } | null {
  const cleaned = raw.trim().replace(/^(ts3server|teamspeak|ts3s):\/\//i, '');
  if (!cleaned) return null;
  const bare = cleaned.split('?')[0];
  const v6 = bare.match(/^\[([^\]]+)\](?::(\d+))?$/);
  const plain = bare.match(/^([^:\s]+)(?::(\d+))?$/);
  const m = v6 ?? plain;
  if (!m) return null;
  const port = m[2] ? Number(m[2]) : 9987;
  if (!Number.isInteger(port) || port < 1 || port > 65535) return null;
  return { host: m[1], port };
}

interface Props {
  tokenNeeded: boolean;
  token: string;
  onTokenChange: (v: string) => void;
  connecting: boolean;
  error: string | null;
  account: AccountState;
  bookmarks: BookmarksMessage;
  onConnect: (opts: ConnectOptions) => void;
  onDismissError: () => void;
  onAccountLogin: (email: string, password: string, otp: string) => void;
  onAccountLogout: () => void;
  onAccountRefresh: () => void;
}

const inputStyle = { height: 44 };

export default function ConnectPage({
  tokenNeeded,
  token,
  onTokenChange,
  connecting,
  error,
  account,
  bookmarks,
  onConnect,
  onDismissError,
  onAccountLogin,
  onAccountLogout,
  onAccountRefresh,
}: Props) {
  const [address, setAddress] = useState('');
  const [nickname, setNickname] = useState(() => store.nickname());
  const [password, setPassword] = useState('');
  const [passwordOpen, setPasswordOpen] = useState(false);
  const [showPassword, setShowPassword] = useState(false);
  const [loginOpen, setLoginOpen] = useState(false);
  const [accountEmail, setAccountEmail] = useState('');
  const [accountPassword, setAccountPassword] = useState('');
  const [otp, setOtp] = useState('');
  const [showOtp, setShowOtp] = useState(false);
  const [showAccountPassword, setShowAccountPassword] = useState(false);
  const lastAutoNick = useRef('');
  const recent = useMemo(() => store.recent(), []);
  const parsed = parseAddress(address);
  const signedIn = account.phase === 'signedIn';
  // 已登录时昵称固定是账号名，输入框只做展示；游客才用自己填的昵称
  const accountNick = accountNickname(account);
  const nick = effectiveNickname(account, nickname);
  const canConnect = !!parsed && nick.trim().length > 0 && (!tokenNeeded || token.trim().length > 0);
  const accountBusy = account.phase === 'busy';
  const canLogin =
    !!accountEmail.trim() && !!accountPassword && (!tokenNeeded || token.trim().length > 0) && !accountBusy;

  // 只有一个地址时进页面直接填上，省一次输入。
  // 只在首次填充，之后用户删掉就是删掉——不能「地址一空就补回来」，否则永远删不掉。
  const autoFilled = useRef(false);
  useEffect(() => {
    if (autoFilled.current) return;
    if (recent.length === 1 && !address) {
      autoFilled.current = true;
      setAddress(recent[0]);
    }
  }, [recent, address]);

  // 登录后默认用账号昵称；用户自己改过就保留。
  useEffect(() => {
    if (account.phase !== 'signedIn' || !account.username) {
      if (account.phase !== 'signedIn') lastAutoNick.current = '';
      return;
    }
    const name = account.username;
    setNickname((prev) => {
      if (!prev.trim() || prev === lastAutoNick.current) {
        lastAutoNick.current = name;
        return name;
      }
      return prev;
    });
  }, [account.phase, account.username]);

  // 需要两步验证时，网关会说 208/209；把验证码输入框露出来。
  useEffect(() => {
    if (account.error === 'MYTS_208' || account.error === 'MYTS_209') {
      setLoginOpen(true);
      setShowOtp(true);
    }
  }, [account.error]);

  // 主动退出后收起登录表单；登录失败（有错误码）时留着，方便改完再试。
  useEffect(() => {
    if (account.phase === 'guest' && !account.error) setLoginOpen(false);
  }, [account.phase, account.error]);

  const submit = () => {
    if (!parsed || !canConnect) return;
    onConnect({
      host: parsed.host,
      port: parsed.port,
      nickname: nick.trim(),
      password,
      identity: store.identity(),
    });
  };

  const submitLogin = () => {
    if (!canLogin) return;
    setLoginOpen(true);
    onAccountLogin(accountEmail.trim(), accountPassword, otp.trim());
  };

  const useBookmark = (bookmark: Bookmark) => {
    setAddress(bookmark.address);
    setNickname(bookmark.nickname.trim() || account.username || nickname);
    setPassword(bookmark.password);
    if (bookmark.password) setPasswordOpen(true);
    Toast.info(bookmark.password ? '已填入云书签，含服务器密码' : '已填入云书签');
  };

  return (
    <div className="connect-page">
      <div className="connect-card">
        <div className="brand">
          <img className="brand-mark" src="/icons/icon-96.png" alt="" aria-hidden="true" />
          <div className="brand-text">
            <span className="brand-name">回响</span>
            <span className="brand-sub">网页版 · 不用装客户端</span>
          </div>
        </div>

        {error && (
          <div
            role="alert"
            style={{
              display: 'flex',
              alignItems: 'flex-start',
              gap: 8,
              padding: '9px 10px 9px 12px',
              borderRadius: 10,
              background: 'var(--danger-soft)',
              color: '#a3242b',
            }}
          >
            <span style={{ flex: 1, fontSize: 12, lineHeight: '18px', minWidth: 0 }}>{error}</span>
            <button type="button" className="icon-btn" onClick={onDismissError} aria-label="关闭提示">
              <IconClose size="small" />
            </button>
          </div>
        )}

        <section className="account-card" aria-label="myTeamSpeak 账号">
          <div className="account-head">
            <UserAvatar
              name={signedIn ? account.username || account.email : '?'}
              sources={signedIn && account.avatarUrl ? [account.avatarUrl] : []}
              colorKey={signedIn ? account.username || account.email : 'guest'}
              className="account-avatar"
              label={signedIn ? `${account.username} 的头像` : undefined}
            />
            <div className="account-id">
              <span className="account-title">
                {signedIn ? account.username || 'myTeamSpeak 账号' : 'myTeamSpeak 账号'}
              </span>
              <span className="account-sub">
                {signedIn
                  ? account.email || '已登录'
                  : accountBusy
                    ? '正在登录…'
                    : '登录后同步云书签；不登录也能以游客身份进入'}
              </span>
            </div>
            {signedIn ? (
              <div className="account-links">
                <button type="button" className="field-link" onClick={onAccountRefresh}>
                  刷新
                </button>
                <button type="button" className="field-link" onClick={onAccountLogout}>
                  退出
                </button>
              </div>
            ) : (
              <button type="button" className="field-link" onClick={() => setLoginOpen((v) => !v)}>
                {loginOpen ? '收起' : '登录'}
              </button>
            )}
          </div>

          {!signedIn && loginOpen && (
            <div className="account-login">
              <div className="field">
                <label className="field-label" htmlFor="account-email">
                  myTeamSpeak 邮箱
                </label>
                <Input
                  id="account-email"
                  size="large"
                  style={inputStyle}
                  placeholder="登录 myTeamSpeak 的邮箱"
                  value={accountEmail}
                  onChange={setAccountEmail}
                  onEnterPress={submitLogin}
                  disabled={accountBusy}
                />
              </div>
              <div className="field">
                <label className="field-label" htmlFor="account-password">
                  账号密码
                </label>
                <Input
                  id="account-password"
                  size="large"
                  style={inputStyle}
                  type={showAccountPassword ? 'text' : 'password'}
                  placeholder="不是服务器密码"
                  value={accountPassword}
                  onChange={setAccountPassword}
                  onEnterPress={submitLogin}
                  disabled={accountBusy}
                  suffix={
                    <button
                      type="button"
                      className="field-link"
                      onClick={() => setShowAccountPassword((v) => !v)}
                    >
                      {showAccountPassword ? '隐藏' : '显示'}
                    </button>
                  }
                />
              </div>
              {showOtp ? (
                <div className="field">
                  <label className="field-label" htmlFor="account-otp">
                    两步验证码
                  </label>
                  <Input
                    id="account-otp"
                    size="large"
                    style={inputStyle}
                    placeholder="账号开启两步验证时填写"
                    value={otp}
                    onChange={setOtp}
                    onEnterPress={submitLogin}
                    disabled={accountBusy}
                    maxLength={64}
                  />
                </div>
              ) : (
                <button type="button" className="field-link" onClick={() => setShowOtp(true)}>
                  账号开启了两步验证？
                </button>
              )}
              {account.error && <span className="field-error">{accountErrorText(account.error)}</span>}
              {tokenNeeded && !token.trim() && (
                <span className="field-help">先在下面填写网关访问口令，再登录账号。</span>
              )}
              <button
                type="button"
                className="connect-submit account-login-submit"
                disabled={!canLogin}
                onClick={submitLogin}
              >
                {accountBusy ? '正在登录…' : '登录 myTeamSpeak'}
              </button>
              <span className="field-help">
                密码只发给网关换取这次登录，不写进浏览器存储；登录状态会记在这台浏览器里，网关重启后需要重新登录。
              </span>
            </div>
          )}

          {signedIn && (
            <>
              <div className="account-section">
                <div className="account-section-head">
                  <span className="account-section-title">云书签</span>
                  <span className="account-section-count">
                    {bookmarks.loading ? '同步中…' : `${bookmarks.items.length} 个`}
                  </span>
                </div>
                {bookmarks.error ? (
                  <span className="field-error">{accountErrorText(bookmarks.error)}</span>
                ) : bookmarks.items.length === 0 && !bookmarks.loading ? (
                  <span className="field-help">这个账号还没有保存服务器书签。</span>
                ) : (
                  <div className="saved-list">
                    {bookmarks.items.map((bookmark) => (
                      <button
                        key={bookmark.id}
                        type="button"
                        className="saved-item"
                        onClick={() => useBookmark(bookmark)}
                        title={`填入 ${bookmark.address}`}
                      >
                        <IconServer size="small" />
                        <span className="saved-main">
                          <span className="saved-name">{bookmark.name || bookmark.address}</span>
                          <span className="saved-sub">{bookmark.address}</span>
                        </span>
                        {bookmark.password && (
                          <span className="saved-key" title="云书签里有服务器密码，填入后自动使用">
                            <IconKey size="small" />
                          </span>
                        )}
                      </button>
                    ))}
                  </div>
                )}
              </div>
            </>
          )}
        </section>

        <div className="field">
          <label className="field-label" htmlFor="addr">
            服务器地址
          </label>
          <Input
            id="addr"
            size="large"
            style={inputStyle}
            prefix={<IconGlobe />}
            placeholder="例如 pi.animal26.site 或 1.2.3.4:9987"
            value={address}
            onChange={setAddress}
            onEnterPress={submit}
            showClear
          />
          {address && !parsed ? (
            <span className="field-error">地址看不懂，端口要 1–65535</span>
          ) : (
            <span className="field-help">不带端口就是默认的 9987</span>
          )}
        </div>

        <div className="field">
          <label className="field-label" htmlFor="nick">
            你的昵称
          </label>
          <Input
            id="nick"
            size="large"
            style={inputStyle}
            prefix={
              <UserAvatar
                name={nick || 'W'}
                sources={signedIn && account.avatarUrl ? [account.avatarUrl] : []}
                colorKey={signedIn ? account.username || 'me' : 'me'}
                style={{ width: 24, height: 24 }}
              />
            }
            placeholder="别人看到的名字"
            value={nick}
            onChange={setNickname}
            onEnterPress={submit}
            maxLength={30}
            disabled={!!accountNick}
          />
          {!!accountNick && (
            <span className="field-help">已登录 myTeamSpeak，将以账号名进入；要自定义昵称请先退出账号</span>
          )}
        </div>

        {passwordOpen ? (
          <div className="field">
            <label className="field-label" htmlFor="pwd">
              服务器密码
            </label>
            <Input
              id="pwd"
              size="large"
              style={inputStyle}
              prefix={<IconKey />}
              type={showPassword ? 'text' : 'password'}
              placeholder="不是 TeamSpeak 账号密码"
              value={password}
              onChange={setPassword}
              onEnterPress={submit}
              suffix={
                <button
                  type="button"
                  className="field-link"
                  onClick={() => setShowPassword((v) => !v)}
                >
                  {showPassword ? '隐藏' : '显示'}
                </button>
              }
            />
          </div>
        ) : (
          <button
            type="button"
            className="field-link"
            onClick={() => setPasswordOpen(true)}
          >
            <IconKey size="small" />
            服务器有密码？点这里填写
          </button>
        )}

        {tokenNeeded && (
          <div className="field">
            <label className="field-label" htmlFor="token">
              访问口令
            </label>
            <Input
              id="token"
              size="large"
              style={inputStyle}
              prefix={<IconServer />}
              placeholder="站主给你的访问口令"
              value={token}
              onChange={onTokenChange}
              onEnterPress={submit}
            />
            <span className="field-help">口令只存在这台浏览器里</span>
          </div>
        )}

        <button
          type="button"
          className="connect-submit"
          disabled={!canConnect || connecting}
          onClick={submit}
        >
          {connecting ? '正在连接…' : signedIn ? `以 ${nick} 进入服务器` : '以游客身份进入'}
        </button>

        {signedIn && (
          <span className="field-help">
            登录状态下连接会携带 myTeamSpeak 身份；要完全匿名进入，请先退出账号。
          </span>
        )}

        {recent.length > 0 && (
          <>
            <div className="divider" />
            <div className="recent">
              <span className="recent-label">最近连过</span>
              <div className="recent-list">
                {recent.map((r) => (
                  <button key={r} type="button" className="recent-item" onClick={() => setAddress(r)}>
                    <IconServer size="small" />
                    <span>{r}</span>
                  </button>
                ))}
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
