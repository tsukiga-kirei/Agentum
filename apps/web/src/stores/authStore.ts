import { create } from "zustand";
import { AgentumApiError, authApi, configureAuthSessionBridge } from "../services/apiClient";
import type { AuthUser, LoginResponse, MenuItem, PortalType, RoleInfo, SsoProviderOption, TenantOption, ThemeMode } from "../types/auth";
import { clearAuthToken, persistAuthToken, readStoredAuthToken } from "./authSession";

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
  /** 当前租户公开启用的企业 SSO 身份源 */
  ssoProviders: SsoProviderOption[];
  /** SSO 身份源是否正在加载 */
  ssoProvidersLoading: boolean;
  /** 当前后端是否处于零用户初始化阶段 */
  bootstrapRequired: boolean;
  /** 是否已完成初始化检查（例如从本地缓存恢复） */
  initialized: boolean;
  /** Access Token 始终持久化；该字段仅保留兼容现有调用。 */
  sessionPersist: boolean;
  /** 当前主题模式 */
  themeMode: ThemeMode;
};

type AuthActions = {
  /** 从后端加载登录页可见租户 */
  fetchTenants: () => Promise<void>;
  /** 根据租户加载可用企业 SSO 身份源 */
  fetchSsoProviders: (tenantId?: string) => Promise<void>;
  /** 登录并保存 Access Token、角色和菜单；rememberMe 仅控制登录页是否保存用户名偏好。 */
  login: (
    username: string,
    password: string,
    portal: PortalType,
    tenantId?: string,
    rememberMe?: boolean,
  ) => Promise<{ success: boolean; message?: string }>;
  /** SSO 回调完成后写入 Agentum 自己的 token 和角色上下文 */
  completeSsoLogin: (response: LoginResponse, rememberMe?: boolean) => void;
  /** 首次部署时创建首个系统管理员账号 */
  createBootstrapAdmin: (request: { username: string; displayName: string; password: string; email?: string }) => Promise<{ success: boolean; message?: string }>;
  /** 退出登录 */
  logout: () => Promise<void>;
  /** 从本地缓存恢复会话（调用 /api/auth/me 获取完整角色和菜单上下文） */
  restoreSession: () => void;
  /** 切换角色（参照 AuraOA switch-role） */
  switchRole: (roleId: string) => Promise<{ success: boolean; message?: string }>;
  /** 切换主题模式 */
  setThemeMode: (mode: ThemeMode) => void;
  /** 切换主题（深浅切换） */
  toggleTheme: () => void;
};

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
  ssoProviders: [],
  ssoProvidersLoading: false,
  bootstrapRequired: false,
  initialized: false,
  sessionPersist: false,
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

  fetchSsoProviders: async (tenantId) => {
    if (!tenantId) {
      set({ ssoProviders: [], ssoProvidersLoading: false });
      return;
    }

    set({ ssoProvidersLoading: true });

    try {
      const ssoProviders = await authApi.listSsoProviders(tenantId);
      set({ ssoProviders, ssoProvidersLoading: false });
    } catch (error) {
      // SSO 是登录增强能力，加载失败时保留密码登录可用，只输出脱敏诊断。
      console.warn("[auth] 企业 SSO 身份源加载失败", getErrorLogContext(error));
      set({ ssoProviders: [], ssoProvidersLoading: false });
    }
  },

  login: async (username, password, portal, tenantId, _rememberMe = false) => {
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

      persistAuthToken(response.token);
      set({
        user: response.user,
        token: response.token,
        roles: response.roles,
        activeRole: response.activeRole,
        permissions: response.permissions,
        menus: response.menus,
        bootstrapRequired: false,
        initialized: true,
        sessionPersist: true,
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

  completeSsoLogin: (response, _rememberMe = false) => {
    persistAuthToken(response.token);
    set({
      user: response.user,
      token: response.token,
      roles: response.roles,
      activeRole: response.activeRole,
      permissions: response.permissions,
      menus: response.menus,
      bootstrapRequired: false,
      initialized: true,
      sessionPersist: true,
    });
  },

  createBootstrapAdmin: async (request) => {
    try {
      await authApi.bootstrapAdmin(request);
      set({ bootstrapRequired: false, initialized: true });
      return { success: true };
    } catch (error) {
      if (error instanceof AgentumApiError) {
        console.warn("[auth] 系统管理员初始化失败", { code: error.code, requestId: error.requestId });
        return { success: false, message: error.message };
      }

      console.error("[auth] 系统管理员初始化请求异常", getErrorLogContext(error));
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

    clearAuthToken();
    set({ user: null, token: null, roles: [], activeRole: null, permissions: [], menus: [], sessionPersist: false });
  },

  restoreSession: () => {
    restoreTheme(set);

    void (async () => {
      try {
        const status = await authApi.bootstrapStatus();

        if (status.needsSetup) {
          // 零用户阶段不信任任何本地缓存 token，前端只允许进入首个系统管理员创建页。
          clearAuthToken();
          set({
            user: null,
            token: null,
            roles: [],
            activeRole: null,
            permissions: [],
            menus: [],
            bootstrapRequired: true,
            sessionPersist: false,
            initialized: true,
          });
          return;
        }

        set({ bootstrapRequired: false });
      } catch (error) {
        // 初始化状态接口失败时继续走常规会话恢复，让登录页展示更具体的后端连接错误。
        console.warn("[auth] 初始化状态检查失败，继续尝试恢复会话", getErrorLogContext(error));
      }

      const stored = readStoredAuthToken();

      if (stored?.token) {
        set({ token: stored.token, sessionPersist: stored.persist });

        // /me 返回与登录相同的结构（含 roles、activeRole、menus），前端可完整恢复会话状态。
        void authApi.me(stored.token)
          .then((meResponse) => {
            // /me 触发自动续签时 bridge 已先写入新 Access Token，不能再用缓存里的过期值覆盖。
            const activeToken = get().token ?? stored.token;
            set({
              user: meResponse.user,
              token: activeToken,
              roles: meResponse.roles,
              activeRole: meResponse.activeRole,
              permissions: meResponse.permissions,
              menus: meResponse.menus,
              bootstrapRequired: false,
              initialized: true,
              sessionPersist: stored.persist,
            });
          })
          .catch((error) => {
            console.warn("[auth] 会话恢复失败，已清理本地凭据", getErrorLogContext(error));
            clearAuthToken();
            set({
              user: null,
              token: null,
              roles: [],
              activeRole: null,
              permissions: [],
              menus: [],
              bootstrapRequired: false,
              sessionPersist: false,
              initialized: true,
            });
          });
        return;
      }

      void authApi.refresh()
        .then((response) => {
          persistAuthToken(response.token);
          set({
            user: response.user,
            token: response.token,
            roles: response.roles,
            activeRole: response.activeRole,
            permissions: response.permissions,
            menus: response.menus,
            bootstrapRequired: false,
            sessionPersist: true,
            initialized: true,
          });
        })
        .catch(() => set({ bootstrapRequired: false, initialized: true }));
    })().catch((error) => {
      console.warn("[auth] 本地会话缓存损坏，已忽略", getErrorLogContext(error));
      clearAuthToken();
      set({ bootstrapRequired: false, initialized: true });
    });
  },

  switchRole: async (roleId) => {
    const token = get().token;

    if (!token) {
      return { success: false, message: "请先登录" };
    }

    try {
      // 角色切换请求后端重签 token，返回新的活跃角色和菜单
      const response = await authApi.switchRole(token, { roleId });

      persistAuthToken(response.token, get().sessionPersist);
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

configureAuthSessionBridge({
  getAccessToken: () => useAuthStore.getState().token,
  onRefreshed: (response) => {
    persistAuthToken(response.token);
    useAuthStore.setState({
      user: response.user,
      token: response.token,
      roles: response.roles,
      activeRole: response.activeRole,
      permissions: response.permissions,
      menus: response.menus,
      bootstrapRequired: false,
      sessionPersist: true,
      initialized: true,
    });
  },
  onExpired: () => {
    clearAuthToken();
    window.sessionStorage.setItem("agentum_auth_notice", "登录状态已失效，请重新登录");
    useAuthStore.setState({
      user: null,
      token: null,
      roles: [],
      activeRole: null,
      permissions: [],
      menus: [],
      bootstrapRequired: false,
      sessionPersist: false,
      initialized: true,
    });
  },
});

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
