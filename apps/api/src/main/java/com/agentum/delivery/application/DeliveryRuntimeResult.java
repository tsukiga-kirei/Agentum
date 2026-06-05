package com.agentum.delivery.application;

import java.util.Map;

public record DeliveryRuntimeResult(Map<String, Object> outputs) {
    public DeliveryRuntimeResult {
        outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
    }
}
