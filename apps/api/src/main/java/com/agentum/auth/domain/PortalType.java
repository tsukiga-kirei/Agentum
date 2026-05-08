package com.agentum.auth.domain;

import com.agentum.shared.api.ApiException;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;

// 登录入口决定首屏和允许角色范围；它只是入口约束，不能替代后续资源级权限判断。
public enum PortalType {
    BUSINESS("business", Set.of("executor", "reviewer", "workflow_designer", "agent_admin", "capability_admin", "observer")),
    SPACE_ADMIN("space_admin", Set.of("space_admin", "tenant_admin")),
    SYSTEM_ADMIN("system_admin", Set.of("system_admin"));

    private final String code;
    private final Set<String> allowedRoleCodes;

    PortalType(String code, Set<String> allowedRoleCodes) {
        this.code = code;
        this.allowedRoleCodes = allowedRoleCodes;
    }

    public String code() {
        return code;
    }

    public boolean isTenantScoped() {
        return this != SYSTEM_ADMIN;
    }

    public boolean allowsRole(String roleCode) {
        return allowedRoleCodes.contains(roleCode);
    }

    public static PortalType fromCode(String code) {
        if (code != null) {
            String normalized = code.trim().toLowerCase(Locale.ROOT);

            for (PortalType value : values()) {
                if (value.code.equals(normalized)) {
                    return value;
                }
            }
        }

        throw new ApiException(HttpStatus.BAD_REQUEST, "AUTH_PORTAL_INVALID", "登录入口类型不正确");
    }
}
