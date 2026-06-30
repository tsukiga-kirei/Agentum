import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          "vendor-antd": ["antd"],
          "vendor-react": ["react", "react-dom"],
        },
      },
    },
  },
  server: {
    port: 5173,
    proxy: {
      // 本地前后端分端口调试时转发 API；生产镜像由 Nginx 负责同域 /api 代理。
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
});
