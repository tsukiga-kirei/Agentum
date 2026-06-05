package com.agentum.auth.interfaces;

// 登录页公开展示的 SSO 身份源信息，只返回按钮渲染所需字段，不暴露 issuer、clientId 或端点细节。
public record SsoProviderResponse(
    String id,
    String name,
    String providerType
) {
}
