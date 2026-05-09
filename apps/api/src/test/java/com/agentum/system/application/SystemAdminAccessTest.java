package com.agentum.system.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.shared.api.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SystemAdminAccessTest {

    private final SystemAdminAccess access = new SystemAdminAccess();

    @Test
    void shouldAllowSystemAdmin() {
        CurrentUserPrincipal principal = new CurrentUserPrincipal(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "admin",
            null,
            "system_admin",
            "system_admin",
            "system",
            UUID.fromString("00000000-0000-0000-0000-000000000401")
        );

        assertThatCode(() -> access.assertSystemAdmin(principal)).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectTenantAdmin() {
        CurrentUserPrincipal principal = new CurrentUserPrincipal(
            UUID.fromString("00000000-0000-0000-0000-000000000004"),
            "tenantadmin",
            UUID.fromString("00000000-0000-0000-0000-000000000101"),
            "tenant_admin",
            "tenant_admin",
            "默认空间",
            UUID.fromString("00000000-0000-0000-0000-000000000503")
        );

        assertThatThrownBy(() -> access.assertSystemAdmin(principal))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("PERMISSION_SYSTEM_ADMIN_REQUIRED");
    }

    @Test
    void shouldRejectMissingPrincipal() {
        assertThatThrownBy(() -> access.assertSystemAdmin(null))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("AUTH_REQUIRED");
    }
}
