import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { SideSheet, Toast } from '@douyinfe/semi-ui';
import { IconChevronRight, IconDownload, IconFile, IconFolder, IconRefresh, IconUpload } from '@douyinfe/semi-icons';
import type { FileEntry, FilesMessage } from '../protocol';
import { downloadFile, formatBytes, joinPath, saveBlob, uploadFile, type TransferProgress } from '../files';

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
  /** 传完了说一声（一次拖进来的几个合成一条），聊天那边好接一句。 */
  onUploaded?: (paths: string[]) => void;
}

/** 一条传输的状态。上传和下载共用一套显示，方向上才有区别。 */
interface Transfer {
  id: string;
  name: string;
  direction: 'up' | 'down';
  done: number;
  total: number;
  state: 'running' | 'done' | 'failed' | 'exists';
  error?: string;
}

let seq = 0;
const nextId = () => `${Date.now()}-${(seq += 1)}`;

/**
 * 频道文件区。
 *
 * 文件落在 TeamSpeak 服务器上（`ftinitupload` 到频道的虚拟路径），不是网关或浏览器的某个缓存目录：
 * 所以安卓 App 和官方客户端能在自己的文件浏览器里看到同一批文件，网页只是多开了一个入口。
 *
 * 列表走 WebSocket（命令短、往返快），字节走 HTTP（见 files.ts 里的说明）。两条线在网关那头汇到同一个
 * `FileTransfer` 上。
 */
export default function FilesPanel({ open, cid, channelName, token, listing, onList, onClose, onUploaded }: Props) {
  const [path, setPath] = useState('/');
  const [transfers, setTransfers] = useState<Transfer[]>([]);
  const [dragging, setDragging] = useState(false);
  const pickRef = useRef<HTMLInputElement>(null);
  const timers = useRef<number[]>([]);
  /**
   * 重名时要重传，得把当初那个 File 拿回来。任务行里只存名字是不够的——用户已经换过目录、重新选过文件，
   * 只能在这里留一份引用。
   */
  const filesFor = useRef(new Map<string, File>());

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

  const update = useCallback((id: string, patch: Partial<Transfer>) => {
    setTransfers((prev) => prev.map((t) => (t.id === id ? { ...t, ...patch } : t)));
  }, []);

  /** 完成和失败的行留一会儿再消失，好让人看见「传完了」而不是闪一下就没了。 */
  const fadeLater = useCallback((id: string) => {
    const timer = window.setTimeout(() => {
      setTransfers((prev) => prev.filter((t) => t.id !== id));
    }, 4000);
    timers.current.push(timer);
  }, []);

  const startUpload = useCallback(
    (file: File, overwrite = false, reuse?: string): Promise<{ ok: boolean; target: string }> => {
      const target = joinPath(path, file.name);
      if (!token) {
        Toast.error('还没连上服务器');
        return Promise.resolve({ ok: false, target });
      }
      const id = reuse ?? nextId();
      if (reuse) update(id, { state: 'running', done: 0, error: undefined });
      else
        setTransfers((prev) => [
          ...prev,
          { id, name: file.name, direction: 'up', done: 0, total: file.size, state: 'running' },
        ]);

      return uploadFile({
        token,
        cid,
        path: target,
        file,
        overwrite,
        onProgress: (p: TransferProgress) => update(id, { done: p.done, total: p.total }),
      })
        .then(() => {
          update(id, { state: 'done', done: file.size, total: file.size });
          onList(cid, path);
          fadeLater(id);
          return { ok: true, target };
        })
        .catch((e: unknown) => {
          const msg = e instanceof Error ? e.message : String(e);
          const exists = msg.includes('同名文件');
          // 重名的行留在原地等人点「覆盖」，别的错误过几秒自己消失
          update(id, { state: exists ? 'exists' : 'failed', error: msg });
          if (!exists) fadeLater(id);
          return { ok: false, target };
        });
    },
    [token, cid, path, update, onList, fadeLater],
  );

  const uploadMany = useCallback(
    (list: FileList | File[] | null) => {
      if (!list) return;
      const jobs: Array<Promise<{ ok: boolean; target: string }>> = [];
      for (const file of Array.from(list)) {
        // 零字节的先挡一下：TeamSpeak 传空文件只会回来一条没意义的错误
        if (file.size === 0) {
          Toast.warning(`${file.name} 是空文件，跳过`);
          continue;
        }
        const id = nextId();
        filesFor.current.set(id, file);
        jobs.push(startUpload(file, false, id));
      }
      if (jobs.length === 0) return;
      // 一次拖进来的几个文件合成一条消息，否则频道里会刷一屏
      void Promise.all(jobs).then((results) => {
        const done = results.filter((r) => r.ok).map((r) => r.target);
        if (done.length > 0) onUploaded?.(done);
      });
    },
    [startUpload, onUploaded],
  );

  const startDownload = useCallback(
    (entry: FileEntry) => {
      if (!token) return;
      const id = nextId();
      setTransfers((prev) => [
        ...prev,
        { id, name: entry.name, direction: 'down', done: 0, total: entry.size, state: 'running' },
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
      footer={
        <div className="files-foot">
          <button type="button" className="files-pick" onClick={() => pickRef.current?.click()}>
            <IconUpload size="small" />
            选择文件上传
          </button>
          <span className="files-foot-hint">也可以把文件拖进来</span>
          <input
            ref={pickRef}
            type="file"
            multiple
            hidden
            onChange={(e) => {
              uploadMany(e.target.files);
              e.target.value = '';
            }}
          />
        </div>
      }
    >
      <div
        className={`files-body${dragging ? ' is-dragging' : ''}`}
        onDragOver={(e) => {
          if (e.dataTransfer.types.includes('Files')) {
            e.preventDefault();
            setDragging(true);
          }
        }}
        onDragLeave={(e) => {
          if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setDragging(false);
        }}
        onDrop={(e) => {
          e.preventDefault();
          setDragging(false);
          uploadMany(e.dataTransfer.files);
        }}
      >
        <div className="files-sub">
          {channelName} · 文件存在服务器上，同频道的人都能取
        </div>

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

        {transfers.length > 0 && (
          <ul className="files-transfers">
            {transfers.map((t) => {
              const pct = t.total > 0 ? Math.min(100, Math.round((t.done / t.total) * 100)) : 0;
              return (
                <li key={t.id} className={`files-transfer is-${t.state}`}>
                  <div className="files-transfer-head">
                    <span className="files-transfer-name" title={t.name}>
                      {t.direction === 'up' ? '↑' : '↓'} {t.name}
                    </span>
                    <span className="files-transfer-note">
                      {t.state === 'running' && `${pct}%`}
                      {t.state === 'done' && '完成'}
                      {t.state === 'failed' && '失败'}
                      {t.state === 'exists' && '重名'}
                    </span>
                  </div>
                  <div className="files-bar-track">
                    <div className={`files-bar-fill is-${t.state}`} style={{ width: `${t.state === 'exists' ? 100 : pct}%` }} />
                  </div>
                  {t.state === 'failed' && <div className="files-transfer-err">{t.error}</div>}
                  {t.state === 'exists' && (
                    <div className="files-transfer-err">
                      已经有一个同名文件了
                      <button
                        type="button"
                        className="files-overwrite"
                        onClick={() => {
                          const file = filesFor.current.get(t.id);
                          if (file) startUpload(file, true, t.id);
                        }}
                      >
                        覆盖它
                      </button>
                    </div>
                  )}
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
              <span>传点东西上来，同频道的人就能拿了</span>
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

        {dragging && <div className="files-drop">松手就传上去</div>}
      </div>
    </SideSheet>
  );
}
