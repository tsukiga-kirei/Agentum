package com.agentum.tenant.interfaces;

// 登录页公开租户选项只返回轻量字段，避免在未认证接口暴露配额、策略或能力授权信息。
public record TenantOptionResponse(String id, String name, String code) {
}
