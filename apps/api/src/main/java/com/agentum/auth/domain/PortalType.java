package com.agentum.auth.domain;

import com.agentum.shared.api.ApiException;
import java.util.Locale;
import org.springframework.http.HttpStatus;

// 登录入口决定首屏和活跃角色；user_role_assignments.role 直接对应入口类型。
// business 进入业务工作台，tenant_admin 进入租户管理，system_admin 进入系统管理。
public enum PortalType {
    BUSINESS("business"),
    TENANT_ADMIN("tenant_admin"),
    SYSTEM_ADMIN("system_admin");

    private final String code;

    PortalType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public boolean isTenantScoped() {
        return this != SYSTEM_ADMIN;
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
