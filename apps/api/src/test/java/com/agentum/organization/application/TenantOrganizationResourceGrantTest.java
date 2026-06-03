package com.agentum.organization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentum.auth.domain.UserAccount;
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
import com.agentum.organization.interfaces.GrantPrincipalRequest;
import com.agentum.organization.interfaces.PageGrantResponse;
import com.agentum.organization.interfaces.ResourceGrantItemRequest;
import com.agentum.organization.interfaces.ResourceGrantResponse;
import com.agentum.organization.interfaces.TenantOrganizationOverviewResponse;
import com.agentum.organization.interfaces.UpdateMemberProfileRequest;
import com.agentum.organization.interfaces.UpdateTenantRoleRequest;
import com.agentum.permission.domain.PageGrantEntity;
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
        RoleEntity role = RoleEntity.create(TENANT_ID, "executor", "执行人", "流程执行角色");
        SystemCapabilityEntity capability = SystemCapabilityEntity.create("skill", "合同解析", "contract_parse", "v1", "", "medium", "active", Map.of(), Instant.now());
        TenantCapabilityGrantEntity capabilityGrant = TenantCapabilityGrantEntity.create(TENANT_ID, capability.getId(), "enabled", Instant.now());

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
        when(roleRepository.findByTenantIdAndStatusOrderByNameAsc(TENANT_ID, "active")).thenReturn(List.of(role));

        ResourceGrantResponse response = service.createResourceGrant(
            TENANT_ID,
            OPERATOR_USER_ID,
            new CreateResourceGrantRequest(
                "合同处理能力",
                List.of(new GrantPrincipalRequest("role", role.getId())),
                List.of(new ResourceGrantItemRequest("skill", capability.getId()))
            )
        );

        assertThat(response.groupName()).isEqualTo("合同处理能力");
        assertThat(response.principals()).hasSize(1);
        assertThat(response.principals().get(0).principalType()).isEqualTo("role");
        assertThat(response.principals().get(0).principalName()).isEqualTo("执行人");
        assertThat(response.resources()).hasSize(1);
        assertThat(response.resources().get(0).resourceName()).isEqualTo("合同解析");
    }

    @Test
    void shouldHideTenantEnabledDraftCapabilityFromResourceOptions() {
        TenantOrganizationService service = newService();
        SystemCapabilityEntity capability = SystemCapabilityEntity.create("prompt_template", "测试", "test_prompt", "v1", "", "low", "draft", Map.of(), Instant.now());
        TenantCapabilityGrantEntity capabilityGrant = TenantCapabilityGrantEntity.create(TENANT_ID, capability.getId(), "enabled", Instant.now());

        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("演示租户", "demo", Instant.now())));
        when(tenantCapabilityGrantRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).thenReturn(List.of(capabilityGrant));
        when(systemCapabilityRepository.findAllById(any())).thenReturn(List.of(capability));

        var options = service.listTenantResourceOptions(TENANT_ID);

        assertThat(options).isEmpty();
    }

    @Test
    void shouldRejectUserPrincipalOutsideCurrentTenant() {
        TenantOrganizationService service = newService();

        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("演示租户", "demo", Instant.now())));
        when(userMembershipRepository.findByUserIdAndTenantIdAndStatus(USER_ID, TENANT_ID, "active")).thenReturn(List.of());

        assertThatThrownBy(() -> service.createResourceGrant(
            TENANT_ID,
            OPERATOR_USER_ID,
            new CreateResourceGrantRequest(
                "外部用户能力",
                List.of(new GrantPrincipalRequest("user", USER_ID)),
                List.of(new ResourceGrantItemRequest("skill", UUID.randomUUID()))
            )
        ))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("ORG_RESOURCE_GRANT_PRINCIPAL_NOT_AVAILABLE");
    }

    @Test
    void shouldCreatePageGrantForDepartmentOrRolePrincipal() {
        TenantOrganizationService service = newService();
        RoleEntity role = RoleEntity.create(TENANT_ID, "workflow_designer", "流程设计者", "维护流程草稿");
        AtomicReference<PageGrantEntity> savedGrant = new AtomicReference<>();

        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("演示租户", "demo", Instant.now())));
        when(roleRepository.findByIdAndTenantIdAndStatus(role.getId(), TENANT_ID, "active")).thenReturn(Optional.of(role));
        when(pageGrantRepository.existsByTenantIdAndPrincipalTypeAndPrincipalIdAndPageKey(TENANT_ID, "role", role.getId(), "designer")).thenReturn(false);
        when(pageGrantRepository.saveAll(any())).thenAnswer(invocation -> {
            List<PageGrantEntity> grants = invocation.getArgument(0);
            PageGrantEntity grant = grants.get(0);
            savedGrant.set(grant);
            return grants;
        });
        when(roleRepository.findByTenantIdAndStatusOrderByNameAsc(TENANT_ID, "active")).thenReturn(List.of(role));

        PageGrantResponse response = service.createPageGrant(
            TENANT_ID,
            OPERATOR_USER_ID,
            new CreatePageGrantRequest(
                "设计入口",
                List.of(new GrantPrincipalRequest("role", role.getId())),
                List.of("designer")
            )
        );

        assertThat(savedGrant.get()).isNotNull();
        assertThat(response.groupName()).isEqualTo("设计入口");
        assertThat(response.principals().get(0).principalName()).isEqualTo("流程设计者");
        assertThat(response.pages().get(0).pageKey()).isEqualTo("designer");
        assertThat(response.pages().get(0).pageName()).isEqualTo("流程设计");
    }

    @Test
    void shouldSyncRoleMembersWhenUpdatingTenantRole() {
        TenantOrganizationService service = newService();
        RoleEntity reviewerRole = RoleEntity.create(TENANT_ID, "reviewer", "合同审核员", "审核合同");
        RoleEntity executorRole = RoleEntity.create(TENANT_ID, "executor", "执行人", "执行流程");
        UserMembershipEntity currentReviewer = UserMembershipEntity.create(TENANT_ID, USER_ID, null);
        UserMembershipEntity currentExecutor = UserMembershipEntity.create(TENANT_ID, UUID.randomUUID(), null);
        UserMembershipRoleEntity reviewerLink = UserMembershipRoleEntity.create(currentReviewer.getId(), reviewerRole.getId());
        AtomicReference<UserMembershipRoleEntity> addedLink = new AtomicReference<>();

        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("演示租户", "demo", Instant.now())));
        when(roleRepository.findByIdAndTenantId(reviewerRole.getId(), TENANT_ID)).thenReturn(Optional.of(reviewerRole));
        when(userMembershipRepository.findByTenantId(TENANT_ID)).thenReturn(List.of(currentReviewer, currentExecutor));
        when(userMembershipRoleRepository.findByMembershipIdInAndStatus(any(), any())).thenReturn(List.of(reviewerLink));
        when(userMembershipRoleRepository.save(any(UserMembershipRoleEntity.class))).thenAnswer(invocation -> {
            UserMembershipRoleEntity link = invocation.getArgument(0);
            if (currentExecutor.getId().equals(link.getMembershipId())) {
                addedLink.set(link);
            }
            return link;
        });
        when(userRoleAssignmentRepository.findByUserIdAndRoleAndTenantId(any(), any(), any()))
            .thenReturn(Optional.of(com.agentum.auth.domain.UserRoleAssignmentEntity.create(USER_ID, "business", TENANT_ID, "业务用户", true)));
        when(userAccountRepository.findAllById(any())).thenReturn(List.of());
        when(departmentRepository.findByTenantIdOrderBySortOrderAscNameAsc(TENANT_ID)).thenReturn(List.of());
        when(roleRepository.findByTenantIdOrderByNameAsc(TENANT_ID)).thenReturn(List.of(reviewerRole, executorRole));

        service.updateTenantRole(
            TENANT_ID,
            OPERATOR_USER_ID,
            reviewerRole.getId(),
            new UpdateTenantRoleRequest("合同审核员", "审核合同", "active", List.of(currentExecutor.getId()))
        );

        assertThat(currentExecutor.getStatus()).isEqualTo("active");
        assertThat(reviewerLink.getStatus()).isEqualTo("disabled");
        assertThat(addedLink.get()).isNotNull();
        assertThat(addedLink.get().getMembershipId()).isEqualTo(currentExecutor.getId());
        assertThat(addedLink.get().getRoleId()).isEqualTo(reviewerRole.getId());
    }

    @Test
    void shouldRejectDisablingRoleWithSelectedMembers() {
        TenantOrganizationService service = newService();
        RoleEntity reviewerRole = RoleEntity.create(TENANT_ID, "reviewer", "合同审核员", "审核合同");
        UserMembershipEntity currentReviewer = UserMembershipEntity.create(TENANT_ID, USER_ID, null);

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

    @Test
    void shouldDisableRoleWithoutMembersAndKeepItInOverview() {
        TenantOrganizationService service = newService();
        RoleEntity reviewerRole = RoleEntity.create(TENANT_ID, "reviewer", "合同审核员", "审核合同");
        UserMembershipEntity currentReviewer = UserMembershipEntity.create(TENANT_ID, USER_ID, null);

        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("演示租户", "demo", Instant.now())));
        when(roleRepository.findByIdAndTenantId(reviewerRole.getId(), TENANT_ID)).thenReturn(Optional.of(reviewerRole));
        when(userMembershipRoleRepository.countActiveMembershipsByTenantIdAndRoleId(TENANT_ID, reviewerRole.getId(), "active", "active")).thenReturn(0L);
        when(userAccountRepository.findAllById(any())).thenReturn(List.of());
        when(departmentRepository.findByTenantIdOrderBySortOrderAscNameAsc(TENANT_ID)).thenReturn(List.of());
        when(roleRepository.findByTenantIdOrderByNameAsc(TENANT_ID)).thenReturn(List.of(reviewerRole));
        when(userMembershipRepository.findByTenantId(TENANT_ID)).thenReturn(List.of(currentReviewer));
        when(userMembershipRoleRepository.findByMembershipIdInAndStatus(any(), any())).thenReturn(List.of());

        TenantOrganizationOverviewResponse overview = service.updateRoleStatus(TENANT_ID, OPERATOR_USER_ID, reviewerRole.getId(), "disabled");

        assertThat(reviewerRole.getStatus()).isEqualTo("disabled");
        assertThat(overview.roles()).extracting("name", "status", "description")
            .containsExactly(tuple("合同审核员", "disabled", "审核合同"));
    }

    @Test
    void shouldRejectDeletingRoleWithActiveMembers() {
        TenantOrganizationService service = newService();
        RoleEntity reviewerRole = RoleEntity.create(TENANT_ID, "reviewer", "合同审核员", "审核合同");

        when(roleRepository.findByIdAndTenantId(reviewerRole.getId(), TENANT_ID)).thenReturn(Optional.of(reviewerRole));
        when(userMembershipRoleRepository.countActiveMembershipsByTenantIdAndRoleId(TENANT_ID, reviewerRole.getId(), "active", "active")).thenReturn(1L);

        assertThatThrownBy(() -> service.deleteTenantRole(TENANT_ID, OPERATOR_USER_ID, reviewerRole.getId()))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("ORG_ROLE_HAS_MEMBERS");
    }

    @Test
    void shouldDeleteRoleWhenNoMemberOrGrantReferenceExists() {
        TenantOrganizationService service = newService();
        RoleEntity reviewerRole = RoleEntity.create(TENANT_ID, "reviewer", "合同审核员", "审核合同");

        when(roleRepository.findByIdAndTenantId(reviewerRole.getId(), TENANT_ID)).thenReturn(Optional.of(reviewerRole));
        when(userMembershipRoleRepository.countActiveMembershipsByTenantIdAndRoleId(TENANT_ID, reviewerRole.getId(), "active", "active")).thenReturn(0L);
        when(pageGrantRepository.countByTenantIdAndPrincipalTypeAndPrincipalId(TENANT_ID, "role", reviewerRole.getId())).thenReturn(0L);
        when(resourceGrantRepository.countByTenantIdAndPrincipalTypeAndPrincipalId(TENANT_ID, "role", reviewerRole.getId())).thenReturn(0L);

        service.deleteTenantRole(TENANT_ID, OPERATOR_USER_ID, reviewerRole.getId());

        verify(userMembershipRoleRepository).deleteByRoleId(reviewerRole.getId());
        verify(roleRepository).delete(reviewerRole);
    }

    @Test
    void shouldUpdateMemberProfileForTenantMembership() {
        TenantOrganizationService service = newService();
        UserMembershipEntity membership = UserMembershipEntity.create(TENANT_ID, USER_ID, null);
        UserAccount account = UserAccount.create("old_operator", "hash", "旧姓名", "old@example.com");

        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("演示租户", "demo", Instant.now())));
        when(userMembershipRepository.findByIdAndTenantId(membership.getId(), TENANT_ID)).thenReturn(Optional.of(membership));
        when(userAccountRepository.findById(membership.getUserId())).thenReturn(Optional.of(account));
        when(userAccountRepository.existsByUsernameAndIdNot("new_operator", account.getId())).thenReturn(false);
        when(userAccountRepository.findAllById(any())).thenReturn(List.of(account));
        when(departmentRepository.findByTenantIdOrderBySortOrderAscNameAsc(TENANT_ID)).thenReturn(List.of());
        when(roleRepository.findByTenantIdOrderByNameAsc(TENANT_ID)).thenReturn(List.of());
        when(userMembershipRepository.findByTenantId(TENANT_ID)).thenReturn(List.of(membership));
        when(userMembershipRoleRepository.findByMembershipIdInAndStatus(any(), any())).thenReturn(List.of());

        service.updateMemberProfile(
            TENANT_ID,
            OPERATOR_USER_ID,
            membership.getId(),
            new UpdateMemberProfileRequest("new_operator", "新姓名", "new@example.com")
        );

        assertThat(account.getUsername()).isEqualTo("new_operator");
        assertThat(account.getDisplayName()).isEqualTo("新姓名");
        assertThat(account.getEmail()).isEqualTo("new@example.com");
        verify(userAccountRepository).save(account);
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
