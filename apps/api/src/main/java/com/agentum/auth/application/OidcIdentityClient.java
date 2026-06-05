package com.agentum.auth.application;

import com.agentum.auth.domain.TenantSsoProviderEntity;

public interface OidcIdentityClient {

    OidcExternalIdentity exchangeCode(TenantSsoProviderEntity provider, String code, String redirectUri, String expectedNonce);
}
