package com.agentum.delivery.application;

import java.util.Map;

public record DocumentDeliveryPreviewCommand(
    String capabilityId,
    String markdown,
    String fileName,
    String title,
    Map<String, Object> style
) {
}
