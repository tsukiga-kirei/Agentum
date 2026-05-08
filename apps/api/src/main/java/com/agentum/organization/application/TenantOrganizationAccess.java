package com.agentum.organization.application;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.shared.api.ApiException;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class TenantOrganizationAccess {

    private static final Set<String> TENANT_ADMIN_ROLES = Set.of("tenant_admin", "space_admin", "system_admin");

    public void assertCanManageTenant(CurrentUserPrincipal principal, UUID tenantId) {
        if (principal == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请先登录后再访问");
        }

        if ("system_admin".equals(principal.role())) {
            return;
        }

        // 人员组织是租户级治理数据，业务用户即使能进入工作台，也不能仅凭前端入口访问其他租户成员关系。
        if (!TENANT_ADMIN_ROLES.contains(principal.role()) || principal.tenantId() == null || !principal.tenantId().equals(tenantId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_TENANT_ORG_DENIED", "当前账号不能管理该租户的人员组织");
        }
    }
}
