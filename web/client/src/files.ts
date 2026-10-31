// 频道文件的字节。
//
// 命令走 WebSocket（要哪一层目录的列表），字节走 HTTP——WebSocket 一帧上限 64 KB，搬一个 200 MB 的文件
// 要拆成三千多帧，分片、重组、流控全得自己写；HTTP 这三样都是现成的，还顺手给了进度、取消和一个准确的
// 错误码。网关那两个端点见 Gateway.kt 的 /files/upload 和 /files/download。
//
// 认证靠状态快照里下发的 fileToken：它只代表这一个标签页的这次会话，别人拿不到也用不上。

export interface TransferProgress {
  done: number;
  total: number;
}

/** 拼给网关的查询串：路径是虚拟的（"/报表/三月.pdf"），交给 URLSearchParams 统一转义。 */
function transferUrl(kind: 'upload' | 'download', cid: number, path: string, extra?: Record<string, string>) {
  const q = new URLSearchParams({ cid: String(cid), path, ...extra });
  return `/files/${kind}?${q}`;
}

/** 网关用纯文本回错误（中文，人话），所以失败时读 text 就够了。 */
async function failureText(res: Response): Promise<string> {
  const text = (await res.text().catch(() => '')).trim();
  return text || `传输失败（HTTP ${res.status}）`;
}

export interface UploadOptions {
  token: string;
  cid: number;
  /** 目标路径，含文件名："/report.pdf" 或 "/资料/report.pdf"。 */
  path: string;
  file: File;
  /** 同名文件是否覆盖；否则服务器会拒绝（同名文件已经在了）。 */
  overwrite?: boolean;
  onProgress?: (p: TransferProgress) => void;
  signal?: AbortSignal;
}

/**
 * 上传一个文件到频道的文件区（服务器上，不是网关的磁盘）。
 *
 * 用 XHR 而不是 fetch：fetch 至今没有可用的上传进度事件，而一个看不到进度的上传等于让人干等。
 */
export function uploadFile(opts: UploadOptions): Promise<void> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', transferUrl('upload', opts.cid, opts.path, { overwrite: opts.overwrite ? '1' : '0' }));
    xhr.setRequestHeader('X-File-Token', opts.token);
    xhr.setRequestHeader('Content-Type', 'application/octet-stream');

    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) opts.onProgress?.({ done: e.loaded, total: e.total });
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        opts.onProgress?.({ done: opts.file.size, total: opts.file.size });
        resolve();
      } else {
        reject(new Error((xhr.responseText || '').trim() || `上传失败（HTTP ${xhr.status}）`));
      }
    };
    xhr.onerror = () => reject(new Error('网络上没走通，文件没传上去'));
    xhr.onabort = () => reject(new DOMException('上传已取消', 'AbortError'));

    opts.signal?.addEventListener('abort', () => xhr.abort(), { once: true });
    xhr.send(opts.file);
  });
}

export interface DownloadOptions {
  token: string;
  cid: number;
  path: string;
  /** 列表里已经有大小了，服务器的 Content-Length 万一没有就用它算进度。 */
  expectedSize?: number;
  onProgress?: (p: TransferProgress) => void;
  signal?: AbortSignal;
}

/**
 * 把一个文件读成 Blob。
 *
 * 一块一块读而不是 `res.blob()`：这样进度是真的在动，而且中途断线时能立刻知道，不用等一个永远不来的
 * 完整响应。文件大小上限由网关管（1 GiB），浏览器这边不额外设。
 */
export async function downloadFile(opts: DownloadOptions): Promise<Blob> {
  const res = await fetch(transferUrl('download', opts.cid, opts.path), {
    headers: { 'X-File-Token': opts.token },
    signal: opts.signal,
  });
  if (!res.ok) throw new Error(await failureText(res));

  const total = Number(res.headers.get('Content-Length')) || opts.expectedSize || 0;
  if (!res.body) return res.blob();

  const reader = res.body.getReader();
  const chunks: Uint8Array[] = [];
  let done = 0;
  for (;;) {
    const { done: end, value } = await reader.read();
    if (end) break;
    if (value) {
      chunks.push(value);
      done += value.length;
      opts.onProgress?.({ done, total });
    }
  }
  // 服务器说了多长就要收够：少了就是半路断了，交给浏览器的会是个坏文件
  if (total > 0 && done < total) throw new Error(`只收到 ${done} / ${total} 字节，连接中途断了`);
  return new Blob(chunks as BlobPart[]);
}

/** 交给浏览器的下载器存盘，文件名照旧。 */
export function saveBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.style.display = 'none';
  document.body.appendChild(a);
  a.click();
  a.remove();
  // 立刻 revoke 会让有些浏览器的下载拿不到内容，留一会儿再收
  window.setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

/** 1024 进制，和资源管理器说法一致。 */
export function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let v = n / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i += 1;
  }
  return `${v < 10 ? v.toFixed(1) : Math.round(v)} ${units[i]}`;
}

/** 把 "/a/b/c.txt" 和上一层目录分开。 */
export function splitPath(path: string): { dir: string; name: string } {
  const cut = path.lastIndexOf('/');
  if (cut <= 0) return { dir: '/', name: path.slice(1) };
  return { dir: path.slice(0, cut), name: path.slice(cut + 1) };
}

/** 拼出 "/a/b/c.txt"；dir 为 "/" 时不留双斜杠。 */
export function joinPath(dir: string, name: string): string {
  return dir === '/' ? `/${name}` : `${dir}/${name}`;
}
