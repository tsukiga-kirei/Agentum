package com.agentum.auth.application;

public record OidcExternalIdentity(
    String subject,
    String username,
    String email,
    String displayName
) {
}
