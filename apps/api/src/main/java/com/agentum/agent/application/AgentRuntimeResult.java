package com.agentum.agent.application;

import java.util.Map;

public record AgentRuntimeResult(Map<String, Object> outputs) {
    public AgentRuntimeResult {
        outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
    }
}
