import type { LucideIcon } from "lucide-react";
import type { ReactNode } from "react";

export type SurfacePageLayoutProps = {
  markClassName: string;
  icon: LucideIcon;
  title: string;
  badge: string;
  description: string;
  /** 页头右侧、全局操作之前的业务操作区 */
  headerEnd?: ReactNode;
  /** message 等需挂在布局根部的内容 */
  topSlot?: ReactNode;
  children: ReactNode;
};

/**
 * 工作台业务页统一骨架：全局账号与显示偏好统一收纳在左下角人物菜单，页头只承载当前业务操作。
 */
export function SurfacePageLayout({
  markClassName,
  icon: Icon,
  title,
  badge,
  description,
  headerEnd,
  topSlot,
  children,
}: SurfacePageLayoutProps) {
  return (
    <div className="surface-page-layout min-h-screen bg-[var(--color-bg-page)] pb-10">
      {topSlot}
      <div className="mx-auto max-w-[1400px] px-5 lg:px-6">
        <header className="surface-page-chrome mb-5 flex flex-col gap-3 border-b border-[var(--color-border-light)] pb-4 pt-3 sm:flex-row sm:items-start sm:justify-between">
          <div className="flex min-w-0 gap-4">
            <div className={`${markClassName} flex h-12 w-12 shrink-0 items-center justify-center rounded-[var(--radius-lg)]`}>
              <Icon className="h-6 w-6" aria-hidden="true" />
            </div>
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <h1 className="text-lg font-semibold tracking-tight text-[var(--color-text-primary)] sm:text-xl">{title}</h1>
                <span className="rounded-full bg-[var(--color-bg-hover)] px-2.5 py-0.5 text-xs font-medium text-[var(--color-text-secondary)] ring-1 ring-[var(--color-border-light)]">
                  {badge}
                </span>
              </div>
              <p className="agent-muted mt-1.5 text-sm leading-relaxed">{description}</p>
            </div>
          </div>
          <div className="flex shrink-0 flex-wrap items-center justify-end gap-2 sm:pt-0.5">
            {headerEnd}
          </div>
        </header>
        {children}
      </div>
    </div>
  );
}
