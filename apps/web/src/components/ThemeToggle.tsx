import { Moon, Sun } from "lucide-react";
import { useAuthStore } from "../stores/authStore";

// 主题切换药丸开关，保持 Agentum 顶栏操作区的紧凑风格。
// 结构：外层透明按钮 > 轨道 track > 滑块 thumb（内含图标）。
// 深色模式通过 --dark 修饰类控制轨道颜色和滑块位移。
export function ThemeToggle() {
  const themeMode = useAuthStore((s) => s.themeMode);
  const toggleTheme = useAuthStore((s) => s.toggleTheme);
  const isDark = themeMode === "dark";

  return (
    <button
      type="button"
      className={`theme-toggle-btn ${isDark ? "theme-toggle-btn--dark" : ""}`}
      onClick={toggleTheme}
      aria-label={isDark ? "切换到浅色模式" : "切换到深色模式"}
    >
      <span className="theme-toggle-track">
        <span className="theme-toggle-thumb">
          {isDark ? (
            <Moon className="h-3.5 w-3.5" aria-hidden="true" />
          ) : (
            <Sun className="h-4 w-4" aria-hidden="true" />
          )}
        </span>
      </span>
    </button>
  );
}
