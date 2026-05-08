import { create } from "zustand";
import { AgentumApiError, authApi } from "../services/apiClient";
import type { AuthUser, PortalType, TenantOption, ThemeMode } from "../types/auth";

// 认证状态管理负责前端会话缓存，真实身份、租户和角色上下文全部以后端 auth API 为准。

type AuthState = {
  /** 当前登录用户信息，null 表示未登录 */
  user: AuthUser | null;
  /** 后端签发的 Bearer Token */
  token: string | null;
  /** 登录页可选择的活跃租户列表 */
  tenants: TenantOption[];
  /** 租户列表是否正在加载 */
  tenantsLoading: boolean;
  /** 是否已完成初始化检查（例如从 localStorage 恢复） */
  initialized: boolean;
  /** 当前主题模式 */
  themeMode: ThemeMode;
};

type AuthActions = {
  /** 从后端加载登录页可见租户 */
  fetchTenants: () => Promise<void>;
  /** 登录并保存后端返回的 token 与活跃用户上下文 */
  login: (username: string, password: string, portal: PortalType, tenantId?: string) => Promise<{ success: boolean; message?: string }>;
  /** 退出登录 */
  logout: () => Promise<void>;
  /** 从 localStorage 恢复会话 */
  restoreSession: () => void;
  /** 切换主题模式 */
  setThemeMode: (mode: ThemeMode) => void;
  /** 切换主题（深浅切换） */
  toggleTheme: () => void;
};

const STORAGE_KEY = "agentum_auth";
const THEME_KEY = "agentum_theme_mode";

export const useAuthStore = create<AuthState & AuthActions>((set, get) => ({
  user: null,
  token: null,
  tenants: [],
  tenantsLoading: false,
  initialized: false,
  themeMode: "light",

  fetchTenants: async () => {
    set({ tenantsLoading: true });

    try {
      const tenants = await authApi.listTenants();
      set({ tenants, tenantsLoading: false });
    } catch {
      set({ tenants: [], tenantsLoading: false });
      throw new Error("无法加载租户列表，请确认后端服务已启动");
    }
  },

  login: async (username, password, portal, tenantId) => {
    if (!username || !password) {
      return { success: false, message: "请输入用户名和密码" };
    }

    if (portal !== "system_admin" && !tenantId) {
      return { success: false, message: "请选择租户" };
    }

    try {
      const response = await authApi.login({
        username,
        password,
        portal,
        tenantId: portal === "system_admin" ? undefined : tenantId,
      });

      // 只缓存 token，刷新后重新向后端确认用户、租户和角色上下文，避免前端长期持有过期身份快照。
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ token: response.token }));
      set({ user: response.user, token: response.token, initialized: true });
      return { success: true };
    } catch (error) {
      if (error instanceof AgentumApiError) {
        return { success: false, message: error.message };
      }

      return { success: false, message: "无法连接后端服务，请确认 API 已启动" };
    }
  },

  logout: async () => {
    const token = get().token;

    if (token) {
      try {
        await authApi.logout(token);
      } catch {
        // 登出时以后端会话失效为最佳努力，前端仍需立即清除本地 token，避免继续使用过期身份。
      }
    }

    window.localStorage.removeItem(STORAGE_KEY);
    set({ user: null, token: null });
  },

  restoreSession: () => {
    restoreTheme(set);

    try {
      const raw = window.localStorage.getItem(STORAGE_KEY);

      if (raw) {
        const saved = JSON.parse(raw) as { token?: string };

        if (saved.token) {
          set({ token: saved.token });

          void authApi.me(saved.token)
            .then((user) => {
              set({ user, token: saved.token ?? null, initialized: true });
            })
            .catch(() => {
              window.localStorage.removeItem(STORAGE_KEY);
              set({ user: null, token: null, initialized: true });
            });
          return;
        } else {
          window.localStorage.removeItem(STORAGE_KEY);
          set({ initialized: true });
        }
      } else {
        set({ initialized: true });
      }
    } catch {
      set({ initialized: true });
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

function restoreTheme(set: (state: Partial<AuthState & AuthActions>) => void) {
  const savedTheme = window.localStorage.getItem(THEME_KEY);

  if (savedTheme === "dark" || savedTheme === "light") {
    set({ themeMode: savedTheme });
    document.documentElement.classList.toggle("dark", savedTheme === "dark");
    document.documentElement.setAttribute("data-theme", savedTheme);
  }
}
