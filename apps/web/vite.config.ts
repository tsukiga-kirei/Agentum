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
          "vendor-flow": ["reactflow"],
        },
      },
    },
  },
  server: {
    port: 5173,
  },
});
