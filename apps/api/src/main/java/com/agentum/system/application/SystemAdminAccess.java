package com.agentum.system.application;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 系统管理 API 仅允许平台级 system_admin；与租户管理入口解耦，避免租户管理员误触全局模型与能力注册。
 */
@Component
public class SystemAdminAccess {

    private static final Logger log = LoggerFactory.getLogger(SystemAdminAccess.class);

    public void assertSystemAdmin(CurrentUserPrincipal principal) {
        if (principal == null) {
            log.warn("系统管理访问被拒绝：未登录 requestId={}", RequestIds.current());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请先登录后再访问");
        }

        if (!"system_admin".equals(principal.role())) {
            log.warn(
                "系统管理访问被拒绝 userId={} role={} requestId={}",
                principal.userId(),
                principal.role(),
                RequestIds.current()
            );
            throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_SYSTEM_ADMIN_REQUIRED", "当前账号不是系统管理员");
        }
    }
}
