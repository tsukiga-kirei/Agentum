package com.agentum.auth.application;

import java.util.UUID;

// Basic 认证通过后只暂存完成登录所需的最小上下文；共享密码和 Access / Refresh Token 均不进入 Redis。
public record BasicSsoHandoff(UUID tenantId, UUID providerId, String username, String portal) {
}
