package com.agentum.asset.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentum.asset.domain.TenantAssetCapabilityEntity;
import com.agentum.asset.infrastructure.TenantAssetCapabilityRepository;
import com.agentum.asset.interfaces.AssetManagementApi;
import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.organization.domain.UserMembershipEntity;
import com.agentum.organization.domain.UserMembershipRoleEntity;
import com.agentum.organization.infrastructure.UserMembershipRepository;
import com.agentum.organization.infrastructure.UserMembershipRoleRepository;
import com.agentum.permission.domain.ResourceGrantEntity;
import com.agentum.permission.infrastructure.ResourceGrantRepository;
import com.agentum.shared.api.ApiException;
import com.agentum.system.domain.SystemCapabilityEntity;
import com.agentum.system.domain.TenantCapabilityGrantEntity;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import com.agentum.system.infrastructure.TenantCapabilityGrantRepository;
import com.agentum.tenant.domain.TenantEntity;
import com.agentum.tenant.infrastructure.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

class AssetManagementServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final Instant NOW = Instant.parse("2026-05-19T08:00:00Z");

    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final TenantCapabilityGrantRepository tenantCapabilityGrantRepository = mock(TenantCapabilityGrantRepository.class);
    private final SystemCapabilityRepository systemCapabilityRepository = mock(SystemCapabilityRepository.class);
    private final ResourceGrantRepository resourceGrantRepository = mock(ResourceGrantRepository.class);
    private final UserMembershipRepository userMembershipRepository = mock(UserMembershipRepository.class);
    private final UserMembershipRoleRepository userMembershipRoleRepository = mock(UserMembershipRoleRepository.class);
    private final TenantAssetCapabilityRepository tenantAssetCapabilityRepository = mock(TenantAssetCapabilityRepository.class);

    @Test
    void shouldMarkTenantSystemCapabilityAssignedByResourceGrant() {
        AssetManagementService service = newService();
        CurrentUserPrincipal principal = businessPrincipal();
        UserMembershipEntity membership = UserMembershipEntity.create(TENANT_ID, USER_ID, null);
        UserMembershipRoleEntity membershipRole = UserMembershipRoleEntity.create(membership.getId(), ROLE_ID);
        SystemCapabilityEntity capability = SystemCapabilityEntity.create("skill", "合同解析 Skill", "contract_parse", "v1", "", "low", "active", Map.of(), NOW);
        SystemCapabilityEntity unassignedCapability = SystemCapabilityEntity.create("mcp", "未分配 MCP", "unassigned_mcp", "v1", "", "medium", "active", Map.of(), NOW);
        TenantCapabilityGrantEntity tenantGrant = TenantCapabilityGrantEntity.create(TENANT_ID, capability.getId(), "enabled", NOW);
        TenantCapabilityGrantEntity unassignedTenantGrant = TenantCapabilityGrantEntity.create(TENANT_ID, unassignedCapability.getId(), "enabled", NOW);
        ResourceGrantEntity resourceGrant = ResourceGrantEntity.create(TENANT_ID, UUID.randomUUID(), "合同能力", "skill", capability.getId(), "role", ROLE_ID, new String[] { "use" });

        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("演示租户", "demo", NOW)));
        when(tenantCapabilityGrantRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).thenReturn(List.of(tenantGrant, unassignedTenantGrant));
        when(systemCapabilityRepository.findAllById(any())).thenReturn(List.of(capability, unassignedCapability));
        when(userMembershipRepository.findByUserIdAndTenantIdAndStatus(USER_ID, TENANT_ID, "active")).thenReturn(List.of(membership));
        when(userMembershipRoleRepository.findByMembershipIdInAndStatus(any(), any())).thenReturn(List.of(membershipRole));
        when(resourceGrantRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).thenReturn(List.of(resourceGrant));

        var page = service.listTenantSystemCapabilities(TENANT_ID, principal, 1, 10, "openedAt,desc", null, null);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).assignedToMe()).isTrue();
        assertThat(page.items().get(0).assignmentScope()).isEqualTo("租户管理已分配");
    }

    @Test
    void shouldHideTenantPoolCapabilityWithoutSubjectGrant() {
        AssetManagementService service = newService();
        SystemCapabilityEntity capability = SystemCapabilityEntity.create("skill", "合同解析 Skill", "contract_parse", "v1", "", "low", "active", Map.of(), NOW);
        TenantCapabilityGrantEntity tenantGrant = TenantCapabilityGrantEntity.create(TENANT_ID, capability.getId(), "enabled", NOW);

        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("演示租户", "demo", NOW)));
        when(tenantCapabilityGrantRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).thenReturn(List.of(tenantGrant));
        when(systemCapabilityRepository.findAllById(any())).thenReturn(List.of(capability));
        when(userMembershipRepository.findByUserIdAndTenantIdAndStatus(USER_ID, TENANT_ID, "active")).thenReturn(List.of());
        when(resourceGrantRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).thenReturn(List.of());

        var page = service.listTenantSystemCapabilities(TENANT_ID, businessPrincipal(), 1, 10, "openedAt,desc", null, null);
        var summary = service.getSummary(TENANT_ID, businessPrincipal());

        assertThat(page.items()).isEmpty();
        assertThat(summary.openedToMeSystemTotal()).isZero();
        assertThat(summary.tenantSystemPoolTotal()).isEqualTo(1);
    }

    @Test
    void shouldCreateMyAssetAsDraft() {
        AssetManagementService service = newService();
        TenantAssetCapabilityEntity saved = TenantAssetCapabilityEntity.create(
            TENANT_ID,
            "prompt_template",
            "Renewal Question",
            "renewal_question",
            "v1",
            "用于客户续约流程",
            "low",
            "draft",
            "private",
            null,
            Map.of(),
            USER_ID,
            NOW
        );

        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("演示租户", "demo", NOW)));
        when(tenantAssetCapabilityRepository.existsByTenantIdAndCodeAndVersion(TENANT_ID, "renewal_question", "v1")).thenReturn(false);
        when(tenantAssetCapabilityRepository.save(any(TenantAssetCapabilityEntity.class))).thenReturn(saved);

        AssetManagementApi.MyAssetRow row = service.createMyAsset(
            TENANT_ID,
            businessPrincipal(),
            new AssetManagementApi.CreateMyAssetRequest("prompt_template", "Renewal Question", null, "v1", "用于客户续约流程", "low", "private", null, Map.of())
        );

        assertThat(row.name()).isEqualTo("Renewal Question");
        assertThat(row.status()).isEqualTo("draft");
        assertThat(row.visibility()).isEqualTo("private");
    }

    @Test
    void shouldRejectBaseSystemCapabilityWhenCreatingUserDraft() {
        AssetManagementService service = newService();
        UUID baseCapabilityId = UUID.randomUUID();

        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("演示租户", "demo", NOW)));

        assertThatThrownBy(() -> service.createMyAsset(
            TENANT_ID,
            businessPrincipal(),
            new AssetManagementApi.CreateMyAssetRequest("agent_template", "合同解析智能体", null, "v1", "", "low", "private", baseCapabilityId, Map.of())
        ))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("ASSET_BASE_CAPABILITY_NOT_SUPPORTED");
    }

    @Test
    void shouldRejectUserCreatedSkillDraft() {
        AssetManagementService service = newService();
        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("演示租户", "demo", NOW)));

        assertThatThrownBy(() -> service.createMyAsset(
            TENANT_ID,
            businessPrincipal(),
            new AssetManagementApi.CreateMyAssetRequest("skill", "风险核对 Skill", null, "v1", "", "low", "private", null, Map.of())
        ))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("ASSET_TYPE_INVALID");
    }

    @Test
    void shouldGenerateUniqueCodeWhenBaseCodeExists() {
        AssetManagementService service = newService();
        TenantAssetCapabilityEntity saved = TenantAssetCapabilityEntity.create(
            TENANT_ID,
            "prompt_template",
            "Renewal Question",
            "renewal_question_2",
            "v1",
            "",
            "low",
            "draft",
            "private",
            null,
            Map.of(),
            USER_ID,
            NOW
        );

        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("演示租户", "demo", NOW)));
        when(tenantAssetCapabilityRepository.existsByTenantIdAndCodeAndVersion(TENANT_ID, "renewal_question", "v1")).thenReturn(true);
        when(tenantAssetCapabilityRepository.existsByTenantIdAndCodeAndVersion(TENANT_ID, "renewal_question_2", "v1")).thenReturn(false);
        when(tenantAssetCapabilityRepository.save(any(TenantAssetCapabilityEntity.class))).thenReturn(saved);

        AssetManagementApi.MyAssetRow row = service.createMyAsset(
            TENANT_ID,
            businessPrincipal(),
            new AssetManagementApi.CreateMyAssetRequest("prompt_template", "Renewal Question", null, "v1", "", "low", "private", null, Map.of())
        );

        assertThat(row.code()).isEqualTo("renewal_question_2");
    }

    @Test
    void shouldListOnlyCurrentUsersAssets() {
        AssetManagementService service = newService();
        TenantAssetCapabilityEntity asset = TenantAssetCapabilityEntity.create(
            TENANT_ID,
            "prompt_template",
            "提示词模板",
            "prompt_draft",
            "v1",
            "",
            "medium",
            "draft",
            "private",
            null,
            Map.of(),
            USER_ID,
            NOW
        );
        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("演示租户", "demo", NOW)));
        when(tenantAssetCapabilityRepository.searchMine(any(), any(), any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of(asset)));

        var page = service.listMyAssets(TENANT_ID, businessPrincipal(), "", 1, 10, "updatedAt,desc", null, null);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).code()).isEqualTo("prompt_draft");
    }

    @Test
    void shouldUpdateAndPublishPromptDraft() {
        AssetManagementService service = newService();
        TenantAssetCapabilityEntity asset = TenantAssetCapabilityEntity.create(
            TENANT_ID,
            "prompt_template",
            "续约追问模板",
            "renewal_question",
            "v1",
            "",
            "low",
            "draft",
            "private",
            null,
            Map.of("promptContent", ""),
            USER_ID,
            NOW
        );

        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("演示租户", "demo", NOW)));
        when(tenantAssetCapabilityRepository.findByIdAndTenantId(asset.getId(), TENANT_ID)).thenReturn(Optional.of(asset));
        when(tenantAssetCapabilityRepository.existsByTenantIdAndCodeAndVersionAndIdNot(TENANT_ID, "renewal_question", "v1", asset.getId())).thenReturn(false);

        var updated = service.updateMyAsset(
            TENANT_ID,
            asset.getId(),
            businessPrincipal(),
            new AssetManagementApi.UpdateMyAssetRequest("续约追问模板", "v1", "用于客户续约流程", "low", "private", Map.of("promptContent", "请识别客户续约风险。"))
        );
        var published = service.publishMyAsset(TENANT_ID, asset.getId(), businessPrincipal());

        assertThat(updated.config()).containsEntry("promptContent", "请识别客户续约风险。");
        assertThat(published.status()).isEqualTo("published");
        assertThat(published.visibility()).isEqualTo("tenant");
        assertThat(published.publishedAt()).isEqualTo(NOW);
    }

    @Test
    void shouldRejectAgentPublishWhenReferencedSkillIsNotAssignedToSubject() {
        AssetManagementService service = newService();
        SystemCapabilityEntity capability = SystemCapabilityEntity.create("skill", "合同解析 Skill", "contract_parse", "v1", "", "low", "active", Map.of(), NOW);
        TenantCapabilityGrantEntity tenantGrant = TenantCapabilityGrantEntity.create(TENANT_ID, capability.getId(), "enabled", NOW);
        TenantAssetCapabilityEntity asset = TenantAssetCapabilityEntity.create(
            TENANT_ID,
            "agent_template",
            "合同解析智能体",
            "contract_agent",
            "v1",
            "",
            "medium",
            "draft",
            "private",
            null,
            Map.of("systemPrompt", "你是合同解析智能体。", "skillIds", List.of(capability.getId().toString()), "mcpIds", List.of()),
            USER_ID,
            NOW
        );

        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("演示租户", "demo", NOW)));
        when(tenantAssetCapabilityRepository.findByIdAndTenantId(asset.getId(), TENANT_ID)).thenReturn(Optional.of(asset));
        when(tenantCapabilityGrantRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).thenReturn(List.of(tenantGrant));
        when(systemCapabilityRepository.findAllById(any())).thenReturn(List.of(capability));
        when(userMembershipRepository.findByUserIdAndTenantIdAndStatus(USER_ID, TENANT_ID, "active")).thenReturn(List.of());
        when(resourceGrantRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.publishMyAsset(TENANT_ID, asset.getId(), businessPrincipal()))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("ASSET_AGENT_CAPABILITY_NOT_AVAILABLE");
    }

    @Test
    void shouldDeleteOwnAsset() {
        AssetManagementService service = newService();
        TenantAssetCapabilityEntity asset = TenantAssetCapabilityEntity.create(
            TENANT_ID,
            "prompt_template",
            "续约追问模板",
            "renewal_question",
            "v1",
            "",
            "low",
            "draft",
            "private",
            null,
            Map.of("promptContent", "请识别客户续约风险。"),
            USER_ID,
            NOW
        );

        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("演示租户", "demo", NOW)));
        when(tenantAssetCapabilityRepository.findByIdAndTenantId(asset.getId(), TENANT_ID)).thenReturn(Optional.of(asset));

        service.deleteMyAsset(TENANT_ID, asset.getId(), businessPrincipal());

        verify(tenantAssetCapabilityRepository).delete(asset);
    }

    private AssetManagementService newService() {
        return new AssetManagementService(
            tenantRepository,
            tenantCapabilityGrantRepository,
            systemCapabilityRepository,
            resourceGrantRepository,
            userMembershipRepository,
            userMembershipRoleRepository,
            tenantAssetCapabilityRepository,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static CurrentUserPrincipal businessPrincipal() {
        return new CurrentUserPrincipal(USER_ID, "designer", TENANT_ID, "business", "business", UUID.randomUUID());
    }
}
