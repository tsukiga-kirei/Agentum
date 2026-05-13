package com.agentum.workflow.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.organization.domain.UserMembershipEntity;
import com.agentum.organization.infrastructure.UserMembershipRepository;
import com.agentum.permission.domain.RoleEntity;
import com.agentum.permission.infrastructure.RoleRepository;
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
    private RoleRepository roleRepository;

    @Test
    void shouldAllowTenantAdminInsideOwnTenant() {
        WorkflowDesignAccess access = new WorkflowDesignAccess(userMembershipRepository, roleRepository);

        assertThatCode(() -> access.assertCanDesign(newPrincipal("tenant_admin", TENANT_ID), TENANT_ID))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldAllowBusinessUserWithWorkflowDesignerMembership() {
        WorkflowDesignAccess access = new WorkflowDesignAccess(userMembershipRepository, roleRepository);
        RoleEntity role = RoleEntity.create(TENANT_ID, "workflow_designer", "流程设计者", "business", "维护工作流草稿");
        UUID roleId = role.getId();
        UserMembershipEntity membership = UserMembershipEntity.create(TENANT_ID, USER_ID, null, roleId, "默认空间");

        when(userMembershipRepository.findByUserIdAndTenantIdAndStatus(USER_ID, TENANT_ID, "active")).thenReturn(List.of(membership));
        when(roleRepository.findAllById(any())).thenReturn(List.of(role));

        assertThatCode(() -> access.assertCanDesign(newPrincipal("business", TENANT_ID), TENANT_ID))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectBusinessUserWithoutDesignerMembership() {
        WorkflowDesignAccess access = new WorkflowDesignAccess(userMembershipRepository, roleRepository);
        RoleEntity role = RoleEntity.create(TENANT_ID, "executor", "执行人", "business", "发起流程");
        UUID roleId = role.getId();
        UserMembershipEntity membership = UserMembershipEntity.create(TENANT_ID, USER_ID, null, roleId, "默认空间");

        when(userMembershipRepository.findByUserIdAndTenantIdAndStatus(USER_ID, TENANT_ID, "active")).thenReturn(List.of(membership));
        when(roleRepository.findAllById(any())).thenReturn(List.of(role));

        assertThatThrownBy(() -> access.assertCanDesign(newPrincipal("business", TENANT_ID), TENANT_ID))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("PERMISSION_WORKFLOW_DESIGN_DENIED");
    }

    @Test
    void shouldRejectCrossTenantAccess() {
        WorkflowDesignAccess access = new WorkflowDesignAccess(userMembershipRepository, roleRepository);

        assertThatThrownBy(() -> access.assertCanDesign(newPrincipal("business", TENANT_ID), UUID.fromString("00000000-0000-0000-0000-000000000102")))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("PERMISSION_WORKFLOW_DESIGN_DENIED");
    }

    private static CurrentUserPrincipal newPrincipal(String role, UUID tenantId) {
        return new CurrentUserPrincipal(USER_ID, "designer", tenantId, role, role, "默认空间", ROLE_ASSIGNMENT_ID);
    }
}
