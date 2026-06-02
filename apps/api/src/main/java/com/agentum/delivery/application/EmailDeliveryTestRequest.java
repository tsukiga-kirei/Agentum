package com.agentum.delivery.application;

import java.util.Map;
import java.util.UUID;

public record EmailDeliveryTestRequest(UUID capabilityId, Map<String, Object> config) {
}
