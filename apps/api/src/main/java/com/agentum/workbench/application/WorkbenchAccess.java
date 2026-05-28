package com.agentum.workbench.application;

import com.agentum.auth.application.CurrentUserPrincipal;
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

    /**
     * 校验当前主体是否能进入指定租户的业务工作台。
     */
    public void assertCanAccessWorkbench(CurrentUserPrincipal principal, UUID tenantId) {
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

        log.debug(
            "业务工作台访问通过 userId={} role={} tenantId={} requestId={}",
            principal.userId(),
            principal.role(),
            tenantId,
            RequestIds.current()
        );
    }
}
