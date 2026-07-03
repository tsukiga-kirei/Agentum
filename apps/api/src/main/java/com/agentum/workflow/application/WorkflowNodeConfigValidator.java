package com.agentum.workflow.application;

import com.agentum.agent.application.AgentRuntimeProperties;
import com.agentum.asset.application.AssetManagementService;
import com.agentum.system.domain.SystemCapabilityEntity;
import com.agentum.system.domain.TenantCapabilityGrantEntity;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import com.agentum.system.infrastructure.TenantCapabilityGrantRepository;
import com.agentum.workflow.interfaces.WorkflowDraftApi;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 保存和发布校验：验证流程节点引用的系统能力与租户自建能力已发布，并且当前编辑者仍有读取权限。
 * 这是协作编辑的安全边界，避免流程创建者授权编辑后，协作者通过流程间接使用未向其开放的能力。
 */
@Component
public class WorkflowNodeConfigValidator {

    private static final Set<String> SENTINEL_VALUES = Set.of("custom", "none", "");
    private static final String ACTIVE_STATUS = "active";
    /** 智能体集群支持的执行方式枚举，历史 parallel/sequential 由数据库迁移统一清洗。 */
    private static final Set<String> CLUSTER_EXECUTION_MODES = Set.of("collaborative", "relay", "intent");
    private static final Set<String> INTENT_SELECTION_MODES = Set.of("single", "multiple");
    private static final Set<String> INTENT_FALLBACK_MODES = Set.of("fail", "agent", "fixed_reply");
    private static final Pattern TEMPLATE_VARIABLE_PATTERN = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");

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
                        "节点[" + node.name() + "]的执行方式不合法，仅支持协同处理、接力处理或意图分派",
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
            if (isInvalidVariableName(variable)) {
                issues.add(issue("WORKFLOW_VALIDATION_INPUT_FIELD_VARIABLE_INVALID", "节点[" + node.name() + "]的输入字段变量名不合法：" + variable, node));
                continue;
            }
            fieldVariables.add(variable);
            validateInputFieldOptions(field, node, issues);
        }
        Set<String> declaredOutputs = variableSet(node.outputVariables());
        if (!fieldVariables.equals(declaredOutputs)) {
            issues.add(issue("WORKFLOW_VALIDATION_INPUT_OUTPUT_MISMATCH", "节点[" + node.name() + "]的输入字段变量必须与节点输出变量保持一致", node));
        }
    }

    private void validateInputFieldOptions(
        Map<String, Object> field,
        WorkflowDraftApi.WorkflowNodeRow node,
        List<WorkflowDraftApi.WorkflowValidationIssue> issues
    ) {
        String fieldType = rawString(field.get("fieldType"));
        if (!"select".equals(fieldType)) {
            return;
        }

        String fieldLabel = rawString(field.get("label"));
        if (fieldLabel.isBlank()) {
            fieldLabel = rawString(field.get("variable"));
        }

        String placeholder = rawString(field.get("placeholder"));
        List<Map<String, Object>> options = extractMapList(field, "options");
        long validOptionCount = options.stream()
            .filter(option -> isValidSelectOption(option, placeholder))
            .count();
        if (validOptionCount == 0) {
            issues.add(issue(
                "WORKFLOW_VALIDATION_INPUT_FIELD_OPTIONS_REQUIRED",
                "节点[" + node.name() + "]的下拉字段「" + fieldLabel + "」至少需要配置一个有效选项",
                node
            ));
        }
    }

    private boolean isValidSelectOption(Map<String, Object> option, String placeholder) {
        String label = rawString(option.get("label"));
        String value = rawString(option.get("value"));
        if (label.isBlank() || value.isBlank()) {
            return false;
        }
        return !isPlaceholderLikeSelectOption(label, value, placeholder);
    }

    private boolean isPlaceholderLikeSelectOption(String label, String value, String placeholder) {
        if (!placeholder.isBlank() && (placeholder.equals(label) || placeholder.equals(value))) {
            return true;
        }
        return "请选择".equals(label)
            || "请选择".equals(value)
            || "__placeholder__".equals(value)
            || "placeholder".equals(value);
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
        String executionMode = normalizeClusterExecutionMode(rawString(config.get("executionMode")));
        Set<String> agentOutputs = new LinkedHashSet<>();
        Set<String> intentCodes = new LinkedHashSet<>();
        for (int index = 0; index < agents.size(); index++) {
            Map<String, Object> agent = agents.get(index);
            String output = rawString(agent.get("output"));
            if (isInvalidVariableName(output)) {
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
        String clusterOutputVariable = rawString(config.getOrDefault("clusterOutputVariable", "cluster_result"));
        if (isInvalidVariableName(clusterOutputVariable)) {
            issues.add(issue("WORKFLOW_VALIDATION_CLUSTER_OUTPUT_VARIABLE_INVALID", "节点[" + node.name() + "]的最终输出变量名不合法：" + clusterOutputVariable, node));
        }
        Set<String> invalidMergeVariables = extractTemplateVariables(rawString(config.get("mergeRule"))).stream()
            .filter(variable -> !agentOutputs.contains(variable))
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!invalidMergeVariables.isEmpty()) {
            issues.add(issue(
                "WORKFLOW_VALIDATION_CLUSTER_MERGE_VARIABLE_INVALID",
                "节点[" + node.name() + "]的集群输出模板只能引用子智能体输出变量：" + String.join("、", invalidMergeVariables),
                node
            ));
        }
        if ("intent".equals(executionMode)) {
            validateIntentRoutingConfig(config, node, agents, declaredOutputs, issues);
        } else {
            Set<String> expectedOutputs = new LinkedHashSet<>();
            expectedOutputs.add(clusterOutputVariable);
            expectedOutputs.addAll(agentOutputs);
            if (!expectedOutputs.equals(declaredOutputs)) {
                issues.add(issue("WORKFLOW_VALIDATION_CLUSTER_OUTPUT_MISMATCH", "节点[" + node.name() + "]的最终输出和子智能体输出必须与节点输出变量保持一致", node));
            }
        }
    }

    private void validateIntentRoutingConfig(
        Map<String, Object> config,
        WorkflowDraftApi.WorkflowNodeRow node,
        List<Map<String, Object>> agents,
        Set<String> declaredOutputs,
        List<WorkflowDraftApi.WorkflowValidationIssue> issues
    ) {
        String clusterOutputVariable = rawString(config.getOrDefault("clusterOutputVariable", "cluster_result"));
        if (!Set.of(clusterOutputVariable).equals(declaredOutputs)) {
            issues.add(issue(
                "WORKFLOW_VALIDATION_CLUSTER_INTENT_OUTPUT_INVALID",
                "节点[" + node.name() + "]使用意图分派时，下游输出必须统一为 " + clusterOutputVariable + "，避免引用未命中的子智能体变量",
                node
            ));
        }
        String selectionMode = rawString(config.get("intentSelectionMode"));
        if (!selectionMode.isBlank() && !INTENT_SELECTION_MODES.contains(selectionMode)) {
            issues.add(issue("WORKFLOW_VALIDATION_CLUSTER_INTENT_SELECTION_MODE_INVALID", "节点[" + node.name() + "]的意图命中策略不合法", node));
        }
        String fallbackMode = rawString(config.get("intentFallbackMode"));
        if (!fallbackMode.isBlank() && !INTENT_FALLBACK_MODES.contains(fallbackMode)) {
            issues.add(issue("WORKFLOW_VALIDATION_CLUSTER_INTENT_FALLBACK_MODE_INVALID", "节点[" + node.name() + "]的未命中处理方式不合法", node));
        }
        if (rawString(config.get("intentInputTemplate")).isBlank()) {
            issues.add(issue("WORKFLOW_VALIDATION_CLUSTER_INTENT_INPUT_REQUIRED", "节点[" + node.name() + "]使用意图分派时必须配置待判断内容", node));
        }

        Set<String> agentIds = agents.stream()
            .map(agent -> rawString(agent.get("id")))
            .filter(value -> !value.isBlank())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        List<Map<String, Object>> routes = extractMapList(config, "intentRoutes");
        if (routes.isEmpty()) {
            issues.add(issue("WORKFLOW_VALIDATION_CLUSTER_INTENT_ROUTES_REQUIRED", "节点[" + node.name() + "]至少需要配置一个意图", node));
        }
        Set<String> intentCodes = new LinkedHashSet<>();
        for (int index = 0; index < routes.size(); index++) {
            Map<String, Object> route = routes.get(index);
            String intentCode = rawString(route.get("intentCode"));
            if (isInvalidVariableName(intentCode)) {
                issues.add(issue("WORKFLOW_VALIDATION_CLUSTER_INTENT_CODE_INVALID", "节点[" + node.name() + "]的第 " + (index + 1) + " 个意图代码不合法", node));
            } else if (!intentCodes.add(intentCode)) {
                issues.add(issue("WORKFLOW_VALIDATION_CLUSTER_INTENT_CODE_DUPLICATED", "节点[" + node.name() + "]存在重复的意图代码：" + intentCode, node));
            }
            if (rawString(route.get("intentDescription")).isBlank()) {
                issues.add(issue("WORKFLOW_VALIDATION_CLUSTER_INTENT_DESCRIPTION_REQUIRED", "节点[" + node.name() + "]的第 " + (index + 1) + " 个意图必须说明什么时候命中", node));
            }
            String agentId = rawString(route.get("agentId"));
            if (agentId.isBlank() || !agentIds.contains(agentId)) {
                issues.add(issue("WORKFLOW_VALIDATION_CLUSTER_INTENT_AGENT_MISSING", "节点[" + node.name() + "]的第 " + (index + 1) + " 个意图必须选择目标智能体", node));
            }
        }

        if ("agent".equals(fallbackMode)) {
            String fallbackAgentId = rawString(config.get("fallbackAgentId"));
            if (fallbackAgentId.isBlank() || !agentIds.contains(fallbackAgentId)) {
                issues.add(issue("WORKFLOW_VALIDATION_CLUSTER_INTENT_FALLBACK_AGENT_MISSING", "节点[" + node.name() + "]的其他情况必须选择目标智能体", node));
            }
        } else if ("fixed_reply".equals(fallbackMode) && rawString(config.get("fallbackReply")).isBlank()) {
            issues.add(issue("WORKFLOW_VALIDATION_CLUSTER_INTENT_FALLBACK_REPLY_REQUIRED", "节点[" + node.name() + "]的其他情况固定回复不能为空", node));
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

    private static String normalizeClusterExecutionMode(String value) {
        return switch (value) {
            case "collaborative", "relay", "intent" -> value;
            case "" -> "collaborative";
            default -> value;
        };
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

    private static boolean isInvalidVariableName(String value) {
        return value == null || !value.matches("^[a-z][a-z0-9_]*$");
    }

    private static Set<String> extractTemplateVariables(String text) {
        Set<String> variables = new LinkedHashSet<>();
        Matcher matcher = TEMPLATE_VARIABLE_PATTERN.matcher(text == null ? "" : text);
        while (matcher.find()) {
            variables.add(matcher.group(1));
        }
        return variables;
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
