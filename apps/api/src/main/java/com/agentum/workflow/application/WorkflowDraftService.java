package com.agentum.workflow.application;

import com.agentum.auth.domain.UserAccount;
import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.agentum.shared.pagination.PageQuery;
import com.agentum.shared.pagination.PageResponse;
import com.agentum.shared.pagination.PageableFactory;
import com.agentum.shared.pagination.SortWhitelist;
import com.agentum.tenant.infrastructure.TenantRepository;
import com.agentum.workflow.domain.WorkflowDefinitionEntity;
import com.agentum.workflow.domain.WorkflowEdgeDefinitionEntity;
import com.agentum.workflow.domain.WorkflowNodeDefinitionEntity;
import com.agentum.workflow.domain.WorkflowVariableDefinitionEntity;
import com.agentum.workflow.domain.WorkflowVersionEntity;
import com.agentum.workflow.infrastructure.WorkflowDefinitionRepository;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private static final Set<String> PAUSE_NODE_TYPES = Set.of("user_input", "agent", "human_review");

    private final TenantRepository tenantRepository;
    private final UserAccountRepository userAccountRepository;
    private final WorkflowDefinitionRepository workflowDefinitionRepository;
    private final WorkflowNodeDefinitionRepository workflowNodeDefinitionRepository;
    private final WorkflowEdgeDefinitionRepository workflowEdgeDefinitionRepository;
    private final WorkflowVariableDefinitionRepository workflowVariableDefinitionRepository;
    private final WorkflowVersionRepository workflowVersionRepository;
    private final WorkflowVariableDeclarationValidator workflowVariableDeclarationValidator;
    private final WorkflowPublishValidator workflowPublishValidator;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WorkflowDraftService(
        TenantRepository tenantRepository,
        UserAccountRepository userAccountRepository,
        WorkflowDefinitionRepository workflowDefinitionRepository,
        WorkflowNodeDefinitionRepository workflowNodeDefinitionRepository,
        WorkflowEdgeDefinitionRepository workflowEdgeDefinitionRepository,
        WorkflowVariableDefinitionRepository workflowVariableDefinitionRepository,
        WorkflowVersionRepository workflowVersionRepository,
        WorkflowVariableDeclarationValidator workflowVariableDeclarationValidator,
        WorkflowPublishValidator workflowPublishValidator,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.tenantRepository = tenantRepository;
        this.userAccountRepository = userAccountRepository;
        this.workflowDefinitionRepository = workflowDefinitionRepository;
        this.workflowNodeDefinitionRepository = workflowNodeDefinitionRepository;
        this.workflowEdgeDefinitionRepository = workflowEdgeDefinitionRepository;
        this.workflowVariableDefinitionRepository = workflowVariableDefinitionRepository;
        this.workflowVersionRepository = workflowVersionRepository;
        this.workflowVariableDeclarationValidator = workflowVariableDeclarationValidator;
        this.workflowPublishValidator = workflowPublishValidator;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<WorkflowDraftApi.WorkflowDraftRow> listDrafts(UUID tenantId, String keyword, int page, int size, String sort) {
        ensureActiveTenant(tenantId);
        Pageable pageable = PageableFactory.from(PageQuery.of(page, size, sort), DRAFT_SORT);
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        Map<UUID, UserAccount> usersById = loadUsersById();
        return PageResponse.from(workflowDefinitionRepository.searchDrafts(tenantId, normalizedKeyword, pageable)
            .map(definition -> toDraftRow(definition, usersById)));
    }

    @Transactional
    public WorkflowDraftApi.WorkflowDraftRow createDraft(UUID tenantId, UUID operatorUserId, WorkflowDraftApi.CreateWorkflowDraftRequest request) {
        ensureActiveTenant(tenantId);
        String name = normalizeRequired(request.name());
        String description = normalizeOptional(request.description());
        if (name.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_DRAFT_NAME_REQUIRED", "请输入工作流名称");
        }

        WorkflowDefinitionEntity definition = WorkflowDefinitionEntity.create(tenantId, name, description, operatorUserId, clock.instant());
        workflowDefinitionRepository.save(definition);
        log.info(
            "工作流草稿创建成功 tenantId={} operatorUserId={} workflowId={} name={} requestId={}",
            tenantId,
            operatorUserId,
            definition.getId(),
            name,
            RequestIds.current()
        );
        return toDraftRow(definition, loadUsersById());
    }

    @Transactional(readOnly = true)
    public WorkflowDraftApi.WorkflowDraftDetail getDraft(UUID tenantId, UUID workflowId) {
        WorkflowDefinitionEntity definition = findDefinition(tenantId, workflowId);
        return toDetail(definition);
    }

    @Transactional(readOnly = true)
    public WorkflowDraftApi.WorkflowPublishValidationResult validateForPublish(UUID tenantId, UUID workflowId) {
        WorkflowDefinitionEntity definition = findDefinition(tenantId, workflowId);
        WorkflowDraftApi.WorkflowDraftDetail detail = toDetail(definition);
        WorkflowDraftApi.WorkflowPublishValidationResult result = workflowPublishValidator.validate(detail.nodes(), detail.edges());
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
        WorkflowDefinitionEntity definition = findDefinition(tenantId, workflowId);
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

        WorkflowDraftApi.WorkflowDraftDetail detail = toDetail(definition);
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
            definition.getPausePointCount(),
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
        return new WorkflowDraftApi.WorkflowPublishResult(toDraftRow(definition, loadUsersById()), nextVersionNumber, now);
    }

    @Transactional
    public WorkflowDraftApi.WorkflowDraftDetail saveGraph(
        UUID tenantId,
        UUID operatorUserId,
        UUID workflowId,
        WorkflowDraftApi.SaveWorkflowDraftGraphRequest request
    ) {
        WorkflowDefinitionEntity definition = findDefinition(tenantId, workflowId);
        List<WorkflowDraftApi.WorkflowNodeDraft> nodes = request.nodes() == null ? List.of() : request.nodes();
        List<WorkflowDraftApi.WorkflowEdgeDraft> edges = request.edges() == null ? List.of() : request.edges();
        List<WorkflowDraftApi.WorkflowVariableDraft> variables = request.variables() == null ? List.of() : request.variables();
        validateGraph(tenantId, workflowId, nodes, edges);
        workflowVariableDeclarationValidator.validate(nodes, variables);

        Instant now = clock.instant();
        workflowNodeDefinitionRepository.deleteByWorkflowId(workflowId);
        workflowEdgeDefinitionRepository.deleteByWorkflowId(workflowId);
        workflowVariableDefinitionRepository.deleteByWorkflowId(workflowId);

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
                writeJsonArray(node.inputVariables()),
                writeJsonArray(node.outputVariables()),
                writeJsonObject(node.config()),
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

        definition.updateGraphSummary(nodes.size(), countPausePoints(nodes), operatorUserId, now);
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
        return toDetail(definition);
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

    private WorkflowDefinitionEntity findDefinition(UUID tenantId, UUID workflowId) {
        ensureActiveTenant(tenantId);
        return workflowDefinitionRepository.findByIdAndTenantId(workflowId, tenantId)
            .orElseThrow(() -> {
                log.warn("工作流草稿查询失败：草稿不存在 tenantId={} workflowId={} requestId={}", tenantId, workflowId, RequestIds.current());
                return new ApiException(HttpStatus.NOT_FOUND, "WORKFLOW_DRAFT_NOT_FOUND", "工作流草稿不存在");
            });
    }

    private void ensureActiveTenant(UUID tenantId) {
        tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> {
                log.warn("工作流草稿访问失败：租户不可用 tenantId={} requestId={}", tenantId, RequestIds.current());
                return new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "租户不存在或已停用");
            });
    }

    private WorkflowDraftApi.WorkflowDraftDetail toDetail(WorkflowDefinitionEntity definition) {
        return new WorkflowDraftApi.WorkflowDraftDetail(
            toDraftRow(definition, loadUsersById()),
            workflowNodeDefinitionRepository.findByWorkflowIdOrderBySortOrderAsc(definition.getId()).stream().map(this::toNodeRow).toList(),
            workflowEdgeDefinitionRepository.findByWorkflowIdOrderBySortOrderAsc(definition.getId()).stream().map(this::toEdgeRow).toList(),
            workflowVariableDefinitionRepository.findByWorkflowIdOrderBySortOrderAsc(definition.getId()).stream().map(this::toVariableRow).toList()
        );
    }

    private WorkflowDraftApi.WorkflowDraftRow toDraftRow(WorkflowDefinitionEntity definition, Map<UUID, UserAccount> usersById) {
        UserAccount owner = definition.getCreatedBy() == null ? null : usersById.get(definition.getCreatedBy());
        return new WorkflowDraftApi.WorkflowDraftRow(
            definition.getId(),
            definition.getTenantId(),
            definition.getName(),
            definition.getDescription() == null ? "" : definition.getDescription(),
            definition.getStatus(),
            definition.getNodeCount(),
            definition.getPausePointCount(),
            owner == null ? "未知用户" : owner.getDisplayName(),
            definition.getUpdatedAt()
        );
    }

    private WorkflowDraftApi.WorkflowNodeRow toNodeRow(WorkflowNodeDefinitionEntity node) {
        return new WorkflowDraftApi.WorkflowNodeRow(
            node.getNodeKey(),
            node.getNodeType(),
            node.getName(),
            node.getPositionX().doubleValue(),
            node.getPositionY().doubleValue(),
            readJsonArray(node.getInputVariables()),
            readJsonArray(node.getOutputVariables()),
            readJsonObject(node.getConfig())
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

    private Map<UUID, UserAccount> loadUsersById() {
        return userAccountRepository.findAll().stream().collect(Collectors.toMap(UserAccount::getId, Function.identity()));
    }

    private int countPausePoints(List<WorkflowDraftApi.WorkflowNodeDraft> nodes) {
        return (int) nodes.stream().filter(node -> PAUSE_NODE_TYPES.contains(normalizeRequired(node.nodeType()))).count();
    }

    private String writeJsonArray(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            log.error("工作流节点变量序列化失败 requestId={}", RequestIds.current(), exception);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SYSTEM_JSON_SERIALIZE_FAILED", "系统暂时无法保存节点变量");
        }
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

    private List<String> readJsonArray(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException exception) {
            log.warn("工作流节点变量解析失败 requestId={}", RequestIds.current());
            return List.of();
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

    private record WorkflowVersionSnapshot(
        String name,
        String description,
        List<WorkflowDraftApi.WorkflowNodeRow> nodes,
        List<WorkflowDraftApi.WorkflowEdgeRow> edges,
        List<WorkflowDraftApi.WorkflowVariableRow> variables
    ) {
    }
}
