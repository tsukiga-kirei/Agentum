package com.agentum.workbench.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.permission.application.BusinessPageAccess;
import com.agentum.shared.api.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkbenchAccessTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ROLE_ASSIGNMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000502");

    @Mock
    private BusinessPageAccess businessPageAccess;

    @Test
    void shouldAllowBusinessUserInsideOwnTenant() {
        WorkbenchAccess access = new WorkbenchAccess(businessPageAccess);
        when(businessPageAccess.hasPageGrant(TENANT_ID, USER_ID, "workbench")).thenReturn(true);

        assertThatCode(() -> access.assertCanAccessWorkbench(newPrincipal("business", TENANT_ID), TENANT_ID))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldAllowBusinessUserWithSchedulePageGrant() {
        WorkbenchAccess access = new WorkbenchAccess(businessPageAccess);
        when(businessPageAccess.hasPageGrant(TENANT_ID, USER_ID, "workbench_schedules")).thenReturn(true);

        assertThatCode(() -> access.assertCanAccessSchedule(newPrincipal("business", TENANT_ID), TENANT_ID))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldAllowBusinessUserWithEitherBusinessTabForSharedReadEndpoints() {
        WorkbenchAccess access = new WorkbenchAccess(businessPageAccess);
        lenient().when(businessPageAccess.hasPageGrant(TENANT_ID, USER_ID, "workbench")).thenReturn(false);
        when(businessPageAccess.hasPageGrant(TENANT_ID, USER_ID, "workbench_schedules")).thenReturn(true);

        assertThatCode(() -> access.assertCanAccessWorkbenchOrSchedule(newPrincipal("business", TENANT_ID), TENANT_ID))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectBusinessUserWithoutPageGrant() {
        WorkbenchAccess access = new WorkbenchAccess(businessPageAccess);
        when(businessPageAccess.hasPageGrant(TENANT_ID, USER_ID, "workbench")).thenReturn(false);

        assertThatThrownBy(() -> access.assertCanAccessWorkbench(newPrincipal("business", TENANT_ID), TENANT_ID))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("WORKBENCH_PAGE_ACCESS_DENIED");
    }

    @Test
    void shouldAllowTenantAdminInsideOwnTenant() {
        WorkbenchAccess access = new WorkbenchAccess(businessPageAccess);

        assertThatCode(() -> access.assertCanAccessWorkbench(newPrincipal("tenant_admin", TENANT_ID), TENANT_ID))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldAllowSystemAdminAcrossTenants() {
        WorkbenchAccess access = new WorkbenchAccess(businessPageAccess);

        assertThatCode(() -> access.assertCanAccessWorkbench(newPrincipal("system_admin", null), TENANT_ID))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectBusinessUserAcrossTenants() {
        WorkbenchAccess access = new WorkbenchAccess(businessPageAccess);

        assertThatThrownBy(() -> access.assertCanAccessWorkbench(newPrincipal("business", TENANT_ID), OTHER_TENANT_ID))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("WORKBENCH_ACCESS_DENIED");
    }

    @Test
    void shouldRejectUnknownRole() {
        WorkbenchAccess access = new WorkbenchAccess(businessPageAccess);

        assertThatThrownBy(() -> access.assertCanAccessWorkbench(newPrincipal("guest", TENANT_ID), TENANT_ID))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("WORKBENCH_ACCESS_DENIED");
    }

    @Test
    void shouldRejectUnauthenticatedAccess() {
        WorkbenchAccess access = new WorkbenchAccess(businessPageAccess);

        assertThatThrownBy(() -> access.assertCanAccessWorkbench(null, TENANT_ID))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("AUTH_REQUIRED");
    }

    private static CurrentUserPrincipal newPrincipal(String role, UUID tenantId) {
        return new CurrentUserPrincipal(USER_ID, "demo", tenantId, role, role, ROLE_ASSIGNMENT_ID);
    }
}
