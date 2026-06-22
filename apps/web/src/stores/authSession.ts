import type { PortalType } from "../types/auth";

/** 持久化 Access Token 的 key；是否保存账号偏好不影响此项。 */
export const AUTH_STORAGE_KEY = "agentum_auth";
/** 旧版会话级 Token key，仅用于升级时清理。 */
export const AUTH_SESSION_STORAGE_KEY = "agentum_auth_session";
/** 登录页表单偏好（勾选“记住账号”时才保存用户名；始终不保存密码或 Token） */
export const LOGIN_PREFS_KEY = "agentum_login_prefs";

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
  window.sessionStorage.removeItem(AUTH_SESSION_STORAGE_KEY);
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
