import { useEffect, useRef, useState } from 'react';
import { Input, Spin } from '@douyinfe/semi-ui';
import { IconClose, IconRefresh } from '@douyinfe/semi-icons';
import type { AccountState, CloudChat, CloudState, ContactsMessage, Friend } from '../protocol';

interface Props {
  open: boolean;
  onClose: () => void;
  friends: ContactsMessage;
  account: AccountState;
  cloud: CloudState;
  chat: CloudChat | null;
  notice: string | null;
  onConnect: () => void;
  onDisconnect: () => void;
  onOpenFriend: (friend: Friend) => void;
  onSend: (roomId: string, text: string) => void;
  onHistory: (roomId: string) => void;
  onDismissNotice: () => void;
}

function initial(name: string): string {
  return (name || '?').trim().slice(0, 1).toUpperCase();
}

/** Round avatar: a friend's Matrix thumbnail when one exists, otherwise the name's first letter. */
function FriendAvatar({ matrixId, name }: { matrixId?: string; name: string }) {
  const [broken, setBroken] = useState(false);
  const url = matrixId ? `/cloud-avatar/${encodeURIComponent(matrixId)}` : '';
  if (url && !broken) {
    return (
      <span className="friend-avatar">
        <img src={url} alt="" onError={() => setBroken(true)} />
      </span>
    );
  }
  return <span className="friend-avatar">{initial(name)}</span>;
}

