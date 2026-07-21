/**
 * 读取系统「减少动态效果」偏好。
 * 动效封装在此分支上跳过时间轴，直接展示终态，避免闪烁与无障碍冲突。
 */
export function prefersReducedMotion(): boolean {
  if (typeof window === "undefined" || typeof window.matchMedia !== "function") {
    return false;
  }

  return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}
