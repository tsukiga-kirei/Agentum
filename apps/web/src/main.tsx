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

  return (
    <ConfigProvider
      locale={zhCN}
      theme={{
        algorithm: isDark ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
        token: {
          colorPrimary: themeMode === "warm" ? "#b75f42" : undefined,
          fontFamily: "var(--font-sans)",
        },
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
