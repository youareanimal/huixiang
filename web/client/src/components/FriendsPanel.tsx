import { useEffect, useRef, useState } from 'react';
import { Input, Spin } from '@douyinfe/semi-ui';
import { IconClose, IconRefresh } from '@douyinfe/semi-icons';
import type {
  AccountState,
  ChatLine,
  CloudChat,
  CloudState,
  ContactsMessage,
  Friend,
  Member,
} from '../protocol';
import { avatarSources } from '../ui';

interface Props {
  open: boolean;
  onClose: () => void;
  friends: ContactsMessage;
  account: AccountState;
  cloud: CloudState;
  chat: CloudChat | null;
  notice: string | null;
  /** 好友的 TS 身份 UID → 本服务器上的 clientId（在线才有）。 */
  onlineUids: Map<string, number>;
  /** 昵称兜底：好友 identifier 万一和 UID 对不上时用。 */
  onlineByName: Map<string, number>;
  /** clientId → 服务器成员，用来取在线好友的服务器 / myTeamSpeak 头像。 */
  memberById: Map<number, Member>;
  avatarToken: string;
  accountAvatar: string;
  /** 服务器聊天的实时行，用来筛出和某个人的私聊。 */
  serverLines: ChatLine[];
  ownId: number;
  /** 给本服务器上的某个人发私聊（官方客户端认的服务器文本消息）。 */
  onSendServer: (clientId: number, text: string) => void;
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

/** Round avatar: server/myTeamSpeak avatar (online) → Matrix thumbnail → name initial. */
function FriendAvatar({ matrixId, name, src }: { matrixId?: string; name: string; src?: string }) {
  const [broken, setBroken] = useState(false);
  const [brokenMatrix, setBrokenMatrix] = useState(false);
  if (src && !broken) {
    return (
      <span className="friend-avatar">
        <img src={src} alt="" onError={() => setBroken(true)} />
      </span>
    );
  }
  const url = matrixId ? `/cloud-avatar/${encodeURIComponent(matrixId)}` : '';
  if (url && !brokenMatrix) {
    return (
      <span className="friend-avatar">
        <img src={url} alt="" onError={() => setBrokenMatrix(true)} />
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
 * 好友抽屉：左边联系人，点进去是一对一会话。
 *
 * 两条路自动选：
 * - 对方正连在**当前服务器**上 → 走 TeamSpeak 服务器的私聊（sendtextmessage targetmode=1），
 *   官方 TS5/TS6 客户端收得到、也显示得出来；
 * - 对方不在本服务器 → 退回 myTeamSpeak 云聊天（Matrix），跨服务器也能聊。
 *
 * 好友列表来自 myTS 联系人，所以入口仍要求登录 myTeamSpeak。
 */
export default function FriendsPanel({
  open,
  onClose,
  friends,
  account,
  cloud,
  chat,
  notice,
  onlineUids,
  onlineByName,
  memberById,
  avatarToken,
  accountAvatar,
  serverLines,
  ownId,
  onSendServer,
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

  const onlineOf = (f: Friend): number | undefined =>
    onlineUids.get(f.id) ?? onlineByName.get(f.name);

  // 在线好友用服务器 / myTeamSpeak 头像（TS5 里看到的那张）；离线再退回 Matrix 头像。
  const avatarSrcOf = (f: Friend | null | undefined): string | undefined => {
    if (!f) return undefined;
    const cid = onlineOf(f);
    if (cid == null) return undefined;
    const m = memberById.get(cid);
    return m ? avatarSources(m, ownId, accountAvatar, avatarToken)[0] : undefined;
  };

  // 连上云端之后，把之前点了的离线好友真正打开
  useEffect(() => {
    if (cloud.connected && pending) {
      onOpenFriend(pending);
      setPending(null);
    }
  }, [cloud.connected, pending, onOpenFriend]);

  const viewing = selected && chat && chat.roomId && chat.friendId === selected.id ? chat : null;
  const onlineId = selected ? onlineOf(selected) : undefined;
  const serverMode = !!(selected && onlineId != null);
  const selectedUid = selected
    ? onlineUids.has(selected.id)
      ? selected.id
      : Array.from(onlineUids.entries()).find(([, cid]) => cid === onlineId)?.[0]
    : undefined;
  const serverMsgs = selected
    ? serverLines.filter((l) => {
        if (l.mode !== 1) return false;
        if (l.peerUid && selectedUid && l.peerUid === selectedUid) return true;
        if (l.peerUid && l.peerUid === selected.id) return true;
        if (l.peerName && l.peerName === selected.name) return true;
        if (onlineId != null && (l.from === onlineId || (l.from === ownId && l.channel === onlineId))) return true;
        return false;
      })
    : [];

  // 进来新消息就滚到底
  const messageCount = serverMode ? serverMsgs.length : chat?.messages.length ?? 0;
  useEffect(() => {
    const el = listRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messageCount, chat?.roomId, selected?.id]);

  if (!open) return null;

  const signedIn = account.phase === 'signedIn';

  const pick = (f: Friend) => {
    setSelected(f);
    onDismissNotice();
    if (onlineOf(f) != null) return; // 服务器私聊，不用连云端
    if (cloud.connected) onOpenFriend(f);
    else {
      setPending(f);
      if (cloud.phase !== 'connecting') onConnect();
    }
  };

  const submit = () => {
    const text = draft.trim();
    if (!text) return;
    if (serverMode && onlineId != null) {
      onSendServer(onlineId, text);
      setDraft('');
      return;
    }
    if (viewing && viewing.roomId) {
      onSend(viewing.roomId, text);
      setDraft('');
    }
  };

  const chatTitle = selected?.name || viewing?.name || '';

  return (
    <>
      <div className="friends-scrim" onClick={onClose} />
      <aside className="friends-drawer" role="dialog" aria-label="好友">
        <div className="friends-head">
          {selected ? (
            <button type="button" className="friends-back" onClick={() => setSelected(null)}>
              ‹ 好友
            </button>
          ) : (
            <span className="friends-title">好友</span>
          )}
          <div className="friends-head-actions">
            {viewing && !serverMode && (
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
          <div className="friends-empty">登录 myTeamSpeak 后即可使用好友聊天。</div>
        ) : selected && serverMode ? (
          <>
            <div className="friends-chat-head">
              <FriendAvatar matrixId={selected.matrixId} name={selected.name} src={avatarSrcOf(selected)} />
              <span className="friends-chat-name">{chatTitle}</span>
              <span className="friends-chat-mode">在线 · 服务器私聊</span>
            </div>
            <div className="friends-messages" ref={listRef}>
              {serverMsgs.length === 0 && (
                <div className="friends-chat-empty">还没有消息，打个招呼吧</div>
              )}
              {serverMsgs.map((l, i) => {
                const mine = l.mine ?? l.from === ownId;
                return (
                  <div key={`${l.time}-${i}`} className={`friends-msg${mine ? ' is-mine' : ''}`}>
                    <div className="friends-bubble">{l.text}</div>
                    <div className="friends-time">{clock(l.time)}</div>
                  </div>
                );
              })}
            </div>
            <div className="friends-compose">
              <Input value={draft} onChange={setDraft} placeholder="发消息…" onEnterPress={submit} />
              <button type="button" className="friends-send" onClick={submit} disabled={!draft.trim()}>
                发送
              </button>
            </div>
          </>
        ) : selected && viewing ? (
          <>
            <div className="friends-chat-head">
              <FriendAvatar matrixId={selected?.matrixId || viewing.peer || undefined} name={chatTitle} src={avatarSrcOf(selected)} />
              <span className="friends-chat-name">{chatTitle}</span>
              <span className="friends-chat-mode">不在本服务器 · 云聊天</span>
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
                <Input value={draft} onChange={setDraft} placeholder="发消息…" onEnterPress={submit} />
                <button type="button" className="friends-send" onClick={submit} disabled={!draft.trim()}>
                  发送
                </button>
              </div>
            )}
          </>
        ) : selected ? (
          <div className="friends-body">
            <div className="friends-status">
              <Spin size="small" /> 正在打开与 {chatTitle} 的会话…
            </div>
          </div>
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
                {friends.items.map((f) => {
                  const online = onlineOf(f) != null;
                  return (
                    <button key={f.id} type="button" className="friend-row" onClick={() => pick(f)}>
                      <FriendAvatar matrixId={f.matrixId} name={f.name} src={avatarSrcOf(f)} />
                      <span className="friend-text">
                        <span className="friend-name">{f.name}</span>
                        {f.subtitle && <span className="friend-sub">{f.subtitle}</span>}
                      </span>
                      <span className={`friend-online${online ? ' is-on' : ''}`}>
                        {online ? '在线' : '云聊天'}
                      </span>
                    </button>
                  );
                })}
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
