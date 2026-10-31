import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { SideSheet, Toast } from '@douyinfe/semi-ui';
import { IconChevronRight, IconDownload, IconFile, IconFolder, IconRefresh } from '@douyinfe/semi-icons';
import type { FileEntry, FilesMessage } from '../protocol';
import { downloadFile, formatBytes, joinPath, saveBlob } from '../files';

interface Props {
  open: boolean;
  /** 当前频道：文件区是按频道分的，切频道就是另一个目录树。 */
  cid: number;
  channelName: string;
  /** 会话令牌；没连上时是空串。 */
  token: string;
  /** 最近一次收到的列表。带上 cid 和 path，才能认出是不是自己等的那一份（换目录时旧的会晚到）。 */
  listing: FilesMessage | null;
  onList: (cid: number, path: string) => void;
  onClose: () => void;
}

/** 一条下载的状态。只读面板只有这一个方向。 */
interface Download {
  id: string;
  name: string;
  done: number;
  total: number;
  state: 'running' | 'done' | 'failed';
  error?: string;
}

let seq = 0;
const nextId = () => `${Date.now()}-${(seq += 1)}`;

/**
 * 频道文件区，只读：浏览目录、下载。
 *
 * 文件在 TeamSpeak 服务器上，所以安卓 App 和官方客户端能看到同一批；网页这里只是多开一个查看和取用的
 * 入口，不提供上传、删除、改名——那些要动服务器上的东西，值得单独做一次带确认的交互。
 *
 * 列表走 WebSocket（命令短、往返快），字节走 HTTP（见 files.ts 里的说明）。两条线在网关那头汇到同一个
 * `FileTransfer` 上。
 */
