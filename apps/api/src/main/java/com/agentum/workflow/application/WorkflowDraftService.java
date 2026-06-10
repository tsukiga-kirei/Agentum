package com.agentum.workflow.application;

import com.agentum.auth.domain.UserAccount;
import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.agentum.shared.pagination.PageQuery;
import com.agentum.shared.pagination.PageResponse;
import com.agentum.shared.pagination.PageableFactory;
import com.agentum.shared.pagination.SortWhitelist;
import com.agentum.organization.domain.UserMembershipEntity;
import com.agentum.organization.infrastructure.UserMembershipRepository;
import com.agentum.permission.application.CollaborationAccessPolicy;
import com.agentum.tenant.infrastructure.TenantRepository;
import com.agentum.workflow.domain.WorkflowAccessGrantEntity;
import com.agentum.workflow.domain.WorkflowDefinitionEntity;
import com.agentum.workflow.domain.WorkflowEdgeDefinitionEntity;
import com.agentum.workflow.domain.WorkflowNodeDefinitionEntity;
import com.agentum.workflow.domain.WorkflowVariableDefinitionEntity;
import com.agentum.workflow.domain.WorkflowVersionEntity;
import com.agentum.workflow.infrastructure.WorkflowDefinitionRepository;
import com.agentum.workflow.infrastructure.WorkflowAccessGrantRepository;
import com.agentum.workflow.infrastructure.WorkflowEdgeDefinitionRepository;
import com.agentum.workflow.infrastructure.WorkflowNodeDefinitionRepository;
import com.agentum.workflow.infrastructure.WorkflowVariableDefinitionRepository;
import com.agentum.workflow.infrastructure.WorkflowVersionRepository;
import com.agentum.workflow.interfaces.WorkflowDraftApi;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowDraftService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDraftService.class);
    private static final String ACTIVE_STATUS = "active";
    private static final SortWhitelist DRAFT_SORT = SortWhitelist.of("updatedAt", "name", "status", "createdAt", "updatedAt", "nodeCount");
    private static final Set<String> ALLOWED_NODE_TYPES = Set.of(
        "trigger",
        "user_input",
        "agent",
        "parallel_group",
        "merge",
        "condition",
        "human_review",
        "delivery"
    );

    private final TenantRepository tenantRepository;
    private final UserAccountRepository userAccountRepository;
    private final WorkflowDefinitionRepository workflowDefinitionRepository;
    private final WorkflowAccessGrantRepository workflowAccessGrantRepository;
    private final WorkflowNodeDefinitionRepository workflowNodeDefinitionRepository;
    private final WorkflowEdgeDefinitionRepository workflowEdgeDefinitionRepository;
    private final WorkflowVariableDefinitionRepository workflowVariableDefinitionRepository;
    private final WorkflowVersionRepository workflowVersionRepository;
    private final WorkflowVariableDeclarationValidator workflowVariableDeclarationValidator;
    private final WorkflowPublishValidator workflowPublishValidator;
    private final WorkflowNodeConfigValidator workflowNodeConfigValidator;
    private final UserMembershipRepository userMembershipRepository;
    private final CollaborationAccessPolicy collaborationAccessPolicy;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WorkflowDraftService(
        TenantRepository tenantRepository,
        UserAccountRepository userAccountRepository,
        WorkflowDefinitionRepository workflowDefinitionRepository,
        WorkflowAccessGrantRepository workflowAccessGrantRepository,
        WorkflowNodeDefinitionRepository workflowNodeDefinitionRepository,
        WorkflowEdgeDefinitionRepository workflowEdgeDefinitionRepository,
        WorkflowVariableDefinitionRepository workflowVariableDefinitionRepository,
        WorkflowVersionRepository workflowVersionRepository,
        WorkflowVariableDeclarationValidator workflowVariableDeclarationValidator,
        WorkflowPublishValidator workflowPublishValidator,
        WorkflowNodeConfigValidator workflowNodeConfigValidator,
        UserMembershipRepository userMembershipRepository,
        CollaborationAccessPolicy collaborationAccessPolicy,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.tenantRepository = tenantRepository;
        this.userAccountRepository = userAccountRepository;
        this.workflowDefinitionRepository = workflowDefinitionRepository;
        this.workflowAccessGrantRepository = workflowAccessGrantRepository;
        this.workflowNodeDefinitionRepository = workflowNodeDefinitionRepository;
        this.workflowEdgeDefinitionRepository = workflowEdgeDefinitionRepository;
        this.workflowVariableDefinitionRepository = workflowVariableDefinitionRepository;
        this.workflowVersionRepository = workflowVersionRepository;
        this.workflowVariableDeclarationValidator = workflowVariableDeclarationValidator;
        this.workflowPublishValidator = workflowPublishValidator;
        this.workflowNodeConfigValidator = workflowNodeConfigValidator;
        this.userMembershipRepository = userMembershipRepository;
        this.collaborationAccessPolicy = collaborationAccessPolicy;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<WorkflowDraftApi.WorkflowDraftRow> listDrafts(UUID tenantId, UUID operatorUserId, String keyword, String scope, String status, int page, int size, String sort) {
        ensureActiveTenant(tenantId);
        Pageable pageable = PageableFactory.from(PageQuery.of(page, size, sort), DRAFT_SORT);
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        boolean onlyMine = "mine".equals(scope);
        boolean onlyShared = "shared".equals(scope);
        String normalizedStatus = status == null || status.isBlank() || "all".equals(status) ? null : status.trim();
        // 协作开放只展示他人开放给当前用户参与设计的流程；我的流程只筛当前创建人，避免前端用负责人姓名猜测归属。
        var resultPage = workflowDefinitionRepository.searchDrafts(tenantId, normalizedKeyword, operatorUserId, onlyMine, onlyShared, normalizedStatus, pageable);
        Set<UUID> creatorIds = resultPage.getContent().stream()
            .map(WorkflowDefinitionEntity::getCreatedBy)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<UUID, UserAccount> usersById = loadUsersById(creatorIds);
        Map<UUID, WorkflowVersionEntity> latestVersions = loadLatestVersions(resultPage.getContent());
        return PageResponse.from(resultPage.map(definition -> toDraftRow(definition, usersById, operatorUserId, latestVersions.get(definition.getId()))));
    }

    @Transactional
    public WorkflowDraftApi.WorkflowDraftRow createDraft(UUID tenantId, UUID operatorUserId, WorkflowDraftApi.CreateWorkflowDraftRequest request) {
        ensureActiveTenant(tenantId);
        String name = normalizeRequired(request.name());
        String description = normalizeOptional(request.description());
        String readScope = normalizeScope(request.readScope());
        String editScope = normalizeScope(request.editScope());
        if (name.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_DRAFT_NAME_REQUIRED", "请输入工作流名称");
        }

        WorkflowDefinitionEntity definition = WorkflowDefinitionEntity.create(tenantId, name, description, operatorUserId, clock.instant());
        definition.updateAccess(readScope, editScope, operatorUserId, clock.instant());
        workflowDefinitionRepository.save(definition);
        replaceAccessGrants(tenantId, definition, operatorUserId, readScope, request.readUserIds(), editScope, request.editUserIds());
        log.info(
            "工作流草稿创建成功 tenantId={} operatorUserId={} workflowId={} name={} requestId={}",
            tenantId,
            operatorUserId,
            definition.getId(),
            name,
            RequestIds.current()
        );
        return toDraftRow(definition, loadUsersById(Set.of(operatorUserId)), operatorUserId, null);
    }

    @Transactional(readOnly = true)
    public List<WorkflowDraftApi.ShareableMemberRow> listShareableMembers(UUID tenantId, UUID operatorUserId) {
        ensureActiveTenant(tenantId);
        return userMembershipRepository.findByTenantIdAndStatus(tenantId, ACTIVE_STATUS).stream()
            .map(UserMembershipEntity::getUserId)
            .filter(userId -> !userId.equals(operatorUserId))
            .distinct()
            .map(userId -> userAccountRepository.findById(userId).orElse(null))
            .filter(user -> user != null && ACTIVE_STATUS.equals(user.getStatus()))
            .sorted(java.util.Comparator.comparing(UserAccount::getDisplayName))
            .map(user -> new WorkflowDraftApi.ShareableMemberRow(user.getId(), user.getUsername(), user.getDisplayName()))
            .toList();
    }

    @Transactional
    public WorkflowDraftApi.WorkflowDraftDetail updateDraft(
        UUID tenantId,
        UUID operatorUserId,
        UUID workflowId,
        WorkflowDraftApi.UpdateWorkflowDraftRequest request
    ) {
        WorkflowDefinitionEntity definition = findDefinitionForEdit(tenantId, workflowId, operatorUserId);
        String name = normalizeRequired(request.name());
        if (name.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_DRAFT_NAME_REQUIRED", "请输入工作流名称");
        }
        definition.updateMetadata(name, normalizeOptional(request.description()), operatorUserId, clock.instant());
        markUnpublishedChangesIfNeeded(definition, operatorUserId);
        workflowDefinitionRepository.save(definition);
        return toDetail(definition, operatorUserId);
    }

    @Transactional
    public void deleteDraft(UUID tenantId, UUID operatorUserId, UUID workflowId) {
        WorkflowDefinitionEntity definition = findDefinitionForOwner(tenantId, workflowId, operatorUserId);
        // 运行实例引用检查将在运行态接入后补全；当前仅允许创建者删除整条定义及关联版本快照。
        workflowAccessGrantRepository.deleteByWorkflowId(workflowId);
        workflowDefinitionRepository.delete(definition);
        log.info(
            "工作流草稿已删除 tenantId={} operatorUserId={} workflowId={} requestId={}",
            tenantId,
            operatorUserId,
            workflowId,
            RequestIds.current()
        );
    }

    @Transactional
    public WorkflowDraftApi.WorkflowDraftDetail recallLaunch(UUID tenantId, UUID operatorUserId, UUID workflowId) {
        WorkflowDefinitionEntity definition = findDefinitionForOwner(tenantId, workflowId, operatorUserId);
        WorkflowVersionEntity latestVersion = requireLatestVersion(workflowId);
        if (!definition.isLaunchEnabled()) {
            return toDetail(definition, operatorUserId);
        }
        definition.recallFromLaunch(operatorUserId, clock.instant());
        workflowDefinitionRepository.save(definition);
        log.info(
            "工作流已下线 tenantId={} operatorUserId={} workflowId={} version={} requestId={}",
            tenantId,
            operatorUserId,
            workflowId,
            latestVersion.getVersionNumber(),
            RequestIds.current()
        );
        return toDetail(definition, operatorUserId);
    }

    @Transactional
    public WorkflowDraftApi.WorkflowDraftDetail restoreLaunch(UUID tenantId, UUID operatorUserId, UUID workflowId) {
        WorkflowDefinitionEntity definition = findDefinitionForOwner(tenantId, workflowId, operatorUserId);
        requireLatestVersion(workflowId);
        if (definition.isLaunchEnabled()) {
            return toDetail(definition, operatorUserId);
        }
        definition.restoreLaunch(operatorUserId, clock.instant());
        workflowDefinitionRepository.save(definition);
        log.info(
            "工作流已上线 tenantId={} operatorUserId={} workflowId={} requestId={}",
            tenantId,
            operatorUserId,
            workflowId,
            RequestIds.current()
        );
        return toDetail(definition, operatorUserId);
    }

    @Transactional
    public WorkflowDraftApi.WorkflowDraftDetail updateAccess(
        UUID tenantId,
        UUID operatorUserId,
        UUID workflowId,
        WorkflowDraftApi.UpdateWorkflowAccessRequest request
    ) {
        WorkflowDefinitionEntity definition = findDefinitionForOwner(tenantId, workflowId, operatorUserId);
        String readScope = normalizeScope(request.readScope());
        String editScope = normalizeScope(request.editScope());
        definition.updateAccess(readScope, editScope, operatorUserId, clock.instant());
        workflowDefinitionRepository.save(definition);
        replaceAccessGrants(tenantId, definition, operatorUserId, readScope, request.readUserIds(), editScope, request.editUserIds());
        log.info(
            "工作流读取编辑权限已更新 tenantId={} operatorUserId={} workflowId={} readScope={} editScope={} requestId={}",
            tenantId,
            operatorUserId,
            workflowId,
            readScope,
            editScope,
            RequestIds.current()
        );
        return toDetail(definition, operatorUserId);
    }

    @Transactional(readOnly = true)
    public WorkflowDraftApi.WorkflowDraftDetail getDraft(UUID tenantId, UUID operatorUserId, UUID workflowId) {
        WorkflowDefinitionEntity definition = findDefinitionForRead(tenantId, workflowId, operatorUserId);
        return toDetail(definition, operatorUserId);
    }

    @Transactional(readOnly = true)
    public WorkflowDraftApi.WorkflowPublishValidationResult validateForPublish(UUID tenantId, UUID operatorUserId, UUID workflowId) {
        WorkflowDefinitionEntity definition = findDefinitionForEdit(tenantId, workflowId, operatorUserId);
        WorkflowDraftApi.WorkflowDraftDetail detail = toDetail(definition, operatorUserId);
        WorkflowDraftApi.WorkflowPublishValidationResult graphResult = workflowPublishValidator.validate(detail.nodes(), detail.edges());
        List<WorkflowDraftApi.WorkflowValidationIssue> issues = new ArrayList<>(graphResult.issues());
        issues.addAll(workflowNodeConfigValidator.validateCapabilityReferences(tenantId, operatorUserId, detail.nodes()));
        WorkflowDraftApi.WorkflowPublishValidationResult result = new WorkflowDraftApi.WorkflowPublishValidationResult(
            graphResult.valid() && issues.isEmpty(),
            graphResult.nodeCount(),
            graphResult.edgeCount(),
            issues
        );
        log.info(
            "工作流发布校验完成 tenantId={} workflowId={} valid={} issueCount={} requestId={}",
            tenantId,
            workflowId,
            result.valid(),
            result.issues().size(),
            RequestIds.current()
        );
        return result;
    }

    @Transactional
    public WorkflowDraftApi.WorkflowPublishResult publish(UUID tenantId, UUID operatorUserId, UUID workflowId) {
        WorkflowDefinitionEntity definition = findDefinitionForEdit(tenantId, workflowId, operatorUserId);
        if ("published".equals(definition.getStatus())) {
            log.warn(
                "工作流发布被拒绝：当前草稿没有待发布变更 tenantId={} operatorUserId={} workflowId={} requestId={}",
                tenantId,
                operatorUserId,
                workflowId,
                RequestIds.current()
            );
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_ALREADY_PUBLISHED", "当前草稿没有待发布变更");
        }

        WorkflowDraftApi.WorkflowDraftDetail detail = toDetail(definition, operatorUserId);
        WorkflowDraftApi.WorkflowPublishValidationResult validation = workflowPublishValidator.validate(detail.nodes(), detail.edges());
        if (!validation.valid()) {
            log.warn(
                "工作流发布被拒绝：校验未通过 tenantId={} operatorUserId={} workflowId={} issueCount={} requestId={}",
                tenantId,
                operatorUserId,
                workflowId,
                validation.issues().size(),
                RequestIds.current()
            );
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "WORKFLOW_PUBLISH_VALIDATION_FAILED",
                "工作流尚未通过发布校验",
                Map.of("issueCount", validation.issues().size())
            );
        }

        // 发布前校验节点 config 中引用的 MCP / Skill / 交付能力仍在租户能力池内且状态有效。
        List<WorkflowDraftApi.WorkflowValidationIssue> configIssues = workflowNodeConfigValidator.validateCapabilityReferences(tenantId, operatorUserId, detail.nodes());
        if (!configIssues.isEmpty()) {
            log.warn(
                "工作流发布被拒绝：节点能力引用校验未通过 tenantId={} operatorUserId={} workflowId={} issueCount={} requestId={}",
                tenantId,
                operatorUserId,
                workflowId,
                configIssues.size(),
                RequestIds.current()
            );
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "WORKFLOW_PUBLISH_CAPABILITY_REFERENCE_INVALID",
                "流程节点引用的 MCP、Skill 或交付能力不在当前租户能力池中或已失效",
                Map.of("issueCount", configIssues.size())
            );
        }

        Instant now = clock.instant();
        int nextVersionNumber = workflowVersionRepository.findTopByWorkflowIdOrderByVersionNumberDesc(workflowId)
            .map(version -> version.getVersionNumber() + 1)
            .orElse(1);
        WorkflowVersionEntity version = WorkflowVersionEntity.create(
            workflowId,
            tenantId,
            nextVersionNumber,
            writeVersionSnapshot(definition, detail),
            definition.getNodeCount(),
            operatorUserId,
            now
        );
        workflowVersionRepository.save(version);
        definition.markPublished(operatorUserId, now);
        workflowDefinitionRepository.save(definition);

        log.info(
            "工作流发布成功 tenantId={} operatorUserId={} workflowId={} version={} requestId={}",
            tenantId,
            operatorUserId,
            workflowId,
            nextVersionNumber,
            RequestIds.current()
        );
        return new WorkflowDraftApi.WorkflowPublishResult(
            toDraftRow(definition, loadUsersById(definition.getCreatedBy() == null ? Set.of() : Set.of(definition.getCreatedBy())), operatorUserId, version),
            nextVersionNumber,
            now
        );
    }

    @Transactional
    public WorkflowDraftApi.WorkflowDraftDetail saveGraph(
        UUID tenantId,
        UUID operatorUserId,
        UUID workflowId,
        WorkflowDraftApi.SaveWorkflowDraftGraphRequest request
    ) {
        WorkflowDefinitionEntity definition = findDefinitionForEdit(tenantId, workflowId, operatorUserId);
        List<WorkflowDraftApi.WorkflowNodeDraft> nodes = WorkflowNodeConfigNormalizer.normalizeNodes(
            request.nodes() == null ? List.of() : request.nodes()
        );
        List<WorkflowDraftApi.WorkflowEdgeDraft> edges = request.edges() == null ? List.of() : request.edges();
        List<WorkflowDraftApi.WorkflowVariableDraft> variables = request.variables() == null ? List.of() : request.variables();
        validateGraph(tenantId, workflowId, nodes, edges);
        workflowVariableDeclarationValidator.validate(nodes, variables);
        List<WorkflowDraftApi.WorkflowValidationIssue> referenceIssues = workflowNodeConfigValidator.validateCapabilityReferences(
            tenantId,
            operatorUserId,
            nodes.stream().map(this::toNodeRow).toList()
        );
        if (!referenceIssues.isEmpty()) {
            throwConfigValidationFailed(referenceIssues);
        }

        Instant now = clock.instant();
        workflowNodeDefinitionRepository.deleteByWorkflowId(workflowId);
        workflowEdgeDefinitionRepository.deleteByWorkflowId(workflowId);
        workflowVariableDefinitionRepository.deleteByWorkflowId(workflowId);

        // 必须立即 flush 确保物理删除在插入新数据前执行，防止 Hibernate 默认 ActionQueue 插入优先导致唯一约束冲突 (uk_workflow_nodes_workflow_key)
        workflowNodeDefinitionRepository.flush();
        workflowEdgeDefinitionRepository.flush();
        workflowVariableDefinitionRepository.flush();

        List<WorkflowNodeDefinitionEntity> nodeEntities = new ArrayList<>();
        for (int index = 0; index < nodes.size(); index++) {
            WorkflowDraftApi.WorkflowNodeDraft node = nodes.get(index);
            nodeEntities.add(WorkflowNodeDefinitionEntity.create(
                workflowId,
                normalizeRequired(node.nodeId()),
                normalizeRequired(node.nodeType()),
                normalizeRequired(node.name()),
                BigDecimal.valueOf(node.positionX()),
                BigDecimal.valueOf(node.positionY()),
                node.inputVariables(),
                node.outputVariables(),
                node.config(),
                index,
                now
            ));
        }
        workflowNodeDefinitionRepository.saveAll(nodeEntities);

        List<WorkflowEdgeDefinitionEntity> edgeEntities = new ArrayList<>();
        for (int index = 0; index < edges.size(); index++) {
            WorkflowDraftApi.WorkflowEdgeDraft edge = edges.get(index);
            edgeEntities.add(WorkflowEdgeDefinitionEntity.create(
                workflowId,
                normalizeRequired(edge.edgeId()),
                normalizeRequired(edge.sourceNodeId()),
                normalizeRequired(edge.targetNodeId()),
                normalizeOptional(edge.label()),
                normalizeOptional(edge.conditionExpression()),
                index,
                now
            ));
        }
        workflowEdgeDefinitionRepository.saveAll(edgeEntities);

        List<WorkflowVariableDefinitionEntity> variableEntities = new ArrayList<>();
        for (int index = 0; index < variables.size(); index++) {
            WorkflowDraftApi.WorkflowVariableDraft variable = variables.get(index);
            variableEntities.add(WorkflowVariableDefinitionEntity.create(
                workflowId,
                normalizeRequired(variable.name()),
                normalizeRequired(variable.type()),
                normalizeRequired(variable.sourceNode()),
                normalizeOptional(variable.description()),
                writeJsonObject(variable.jsonSchema()),
                variable.sensitive(),
                variable.deliverable(),
                index,
                now
            ));
        }
        workflowVariableDefinitionRepository.saveAll(variableEntities);

        // 积木计数排除系统触发节点（trigger），与前端编辑器 visibleNodes 口径一致，用户只关心业务积木数量。
        int userNodeCount = (int) nodes.stream().filter(n -> !"trigger".equals(n.nodeType())).count();
        definition.updateGraphSummary(userNodeCount, operatorUserId, now);
        workflowDefinitionRepository.save(definition);
        log.info(
            "工作流草稿图保存成功 tenantId={} operatorUserId={} workflowId={} nodeCount={} edgeCount={} variableCount={} requestId={}",
            tenantId,
            operatorUserId,
            workflowId,
            nodes.size(),
            edges.size(),
            variables.size(),
            RequestIds.current()
        );
        return toDetail(definition, operatorUserId);
    }

    private void validateGraph(
        UUID tenantId,
        UUID workflowId,
        List<WorkflowDraftApi.WorkflowNodeDraft> nodes,
        List<WorkflowDraftApi.WorkflowEdgeDraft> edges
    ) {
        Set<String> nodeIds = new HashSet<>();
        for (WorkflowDraftApi.WorkflowNodeDraft node : nodes) {
            String nodeId = normalizeRequired(node.nodeId());
            String nodeType = normalizeRequired(node.nodeType());
            if (nodeId.isBlank() || !nodeIds.add(nodeId)) {
                log.warn("工作流草稿保存失败：节点 ID 重复或为空 tenantId={} workflowId={} nodeId={} requestId={}", tenantId, workflowId, nodeId, RequestIds.current());
                throw new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_NODE_ID_INVALID", "节点标识不能为空且不能重复");
            }
            if (!ALLOWED_NODE_TYPES.contains(nodeType)) {
                log.warn("工作流草稿保存失败：节点类型非法 tenantId={} workflowId={} nodeId={} nodeType={} requestId={}", tenantId, workflowId, nodeId, nodeType, RequestIds.current());
                throw new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_NODE_TYPE_INVALID", "包含不支持的节点类型");
            }
        }

        Set<String> edgeIds = new HashSet<>();
        for (WorkflowDraftApi.WorkflowEdgeDraft edge : edges) {
            String edgeId = normalizeRequired(edge.edgeId());
            if (edgeId.isBlank() || !edgeIds.add(edgeId)) {
                log.warn("工作流草稿保存失败：边 ID 重复或为空 tenantId={} workflowId={} edgeId={} requestId={}", tenantId, workflowId, edgeId, RequestIds.current());
                throw new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_EDGE_ID_INVALID", "连线标识不能为空且不能重复");
            }
            if (!nodeIds.contains(normalizeRequired(edge.sourceNodeId())) || !nodeIds.contains(normalizeRequired(edge.targetNodeId()))) {
                log.warn(
                    "工作流草稿保存失败：连线引用不存在节点 tenantId={} workflowId={} edgeId={} source={} target={} requestId={}",
                    tenantId,
                    workflowId,
                    edgeId,
                    edge.sourceNodeId(),
                    edge.targetNodeId(),
                    RequestIds.current()
                );
                throw new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_EDGE_NODE_NOT_FOUND", "连线引用了不存在的节点");
            }
        }
    }

    private void replaceAccessGrants(
        UUID tenantId,
        WorkflowDefinitionEntity definition,
        UUID operatorUserId,
        String readScope,
        List<UUID> readUserIds,
        String editScope,
        List<UUID> editUserIds
    ) {
        workflowAccessGrantRepository.deleteByWorkflowId(definition.getId());
        // 必须立即 flush，确保物理删除先于新授权插入执行，避免 uk_workflow_access_grants_workflow_user_level 冲突。
        workflowAccessGrantRepository.flush();
        List<UUID> normalizedReadUserIds = normalizeAccessUserIds(tenantId, operatorUserId, readScope, readUserIds, "读取");
        List<UUID> normalizedEditUserIds = normalizeAccessUserIds(tenantId, operatorUserId, editScope, editUserIds, "编辑");
        Instant now = clock.instant();
        normalizedReadUserIds.forEach(userId -> workflowAccessGrantRepository.save(
            WorkflowAccessGrantEntity.create(tenantId, definition.getId(), userId, "read", operatorUserId, now)
        ));
        normalizedEditUserIds.forEach(userId -> workflowAccessGrantRepository.save(
            WorkflowAccessGrantEntity.create(tenantId, definition.getId(), userId, "edit", operatorUserId, now)
        ));
    }

    private List<UUID> normalizeAccessUserIds(UUID tenantId, UUID operatorUserId, String scope, List<UUID> userIds, String label) {
        if (!CollaborationAccessPolicy.SCOPE_SPECIFIED.equals(scope)) {
            return List.of();
        }
        if (userIds == null || userIds.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_ACCESS_USERS_REQUIRED", label + "权限选择指定同事时，必须至少选择一名有效成员");
        }
        Set<UUID> activeMemberIds = userMembershipRepository.findByTenantIdAndStatus(tenantId, ACTIVE_STATUS).stream()
            .map(UserMembershipEntity::getUserId)
            .collect(Collectors.toSet());
        LinkedHashSet<UUID> normalized = new LinkedHashSet<>();
        for (UUID userId : userIds) {
            if (userId == null || userId.equals(operatorUserId)) {
                continue;
            }
            if (!activeMemberIds.contains(userId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_ACCESS_USER_INVALID", "权限对象必须是当前租户内的有效成员");
            }
            normalized.add(userId);
        }
        if (normalized.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_ACCESS_USERS_REQUIRED", label + "权限选择指定同事时，必须至少选择一名有效成员");
        }
        return new ArrayList<>(normalized);
    }

    private WorkflowDefinitionEntity findDefinition(UUID tenantId, UUID workflowId) {
        ensureActiveTenant(tenantId);
        return workflowDefinitionRepository.findByIdAndTenantId(workflowId, tenantId)
            .orElseThrow(() -> {
                log.warn("工作流草稿查询失败：草稿不存在 tenantId={} workflowId={} requestId={}", tenantId, workflowId, RequestIds.current());
                return new ApiException(HttpStatus.NOT_FOUND, "WORKFLOW_DRAFT_NOT_FOUND", "工作流草稿不存在");
            });
    }

    private WorkflowDefinitionEntity findDefinitionForRead(UUID tenantId, UUID workflowId, UUID operatorUserId) {
        WorkflowDefinitionEntity definition = findDefinition(tenantId, workflowId);
        if (!resolveAccess(definition, operatorUserId).canRead()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "WORKFLOW_READ_ACCESS_REQUIRED", "当前账号没有读取该流程的权限");
        }
        return definition;
    }

    private WorkflowDefinitionEntity findDefinitionForEdit(UUID tenantId, UUID workflowId, UUID operatorUserId) {
        WorkflowDefinitionEntity definition = findDefinition(tenantId, workflowId);
        if (!resolveAccess(definition, operatorUserId).canEdit()) {
            log.warn(
                "工作流编辑被拒绝 tenantId={} operatorUserId={} workflowId={} requestId={}",
                tenantId,
                operatorUserId,
                workflowId,
                RequestIds.current()
            );
            throw new ApiException(HttpStatus.FORBIDDEN, "WORKFLOW_EDIT_ACCESS_REQUIRED", "当前账号没有编辑该流程的权限");
        }
        return definition;
    }

    private WorkflowDefinitionEntity findDefinitionForOwner(UUID tenantId, UUID workflowId, UUID operatorUserId) {
        WorkflowDefinitionEntity definition = findDefinition(tenantId, workflowId);
        if (definition.getCreatedBy() == null || !definition.getCreatedBy().equals(operatorUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "WORKFLOW_OWNER_REQUIRED", "只有流程创建者可以调整权限");
        }
        return definition;
    }

    private void ensureActiveTenant(UUID tenantId) {
        tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> {
                log.warn("工作流草稿访问失败：租户不可用 tenantId={} requestId={}", tenantId, RequestIds.current());
                return new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "租户不存在或已停用");
            });
    }

    private WorkflowDraftApi.WorkflowDraftDetail toDetail(WorkflowDefinitionEntity definition, UUID operatorUserId) {
        List<WorkflowAccessGrantEntity> grants = workflowAccessGrantRepository.findByWorkflowId(definition.getId());
        CollaborationAccessPolicy.AccessLevel accessLevel = resolveAccess(definition, operatorUserId, grants);
        boolean canManageAccess = definition.getCreatedBy() != null && definition.getCreatedBy().equals(operatorUserId);
        WorkflowVersionEntity latestVersion = workflowVersionRepository.findTopByWorkflowIdOrderByVersionNumberDesc(definition.getId()).orElse(null);
        return new WorkflowDraftApi.WorkflowDraftDetail(
            toDraftRow(
                definition,
                loadUsersById(definition.getCreatedBy() == null ? Set.of() : Set.of(definition.getCreatedBy())),
                operatorUserId,
                latestVersion
            ),
            workflowNodeDefinitionRepository.findByWorkflowIdOrderBySortOrderAsc(definition.getId()).stream().map(this::toNodeRow).toList(),
            workflowEdgeDefinitionRepository.findByWorkflowIdOrderBySortOrderAsc(definition.getId()).stream().map(this::toEdgeRow).toList(),
            workflowVariableDefinitionRepository.findByWorkflowIdOrderBySortOrderAsc(definition.getId()).stream().map(this::toVariableRow).toList(),
            new WorkflowDraftApi.WorkflowAccessDetail(
                definition.getReadScope(),
                definition.getEditScope(),
                canManageAccess ? accessUserIds(grants, "read") : List.of(),
                canManageAccess ? accessUserIds(grants, "edit") : List.of(),
                accessLevel.name().toLowerCase(),
                canManageAccess
            )
        );
    }

    private WorkflowDraftApi.WorkflowDraftRow toDraftRow(
        WorkflowDefinitionEntity definition,
        Map<UUID, UserAccount> usersById,
        UUID operatorUserId,
        WorkflowVersionEntity latestVersion
    ) {
        UserAccount owner = definition.getCreatedBy() == null ? null : usersById.get(definition.getCreatedBy());
        int latestVersionNumber = latestVersion == null ? 0 : latestVersion.getVersionNumber();
        Instant latestPublishedAt = latestVersion == null ? null : latestVersion.getPublishedAt();
        boolean hasUnpublishedChanges = latestVersionNumber > 0 && "draft".equals(definition.getStatus());
        return new WorkflowDraftApi.WorkflowDraftRow(
            definition.getId(),
            definition.getTenantId(),
            definition.getName(),
            definition.getDescription() == null ? "" : definition.getDescription(),
            definition.getStatus(),
            definition.getNodeCount(),
            definition.getCreatedBy(),
            owner == null ? "未知用户" : owner.getDisplayName(),
            resolveAccess(definition, operatorUserId).name().toLowerCase(),
            latestVersionNumber,
            latestPublishedAt,
            hasUnpublishedChanges,
            definition.isLaunchEnabled(),
            definition.getUpdatedAt()
        );
    }

    private Map<UUID, WorkflowVersionEntity> loadLatestVersions(Collection<WorkflowDefinitionEntity> definitions) {
        Set<UUID> workflowIds = definitions.stream().map(WorkflowDefinitionEntity::getId).collect(Collectors.toSet());
        if (workflowIds.isEmpty()) {
            return Map.of();
        }
        return workflowVersionRepository.findLatestByWorkflowIds(workflowIds).stream()
            .collect(Collectors.toMap(WorkflowVersionEntity::getWorkflowId, Function.identity(), (left, right) -> left));
    }

    private void markUnpublishedChangesIfNeeded(WorkflowDefinitionEntity definition, UUID operatorUserId) {
        if (!"published".equals(definition.getStatus())) {
            return;
        }
        if (workflowVersionRepository.findTopByWorkflowIdOrderByVersionNumberDesc(definition.getId()).isEmpty()) {
            return;
        }
        definition.markUnpublishedChanges(operatorUserId, clock.instant());
    }

    private WorkflowVersionEntity requireLatestVersion(UUID workflowId) {
        return workflowVersionRepository.findTopByWorkflowIdOrderByVersionNumberDesc(workflowId)
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_VERSION_REQUIRED", "流程尚未发布，无法上下线"));
    }

    private CollaborationAccessPolicy.AccessLevel resolveAccess(WorkflowDefinitionEntity definition, UUID operatorUserId) {
        return resolveAccess(definition, operatorUserId, workflowAccessGrantRepository.findByWorkflowId(definition.getId()));
    }

    private CollaborationAccessPolicy.AccessLevel resolveAccess(
        WorkflowDefinitionEntity definition,
        UUID operatorUserId,
        List<WorkflowAccessGrantEntity> grants
    ) {
        Set<UUID> readUserIds = grants.stream()
            .filter(grant -> "read".equals(grant.getAccessLevel()))
            .map(WorkflowAccessGrantEntity::getGranteeUserId)
            .collect(Collectors.toSet());
        Set<UUID> editUserIds = grants.stream()
            .filter(grant -> "edit".equals(grant.getAccessLevel()))
            .map(WorkflowAccessGrantEntity::getGranteeUserId)
            .collect(Collectors.toSet());
        return collaborationAccessPolicy.resolve(
            definition.getCreatedBy(),
            operatorUserId,
            definition.getReadScope(),
            readUserIds,
            definition.getEditScope(),
            editUserIds
        );
    }

    private List<UUID> accessUserIds(List<WorkflowAccessGrantEntity> grants, String accessLevel) {
        return grants.stream()
            .filter(grant -> accessLevel.equals(grant.getAccessLevel()))
            .map(WorkflowAccessGrantEntity::getGranteeUserId)
            .toList();
    }

    private WorkflowDraftApi.WorkflowNodeRow toNodeRow(WorkflowNodeDefinitionEntity node) {
        return new WorkflowDraftApi.WorkflowNodeRow(
            node.getNodeKey(),
            node.getNodeType(),
            node.getName(),
            node.getPositionX().doubleValue(),
            node.getPositionY().doubleValue(),
            node.getInputVariables(),
            node.getOutputVariables(),
            node.getConfig()
        );
    }

    private WorkflowDraftApi.WorkflowNodeRow toNodeRow(WorkflowDraftApi.WorkflowNodeDraft node) {
        return new WorkflowDraftApi.WorkflowNodeRow(
            node.nodeId(),
            node.nodeType(),
            node.name(),
            node.positionX(),
            node.positionY(),
            node.inputVariables(),
            node.outputVariables(),
            node.config()
        );
    }

    private WorkflowDraftApi.WorkflowEdgeRow toEdgeRow(WorkflowEdgeDefinitionEntity edge) {
        return new WorkflowDraftApi.WorkflowEdgeRow(
            edge.getEdgeKey(),
            edge.getSourceNodeKey(),
            edge.getTargetNodeKey(),
            edge.getLabel() == null ? "" : edge.getLabel(),
            edge.getConditionExpression() == null ? "" : edge.getConditionExpression()
        );
    }

    private WorkflowDraftApi.WorkflowVariableRow toVariableRow(WorkflowVariableDefinitionEntity variable) {
        return new WorkflowDraftApi.WorkflowVariableRow(
            variable.getVariableKey(),
            variable.getVariableType(),
            variable.getSourceNodeKey(),
            variable.getDescription() == null ? "" : variable.getDescription(),
            readJsonObject(variable.getJsonSchema()),
            variable.isSensitive(),
            variable.isDeliverable()
        );
    }

    // 按需加载涉及用户，避免全表扫描；后续可改用二级缓存进一步优化。
    private Map<UUID, UserAccount> loadUsersById(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return userAccountRepository.findAllById(userIds).stream()
            .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
    }

    private String writeJsonObject(Map<String, Object> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? Map.of() : values);
        } catch (JsonProcessingException exception) {
            log.error("工作流节点配置序列化失败 requestId={}", RequestIds.current(), exception);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SYSTEM_JSON_SERIALIZE_FAILED", "系统暂时无法保存节点配置");
        }
    }

    private String writeVersionSnapshot(WorkflowDefinitionEntity definition, WorkflowDraftApi.WorkflowDraftDetail detail) {
        try {
            // 发布快照只保留执行协议需要的内容；页面展示字段可继续演进，不应反向污染历史版本。
            return objectMapper.writeValueAsString(new WorkflowVersionSnapshot(
                definition.getName(),
                definition.getDescription() == null ? "" : definition.getDescription(),
                detail.nodes(),
                detail.edges(),
                detail.variables()
            ));
        } catch (JsonProcessingException exception) {
            log.error("工作流发布快照序列化失败 workflowId={} requestId={}", definition.getId(), RequestIds.current(), exception);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SYSTEM_JSON_SERIALIZE_FAILED", "系统暂时无法生成工作流发布版本");
        }
    }

    private Map<String, Object> readJsonObject(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException exception) {
            log.warn("工作流节点配置解析失败 requestId={}", RequestIds.current());
            return Map.of();
        }
    }

    private static String normalizeRequired(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeOptional(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private String normalizeScope(String scope) {
        String normalized = normalizeRequired(scope);
        if (normalized.isBlank()) {
            return CollaborationAccessPolicy.SCOPE_SELF;
        }
        if (!collaborationAccessPolicy.isSupportedScope(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_ACCESS_SCOPE_INVALID", "权限范围不受支持");
        }
        return normalized;
    }

    private void throwConfigValidationFailed(List<WorkflowDraftApi.WorkflowValidationIssue> issues) {
        String message = issues.stream()
            .map(WorkflowDraftApi.WorkflowValidationIssue::message)
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElse("流程配置校验未通过");
        List<Map<String, Object>> issueDetails = issues.stream()
            .map(issue -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("code", issue.code());
                item.put("message", issue.message());
                item.put("nodeId", issue.nodeId() == null ? "" : issue.nodeId());
                item.put("nodeName", issue.nodeName() == null ? "" : issue.nodeName());
                return item;
            })
            .toList();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("issueCount", issues.size());
        details.put("issues", issueDetails);
        throw new ApiException(HttpStatus.BAD_REQUEST, resolveConfigValidationErrorCode(issues), message, details);
    }

    private static String resolveConfigValidationErrorCode(List<WorkflowDraftApi.WorkflowValidationIssue> issues) {
        boolean hasPromptIssue = issues.stream().anyMatch(issue -> issue.code() != null && issue.code().contains("PROMPT"));
        boolean hasCapabilityIssue = issues.stream().anyMatch(issue -> issue.code() != null && (
            issue.code().contains("CAPABILITY")
                || issue.code().contains("TENANT_ASSET")
                || issue.code().contains("ASSIGNED")
                || issue.code().contains("POOL")
        ));
        if (hasPromptIssue && !hasCapabilityIssue) {
            return "WORKFLOW_VALIDATION_PROMPT_INVALID";
        }
        if (hasPromptIssue) {
            return "WORKFLOW_VALIDATION_CONFIG_INVALID";
        }
        return "WORKFLOW_CAPABILITY_REFERENCE_NOT_AVAILABLE";
    }

    private record WorkflowVersionSnapshot(
        String name,
        String description,
        List<WorkflowDraftApi.WorkflowNodeRow> nodes,
        List<WorkflowDraftApi.WorkflowEdgeRow> edges,
        List<WorkflowDraftApi.WorkflowVariableRow> variables
    ) {
    }
}
