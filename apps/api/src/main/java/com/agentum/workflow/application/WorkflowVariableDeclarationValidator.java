package com.agentum.workflow.application;

import com.agentum.shared.api.ApiException;
import com.agentum.workflow.interfaces.WorkflowDraftApi;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class WorkflowVariableDeclarationValidator {

    private static final Logger log = LoggerFactory.getLogger(WorkflowVariableDeclarationValidator.class);
    private static final Pattern VARIABLE_KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*$");
    private static final Set<String> ALLOWED_TYPES = Set.of("string", "number", "boolean", "object", "array", "file", "decision");

    public void validate(
        List<WorkflowDraftApi.WorkflowNodeDraft> nodes,
        List<WorkflowDraftApi.WorkflowVariableDraft> variables
    ) {
        Map<String, WorkflowDraftApi.WorkflowNodeDraft> nodesById = nodes.stream()
            .collect(Collectors.toMap(node -> normalize(node.nodeId()), node -> node));
        Set<String> declaredVariableNames = new HashSet<>();
        Set<String> outputVariables = nodes.stream()
            .flatMap(node -> safeList(node.outputVariables()).stream())
            .map(WorkflowVariableDeclarationValidator::normalize)
            .filter(value -> !value.isBlank())
            .collect(Collectors.toSet());

        for (WorkflowDraftApi.WorkflowVariableDraft variable : variables) {
            String name = normalize(variable.name());
            String type = normalize(variable.type());
            String sourceNodeId = normalize(variable.sourceNode());

            if (!VARIABLE_KEY_PATTERN.matcher(name).matches()) {
                log.warn("工作流变量声明保存失败：变量名非法 variable={} sourceNode={}", name, sourceNodeId);
                throw new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_VARIABLE_NAME_INVALID", "变量名必须以小写字母开头，且只能包含小写字母、数字和下划线");
            }
            if (!declaredVariableNames.add(name)) {
                log.warn("工作流变量声明保存失败：变量重复 variable={} sourceNode={}", name, sourceNodeId);
                throw new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_VARIABLE_DUPLICATED", "变量声明不能重复");
            }
            if (!ALLOWED_TYPES.contains(type)) {
                log.warn("工作流变量声明保存失败：变量类型非法 variable={} type={}", name, type);
                throw new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_VARIABLE_TYPE_INVALID", "包含不支持的变量类型");
            }

            WorkflowDraftApi.WorkflowNodeDraft sourceNode = nodesById.get(sourceNodeId);
            if (sourceNode == null) {
                log.warn("工作流变量声明保存失败：来源节点不存在 variable={} sourceNode={}", name, sourceNodeId);
                throw new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_VARIABLE_SOURCE_NODE_NOT_FOUND", "变量来源节点不存在");
            }
            if (!safeList(sourceNode.outputVariables()).stream().map(WorkflowVariableDeclarationValidator::normalize).toList().contains(name)) {
                log.warn("工作流变量声明保存失败：来源节点未输出该变量 variable={} sourceNode={}", name, sourceNodeId);
                throw new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_VARIABLE_SOURCE_OUTPUT_MISMATCH", "变量声明必须对应来源节点的输出变量");
            }
        }

        if (!declaredVariableNames.equals(outputVariables)) {
            log.warn("工作流变量声明保存失败：声明与节点输出不一致 declaredCount={} outputCount={}", declaredVariableNames.size(), outputVariables.size());
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_VARIABLE_DECLARATION_MISMATCH", "请为每个节点输出变量补齐唯一声明");
        }
    }

    private static List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
