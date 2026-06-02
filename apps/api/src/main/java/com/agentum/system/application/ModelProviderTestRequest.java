package com.agentum.system.application;

import java.util.UUID;

public record ModelProviderTestRequest(
    UUID providerId,
    String providerType,
    String baseUrl,
    String modelListEndpoint,
    String defaultModel,
    String authScheme,
    String apiKey
) {
}
