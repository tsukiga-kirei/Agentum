import { ShieldCheck } from "lucide-react";
import { useAuthStore } from "../stores/authStore";

export function NoAccessPage() {
  const activeRole = useAuthStore((state) => state.activeRole);
  const roles = useAuthStore((state) => state.roles);
  const currentBusinessRoleHasNoEntry = activeRole?.role === "business";
  const hasTenantAdminRoleForCurrentTenant = roles.some(
    (role) => role.role === "tenant_admin" && role.tenantId === activeRole?.tenantId,
  );

  return (
    <div className="min-h-screen bg-[var(--color-bg-page)] pb-10">
      <div className="mx-auto max-w-[1400px] px-5 lg:px-6">
        <section
          className="agent-card mt-16 flex min-h-[360px] items-center justify-center p-8 text-center sm:mt-20"
          aria-label="无可访问页签"
        >
          <div>
            <ShieldCheck className="mx-auto h-10 w-10 text-[var(--color-text-tertiary)]" aria-hidden="true" />
            <h2 className="mt-4 text-base font-semibold text-[var(--color-text-primary)]">
              {currentBusinessRoleHasNoEntry ? "业务入口尚未配置" : "暂无可访问页签"}
            </h2>
            <p className="agent-muted mt-2 text-sm">
              {currentBusinessRoleHasNoEntry && hasTenantAdminRoleForCurrentTenant
                ? "当前业务用户身份尚未获得页签分配，请切回租户管理，在资源分配中为人员、部门或角色配置业务入口。"
                : "当前账号尚未获得租户内页签分配，请联系租户管理员配置业务入口。"}
            </p>
          </div>
        </section>
      </div>
    </div>
  );
}
