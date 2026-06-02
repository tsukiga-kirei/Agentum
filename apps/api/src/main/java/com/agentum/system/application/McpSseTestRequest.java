package com.agentum.system.application;

import java.util.UUID;

public record McpSseTestRequest(UUID capabilityId, String sseUrl) {
}
