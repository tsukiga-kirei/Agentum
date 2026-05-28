package com.agentum.workbench.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.shared.api.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkbenchAccessTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ROLE_ASSIGNMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000502");

    private final WorkbenchAccess access = new WorkbenchAccess();

    @Test
    void shouldAllowBusinessUserInsideOwnTenant() {
        assertThatCode(() -> access.assertCanAccessWorkbench(newPrincipal("business", TENANT_ID), TENANT_ID))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldAllowTenantAdminInsideOwnTenant() {
        assertThatCode(() -> access.assertCanAccessWorkbench(newPrincipal("tenant_admin", TENANT_ID), TENANT_ID))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldAllowSystemAdminAcrossTenants() {
        assertThatCode(() -> access.assertCanAccessWorkbench(newPrincipal("system_admin", null), TENANT_ID))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectBusinessUserAcrossTenants() {
        assertThatThrownBy(() -> access.assertCanAccessWorkbench(newPrincipal("business", TENANT_ID), OTHER_TENANT_ID))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("WORKBENCH_ACCESS_DENIED");
    }

    @Test
    void shouldRejectUnknownRole() {
        assertThatThrownBy(() -> access.assertCanAccessWorkbench(newPrincipal("guest", TENANT_ID), TENANT_ID))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("WORKBENCH_ACCESS_DENIED");
    }

    @Test
    void shouldRejectUnauthenticatedAccess() {
        assertThatThrownBy(() -> access.assertCanAccessWorkbench(null, TENANT_ID))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("AUTH_REQUIRED");
    }

    private static CurrentUserPrincipal newPrincipal(String role, UUID tenantId) {
        return new CurrentUserPrincipal(USER_ID, "demo", tenantId, role, role, "默认空间", ROLE_ASSIGNMENT_ID);
    }
}
