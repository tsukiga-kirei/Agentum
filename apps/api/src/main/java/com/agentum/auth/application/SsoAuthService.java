package com.agentum.auth.application;

import com.agentum.auth.domain.PortalType;
import com.agentum.auth.domain.TenantSsoProviderEntity;
import com.agentum.auth.domain.UserAccount;
import com.agentum.auth.domain.UserExternalIdentityEntity;
import com.agentum.auth.domain.UserRoleAssignmentEntity;
import com.agentum.auth.infrastructure.TenantSsoProviderRepository;
import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.auth.infrastructure.UserExternalIdentityRepository;
import com.agentum.auth.infrastructure.UserRoleAssignmentRepository;
import com.agentum.auth.interfaces.AuthUserResponse;
import com.agentum.auth.interfaces.LoginResponse;
import com.agentum.auth.interfaces.MenuItemResponse;
import com.agentum.auth.interfaces.RoleInfoResponse;
import com.agentum.auth.interfaces.SsoProviderResponse;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.agentum.shared.security.FieldEncryptionService;
import com.agentum.tenant.domain.TenantEntity;
import com.agentum.tenant.infrastructure.TenantRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SsoAuthService {

    private static final Logger log = LoggerFactory.getLogger(SsoAuthService.class);
    private static final String ACTIVE_STATUS = "active";
    private static final String ENABLED_STATUS = "enabled";

    private final TenantRepository tenantRepository;
    private final TenantSsoProviderRepository providerRepository;
    private final UserExternalIdentityRepository externalIdentityRepository;
    private final UserAccountRepository userAccountRepository;
    private final UserRoleAssignmentRepository roleAssignmentRepository;
    private final AuthTokenService authTokenService;
    private final MenuService menuService;
    private final OidcIdentityClient oidcIdentityClient;
    private final SsoStateService stateService;
    private final FieldEncryptionService fieldEncryptionService;
    private final Clock clock;
    private final String apiBaseUrl;
    private final String webBaseUrl;

    @Autowired
    public SsoAuthService(
        TenantRepository tenantRepository,
        TenantSsoProviderRepository providerRepository,
        UserExternalIdentityRepository externalIdentityRepository,
        UserAccountRepository userAccountRepository,
        UserRoleAssignmentRepository roleAssignmentRepository,
        AuthTokenService authTokenService,
        MenuService menuService,
        OidcIdentityClient oidcIdentityClient,
        SsoStateService stateService,
        FieldEncryptionService fieldEncryptionService,
        @Value("${agentum.auth.sso-api-base-url:http://localhost:8080}") String apiBaseUrl,
        @Value("${agentum.auth.sso-web-base-url:http://localhost:5173}") String webBaseUrl
    ) {
        this(
            tenantRepository,
            providerRepository,
            externalIdentityRepository,
            userAccountRepository,
            roleAssignmentRepository,
            authTokenService,
            menuService,
            oidcIdentityClient,
            stateService,
            fieldEncryptionService,
            Clock.systemUTC(),
            apiBaseUrl,
            webBaseUrl
        );
    }

    SsoAuthService(
        TenantRepository tenantRepository,
        TenantSsoProviderRepository providerRepository,
        UserExternalIdentityRepository externalIdentityRepository,
        UserAccountRepository userAccountRepository,
        UserRoleAssignmentRepository roleAssignmentRepository,
        AuthTokenService authTokenService,
        MenuService menuService,
        OidcIdentityClient oidcIdentityClient,
        SsoStateService stateService,
        FieldEncryptionService fieldEncryptionService,
        Clock clock,
        String apiBaseUrl,
        String webBaseUrl
    ) {
        this.tenantRepository = tenantRepository;
        this.providerRepository = providerRepository;
        this.externalIdentityRepository = externalIdentityRepository;
        this.userAccountRepository = userAccountRepository;
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.authTokenService = authTokenService;
        this.menuService = menuService;
        this.oidcIdentityClient = oidcIdentityClient;
        this.stateService = stateService;
        this.fieldEncryptionService = fieldEncryptionService;
        this.clock = clock;
        this.apiBaseUrl = stripTrailingSlash(apiBaseUrl);
        this.webBaseUrl = stripTrailingSlash(webBaseUrl);
    }

    @Transactional(readOnly = true)
    public List<SsoProviderResponse> listTenantProviders(UUID tenantId) {
        tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "TENANT_NOT_AVAILABLE", "当前租户不可用或已停用"));
        return providerRepository.findByTenantIdAndStatusOrderByNameAsc(tenantId, ENABLED_STATUS)
            .stream()
            .map(provider -> new SsoProviderResponse(
                provider.getId().toString(),
                provider.getName(),
                provider.getProviderType()
            ))
            .toList();
    }

    @Transactional(readOnly = true)
    public SsoAuthorizeRedirect createAuthorizeRedirect(UUID tenantId, UUID providerId, String portal) {
        PortalType portalType = PortalType.fromCode(portal);
        if (!portalType.isTenantScoped()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AUTH_SSO_PORTAL_UNSUPPORTED", "企业 SSO 仅支持租户型入口");
        }

        TenantSsoProviderEntity provider = loadProvider(tenantId, providerId);
        String state = stateService.createState(tenantId, providerId, portalType.code());
        SsoState parsedState = stateService.parseState(state);
        String redirectUri = callbackUri(providerId);
        String url = provider.getAuthorizationEndpoint()
            + "?response_type=code"
            + "&client_id=" + encode(provider.getClientId())
            + "&redirect_uri=" + encode(redirectUri)
            + "&scope=" + encode(readScope(provider))
            + "&state=" + encode(state)
            + "&nonce=" + encode(parsedState.nonce());

        return new SsoAuthorizeRedirect(url);
    }

    @Transactional
    public LoginResponse handleCallback(UUID providerId, String code, String state) {
        SsoState parsedState = stateService.parseState(state);
        if (!providerId.equals(parsedState.providerId())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_SSO_PROVIDER_MISMATCH", "企业 SSO 身份源不匹配，请重新登录");
        }
        TenantSsoProviderEntity provider = loadProvider(parsedState.tenantId(), providerId);
        TenantEntity tenant = tenantRepository.findByIdAndStatus(parsedState.tenantId(), ACTIVE_STATUS)
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "TENANT_NOT_AVAILABLE", "当前租户不可用或已停用"));
        OidcExternalIdentity externalIdentity = oidcIdentityClient.exchangeCode(provider, code, callbackUri(providerId), parsedState.nonce());
        UserExternalIdentityEntity binding = resolveBinding(provider, externalIdentity);
        UserAccount user = userAccountRepository.findById(binding.getUserId())
            .filter(account -> ACTIVE_STATUS.equals(account.getStatus()))
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_SSO_USER_DISABLED", "企业 SSO 对应账号不可用"));

        List<UserRoleAssignmentEntity> assignments = roleAssignmentRepository.findByUserIdOrderByDefaultAssignmentDesc(user.getId());
        UserRoleAssignmentEntity activeAssignment = assignments.stream()
            .filter(assignment -> parsedState.portal().equals(assignment.getRole()))
            .filter(assignment -> parsedState.tenantId().equals(assignment.getTenantId()))
            .findFirst()
            .orElseThrow(() -> {
                log.warn(
                    "企业 SSO 登录失败：用户没有入口权限 userId={} tenantId={} portal={} requestId={}",
                    user.getId(), parsedState.tenantId(), parsedState.portal(), RequestIds.current()
                );
                return new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_ROLE_NOT_ALLOWED", "当前用户没有所选入口权限");
            });

        user.markLoggedIn(clock.instant());
        binding.markLoggedIn(externalIdentity.email(), externalIdentity.displayName(), clock.instant());
        externalIdentityRepository.save(binding);

        List<RoleInfoResponse> roles = buildRoleInfoList(assignments);
        RoleInfoResponse activeRole = buildRoleInfo(activeAssignment, tenant);
        List<MenuItemResponse> menus = menuService.resolveMenus(activeAssignment.getRole(), activeAssignment.getTenantId(), user.getId());
        CurrentUserPrincipal principal = new CurrentUserPrincipal(user.getId(), user.getUsername(), activeAssignment.getTenantId(), activeAssignment.getRole(), parsedState.portal(), activeAssignment.getId());
        String token = authTokenService.createToken(principal);

        log.info(
            "企业 SSO 登录成功 userId={} tenantId={} providerId={} portal={} roleAssignmentId={} requestId={}",
            user.getId(), parsedState.tenantId(), providerId, parsedState.portal(), activeAssignment.getId(), RequestIds.current()
        );
        return new LoginResponse(token, buildUserResponse(user, activeAssignment, tenant), roles, activeRole, List.of(), menus);
    }

    private TenantSsoProviderEntity loadProvider(UUID tenantId, UUID providerId) {
        return providerRepository.findByIdAndTenantIdAndStatus(providerId, tenantId, ENABLED_STATUS)
            .filter(provider -> "oidc".equals(provider.getProviderType()))
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "AUTH_SSO_PROVIDER_NOT_AVAILABLE", "当前租户未启用该企业 SSO 身份源"));
    }

    private UserExternalIdentityEntity resolveBinding(TenantSsoProviderEntity provider, OidcExternalIdentity externalIdentity) {
        return externalIdentityRepository.findByProviderIdAndSubject(provider.getId(), externalIdentity.subject())
            .orElseGet(() -> bindByEmail(provider, externalIdentity));
    }

    private UserExternalIdentityEntity bindByEmail(TenantSsoProviderEntity provider, OidcExternalIdentity externalIdentity) {
        if (!provider.isAutoBindEmail() || externalIdentity.email() == null || externalIdentity.email().isBlank()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "AUTH_SSO_USER_NOT_BOUND", "企业 SSO 账号尚未绑定 Agentum 用户");
        }
        if (provider.getEmailDomain() != null && !provider.getEmailDomain().isBlank()
            && !externalIdentity.email().toLowerCase().endsWith("@" + provider.getEmailDomain().toLowerCase())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "AUTH_SSO_EMAIL_DOMAIN_MISMATCH", "企业 SSO 邮箱域名不属于当前租户");
        }
        UserAccount user = userAccountRepository.findByEmailIgnoreCase(externalIdentity.email())
            .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "AUTH_SSO_USER_NOT_BOUND", "企业 SSO 账号尚未绑定 Agentum 用户"));
        return externalIdentityRepository.save(UserExternalIdentityEntity.create(
            user.getId(),
            provider.getTenantId(),
            provider.getId(),
            externalIdentity.subject(),
            externalIdentity.email(),
            externalIdentity.displayName(),
            clock.instant()
        ));
    }

    private List<RoleInfoResponse> buildRoleInfoList(List<UserRoleAssignmentEntity> assignments) {
        Map<UUID, TenantEntity> tenantCache = new HashMap<>();
        List<RoleInfoResponse> roles = new ArrayList<>();
        for (UserRoleAssignmentEntity assignment : assignments) {
            TenantEntity tenant = null;
            if (assignment.getTenantId() != null) {
                tenant = tenantCache.computeIfAbsent(assignment.getTenantId(), id -> tenantRepository.findByIdAndStatus(id, ACTIVE_STATUS).orElse(null));
                if (tenant == null) {
                    continue;
                }
            }
            roles.add(buildRoleInfo(assignment, tenant));
        }
        return roles;
    }

    private RoleInfoResponse buildRoleInfo(UserRoleAssignmentEntity assignment, TenantEntity tenant) {
        return new RoleInfoResponse(
            assignment.getId().toString(),
            assignment.getRole(),
            assignment.getTenantId() == null ? null : assignment.getTenantId().toString(),
            tenant == null ? null : tenant.getName(),
            assignment.getLabel()
        );
    }

    private AuthUserResponse buildUserResponse(UserAccount user, UserRoleAssignmentEntity assignment, TenantEntity tenant) {
        return new AuthUserResponse(
            user.getId().toString(),
            user.getUsername(),
            user.getDisplayName(),
            user.getEmail() == null ? "" : user.getEmail(),
            user.getAvatarUrl() == null ? "" : user.getAvatarUrl(),
            assignment.getRole(),
            assignment.getTenantId() == null ? null : assignment.getTenantId().toString(),
            tenant.getName(),
            tenant.getCode(),
            tenant.getName(),
            user.getLastLoginAt() == null ? "" : user.getLastLoginAt().toString()
        );
    }

    private String callbackUri(UUID providerId) {
        return apiBaseUrl + "/api/auth/sso/callback/" + providerId;
    }

    private static String readScope(TenantSsoProviderEntity provider) {
        Object scope = provider.getConfig().get("scope");
        return scope instanceof String text && !text.isBlank() ? text : "openid email profile";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }
}
