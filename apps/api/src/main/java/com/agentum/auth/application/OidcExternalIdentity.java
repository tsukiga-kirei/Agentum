package com.agentum.auth.application;

public record OidcExternalIdentity(
    String subject,
    String email,
    String displayName
) {
}
