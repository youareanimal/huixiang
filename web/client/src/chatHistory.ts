// 频道聊天的本地库。
//
// TeamSpeak 服务器不保存频道消息，所以「历史」是谁在听谁记下来的。网页版记在自己这台机器的
// IndexedDB 里：按「服务器 + 频道」各存一份，每份最多 300 条、90 天，规则和安卓端一致。
// 网关那边的 ChannelHistory 是可选的第二份：换浏览器、换设备，或者本地库还没攒到的部分，
// 可以问它要（见 App.tsx 的 loadHistory）。

export interface HistoryLine {
  time: number;
  from: number;
  name: string;
  uid?: string;
  text: string;
}

export interface HistoryPage {
  /** 按时间从旧到新。 */
  lines: HistoryLine[];
  /** 这个频道还有更早的。 */
  more: boolean;
}

/** 一个频道最多留多少条，和安卓端 ChatHistory.LINES 一致。 */
export const HISTORY_LINES = 300;
const HISTORY_DAYS = 90;
const DAY = 86_400_000;

const DB_NAME = 'huixiang-chat';
const DB_VERSION = 1;
const STORE = 'conversations';

interface Row {
  key: string;
  server: string;
  cid: number;
  lines: HistoryLine[];
}

/**
 * 服务器地址的规范形式：历史按它分档，所以换个大小写、补上默认端口，还是同一个服务器。
 * 规则和网关的 ChatHistory.serverKey 一致。
 */
export function serverKey(address: string): string {
  let s = address.trim().toLowerCase();
  s = s.replace(/^ts3server:\/\//, '');
  s = s.replace(/^\/+/, '');
  s = s.split('/')[0];
  s = s.replace(/:9987$/, '');
  return s;
}

const rowKey = (server: string, cid: number) => `${server}|${cid}`;

// ====== 底层：IndexedDB，没有就退回内存 ======

let dbp: Promise<IDBDatabase | null> | null = null;
/** 隐私模式或老浏览器没有 IndexedDB：这一页还能用，只是刷新就没了。 */
const memory = new Map<string, Row>();

function openDb(): Promise<IDBDatabase | null> {
  if (dbp) return dbp;
  dbp = new Promise((resolve) => {
    if (typeof indexedDB === 'undefined') {
      resolve(null);
      return;
    }
    let req: IDBOpenDBRequest;
    try {
      req = indexedDB.open(DB_NAME, DB_VERSION);
    } catch {
      resolve(null);
      return;
    }
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE, { keyPath: 'key' });
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => resolve(null);
    req.onblocked = () => resolve(null);
  });
  return dbp;
}

function readRow(rowKeyValue: string): Promise<Row | null> {
  return openDb().then(
    (db) =>
      new Promise<Row | null>((resolve) => {
        if (!db) {
          resolve(memory.get(rowKeyValue) ?? null);
          return;
        }
        try {
          const req = db.transaction(STORE, 'readonly').objectStore(STORE).get(rowKeyValue);
          req.onsuccess = () => resolve((req.result as Row | undefined) ?? null);
          req.onerror = () => resolve(null);
        } catch {
          resolve(null);
        }
      }),
  );
}

function writeRow(row: Row): Promise<void> {
  memory.set(row.key, row);
  return openDb().then(
    (db) =>
      new Promise<void>((resolve) => {
        if (!db) {
          resolve();
          return;
        }
        try {
          const tx = db.transaction(STORE, 'readwrite');
          tx.objectStore(STORE).put(row);
          tx.oncomplete = () => resolve();
          tx.onerror = () => resolve();
        } catch {
          resolve();
        }
      }),
  );
}

// ====== 读写：同一会话的读改写排成一队，不然两条消息同时进来会互相覆盖 ======

let chain: Promise<unknown> = Promise.resolve();
function serial<T>(task: () => Promise<T>): Promise<T> {
  const run = chain.then(task, task);
  chain = run.catch(() => undefined);
  return run;
}

const key = (line: { time: number; from: number; text: string }) => `${line.time}|${line.from}|${line.text}`;

/** 按时间排序并去重：同一个服务器的两份来源（本地和网关）可能重叠。 */
export function mergeLines<T extends { time: number; from: number; text: string }>(a: T[], b: T[]): T[] {
  const seen = new Set<string>();
  const out: T[] = [];
  for (const line of [...a, ...b]) {
    const k = key(line);
    if (seen.has(k)) continue;
    seen.add(k);
    out.push(line);
  }
  out.sort((x, y) => x.time - y.time || x.from - y.from);
  return out;
}

function trim(lines: HistoryLine[]): HistoryLine[] {
  const from = Date.now() - HISTORY_DAYS * DAY;
  const kept = lines.filter((l) => l.time >= from);
  return kept.length > HISTORY_LINES ? kept.slice(-HISTORY_LINES) : kept;
}

/** [before] 之前的 [limit] 条（不含 [before] 本身）；before <= 0 表示从最新往前取。 */
export function loadPage(server: string, cid: number, before: number, limit: number): Promise<HistoryPage> {
  const size = Math.max(1, Math.min(limit || 30, HISTORY_LINES));
  return readRow(rowKey(server, cid)).then((row) => {
    const all = row?.lines ?? [];
    const older = before > 0 ? all.filter((l) => l.time < before) : all;
    const lines = older.slice(-size);
    return { lines, more: older.length > lines.length };
  });
}

/** 记一条频道消息。不 await：落盘慢一点没关系，界面不等它。 */
export function appendLine(server: string, cid: number, line: HistoryLine): void {
  if (!server || cid <= 0 || !line.text) return;
  void serial(async () => {
    const k = rowKey(server, cid);
    const row = (await readRow(k)) ?? { key: k, server, cid, lines: [] };
    if (row.lines.some((l) => key(l) === key(line))) return;
    row.lines = trim(mergeLines(row.lines, [line]));
    await writeRow(row);
  }).catch(() => undefined);
}

/** 忘掉一个服务器的全部历史（目前没有入口调用，留给将来的「清除记录」）。 */
export function clearServer(server: string): Promise<void> {
  return serial(async () => {
    const prefix = `${server}|`;
    for (const k of [...memory.keys()]) if (k.startsWith(prefix)) memory.delete(k);
    const db = await openDb();
    if (!db) return;
    await new Promise<void>((resolve) => {
      try {
        const tx = db.transaction(STORE, 'readwrite');
        const store = tx.objectStore(STORE);
        const cursor = store.openCursor();
        cursor.onsuccess = () => {
          const c = cursor.result;
          if (!c) return;
          if (String((c.value as Row).key ?? '').startsWith(prefix)) c.delete();
          c.continue();
        };
        tx.oncomplete = () => resolve();
        tx.onerror = () => resolve();
      } catch {
        resolve();
      }
    });
  });
}
