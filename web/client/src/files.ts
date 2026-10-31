// 频道文件的字节。面板是只读的，所以这里只有下载。
//
// 命令走 WebSocket（要哪一层目录的列表），字节走 HTTP——WebSocket 一帧上限 64 KB，搬一个大文件要拆成
// 上千帧、自己写重组和流控；HTTP 现成，还顺手给了进度和取消。网关那个端点见 Gateway.kt 的 /files/download。
//
// 认证靠状态快照里下发的 fileToken：它只代表这一个标签页的这次会话，别人拿不到也用不上。

export interface TransferProgress {
  done: number;
  total: number;
}

/** 拼给网关的查询串：路径是虚拟的（"/报表/三月.pdf"），交给 URLSearchParams 统一转义。 */
function downloadUrl(cid: number, path: string) {
  return `/files/download?${new URLSearchParams({ cid: String(cid), path })}`;
}

/** 网关用纯文本回错误（中文，人话），所以失败时读 text 就够了。 */
async function failureText(res: Response): Promise<string> {
  const text = (await res.text().catch(() => '')).trim();
  return text || `下载失败（HTTP ${res.status}）`;
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
  const res = await fetch(downloadUrl(opts.cid, opts.path), {
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

/** 拼出 "/a/b/c.txt"；dir 为 "/" 时不留双斜杠。 */
export function joinPath(dir: string, name: string): string {
  return dir === '/' ? `/${name}` : `${dir}/${name}`;
}
