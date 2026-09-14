package com.agentum.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyCollection;

import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.organization.domain.UserMembershipEntity;
import com.agentum.organization.domain.UserMembershipRoleEntity;
import com.agentum.organization.infrastructure.DepartmentRepository;
import com.agentum.organization.infrastructure.UserMembershipRepository;
import com.agentum.organization.infrastructure.UserMembershipRoleRepository;
import com.agentum.permission.application.CollaborationAccessPolicy;
import com.agentum.permission.application.TenantPrincipalResolver;
import com.agentum.permission.infrastructure.RoleRepository;
import com.agentum.workflow.domain.WorkflowAccessGrantEntity;
import com.agentum.workflow.domain.WorkflowDefinitionEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkflowAccessServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final UUID ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID DEPARTMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");

    private final UserMembershipRepository membershipRepository = mock(UserMembershipRepository.class);
    private final UserMembershipRoleRepository membershipRoleRepository = mock(UserMembershipRoleRepository.class);
    private final WorkflowAccessService service = new WorkflowAccessService(
        membershipRepository,
        mock(RoleRepository.class),
        mock(DepartmentRepository.class),
        mock(UserAccountRepository.class),
        new TenantPrincipalResolver(membershipRepository, membershipRoleRepository),
        new CollaborationAccessPolicy()
    );

    @Test
    void shouldResolveReadAccessFromRoleGrant() {
        UserMembershipEntity membership = UserMembershipEntity.create(TENANT_ID, USER_ID, null);
        when(membershipRepository.findByUserIdAndTenantIdAndStatus(USER_ID, TENANT_ID, "active")).thenReturn(List.of(membership));
        when(membershipRoleRepository.findByMembershipIdInAndStatus(anyCollection(), org.mockito.ArgumentMatchers.eq("active")))
            .thenReturn(List.of(UserMembershipRoleEntity.create(membership.getId(), ROLE_ID)));
        WorkflowDefinitionEntity definition = specifiedReadDefinition();

        var access = service.resolve(
            definition,
            USER_ID,
            List.of(WorkflowAccessGrantEntity.create(TENANT_ID, definition.getId(), "role", ROLE_ID, "read", OWNER_ID, NOW))
        );

        assertThat(access).isEqualTo(CollaborationAccessPolicy.AccessLevel.READ);
    }

    @Test
    void shouldResolveReadAccessFromDepartmentGrant() {
        UserMembershipEntity membership = UserMembershipEntity.create(TENANT_ID, USER_ID, DEPARTMENT_ID);
        when(membershipRepository.findByUserIdAndTenantIdAndStatus(USER_ID, TENANT_ID, "active")).thenReturn(List.of(membership));
        WorkflowDefinitionEntity definition = specifiedReadDefinition();

        var access = service.resolve(
            definition,
            USER_ID,
            List.of(WorkflowAccessGrantEntity.create(TENANT_ID, definition.getId(), "department", DEPARTMENT_ID, "read", OWNER_ID, NOW))
        );

        assertThat(access).isEqualTo(CollaborationAccessPolicy.AccessLevel.READ);
    }

    private static WorkflowDefinitionEntity specifiedReadDefinition() {
        WorkflowDefinitionEntity definition = WorkflowDefinitionEntity.create(TENANT_ID, "角色可读流程", "", OWNER_ID, NOW);
        definition.updateAccess("specified", "self", OWNER_ID, NOW);
        return definition;
    }
}
