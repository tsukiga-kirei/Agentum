package com.agentum.auth.application;

import java.time.Instant;

public record IssuedRefreshToken(String value, Instant expiresAt) {
}
