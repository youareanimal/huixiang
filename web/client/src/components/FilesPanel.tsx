import { useCallback, useEffect, useMemo, useRef, useState, type ChangeEvent } from 'react';
import { Input, Modal, SideSheet, Toast } from '@douyinfe/semi-ui';
import {
  IconChevronRight,
  IconDelete,
  IconDownload,
  IconEdit,
  IconFile,
  IconFolder,
  IconPlus,
  IconRefresh,
  IconUpload,
} from '@douyinfe/semi-icons';
import type { FileEntry, FilesMessage } from '../protocol';
import {
  deleteFile,
  downloadFile,
  formatBytes,
  joinPath,
  makeFolder,
  renameFile,
  saveBlob,
  uploadFile,
  type FileOpError,
} from '../files';

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

/** 一条传输（上传或下载）的进度。 */
interface Transfer {
  id: string;
  name: string;
  dir: 'up' | 'down';
  done: number;
  total: number;
  state: 'running' | 'done' | 'failed';
  error?: string;
}

/** 当前弹出的小对话框：新建、改名、删除确认、覆盖确认。 */
type Dialog =
  | { kind: 'mkdir' }
  | { kind: 'rename'; entry: FileEntry }
  | { kind: 'delete'; entry: FileEntry }
  | { kind: 'overwrite'; file: File; path: string; name: string }
  | null;

let seq = 0;
const nextId = () => `${Date.now()}-${(seq += 1)}`;

/**
 * 频道文件区：浏览、下载、上传、新建文件夹、改名、删除。
 *
 * 文件在 TeamSpeak 服务器上，所以安卓 App 和官方客户端能看到同一批；网页这里只是多开一个入口。
 * 动服务器上东西的操作（上传/删除/改名/建目录）都带确认，而且是登录后、有频道权限才做得了。
 *
 * 列表走 WebSocket（命令短、往返快），字节和写命令走 HTTP（见 files.ts 里的说明）。两条线在网关那头
 * 汇到同一个 `FileTransfer` 上。
 */
