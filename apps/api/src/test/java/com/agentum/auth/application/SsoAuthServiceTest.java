package com.agentum.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentum.auth.domain.TenantSsoProviderEntity;
import com.agentum.auth.domain.UserAccount;
import com.agentum.auth.domain.UserExternalIdentityEntity;
import com.agentum.auth.domain.UserRoleAssignmentEntity;
import com.agentum.auth.infrastructure.TenantSsoProviderRepository;
import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.auth.infrastructure.UserExternalIdentityRepository;
import com.agentum.auth.infrastructure.UserRoleAssignmentRepository;
import com.agentum.auth.interfaces.LoginResponse;
import com.agentum.shared.security.FieldEncryptionService;
import com.agentum.tenant.domain.TenantEntity;
import com.agentum.tenant.infrastructure.TenantRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SsoAuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-05T08:00:00Z");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID PROVIDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");

    @Test
    void shouldBuildOidcAuthorizeRedirectWithSignedState() {
        TenantSsoProviderRepository providerRepository = mock(TenantSsoProviderRepository.class);
        SsoAuthService service = buildService(null, providerRepository, null, null, null, null, null, null);
        TenantSsoProviderEntity provider = provider();
        when(providerRepository.findByIdAndTenantIdAndStatus(PROVIDER_ID, TENANT_ID, "enabled")).thenReturn(Optional.of(provider));

        SsoAuthorizeRedirect redirect = service.createAuthorizeRedirect(TENANT_ID, PROVIDER_ID, "business");

        assertThat(redirect.redirectUrl())
            .contains("https://idp.example.com/oauth2/authorize")
            .contains("response_type=code")
            .contains("client_id=agentum-client")
            .contains("scope=openid+email+profile")
            .contains("redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fapi%2Fauth%2Fsso%2Fcallback%2F00000000-0000-0000-0000-000000000901")
            .contains("state=")
            .contains("nonce=");
    }

    @Test
    void shouldExchangeOidcCallbackForAgentumLoginResponse() {
        TenantRepository tenantRepository = mock(TenantRepository.class);
        TenantSsoProviderRepository providerRepository = mock(TenantSsoProviderRepository.class);
        UserExternalIdentityRepository externalIdentityRepository = mock(UserExternalIdentityRepository.class);
        UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
        UserRoleAssignmentRepository roleAssignmentRepository = mock(UserRoleAssignmentRepository.class);
        AuthTokenService authTokenService = mock(AuthTokenService.class);
        OidcIdentityClient oidcIdentityClient = mock(OidcIdentityClient.class);
        MenuService menuService = mock(MenuService.class);
        SsoStateService stateService = new SsoStateService(Clock.fixed(NOW, ZoneOffset.UTC), "sso-test-secret", Duration.ofMinutes(5));
        SsoAuthService service = buildService(
            tenantRepository,
            providerRepository,
            externalIdentityRepository,
            userAccountRepository,
            roleAssignmentRepository,
            authTokenService,
            mock(AuthRefreshTokenService.class),
            oidcIdentityClient,
            menuService,
            stateService
        );
        TenantEntity tenant = TenantEntity.create("云程科技", "cloudway", NOW);
        TenantSsoProviderEntity provider = provider();
        UserAccount user = UserAccount.create("operator", "hash", "业务用户", "operator@example.com");
        UserExternalIdentityEntity identity = UserExternalIdentityEntity.create(user.getId(), TENANT_ID, PROVIDER_ID, "external-operator", "operator@example.com", "业务用户", NOW);
        UserRoleAssignmentEntity assignment = UserRoleAssignmentEntity.create(user.getId(), "business", TENANT_ID, "云程科技 - 业务用户", true);
        String state = stateService.createState(TENANT_ID, PROVIDER_ID, "business");

        when(providerRepository.findByIdAndTenantIdAndStatus(PROVIDER_ID, TENANT_ID, "enabled")).thenReturn(Optional.of(provider));
        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(tenant));
        when(oidcIdentityClient.exchangeCode(provider, "oidc-code", "http://localhost:8080/api/auth/sso/callback/" + PROVIDER_ID, stateService.parseState(state).nonce()))
            .thenReturn(new OidcExternalIdentity("external-operator", "operator", "operator@example.com", "业务用户"));
        when(externalIdentityRepository.findByProviderIdAndSubject(PROVIDER_ID, "external-operator")).thenReturn(Optional.of(identity));
        when(userAccountRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(roleAssignmentRepository.findByUserIdOrderByDefaultAssignmentDesc(user.getId())).thenReturn(List.of(assignment));
        when(authTokenService.createToken(any(CurrentUserPrincipal.class))).thenReturn("agentum-token");
        when(menuService.resolveMenus("business", TENANT_ID, user.getId())).thenReturn(List.of());

        AuthSessionResult result = service.handleCallback(PROVIDER_ID, "oidc-code", state);
        LoginResponse response = result.response();

        assertThat(response.token()).isEqualTo("agentum-token");
        assertThat(response.user().username()).isEqualTo("operator");
        assertThat(response.activeRole().role()).isEqualTo("business");
        verify(externalIdentityRepository).save(identity);
    }

    @Test
    void shouldPrepareBasicHandoffOnlyForVerifiedBusinessUser() {
        TenantRepository tenantRepository = mock(TenantRepository.class);
        TenantSsoProviderRepository providerRepository = mock(TenantSsoProviderRepository.class);
        UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
        UserRoleAssignmentRepository roleAssignmentRepository = mock(UserRoleAssignmentRepository.class);
        SsoAuthService service = buildService(
            tenantRepository,
            providerRepository,
            mock(UserExternalIdentityRepository.class),
            userAccountRepository,
            roleAssignmentRepository,
            mock(AuthTokenService.class),
            mock(AuthRefreshTokenService.class),
            mock(OidcIdentityClient.class),
            mock(MenuService.class),
            new SsoStateService(Clock.fixed(NOW, ZoneOffset.UTC), "sso-test-secret", Duration.ofMinutes(5))
        );
        TenantEntity tenant = TenantEntity.create("云程科技", "cloudway", NOW);
        UUID tenantId = tenant.getId();
        TenantSsoProviderEntity provider = TenantSsoProviderEntity.createBasic(
            tenantId,
            "OA Basic",
            new FieldEncryptionService("test-master-key-with-enough-length").encrypt("shared-secret"),
            "10.0.0.8",
            "",
            NOW
        );
        provider.forceIdForTest(PROVIDER_ID);
        UserAccount user = UserAccount.create("operator", "hash", "业务用户", "operator@example.com");
        UserRoleAssignmentEntity assignment = UserRoleAssignmentEntity.create(user.getId(), "business", tenantId, "云程科技 - 业务用户", true);
        String credential = Base64.getEncoder().encodeToString("cloudway/operator:shared-secret".getBytes());

        when(tenantRepository.findByCodeAndStatus("cloudway", "active")).thenReturn(Optional.of(tenant));
        when(providerRepository.findByTenantIdAndProviderType(tenantId, "basic")).thenReturn(Optional.of(provider));
        when(userAccountRepository.findByUsername("operator")).thenReturn(Optional.of(user));
        when(roleAssignmentRepository.findByUserIdOrderByDefaultAssignmentDesc(user.getId())).thenReturn(List.of(assignment));

        BasicSsoHandoff handoff = service.prepareBasicHandoff("Basic " + credential, "business", "10.0.0.8", null, null);

        assertThat(handoff).isEqualTo(new BasicSsoHandoff(tenantId, PROVIDER_ID, "operator", "business"));
    }

    private static SsoAuthService buildService(
        TenantRepository tenantRepository,
        TenantSsoProviderRepository providerRepository,
        UserExternalIdentityRepository externalIdentityRepository,
        UserAccountRepository userAccountRepository,
        UserRoleAssignmentRepository roleAssignmentRepository,
        AuthTokenService authTokenService,
        OidcIdentityClient oidcIdentityClient,
        MenuService menuService
    ) {
        return buildService(
            tenantRepository == null ? mock(TenantRepository.class) : tenantRepository,
            providerRepository == null ? mock(TenantSsoProviderRepository.class) : providerRepository,
            externalIdentityRepository == null ? mock(UserExternalIdentityRepository.class) : externalIdentityRepository,
            userAccountRepository == null ? mock(UserAccountRepository.class) : userAccountRepository,
            roleAssignmentRepository == null ? mock(UserRoleAssignmentRepository.class) : roleAssignmentRepository,
            authTokenService == null ? mock(AuthTokenService.class) : authTokenService,
            mock(AuthRefreshTokenService.class),
            oidcIdentityClient == null ? mock(OidcIdentityClient.class) : oidcIdentityClient,
            menuService == null ? mock(MenuService.class) : menuService,
            new SsoStateService(Clock.fixed(NOW, ZoneOffset.UTC), "sso-test-secret", Duration.ofMinutes(5))
        );
    }

    private static SsoAuthService buildService(
        TenantRepository tenantRepository,
        TenantSsoProviderRepository providerRepository,
        UserExternalIdentityRepository externalIdentityRepository,
        UserAccountRepository userAccountRepository,
        UserRoleAssignmentRepository roleAssignmentRepository,
        AuthTokenService authTokenService,
        AuthRefreshTokenService refreshTokenService,
        OidcIdentityClient oidcIdentityClient,
        MenuService menuService,
        SsoStateService stateService
    ) {
        return new SsoAuthService(
            tenantRepository,
            providerRepository,
            externalIdentityRepository,
            userAccountRepository,
            roleAssignmentRepository,
            authTokenService,
            refreshTokenService,
            menuService,
            oidcIdentityClient,
            stateService,
            new FieldEncryptionService("test-master-key-with-enough-length"),
            Clock.fixed(NOW, ZoneOffset.UTC),
            "http://localhost:8080",
            "http://localhost:5173"
        );
    }

    private static TenantSsoProviderEntity provider() {
        TenantSsoProviderEntity provider = TenantSsoProviderEntity.createOidc(
            TENANT_ID,
            "演示 OIDC",
            "https://idp.example.com",
            "agentum-client",
            "encrypted-secret",
            "https://idp.example.com/oauth2/authorize",
            "https://idp.example.com/oauth2/token",
            "https://idp.example.com/oauth2/jwks",
            NOW
        );
        provider.forceIdForTest(PROVIDER_ID);
        return provider;
    }
}
