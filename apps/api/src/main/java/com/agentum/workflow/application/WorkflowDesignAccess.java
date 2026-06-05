package com.agentum.workflow.application;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.permission.application.BusinessPageAccess;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class WorkflowDesignAccess {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDesignAccess.class);
    private static final String DESIGNER_PAGE_KEY = "designer";

    private final BusinessPageAccess businessPageAccess;

    public WorkflowDesignAccess(BusinessPageAccess businessPageAccess) {
        this.businessPageAccess = businessPageAccess;
    }

    public void assertCanDesign(CurrentUserPrincipal principal, UUID tenantId) {
        if (principal == null) {
            log.warn("工作流设计访问被拒绝：未登录 tenantId={} requestId={}", tenantId, RequestIds.current());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请先登录后再访问");
        }

        if ("system_admin".equals(principal.role())) {
            log.debug("工作流设计访问通过：系统管理员 userId={} targetTenantId={} requestId={}", principal.userId(), tenantId, RequestIds.current());
            return;
        }

        if (principal.tenantId() == null || !principal.tenantId().equals(tenantId)) {
            log.warn(
                "工作流设计访问被拒绝：租户上下文不匹配 userId={} principalTenantId={} targetTenantId={} requestId={}",
                principal.userId(),
                principal.tenantId(),
                tenantId,
                RequestIds.current()
            );
            throw denied();
        }

        if ("tenant_admin".equals(principal.role())) {
            log.debug("工作流设计访问通过：租户管理员 userId={} tenantId={} requestId={}", principal.userId(), tenantId, RequestIds.current());
            return;
        }

        if (businessPageAccess.hasPageGrant(tenantId, principal.userId(), DESIGNER_PAGE_KEY)) {
            log.debug("工作流设计访问通过：已分配流程设计页签 userId={} tenantId={} requestId={}", principal.userId(), tenantId, RequestIds.current());
            return;
        }

        log.warn(
            "工作流设计访问被拒绝：未分配流程设计页签 userId={} tenantId={} systemRole={} requestId={}",
            principal.userId(),
            tenantId,
            principal.role(),
            RequestIds.current()
        );
        throw denied();
    }

    private static ApiException denied() {
        return new ApiException(
            HttpStatus.FORBIDDEN,
            "PERMISSION_WORKFLOW_DESIGN_DENIED",
            "当前账号未被分配流程设计页签，请联系租户管理员在页签分配中开通"
        );
    }
}
