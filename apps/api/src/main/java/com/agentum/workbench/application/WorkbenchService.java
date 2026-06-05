package com.agentum.workbench.application;

import com.agentum.asset.infrastructure.TenantAssetCapabilityRepository;
import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.auth.domain.UserAccount;
import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.organization.domain.UserMembershipEntity;
import com.agentum.organization.domain.UserMembershipRoleEntity;
import com.agentum.organization.infrastructure.UserMembershipRepository;
import com.agentum.organization.infrastructure.UserMembershipRoleRepository;
import com.agentum.permission.domain.ResourceGrantEntity;
import com.agentum.permission.infrastructure.ResourceGrantRepository;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.agentum.shared.pagination.PageQuery;
import com.agentum.shared.pagination.PageResponse;
import com.agentum.shared.pagination.PageableFactory;
import com.agentum.shared.pagination.SortWhitelist;
import com.agentum.system.domain.SystemCapabilityEntity;
import com.agentum.system.domain.TenantCapabilityGrantEntity;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import com.agentum.system.infrastructure.TenantCapabilityGrantRepository;
import com.agentum.tenant.infrastructure.TenantRepository;
import com.agentum.workbench.interfaces.WorkbenchApi;
import com.agentum.workflow.domain.WorkflowDefinitionEntity;
import com.agentum.workflow.domain.WorkflowVersionEntity;
import com.agentum.workflow.infrastructure.WorkflowDefinitionRepository;
import com.agentum.workflow.infrastructure.WorkflowVersionRepository;
import java.time.Clock;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 业务工作台聚合服务。
 *
 * <p>当前阶段聚合三类真实数据：</p>
 * <ul>
 *   <li>概览指标：已发布工作流、能力资产数量等，全部来自真实数据库统计。</li>
 *   <li>可发起的已发布工作流：来自未收回的业务入口与最新发布版本，和设计态草稿状态解耦。</li>
 *   <li>我的待办 / 最近运行：运行态尚未上线，固定返回空列表并通过 {@code runtimeAvailable=false} 通知前端。</li>
 * </ul>
 *
 * <p>租户管理员属于租户内全量视图，业务用户只能看到通过租户管理“分配卡片”开放的能力；
 * 系统管理员允许跨租户访问，但视图与租户管理员一致。</p>
 */
@Service
public class WorkbenchService {

    private static final Logger log = LoggerFactory.getLogger(WorkbenchService.class);
    private static final String ACTIVE_STATUS = "active";
    private static final Set<String> SYSTEM_CAPABILITY_TYPES = Set.of("skill", "mcp", "prompt_template", "delivery");
    // 排序白名单只允许真实数据库字段，避免任意 sort 字段穿透到 JPA。
    // publishedAt / lastPublishedAt 别名映射到 workflow_definitions.updatedAt，
    // 因为发布操作会同步刷新 updatedAt，可近似表达“最近发布”。
    private static final SortWhitelist AVAILABLE_WORKFLOW_SORT = SortWhitelist.mapped(
        "updatedAt",
        Map.of(
            "updatedAt", "updatedAt",
            "name", "name",
            "nodeCount", "nodeCount",
            "publishedAt", "updatedAt",
            "lastPublishedAt", "updatedAt"
        )
    );

    private final TenantRepository tenantRepository;
    private final WorkflowDefinitionRepository workflowDefinitionRepository;
    private final WorkflowVersionRepository workflowVersionRepository;
    private final UserAccountRepository userAccountRepository;
    private final TenantAssetCapabilityRepository tenantAssetCapabilityRepository;
    private final TenantCapabilityGrantRepository tenantCapabilityGrantRepository;
    private final SystemCapabilityRepository systemCapabilityRepository;
    private final ResourceGrantRepository resourceGrantRepository;
    private final UserMembershipRepository userMembershipRepository;
    private final UserMembershipRoleRepository userMembershipRoleRepository;
    private final Clock clock;

    public WorkbenchService(
        TenantRepository tenantRepository,
        WorkflowDefinitionRepository workflowDefinitionRepository,
        WorkflowVersionRepository workflowVersionRepository,
        UserAccountRepository userAccountRepository,
        TenantAssetCapabilityRepository tenantAssetCapabilityRepository,
        TenantCapabilityGrantRepository tenantCapabilityGrantRepository,
        SystemCapabilityRepository systemCapabilityRepository,
        ResourceGrantRepository resourceGrantRepository,
        UserMembershipRepository userMembershipRepository,
        UserMembershipRoleRepository userMembershipRoleRepository,
        Clock clock
    ) {
        this.tenantRepository = tenantRepository;
        this.workflowDefinitionRepository = workflowDefinitionRepository;
        this.workflowVersionRepository = workflowVersionRepository;
        this.userAccountRepository = userAccountRepository;
        this.tenantAssetCapabilityRepository = tenantAssetCapabilityRepository;
        this.tenantCapabilityGrantRepository = tenantCapabilityGrantRepository;
        this.systemCapabilityRepository = systemCapabilityRepository;
        this.resourceGrantRepository = resourceGrantRepository;
        this.userMembershipRepository = userMembershipRepository;
        this.userMembershipRoleRepository = userMembershipRoleRepository;
        this.clock = clock;
    }

