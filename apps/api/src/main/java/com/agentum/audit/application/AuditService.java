package com.agentum.audit.application;

import com.agentum.agent.domain.ModelCallLogEntity;
import com.agentum.agent.application.TokenUsage;
import com.agentum.agent.infrastructure.ModelCallLogRepository;
import com.agentum.audit.domain.AuditLogEntity;
import com.agentum.audit.infrastructure.AuditLogRepository;
import com.agentum.auth.domain.UserAccount;
import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.delivery.domain.DeliveryRecordEntity;
import com.agentum.delivery.infrastructure.DeliveryRecordRepository;
import com.agentum.mcp.domain.McpCallLogEntity;
import com.agentum.mcp.infrastructure.McpCallLogRepository;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.pagination.PageQuery;
import com.agentum.shared.pagination.PageResponse;
import com.agentum.shared.pagination.PageableFactory;
import com.agentum.shared.pagination.SortWhitelist;
import com.agentum.shared.util.AuditMasker;
import com.agentum.workflow.domain.WorkflowNodeRunEntity;
import com.agentum.workflow.domain.WorkflowRunEntity;
import com.agentum.workflow.domain.WorkflowRunEventEntity;
import com.agentum.workflow.domain.WorkflowVariableSnapshotEntity;
import com.agentum.workflow.infrastructure.WorkflowNodeRunRepository;
import com.agentum.workflow.infrastructure.WorkflowRunEventRepository;
import com.agentum.workflow.infrastructure.WorkflowRunRepository;
import com.agentum.workflow.infrastructure.WorkflowVariableSnapshotRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运行审计与配置改动审计服务类。
 */
@Service
@Transactional(readOnly = true)
public class AuditService {

    private static final SortWhitelist RUN_SORT = SortWhitelist.of("startedAt", "completedAt", "title", "workflowName", "state");
    private static final SortWhitelist TOOL_SORT = SortWhitelist.of("createdAt", "latencyMs", "toolName", "status");
    private static final SortWhitelist OP_SORT = SortWhitelist.of("createdAt", "actionType", "operatorName");

    private static final ObjectMapper mapper = new ObjectMapper();

    private final AuditLogRepository auditLogRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowNodeRunRepository workflowNodeRunRepository;
    private final WorkflowRunEventRepository workflowRunEventRepository;
    private final WorkflowVariableSnapshotRepository variableSnapshotRepository;
    private final McpCallLogRepository mcpCallLogRepository;
    private final ModelCallLogRepository modelCallLogRepository;
    private final DeliveryRecordRepository deliveryRecordRepository;
    private final UserAccountRepository userAccountRepository;

    public AuditService(
        AuditLogRepository auditLogRepository,
        WorkflowRunRepository workflowRunRepository,
        WorkflowNodeRunRepository workflowNodeRunRepository,
        WorkflowRunEventRepository workflowRunEventRepository,
        WorkflowVariableSnapshotRepository variableSnapshotRepository,
        McpCallLogRepository mcpCallLogRepository,
        ModelCallLogRepository modelCallLogRepository,
        DeliveryRecordRepository deliveryRecordRepository,
        UserAccountRepository userAccountRepository
    ) {
        this.auditLogRepository = auditLogRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.workflowNodeRunRepository = workflowNodeRunRepository;
        this.workflowRunEventRepository = workflowRunEventRepository;
        this.variableSnapshotRepository = variableSnapshotRepository;
        this.mcpCallLogRepository = mcpCallLogRepository;
        this.modelCallLogRepository = modelCallLogRepository;
        this.deliveryRecordRepository = deliveryRecordRepository;
        this.userAccountRepository = userAccountRepository;
    }

    /**
     * 查询运行审计列表 (分页)
     */
    public PageResponse<AuditRunSummaryDto> getRunAuditList(
        UUID tenantId,
        PageQuery pageQuery,
        String keyword,
        String state,
        String triggerSource
    ) {
        Pageable pageable = PageableFactory.from(pageQuery, RUN_SORT);
        Page<WorkflowRunEntity> page = workflowRunRepository.searchAllRunsForAudit(
            tenantId,
            keyword != null ? keyword.trim() : "",
            state != null ? state.trim() : "",
            normalizeTriggerSource(triggerSource),
            pageable
        );

        // 获取该页所有操作人名字，避免 N+1
        Set<UUID> userIds = page.getContent().stream()
            .map(WorkflowRunEntity::getCreatedBy)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
        Map<UUID, String> operatorNames = getOperatorNames(userIds);

        Page<AuditRunSummaryDto> dtoPage = page.map(entity -> new AuditRunSummaryDto(
            entity.getId(),
            entity.getTitle(),
            entity.getWorkflowName(),
            entity.getWorkflowVersionNumber(),
            entity.getTriggerSource(),
            entity.getTriggerScheduleId(),
            entity.getState(),
            entity.getStartedAt(),
            entity.getCompletedAt(),
            operatorNames.getOrDefault(entity.getCreatedBy(), "System")
        ));

        return PageResponse.from(dtoPage);
    }

