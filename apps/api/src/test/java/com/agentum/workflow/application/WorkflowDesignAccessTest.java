package com.agentum.workflow.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.organization.domain.UserMembershipEntity;
import com.agentum.organization.infrastructure.UserMembershipRepository;
import com.agentum.organization.infrastructure.UserMembershipRoleRepository;
import com.agentum.permission.application.BusinessPageAccess;
import com.agentum.permission.domain.PageGrantEntity;
import com.agentum.permission.infrastructure.PageGrantRepository;
import com.agentum.shared.api.ApiException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkflowDesignAccessTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ROLE_ASSIGNMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000502");

    @Mock
    private UserMembershipRepository userMembershipRepository;

    @Mock
    private UserMembershipRoleRepository userMembershipRoleRepository;

    @Mock
    private PageGrantRepository pageGrantRepository;

    @Test
    void shouldAllowTenantAdminInsideOwnTenant() {
        WorkflowDesignAccess access = newAccess();

        assertThatCode(() -> access.assertCanDesign(newPrincipal("tenant_admin", TENANT_ID), TENANT_ID))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldAllowBusinessUserWithDesignerPageGrant() {
        WorkflowDesignAccess access = newAccess();
        UserMembershipEntity membership = UserMembershipEntity.create(TENANT_ID, USER_ID, null);

        when(userMembershipRepository.findByUserIdAndTenantIdAndStatus(USER_ID, TENANT_ID, "active")).thenReturn(List.of(membership));
        when(userMembershipRoleRepository.findByMembershipIdInAndStatus(any(), eq("active"))).thenReturn(List.of());
        when(pageGrantRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).thenReturn(List.of(
            PageGrantEntity.create(TENANT_ID, UUID.randomUUID(), "人员流程设计", "designer", "user", USER_ID)
        ));

        assertThatCode(() -> access.assertCanDesign(newPrincipal("business", TENANT_ID), TENANT_ID))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectBusinessUserWithoutDesignerPageGrant() {
        WorkflowDesignAccess access = newAccess();
        UserMembershipEntity membership = UserMembershipEntity.create(TENANT_ID, USER_ID, null);

        when(userMembershipRepository.findByUserIdAndTenantIdAndStatus(USER_ID, TENANT_ID, "active")).thenReturn(List.of(membership));
        when(userMembershipRoleRepository.findByMembershipIdInAndStatus(any(), eq("active"))).thenReturn(List.of());
        when(pageGrantRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> access.assertCanDesign(newPrincipal("business", TENANT_ID), TENANT_ID))
            .isInstanceOf(ApiException.class)
            .extracting("code", "message")
            .containsExactly(
                "PERMISSION_WORKFLOW_DESIGN_DENIED",
                "当前账号未被分配流程设计页签，请联系租户管理员在页签分配中开通"
            );
    }

    @Test
    void shouldRejectCrossTenantAccess() {
        WorkflowDesignAccess access = newAccess();

        assertThatThrownBy(() -> access.assertCanDesign(newPrincipal("business", TENANT_ID), UUID.fromString("00000000-0000-0000-0000-000000000102")))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("PERMISSION_WORKFLOW_DESIGN_DENIED");
    }

    private WorkflowDesignAccess newAccess() {
        return new WorkflowDesignAccess(
            new BusinessPageAccess(pageGrantRepository, userMembershipRepository, userMembershipRoleRepository)
        );
    }

    private static CurrentUserPrincipal newPrincipal(String role, UUID tenantId) {
        return new CurrentUserPrincipal(USER_ID, "designer", tenantId, role, role, ROLE_ASSIGNMENT_ID);
    }
}
