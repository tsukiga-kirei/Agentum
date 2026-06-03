package com.agentum.organization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.auth.infrastructure.UserRoleAssignmentRepository;
import com.agentum.organization.domain.DepartmentEntity;
import com.agentum.organization.domain.UserMembershipEntity;
import com.agentum.organization.infrastructure.DepartmentRepository;
import com.agentum.organization.infrastructure.TenantOrgRoleRepository;
import com.agentum.organization.infrastructure.UserMembershipRepository;
import com.agentum.organization.infrastructure.UserMembershipRoleRepository;
import com.agentum.organization.interfaces.PrincipalGrantUsageResponse;
import com.agentum.organization.interfaces.UpdateDepartmentRequest;
import com.agentum.organization.interfaces.UpdateMembershipStatusRequest;
import com.agentum.permission.domain.RoleEntity;
import com.agentum.permission.infrastructure.PageGrantRepository;
import com.agentum.permission.infrastructure.ResourceGrantRepository;
import com.agentum.permission.infrastructure.RoleRepository;
import com.agentum.shared.api.ApiException;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import com.agentum.system.infrastructure.TenantCapabilityGrantRepository;
import com.agentum.tenant.domain.TenantEntity;
import com.agentum.tenant.infrastructure.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class TenantOrganizationLifecycleTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID OPERATOR_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private UserAccountRepository userAccountRepository;
    @Mock
    private UserRoleAssignmentRepository userRoleAssignmentRepository;
    @Mock
    private UserMembershipRepository userMembershipRepository;
    @Mock
    private UserMembershipRoleRepository userMembershipRoleRepository;
    @Mock
    private DepartmentRepository departmentRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private PageGrantRepository pageGrantRepository;
    @Mock
    private ResourceGrantRepository resourceGrantRepository;
    @Mock
    private TenantOrgRoleRepository tenantOrgRoleRepository;
    @Mock
    private TenantCapabilityGrantRepository tenantCapabilityGrantRepository;
    @Mock
    private SystemCapabilityRepository systemCapabilityRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    @Test
    void shouldRejectDisablingDepartmentReferencedByPageGrants() {
        TenantOrganizationService service = newService();
        DepartmentEntity department = DepartmentEntity.create(TENANT_ID, null, "销售部", "sales", 0);

        when(departmentRepository.findByIdAndTenantId(department.getId(), TENANT_ID)).thenReturn(Optional.of(department));
        when(userMembershipRepository.countByTenantIdAndDepartmentIdAndStatus(TENANT_ID, department.getId(), "active")).thenReturn(0L);
        when(departmentRepository.countByTenantIdAndParentIdAndStatus(TENANT_ID, department.getId(), "active")).thenReturn(0L);
        when(pageGrantRepository.countByTenantIdAndPrincipalTypeAndPrincipalId(TENANT_ID, "department", department.getId())).thenReturn(2L);
        when(resourceGrantRepository.countByTenantIdAndPrincipalTypeAndPrincipalId(TENANT_ID, "department", department.getId())).thenReturn(0L);

        assertThatThrownBy(() -> service.updateDepartmentStatus(TENANT_ID, OPERATOR_USER_ID, department.getId(), "disabled"))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("ORG_PRINCIPAL_HAS_PAGE_GRANTS");
    }

    @Test
    void shouldRejectDisablingRoleReferencedByResourceGrants() {
        TenantOrganizationService service = newService();
        RoleEntity role = RoleEntity.create(TENANT_ID, "reviewer", "审核员", "审核");

        when(roleRepository.findByIdAndTenantId(role.getId(), TENANT_ID)).thenReturn(Optional.of(role));
        when(userMembershipRoleRepository.countActiveMembershipsByTenantIdAndRoleId(TENANT_ID, role.getId(), "active", "active")).thenReturn(0L);
        when(pageGrantRepository.countByTenantIdAndPrincipalTypeAndPrincipalId(TENANT_ID, "role", role.getId())).thenReturn(0L);
        when(resourceGrantRepository.countByTenantIdAndPrincipalTypeAndPrincipalId(TENANT_ID, "role", role.getId())).thenReturn(1L);

        assertThatThrownBy(() -> service.updateRoleStatus(TENANT_ID, OPERATOR_USER_ID, role.getId(), "disabled"))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("ORG_PRINCIPAL_HAS_RESOURCE_GRANTS");
    }

    @Test
    void shouldRejectDisablingMembershipReferencedByGrants() {
        TenantOrganizationService service = newService();
        UserMembershipEntity membership = UserMembershipEntity.create(TENANT_ID, USER_ID, null);

        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("演示租户", "demo", Instant.now())));
        when(userMembershipRepository.findByIdAndTenantId(membership.getId(), TENANT_ID)).thenReturn(Optional.of(membership));
        when(userMembershipRoleRepository.findByMembershipIdAndStatus(membership.getId(), "active")).thenReturn(List.of());
        when(pageGrantRepository.countByTenantIdAndPrincipalTypeAndPrincipalId(TENANT_ID, "user", USER_ID)).thenReturn(1L);
        when(resourceGrantRepository.countByTenantIdAndPrincipalTypeAndPrincipalId(TENANT_ID, "user", USER_ID)).thenReturn(1L);

        assertThatThrownBy(() -> service.updateMembershipStatus(
            TENANT_ID,
            OPERATOR_USER_ID,
            membership.getId(),
            new UpdateMembershipStatusRequest("disabled")
        ))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("ORG_PRINCIPAL_HAS_GRANTS");
    }

    @Test
    void shouldRejectDepartmentParentCycle() {
        TenantOrganizationService service = newService();
        DepartmentEntity root = DepartmentEntity.create(TENANT_ID, null, "总部", "hq", 0);
        DepartmentEntity child = DepartmentEntity.create(TENANT_ID, root.getId(), "分部", "branch", 1);

        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("演示租户", "demo", Instant.now())));
        when(departmentRepository.findByIdAndTenantId(root.getId(), TENANT_ID)).thenReturn(Optional.of(root));
        when(departmentRepository.findByIdAndTenantIdAndStatus(child.getId(), TENANT_ID, "active")).thenReturn(Optional.of(child));
        when(departmentRepository.findByIdAndTenantId(child.getId(), TENANT_ID)).thenReturn(Optional.of(child));

        assertThatThrownBy(() -> service.updateDepartment(
            TENANT_ID,
            OPERATOR_USER_ID,
            root.getId(),
            new UpdateDepartmentRequest("总部", child.getId(), 0)
        ))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("ORG_DEPARTMENT_CYCLE");
    }

    @Test
    void shouldReturnPrincipalGrantUsage() {
        TenantOrganizationService service = newService();
        UUID roleId = UUID.randomUUID();

        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("演示租户", "demo", Instant.now())));
        when(pageGrantRepository.countByTenantIdAndPrincipalTypeAndPrincipalId(TENANT_ID, "role", roleId)).thenReturn(3L);
        when(resourceGrantRepository.countByTenantIdAndPrincipalTypeAndPrincipalId(TENANT_ID, "role", roleId)).thenReturn(1L);

        PrincipalGrantUsageResponse usage = service.getPrincipalGrantUsage(TENANT_ID, "role", roleId);

        assertThat(usage.principalType()).isEqualTo("role");
        assertThat(usage.principalId()).isEqualTo(roleId.toString());
        assertThat(usage.pageGrantRows()).isEqualTo(3L);
        assertThat(usage.resourceGrantRows()).isEqualTo(1L);
    }

    private TenantOrganizationService newService() {
        return new TenantOrganizationService(
            tenantRepository,
            userAccountRepository,
            userRoleAssignmentRepository,
            userMembershipRepository,
            userMembershipRoleRepository,
            departmentRepository,
            roleRepository,
            pageGrantRepository,
            resourceGrantRepository,
            tenantOrgRoleRepository,
            tenantCapabilityGrantRepository,
            systemCapabilityRepository,
            passwordEncoder,
            new ObjectMapper()
        );
    }
}
