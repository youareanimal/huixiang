/**
 * 桌面通知：有新消息时弹一条系统的通知，切到别的窗口/标签页也能看见。
 *
 * 浏览器只允许「用户手势里」申请权限，所以第一次必须在点击开关时调用
 * requestNotifyPermission，不能进页面就自动问。没有权限、浏览器不支持
 * （比如非 HTTPS 的 http 站点）都安静地当没这回事，不影响聊天本身。
 */

export function notifySupported(): boolean {
  return typeof window !== 'undefined' && 'Notification' in window;
}

export function notifyPermission(): NotificationPermission | 'unsupported' {
  if (!notifySupported()) return 'unsupported';
  return Notification.permission;
}

/** 申请通知权限；已经授权/拒绝过的直接返回现状，不会重复弹窗。 */
export async function requestNotifyPermission(): Promise<NotificationPermission | 'unsupported'> {
  if (!notifySupported()) return 'unsupported';
  if (Notification.permission !== 'default') return Notification.permission;
  try {
    return await Notification.requestPermission();
  } catch {
    return Notification.permission;
  }
}

export interface MessageNotice {
  title: string;
  body: string;
  /** 发送者头像的绝对地址；没有就留空。 */
  icon?: string;
  /** 同一个会话的新消息共用一个 tag，系统通知里是替换而不是堆一屏。 */
  tag: string;
  onClick?: () => void;
}

/** 弹一条消息通知。没权限或不支持时返回 null，不抛错。 */
export function showMessageNotice(n: MessageNotice): Notification | null {
  if (!notifySupported() || Notification.permission !== 'granted') return null;
  try {
    const options = {
      body: n.body || '（空消息）',
      icon: n.icon || undefined,
      tag: n.tag,
      // 同一个 tag 再来新消息时重新提醒一次（否则只是静默替换，看不到新内容）
      renotify: true,
      silent: false,
    } as NotificationOptions;
    const note = new Notification(n.title, options);
    note.onclick = () => {
      window.focus();
      n.onClick?.();
      note.close();
    };
    return note;
  } catch {
    // 部分移动端浏览器禁止页面直接 new Notification（要走 Service Worker），
    // 这里安静降级，聊天和提示都照常。
    return null;
  }
}
