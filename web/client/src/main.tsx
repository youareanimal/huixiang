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