    /**
     * 聚合全链路运行审计证据链详情 (只读证据链)
     */
    public AuditEvidenceDto getRunEvidence(UUID tenantId, UUID runId) {
        WorkflowRunEntity run = workflowRunRepository.findByIdAndTenantId(runId, tenantId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AUDIT_RUN_NOT_FOUND", "未找到指定的工作流运行记录"));

        // 1. 获取关联的节点运行记录 (按顺序排列)
        List<WorkflowNodeRunEntity> nodeRunEntities = workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(runId);

        // 2. 获取运行事件轨迹
        List<WorkflowRunEventEntity> eventEntities = workflowRunEventRepository.findByRunIdOrderByEventTimeAsc(runId);

        // 3. 获取所有变量快照
        List<WorkflowVariableSnapshotEntity> varSnapshots = variableSnapshotRepository.findByRunIdOrderByCreatedAtAsc(runId);

        // 提取租户敏感的变量名和变量明文值，用于全证据链脱敏
        Set<String> sensitiveKeys = new HashSet<>();
        Set<String> sensitivePlainValues = new HashSet<>();

        for (WorkflowVariableSnapshotEntity var : varSnapshots) {
            if (var.isSensitive()) {
                sensitiveKeys.add(var.getVariableName());
                if (var.getValueSnapshot() != null && var.getValueSnapshot().containsKey("value")) {
                    Object valueObj = var.getValueSnapshot().get("value");
                    if (valueObj != null) {
                        String plainVal = valueObj.toString();
                        if (plainVal.length() > 3) { // 避免脱敏 ok、0 等无意义词
                            sensitivePlainValues.add(plainVal);
                        }
                    }
                }
            }
        }

        // 4. 构建脱敏后的变量快照列表
        List<AuditEvidenceDto.VariableSnapshotInfo> variableSnapshotInfos = varSnapshots.stream()
            .map(entity -> {
                Object displayValue = "******";
                if (!entity.isSensitive()) {
                    displayValue = entity.getValueSnapshot() != null ? entity.getValueSnapshot().get("value") : null;
                }
                return new AuditEvidenceDto.VariableSnapshotInfo(
                    entity.getId(),
                    entity.getNodeRunId(),
                    entity.getVariableName(),
                    entity.getValueType(),
                    displayValue,
                    entity.getSourceNodeKey(),
                    entity.isSensitive(),
                    entity.isDeliveryVisible(),
                    entity.getCreatedAt()
                );
            })
            .collect(Collectors.toList());

        // 5. 转换节点运行信息 (输入/输出根据敏感属性做脱敏)
        List<AuditEvidenceDto.NodeRunInfo> nodeRunInfos = nodeRunEntities.stream()
            .map(node -> new AuditEvidenceDto.NodeRunInfo(
                node.getId(),
                node.getNodeKey(),
                node.getNodeType(),
                node.getName(),
                node.getState(),
                node.getStateLabel(),
                maskMap(node.getInputSnapshot(), sensitiveKeys, sensitivePlainValues),
                maskMap(node.getOutputSnapshot(), sensitiveKeys, sensitivePlainValues),
                maskMap(node.getConfigSnapshot(), sensitiveKeys, sensitivePlainValues),
                node.getStartedAt(),
                node.getCompletedAt()
            ))
            .collect(Collectors.toList());

        // 6. 获取操作人名称并转换轨迹事件
        Set<UUID> operatorIds = eventEntities.stream()
            .map(WorkflowRunEventEntity::getOperatorId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
        Map<UUID, String> operatorNames = getOperatorNames(operatorIds);

        List<AuditEvidenceDto.RunEventInfo> runEventInfos = eventEntities.stream()
            .map(event -> new AuditEvidenceDto.RunEventInfo(
                event.getId(),
                event.getEventType(),
                event.getTitle(),
                event.getDescription(),
                event.getNodeKey(),
                operatorNames.getOrDefault(event.getOperatorId(), "System"),
                event.getEventTime()
            ))
            .collect(Collectors.toList());

        // 7. 获取并脱敏模型调用日志
        List<ModelCallLogEntity> modelLogs = modelCallLogRepository.findByRunIdOrderByCreatedAtDesc(runId);
        List<AuditEvidenceDto.ModelCallLogInfo> modelCallLogInfos = modelLogs.stream()
            .map(log -> new AuditEvidenceDto.ModelCallLogInfo(
                log.getId(),
                log.getNodeRunId(),
                log.getModelName(),
                log.getStatus(),
                maskMap(log.getPromptSnapshot(), sensitiveKeys, sensitivePlainValues),
                maskMap(log.getResponseSnapshot(), sensitiveKeys, sensitivePlainValues),
                log.getNormalizedTokenUsage(),
                log.getLatencyMs(),
                log.getCreatedAt(),
                log.getCompletedAt()
            ))
            .collect(Collectors.toList());

        // 8. 获取并脱敏 MCP 调用日志
        List<McpCallLogEntity> mcpLogs = mcpCallLogRepository.findByRunIdOrderByCreatedAtDesc(runId);
        List<AuditEvidenceDto.McpCallLogInfo> mcpCallLogInfos = mcpLogs.stream()
            .map(log -> new AuditEvidenceDto.McpCallLogInfo(
                log.getId(),
                log.getNodeRunId(),
                log.getToolName(),
                log.getCapabilityCode(),
                log.getStatus(),
                maskMap(log.getRequestPayload(), sensitiveKeys, sensitivePlainValues),
                maskMap(log.getResponsePayload(), sensitiveKeys, sensitivePlainValues),
                log.getLatencyMs(),
                log.getCreatedAt(),
                log.getCompletedAt()
            ))
            .collect(Collectors.toList());

        // 9. 获取并脱敏交付日志
        List<DeliveryRecordEntity> deliveryLogs = deliveryRecordRepository.findByRunIdOrderByCreatedAtDesc(runId);
        List<AuditEvidenceDto.DeliveryRecordInfo> deliveryRecordInfos = deliveryLogs.stream()
            .map(log -> new AuditEvidenceDto.DeliveryRecordInfo(
                log.getId(),
                log.getNodeRunId(),
                log.getDeliveryType(),
                log.getTarget(),
                log.getTitle(),
                log.getStatus(),
                maskMap(log.getPayload(), sensitiveKeys, sensitivePlainValues),
                maskMap(log.getResultSnapshot(), sensitiveKeys, sensitivePlainValues),
                log.getErrorMessage(),
                log.getCreatedAt(),
                log.getCompletedAt()
            ))
            .collect(Collectors.toList());

        TokenUsage runTokenUsage = modelLogs.stream()
            .map(ModelCallLogEntity::getNormalizedTokenUsage)
            .reduce(TokenUsage.empty(), TokenUsage::plus);

        // 组装返回数据只读证据链
        return new AuditEvidenceDto(
            new AuditEvidenceDto.WorkflowRunInfo(
                run.getId(),
                run.getTitle(),
                run.getWorkflowName(),
                run.getWorkflowVersionNumber(),
                run.getTriggerSource(),
                run.getTriggerScheduleId(),
                maskMap(run.getTriggerPayload(), sensitiveKeys, sensitivePlainValues),
                run.getState(),
                run.getStartedAt(),
                run.getCompletedAt(),
                operatorNames.getOrDefault(run.getCreatedBy(), "System")
            ),
            runTokenUsage,
            nodeRunInfos,
            variableSnapshotInfos,
            runEventInfos,
            modelCallLogInfos,
            mcpCallLogInfos,
            deliveryRecordInfos
        );
    }

    /**
     * 查询工具及大模型调用审计台账 (分页)
     */
    public PageResponse<AuditToolCallDto> getToolCallAuditList(
        UUID tenantId,
        PageQuery pageQuery,
        String toolType, // "mcp" | "model"
        String status,
        String keyword
    ) {
        Pageable pageable = PageableFactory.from(pageQuery, TOOL_SORT);
        String filterStatus = status != null ? status.trim() : "";
        String filterKeyword = keyword != null ? keyword.trim() : "";

        // 如果明确要查 model
        if ("model".equalsIgnoreCase(toolType)) {
            Page<ModelCallLogEntity> modelLogs = modelCallLogRepository.findWithFilters(tenantId, filterStatus, filterKeyword, pageable);
            
            // 获取运行的标题，用作展示
            Set<UUID> runIds = modelLogs.getContent().stream().map(ModelCallLogEntity::getRunId).collect(Collectors.toSet());
            Map<UUID, String> runTitles = getRunTitles(runIds);

            Page<AuditToolCallDto> dtos = modelLogs.map(log -> new AuditToolCallDto(
                log.getId(),
                log.getRunId(),
                log.getNodeRunId(),
                "MODEL",
                log.getModelName(),
                log.getStatus(),
                log.getLatencyMs(),
                log.getCreatedAt(),
                runTitles.getOrDefault(log.getRunId(), "Unknown Run"),
                log.getPromptSnapshot(),
                log.getResponseSnapshot(),
                log.getErrorMessage(),
                log.getNormalizedTokenUsage()
            ));
            return PageResponse.from(dtos);
        }

        // 默认或者查 mcp
        Page<McpCallLogEntity> mcpLogs = mcpCallLogRepository.findWithFilters(tenantId, filterStatus, filterKeyword, pageable);
        
        Set<UUID> runIds = mcpLogs.getContent().stream().map(McpCallLogEntity::getRunId).collect(Collectors.toSet());
        Map<UUID, String> runTitles = getRunTitles(runIds);

        Page<AuditToolCallDto> dtos = mcpLogs.map(log -> new AuditToolCallDto(
            log.getId(),
            log.getRunId(),
            log.getNodeRunId(),
            "MCP",
            log.getToolName(),
            log.getStatus(),
            log.getLatencyMs(),
            log.getCreatedAt(),
            runTitles.getOrDefault(log.getRunId(), "Unknown Run"),
            log.getRequestPayload(),
            log.getResponsePayload(),
            log.getErrorMessage(),
            null
        ));
        return PageResponse.from(dtos);
    }

    /**
     * 查询管理和配置变动操作审计日志 (分页)
     */
    public PageResponse<AuditOperationLogDto> getOperationLogs(
        UUID tenantId,
        PageQuery pageQuery,
        String actionType,
        UUID operatorId
    ) {
        Pageable pageable = PageableFactory.from(pageQuery, OP_SORT);
        String filterAction = actionType != null && !actionType.trim().isEmpty() ? actionType.trim() : null;

        Page<AuditLogEntity> logs = auditLogRepository.findWithFilters(tenantId, filterAction, operatorId, pageable);

        Page<AuditOperationLogDto> dtos = logs.map(entity -> new AuditOperationLogDto(
            entity.getId(),
            entity.getOperatorName(),
            entity.getActionType(),
            entity.getTargetType(),
            entity.getTargetName(),
            entity.getDescription(),
            entity.getPayload(),
            entity.getClientIp(),
            entity.getCreatedAt()
        ));

        return PageResponse.from(dtos);
    }

    /**
     * 写操作审计日志的通用方法（由其他业务模块在关键变动时显式调用）
     */
    @Transactional
    public void recordOperationLog(
        UUID tenantId,
        UUID operatorId,
        String operatorName,
        String actionType,
        String targetType,
        String targetId,
        String targetName,
        String description,
        Map<String, Object> payload,
        String clientIp
    ) {
        String payloadJson = "{}";
        if (payload != null) {
            try {
                payloadJson = mapper.writeValueAsString(payload);
            } catch (Exception e) {
                // 忽略转换错误
            }
        }
        AuditLogEntity log = AuditLogEntity.record(
            tenantId, operatorId, operatorName, actionType, targetType, targetId, targetName, description, payloadJson, clientIp
        );
        auditLogRepository.save(log);
    }

    // Helper 方法：获取用户名映射，避免 N+1
    private Map<UUID, String> getOperatorNames(Set<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<UserAccount> users = userAccountRepository.findAllById(userIds);
        return users.stream().collect(Collectors.toMap(
            UserAccount::getId,
            u -> u.getDisplayName() != null ? u.getDisplayName() : u.getUsername(),
            (v1, v2) -> v1
        ));
    }

    // Helper 方法：获取运行实例名称映射，避免 N+1
    private Map<UUID, String> getRunTitles(Set<UUID> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<WorkflowRunEntity> runs = workflowRunRepository.findAllById(runIds);
        return runs.stream().collect(Collectors.toMap(
            WorkflowRunEntity::getId,
            WorkflowRunEntity::getTitle,
            (v1, v2) -> v1
        ));
    }

    private String normalizeTriggerSource(String triggerSource) {
        if ("manual".equals(triggerSource) || "schedule".equals(triggerSource)) {
            return triggerSource;
        }
        return "";
    }

    // 辅助转换 Map 的脱敏逻辑
    private Map<String, Object> maskMap(Map<String, Object> map, Set<String> sensitiveKeys, Set<String> sensitivePlainValues) {
        if (map == null || map.isEmpty()) {
            return map;
        }
        try {
            String json = mapper.writeValueAsString(map);
            // 1. JSON-key 的结构化脱敏
            String masked = AuditMasker.maskJson(json, sensitiveKeys, sensitivePlainValues);
            // 2. 文本值的非结构化脱敏
            masked = AuditMasker.maskText(masked, sensitivePlainValues);
            return mapper.readValue(masked, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return map;
        }
    }
}
