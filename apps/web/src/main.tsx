import React from "react";
import ReactDOM from "react-dom/client";
import { App as AntdApp, ConfigProvider, theme as antdTheme } from "antd";
import zhCN from "antd/locale/zh_CN";
import "antd/dist/reset.css";
import { App } from "./App";
import "./styles/global.css";
import { useAuthStore } from "./stores/authStore";
import { isDarkTheme } from "./utils/theme";

function RootProviders() {
  const themeMode = useAuthStore((state) => state.themeMode);
  const isDark = isDarkTheme(themeMode);
  const themeTokens = themeMode === "warm"
    ? {
        colorPrimary: "#a9563e",
        colorBgContainer: "#fffaf4",
        colorBgElevated: "#fffaf4",
        colorBorder: "#cfc6ba",
        colorText: "#25211d",
        colorTextSecondary: "#524a43",
      }
    : isDark
      ? {
          colorPrimary: "#6366f1",
          colorBgContainer: "#0f1829",
          colorBgElevated: "#151e31",
          colorBorder: "#253352",
          colorText: "#f1f5f9",
          colorTextSecondary: "#94a3b8",
        }
      : {
          colorPrimary: "#4f46e5",
          colorBgContainer: "#ffffff",
          colorBgElevated: "#ffffff",
          colorBorder: "#dce0eb",
          colorText: "#0f172a",
          colorTextSecondary: "#475569",
        };

  return (
    <ConfigProvider
      locale={zhCN}
      theme={{
        algorithm: isDark ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
        token: { ...themeTokens, fontFamily: "var(--font-sans)" },
      }}
    >
      <AntdApp>
        <App />
      </AntdApp>
    </ConfigProvider>
  );
}

// React 根入口只负责挂载应用；认证恢复和页面守卫放在 App / authStore 中统一处理。
ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <RootProviders />
  </React.StrictMode>,
);
