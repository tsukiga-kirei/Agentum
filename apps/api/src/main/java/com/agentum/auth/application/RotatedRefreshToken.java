package com.agentum.auth.application;

import java.util.UUID;

public record RotatedRefreshToken(UUID userId, UUID roleAssignmentId, IssuedRefreshToken issuedToken) {
}
