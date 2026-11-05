/*
 * 回响 · 网页版 Service Worker（方案 A：轻量壳缓存）
 *
 * 目标：让浏览器把网页版当成可安装的 PWA（独立窗口 + 图标），并在断网时
 * 仍能打开「外壳」（index.html），再由页面自己提示需要联网。
 *
 * 设计原则：
 * - 只缓存「固定的壳资源」（不含 hash 的 index.html / manifest / 图标）。
 *   Vite 打包出的 JS/CSS 文件名带 hash，无法在此写死；它们通过 fetch 的
 *   运行时缓存（stale-while-revalidate）在用户访问过后被缓存。
 * - /ws（WebSocket 实时语音）、/files（上传下载，带 X-File-Token）、
 *   /auth、/account、/avatar、/server-banner、/cloud-avatar 等动态或鉴权
 *   请求一律「不拦截、不缓存」，直接走网络。
 * - 导航请求网络优先，失败回退到缓存的 index.html（离线也能开壳）。
 */

const CACHE = 'huixiang-shell-v1';

// 固定路径的壳资源（可预缓存）；构建产物带 hash，不在此列。
const SHELL = [
  '/',
  '/index.html',
  '/manifest.webmanifest',
  '/icons/icon-16.png',
  '/icons/icon-32.png',
  '/icons/icon-48.png',
  '/icons/icon-96.png',
  '/icons/icon-180.png',
  '/icons/icon-192.png',
  '/icons/icon-512.png',
  '/icons/icon-maskable-512.png',
  '/icons/favicon.ico',
];

// 这些路径不缓存、不拦截，直接交给网络。
const BYPASS = [
  '/ws',
  '/files',
  '/auth',
  '/account',
  '/avatar',
  '/server-banner',
  '/cloud-avatar',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    (async () => {
      const cache = await caches.open(CACHE);
      // 逐个 add，单个 404 不影响整体安装。
      await Promise.all(
        SHELL.map((url) => cache.add(url).catch(() => undefined)),
      );
      // 新版 SW 安装后立即生效（配合 activate 的 clients.claim）。
      await self.skipWaiting();
    })(),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      // 清掉旧版本缓存。
      const keys = await caches.keys();
      await Promise.all(
        keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)),
      );
      await self.clients.claim();
    })(),
  );
});

function isBypassed(url) {
  return BYPASS.some(
    (prefix) => url.pathname === prefix || url.pathname.startsWith(prefix + '/'),
  );
}

self.addEventListener('fetch', (event) => {
  const req = event.request;

  // 非 GET（上传、删除等）直接放行。
  if (req.method !== 'GET') return;

  const url = new URL(req.url);

  // 跨域请求放行（音频/信令不走这里，但保险起见）。
  if (url.origin !== self.location.origin) return;

  // 动态 / 鉴权路径放行，不缓存。
  if (isBypassed(url)) return;

  // 导航请求：网络优先，失败回退缓存的 index.html。
  if (req.mode === 'navigate') {
    event.respondWith(
      (async () => {
        try {
          return await fetch(req);
        } catch {
          const cache = await caches.open(CACHE);
          return (
            (await cache.match('/index.html')) ||
            (await cache.match('/')) ||
            Response.error()
          );
        }
      })(),
    );
    return;
  }

  // 静态资源：缓存优先 + 后台更新（stale-while-revalidate）。
  event.respondWith(
    (async () => {
      const cache = await caches.open(CACHE);
      const cached = await cache.match(req);
      const network = fetch(req)
        .then((res) => {
          if (res && res.ok) cache.put(req, res.clone()).catch(() => undefined);
          return res;
        })
        .catch(() => null);
      return cached || (await network) || Response.error();
    })(),
  );
});
