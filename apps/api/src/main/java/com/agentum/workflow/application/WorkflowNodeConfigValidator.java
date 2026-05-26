package com.agentum.workflow.application;

import com.agentum.system.domain.SystemCapabilityEntity;
import com.agentum.system.domain.TenantCapabilityGrantEntity;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import com.agentum.system.infrastructure.TenantCapabilityGrantRepository;
import com.agentum.workflow.interfaces.WorkflowDraftApi;
import java.util.ArrayList;
import java.util.HashMap;
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
 * 发布校验：验证流程节点 config 中引用的系统能力（MCP、Skill、交付能力）在租户能力池内且状态有效。
 * 仅校验系统能力引用，不校验租户自建资产（智能体模板、提示词模板）——后者在资产自身的发布流程中已校验。
 * 设计态保存不调用此校验器，保留草稿迭代自由度。
 */
@Component
public class WorkflowNodeConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(WorkflowNodeConfigValidator.class);
    private static final Set<String> SENTINEL_VALUES = Set.of("custom", "none", "");
    private static final String ACTIVE_STATUS = "active";

    private final SystemCapabilityRepository systemCapabilityRepository;
    private final TenantCapabilityGrantRepository tenantCapabilityGrantRepository;

    public WorkflowNodeConfigValidator(
        SystemCapabilityRepository systemCapabilityRepository,
        TenantCapabilityGrantRepository tenantCapabilityGrantRepository
    ) {
        this.systemCapabilityRepository = systemCapabilityRepository;
        this.tenantCapabilityGrantRepository = tenantCapabilityGrantRepository;
    }

    public List<WorkflowDraftApi.WorkflowValidationIssue> validateCapabilityReferences(
        UUID tenantId,
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
            if ("agent".equals(nodeType)) {
                validateIds(extractStringList(config, "mcpIds", "mcpServices"), "mcp", "MCP", node, poolCapabilities, issues);
                validateIds(extractStringList(config, "skillIds", "skills"), "skill", "Skill", node, poolCapabilities, issues);
            } else if ("parallel_group".equals(nodeType)) {
                List<Map<String, Object>> agents = extractMapList(config, "clusterAgents");
                for (Map<String, Object> agent : agents) {
                    validateIds(extractStringList(agent, "mcpIds", "mcpServices"), "mcp", "MCP", node, poolCapabilities, issues);
                    validateIds(extractStringList(agent, "skillIds", "skills"), "skill", "Skill", node, poolCapabilities, issues);
                }
            } else if ("delivery".equals(nodeType)) {
                String deliveryId = extractString(config, "deliveryCapabilityId");
                if (deliveryId != null) {
                    validateIds(List.of(deliveryId), "delivery", "交付能力", node, poolCapabilities, issues);
                }
            }
        }

        return issues;
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
            }
        }
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
