import type { ThemeMode } from "../types/auth";

export const THEME_MODES: ThemeMode[] = ["light", "dark", "warm"];

export function isDarkTheme(mode: ThemeMode) {
  return mode === "dark";
}

export function getThemedDrawerRootClassName(themeMode: ThemeMode, extraClassName?: string) {
  return ["agent-admin-drawer", `agent-admin-drawer--${themeMode}`, extraClassName]
    .filter(Boolean)
    .join(" ");
}

export function getThemeSurfaceClassName(themeMode: ThemeMode, extraClassName?: string) {
  return [`theme-surface theme-surface--${themeMode}`, extraClassName].filter(Boolean).join(" ");
}
