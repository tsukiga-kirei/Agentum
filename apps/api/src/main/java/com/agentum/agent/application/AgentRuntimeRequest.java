package com.agentum.agent.application;

import com.agentum.workflow.domain.WorkflowNodeRunEntity;
import com.agentum.workflow.domain.WorkflowRunEntity;
import java.util.Map;
import java.util.UUID;

public record AgentRuntimeRequest(
    WorkflowRunEntity run,
    WorkflowNodeRunEntity nodeRun,
    Map<String, Object> nodeConfig,
    Map<String, Object> variables,
    Map<String, Object> toolOutputs,
    UUID operatorUserId
) {
    public AgentRuntimeRequest {
        nodeConfig = nodeConfig == null ? Map.of() : Map.copyOf(nodeConfig);
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        toolOutputs = toolOutputs == null ? Map.of() : Map.copyOf(toolOutputs);
    }
}