    /**
     * 获取业务工作台概览：统计指标 + 待办 + 最近运行 + 运行态状态标识。
     */
    @Transactional(readOnly = true)
    public WorkbenchApi.WorkbenchSummary getSummary(UUID tenantId, CurrentUserPrincipal principal) {
        ensureActiveTenant(tenantId);
        // 业务入口展示的是“仍可发起的发布版本”。流程发布后再次编辑会让设计态回到 draft，
        // 但旧版本未收回时仍应计入已发布流程，避免概览与创建任务列表口径不一致。
        long publishedWorkflowTotal = workflowDefinitionRepository.countLaunchableByTenantId(tenantId);
        long availableWorkflowTotal = principal == null
            ? 0
            : workflowDefinitionRepository.countVisibleLaunchableByTenantId(tenantId, principal.userId());
        long openedCapabilityTotal = countOpenedCapabilities(tenantId, principal);
        long myAssetTotal = principal == null
            ? 0
            : tenantAssetCapabilityRepository.countByTenantIdAndCreatedBy(tenantId, principal.userId());

        // 运行态、待办、最近运行所依赖的 WorkflowRun / NodeRun / WaitingEvent 表尚未落地，
        // 这里固定返回空集合并标记 runtimeAvailable=false，前端据此展示运行态建设中提示。
        WorkbenchApi.WorkbenchMetrics metrics = new WorkbenchApi.WorkbenchMetrics(
            0L,
            0L,
            publishedWorkflowTotal,
            availableWorkflowTotal,
            openedCapabilityTotal,
            myAssetTotal
        );

        log.debug(
            "业务工作台概览生成 tenantId={} userId={} publishedWorkflowTotal={} openedCapabilityTotal={} myAssetTotal={} requestId={}",
            tenantId,
            principal == null ? null : principal.userId(),
            publishedWorkflowTotal,
            openedCapabilityTotal,
            myAssetTotal,
            RequestIds.current()
        );

        return new WorkbenchApi.WorkbenchSummary(
            metrics,
            List.of(),
            List.of(),
            false,
            "运行态建设中",
            clock.instant()
        );
    }

    /**
     * 分页查询当前用户可发起的已发布工作流。
     *
     * <p>当前先以流程读取 / 使用权限作为可发起范围；后续接入运行实例后再叠加独立的发起动作权限。</p>
     */
    @Transactional(readOnly = true)
    public PageResponse<WorkbenchApi.AvailableWorkflowRow> listAvailableWorkflows(
        UUID tenantId,
        CurrentUserPrincipal principal,
        String keyword,
        int page,
        int size,
        String sort
    ) {
        ensureActiveTenant(tenantId);
        if (principal == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请先登录后再访问");
        }

        Pageable pageable = PageableFactory.from(PageQuery.of(page, size, sort), AVAILABLE_WORKFLOW_SORT);
        String normalizedKeyword = keyword == null ? "" : keyword.trim();

        // 业务侧可发起流程：至少存在一条冻结版本且入口未收回；与设计态 status 解耦，避免编辑后误下线。
        Page<WorkflowDefinitionEntity> resultPage = workflowDefinitionRepository.searchLaunchableWorkflows(
            tenantId,
            normalizedKeyword,
            principal.userId(),
            pageable
        );

        Set<UUID> definitionIds = resultPage.getContent().stream()
            .map(WorkflowDefinitionEntity::getId)
            .collect(Collectors.toSet());
        Map<UUID, WorkflowVersionEntity> latestVersions = definitionIds.isEmpty()
            ? Map.of()
            : workflowVersionRepository.findLatestByWorkflowIds(definitionIds).stream()
                .collect(Collectors.toMap(WorkflowVersionEntity::getWorkflowId, Function.identity(), (left, right) -> left));

        Set<UUID> ownerIds = resultPage.getContent().stream()
            .map(WorkflowDefinitionEntity::getCreatedBy)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<UUID, UserAccount> ownersById = loadUsersById(ownerIds);

        return PageResponse.from(resultPage.map(definition -> toAvailableWorkflow(definition, latestVersions.get(definition.getId()), ownersById)));
    }

