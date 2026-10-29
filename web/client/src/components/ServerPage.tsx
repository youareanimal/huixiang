import { useEffect, useMemo, useState } from 'react';
import { Banner, Button, Input, Layout, Modal, Spin, Typography } from '@douyinfe/semi-ui';
import { IconEdit, IconServer, IconUserGroup } from '@douyinfe/semi-icons';
import type { ChannelNode, ChatLine, State } from '../protocol';
import { findChannel } from '../protocol';
import ChannelTree from './ChannelTree';
import ChatPanel from './ChatPanel';
import MemberList from './MemberList';
import ControlBar from './ControlBar';

const { Sider, Content } = Layout;
const { Title, Text } = Typography;

interface Props {
  state: State;
  chat: ChatLine[];
  micOn: boolean;
  micLevel: number;
  pttMode: boolean;
  pttHeld: boolean;
  audioReady: boolean;
  onTogglePttMode: () => void;
  onPttHold: (held: boolean) => void;
  onJoinChannel: (cid: number, password?: string) => void;
  onSay: (text: string) => void;
  onRename: (name: string) => void;
  onToggleInput: () => void;
  onToggleOutput: () => void;
  onDisconnect: () => void;
  onReconnect: () => void;
}

/** 打字时不能把空格当按住说话，否则聊不了天。 */
function typingInField(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null;
  if (!el) return false;
  const tag = el.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || el.isContentEditable;
}

export default function ServerPage({
  state,
  chat,
  micOn,
  micLevel,
  pttMode,
  pttHeld,
  audioReady,
  onTogglePttMode,
  onPttHold,
  onJoinChannel,
  onSay,
  onRename,
  onToggleInput,
  onToggleOutput,
  onDisconnect,
  onReconnect,
}: Props) {
  const [renaming, setRenaming] = useState(false);
  const [newName, setNewName] = useState('');
  const [passwordFor, setPasswordFor] = useState<ChannelNode | null>(null);
  const [password, setPassword] = useState('');

  const current = useMemo(
    () => (state.ownChannel ? findChannel(state.tree, state.ownChannel) : null),
    [state.tree, state.ownChannel],
  );

  // 按住说话：空格键也要能用，但输入框里要放过
  useEffect(() => {
    if (!pttMode || !micOn) return;
    const down = (e: KeyboardEvent) => {
      if (e.code !== 'Space' || typingInField(e.target) || e.repeat) return;
      e.preventDefault();
      onPttHold(true);
    };
    const up = (e: KeyboardEvent) => {
      if (e.code !== 'Space' || typingInField(e.target)) return;
      onPttHold(false);
    };
    window.addEventListener('keydown', down);
    window.addEventListener('keyup', up);
    return () => {
      window.removeEventListener('keydown', down);
      window.removeEventListener('keyup', up);
      onPttHold(false);
    };
  }, [pttMode, micOn, onPttHold]);

  const join = (channel: ChannelNode) => {
    if (channel.password) {
      setPasswordFor(channel);
      setPassword('');
      return;
    }
    onJoinChannel(channel.id);
  };

  const channelTitle = current?.display || current?.name || '频道';
  const connecting = state.phase === 'connecting';

  return (
    <Layout className="server-page">
      <div className="server-head">
        <div className="server-title">
          <IconServer />
          <Title heading={5} style={{ margin: 0 }}>
            {state.serverName || '正在连接…'}
          </Title>
          <Text type="tertiary" size="small">
            {state.address}
          </Text>
        </div>
        <div className="server-head-right">
          <Text type="tertiary" size="small">
            <IconUserGroup size="small" /> {state.clientCount}
          </Text>
          <Button
            theme="borderless"
            size="small"
            icon={<IconEdit size="small" />}
            onClick={() => {
              setNewName(state.nickname);
              setRenaming(true);
            }}
          >
            {state.nickname}
          </Button>
        </div>
      </div>

      {state.error && (
        <Banner
          type="danger"
          closeIcon={null}
          description={
            <span>
              {state.error}{' '}
              <Button size="small" theme="borderless" type="primary" onClick={onReconnect}>
                重连
              </Button>
            </span>
          }
          style={{ margin: '0 16px 12px' }}
        />
      )}
      {!audioReady && (
        <Banner
          type="warning"
          closeIcon={null}
          description="这个浏览器不支持 WebCodecs 音频，听不到也说不了话。请用 Chrome 94+、Edge 94+、Safari 16.4+ 或 Firefox 130+。"
          style={{ margin: '0 16px 12px' }}
        />
      )}

      {connecting ? (
        <div className="server-loading">
          <Spin size="large" />
          <Text type="tertiary">正在和服务器握手…</Text>
        </div>
      ) : (
        <Layout className="server-body">
          <Sider className="server-sider" style={{ width: 280, minWidth: 280 }}>
            <div className="sider-head">
              <Text strong size="small">
                频道
              </Text>
            </div>
            <ChannelTree
              tree={state.tree}
              ownId={state.ownId}
              ownChannel={state.ownChannel}
              onJoin={join}
            />
          </Sider>

          <Content className="server-content">
            <ChatPanel
              lines={chat}
              ownId={state.ownId}
              title={channelTitle}
              hint="频道消息，服务器上所有人都能看到"
              placeholder={`在「${channelTitle}」里说点什么，Enter 发送，Shift+Enter 换行`}
              onSend={onSay}
            />
          </Content>

          <Sider className="server-sider server-sider-right" style={{ width: 220, minWidth: 220 }}>
            <MemberList members={current?.clients ?? []} ownId={state.ownId} title={channelTitle} />
          </Sider>
        </Layout>
      )}

      <ControlBar
        micOn={micOn}
        micLevel={micLevel}
        outputMuted={state.outputMuted}
        pttMode={pttMode}
        pttHeld={pttHeld}
        channelName={channelTitle}
        clientCount={state.clientCount}
        onToggleInput={onToggleInput}
        onToggleOutput={onToggleOutput}
        onTogglePttMode={onTogglePttMode}
        onPttHold={onPttHold}
        onDisconnect={onDisconnect}
      />

      <Modal
        title="改昵称"
        visible={renaming}
        onCancel={() => setRenaming(false)}
        onOk={() => {
          onRename(newName);
          setRenaming(false);
        }}
        okText="改"
        cancelText="算了"
      >
        <Input
          value={newName}
          onChange={setNewName}
          maxLength={30}
          onEnterPress={() => {
            onRename(newName);
            setRenaming(false);
          }}
        />
      </Modal>

      <Modal
        title={`「${passwordFor?.display || passwordFor?.name || ''}」有密码`}
        visible={!!passwordFor}
        onCancel={() => setPasswordFor(null)}
        onOk={() => {
          if (passwordFor) onJoinChannel(passwordFor.id, password);
          setPasswordFor(null);
        }}
        okText="进入"
        cancelText="取消"
      >
        <Input
          mode="password"
          value={password}
          onChange={setPassword}
          placeholder="频道密码"
          onEnterPress={() => {
            if (passwordFor) onJoinChannel(passwordFor.id, password);
            setPasswordFor(null);
          }}
        />
      </Modal>
    </Layout>
  );
}
