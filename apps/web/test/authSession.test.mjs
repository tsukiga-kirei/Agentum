import assert from "node:assert/strict";
import { afterEach, beforeEach, test } from "node:test";
import {
  AUTH_SESSION_STORAGE_KEY,
  AUTH_STORAGE_KEY,
  LOGIN_PREFS_KEY,
  SSO_CALLBACK_STORAGE_KEY,
  clearAuthToken,
  consumeSsoCallback,
} from "../src/stores/authSession.ts";

let originalWindow;

beforeEach(() => {
  originalWindow = globalThis.window;
  Object.defineProperty(globalThis, "window", {
    configurable: true,
    value: {
      localStorage: createMemoryStorage(),
      sessionStorage: createMemoryStorage(),
    },
  });
});

afterEach(() => {
  if (originalWindow === undefined) {
    delete globalThis.window;
    return;
  }
  Object.defineProperty(globalThis, "window", { configurable: true, value: originalWindow });
});

test("SSO 回调读取后立即删除，不能在退出时再次登录", () => {
  window.localStorage.setItem(SSO_CALLBACK_STORAGE_KEY, JSON.stringify({ token: "sso-access-token" }));

  assert.equal(consumeSsoCallback()?.token, "sso-access-token");
  assert.equal(window.localStorage.getItem(SSO_CALLBACK_STORAGE_KEY), null);
  assert.equal(consumeSsoCallback(), null);
});

test("退出清理认证凭据和未消费的 SSO 回调，但保留登录表单偏好", () => {
  window.localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify({ token: "access-token" }));
  window.localStorage.setItem(SSO_CALLBACK_STORAGE_KEY, JSON.stringify({ token: "sso-access-token" }));
  window.localStorage.setItem(LOGIN_PREFS_KEY, JSON.stringify({ rememberMe: true }));
  window.sessionStorage.setItem(AUTH_SESSION_STORAGE_KEY, JSON.stringify({ token: "legacy-token" }));

  clearAuthToken();

  assert.equal(window.localStorage.getItem(AUTH_STORAGE_KEY), null);
  assert.equal(window.localStorage.getItem(SSO_CALLBACK_STORAGE_KEY), null);
  assert.equal(window.sessionStorage.getItem(AUTH_SESSION_STORAGE_KEY), null);
  assert.notEqual(window.localStorage.getItem(LOGIN_PREFS_KEY), null);
});

function createMemoryStorage() {
  const values = new Map();
  return {
    getItem(key) {
      return values.has(key) ? values.get(key) : null;
    },
    setItem(key, value) {
      values.set(key, String(value));
    },
    removeItem(key) {
      values.delete(key);
    },
  };
}
