import { Moon, Sparkles, Sun } from "lucide-react";
import { useAuthStore } from "../stores/authStore";
import type { ThemeMode } from "../types/auth";

const themeOptions: Array<{
  mode: ThemeMode;
  label: string;
  icon: typeof Sun;
}> = [
  { mode: "light", label: "浅色模式", icon: Sun },
  { mode: "dark", label: "深色模式", icon: Moon },
  { mode: "warm", label: "暖纸主题", icon: Sparkles },
];

// 三态主题切换：只显示图标，避免顶栏被文字撑开；完整名称通过 title/aria-label 暴露。
export function ThemeToggle() {
  const themeMode = useAuthStore((s) => s.themeMode);
  const setThemeMode = useAuthStore((s) => s.setThemeMode);

  return (
    <div className="theme-toggle-btn" role="group" aria-label="切换界面主题">
      <span className="theme-toggle-track">
        {themeOptions.map((option) => {
          const Icon = option.icon;
          const active = themeMode === option.mode;

          return (
            <button
              key={option.mode}
              type="button"
              className={`theme-toggle-option theme-toggle-option--${option.mode} ${active ? "theme-toggle-option--active" : ""}`}
              onClick={() => setThemeMode(option.mode)}
              aria-pressed={active}
              aria-label={option.label}
              title={option.label}
            >
              <Icon className="h-3.5 w-3.5" aria-hidden="true" />
            </button>
          );
        })}
      </span>
    </div>
  );
}