    private WorkbenchApi.AvailableWorkflowRow toAvailableWorkflow(
        WorkflowDefinitionEntity definition,
        WorkflowVersionEntity latestVersion,
        Map<UUID, UserAccount> ownersById
    ) {
        UserAccount owner = definition.getCreatedBy() == null ? null : ownersById.get(definition.getCreatedBy());
        return new WorkbenchApi.AvailableWorkflowRow(
            definition.getId(),
            definition.getName(),
            definition.getDescription() == null ? "" : definition.getDescription(),
            definition.getNodeCount(),
            latestVersion == null ? 0 : latestVersion.getVersionNumber(),
            latestVersion == null ? definition.getUpdatedAt() : latestVersion.getPublishedAt(),
            definition.getCreatedBy(),
            owner == null ? "未知用户" : owner.getDisplayName()
        );
    }

    private long countOpenedCapabilities(UUID tenantId, CurrentUserPrincipal principal) {
        if (principal == null) {
            return 0L;
        }

        List<TenantCapabilityGrantEntity> enabledGrants = tenantCapabilityGrantRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)
            .stream()
            .filter(grant -> "enabled".equals(grant.getStatus()))
            .toList();
        if (enabledGrants.isEmpty()) {
            return 0L;
        }

        Set<UUID> activeCapabilityIds = systemCapabilityRepository
            .findAllById(enabledGrants.stream().map(TenantCapabilityGrantEntity::getCapabilityId).collect(Collectors.toSet()))
            .stream()
            .filter(capability -> ACTIVE_STATUS.equals(capability.getStatus()))
            .filter(capability -> SYSTEM_CAPABILITY_TYPES.contains(capability.getCapabilityType()))
            .map(SystemCapabilityEntity::getId)
            .collect(Collectors.toSet());
        if (activeCapabilityIds.isEmpty()) {
            return 0L;
        }

        // 租户管理员视角可见全部租户能力池；业务用户只看分配给本人 / 所在部门 / 已绑定角色的能力。
        if (isTenantManager(principal)) {
            return activeCapabilityIds.size();
        }

        Set<UUID> assignedCapabilityIds = resolveAssignedCapabilityIds(tenantId, principal);
        if (assignedCapabilityIds.isEmpty()) {
            return 0L;
        }
        assignedCapabilityIds.retainAll(activeCapabilityIds);
        return assignedCapabilityIds.size();
    }

    private Set<UUID> resolveAssignedCapabilityIds(UUID tenantId, CurrentUserPrincipal principal) {
        // 业务用户的能力可见集合来自 resource_grants 中按用户 / 部门 / 角色三种主体类型的分配。
        List<UserMembershipEntity> memberships = userMembershipRepository.findByUserIdAndTenantIdAndStatus(principal.userId(), tenantId, ACTIVE_STATUS);
        Set<String> principalKeys = new LinkedHashSet<>();
        principalKeys.add("user:" + principal.userId());
        memberships.stream()
            .map(UserMembershipEntity::getDepartmentId)
            .filter(Objects::nonNull)
            .map(departmentId -> "department:" + departmentId)
            .forEach(principalKeys::add);

        Set<UUID> membershipIds = memberships.stream().map(UserMembershipEntity::getId).collect(Collectors.toSet());
        if (!membershipIds.isEmpty()) {
            userMembershipRoleRepository.findByMembershipIdInAndStatus(membershipIds, ACTIVE_STATUS)
                .stream()
                .map(UserMembershipRoleEntity::getRoleId)
                .map(roleId -> "role:" + roleId)
                .forEach(principalKeys::add);
        }

        return resourceGrantRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)
            .stream()
            .filter(grant -> SYSTEM_CAPABILITY_TYPES.contains(grant.getResourceType()))
            .filter(grant -> principalKeys.contains(grant.getPrincipalType() + ":" + grant.getPrincipalId()))
            .map(ResourceGrantEntity::getResourceId)
            .collect(Collectors.toCollection(HashSet::new));
    }

    private Map<UUID, UserAccount> loadUsersById(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return userAccountRepository.findAllById(userIds).stream()
            .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
    }

    private void ensureActiveTenant(UUID tenantId) {
        tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> {
                log.warn("业务工作台访问失败：租户不可用 tenantId={} requestId={}", tenantId, RequestIds.current());
                return new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "租户不存在或已停用");
            });
    }

    private boolean isTenantManager(CurrentUserPrincipal principal) {
        return principal != null && ("tenant_admin".equals(principal.role()) || "system_admin".equals(principal.role()));
    }
}