export default function FilesPanel({ open, cid, channelName, token, listing, onList, onClose }: Props) {
  const [path, setPath] = useState('/');
  const [transfers, setTransfers] = useState<Transfer[]>([]);
  const [dialog, setDialog] = useState<Dialog>(null);
  const [dialogText, setDialogText] = useState('');
  const [busy, setBusy] = useState(false);
  const timers = useRef<number[]>([]);
  const fileInput = useRef<HTMLInputElement>(null);

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

  /** 完成和失败的行留一会儿再消失，好让人看见结果而不是闪一下就没了。 */
  const fadeLater = useCallback((id: string) => {
    const timer = window.setTimeout(() => {
      setTransfers((prev) => prev.filter((t) => t.id !== id));
    }, 4000);
    timers.current.push(timer);
  }, []);

  const refresh = useCallback(() => onList(cid, path), [onList, cid, path]);

  const startDownload = useCallback(
    (entry: FileEntry) => {
      if (!token) {
        Toast.error('还没连上服务器');
        return;
      }
      const id = nextId();
      setTransfers((prev) => [
        ...prev,
        { id, name: entry.name, dir: 'down', done: 0, total: entry.size, state: 'running' },
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

  /** 真正上传一个文件；overwrite 为 false 撞到同名（409）时弹覆盖确认。 */
  const doUpload = useCallback(
    (file: File, overwrite: boolean) => {
      if (!token) {
        Toast.error('还没连上服务器');
        return;
      }
      const target = joinPath(path, file.name);
      const id = nextId();
      setTransfers((prev) => [
        ...prev,
        { id, name: file.name, dir: 'up', done: 0, total: file.size, state: 'running' },
      ]);
      void uploadFile({
        token,
        cid,
        path: target,
        file,
        overwrite,
        onProgress: (p) => update(id, { done: p.done, total: p.total }),
      })
        .then(() => {
          update(id, { state: 'done', done: file.size, total: file.size });
          fadeLater(id);
          refresh();
        })
        .catch((e: unknown) => {
          const msg = e instanceof Error ? e.message : String(e);
          const status = (e as FileOpError)?.status;
          if (status === 409 && !overwrite) {
            // 同名文件：把这条失败行收掉，弹确认，确认后带 overwrite 重传
            setTransfers((prev) => prev.filter((t) => t.id !== id));
            setDialog({ kind: 'overwrite', file, path: target, name: file.name });
            return;
          }
          update(id, { state: 'failed', error: msg });
          Toast.error(`${file.name}：${msg}`);
          fadeLater(id);
        });
    },
    [token, cid, path, update, fadeLater, refresh],
  );

  const onPickFiles = useCallback(
    (e: ChangeEvent<HTMLInputElement>) => {
      const files = Array.from(e.target.files ?? []);
      e.target.value = '';
      for (const f of files) doUpload(f, false);
    },
    [doUpload],
  );

  const openMkdir = useCallback(() => {
    setDialogText('');
    setDialog({ kind: 'mkdir' });
  }, []);

  const openRename = useCallback((entry: FileEntry) => {
    setDialogText(entry.name);
    setDialog({ kind: 'rename', entry });
  }, []);

  const openDelete = useCallback((entry: FileEntry) => {
    setDialog({ kind: 'delete', entry });
  }, []);

  const closeDialog = useCallback(() => {
    if (!busy) setDialog(null);
  }, [busy]);

  const confirmDialog = useCallback(async () => {
    if (!dialog || busy) return;
    const name = dialogText.trim();
    if ((dialog.kind === 'mkdir' || dialog.kind === 'rename') && (!name || name.includes('/'))) {
      Toast.error('名字不能为空，也不能包含斜杠');
      return;
    }
    setBusy(true);
    try {
      if (dialog.kind === 'mkdir') {
        await makeFolder({ token, cid, path: joinPath(path, name) });
      } else if (dialog.kind === 'rename') {
        await renameFile({ token, cid, path: joinPath(path, dialog.entry.name), name });
      } else if (dialog.kind === 'delete') {
        await deleteFile({ token, cid, path: joinPath(path, dialog.entry.name) });
      } else if (dialog.kind === 'overwrite') {
        setDialog(null);
        setBusy(false);
        doUpload(dialog.file, true);
        return;
      }
      setDialog(null);
      refresh();
    } catch (e: unknown) {
      Toast.error(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }, [dialog, dialogText, busy, token, cid, path, refresh, doUpload]);

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

  const dialogTitle =
    dialog?.kind === 'mkdir'
      ? '新建文件夹'
      : dialog?.kind === 'rename'
        ? '重命名'
        : dialog?.kind === 'delete'
          ? '删除'
          : '同名文件已存在';
  const okText =
    dialog?.kind === 'delete' ? '删除' : dialog?.kind === 'overwrite' ? '覆盖' : dialog?.kind === 'rename' ? '保存' : '创建';
  const okDanger = dialog?.kind === 'delete' || dialog?.kind === 'overwrite';

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
        <div className="files-sub">{channelName} · 服务器上这个频道的文件，可上传、下载、改名</div>

        <input ref={fileInput} type="file" multiple style={{ display: 'none' }} onChange={onPickFiles} />

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
          <div className="files-tools">
            <button
              type="button"
              className="icon-btn"
              title="上传文件"
              aria-label="上传文件"
              disabled={!token}
              onClick={() => fileInput.current?.click()}
            >
              <IconUpload size="small" />
            </button>
            <button
              type="button"
              className="icon-btn"
              title="新建文件夹"
              aria-label="新建文件夹"
              disabled={!token}
              onClick={openMkdir}
            >
              <IconPlus size="small" />
            </button>
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
        </div>

        {transfers.length > 0 && (
          <ul className="files-transfers">
            {transfers.map((t) => {
              const pct = t.total > 0 ? Math.min(100, Math.round((t.done / t.total) * 100)) : 0;
              return (
                <li key={t.id} className={`files-transfer is-${t.state}`}>
                  <div className="files-transfer-head">
                    <span className="files-transfer-name" title={t.name}>
                      {t.dir === 'up' ? '↑' : '↓'} {t.name}
                    </span>
                    <span className="files-transfer-note">
                      {t.state === 'running' && `${pct}%`}
                      {t.state === 'done' && (t.dir === 'up' ? '已上传' : '已保存')}
                      {t.state === 'failed' && '失败'}
                    </span>
                  </div>
                  <div className="files-bar-track">
                    <div className={`files-bar-fill is-${t.state}`} style={{ width: `${pct}%` }} />
                  </div>
                  {t.state === 'failed' && <div className="files-transfer-err">{t.error}</div>}
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
                <div className="files-actions">
                  {!entry.folder && (
                    <button
                      type="button"
                      className="files-act"
                      title="下载"
                      aria-label={`下载 ${entry.name}`}
                      onClick={() => startDownload(entry)}
                    >
                      <IconDownload size="small" />
                    </button>
                  )}
                  <button
                    type="button"
                    className="files-act"
                    title="重命名"
                    aria-label={`重命名 ${entry.name}`}
                    disabled={!token}
                    onClick={() => openRename(entry)}
                  >
                    <IconEdit size="small" />
                  </button>
                  <button
                    type="button"
                    className="files-act is-danger"
                    title="删除"
                    aria-label={`删除 ${entry.name}`}
                    disabled={!token}
                    onClick={() => openDelete(entry)}
                  >
                    <IconDelete size="small" />
                  </button>
                </div>
              </div>
            ))}
        </div>
      </div>

      <Modal
        title={dialogTitle}
        visible={dialog !== null}
        onOk={confirmDialog}
        onCancel={closeDialog}
        okText={okText}
        cancelText="取消"
        confirmLoading={busy}
        okButtonProps={okDanger ? { type: 'danger' } : undefined}
      >
        {dialog?.kind === 'mkdir' || dialog?.kind === 'rename' ? (
          <Input
            value={dialogText}
            onChange={setDialogText}
            placeholder="文件夹 / 文件名字"
            autoFocus
            onEnterPress={confirmDialog}
          />
        ) : dialog?.kind === 'delete' ? (
          <div className="files-dialog-text">
            确定删除「{dialog.entry.name}」吗？{dialog.entry.folder ? '文件夹会连同里面的内容一起删除。' : ''}
            此操作不可撤销。
          </div>
        ) : dialog?.kind === 'overwrite' ? (
          <div className="files-dialog-text">「{dialog.name}」已存在，确定覆盖？原文件会被替换。</div>
        ) : null}
      </Modal>
    </SideSheet>
  );
}
