// 频道进出的轻提示：固定右下角、堆叠、几秒自动消失，不打断操作。
// 纯展示组件，队列的增删由 App 负责（见 App.tsx 里 diffPresence 的调用处）。

export interface PresenceNotice {
  id: number;
  text: string;
  kind: 'join' | 'leave';
}

export default function PresenceToasts({ items }: { items: PresenceNotice[] }) {
  if (!items.length) return null;
  return (
    <div className="presence-toasts" aria-live="polite" aria-atomic="false">
      {items.map((it) => (
        <div key={it.id} className={`presence-toast presence-${it.kind}`}>
          <span className="presence-dot" aria-hidden="true" />
          <span className="presence-text">{it.text}</span>
        </div>
      ))}
    </div>
  );
}
