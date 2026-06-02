package com.agentum.system.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentum.auth.domain.UserRoleAssignmentEntity;
import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.auth.infrastructure.UserRoleAssignmentRepository;
import com.agentum.organization.infrastructure.DepartmentRepository;
import com.agentum.organization.infrastructure.UserMembershipRepository;
import com.agentum.organization.infrastructure.UserMembershipRoleRepository;
import com.agentum.permission.infrastructure.RoleRepository;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.security.FieldEncryptionService;
import com.agentum.system.domain.ModelProviderEntity;
import com.agentum.system.domain.ModelProviderTypeEntity;
import com.agentum.system.domain.SystemCapabilityEntity;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class SystemManagementServiceTest {

    private static final FieldEncryptionService FIELD_ENCRYPTION = new FieldEncryptionService("test-master-key-with-enough-length");

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
            FIELD_ENCRYPTION,
            mock(ModelProviderConnectionTester.class),
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

    @Test
    void shouldDeleteModelProviderWhenExists() {
        ModelProviderRepository modelProviderRepository = mock(ModelProviderRepository.class);
        UUID providerId = UUID.randomUUID();
        ModelProviderEntity entity = mock(ModelProviderEntity.class);
        when(modelProviderRepository.findById(providerId)).thenReturn(Optional.of(entity));
        when(entity.getId()).thenReturn(providerId);
        when(entity.getName()).thenReturn("演示供应商");

        SystemManagementService service = buildService(modelProviderRepository, mock(SystemCapabilityRepository.class));

        service.deleteModelProvider(providerId);

        verify(modelProviderRepository).delete(entity);
    }

    @Test
    void shouldRejectDeleteModelProviderWhenMissing() {
        ModelProviderRepository modelProviderRepository = mock(ModelProviderRepository.class);
        UUID providerId = UUID.randomUUID();
        when(modelProviderRepository.findById(providerId)).thenReturn(Optional.empty());

        SystemManagementService service = buildService(modelProviderRepository, mock(SystemCapabilityRepository.class));

        assertThatThrownBy(() -> service.deleteModelProvider(providerId))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("模型供应商不存在");
    }

    @Test
    void shouldStoreModelProviderApiKeyEncryptedWhenCreatingProvider() {
        ModelProviderRepository modelProviderRepository = mock(ModelProviderRepository.class);
        ModelProviderTypeRepository modelProviderTypeRepository = mock(ModelProviderTypeRepository.class);
        ModelProviderTypeEntity providerType = mock(ModelProviderTypeEntity.class);
        List<ModelProviderEntity> savedProviders = new ArrayList<>();

        when(providerType.getCode()).thenReturn("openai-compatible");
        when(providerType.getDefaultBaseUrl()).thenReturn("https://api.example.com/v1");
        when(modelProviderTypeRepository.findByCodeAndStatus("openai-compatible", "active")).thenReturn(Optional.of(providerType));
        when(modelProviderRepository.save(any(ModelProviderEntity.class))).thenAnswer(invocation -> {
            ModelProviderEntity entity = invocation.getArgument(0);
            savedProviders.add(entity);
            return entity;
        });

        SystemManagementService service = buildService(
            modelProviderRepository,
            modelProviderTypeRepository,
            mock(SystemCapabilityRepository.class),
            mock(ModelProviderConnectionTester.class)
        );

        service.createModelProvider(new SystemManagementApi.CreateModelProviderRequest(
            "OpenAI 兼容测试",
            "openai-compatible",
            "",
            "gpt-4o-mini",
            "sk-test-secret",
            "active"
        ));

        assertThat(savedProviders).hasSize(1);
        ModelProviderEntity provider = savedProviders.getFirst();
        String encryptedApiKey = provider.getEncryptedApiKey();
        assertThat(encryptedApiKey).isNotBlank();
        assertThat(encryptedApiKey).doesNotContain("sk-test-secret");
        assertThat(FIELD_ENCRYPTION.decrypt(encryptedApiKey)).isEqualTo("sk-test-secret");
    }

    @Test
    void shouldAllowModelProviderTestWithoutApiKey() {
        ModelProviderRepository modelProviderRepository = mock(ModelProviderRepository.class);
        ModelProviderTypeRepository modelProviderTypeRepository = mock(ModelProviderTypeRepository.class);
        ModelProviderConnectionTester connectionTester = mock(ModelProviderConnectionTester.class);
        ModelProviderEntity provider = ModelProviderEntity.create(
            "OpenAI 兼容测试",
            "openai-compatible",
            "https://api.example.com/v1",
            "gpt-4o-mini",
            "active",
            Instant.parse("2026-05-15T08:00:00Z")
        );
        UUID providerId = provider.getId();
        ModelProviderTypeEntity providerType = mock(ModelProviderTypeEntity.class);

        when(modelProviderRepository.findById(providerId)).thenReturn(Optional.of(provider));
        when(modelProviderTypeRepository.findByCodeAndStatus("openai-compatible", "active")).thenReturn(Optional.of(providerType));
        when(providerType.getAuthScheme()).thenReturn("bearer");
        when(providerType.getModelListEndpoint()).thenReturn("/models");
        when(connectionTester.test(any(ModelProviderTestRequest.class))).thenReturn(new ModelProviderTestOutcome(
            "success",
            "模型供应商连接成功",
            List.of("local-model"),
            18
        ));

        SystemManagementService service = buildService(
            modelProviderRepository,
            modelProviderTypeRepository,
            mock(SystemCapabilityRepository.class),
            connectionTester
        );

        SystemManagementApi.ModelProviderTestResult result = service.testModelProvider(providerId);

        assertThat(result.status()).isEqualTo("success");
        org.mockito.ArgumentCaptor<ModelProviderTestRequest> captor = org.mockito.ArgumentCaptor.forClass(ModelProviderTestRequest.class);
        verify(connectionTester).test(captor.capture());
        assertThat(captor.getValue().apiKey()).isNull();
    }

    @Test
    void shouldDecryptApiKeyBeforeTestingModelProvider() {
        ModelProviderRepository modelProviderRepository = mock(ModelProviderRepository.class);
        ModelProviderTypeRepository modelProviderTypeRepository = mock(ModelProviderTypeRepository.class);
        ModelProviderConnectionTester connectionTester = mock(ModelProviderConnectionTester.class);
        ModelProviderEntity provider = ModelProviderEntity.create(
            "OpenAI 兼容测试",
            "openai-compatible",
            "https://api.example.com/v1",
            "gpt-4o-mini",
            "active",
            Instant.parse("2026-05-15T08:00:00Z")
        );
        provider.storeEncryptedApiKey(FIELD_ENCRYPTION.encrypt("sk-test-secret"), Instant.parse("2026-05-15T08:00:00Z"));
        UUID providerId = provider.getId();
        ModelProviderTypeEntity providerType = mock(ModelProviderTypeEntity.class);

        when(modelProviderRepository.findById(providerId)).thenReturn(Optional.of(provider));
        when(modelProviderTypeRepository.findByCodeAndStatus("openai-compatible", "active")).thenReturn(Optional.of(providerType));
        when(providerType.getAuthScheme()).thenReturn("bearer");
        when(providerType.getModelListEndpoint()).thenReturn("/models");
        when(connectionTester.test(any(ModelProviderTestRequest.class))).thenReturn(new ModelProviderTestOutcome(
            "success",
            "模型供应商连接成功",
            List.of("gpt-4o-mini"),
            32
        ));

        SystemManagementService service = buildService(
            modelProviderRepository,
            modelProviderTypeRepository,
            mock(SystemCapabilityRepository.class),
            connectionTester
        );

        SystemManagementApi.ModelProviderTestResult result = service.testModelProvider(providerId);

        assertThat(result.status()).isEqualTo("success");
        org.mockito.ArgumentCaptor<ModelProviderTestRequest> captor = org.mockito.ArgumentCaptor.forClass(ModelProviderTestRequest.class);
        verify(connectionTester).test(captor.capture());
        assertThat(captor.getValue().apiKey()).isEqualTo("sk-test-secret");
        assertThat(captor.getValue().baseUrl()).isEqualTo("https://api.example.com/v1");
        assertThat(captor.getValue().modelListEndpoint()).isEqualTo("/models");
    }

    @Test
    void shouldDeleteCapabilityWhenExists() {
        SystemCapabilityRepository systemCapabilityRepository = mock(SystemCapabilityRepository.class);
        UUID capabilityId = UUID.randomUUID();
        SystemCapabilityEntity entity = mock(SystemCapabilityEntity.class);
        when(systemCapabilityRepository.findById(capabilityId)).thenReturn(Optional.of(entity));
        when(entity.getId()).thenReturn(capabilityId);
        when(entity.getCode()).thenReturn("demo_mcp");
        when(entity.getVersion()).thenReturn("v1");

        SystemManagementService service = buildService(mock(ModelProviderRepository.class), systemCapabilityRepository);

        service.deleteCapability(capabilityId);

        verify(systemCapabilityRepository).delete(entity);
    }

    private static SystemManagementService buildService(
        ModelProviderRepository modelProviderRepository,
        SystemCapabilityRepository systemCapabilityRepository
    ) {
        return buildService(
            modelProviderRepository,
            mock(ModelProviderTypeRepository.class),
            systemCapabilityRepository,
            mock(ModelProviderConnectionTester.class)
        );
    }

    private static SystemManagementService buildService(
        ModelProviderRepository modelProviderRepository,
        ModelProviderTypeRepository modelProviderTypeRepository,
        SystemCapabilityRepository systemCapabilityRepository,
        ModelProviderConnectionTester modelProviderConnectionTester
    ) {
        return new SystemManagementService(
            mock(TenantRepository.class),
            modelProviderRepository,
            modelProviderTypeRepository,
            systemCapabilityRepository,
            mock(TenantCapabilityGrantRepository.class),
            mock(TenantModelAssignmentRepository.class),
            mock(UserAccountRepository.class),
            mock(UserRoleAssignmentRepository.class),
            mock(RoleRepository.class),
            mock(DepartmentRepository.class),
            mock(UserMembershipRepository.class),
            mock(UserMembershipRoleRepository.class),
            mock(PasswordEncoder.class),
            FIELD_ENCRYPTION,
            modelProviderConnectionTester,
            Clock.fixed(Instant.parse("2026-05-15T08:00:00Z"), ZoneOffset.UTC)
        );
    }
}
