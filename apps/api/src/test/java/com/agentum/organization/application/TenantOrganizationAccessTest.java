package com.agentum.organization.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.shared.api.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantOrganizationAccessTest {

    private final TenantOrganizationAccess access = new TenantOrganizationAccess();

    @Test
    void shouldAllowSystemAdminToManageAnyTenant() {
        CurrentUserPrincipal principal = new CurrentUserPrincipal(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "admin",
            null,
            "system_admin",
            "system_admin",
            "system"
        );

        assertThatCode(() -> access.assertCanManageTenant(principal, UUID.fromString("00000000-0000-0000-0000-000000000101")))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldAllowSpaceAdminInsideOwnTenant() {
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        CurrentUserPrincipal principal = new CurrentUserPrincipal(
            UUID.fromString("00000000-0000-0000-0000-000000000004"),
            "spaceadmin",
            tenantId,
            "space_admin",
            "space_admin",
            "默认空间"
        );

        assertThatCode(() -> access.assertCanManageTenant(principal, tenantId))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectBusinessUser() {
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        CurrentUserPrincipal principal = new CurrentUserPrincipal(
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            "operator",
            tenantId,
            "executor",
            "business",
            "默认空间"
        );

        assertThatThrownBy(() -> access.assertCanManageTenant(principal, tenantId))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("PERMISSION_TENANT_ORG_DENIED");
    }

    @Test
    void shouldRejectCrossTenantAccess() {
        CurrentUserPrincipal principal = new CurrentUserPrincipal(
            UUID.fromString("00000000-0000-0000-0000-000000000004"),
            "spaceadmin",
            UUID.fromString("00000000-0000-0000-0000-000000000101"),
            "space_admin",
            "space_admin",
            "默认空间"
        );

        assertThatThrownBy(() -> access.assertCanManageTenant(principal, UUID.fromString("00000000-0000-0000-0000-000000000102")))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("PERMISSION_TENANT_ORG_DENIED");
    }
}
