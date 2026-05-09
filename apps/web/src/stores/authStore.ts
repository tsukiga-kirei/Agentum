import { create } from "zustand";
import { AgentumApiError, authApi } from "../services/apiClient";
import type { AuthUser, MenuItem, PortalType, RoleInfo, TenantOption, ThemeMode } from "../types/auth";

// 认证状态管理负责前端会话缓存，真实身份、租户和角色上下文全部以后端 auth API 为准。
// 参照 AuraOA，登录后保存完整角色列表和菜单，角色切换通过 switchRole API 完成。

type AuthState = {
  /** 当前登录用户信息，null 表示未登录 */
  user: AuthUser | null;
  /** 后端签发的 Bearer Token */
  token: string | null;
  /** 用户所有可用角色（来自 user_role_assignments） */
  roles: RoleInfo[];
  /** 当前活跃角色 */
  activeRole: RoleInfo | null;
  /** 当前角色的页面权限（第二层权限，后续由 tenant_org_roles 驱动） */
  permissions: string[];
  /** 当前角色的可见菜单（由后端计算） */
  menus: MenuItem[];
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
  /** 登录并保存后端返回的 token、角色列表和菜单 */
  login: (username: string, password: string, portal: PortalType, tenantId?: string) => Promise<{ success: boolean; message?: string }>;
  /** 退出登录 */
  logout: () => Promise<void>;
  /** 从 localStorage 恢复会话（调用 /api/auth/me 获取完整角色和菜单上下文） */
  restoreSession: () => void;
  /** 切换角色（参照 AuraOA switch-role） */
  switchRole: (roleId: string) => Promise<{ success: boolean; message?: string }>;
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
  roles: [],
  activeRole: null,
  permissions: [],
  menus: [],
  tenants: [],
  tenantsLoading: false,
  initialized: false,
  themeMode: "light",

  fetchTenants: async () => {
    set({ tenantsLoading: true });

    try {
      const tenants = await authApi.listTenants();
      set({ tenants, tenantsLoading: false });
    } catch (error) {
      console.warn("[auth] 租户列表加载失败", getErrorLogContext(error));
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
      // 系统管理员入口不绑定租户；业务和租户管理入口必须把租户交给后端重新校验成员关系和入口角色。
      const response = await authApi.login({
        username,
        password,
        portal,
        tenantId: portal === "system_admin" ? undefined : tenantId,
      });

      // 缓存 token，刷新后通过 /me 重新获取角色和菜单上下文
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ token: response.token }));
      set({
        user: response.user,
        token: response.token,
        roles: response.roles,
        activeRole: response.activeRole,
        permissions: response.permissions,
        menus: response.menus,
        initialized: true,
      });
      return { success: true };
    } catch (error) {
      if (error instanceof AgentumApiError) {
        console.warn("[auth] 登录失败", { code: error.code, requestId: error.requestId, portal, tenantId });
        return { success: false, message: error.message };
      }

      console.error("[auth] 登录请求异常", getErrorLogContext(error));
      return { success: false, message: "无法连接后端服务，请确认 API 已启动" };
    }
  },

  logout: async () => {
    const token = get().token;

    if (token) {
      try {
        await authApi.logout(token);
      } catch (error) {
        console.warn("[auth] 登出请求失败，本地会话仍会清理", getErrorLogContext(error));
      }
    }

    window.localStorage.removeItem(STORAGE_KEY);
    set({ user: null, token: null, roles: [], activeRole: null, permissions: [], menus: [] });
  },

  restoreSession: () => {
    restoreTheme(set);

    try {
      const raw = window.localStorage.getItem(STORAGE_KEY);

      if (raw) {
        const saved = JSON.parse(raw) as { token?: string };

        if (saved.token) {
          set({ token: saved.token });

          // /me 返回与登录相同的结构（含 roles、activeRole、menus），前端可完整恢复会话状态。
          void authApi.me(saved.token)
            .then((meResponse) => {
              set({
                user: meResponse.user,
                token: saved.token ?? null,
                roles: meResponse.roles,
                activeRole: meResponse.activeRole,
                permissions: meResponse.permissions,
                menus: meResponse.menus,
                initialized: true,
              });
            })
            .catch((error) => {
              console.warn("[auth] 会话恢复失败，已清理本地凭据", getErrorLogContext(error));
              window.localStorage.removeItem(STORAGE_KEY);
              set({ user: null, token: null, roles: [], activeRole: null, permissions: [], menus: [], initialized: true });
            });
          return;
        } else {
          window.localStorage.removeItem(STORAGE_KEY);
          set({ initialized: true });
        }
      } else {
        set({ initialized: true });
      }
    } catch (error) {
      console.warn("[auth] 本地会话缓存损坏，已忽略", getErrorLogContext(error));
      set({ initialized: true });
    }
  },

  switchRole: async (roleId) => {
    const token = get().token;

    if (!token) {
      return { success: false, message: "请先登录" };
    }

    try {
      // 角色切换请求后端重签 token，返回新的活跃角色和菜单
      const response = await authApi.switchRole(token, { roleId });

      window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ token: response.token }));
      set({
        user: response.user,
        token: response.token,
        activeRole: response.activeRole,
        permissions: response.permissions,
        menus: response.menus,
      });
      return { success: true };
    } catch (error) {
      if (error instanceof AgentumApiError) {
        console.warn("[auth] 角色切换失败", { code: error.code, requestId: error.requestId, roleId });
        return { success: false, message: error.message };
      }

      console.error("[auth] 角色切换请求异常", getErrorLogContext(error));
      return { success: false, message: "角色切换失败，请稍后重试" };
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

function getErrorLogContext(error: unknown) {
  if (error instanceof AgentumApiError) {
    return { code: error.code, requestId: error.requestId };
  }

  return { message: error instanceof Error ? error.message : "unknown" };
}
