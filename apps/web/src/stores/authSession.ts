import type { LoginResponse, PortalType } from "../types/auth";

/** 持久化 Access Token 的 key；是否保存账号偏好不影响此项。 */
export const AUTH_STORAGE_KEY = "agentum_auth";
/** 旧版会话级 Token key，仅用于升级时清理。 */
export const AUTH_SESSION_STORAGE_KEY = "agentum_auth_session";
/** 登录页表单偏好（勾选“记住账号”时才保存用户名；始终不保存密码或 Token） */
export const LOGIN_PREFS_KEY = "agentum_login_prefs";
/** SSO 回调页跨窗口或整页跳转时暂存的一次性登录结果。 */
export const SSO_CALLBACK_STORAGE_KEY = "agentum_sso_callback";

type StoredAuthPayload = {
  token: string;
};

export type LoginPrefs = {
  rememberMe: boolean;
  portal: PortalType;
  tenantId?: string;
  username?: string;
};

/**
 * Access Token 始终持久化；登录状态是否有效只由 Access/Refresh Token 判断，与“记住账号”无关。
 */
export function persistAuthToken(token: string, _persist = true): void {
  const payload = JSON.stringify({ token } satisfies StoredAuthPayload);
  window.localStorage.setItem(AUTH_STORAGE_KEY, payload);
  window.sessionStorage.removeItem(AUTH_SESSION_STORAGE_KEY);
}

/**
 * 恢复会话时读取持久化的 Access Token；Refresh Token 由 HttpOnly Cookie 管理。
 */
export function readStoredAuthToken(): { token: string; persist: boolean } | null {
  const localRaw = window.localStorage.getItem(AUTH_STORAGE_KEY);
  const fromLocal = parseStoredToken(localRaw);

  if (fromLocal) {
    return { token: fromLocal, persist: true };
  }

  return null;
}

/** 登出或凭据失效时清理全部 token 缓存 */
export function clearAuthToken(): void {
  window.localStorage.removeItem(AUTH_STORAGE_KEY);
  // 单点直达可能尚未经过登录页消费回调；退出时必须一起清理，避免旧 Access Token 把用户重新登录。
  window.localStorage.removeItem(SSO_CALLBACK_STORAGE_KEY);
  window.sessionStorage.removeItem(AUTH_SESSION_STORAGE_KEY);
}

/**
 * 读取并立即删除一次性 SSO 回调，避免应用通过 Refresh Cookie 恢复后仍残留可重放的登录结果。
 */
export function consumeSsoCallback(): LoginResponse | null {
  const raw = window.localStorage.getItem(SSO_CALLBACK_STORAGE_KEY);

  if (!raw) {
    return null;
  }

  window.localStorage.removeItem(SSO_CALLBACK_STORAGE_KEY);

  try {
    const response = JSON.parse(raw) as LoginResponse;
    return typeof response?.token === "string" && response.token.length > 0 ? response : null;
  } catch (error) {
    console.warn("[auth] 企业 SSO 回调缓存解析失败", { message: error instanceof Error ? error.message : "unknown" });
    return null;
  }
}

export function readLoginPrefs(): LoginPrefs | null {
  try {
    const raw = window.localStorage.getItem(LOGIN_PREFS_KEY);

    if (!raw) {
      return null;
    }

    const parsed = JSON.parse(raw) as Partial<LoginPrefs>;

    if (!parsed.portal || typeof parsed.rememberMe !== "boolean") {
      return null;
    }

    return {
      rememberMe: parsed.rememberMe,
      portal: parsed.portal,
      tenantId: typeof parsed.tenantId === "string" ? parsed.tenantId : undefined,
      username: typeof parsed.username === "string" ? parsed.username : undefined,
    };
  } catch {
    return null;
  }
}

export function saveLoginPrefs(prefs: LoginPrefs): void {
  window.localStorage.setItem(LOGIN_PREFS_KEY, JSON.stringify(prefs));
}

function parseStoredToken(raw: string | null): string | null {
  if (!raw) {
    return null;
  }

  try {
    const saved = JSON.parse(raw) as StoredAuthPayload;

    return typeof saved.token === "string" && saved.token.length > 0 ? saved.token : null;
  } catch {
    return null;
  }
}
