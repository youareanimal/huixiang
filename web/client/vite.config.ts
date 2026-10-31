import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 开发时前端跑 5173，网关卡跑 8080。`/ws` 反代过去而不是直连，是为了让页面和网关同源——
// 网关的 Origin 校验按主机名判断同源，Host 头保持不变（不设 changeOrigin）才能对上。
// 网关地址。换个端口改这里。
const target = '127.0.0.1:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/ws': { target: `ws://${target}`, ws: true },
      '/auth': { target: `http://${target}` },
      // 文件的字节是 HTTP，不走 /ws：dev 下不代理就会打到 vite 自己，拿回一个 index.html
      '/files': { target: `http://${target}` },
      // 会话横幅同理（ts3image 的那种要网关去下载再给出来）
      '/server-banner': { target: `http://${target}` },
      // 成员头像和账号头像上传：头像走 HTTP，登录口令/账号消息仍走 /ws
      '/avatar': { target: `http://${target}` },
      '/account': { target: `http://${target}` },
    },
  },
  build: { outDir: 'dist', emptyOutDir: true },
});
