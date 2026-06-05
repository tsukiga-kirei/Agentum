package com.agentum.mcp.application;

import com.agentum.workflow.domain.WorkflowNodeRunEntity;
import com.agentum.workflow.domain.WorkflowRunEntity;
import java.util.Map;
import java.util.UUID;

public record McpRuntimeRequest(
    WorkflowRunEntity run,
    WorkflowNodeRunEntity nodeRun,
    Map<String, Object> nodeConfig,
    Map<String, Object> variables,
    UUID operatorUserId
) {
    public McpRuntimeRequest {
        nodeConfig = nodeConfig == null ? Map.of() : Map.copyOf(nodeConfig);
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }
}
