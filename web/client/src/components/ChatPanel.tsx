import { useEffect, useRef, useState } from 'react';
import { Avatar, Button, Empty, Typography } from '@douyinfe/semi-ui';
import { IconSend } from '@douyinfe/semi-icons';
import type { ChatLine } from '../protocol';

const { Text } = Typography;

interface Props {
  lines: ChatLine[];
  ownId: number;
  title: string;
  hint: string;
  placeholder: string;
  onSend: (text: string) => void;
}

const hhmm = (ms: number) =>
  new Date(ms).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });

/** 一个会话的消息流：自己的靠右，别人的靠左。频道和私聊共用这个组件。 */
export default function ChatPanel({ lines, ownId, title, hint, placeholder, onSend }: Props) {
  const [draft, setDraft] = useState('');
  const listRef = useRef<HTMLDivElement>(null);
  const stick = useRef(true);

  useEffect(() => {
    const el = listRef.current;
    if (el && stick.current) el.scrollTop = el.scrollHeight;
  }, [lines]);

  // 换会话时回到最底部
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
    <div className="chat">
      <div className="chat-head">
        <Text strong>{title}</Text>
        <Text type="tertiary" size="small">
          {hint}
        </Text>
      </div>

      <div
        className="chat-list"
        ref={listRef}
        onScroll={(e) => {
          const el = e.currentTarget;
          stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
        }}
      >
        {lines.length === 0 && <Empty title="还没有消息" description="说点什么吧" style={{ padding: '48px 0' }} />}
        {lines.map((line, i) => {
          const mine = line.from === ownId;
          return (
            <div key={`${line.time}-${i}`} className={`msg${mine ? ' msg-mine' : ''}`}>
              <Avatar size="small" color={mine ? 'violet' : 'light-blue'} className="msg-avatar">
                {(line.name || '?').slice(0, 1).toUpperCase()}
              </Avatar>
              <div className="msg-body">
                <div className="msg-meta">
                  <span className="msg-name">{line.name}</span>
                  <span className="msg-time">{hhmm(line.time)}</span>
                </div>
                <div className="msg-text">{line.text}</div>
              </div>
            </div>
          );
        })}
      </div>

      <div className="chat-input">
        <textarea
          className="chat-textarea"
          placeholder={placeholder}
          value={draft}
          rows={2}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
              e.preventDefault();
              send();
            }
          }}
        />
        <Button
          theme="solid"
          type="primary"
          icon={<IconSend />}
          onClick={send}
          disabled={!draft.trim()}
          className="chat-send"
        >
          发送
        </Button>
      </div>
    </div>
  );
}
