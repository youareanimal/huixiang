import { useEffect, useMemo, useState } from 'react';
import { Banner, Button, Input, Typography } from '@douyinfe/semi-ui';
import { IconGlobe, IconKey, IconServer, IconUser } from '@douyinfe/semi-icons';
import { store, type ConnectOptions } from '../protocol';

const { Title, Paragraph, Text } = Typography;

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
          <div className="brand-mark">响</div>
          <div>
            <Title heading={3} style={{ margin: 0 }}>
              回响
            </Title>
            <Text type="tertiary" size="small">
              网页版 · 不需要安装
            </Text>
          </div>
        </div>

        <Paragraph type="tertiary" size="small" className="connect-note">
          填入服务器地址即可进入。语音要经过本站的中转服务器，所以你的麦克风会在浏览器里编码后送到服务器再转发。
        </Paragraph>

        {error && (
          <Banner
            type="danger"
            closeIcon={null}
            description={error}
            style={{ marginBottom: 16 }}
            onClose={onDismissError}
          />
        )}

        <div className="field">
          <label className="field-label">服务器地址</label>
          <Input
            size="large"
            prefix={<IconGlobe />}
            placeholder="例如 pi.animal26.site 或 1.2.3.4:9987"
            value={address}
            onChange={setAddress}
            onEnterPress={submit}
            showClear
          />
          {address && !parsed && <Text type="danger" size="small">地址看不懂，端口要 1–65535</Text>}
        </div>

        <div className="field">
          <label className="field-label">你的昵称</label>
          <Input
            size="large"
            prefix={<IconUser />}
            placeholder="别人看到的名字"
            value={nickname}
            onChange={setNickname}
            onEnterPress={submit}
            maxLength={30}
          />
        </div>

        <div className="field">
          <label className="field-label">
            服务器密码 <Text type="tertiary" size="small">（没设就不用填）</Text>
          </label>
          <Input
            size="large"
            prefix={<IconKey />}
            type={showPassword ? 'text' : 'password'}
            placeholder="不是 TeamSpeak 账号密码"
            value={password}
            onChange={setPassword}
            onEnterPress={submit}
            suffix={
              <Button
                theme="borderless"
                size="small"
                type="tertiary"
                onClick={() => setShowPassword((v) => !v)}
              >
                {showPassword ? '隐藏' : '显示'}
              </Button>
            }
          />
        </div>

        {tokenNeeded && (
          <div className="field">
            <label className="field-label">访问口令</label>
            <Input
              size="large"
              prefix={<IconServer />}
              placeholder="本站要求的访问口令"
              value={token}
              onChange={onTokenChange}
              onEnterPress={submit}
            />
            <Text type="tertiary" size="small">
              口令由站主提供，只存在这台浏览器里
            </Text>
          </div>
        )}

        <Button
          theme="solid"
          type="primary"
          size="large"
          block
          loading={connecting}
          disabled={!canConnect}
          onClick={submit}
          style={{ marginTop: 8 }}
        >
          {connecting ? '正在连接…' : '进入服务器'}
        </Button>

        {recent.length > 0 && (
          <div className="recent">
            <Text type="tertiary" size="small">
              最近连过
            </Text>
            <div className="recent-list">
              {recent.map((r) => (
                <button key={r} className="recent-item" onClick={() => setAddress(r)}>
                  <IconServer size="small" />
                  <span>{r}</span>
                </button>
              ))}
            </div>
          </div>
        )}

        <Text type="tertiary" size="small" className="connect-foot">
          和 TeamSpeak 官方没有关系。关掉页面语音就断了，网页版没有后台常驻。
        </Text>
      </div>
    </div>
  );
}
