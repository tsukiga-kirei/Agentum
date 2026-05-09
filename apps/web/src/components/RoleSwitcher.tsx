import { useState } from "react";
import { ChevronDown, Check, Building2, Shield, LayoutDashboard, ShieldCheck } from "lucide-react";
import { useAuthStore } from "../stores/authStore";
import type { RoleInfo } from "../types/auth";

// 角色切换器（参照 AuraOA），展示用户所有可用角色并支持一键切换。
// 切换角色后后端重签 token，前端更新菜单和活跃角色，无需重新登录。

const ROLE_LABELS: Record<string, { label: string; icon: typeof Shield }> = {
  system_admin: { label: "系统管理员", icon: Shield },
  tenant_admin: { label: "租户管理", icon: ShieldCheck },
  business: { label: "业务用户", icon: LayoutDashboard },
};

export function RoleSwitcher() {
  const roles = useAuthStore((s) => s.roles);
  const activeRole = useAuthStore((s) => s.activeRole);
  const switchRole = useAuthStore((s) => s.switchRole);
  const [open, setOpen] = useState(false);
  const [switching, setSwitching] = useState(false);

  if (roles.length <= 1) {
    // 只有一个角色时不展示切换器，仅显示当前角色标签
    const info = ROLE_LABELS[activeRole?.role ?? "business"];
    const Icon = info?.icon ?? LayoutDashboard;
    return (
      <div className="flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs text-[var(--color-text-secondary)]">
        <Icon className="h-3.5 w-3.5" aria-hidden="true" />
        <span>{activeRole?.label ?? info?.label ?? "未知角色"}</span>
      </div>
    );
  }

  const currentInfo = ROLE_LABELS[activeRole?.role ?? "business"];
  const CurrentIcon = currentInfo?.icon ?? LayoutDashboard;

  async function handleSwitch(role: RoleInfo) {
    if (role.id === activeRole?.id || switching) return;
    setSwitching(true);
    setOpen(false);
    await switchRole(role.id);
    setSwitching(false);
  }

  return (
    <div className="relative">
      <button
        type="button"
        onClick={() => setOpen(!open)}
        disabled={switching}
        className="agent-button flex h-8 items-center gap-1.5 px-2.5 text-[13px]"
        aria-label="切换角色"
      >
        <CurrentIcon className="h-3.5 w-3.5" aria-hidden="true" />
        <span className="max-w-[120px] truncate">{activeRole?.label ?? currentInfo?.label}</span>
        <ChevronDown className={`h-3 w-3 transition-transform ${open ? "rotate-180" : ""}`} aria-hidden="true" />
      </button>

      {open ? (
        <>
          {/* 点击外部关闭 */}
          <div className="fixed inset-0 z-40" onClick={() => setOpen(false)} />
          <div className="absolute right-0 top-full z-50 mt-1.5 w-72 overflow-hidden rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] shadow-lg">
            <div className="px-3 py-2">
              <p className="text-[11px] font-semibold uppercase tracking-wider text-[var(--color-text-tertiary)]">切换角色</p>
            </div>
            <div className="max-h-64 overflow-y-auto">
              {roles.map((role) => {
                const isActive = role.id === activeRole?.id;
                const info = ROLE_LABELS[role.role];
                const Icon = info?.icon ?? LayoutDashboard;

                return (
                  <button
                    key={role.id}
                    type="button"
                    onClick={() => handleSwitch(role)}
                    disabled={isActive || switching}
                    className={`flex w-full items-center gap-3 px-3 py-2.5 text-left transition-colors ${
                      isActive
                        ? "bg-[var(--color-primary-bg)] text-[var(--color-primary)]"
                        : "hover:bg-[var(--color-bg-hover)] text-[var(--color-text-primary)]"
                    }`}
                  >
                    <Icon className="h-4 w-4 shrink-0" aria-hidden="true" />
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm font-medium">{info?.label ?? role.role}</p>
                      {role.tenantName ? (
                        <p className="flex items-center gap-1 truncate text-xs text-[var(--color-text-tertiary)]">
                          <Building2 className="h-3 w-3 shrink-0" aria-hidden="true" />
                          {role.tenantName}
                        </p>
                      ) : null}
                    </div>
                    {isActive ? <Check className="h-4 w-4 shrink-0 text-[var(--color-primary)]" aria-hidden="true" /> : null}
                  </button>
                );
              })}
            </div>
          </div>
        </>
      ) : null}
    </div>
  );
}
