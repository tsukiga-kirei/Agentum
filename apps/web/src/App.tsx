import { useEffect } from "react";
import { useAuthStore } from "./stores/authStore";
import { LoginPage } from "./surfaces/auth/LoginPage";
import { WorkbenchShell } from "./surfaces/workbench/WorkbenchShell";

// 应用入口：当前使用 zustand 认证状态做路由守卫。
// 后续接入 react-router-dom 后，应改为路由级 guard + layout 嵌套。
export function App() {
  const user = useAuthStore((s) => s.user);
  const initialized = useAuthStore((s) => s.initialized);
  const restoreSession = useAuthStore((s) => s.restoreSession);

  useEffect(() => {
    restoreSession();
  }, [restoreSession]);

  // 初始化中不渲染任何内容，避免闪烁
  if (!initialized) {
    return null;
  }

  // 未登录时展示登录页
  if (!user) {
    return <LoginPage />;
  }

  // 已登录时展示工作台
  return <WorkbenchShell />;
}
