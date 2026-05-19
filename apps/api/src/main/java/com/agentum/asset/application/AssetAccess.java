package com.agentum.asset.application;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class AssetAccess {

    private static final Logger log = LoggerFactory.getLogger(AssetAccess.class);
    private static final Set<String> TENANT_ROLES = Set.of("business", "tenant_admin", "system_admin");

    public void assertCanUseAssets(CurrentUserPrincipal principal, UUID tenantId) {
        if (principal == null) {
            log.warn("能力资产访问被拒绝：未登录 tenantId={} requestId={}", tenantId, RequestIds.current());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请先登录后再访问");
        }

        if ("system_admin".equals(principal.role())) {
            log.debug("能力资产访问通过：系统管理员 userId={} targetTenantId={} requestId={}", principal.userId(), tenantId, RequestIds.current());
            return;
        }

        // 能力资产是租户内业务页面，必须与当前 token 的租户上下文一致；前端菜单隐藏不是安全边界。
        if (!TENANT_ROLES.contains(principal.role()) || principal.tenantId() == null || !principal.tenantId().equals(tenantId)) {
            log.warn(
                "能力资产访问被拒绝：租户上下文不匹配 userId={} role={} principalTenantId={} targetTenantId={} requestId={}",
                principal.userId(),
                principal.role(),
                principal.tenantId(),
                tenantId,
                RequestIds.current()
            );
            throw new ApiException(HttpStatus.FORBIDDEN, "ASSET_ACCESS_DENIED", "当前账号不能访问该租户的能力资产");
        }

        log.debug("能力资产访问通过 userId={} role={} tenantId={} requestId={}", principal.userId(), principal.role(), tenantId, RequestIds.current());
    }
}
