package com.agentum.workbench.application;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.permission.application.BusinessPageAccess;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 业务工作台访问校验。
 *
 * <p>业务工作台是租户内业务用户的默认入口，前端菜单隐藏不能作为安全边界，
 * 所以无论第一层登录入口角色是 {@code business} 还是 {@code tenant_admin}，
 * 都必须复核 token 中的 tenantId 与请求路径中的 tenantId 一致。
 * {@code system_admin} 不属于租户内业务用户，但允许跨租户访问以支撑平台诊断。</p>
 */
@Component
public class WorkbenchAccess {

    private static final Logger log = LoggerFactory.getLogger(WorkbenchAccess.class);
    private static final Set<String> TENANT_ROLES = Set.of("business", "tenant_admin", "system_admin");
    private static final String WORKBENCH_PAGE_KEY = "workbench";
    private static final String SCHEDULES_PAGE_KEY = "workbench_schedules";

    private final BusinessPageAccess businessPageAccess;

    public WorkbenchAccess(BusinessPageAccess businessPageAccess) {
        this.businessPageAccess = businessPageAccess;
    }

    /**
     * 校验当前主体是否能进入指定租户的业务工作台。
     */
    public void assertCanAccessWorkbench(CurrentUserPrincipal principal, UUID tenantId) {
        assertCanAccessWorkbenchPage(principal, tenantId, Set.of(WORKBENCH_PAGE_KEY), "当前账号未被分配业务工作台页签");
    }

    public void assertCanAccessSchedule(CurrentUserPrincipal principal, UUID tenantId) {
        assertCanAccessWorkbenchPage(principal, tenantId, Set.of(SCHEDULES_PAGE_KEY), "当前账号未被分配定时任务页签");
    }

    /**
     * 定时任务复用业务工作台下的流程选择、运行详情和交付查看能力。
     * 这些接口属于只读或配置辅助读取，允许“业务工作台”和“定时任务”两个同级页签任一授权进入；
     * 手工发起、待办处理、回退等业务操作仍必须走 {@link #assertCanAccessWorkbench(CurrentUserPrincipal, UUID)}。
     */
    public void assertCanAccessWorkbenchOrSchedule(CurrentUserPrincipal principal, UUID tenantId) {
        assertCanAccessWorkbenchPage(
            principal,
            tenantId,
            Set.of(WORKBENCH_PAGE_KEY, SCHEDULES_PAGE_KEY),
            "当前账号未被分配业务工作台或定时任务页签"
        );
    }

    private void assertCanAccessWorkbenchPage(CurrentUserPrincipal principal, UUID tenantId, Set<String> pageKeys, String deniedMessage) {
        if (principal == null) {
            log.warn("业务工作台访问被拒绝：未登录 tenantId={} requestId={}", tenantId, RequestIds.current());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请先登录后再访问");
        }

        // 系统管理员通常不进入租户内业务工作台，但允许跨租户诊断进入只读视图，对应能力仍由后端按角色限定。
        if ("system_admin".equals(principal.role())) {
            log.debug(
                "业务工作台访问通过：系统管理员 userId={} targetTenantId={} requestId={}",
                principal.userId(),
                tenantId,
                RequestIds.current()
            );
            return;
        }

        if (!TENANT_ROLES.contains(principal.role())
            || principal.tenantId() == null
            || !principal.tenantId().equals(tenantId)) {
            log.warn(
                "业务工作台访问被拒绝：租户上下文不匹配 userId={} role={} principalTenantId={} targetTenantId={} requestId={}",
                principal.userId(),
                principal.role(),
                principal.tenantId(),
                tenantId,
                RequestIds.current()
            );
            throw new ApiException(HttpStatus.FORBIDDEN, "WORKBENCH_ACCESS_DENIED", "当前账号不能访问该租户的业务工作台");
        }

        if ("business".equals(principal.role()) && !hasAnyPageGrant(tenantId, principal.userId(), pageKeys)) {
            log.warn(
                "业务工作台访问被拒绝：未分配页签 userId={} tenantId={} pageKey={} requestId={}",
                principal.userId(),
                tenantId,
                pageKeys,
                RequestIds.current()
            );
            throw new ApiException(HttpStatus.FORBIDDEN, "WORKBENCH_PAGE_ACCESS_DENIED", deniedMessage);
        }

        log.debug(
            "业务工作台访问通过 userId={} role={} tenantId={} pageKey={} requestId={}",
            principal.userId(),
            principal.role(),
            tenantId,
            pageKeys,
            RequestIds.current()
        );
    }

    private boolean hasAnyPageGrant(UUID tenantId, UUID userId, Set<String> pageKeys) {
        return pageKeys.stream().anyMatch(pageKey -> businessPageAccess.hasPageGrant(tenantId, userId, pageKey));
    }
}
