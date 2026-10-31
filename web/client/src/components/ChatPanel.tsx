import { useEffect, useRef, useState } from 'react';
import { IconChevronDown, IconChevronUp, IconSend } from '@douyinfe/semi-icons';
import type { ChatLine } from '../protocol';
import { avatarColor, initial } from '../ui';

interface Props {
  lines: ChatLine[];
  ownId: number;
  title: string;
  hint: string;
  placeholder: string;
  collapsed: boolean;
  onToggleCollapsed: () => void;
  onSend: (text: string) => void;
}

const hhmm = (ms: number) =>
  new Date(ms).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });

/**
 * 聊天区：语音优先的页面里它是配角，所以默认矮、可折叠。
 * 自己的消息用品牌实色，和「频道选中」的浅紫区分开，两种语义不撞色。
 */
export default function ChatPanel({
  lines,
  ownId,
  title,
  hint,
  placeholder,
  collapsed,
  onToggleCollapsed,
  onSend,
}: Props) {
  const [draft, setDraft] = useState('');
  const listRef = useRef<HTMLDivElement>(null);
  const stick = useRef(true);

  useEffect(() => {
    const el = listRef.current;
    if (el && stick.current && !collapsed) el.scrollTop = el.scrollHeight;
  }, [lines, collapsed]);

  // 换频道时回到最底部，草稿清掉
  useEffect(() => {
    const el = listRef.current;
    if (el) {
      el.scrollTop = el.scrollHeight;
      stick.current = true;
    }
    setDraft('');
  }, [title]);

  const send = () => {
    const text = draft.trim();
    if (!text) return;
    onSend(text);
    setDraft('');
  };

  return (
    <section className={`chat${collapsed ? ' is-collapsed' : ''}`} aria-label="聊天">
      <div className="chat-head">
        <div className="chat-head-left">
          <span className="chat-title">聊天</span>
          <span className="chat-hint">{hint}</span>
        </div>
        <button
          type="button"
          className="icon-btn"
          onClick={onToggleCollapsed}
          aria-expanded={!collapsed}
          title={collapsed ? '展开聊天' : '收起聊天'}
        >
          {collapsed ? <IconChevronUp /> : <IconChevronDown />}
        </button>
      </div>

      {!collapsed && (
        <>
          <div
            className="chat-list"
            ref={listRef}
            onScroll={(e) => {
              const el = e.currentTarget;
              stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
            }}
          >
            {lines.length === 0 && (
              <div className="chat-empty">
                <span className="chat-empty-title">还没有消息</span>
                <span>说点什么吧</span>
              </div>
            )}
            {lines.map((line, i) => {
              const mine = line.from === ownId;
              return (
                <div key={`${line.time}-${i}`} className={`msg${mine ? ' msg-mine' : ''}`}>
                  <div
                    className="msg-avatar"
                    style={{ background: avatarColor(mine ? 'me' : line.name || line.from) }}
                    aria-hidden="true"
                  >
                    {initial(line.name)}
                  </div>
                  <div className="msg-body">
                    <div className="msg-meta">
                      <span className="msg-name">{line.name}</span>
                      <span className="msg-time">{hhmm(line.time)}</span>
                    </div>
                    <div className="bubble">{line.text}</div>
                  </div>
                </div>
              );
            })}
          </div>

          <div className="chat-input">
            <div className="chat-field">
              <textarea
                className="chat-textarea"
                placeholder={placeholder}
                value={draft}
                rows={1}
                aria-label="消息输入框"
                onChange={(e) => {
                  setDraft(e.target.value);
                  const el = e.target;
                  el.style.height = 'auto';
                  el.style.height = `${Math.min(el.scrollHeight, 120)}px`;
                }}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                    e.preventDefault();
                    send();
                  }
                }}
              />
              <span className="key-hint">Enter 发送 · Shift+Enter 换行</span>
            </div>
            <button
              type="button"
              className="send-btn"
              onClick={send}
              disabled={!draft.trim()}
              aria-label="发送"
              title="发送"
            >
              <IconSend />
            </button>
          </div>
        </>
      )}
    </section>
  );
}
