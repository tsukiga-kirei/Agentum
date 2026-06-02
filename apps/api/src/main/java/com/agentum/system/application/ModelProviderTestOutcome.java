package com.agentum.system.application;

import java.util.List;

public record ModelProviderTestOutcome(
    String status,
    String summary,
    List<String> availableModels,
    long latencyMs
) {
}
