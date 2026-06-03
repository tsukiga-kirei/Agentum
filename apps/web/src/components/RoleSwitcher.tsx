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

/** 触发器收起态仅展示图标，展开态再显示角色简称。 */
function getRoleShortLabel(role: RoleInfo | null | undefined): string {
  if (!role) {
    return "未知角色";
  }
  return ROLE_LABELS[role.role]?.label ?? role.label;
}

export function RoleSwitcher() {
  const roles = useAuthStore((s) => s.roles);
  const activeRole = useAuthStore((s) => s.activeRole);
  const menus = useAuthStore((s) => s.menus);
  const switchRole = useAuthStore((s) => s.switchRole);
  const [open, setOpen] = useState(false);
  const [switching, setSwitching] = useState(false);

  const shortLabel = getRoleShortLabel(activeRole);
  const activeBusinessWithoutEntry = activeRole?.role === "business" && menus.length === 0;
  const fullLabel = activeRole?.label ?? shortLabel;

  if (roles.length <= 1) {
    // 只有一个角色时不展示切换器，仅显示当前角色标签；悬停/聚焦时内联展开角色名。
    const info = ROLE_LABELS[activeRole?.role ?? "business"];
    const Icon = info?.icon ?? LayoutDashboard;
    return (
      <div
        className="role-switcher-pill role-switcher-pill--static"
        tabIndex={0}
        aria-label={`当前角色：${fullLabel}`}
      >
        <span className="role-switcher-pill-icon">
          <Icon className="h-3.5 w-3.5" aria-hidden="true" />
        </span>
        <span className="role-switcher-pill-text">
          <span className="role-switcher-pill-role">{shortLabel}</span>
          {activeBusinessWithoutEntry ? <span className="role-switcher-pill-tenant">未配置入口</span> : null}
          {activeRole?.tenantName ? <span className="role-switcher-pill-tenant">{activeRole.tenantName}</span> : null}
        </span>
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
    <div className={`relative ${open ? "role-switcher--open" : ""}`}>
      <button
        type="button"
        onClick={() => setOpen(!open)}
        disabled={switching}
        className="role-switcher-pill role-switcher-pill--button"
        aria-label={`切换角色，当前：${fullLabel}`}
        aria-expanded={open}
      >
        <span className="role-switcher-pill-icon">
          <CurrentIcon className="h-3.5 w-3.5" aria-hidden="true" />
        </span>
        <span className="role-switcher-pill-text">
          <span className="role-switcher-pill-role">{shortLabel}</span>
          {activeBusinessWithoutEntry ? <span className="role-switcher-pill-tenant">未配置入口</span> : null}
          {activeRole?.tenantName ? <span className="role-switcher-pill-tenant">{activeRole.tenantName}</span> : null}
        </span>
        <ChevronDown className={`role-switcher-pill-chevron ${open ? "role-switcher-pill-chevron--open" : ""}`} aria-hidden="true" />
      </button>

      {open ? (
        <>
          {/* 点击外部关闭 */}
          <div className="fixed inset-0 z-40" onClick={() => setOpen(false)} />
          <div className="role-switcher-menu" role="menu" aria-label="切换角色">
            <p className="role-switcher-menu-title">切换角色</p>
            <div className="role-switcher-menu-list">
              {roles.map((role) => {
                const isActive = role.id === activeRole?.id;
                const info = ROLE_LABELS[role.role];
                const Icon = info?.icon ?? LayoutDashboard;
                const itemShortLabel = getRoleShortLabel(role);

                return (
                  <button
                    key={role.id}
                    type="button"
                    role="menuitemradio"
                    aria-checked={isActive}
                    onClick={() => handleSwitch(role)}
                    disabled={isActive || switching}
                    title={role.label}
                    className={`role-switcher-menu-item ${isActive ? "role-switcher-menu-item--active" : ""}`}
                  >
                    <span className="role-switcher-menu-item-icon">
                      <Icon className="h-4 w-4" aria-hidden="true" />
                    </span>
                    <div className="min-w-0 flex-1">
                      <p className="role-switcher-menu-role">{itemShortLabel}</p>
                      {role.tenantName ? (
                        <p className="role-switcher-menu-tenant">
                          <Building2 className="h-3 w-3 shrink-0" aria-hidden="true" />
                          <span className="truncate">{role.tenantName}</span>
                        </p>
                      ) : null}
                    </div>
                    {isActive ? <Check className="role-switcher-menu-check h-4 w-4 shrink-0" aria-hidden="true" /> : null}
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
