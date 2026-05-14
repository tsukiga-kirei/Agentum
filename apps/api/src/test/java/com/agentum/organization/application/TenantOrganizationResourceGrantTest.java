package com.agentum.organization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.auth.infrastructure.UserRoleAssignmentRepository;
import com.agentum.organization.domain.UserMembershipEntity;
import com.agentum.organization.domain.UserMembershipRoleEntity;
import com.agentum.organization.infrastructure.DepartmentRepository;
import com.agentum.organization.infrastructure.TenantOrgRoleRepository;
import com.agentum.organization.infrastructure.UserMembershipRepository;
import com.agentum.organization.infrastructure.UserMembershipRoleRepository;
import com.agentum.organization.interfaces.CreateResourceGrantRequest;
import com.agentum.organization.interfaces.CreatePageGrantRequest;
import com.agentum.organization.interfaces.PageGrantResponse;
import com.agentum.organization.interfaces.ResourceGrantResponse;
import com.agentum.organization.interfaces.UpdateTenantRoleRequest;
import com.agentum.permission.domain.PageGrantEntity;
import com.agentum.permission.domain.ResourceGrantEntity;
import com.agentum.permission.domain.RoleEntity;
import com.agentum.permission.infrastructure.ResourceGrantRepository;
import com.agentum.permission.infrastructure.PageGrantRepository;
import com.agentum.permission.infrastructure.RoleRepository;
import com.agentum.shared.api.ApiException;
import com.agentum.system.domain.SystemCapabilityEntity;
import com.agentum.system.domain.TenantCapabilityGrantEntity;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import com.agentum.system.infrastructure.TenantCapabilityGrantRepository;
import com.agentum.tenant.domain.TenantEntity;
import com.agentum.tenant.infrastructure.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class TenantOrganizationResourceGrantTest {

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
    void shouldCreateGrantForEnabledCapabilityAndRolePrincipal() {
        TenantOrganizationService service = newService();
        RoleEntity role = RoleEntity.create(TENANT_ID, "executor", "执行人", "business", "流程执行角色");
        SystemCapabilityEntity capability = SystemCapabilityEntity.create("skill", "合同解析", "contract_parse", "v1", "medium", "active", Map.of(), Instant.now());
        TenantCapabilityGrantEntity capabilityGrant = TenantCapabilityGrantEntity.create(TENANT_ID, capability.getId(), "enabled", Instant.now());
        AtomicReference<ResourceGrantEntity> savedGrant = new AtomicReference<>();

        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("演示租户", "demo", Instant.now())));
        when(roleRepository.findByIdAndTenantIdAndStatus(role.getId(), TENANT_ID, "active")).thenReturn(Optional.of(role));
        when(tenantCapabilityGrantRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).thenReturn(List.of(capabilityGrant));
        when(systemCapabilityRepository.findAllById(any())).thenReturn(List.of(capability));
        when(resourceGrantRepository.existsByTenantIdAndPrincipalTypeAndPrincipalIdAndResourceTypeAndResourceId(
            TENANT_ID,
            "role",
            role.getId(),
            "skill",
            capability.getId()
        )).thenReturn(false);
        when(resourceGrantRepository.save(any(ResourceGrantEntity.class))).thenAnswer(invocation -> {
            ResourceGrantEntity grant = invocation.getArgument(0);
            savedGrant.set(grant);
            return grant;
        });
        when(resourceGrantRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).thenAnswer(invocation -> List.of(savedGrant.get()));
        when(roleRepository.findByTenantIdAndStatusOrderByNameAsc(TENANT_ID, "active")).thenReturn(List.of(role));
        when(departmentRepository.findByTenantIdAndStatusOrderBySortOrderAscNameAsc(TENANT_ID, "active")).thenReturn(List.of());
        when(userMembershipRepository.findByTenantIdAndStatus(TENANT_ID, "active")).thenReturn(List.of());
        when(userAccountRepository.findAllById(any())).thenReturn(List.of());

        ResourceGrantResponse response = service.createResourceGrant(
            TENANT_ID,
            OPERATOR_USER_ID,
            new CreateResourceGrantRequest("role", role.getId(), "skill", capability.getId(), List.of("use", "execute"))
        );

        assertThat(response.principalType()).isEqualTo("role");
        assertThat(response.principalName()).isEqualTo("执行人");
        assertThat(response.resourceName()).isEqualTo("合同解析");
        assertThat(response.actions()).containsExactly("use", "execute");
    }

    @Test
    void shouldRejectUserPrincipalOutsideCurrentTenant() {
        TenantOrganizationService service = newService();

        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("演示租户", "demo", Instant.now())));
        when(userMembershipRepository.findByUserIdAndTenantIdAndStatus(USER_ID, TENANT_ID, "active")).thenReturn(List.of());

        assertThatThrownBy(() -> service.createResourceGrant(
            TENANT_ID,
            OPERATOR_USER_ID,
            new CreateResourceGrantRequest("user", USER_ID, "skill", UUID.randomUUID(), List.of("use"))
        ))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("ORG_RESOURCE_GRANT_PRINCIPAL_NOT_AVAILABLE");
    }

    @Test
    void shouldCreatePageGrantForDepartmentOrRolePrincipal() {
        TenantOrganizationService service = newService();
        RoleEntity role = RoleEntity.create(TENANT_ID, "workflow_designer", "流程设计者", "business", "维护流程草稿");
        AtomicReference<PageGrantEntity> savedGrant = new AtomicReference<>();

        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("演示租户", "demo", Instant.now())));
        when(roleRepository.findByIdAndTenantIdAndStatus(role.getId(), TENANT_ID, "active")).thenReturn(Optional.of(role));
        when(pageGrantRepository.existsByTenantIdAndPrincipalTypeAndPrincipalIdAndPageKey(TENANT_ID, "role", role.getId(), "designer")).thenReturn(false);
        when(pageGrantRepository.save(any(PageGrantEntity.class))).thenAnswer(invocation -> {
            PageGrantEntity grant = invocation.getArgument(0);
            savedGrant.set(grant);
            return grant;
        });
        when(roleRepository.findByTenantIdAndStatusOrderByNameAsc(TENANT_ID, "active")).thenReturn(List.of(role));
        when(departmentRepository.findByTenantIdAndStatusOrderBySortOrderAscNameAsc(TENANT_ID, "active")).thenReturn(List.of());
        when(userMembershipRepository.findByTenantIdAndStatus(TENANT_ID, "active")).thenReturn(List.of());
        when(userAccountRepository.findAllById(any())).thenReturn(List.of());

        PageGrantResponse response = service.createPageGrant(
            TENANT_ID,
            OPERATOR_USER_ID,
            new CreatePageGrantRequest("role", role.getId(), "designer")
        );

        assertThat(savedGrant.get()).isNotNull();
        assertThat(response.principalName()).isEqualTo("流程设计者");
        assertThat(response.pageKey()).isEqualTo("designer");
        assertThat(response.pageName()).isEqualTo("流程设计");
    }

    @Test
    void shouldSyncRoleMembersWhenUpdatingTenantRole() {
        TenantOrganizationService service = newService();
        RoleEntity reviewerRole = RoleEntity.create(TENANT_ID, "reviewer", "合同审核员", "business", "审核合同");
        RoleEntity executorRole = RoleEntity.create(TENANT_ID, "executor", "执行人", "business", "执行流程");
        UserMembershipEntity currentReviewer = UserMembershipEntity.create(TENANT_ID, USER_ID, null, "默认空间");
        UserMembershipEntity currentExecutor = UserMembershipEntity.create(TENANT_ID, UUID.randomUUID(), null, "默认空间");
        UserMembershipRoleEntity reviewerLink = UserMembershipRoleEntity.create(currentReviewer.getId(), reviewerRole.getId());
        AtomicReference<UserMembershipRoleEntity> savedLink = new AtomicReference<>();

        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("演示租户", "demo", Instant.now())));
        when(roleRepository.findByIdAndTenantId(reviewerRole.getId(), TENANT_ID)).thenReturn(Optional.of(reviewerRole));
        when(userMembershipRepository.findByTenantId(TENANT_ID)).thenReturn(List.of(currentReviewer, currentExecutor));
        when(userMembershipRoleRepository.findByMembershipIdInAndStatus(any(), any())).thenReturn(List.of(reviewerLink));
        when(userMembershipRoleRepository.save(any(UserMembershipRoleEntity.class))).thenAnswer(invocation -> {
            UserMembershipRoleEntity link = invocation.getArgument(0);
            savedLink.set(link);
            return link;
        });
        when(userRoleAssignmentRepository.findByUserIdAndRoleAndTenantId(any(), any(), any()))
            .thenReturn(Optional.of(com.agentum.auth.domain.UserRoleAssignmentEntity.create(USER_ID, "business", TENANT_ID, "业务用户", true)));
        when(userAccountRepository.findAllById(any())).thenReturn(List.of());
        when(departmentRepository.findByTenantIdAndStatusOrderBySortOrderAscNameAsc(TENANT_ID, "active")).thenReturn(List.of());
        when(roleRepository.findByTenantIdAndStatusOrderByNameAsc(TENANT_ID, "active")).thenReturn(List.of(reviewerRole, executorRole));

        service.updateTenantRole(
            TENANT_ID,
            OPERATOR_USER_ID,
            reviewerRole.getId(),
            new UpdateTenantRoleRequest("合同审核员", "审核合同", "active", List.of(currentExecutor.getId()))
        );

        assertThat(currentExecutor.getStatus()).isEqualTo("active");
        assertThat(reviewerLink.getStatus()).isEqualTo("disabled");
        assertThat(savedLink.get()).isNotNull();
        assertThat(savedLink.get().getMembershipId()).isEqualTo(currentExecutor.getId());
        assertThat(savedLink.get().getRoleId()).isEqualTo(reviewerRole.getId());
    }

    @Test
    void shouldRejectDisablingRoleWithSelectedMembers() {
        TenantOrganizationService service = newService();
        RoleEntity reviewerRole = RoleEntity.create(TENANT_ID, "reviewer", "合同审核员", "business", "审核合同");
        UserMembershipEntity currentReviewer = UserMembershipEntity.create(TENANT_ID, USER_ID, null, "默认空间");

        when(roleRepository.findByIdAndTenantId(reviewerRole.getId(), TENANT_ID)).thenReturn(Optional.of(reviewerRole));
        when(userMembershipRepository.findByTenantId(TENANT_ID)).thenReturn(List.of(currentReviewer));

        assertThatThrownBy(() -> service.updateTenantRole(
            TENANT_ID,
            OPERATOR_USER_ID,
            reviewerRole.getId(),
            new UpdateTenantRoleRequest("合同审核员", "审核合同", "disabled", List.of(currentReviewer.getId()))
        ))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("ORG_ROLE_DISABLE_WITH_MEMBERS");
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
