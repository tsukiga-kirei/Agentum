package com.agentum.system.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentum.auth.domain.UserRoleAssignmentEntity;
import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.auth.infrastructure.UserRoleAssignmentRepository;
import com.agentum.organization.infrastructure.DepartmentRepository;
import com.agentum.organization.infrastructure.UserMembershipRepository;
import com.agentum.organization.infrastructure.UserMembershipRoleRepository;
import com.agentum.permission.infrastructure.RoleRepository;
import com.agentum.system.infrastructure.ModelProviderRepository;
import com.agentum.system.infrastructure.ModelProviderTypeRepository;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import com.agentum.system.infrastructure.TenantCapabilityGrantRepository;
import com.agentum.system.infrastructure.TenantModelAssignmentRepository;
import com.agentum.system.interfaces.SystemManagementApi;
import com.agentum.tenant.infrastructure.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class SystemManagementServiceTest {

    @Test
    void shouldCreateTenantAdminWithBusinessLoginAssignment() {
        TenantRepository tenantRepository = mock(TenantRepository.class);
        ModelProviderRepository modelProviderRepository = mock(ModelProviderRepository.class);
        ModelProviderTypeRepository modelProviderTypeRepository = mock(ModelProviderTypeRepository.class);
        SystemCapabilityRepository systemCapabilityRepository = mock(SystemCapabilityRepository.class);
        TenantCapabilityGrantRepository tenantCapabilityGrantRepository = mock(TenantCapabilityGrantRepository.class);
        TenantModelAssignmentRepository tenantModelAssignmentRepository = mock(TenantModelAssignmentRepository.class);
        UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
        UserRoleAssignmentRepository userRoleAssignmentRepository = mock(UserRoleAssignmentRepository.class);
        RoleRepository roleRepository = mock(RoleRepository.class);
        DepartmentRepository departmentRepository = mock(DepartmentRepository.class);
        UserMembershipRepository userMembershipRepository = mock(UserMembershipRepository.class);
        UserMembershipRoleRepository userMembershipRoleRepository = mock(UserMembershipRoleRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        List<UserRoleAssignmentEntity> savedAssignments = new ArrayList<>();

        when(tenantRepository.existsByCode("ACME")).thenReturn(false);
        when(userAccountRepository.existsByUsername("acme_admin")).thenReturn(false);
        when(passwordEncoder.encode("change-me-123")).thenReturn("encoded-password");
        when(userRoleAssignmentRepository.save(any(UserRoleAssignmentEntity.class))).thenAnswer(invocation -> {
            UserRoleAssignmentEntity assignment = invocation.getArgument(0);
            savedAssignments.add(assignment);
            return assignment;
        });

        SystemManagementService service = new SystemManagementService(
            tenantRepository,
            modelProviderRepository,
            modelProviderTypeRepository,
            systemCapabilityRepository,
            tenantCapabilityGrantRepository,
            tenantModelAssignmentRepository,
            userAccountRepository,
            userRoleAssignmentRepository,
            roleRepository,
            departmentRepository,
            userMembershipRepository,
            userMembershipRoleRepository,
            passwordEncoder,
            Clock.fixed(Instant.parse("2026-05-15T08:00:00Z"), ZoneOffset.UTC)
        );

        service.createTenant(new SystemManagementApi.CreateTenantRequest(
            "艾可米科技",
            "ACME",
            "acme_admin",
            "艾可米管理员",
            "change-me-123",
            "admin@acme.example"
        ));

        // 新建租户应与历史迁移保持一致：租户管理员可进入租户管理，也可切换到业务视图。
        assertThat(savedAssignments)
            .extracting(UserRoleAssignmentEntity::getRole)
            .containsExactlyInAnyOrder("tenant_admin", "business");
        assertThat(savedAssignments)
            .filteredOn(assignment -> "tenant_admin".equals(assignment.getRole()))
            .allSatisfy(assignment -> assertThat(assignment.isDefaultAssignment()).isTrue());
        assertThat(savedAssignments)
            .filteredOn(assignment -> "business".equals(assignment.getRole()))
            .allSatisfy(assignment -> assertThat(assignment.isDefaultAssignment()).isFalse());
    }
}
