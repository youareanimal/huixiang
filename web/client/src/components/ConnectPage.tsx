import { useEffect, useMemo, useState } from 'react';
import { Input } from '@douyinfe/semi-ui';
import { IconClose, IconGlobe, IconKey, IconServer } from '@douyinfe/semi-icons';
import { store, type ConnectOptions } from '../protocol';
import { avatarColor, initial } from '../ui';

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
  onConnect: (opts: ConnectOptions) => void;
  onDismissError: () => void;
}

const inputStyle = { height: 44 };

export default function ConnectPage({
  tokenNeeded,
  token,
  onTokenChange,
  connecting,
  error,
  onConnect,
  onDismissError,
}: Props) {
  const [address, setAddress] = useState('');
  const [nickname, setNickname] = useState(() => store.nickname());
  const [password, setPassword] = useState('');
  const [passwordOpen, setPasswordOpen] = useState(false);
  const [showPassword, setShowPassword] = useState(false);
  const recent = useMemo(() => store.recent(), []);
  const parsed = parseAddress(address);
  const canConnect = !!parsed && nickname.trim().length > 0 && (!tokenNeeded || token.trim().length > 0);

  useEffect(() => {
    // 只有一个地址时直接填上，省一次输入
    if (recent.length === 1 && !address) setAddress(recent[0]);
  }, [recent, address]);

  const submit = () => {
    if (!parsed || !canConnect) return;
    onConnect({
      host: parsed.host,
      port: parsed.port,
      nickname: nickname.trim(),
      password,
      identity: store.identity(),
    });
  };

  return (
    <div className="connect-page">
      <div className="connect-card">
        <div className="brand">
          <div className="brand-mark" aria-hidden="true">
            响
          </div>
          <div className="brand-text">
            <span className="brand-name">回响</span>
            <span className="brand-sub">网页版 · 不用装客户端</span>
          </div>
        </div>

        <p className="connect-note">
          填入服务器地址就能进。语音由本站中转，麦克风在你浏览器里编码后发给服务器。
        </p>

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
              <span
                className="msg-avatar"
                style={{ background: avatarColor('me'), width: 24, height: 24 }}
                aria-hidden="true"
              >
                {initial(nickname || 'W')}
              </span>
            }
            placeholder="别人看到的名字"
            value={nickname}
            onChange={setNickname}
            onEnterPress={submit}
            maxLength={30}
          />
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
          {connecting ? '正在连接…' : '进入服务器'}
        </button>

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

        <span className="connect-foot">
          和 TeamSpeak 官方没有关系。关掉页面语音就断，网页版不后台常驻。
        </span>
      </div>
    </div>
  );
}