export default function FilesPanel({ open, cid, channelName, token, listing, onList, onClose }: Props) {
  const [path, setPath] = useState('/');
  const [downloads, setDownloads] = useState<Download[]>([]);
  const timers = useRef<number[]>([]);

  // 打开面板、换目录、换频道都要重新问一次；关着的时候不问
  useEffect(() => {
    if (open) onList(cid, path);
  }, [open, cid, path, onList]);

  // 换频道回到根目录，否则会停在上一个频道里根本不存在的路径
  useEffect(() => {
    setPath('/');
  }, [cid]);

  useEffect(() => {
    return () => {
      for (const t of timers.current) window.clearTimeout(t);
    };
  }, []);

  const current = listing && listing.cid === cid && listing.path === path ? listing : null;
  const loading = open && !current;

  const update = useCallback((id: string, patch: Partial<Download>) => {
    setDownloads((prev) => prev.map((d) => (d.id === id ? { ...d, ...patch } : d)));
  }, []);

  /** 完成和失败的行留一会儿再消失，好让人看见「下好了」而不是闪一下就没了。 */
  const fadeLater = useCallback((id: string) => {
    const timer = window.setTimeout(() => {
      setDownloads((prev) => prev.filter((d) => d.id !== id));
    }, 4000);
    timers.current.push(timer);
  }, []);

  const startDownload = useCallback(
    (entry: FileEntry) => {
      if (!token) {
        Toast.error('还没连上服务器');
        return;
      }
      const id = nextId();
      setDownloads((prev) => [
        ...prev,
        { id, name: entry.name, done: 0, total: entry.size, state: 'running' },
      ]);
      void downloadFile({
        token,
        cid,
        path: joinPath(path, entry.name),
        expectedSize: entry.size,
        onProgress: (p) => update(id, { done: p.done, total: p.total }),
      })
        .then((blob) => {
          saveBlob(blob, entry.name);
          update(id, { state: 'done', done: blob.size, total: blob.size });
          fadeLater(id);
        })
        .catch((e: unknown) => {
          const msg = e instanceof Error ? e.message : String(e);
          update(id, { state: 'failed', error: msg });
          Toast.error(`${entry.name}：${msg}`);
          fadeLater(id);
        });
    },
    [token, cid, path, update, fadeLater],
  );

  const crumbs = useMemo(() => {
    const parts = path.split('/').filter(Boolean);
    const out = [{ name: '根目录', path: '/' }];
    let acc = '';
    for (const p of parts) {
      acc += `/${p}`;
      out.push({ name: p, path: acc });
    }
    return out;
  }, [path]);

  const entries = current?.entries ?? [];
  const failed = current?.error ?? null;

  return (
    <SideSheet
      title="频道文件"
      visible={open}
      onCancel={onClose}
      width={430}
      placement="right"
      className="files-sheet"
    >
      <div className="files-body">
        <div className="files-sub">{channelName} · 服务器上这个频道的文件，点右边下载</div>

        <div className="files-bar">
          <div className="files-crumbs">
            {crumbs.map((c, i) => (
              <span key={c.path} className="files-crumb-wrap">
                {i > 0 && <IconChevronRight size="small" className="files-crumb-sep" />}
                <button
                  type="button"
                  className={`files-crumb${i === crumbs.length - 1 ? ' is-here' : ''}`}
                  onClick={() => setPath(c.path)}
                  disabled={i === crumbs.length - 1}
                >
                  {c.name}
                </button>
              </span>
            ))}
          </div>
          <button
            type="button"
            className="icon-btn"
            title="刷新"
            aria-label="刷新"
            onClick={() => onList(cid, path)}
          >
            <IconRefresh size="small" />
          </button>
        </div>

        {downloads.length > 0 && (
          <ul className="files-transfers">
            {downloads.map((d) => {
              const pct = d.total > 0 ? Math.min(100, Math.round((d.done / d.total) * 100)) : 0;
              return (
                <li key={d.id} className={`files-transfer is-${d.state}`}>
                  <div className="files-transfer-head">
                    <span className="files-transfer-name" title={d.name}>
                      ↓ {d.name}
                    </span>
                    <span className="files-transfer-note">
                      {d.state === 'running' && `${pct}%`}
                      {d.state === 'done' && '已保存'}
                      {d.state === 'failed' && '失败'}
                    </span>
                  </div>
                  <div className="files-bar-track">
                    <div className={`files-bar-fill is-${d.state}`} style={{ width: `${pct}%` }} />
                  </div>
                  {d.state === 'failed' && <div className="files-transfer-err">{d.error}</div>}
                </li>
              );
            })}
          </ul>
        )}

        <div className="files-list">
          {loading && <div className="files-empty">正在读服务器上的目录…</div>}
          {!loading && failed && <div className="files-empty is-error">{failed}</div>}
          {!loading && !failed && entries.length === 0 && (
            <div className="files-empty">
              <span className="files-empty-title">这个文件夹是空的</span>
              <span>服务器上这个频道还没有文件</span>
            </div>
          )}
          {!loading &&
            !failed &&
            entries.map((entry) => (
              <div key={entry.name} className="files-row">
                <span className={`files-icon${entry.folder ? ' is-folder' : ''}`} aria-hidden="true">
                  {entry.folder ? <IconFolder /> : <IconFile />}
                </span>
                <div className="files-main">
                  {entry.folder ? (
                    <button type="button" className="files-name is-folder" onClick={() => setPath(joinPath(path, entry.name))}>
                      {entry.name}
                    </button>
                  ) : (
                    <span className="files-name" title={entry.name}>
                      {entry.name}
                    </span>
                  )}
                  <span className="files-meta">
                    {entry.folder ? '文件夹' : formatBytes(entry.size)}
                    {entry.time > 0 && ` · ${new Date(entry.time).toLocaleDateString('zh-CN')}`}
                  </span>
                </div>
                {!entry.folder && (
                  <button
                    type="button"
                    className="files-get"
                    title="下载"
                    aria-label={`下载 ${entry.name}`}
                    onClick={() => startDownload(entry)}
                  >
                    <IconDownload size="small" />
                  </button>
                )}
              </div>
            ))}
        </div>
      </div>
    </SideSheet>
  );
}
