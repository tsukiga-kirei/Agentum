package com.agentum.system.application;

import java.util.UUID;

public record McpConnectionTestRequest(UUID capabilityId, String transportType, String endpointUrl) {
}
