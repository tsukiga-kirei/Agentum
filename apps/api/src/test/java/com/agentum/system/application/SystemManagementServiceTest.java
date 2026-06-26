package com.agentum.system.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentum.delivery.application.EmailDeliveryConnectionTester;
import com.agentum.auth.domain.UserAccount;
import com.agentum.auth.domain.UserRoleAssignmentEntity;
import com.agentum.auth.infrastructure.TenantSsoProviderRepository;
import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.auth.infrastructure.UserRoleAssignmentRepository;
import com.agentum.organization.domain.UserMembershipEntity;
import com.agentum.organization.domain.UserMembershipRoleEntity;
import com.agentum.organization.infrastructure.DepartmentRepository;
import com.agentum.organization.infrastructure.UserMembershipRepository;
import com.agentum.organization.infrastructure.UserMembershipRoleRepository;
import com.agentum.permission.domain.RoleEntity;
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
import com.agentum.tenant.domain.TenantEntity;
import com.agentum.tenant.infrastructure.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class SystemManagementServiceTest {

    private static final FieldEncryptionService FIELD_ENCRYPTION = new FieldEncryptionService("test-master-key-with-enough-length");

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

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
            mock(TenantSsoProviderRepository.class),
            userAccountRepository,
            userRoleAssignmentRepository,
            roleRepository,
            departmentRepository,
            userMembershipRepository,
            userMembershipRoleRepository,
            passwordEncoder,
            FIELD_ENCRYPTION,
            mock(ModelProviderConnectionTester.class),
            mock(McpSseConnectionTester.class),
            mock(McpConnectionTester.class),
            mock(SkillManifestProbe.class),
            mock(EmailDeliveryConnectionTester.class),
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
    void shouldCreateAdditionalTenantAdminFromSystemManagement() {
        TenantRepository tenantRepository = mock(TenantRepository.class);
        UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
        UserRoleAssignmentRepository userRoleAssignmentRepository = mock(UserRoleAssignmentRepository.class);
        RoleRepository roleRepository = mock(RoleRepository.class);
        UserMembershipRepository userMembershipRepository = mock(UserMembershipRepository.class);
        UserMembershipRoleRepository userMembershipRoleRepository = mock(UserMembershipRoleRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        RoleEntity tenantAdminRole = RoleEntity.create(TENANT_ID, "tenant_admin", "租户管理员", "管理租户");
        List<UserRoleAssignmentEntity> savedAssignments = new ArrayList<>();
        List<UserMembershipRoleEntity> savedLinks = new ArrayList<>();

        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("演示租户", "demo", Instant.now())));
        when(roleRepository.findByTenantIdAndCodeAndStatus(TENANT_ID, "tenant_admin", "active")).thenReturn(Optional.of(tenantAdminRole));
        when(userAccountRepository.existsByUsername("new_tenant_admin")).thenReturn(false);
        when(passwordEncoder.encode("change-me-123")).thenReturn("encoded-password");
        when(userRoleAssignmentRepository.save(any(UserRoleAssignmentEntity.class))).thenAnswer(invocation -> {
            UserRoleAssignmentEntity assignment = invocation.getArgument(0);
            savedAssignments.add(assignment);
            return assignment;
        });
        when(userMembershipRoleRepository.save(any(UserMembershipRoleEntity.class))).thenAnswer(invocation -> {
            UserMembershipRoleEntity link = invocation.getArgument(0);
            savedLinks.add(link);
            return link;
        });

        SystemManagementService service = buildService(
            tenantRepository,
            mock(ModelProviderRepository.class),
            mock(ModelProviderTypeRepository.class),
            mock(SystemCapabilityRepository.class),
            mock(TenantCapabilityGrantRepository.class),
            mock(TenantModelAssignmentRepository.class),
            userAccountRepository,
            userRoleAssignmentRepository,
            roleRepository,
            mock(DepartmentRepository.class),
            userMembershipRepository,
            userMembershipRoleRepository,
            passwordEncoder
        );

        service.createTenantAdmin(TENANT_ID, new SystemManagementApi.CreateTenantAdminRequest(
            "new_tenant_admin",
            "新增管理员",
            "change-me-123",
            "admin@example.com",
            null
        ));

        assertThat(savedLinks).hasSize(1);
        assertThat(savedLinks.getFirst().getRoleId()).isEqualTo(tenantAdminRole.getId());
        assertThat(savedAssignments)
            .extracting(UserRoleAssignmentEntity::getRole)
            .containsExactlyInAnyOrder("tenant_admin", "business");
    }

    @Test
    void shouldRejectInvalidTenantAdminUsernameFromSystemManagement() {
        TenantRepository tenantRepository = mock(TenantRepository.class);
        RoleRepository roleRepository = mock(RoleRepository.class);
        RoleEntity tenantAdminRole = RoleEntity.create(TENANT_ID, "tenant_admin", "租户管理员", "管理租户");

        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("演示租户", "demo", Instant.now())));
        when(roleRepository.findByTenantIdAndCodeAndStatus(TENANT_ID, "tenant_admin", "active")).thenReturn(Optional.of(tenantAdminRole));

        SystemManagementService service = buildService(
            tenantRepository,
            mock(ModelProviderRepository.class),
            mock(ModelProviderTypeRepository.class),
            mock(SystemCapabilityRepository.class),
            mock(TenantCapabilityGrantRepository.class),
            mock(TenantModelAssignmentRepository.class),
            mock(UserAccountRepository.class),
            mock(UserRoleAssignmentRepository.class),
            roleRepository,
            mock(DepartmentRepository.class),
            mock(UserMembershipRepository.class),
            mock(UserMembershipRoleRepository.class),
            mock(PasswordEncoder.class)
        );

        assertThatThrownBy(() -> service.createTenantAdmin(TENANT_ID, new SystemManagementApi.CreateTenantAdminRequest(
            "管理员",
            "新增管理员",
            "change-me-123",
            "admin@example.com",
            null
        )))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("SYSTEM_TENANT_ADMIN_USERNAME_INVALID");
    }

    @Test
    void shouldRejectInvalidTenantAdminUsernameWhenUpdatingProfile() {
        TenantRepository tenantRepository = mock(TenantRepository.class);
        UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
        UserMembershipRepository userMembershipRepository = mock(UserMembershipRepository.class);
        UserMembershipRoleRepository userMembershipRoleRepository = mock(UserMembershipRoleRepository.class);
        RoleRepository roleRepository = mock(RoleRepository.class);
        RoleEntity tenantAdminRole = RoleEntity.create(TENANT_ID, "tenant_admin", "租户管理员", "管理租户");
        UserMembershipEntity adminMembership = UserMembershipEntity.create(TENANT_ID, UUID.randomUUID(), null);
        UserAccount account = UserAccount.create("tenantadmin", "hash", "租户管理员", "admin@example.com");

        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("演示租户", "demo", Instant.now())));
        when(roleRepository.findByTenantIdAndCodeAndStatus(TENANT_ID, "tenant_admin", "active")).thenReturn(Optional.of(tenantAdminRole));
        when(userMembershipRepository.findByIdAndTenantId(adminMembership.getId(), TENANT_ID)).thenReturn(Optional.of(adminMembership));
        when(userMembershipRoleRepository.existsByMembershipIdAndRoleIdAndStatus(adminMembership.getId(), tenantAdminRole.getId(), "active")).thenReturn(true);
        when(userAccountRepository.findById(adminMembership.getUserId())).thenReturn(Optional.of(account));

        SystemManagementService service = buildService(
            tenantRepository,
            mock(ModelProviderRepository.class),
            mock(ModelProviderTypeRepository.class),
            mock(SystemCapabilityRepository.class),
            mock(TenantCapabilityGrantRepository.class),
            mock(TenantModelAssignmentRepository.class),
            userAccountRepository,
            mock(UserRoleAssignmentRepository.class),
            roleRepository,
            mock(DepartmentRepository.class),
            userMembershipRepository,
            userMembershipRoleRepository,
            mock(PasswordEncoder.class)
        );

        assertThatThrownBy(() -> service.updateTenantAdminProfile(
            TENANT_ID,
            adminMembership.getId(),
            new SystemManagementApi.UpdateTenantAdminProfileRequest("1tenantadmin", "租户管理员", "admin@example.com")
        ))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("SYSTEM_TENANT_ADMIN_USERNAME_INVALID");
    }

    @Test
    void shouldRejectDisablingLastTenantAdminFromSystemManagement() {
        TenantRepository tenantRepository = mock(TenantRepository.class);
        UserMembershipRepository userMembershipRepository = mock(UserMembershipRepository.class);
        UserMembershipRoleRepository userMembershipRoleRepository = mock(UserMembershipRoleRepository.class);
        RoleRepository roleRepository = mock(RoleRepository.class);
        RoleEntity tenantAdminRole = RoleEntity.create(TENANT_ID, "tenant_admin", "租户管理员", "管理租户");
        UserMembershipEntity onlyAdmin = UserMembershipEntity.create(TENANT_ID, UUID.randomUUID(), null);

        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("演示租户", "demo", Instant.now())));
        when(userMembershipRepository.findByIdAndTenantId(onlyAdmin.getId(), TENANT_ID)).thenReturn(Optional.of(onlyAdmin));
        when(roleRepository.findByTenantIdAndCodeAndStatus(TENANT_ID, "tenant_admin", "active")).thenReturn(Optional.of(tenantAdminRole));
        when(userMembershipRoleRepository.existsByMembershipIdAndRoleIdAndStatus(onlyAdmin.getId(), tenantAdminRole.getId(), "active")).thenReturn(true);
        when(userMembershipRoleRepository.countActiveMembershipsByTenantIdAndRoleId(TENANT_ID, tenantAdminRole.getId(), "active", "active")).thenReturn(1L);

        SystemManagementService service = buildService(
            tenantRepository,
            mock(ModelProviderRepository.class),
            mock(ModelProviderTypeRepository.class),
            mock(SystemCapabilityRepository.class),
            mock(TenantCapabilityGrantRepository.class),
            mock(TenantModelAssignmentRepository.class),
            mock(UserAccountRepository.class),
            mock(UserRoleAssignmentRepository.class),
            roleRepository,
            mock(DepartmentRepository.class),
            userMembershipRepository,
            userMembershipRoleRepository,
            mock(PasswordEncoder.class)
        );

        assertThatThrownBy(() -> service.updateTenantAdminStatus(
            TENANT_ID,
            onlyAdmin.getId(),
            new SystemManagementApi.UpdateTenantAdminStatusRequest("disabled")
        ))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("SYSTEM_TENANT_ADMIN_REQUIRED");
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
    void shouldRejectDeleteModelProviderWhenEnabledByTenant() {
        ModelProviderRepository modelProviderRepository = mock(ModelProviderRepository.class);
        TenantModelAssignmentRepository tenantModelAssignmentRepository = mock(TenantModelAssignmentRepository.class);
        UUID providerId = UUID.randomUUID();
        ModelProviderEntity entity = mock(ModelProviderEntity.class);
        when(modelProviderRepository.findById(providerId)).thenReturn(Optional.of(entity));
        when(entity.getId()).thenReturn(providerId);
        when(tenantModelAssignmentRepository.existsByProviderIdAndStatus(providerId, "enabled")).thenReturn(true);

        SystemManagementService service = buildService(
            modelProviderRepository,
            mock(ModelProviderTypeRepository.class),
            mock(SystemCapabilityRepository.class),
            mock(TenantCapabilityGrantRepository.class),
            tenantModelAssignmentRepository,
            mock(ModelProviderConnectionTester.class)
        );

        assertThatThrownBy(() -> service.deleteModelProvider(providerId))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("已被租户启用");
    }

    @Test
    void shouldRejectDraftModelProviderWhenEnabledByTenant() {
        ModelProviderRepository modelProviderRepository = mock(ModelProviderRepository.class);
        ModelProviderTypeRepository modelProviderTypeRepository = mock(ModelProviderTypeRepository.class);
        TenantModelAssignmentRepository tenantModelAssignmentRepository = mock(TenantModelAssignmentRepository.class);
        UUID providerId = UUID.randomUUID();
        ModelProviderEntity entity = ModelProviderEntity.create(
            "OpenAI 兼容测试",
            "openai-compatible",
            "https://api.example.com/v1",
            "gpt-4o-mini",
            false,
            "active",
            Instant.parse("2026-05-15T08:00:00Z")
        );
        ModelProviderTypeEntity providerType = mock(ModelProviderTypeEntity.class);
        when(providerType.getCode()).thenReturn("openai-compatible");
        when(providerType.getDefaultBaseUrl()).thenReturn("https://api.example.com/v1");
        when(modelProviderRepository.findById(providerId)).thenReturn(Optional.of(entity));
        when(modelProviderTypeRepository.findByCodeAndStatus("openai-compatible", "active")).thenReturn(Optional.of(providerType));
        when(tenantModelAssignmentRepository.existsByProviderIdAndStatus(providerId, "enabled")).thenReturn(true);

        SystemManagementService service = buildService(
            modelProviderRepository,
            modelProviderTypeRepository,
            mock(SystemCapabilityRepository.class),
            mock(TenantCapabilityGrantRepository.class),
            tenantModelAssignmentRepository,
            mock(ModelProviderConnectionTester.class)
        );

        assertThatThrownBy(() -> service.updateModelProvider(providerId, new SystemManagementApi.UpdateModelProviderRequest(
            "OpenAI 兼容测试",
            "openai-compatible",
            "https://api.example.com/v1",
            "gpt-4o-mini",
            null,
            "draft",
            8192,
            false
        )))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("已被租户启用");
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
            "active",
            8192,
            true
        ));

        assertThat(savedProviders).hasSize(1);
        ModelProviderEntity provider = savedProviders.getFirst();
        assertThat(provider.getSettings().get("maxTokens")).isEqualTo(8192);
        assertThat(provider.isReasoningModel()).isTrue();
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
            false,
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
        assertThat(result.connectivityStatus()).isEqualTo("online");
        assertThat(provider.getConnectivityStatus()).isEqualTo("online");
        verify(modelProviderRepository).save(provider);
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
            false,
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
        assertThat(result.connectivityStatus()).isEqualTo("online");
        verify(modelProviderRepository).save(provider);
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

    @Test
    void shouldRejectDeleteCapabilityWhenEnabledByTenant() {
        SystemCapabilityRepository systemCapabilityRepository = mock(SystemCapabilityRepository.class);
        TenantCapabilityGrantRepository tenantCapabilityGrantRepository = mock(TenantCapabilityGrantRepository.class);
        UUID capabilityId = UUID.randomUUID();
        SystemCapabilityEntity entity = mock(SystemCapabilityEntity.class);
        when(systemCapabilityRepository.findById(capabilityId)).thenReturn(Optional.of(entity));
        when(entity.getId()).thenReturn(capabilityId);
        when(tenantCapabilityGrantRepository.existsByCapabilityIdAndStatus(capabilityId, "enabled")).thenReturn(true);

        SystemManagementService service = buildService(
            mock(ModelProviderRepository.class),
            mock(ModelProviderTypeRepository.class),
            systemCapabilityRepository,
            tenantCapabilityGrantRepository,
            mock(TenantModelAssignmentRepository.class),
            mock(ModelProviderConnectionTester.class)
        );

        assertThatThrownBy(() -> service.deleteCapability(capabilityId))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("已被租户启用");
    }

    @Test
    void shouldRejectDraftCapabilityWhenEnabledByTenant() {
        SystemCapabilityRepository systemCapabilityRepository = mock(SystemCapabilityRepository.class);
        TenantCapabilityGrantRepository tenantCapabilityGrantRepository = mock(TenantCapabilityGrantRepository.class);
        SystemCapabilityEntity entity = SystemCapabilityEntity.create(
            "mcp",
            "文件读取 MCP",
            "file_read_mcp",
            "v1",
            "",
            "medium",
            "active",
            Map.of("transport", "stdio", "command", "node server.js"),
            Instant.parse("2026-05-15T08:00:00Z")
        );
        UUID capabilityId = entity.getId();
        when(systemCapabilityRepository.findById(capabilityId)).thenReturn(Optional.of(entity));
        when(tenantCapabilityGrantRepository.existsByCapabilityIdAndStatus(capabilityId, "enabled")).thenReturn(true);

        SystemManagementService service = buildService(
            mock(ModelProviderRepository.class),
            mock(ModelProviderTypeRepository.class),
            systemCapabilityRepository,
            tenantCapabilityGrantRepository,
            mock(TenantModelAssignmentRepository.class),
            mock(ModelProviderConnectionTester.class)
        );

        assertThatThrownBy(() -> service.updateCapability(capabilityId, new SystemManagementApi.UpdateCapabilityRequest(
            "mcp",
            "文件读取 MCP",
            "v1",
            "",
            "medium",
            "draft",
            Map.of("transport", "stdio", "command", "node server.js")
        )))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("已被租户启用");
    }

    @Test
    void shouldKeepCapabilityConnectivityWhenOnlyGovernanceStatusChanges() {
        SystemCapabilityRepository systemCapabilityRepository = mock(SystemCapabilityRepository.class);
        SystemCapabilityEntity entity = SystemCapabilityEntity.create(
            "mcp",
            "文件读取 MCP",
            "file_read_mcp",
            "v1",
            "",
            "medium",
            "draft",
            Map.of("transport", "sse", "sseUrl", "http://localhost:18080/sse"),
            Instant.parse("2026-05-15T08:00:00Z")
        );
        UUID capabilityId = entity.getId();
        entity.recordConnectivityCheck("online", Instant.parse("2026-05-15T08:10:00Z"), Instant.parse("2026-05-15T08:10:00Z"));
        when(systemCapabilityRepository.findById(capabilityId)).thenReturn(Optional.of(entity));
        when(systemCapabilityRepository.findByCodeAndVersion(any(), any())).thenReturn(Optional.of(entity));

        SystemManagementService service = buildService(mock(ModelProviderRepository.class), systemCapabilityRepository);

        SystemManagementApi.CapabilityRow row = service.updateCapability(capabilityId, new SystemManagementApi.UpdateCapabilityRequest(
            "mcp",
            "文件读取 MCP",
            "v1",
            "",
            "medium",
            "active",
            Map.of("transport", "sse", "sseUrl", "http://localhost:18080/sse")
        ));

        assertThat(row.connectivityStatus()).isEqualTo("online");
        assertThat(entity.getConnectivityStatus()).isEqualTo("online");
        assertThat(entity.getConnectivityCheckedAt()).isEqualTo(Instant.parse("2026-05-15T08:10:00Z"));
    }

    @Test
    void shouldMarkCapabilityConnectivityStaleWhenRuntimeConfigChanges() {
        SystemCapabilityRepository systemCapabilityRepository = mock(SystemCapabilityRepository.class);
        SystemCapabilityEntity entity = SystemCapabilityEntity.create(
            "mcp",
            "文件读取 MCP",
            "file_read_mcp",
            "v1",
            "",
            "medium",
            "active",
            Map.of("transport", "sse", "sseUrl", "http://localhost:18080/sse"),
            Instant.parse("2026-05-15T08:00:00Z")
        );
        UUID capabilityId = entity.getId();
        entity.recordConnectivityCheck("online", Instant.parse("2026-05-15T08:10:00Z"), Instant.parse("2026-05-15T08:10:00Z"));
        when(systemCapabilityRepository.findById(capabilityId)).thenReturn(Optional.of(entity));
        when(systemCapabilityRepository.findByCodeAndVersion(any(), any())).thenReturn(Optional.of(entity));

        SystemManagementService service = buildService(mock(ModelProviderRepository.class), systemCapabilityRepository);

        SystemManagementApi.CapabilityRow row = service.updateCapability(capabilityId, new SystemManagementApi.UpdateCapabilityRequest(
            "mcp",
            "文件读取 MCP",
            "v1",
            "",
            "medium",
            "active",
            Map.of("transport", "sse", "sseUrl", "http://localhost:18081/sse")
        ));

        assertThat(row.connectivityStatus()).isEqualTo("stale");
        assertThat(entity.getConnectivityStatus()).isEqualTo("stale");
        assertThat(entity.getConnectivityCheckedAt()).isNull();
    }

    @Test
    void shouldPersistMcpCapabilityWithSseTransportOnly() {
        SystemCapabilityRepository systemCapabilityRepository = mock(SystemCapabilityRepository.class);
        List<SystemCapabilityEntity> savedCapabilities = new ArrayList<>();
        when(systemCapabilityRepository.findByCodeAndVersion(any(), any())).thenReturn(Optional.empty());
        when(systemCapabilityRepository.save(any(SystemCapabilityEntity.class))).thenAnswer(invocation -> {
            SystemCapabilityEntity entity = invocation.getArgument(0);
            savedCapabilities.add(entity);
            return entity;
        });
        SystemManagementService service = buildService(mock(ModelProviderRepository.class), systemCapabilityRepository);

        SystemManagementApi.CapabilityRow row = service.createCapability(new SystemManagementApi.CreateCapabilityRequest(
            "mcp",
            "连通性 MCP",
            null,
            "v1",
            "只通过 SSE 接入的测试 MCP",
            "medium",
            "active",
            Map.of(
                "transport", "stdio",
                "command", "node server.js",
                "sseUrl", "http://localhost:18080/sse"
            )
        ));

        assertThat(savedCapabilities).hasSize(1);
        assertThat(savedCapabilities.getFirst().getConfig())
            .containsEntry("transport", "sse")
            .containsEntry("sseUrl", "http://localhost:18080/sse")
            .doesNotContainKeys("command", "args", "workingDir");
        assertThat(row.config())
            .containsEntry("transport", "sse")
            .containsEntry("sseUrl", "http://localhost:18080/sse")
            .doesNotContainKeys("command", "args", "workingDir");
    }

    @Test
    void shouldRejectMcpCapabilityWithoutSseUrl() {
        SystemManagementService service = buildService(mock(ModelProviderRepository.class), mock(SystemCapabilityRepository.class));

        assertThatThrownBy(() -> service.createCapability(new SystemManagementApi.CreateCapabilityRequest(
            "mcp",
            "缺少地址的 MCP",
            null,
            "v1",
            "",
            "medium",
            "active",
            Map.of()
        )))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("SSE 地址不能为空");
    }

    @Test
    void shouldPersistMcpCapabilityWithStreamableHttp() {
        SystemCapabilityRepository systemCapabilityRepository = mock(SystemCapabilityRepository.class);
        List<SystemCapabilityEntity> savedCapabilities = new ArrayList<>();
        when(systemCapabilityRepository.findByCodeAndVersion(any(), any())).thenReturn(Optional.empty());
        when(systemCapabilityRepository.save(any(SystemCapabilityEntity.class))).thenAnswer(invocation -> {
            SystemCapabilityEntity entity = invocation.getArgument(0);
            savedCapabilities.add(entity);
            return entity;
        });
        SystemManagementService service = buildService(mock(ModelProviderRepository.class), systemCapabilityRepository);

        SystemManagementApi.CapabilityRow row = service.createCapability(new SystemManagementApi.CreateCapabilityRequest(
            "mcp",
            "端点 MCP",
            null,
            "v1",
            "Streamable HTTP 接入的测试 MCP",
            "medium",
            "active",
            Map.of(
                "transport", "streamable_http",
                "endpointUrl", "http://localhost:18080/mcp"
            )
        ));

        assertThat(savedCapabilities).hasSize(1);
        assertThat(savedCapabilities.getFirst().getConfig())
            .containsEntry("transport", "streamable_http")
            .containsEntry("endpointUrl", "http://localhost:18080/mcp");
        assertThat(row.config())
            .containsEntry("transport", "streamable_http")
            .containsEntry("endpointUrl", "http://localhost:18080/mcp");
    }

    @Test
    void shouldPersistDiscoveredMcpToolsAfterSuccessfulConnectionTest() {
        SystemCapabilityRepository systemCapabilityRepository = mock(SystemCapabilityRepository.class);
        McpConnectionTester mcpConnectionTester = mock(McpConnectionTester.class);
        SystemCapabilityEntity capability = SystemCapabilityEntity.create(
            "mcp",
            "金融月报",
            "cap_financial_report",
            "v1",
            "金融业务工作报告 MCP",
            "medium",
            "active",
            Map.of(
                "transport", "streamable_http",
                "endpointUrl", "http://127.0.0.1:3001/mcp"
            ),
            Instant.parse("2026-05-15T08:00:00Z")
        );
        Map<String, Object> inputSchema = Map.of(
            "type", "object",
            "properties", Map.of("pdt", Map.of("type", "string")),
            "required", List.of("pdt")
        );
        when(systemCapabilityRepository.findById(capability.getId())).thenReturn(Optional.of(capability));
        when(mcpConnectionTester.test(any())).thenReturn(new McpConnectionTestOutcome(
            "success",
            "已发现 1 个工具",
            List.of(new McpConnectionTestOutcome.McpToolDescriptor(
                "get_financial_work_report_core_kpi",
                "获取核心经营指标",
                inputSchema
            ))
        ));
        SystemManagementService service = buildService(systemCapabilityRepository, mcpConnectionTester);

        SystemManagementApi.CapabilityTestResult result = service.testCapability(capability.getId());

        assertThat(result.status()).isEqualTo("success");
        assertThat(capability.getConfig().get("tools")).isEqualTo(List.of(Map.of(
            "name", "get_financial_work_report_core_kpi",
            "description", "获取核心经营指标",
            "inputSchema", inputSchema
        )));
        verify(systemCapabilityRepository).save(capability);
    }

    @Test
    void shouldRejectMcpCapabilityWithoutEndpointUrl() {
        SystemManagementService service = buildService(mock(ModelProviderRepository.class), mock(SystemCapabilityRepository.class));

        assertThatThrownBy(() -> service.createCapability(new SystemManagementApi.CreateCapabilityRequest(
            "mcp",
            "缺少端点地址的 MCP",
            null,
            "v1",
            "",
            "medium",
            "active",
            Map.of(
                "transport", "streamable_http"
            )
        )))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("MCP HTTP 端点地址不能为空");
    }

    @Test
    void shouldEncryptEmailDeliveryPasswordAndHideSecretFromResponse() {
        SystemCapabilityRepository systemCapabilityRepository = mock(SystemCapabilityRepository.class);
        List<SystemCapabilityEntity> savedCapabilities = new ArrayList<>();
        when(systemCapabilityRepository.findByCodeAndVersion(any(), any())).thenReturn(Optional.empty());
        when(systemCapabilityRepository.save(any(SystemCapabilityEntity.class))).thenAnswer(invocation -> {
            SystemCapabilityEntity entity = invocation.getArgument(0);
            savedCapabilities.add(entity);
            return entity;
        });
        SystemManagementService service = buildService(mock(ModelProviderRepository.class), systemCapabilityRepository);

        SystemManagementApi.CapabilityRow row = service.createCapability(new SystemManagementApi.CreateCapabilityRequest(
            "delivery",
            "本地邮箱交付",
            null,
            "v1",
            "通过 Mailpit 验证邮箱交付",
            "high",
            "active",
            Map.of(
                "deliveryChannel", "email",
                "smtpHost", "localhost",
                "smtpPort", "1025",
                "smtpUsername", "mailpit-user",
                "smtpPassword", "smtp-secret",
                "fromAddress", "agentum@example.test",
                "useTls", "false"
            )
        ));

        assertThat(savedCapabilities).hasSize(1);
        Map<String, Object> storedConfig = savedCapabilities.getFirst().getConfig();
        assertThat(storedConfig)
            .containsEntry("sourceType", "builtin")
            .containsEntry("deliveryChannel", "email")
            .containsEntry("smtpHost", "localhost")
            .containsEntry("smtpPort", 1025)
            .containsEntry("smtpUsername", "mailpit-user")
            .containsEntry("fromAddress", "agentum@example.test")
            .containsEntry("useTls", false);
        assertThat(storedConfig.get("encryptedSmtpPassword").toString()).doesNotContain("smtp-secret");
        assertThat(FIELD_ENCRYPTION.decrypt(storedConfig.get("encryptedSmtpPassword").toString())).isEqualTo("smtp-secret");
        assertThat(row.config())
            .containsEntry("sourceType", "builtin")
            .containsEntry("deliveryChannel", "email")
            .containsEntry("smtpPasswordConfigured", true)
            .doesNotContainKeys("smtpPassword", "encryptedSmtpPassword");
    }

    @Test
    void shouldPersistBuiltinWordDocumentDeliveryConfig() {
        SystemCapabilityRepository systemCapabilityRepository = mock(SystemCapabilityRepository.class);
        List<SystemCapabilityEntity> savedCapabilities = new ArrayList<>();
        when(systemCapabilityRepository.findByCodeAndVersion(any(), any())).thenReturn(Optional.empty());
        when(systemCapabilityRepository.save(any(SystemCapabilityEntity.class))).thenAnswer(invocation -> {
            SystemCapabilityEntity entity = invocation.getArgument(0);
            savedCapabilities.add(entity);
            return entity;
        });
        SystemManagementService service = buildService(mock(ModelProviderRepository.class), systemCapabilityRepository);

        SystemManagementApi.CapabilityRow row = service.createCapability(new SystemManagementApi.CreateCapabilityRequest(
            "delivery",
            "Word 文档交付",
            null,
            "v1",
            "将 AI Markdown 输出转换为 docx 文件",
            "medium",
            "active",
            Map.of(
                "sourceType", "builtin",
                "deliveryChannel", "document",
                "documentKind", "word",
                "defaultStyle", Map.of(
                    "chineseFont", "黑体",
                    "latinFont", "Arial",
                    "bodyFontSize", "四号",
                    "heading1FontSize", "三号",
                    "lineSpacing", "1.25",
                    "firstLineIndentChars", "2",
                    "titleCentered", "true"
                ),
                "allowNodeStyleOverride", true,
                "maxFileSizeMb", "30",
                "retentionDays", "365"
            )
        ));

        assertThat(savedCapabilities).hasSize(1);
        Map<String, Object> storedConfig = savedCapabilities.getFirst().getConfig();
        assertThat(storedConfig)
            .containsEntry("sourceType", "builtin")
            .containsEntry("deliveryChannel", "document")
            .containsEntry("documentKind", "word")
            .containsEntry("allowNodeStyleOverride", true)
            .containsEntry("maxFileSizeMb", 30)
            .containsEntry("retentionDays", 365)
            .doesNotContainKeys("smtpHost", "smtpPort", "encryptedSmtpPassword");
        assertThat(storedConfig.get("defaultStyle"))
            .isInstanceOf(Map.class)
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("chineseFont", "黑体")
            .containsEntry("latinFont", "Arial")
            .containsEntry("bodyFontSize", 14)
            .containsEntry("heading1FontSize", 16)
            .containsEntry("lineSpacing", 1.25)
            .containsEntry("titleCentered", true);
        assertThat(row.config()).containsEntry("deliveryChannel", "document");
    }

    @Test
    void shouldPersistCustomDeliveryAdapterProtocolConfig() {
        SystemCapabilityRepository systemCapabilityRepository = mock(SystemCapabilityRepository.class);
        List<SystemCapabilityEntity> savedCapabilities = new ArrayList<>();
        when(systemCapabilityRepository.findByCodeAndVersion(any(), any())).thenReturn(Optional.empty());
        when(systemCapabilityRepository.save(any(SystemCapabilityEntity.class))).thenAnswer(invocation -> {
            SystemCapabilityEntity entity = invocation.getArgument(0);
            savedCapabilities.add(entity);
            return entity;
        });
        SystemManagementService service = buildService(mock(ModelProviderRepository.class), systemCapabilityRepository);

        SystemManagementApi.CapabilityRow row = service.createCapability(new SystemManagementApi.CreateCapabilityRequest(
            "delivery",
            "OA 自定义交付",
            null,
            "v1",
            "通过外部适配器创建 OA 流程",
            "high",
            "active",
            Map.of(
                "sourceType", "custom",
                "implementationKey", "custom-oa-delivery",
                "manifestPath", "capabilities/delivery/custom-oa-delivery/manifest.yaml",
                "protocol", "http",
                "endpointUrl", "http://localhost:19090/delivery"
            )
        ));

        assertThat(savedCapabilities).hasSize(1);
        assertThat(savedCapabilities.getFirst().getConfig())
            .containsEntry("sourceType", "custom")
            .containsEntry("implementationKey", "custom-oa-delivery")
            .containsEntry("manifestPath", "capabilities/delivery/custom-oa-delivery/manifest.yaml")
            .containsEntry("protocol", "http")
            .containsEntry("endpointUrl", "http://localhost:19090/delivery")
            .doesNotContainKeys("smtpHost", "smtpPort", "encryptedSmtpPassword");
        assertThat(row.config()).containsEntry("sourceType", "custom");
    }

    @Test
    void shouldRejectEnableDraftModelProviderForTenant() {
        TenantRepository tenantRepository = mock(TenantRepository.class);
        ModelProviderRepository modelProviderRepository = mock(ModelProviderRepository.class);
        TenantModelAssignmentRepository tenantModelAssignmentRepository = mock(TenantModelAssignmentRepository.class);
        UUID tenantId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        TenantEntity tenant = mock(TenantEntity.class);
        ModelProviderEntity provider = mock(ModelProviderEntity.class);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(tenant.getId()).thenReturn(tenantId);
        when(modelProviderRepository.findById(providerId)).thenReturn(Optional.of(provider));
        when(provider.getId()).thenReturn(providerId);
        when(provider.getStatus()).thenReturn("draft");

        SystemManagementService service = buildService(
            tenantRepository,
            modelProviderRepository,
            mock(ModelProviderTypeRepository.class),
            mock(SystemCapabilityRepository.class),
            mock(TenantCapabilityGrantRepository.class),
            tenantModelAssignmentRepository,
            mock(ModelProviderConnectionTester.class)
        );

        assertThatThrownBy(() -> service.createTenantModelAssignment(new SystemManagementApi.CreateTenantModelAssignmentRequest(
            tenantId,
            providerId,
            null,
            "enabled"
        )))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("草稿");
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
        SystemCapabilityRepository systemCapabilityRepository,
        McpConnectionTester mcpConnectionTester
    ) {
        return new SystemManagementService(
            mock(TenantRepository.class),
            mock(ModelProviderRepository.class),
            mock(ModelProviderTypeRepository.class),
            systemCapabilityRepository,
            mock(TenantCapabilityGrantRepository.class),
            mock(TenantModelAssignmentRepository.class),
            mock(TenantSsoProviderRepository.class),
            mock(UserAccountRepository.class),
            mock(UserRoleAssignmentRepository.class),
            mock(RoleRepository.class),
            mock(DepartmentRepository.class),
            mock(UserMembershipRepository.class),
            mock(UserMembershipRoleRepository.class),
            mock(PasswordEncoder.class),
            FIELD_ENCRYPTION,
            mock(ModelProviderConnectionTester.class),
            mock(McpSseConnectionTester.class),
            mcpConnectionTester,
            mock(SkillManifestProbe.class),
            mock(EmailDeliveryConnectionTester.class),
            Clock.fixed(Instant.parse("2026-05-15T08:00:00Z"), ZoneOffset.UTC)
        );
    }

    private static SystemManagementService buildService(
        ModelProviderRepository modelProviderRepository,
        ModelProviderTypeRepository modelProviderTypeRepository,
        SystemCapabilityRepository systemCapabilityRepository,
        ModelProviderConnectionTester modelProviderConnectionTester
    ) {
        return buildService(
            mock(TenantRepository.class),
            modelProviderRepository,
            modelProviderTypeRepository,
            systemCapabilityRepository,
            mock(TenantCapabilityGrantRepository.class),
            mock(TenantModelAssignmentRepository.class),
            modelProviderConnectionTester,
            mock(McpSseConnectionTester.class),
            mock(SkillManifestProbe.class),
            mock(EmailDeliveryConnectionTester.class)
        );
    }

    private static SystemManagementService buildService(
        ModelProviderRepository modelProviderRepository,
        ModelProviderTypeRepository modelProviderTypeRepository,
        SystemCapabilityRepository systemCapabilityRepository,
        TenantCapabilityGrantRepository tenantCapabilityGrantRepository,
        TenantModelAssignmentRepository tenantModelAssignmentRepository,
        ModelProviderConnectionTester modelProviderConnectionTester
    ) {
        return buildService(
            mock(TenantRepository.class),
            modelProviderRepository,
            modelProviderTypeRepository,
            systemCapabilityRepository,
            tenantCapabilityGrantRepository,
            tenantModelAssignmentRepository,
            modelProviderConnectionTester,
            mock(McpSseConnectionTester.class),
            mock(SkillManifestProbe.class),
            mock(EmailDeliveryConnectionTester.class)
        );
    }

    private static SystemManagementService buildService(
        TenantRepository tenantRepository,
        ModelProviderRepository modelProviderRepository,
        ModelProviderTypeRepository modelProviderTypeRepository,
        SystemCapabilityRepository systemCapabilityRepository,
        TenantCapabilityGrantRepository tenantCapabilityGrantRepository,
        TenantModelAssignmentRepository tenantModelAssignmentRepository,
        ModelProviderConnectionTester modelProviderConnectionTester
    ) {
        return buildService(
            tenantRepository,
            modelProviderRepository,
            modelProviderTypeRepository,
            systemCapabilityRepository,
            tenantCapabilityGrantRepository,
            tenantModelAssignmentRepository,
            modelProviderConnectionTester,
            mock(McpSseConnectionTester.class),
            mock(SkillManifestProbe.class),
            mock(EmailDeliveryConnectionTester.class)
        );
    }

    private static SystemManagementService buildService(
        TenantRepository tenantRepository,
        ModelProviderRepository modelProviderRepository,
        ModelProviderTypeRepository modelProviderTypeRepository,
        SystemCapabilityRepository systemCapabilityRepository,
        TenantCapabilityGrantRepository tenantCapabilityGrantRepository,
        TenantModelAssignmentRepository tenantModelAssignmentRepository,
        UserAccountRepository userAccountRepository,
        UserRoleAssignmentRepository userRoleAssignmentRepository,
        RoleRepository roleRepository,
        DepartmentRepository departmentRepository,
        UserMembershipRepository userMembershipRepository,
        UserMembershipRoleRepository userMembershipRoleRepository,
        PasswordEncoder passwordEncoder
    ) {
        return new SystemManagementService(
            tenantRepository,
            modelProviderRepository,
            modelProviderTypeRepository,
            systemCapabilityRepository,
            tenantCapabilityGrantRepository,
            tenantModelAssignmentRepository,
            mock(TenantSsoProviderRepository.class),
            userAccountRepository,
            userRoleAssignmentRepository,
            roleRepository,
            departmentRepository,
            userMembershipRepository,
            userMembershipRoleRepository,
            passwordEncoder,
            FIELD_ENCRYPTION,
            mock(ModelProviderConnectionTester.class),
            mock(McpSseConnectionTester.class),
            mock(McpConnectionTester.class),
            mock(SkillManifestProbe.class),
            mock(EmailDeliveryConnectionTester.class),
            Clock.fixed(Instant.parse("2026-05-15T08:00:00Z"), ZoneOffset.UTC)
        );
    }

    private static SystemManagementService buildService(
        TenantRepository tenantRepository,
        ModelProviderRepository modelProviderRepository,
        ModelProviderTypeRepository modelProviderTypeRepository,
        SystemCapabilityRepository systemCapabilityRepository,
        TenantCapabilityGrantRepository tenantCapabilityGrantRepository,
        TenantModelAssignmentRepository tenantModelAssignmentRepository,
        ModelProviderConnectionTester modelProviderConnectionTester,
        McpSseConnectionTester mcpSseConnectionTester,
        SkillManifestProbe skillManifestProbe,
        EmailDeliveryConnectionTester emailDeliveryConnectionTester
    ) {
        return new SystemManagementService(
            tenantRepository,
            modelProviderRepository,
            modelProviderTypeRepository,
            systemCapabilityRepository,
            tenantCapabilityGrantRepository,
            tenantModelAssignmentRepository,
            mock(TenantSsoProviderRepository.class),
            mock(UserAccountRepository.class),
            mock(UserRoleAssignmentRepository.class),
            mock(RoleRepository.class),
            mock(DepartmentRepository.class),
            mock(UserMembershipRepository.class),
            mock(UserMembershipRoleRepository.class),
            mock(PasswordEncoder.class),
            FIELD_ENCRYPTION,
            modelProviderConnectionTester,
            mcpSseConnectionTester,
            mock(McpConnectionTester.class),
            skillManifestProbe,
            emailDeliveryConnectionTester,
            Clock.fixed(Instant.parse("2026-05-15T08:00:00Z"), ZoneOffset.UTC)
        );
    }
}
