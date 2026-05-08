package com.agentum.auth.application;

import java.util.UUID;

public record CurrentUserPrincipal(UUID userId, String username, UUID tenantId, String role, String portal, String spaceCode) {
}