function clock(ms: number): string {
  if (!ms) return '';
  const d = new Date(ms);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

/**
 * 好友云聊天的侧边抽屉：左边是联系人，点进去就是一对一会话。
 *
 * 只有登录了 myTeamSpeak 才会出现入口；打开抽屉时才连 Matrix（见 App 的 onCloudConnect），
 * 所以不聊天的人不会被建一条云端会话。
 */
export default function FriendsPanel({
  open,
  onClose,
  friends,
  account,
  cloud,
  chat,
  notice,
  onConnect,
  onDisconnect,
  onOpenFriend,
  onSend,
  onHistory,
  onDismissNotice,
}: Props) {
  const [selected, setSelected] = useState<Friend | null>(null);
  const [pending, setPending] = useState<Friend | null>(null);
  const [draft, setDraft] = useState('');
  const listRef = useRef<HTMLDivElement | null>(null);

  // 打开抽屉、已登录、还没连：自动连一次，用户不用先点「连接」
  useEffect(() => {
    if (open && account.phase === 'signedIn' && cloud.phase === 'idle') onConnect();
  }, [open, account.phase, cloud.phase, onConnect]);

  // 连上之前点了好友：等连上再真正打开
  useEffect(() => {
    if (cloud.connected && pending) {
      onOpenFriend(pending);
      setPending(null);
    }
  }, [cloud.connected, pending, onOpenFriend]);

  // 进来新消息就滚到底
  const messageCount = chat?.messages.length ?? 0;
  useEffect(() => {
    const el = listRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messageCount, chat?.roomId]);

  if (!open) return null;

  const signedIn = account.phase === 'signedIn';
  const viewing = selected && chat && chat.roomId && chat.friendId === selected.id ? chat : null;

  const pick = (f: Friend) => {
    setSelected(f);
    onDismissNotice();
    if (cloud.connected) onOpenFriend(f);
    else {
      setPending(f);
      if (cloud.phase !== 'connecting') onConnect();
    }
  };

  const submit = () => {
    const text = draft.trim();
    if (!text || !viewing || !viewing.roomId) return;
    onSend(viewing.roomId, text);
    setDraft('');
  };

  return (
    <>
      <div className="friends-scrim" onClick={onClose} />
      <aside className="friends-drawer" role="dialog" aria-label="好友">
        <div className="friends-head">
          {viewing ? (
            <button type="button" className="friends-back" onClick={() => setSelected(null)}>
              ‹ 好友
            </button>
          ) : (
            <span className="friends-title">好友</span>
          )}
          <div className="friends-head-actions">
            {viewing && (
              <button
                type="button"
                className="icon-btn"
                title="刷新"
                onClick={() => viewing.roomId && onHistory(viewing.roomId)}
              >
                <IconRefresh size="small" />
              </button>
            )}
            <button type="button" className="icon-btn" title="关闭" onClick={onClose}>
              <IconClose size="small" />
            </button>
          </div>
        </div>

        {!signedIn ? (
          <div className="friends-empty">登录 myTeamSpeak 后即可使用好友云聊天。</div>
        ) : viewing ? (
          <>
            <div className="friends-chat-head">
              <FriendAvatar matrixId={selected?.matrixId || viewing.peer || undefined} name={selected?.name || viewing.name || ''} />
              <span className="friends-chat-name">{selected?.name || viewing.name}</span>
            </div>
            <div className="friends-messages" ref={listRef}>
              {viewing.more && (
                <button
                  type="button"
                  className="friends-more"
                  onClick={() => viewing.roomId && onHistory(viewing.roomId)}
                >
                  加载更早的消息
                </button>
              )}
              {viewing.messages.length === 0 && (
                <div className="friends-chat-empty">还没有消息，打个招呼吧</div>
              )}
              {viewing.messages.map((m) => {
                const mine = m.sender === cloud.userId;
                return (
                  <div key={m.id} className={`friends-msg${mine ? ' is-mine' : ''}`}>
                    <div className="friends-bubble">{m.body}</div>
                    <div className="friends-time">{clock(m.time)}</div>
                  </div>
                );
              })}
            </div>
            {notice && <div className="friends-notice">{notice}</div>}
            {viewing.encrypted ? (
              <div className="friends-locked">这是端到端加密会话，网页版暂不支持收发。</div>
            ) : (
              <div className="friends-compose">
                <Input
                  value={draft}
                  onChange={setDraft}
                  placeholder="发消息…"
                  onEnterPress={submit}
                />
                <button type="button" className="friends-send" onClick={submit} disabled={!draft.trim()}>
                  发送
                </button>
              </div>
            )}
          </>
        ) : (
          <div className="friends-body">
            {cloud.phase === 'connecting' && (
              <div className="friends-status">
                <Spin size="small" /> 正在连接云端聊天…
              </div>
            )}
            {cloud.phase === 'error' && (
              <div className="friends-status friends-status-error">
                <span>{cloud.errorText || '云端聊天连接失败'}</span>
                <button type="button" className="friends-retry" onClick={onConnect}>
                  重试
                </button>
              </div>
            )}
            {notice && cloud.phase !== 'connecting' && (
              <div className="friends-notice">
                {notice}
                <button type="button" className="friends-notice-x" onClick={onDismissNotice}>
                  <IconClose size="small" />
                </button>
              </div>
            )}
            {friends.loading && friends.items.length === 0 ? (
              <div className="friends-status">
                <Spin size="small" /> 正在加载好友…
              </div>
            ) : friends.items.length === 0 ? (
              <div className="friends-empty">
                {friends.error ? friends.error : '还没有好友。在 myTeamSpeak 里加了好友就会出现在这里。'}
              </div>
            ) : (
              <div className="friends-list">
                {friends.items.map((f) => (
                  <button key={f.id} type="button" className="friend-row" onClick={() => pick(f)}>
                    <FriendAvatar matrixId={f.matrixId} name={f.name} />
                    <span className="friend-text">
                      <span className="friend-name">{f.name}</span>
                      {f.subtitle && <span className="friend-sub">{f.subtitle}</span>}
                    </span>
                  </button>
                ))}
              </div>
            )}
            {cloud.connected && friends.items.length > 0 && (
              <button type="button" className="friends-disconnect" onClick={onDisconnect}>
                断开云端聊天
              </button>
            )}
          </div>
        )}
      </aside>
    </>
  );
}
