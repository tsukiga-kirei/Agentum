package com.agentum.workflow.application;

import com.agentum.agent.application.AgentRuntimeProperties;
import com.agentum.asset.application.AssetManagementService;
import com.agentum.system.domain.SystemCapabilityEntity;
import com.agentum.system.domain.TenantCapabilityGrantEntity;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import com.agentum.system.infrastructure.TenantCapabilityGrantRepository;
import com.agentum.workflow.interfaces.WorkflowDraftApi;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 保存和发布校验：验证流程节点引用的系统能力与租户自建能力已发布，并且当前编辑者仍有读取权限。
 * 这是协作编辑的安全边界，避免流程创建者授权编辑后，协作者通过流程间接使用未向其开放的能力。
 */
@Component
public class WorkflowNodeConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(WorkflowNodeConfigValidator.class);
    private static final Set<String> SENTINEL_VALUES = Set.of("custom", "none", "");
    private static final String ACTIVE_STATUS = "active";
    /** 智能体集群支持的执行方式枚举，后续扩展新策略时在此追加。 */
    private static final Set<String> CLUSTER_EXECUTION_MODES = Set.of("parallel", "sequential");

    private final SystemCapabilityRepository systemCapabilityRepository;
    private final TenantCapabilityGrantRepository tenantCapabilityGrantRepository;
    private final AssetManagementService assetManagementService;
    private final AgentRuntimeProperties agentRuntimeProperties;

    public WorkflowNodeConfigValidator(
        SystemCapabilityRepository systemCapabilityRepository,
        TenantCapabilityGrantRepository tenantCapabilityGrantRepository,
        AssetManagementService assetManagementService,
        AgentRuntimeProperties agentRuntimeProperties
    ) {
        this.systemCapabilityRepository = systemCapabilityRepository;
        this.tenantCapabilityGrantRepository = tenantCapabilityGrantRepository;
        this.assetManagementService = assetManagementService;
        this.agentRuntimeProperties = agentRuntimeProperties;
    }

    public List<WorkflowDraftApi.WorkflowValidationIssue> validateCapabilityReferences(
        UUID tenantId,
        UUID operatorUserId,
        List<WorkflowDraftApi.WorkflowNodeRow> nodes
    ) {
        // 加载租户能力池：已启用的系统能力授权
        Map<UUID, SystemCapabilityEntity> poolCapabilities = loadTenantCapabilityPool(tenantId);
        List<WorkflowDraftApi.WorkflowValidationIssue> issues = new ArrayList<>();

        for (WorkflowDraftApi.WorkflowNodeRow node : nodes) {
            Map<String, Object> config = node.config();
            if (config == null || config.isEmpty()) {
                continue;
            }

            String nodeType = node.nodeType();
            if ("user_input".equals(nodeType)) {
                validateInputNodeConfig(config, node, issues);
            } else if ("agent".equals(nodeType)) {
                validateTenantAssetId(tenantId, operatorUserId, extractString(config, "agentAssetId"), "agent_template", "智能体模板", node, issues);
                validateTenantAssetId(tenantId, operatorUserId, extractString(config, "systemPromptTemplateId"), "prompt_template", "系统提示词模板", node, issues);
                validateTenantAssetId(tenantId, operatorUserId, extractString(config, "promptTemplateId"), "prompt_template", "系统提示词模板", node, issues);
                validateTenantAssetId(tenantId, operatorUserId, extractString(config, "userPromptTemplateId"), "prompt_template", "用户提示词模板", node, issues);
                validateAgentPromptConfiguration(config, node, "节点[" + node.name() + "]", issues);
                validateAgentIterationLimit(config, node, "节点[" + node.name() + "]", issues);
                validateIds(tenantId, operatorUserId, extractStringList(config, "mcpIds", "mcpServices"), "mcp", "MCP", node, poolCapabilities, issues);
                validateIds(tenantId, operatorUserId, extractStringList(config, "skillIds", "skills"), "skill", "Skill", node, poolCapabilities, issues);
            } else if ("parallel_group".equals(nodeType)) {
                validateClusterNodeConfig(config, node, issues);
                String executionMode = extractString(config, "executionMode");
                if (executionMode != null && !CLUSTER_EXECUTION_MODES.contains(executionMode)) {
                    issues.add(issue(
                        "WORKFLOW_VALIDATION_EXECUTION_MODE_INVALID",
                        "节点[" + node.name() + "]的执行方式不合法，仅支持并行执行（parallel）或顺序执行（sequential）",
                        node
                    ));
                }
                List<Map<String, Object>> agents = extractMapList(config, "clusterAgents");
                for (int index = 0; index < agents.size(); index++) {
                    Map<String, Object> agent = agents.get(index);
                    String agentName = rawString(agent.get("name"));
                    if (agentName.isBlank()) {
                        agentName = "子智能体 " + (index + 1);
                    }
                    String subject = "节点[" + node.name() + "]的子智能体「" + agentName + "」";
                    validateTenantAssetId(tenantId, operatorUserId, extractString(agent, "agentAssetId"), "agent_template", "智能体模板", node, issues);
                    validateTenantAssetId(tenantId, operatorUserId, extractString(agent, "systemPromptTemplateId"), "prompt_template", "系统提示词模板", node, issues);
                    validateTenantAssetId(tenantId, operatorUserId, extractString(agent, "promptTemplateId"), "prompt_template", "系统提示词模板", node, issues);
                    validateTenantAssetId(tenantId, operatorUserId, extractString(agent, "userPromptTemplateId"), "prompt_template", "用户提示词模板", node, issues);
                    validateAgentPromptConfiguration(agent, node, subject, issues);
                    validateAgentIterationLimit(agent, node, subject, issues);
                    validateIds(tenantId, operatorUserId, extractStringList(agent, "mcpIds", "mcpServices"), "mcp", "MCP", node, poolCapabilities, issues);
                    validateIds(tenantId, operatorUserId, extractStringList(agent, "skillIds", "skills"), "skill", "Skill", node, poolCapabilities, issues);
                }
            } else if ("delivery".equals(nodeType)) {
                String deliveryMode = rawString(config.get("deliveryMode"));
                if ("direct".equalsIgnoreCase(deliveryMode) || "direct".equalsIgnoreCase(rawString(config.get("deliveryType")))) {
                    String deliveryContent = rawString(config.get("deliveryContent"));
                    if (deliveryContent.isBlank()) {
                        deliveryContent = rawString(config.get("deliveryTarget"));
                    }
                    if (deliveryContent.isBlank()) {
                        deliveryContent = rawString(config.get("body"));
                    }
                    if (deliveryContent.isBlank()) {
                        issues.add(issue(
                            "WORKFLOW_VALIDATION_DELIVERY_DIRECT_CONTENT_REQUIRED",
                            "节点[" + node.name() + "]必须配置直接交付内容模板",
                            node
                        ));
                    }
                } else {
                    String deliveryId = extractString(config, "deliveryCapabilityId");
                    if (deliveryId == null) {
                        issues.add(issue("WORKFLOW_VALIDATION_DELIVERY_CAPABILITY_REQUIRED", "节点[" + node.name() + "]必须选择交付能力", node));
                    } else {
                        validateIds(tenantId, operatorUserId, List.of(deliveryId), "delivery", "交付能力", node, poolCapabilities, issues);
                    }
                    String deliveryType = rawString(config.get("deliveryType"));
                    String documentKind = rawString(config.get("documentKind"));
                    if ("word_document".equals(deliveryType) || "word".equals(documentKind)) {
                        String markdownContent = rawString(config.get("markdownContent"));
                        if (markdownContent.isBlank()) {
                            issues.add(issue("WORKFLOW_VALIDATION_DELIVERY_MARKDOWN_TEMPLATE_REQUIRED", "节点[" + node.name() + "]必须配置 Word 交付正文模板", node));
                        }
                    }
                }
            }
        }

        return issues;
    }

    private void validateInputNodeConfig(
        Map<String, Object> config,
        WorkflowDraftApi.WorkflowNodeRow node,
        List<WorkflowDraftApi.WorkflowValidationIssue> issues
    ) {
        List<Map<String, Object>> fields = extractMapList(config, "inputFields");
        if (fields.isEmpty()) {
            issues.add(issue("WORKFLOW_VALIDATION_INPUT_FIELDS_REQUIRED", "节点[" + node.name() + "]至少需要配置一个输入字段", node));
            return;
        }
        Set<String> fieldVariables = new LinkedHashSet<>();
        for (Map<String, Object> field : fields) {
            String variable = rawString(field.get("variable"));
            if (!isValidVariableName(variable)) {
                issues.add(issue("WORKFLOW_VALIDATION_INPUT_FIELD_VARIABLE_INVALID", "节点[" + node.name() + "]的输入字段变量名不合法：" + variable, node));
                continue;
            }
            fieldVariables.add(variable);
        }
        Set<String> declaredOutputs = variableSet(node.outputVariables());
        if (!fieldVariables.equals(declaredOutputs)) {
            issues.add(issue("WORKFLOW_VALIDATION_INPUT_OUTPUT_MISMATCH", "节点[" + node.name() + "]的输入字段变量必须与节点输出变量保持一致", node));
        }
    }

    private void validateClusterNodeConfig(
        Map<String, Object> config,
        WorkflowDraftApi.WorkflowNodeRow node,
        List<WorkflowDraftApi.WorkflowValidationIssue> issues
    ) {
        List<Map<String, Object>> agents = extractMapList(config, "clusterAgents");
        if (agents.isEmpty()) {
            issues.add(issue("WORKFLOW_VALIDATION_CLUSTER_AGENTS_REQUIRED", "节点[" + node.name() + "]至少需要配置一个子智能体", node));
            return;
        }
        Set<String> agentOutputs = new LinkedHashSet<>();
        for (int index = 0; index < agents.size(); index++) {
            String output = rawString(agents.get(index).get("output"));
            if (!isValidVariableName(output)) {
                issues.add(issue(
                    "WORKFLOW_VALIDATION_CLUSTER_AGENT_OUTPUT_INVALID",
                    "节点[" + node.name() + "]的第 " + (index + 1) + " 个子智能体输出变量名不合法：" + output,
                    node
                ));
                continue;
            }
            if (!agentOutputs.add(output)) {
                issues.add(issue("WORKFLOW_VALIDATION_CLUSTER_AGENT_OUTPUT_DUPLICATED", "节点[" + node.name() + "]存在重复的子智能体输出变量：" + output, node));
            }
        }
        Set<String> declaredOutputs = variableSet(node.outputVariables());
        if (!agentOutputs.equals(declaredOutputs)) {
            issues.add(issue("WORKFLOW_VALIDATION_CLUSTER_OUTPUT_MISMATCH", "节点[" + node.name() + "]的子智能体输出必须与节点输出变量保持一致", node));
        }
    }

    private void validateTenantAssetId(
        UUID tenantId,
        UUID operatorUserId,
        String id,
        String expectedType,
        String typeLabel,
        WorkflowDraftApi.WorkflowNodeRow node,
        List<WorkflowDraftApi.WorkflowValidationIssue> issues
    ) {
        if (id == null) {
            return;
        }
        try {
            if (!assetManagementService.canUseTenantAssetReference(tenantId, operatorUserId, UUID.fromString(id), expectedType)) {
                issues.add(issue(
                    "WORKFLOW_VALIDATION_TENANT_ASSET_NOT_AVAILABLE",
                    "节点[" + node.name() + "]引用的" + typeLabel + "未发布或当前编辑者没有读取权限",
                    node
                ));
            }
        } catch (IllegalArgumentException exception) {
            issues.add(issue("WORKFLOW_VALIDATION_TENANT_ASSET_ID_INVALID", "节点[" + node.name() + "]引用的" + typeLabel + "标识不合法", node));
        }
    }

    // ---- 内部方法 ----

    /**
     * 加载租户已启用的系统能力池，返回 capabilityId -> SystemCapabilityEntity 映射。
     * 只保留系统端状态为 active 的能力，避免已下架能力通过旧授权记录渗漏。
     */
    private Map<UUID, SystemCapabilityEntity> loadTenantCapabilityPool(UUID tenantId) {
        List<TenantCapabilityGrantEntity> enabledGrants = tenantCapabilityGrantRepository
            .findByTenantIdOrderByCreatedAtDesc(tenantId)
            .stream()
            .filter(grant -> "enabled".equals(grant.getStatus()))
            .toList();

        if (enabledGrants.isEmpty()) {
            return Map.of();
        }

        Set<UUID> grantedCapabilityIds = enabledGrants.stream()
            .map(TenantCapabilityGrantEntity::getCapabilityId)
            .collect(Collectors.toSet());

        return systemCapabilityRepository.findAllById(grantedCapabilityIds)
            .stream()
            .filter(cap -> ACTIVE_STATUS.equals(cap.getStatus()))
            .collect(Collectors.toMap(SystemCapabilityEntity::getId, Function.identity()));
    }

    /**
     * 校验一组能力 ID 是否在租户能力池中且类型匹配。
     */
    private void validateIds(
        UUID tenantId,
        UUID operatorUserId,
        List<String> ids,
        String expectedType,
        String typeLabel,
        WorkflowDraftApi.WorkflowNodeRow node,
        Map<UUID, SystemCapabilityEntity> poolCapabilities,
        List<WorkflowDraftApi.WorkflowValidationIssue> issues
    ) {
        for (String idStr : ids) {
            UUID capabilityId;
            try {
                capabilityId = UUID.fromString(idStr);
            } catch (IllegalArgumentException e) {
                issues.add(issue(
                    "WORKFLOW_VALIDATION_CAPABILITY_ID_INVALID",
                    "节点[" + node.name() + "]引用的 " + typeLabel + " ID 格式非法：" + idStr,
                    node
                ));
                continue;
            }

            SystemCapabilityEntity capability = poolCapabilities.get(capabilityId);
            if (capability == null) {
                issues.add(issue(
                    "WORKFLOW_VALIDATION_CAPABILITY_NOT_IN_POOL",
                    "节点[" + node.name() + "]引用的 " + typeLabel + " 不在当前租户能力池中或已失效",
                    node
                ));
            } else if (!expectedType.equals(capability.getCapabilityType())) {
                issues.add(issue(
                    "WORKFLOW_VALIDATION_CAPABILITY_TYPE_MISMATCH",
                    "节点[" + node.name() + "]引用的能力类型不匹配，期望 " + typeLabel + " 但实际为 " + capability.getCapabilityType(),
                    node
                ));
            } else if (!assetManagementService.canUseSystemCapabilityReference(tenantId, operatorUserId, capabilityId, expectedType)) {
                issues.add(issue(
                    "WORKFLOW_VALIDATION_CAPABILITY_NOT_ASSIGNED",
                    "节点[" + node.name() + "]引用的 " + typeLabel + " 未分配给当前编辑者",
                    node
                ));
            }
        }
    }

    /**
     * 自定义提示词模式下必须填写正文；选择模板时由模板资产提供正文。
     */
    private void validateAgentPromptConfiguration(
        Map<String, Object> config,
        WorkflowDraftApi.WorkflowNodeRow node,
        String subject,
        List<WorkflowDraftApi.WorkflowValidationIssue> issues
    ) {
        if (resolveTemplateReference(config, "systemPromptTemplateId", "promptTemplateId") == null
            && rawString(config.get("systemPrompt")).isBlank()) {
            issues.add(issue(
                "WORKFLOW_VALIDATION_SYSTEM_PROMPT_REQUIRED",
                subject + "的系统提示词为自定义时必须填写正文，或选择系统提示词模板",
                node
            ));
        }
        if (resolveTemplateReference(config, "userPromptTemplateId") == null
            && rawString(config.get("userPrompt")).isBlank()) {
            issues.add(issue(
                "WORKFLOW_VALIDATION_USER_PROMPT_REQUIRED",
                subject + "的用户提示词为自定义时必须填写正文，或选择用户提示词模板",
                node
            ));
        }
    }

    private void validateAgentIterationLimit(
        Map<String, Object> config,
        WorkflowDraftApi.WorkflowNodeRow node,
        String subject,
        List<WorkflowDraftApi.WorkflowValidationIssue> issues
    ) {
        Object rawValue = config.get("maxAgentIterationsPerTurn");
        int value = parsePositiveInteger(rawValue);
        int maximum = Math.max(1, agentRuntimeProperties.getMaxIterationsPerTurn());
        if (rawValue == null) {
            issues.add(issue(
                "WORKFLOW_VALIDATION_AGENT_ITERATIONS_REQUIRED",
                subject + "必须配置单轮最大推理次数",
                node
            ));
        } else if (value < 1 || value > maximum) {
            issues.add(issue(
                "WORKFLOW_VALIDATION_AGENT_ITERATIONS_INVALID",
                subject + "的单轮最大推理次数必须在 1 到 " + maximum + " 之间",
                node
            ));
        }
    }

    private static int parsePositiveInteger(Object value) {
        if (value instanceof Number number) {
            double numericValue = number.doubleValue();
            if (!Double.isFinite(numericValue) || numericValue != Math.rint(numericValue)
                || numericValue > Integer.MAX_VALUE || numericValue < Integer.MIN_VALUE) {
                return -1;
            }
            return (int) numericValue;
        }
        try {
            return value == null ? -1 : Integer.parseInt(value.toString());
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static String resolveTemplateReference(Map<String, Object> config, String primaryKey, String... fallbackKeys) {
        String templateId = extractString(config, primaryKey);
        if (templateId != null) {
            return templateId;
        }
        for (String fallbackKey : fallbackKeys) {
            templateId = extractString(config, fallbackKey);
            if (templateId != null) {
                return templateId;
            }
        }
        return null;
    }

    private static String rawString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean isValidVariableName(String value) {
        return value != null && value.matches("^[a-z][a-z0-9_]*$");
    }

    private static Set<String> variableSet(List<String> variables) {
        if (variables == null || variables.isEmpty()) {
            return Set.of();
        }
        return variables.stream()
            .map(WorkflowNodeConfigValidator::rawString)
            .filter(value -> !value.isBlank())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // ---- 配置解析辅助 ----

    /**
     * 从 config 中提取字符串列表；支持两个候选键名以兼容前端新旧字段名。
     */
    @SuppressWarnings("unchecked")
    private static List<String> extractStringList(Map<String, Object> config, String primaryKey, String fallbackKey) {
        Object value = config.get(primaryKey);
        if (value == null) {
            value = config.get(fallbackKey);
        }
        if (!(value instanceof List<?> rawList)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : rawList) {
            String str = item == null ? "" : String.valueOf(item).trim();
            if (!str.isEmpty() && !SENTINEL_VALUES.contains(str)) {
                result.add(str);
            }
        }
        return result;
    }

    /**
     * 从 config 中提取 Map 列表（如 clusterAgents）。
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractMapList(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (!(value instanceof List<?> rawList)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : rawList) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    /**
     * 从 config 中提取单个字符串值，跳过哨兵值。
     */
    private static String extractString(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value == null) {
            return null;
        }
        String str = String.valueOf(value).trim();
        return str.isEmpty() || SENTINEL_VALUES.contains(str) ? null : str;
    }

    private static WorkflowDraftApi.WorkflowValidationIssue issue(String code, String message, WorkflowDraftApi.WorkflowNodeRow node) {
        return new WorkflowDraftApi.WorkflowValidationIssue(
            code,
            "error",
            message,
            node == null ? "" : node.nodeId(),
            node == null ? "" : node.name()
        );
    }
}
