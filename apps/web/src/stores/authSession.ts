import type { PortalType } from "../types/auth";

/** 持久登录（勾选「记住我」）时存放 token 的 key */
export const AUTH_STORAGE_KEY = "agentum_auth";
/** 会话级登录（未勾选）时存放 token 的 key，关闭浏览器后失效 */
export const AUTH_SESSION_STORAGE_KEY = "agentum_auth_session";
/** 登录页表单偏好（不含密码、不含 token） */
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
 * 按「记住我」选择写入 localStorage 或 sessionStorage，并清理另一侧，避免双份 token。
 */
export function persistAuthToken(token: string, persist: boolean): void {
  const payload = JSON.stringify({ token } satisfies StoredAuthPayload);

  if (persist) {
    window.localStorage.setItem(AUTH_STORAGE_KEY, payload);
    window.sessionStorage.removeItem(AUTH_SESSION_STORAGE_KEY);
    return;
  }

  window.sessionStorage.setItem(AUTH_SESSION_STORAGE_KEY, payload);
  window.localStorage.removeItem(AUTH_STORAGE_KEY);
}

/**
 * 恢复会话时读取 token：优先 session（未勾选记住我的当前浏览器会话），再读持久缓存。
 */
export function readStoredAuthToken(): { token: string; persist: boolean } | null {
  const sessionRaw = window.sessionStorage.getItem(AUTH_SESSION_STORAGE_KEY);
  const fromSession = parseStoredToken(sessionRaw);

  if (fromSession) {
    return { token: fromSession, persist: false };
  }

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
