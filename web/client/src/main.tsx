import React from 'react';
import { createRoot } from 'react-dom/client';
// semi-ui 2.104 的 package.json exports 没导出 dist/css，只能按文件路径引，
// 不然 Vite 会报 "Missing ./dist/css/semi.min.css specifier"。
import '../node_modules/@douyinfe/semi-ui/dist/css/semi.min.css';
import './styles.css';
import App from './App';

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);

// 注册 Service Worker（仅生产构建）：让网页版可作为 PWA 安装、并在断网时
// 仍能打开外壳。dev 下不注册，避免缓存开发资源；浏览器不支持时静默跳过。
if (import.meta.env.PROD && 'serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch((err) => {
      console.warn('[pwa] Service Worker 注册失败：', err);
    });
  });
}
