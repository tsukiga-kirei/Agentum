import { create } from "zustand";
import type { AuthUser, PortalType, ThemeMode } from "../types/auth";

// 认证状态管理，当前使用 localStorage 模拟 JWT 持久化。
// 后续接入后端 auth API 后，login/logout 应改为 fetch 调用并保存 JWT token。

type AuthState = {
  /** 当前登录用户信息，null 表示未登录 */
  user: AuthUser | null;
  /** JWT token 占位，后续替换为后端签发的真实 token */
  token: string | null;
  /** 是否已完成初始化检查（例如从 localStorage 恢复） */
  initialized: boolean;
  /** 当前主题模式 */
  themeMode: ThemeMode;
};

type AuthActions = {
  /** 模拟登录，当前直接写入本地状态，后续应调用 /api/auth/login */
  login: (username: string, password: string, portal: PortalType) => Promise<boolean>;
  /** 退出登录 */
  logout: () => void;
  /** 从 localStorage 恢复会话 */
  restoreSession: () => void;
  /** 切换主题模式 */
  setThemeMode: (mode: ThemeMode) => void;
  /** 切换主题（深浅切换） */
  toggleTheme: () => void;
};

const STORAGE_KEY = "agentum_auth";
const THEME_KEY = "agentum_theme_mode";

// 模拟用户数据，后续由后端 auth API 返回真实用户信息。
function createMockUser(username: string, portal: PortalType): AuthUser {
  const roleMap: Record<PortalType, AuthUser["role"]> = {
    business: "executor",
    space_admin: "space_admin",
    system_admin: "system_admin",
  };

  return {
    id: `user_${Date.now()}`,
    username,
    displayName: username === "admin" ? "系统管理员" : username === "designer" ? "流程设计者" : "业务用户",
    email: `${username}@agentum.dev`,
    avatar: "",
    role: roleMap[portal],
    organization: "Agentum 演示组织",
    space: "默认空间",
    lastLoginAt: new Date().toISOString(),
  };
}

export const useAuthStore = create<AuthState & AuthActions>((set, get) => ({
  user: null,
  token: null,
  initialized: false,
  themeMode: "light",

  login: async (username, password, portal) => {
    // 当前模拟校验，只检查用户名非空。后续替换为 POST /api/auth/login。
    if (!username || !password) {
      return false;
    }

    const user = createMockUser(username, portal);
    const token = `mock_jwt_${Date.now()}`;

    // 模拟写入 localStorage 以便页面刷新后恢复
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ user, token }));
    set({ user, token, initialized: true });
    return true;
  },

  logout: () => {
    window.localStorage.removeItem(STORAGE_KEY);
    set({ user: null, token: null });
  },

  restoreSession: () => {
    try {
      const raw = window.localStorage.getItem(STORAGE_KEY);

      if (raw) {
        const saved = JSON.parse(raw);
        set({ user: saved.user, token: saved.token, initialized: true });
      } else {
        set({ initialized: true });
      }
    } catch {
      set({ initialized: true });
    }

    // 同步恢复主题偏好
    const savedTheme = window.localStorage.getItem(THEME_KEY);

    if (savedTheme === "dark" || savedTheme === "light") {
      set({ themeMode: savedTheme });
      document.documentElement.classList.toggle("dark", savedTheme === "dark");
      document.documentElement.setAttribute("data-theme", savedTheme);
    }
  },

  setThemeMode: (mode) => {
    window.localStorage.setItem(THEME_KEY, mode);
    document.documentElement.classList.toggle("dark", mode === "dark");
    document.documentElement.setAttribute("data-theme", mode);
    set({ themeMode: mode });
  },

  toggleTheme: () => {
    const current = get().themeMode;
    const next: ThemeMode = current === "dark" ? "light" : "dark";
    get().setThemeMode(next);
  },
}));
