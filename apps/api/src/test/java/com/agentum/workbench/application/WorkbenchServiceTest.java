package com.agentum.workbench.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentum.asset.infrastructure.TenantAssetCapabilityRepository;
import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.auth.domain.UserAccount;
import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.organization.infrastructure.UserMembershipRepository;
import com.agentum.organization.infrastructure.UserMembershipRoleRepository;
import com.agentum.permission.infrastructure.ResourceGrantRepository;
import com.agentum.system.domain.SystemCapabilityEntity;
import com.agentum.system.domain.TenantCapabilityGrantEntity;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import com.agentum.system.infrastructure.TenantCapabilityGrantRepository;
import com.agentum.tenant.domain.TenantEntity;
import com.agentum.tenant.infrastructure.TenantRepository;
import com.agentum.workbench.interfaces.WorkbenchApi;
import com.agentum.workflow.domain.WorkflowDefinitionEntity;
import com.agentum.workflow.domain.WorkflowVersionEntity;
import com.agentum.workflow.infrastructure.WorkflowDefinitionRepository;
import com.agentum.workflow.infrastructure.WorkflowVersionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class WorkbenchServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ROLE_ASSIGNMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000502");
    private static final Instant NOW = Instant.parse("2026-05-28T08:00:00Z");

    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final WorkflowDefinitionRepository workflowDefinitionRepository = mock(WorkflowDefinitionRepository.class);
    private final WorkflowVersionRepository workflowVersionRepository = mock(WorkflowVersionRepository.class);
    private final UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
    private final TenantAssetCapabilityRepository tenantAssetCapabilityRepository = mock(TenantAssetCapabilityRepository.class);
    private final TenantCapabilityGrantRepository tenantCapabilityGrantRepository = mock(TenantCapabilityGrantRepository.class);
    private final SystemCapabilityRepository systemCapabilityRepository = mock(SystemCapabilityRepository.class);
    private final ResourceGrantRepository resourceGrantRepository = mock(ResourceGrantRepository.class);
    private final UserMembershipRepository userMembershipRepository = mock(UserMembershipRepository.class);
    private final UserMembershipRoleRepository userMembershipRoleRepository = mock(UserMembershipRoleRepository.class);

    @Test
    void shouldReturnSummaryWithRealStatistics() {
        WorkbenchService service = newService();
        SystemCapabilityEntity capability = SystemCapabilityEntity.create(
            "skill", "合同解析", "contract_parse", "v1", "", "low", "active", Map.of(), NOW
        );
        TenantCapabilityGrantEntity tenantGrant = TenantCapabilityGrantEntity.create(TENANT_ID, capability.getId(), "enabled", NOW);

        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active"))
            .thenReturn(Optional.of(TenantEntity.create("演示租户", "demo", NOW)));
        when(workflowDefinitionRepository.countByTenantIdAndStatus(TENANT_ID, "published")).thenReturn(7L);
        when(tenantAssetCapabilityRepository.countByTenantIdAndCreatedBy(TENANT_ID, USER_ID)).thenReturn(3L);
        when(tenantCapabilityGrantRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).thenReturn(List.of(tenantGrant));
        when(systemCapabilityRepository.findAllById(any())).thenReturn(List.of(capability));

        WorkbenchApi.WorkbenchSummary summary = service.getSummary(TENANT_ID, tenantAdminPrincipal());

        assertThat(summary.metrics().publishedWorkflowTotal()).isEqualTo(7L);
        assertThat(summary.metrics().availableWorkflowTotal()).isEqualTo(7L);
        assertThat(summary.metrics().myAssetTotal()).isEqualTo(3L);
        // 租户管理员可以看到全部租户能力池中处于 active 且属于资产能力类型的能力。
        assertThat(summary.metrics().openedCapabilityTotal()).isEqualTo(1L);
        // 第一阶段运行态未上线，待办与运行记录恒为空。
        assertThat(summary.pendingTodos()).isEmpty();
        assertThat(summary.recentRuns()).isEmpty();
        assertThat(summary.runtimeAvailable()).isFalse();
        assertThat(summary.runtimeStatusLabel()).isEqualTo("运行态建设中");
        assertThat(summary.generatedAt()).isEqualTo(NOW);
    }

    @Test
    void shouldListAvailableWorkflowsWithLatestVersion() {
        WorkbenchService service = newService();
        WorkflowDefinitionEntity definition = WorkflowDefinitionEntity.create(
            TENANT_ID, "合同审查流程", "识别合同条款风险", USER_ID, NOW
        );
        definition.markPublished(USER_ID, NOW);
        WorkflowVersionEntity version = WorkflowVersionEntity.create(
            definition.getId(), TENANT_ID, 2, "{}", 5, 0, USER_ID, NOW.plusSeconds(60)
        );
        // UserAccount#create 默认生成随机 id，这里用 mock 让 id 与 createdBy 对齐，便于断言 ownerName 落到“设计者”。
        UserAccount owner = mock(UserAccount.class);
        when(owner.getId()).thenReturn(USER_ID);
        when(owner.getDisplayName()).thenReturn("设计者");

        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active"))
            .thenReturn(Optional.of(TenantEntity.create("演示租户", "demo", NOW)));
        when(workflowDefinitionRepository.searchDrafts(eq(TENANT_ID), anyString(), eq(null), eq(null), eq("published"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(definition)));
        when(workflowVersionRepository.findLatestByWorkflowIds(any())).thenReturn(List.of(version));
        when(userAccountRepository.findAllById(any())).thenReturn(List.of(owner));

        var page = service.listAvailableWorkflows(TENANT_ID, businessPrincipal(), "", 1, 10, "updatedAt,desc");

        assertThat(page.items()).hasSize(1);
        WorkbenchApi.AvailableWorkflowRow row = page.items().get(0);
        assertThat(row.id()).isEqualTo(definition.getId());
        assertThat(row.name()).isEqualTo("合同审查流程");
        assertThat(row.latestVersionNumber()).isEqualTo(2);
        assertThat(row.publishedAt()).isEqualTo(NOW.plusSeconds(60));
        // 未匹配到 UserAccount 时应回退为“未知用户”，这里 owner 存在所以使用 displayName。
        assertThat(row.ownerName()).isEqualTo("设计者");
    }

    @Test
    void shouldReportZeroCapabilityWhenBusinessUserHasNoGrant() {
        WorkbenchService service = newService();
        SystemCapabilityEntity capability = SystemCapabilityEntity.create(
            "skill", "合同解析", "contract_parse", "v1", "", "low", "active", Map.of(), NOW
        );
        TenantCapabilityGrantEntity tenantGrant = TenantCapabilityGrantEntity.create(TENANT_ID, capability.getId(), "enabled", NOW);

        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active"))
            .thenReturn(Optional.of(TenantEntity.create("演示租户", "demo", NOW)));
        when(workflowDefinitionRepository.countByTenantIdAndStatus(TENANT_ID, "published")).thenReturn(0L);
        when(tenantAssetCapabilityRepository.countByTenantIdAndCreatedBy(TENANT_ID, USER_ID)).thenReturn(0L);
        when(tenantCapabilityGrantRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).thenReturn(List.of(tenantGrant));
        when(systemCapabilityRepository.findAllById(any())).thenReturn(List.of(capability));
        when(userMembershipRepository.findByUserIdAndTenantIdAndStatus(USER_ID, TENANT_ID, "active")).thenReturn(List.of());
        when(resourceGrantRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).thenReturn(List.of());

        WorkbenchApi.WorkbenchSummary summary = service.getSummary(TENANT_ID, businessPrincipal());

        // 业务用户没有被分配该能力，因此“对我开放能力”应为 0。
        assertThat(summary.metrics().openedCapabilityTotal()).isZero();
        assertThat(summary.metrics().publishedWorkflowTotal()).isZero();
    }

    private WorkbenchService newService() {
        return new WorkbenchService(
            tenantRepository,
            workflowDefinitionRepository,
            workflowVersionRepository,
            userAccountRepository,
            tenantAssetCapabilityRepository,
            tenantCapabilityGrantRepository,
            systemCapabilityRepository,
            resourceGrantRepository,
            userMembershipRepository,
            userMembershipRoleRepository,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static CurrentUserPrincipal tenantAdminPrincipal() {
        return new CurrentUserPrincipal(USER_ID, "tenantadmin", TENANT_ID, "tenant_admin", "tenant_admin", ROLE_ASSIGNMENT_ID);
    }

    private static CurrentUserPrincipal businessPrincipal() {
        return new CurrentUserPrincipal(USER_ID, "operator", TENANT_ID, "business", "business", ROLE_ASSIGNMENT_ID);
    }
}
