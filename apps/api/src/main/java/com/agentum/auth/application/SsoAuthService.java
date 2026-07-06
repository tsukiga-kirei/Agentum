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
import java.util.Base64;
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
    private final AuthRefreshTokenService refreshTokenService;
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
        AuthRefreshTokenService refreshTokenService,
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
            refreshTokenService,
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
        AuthRefreshTokenService refreshTokenService,
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
        this.refreshTokenService = refreshTokenService;
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
        if (!"oidc".equals(provider.getProviderType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AUTH_SSO_PROVIDER_TYPE_INVALID", "当前身份源不是 OAuth2/OIDC 登录方式");
        }
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
    public AuthSessionResult handleCallback(UUID providerId, String code, String state) {
        SsoState parsedState = stateService.parseState(state);
        if (!providerId.equals(parsedState.providerId())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_SSO_PROVIDER_MISMATCH", "企业 SSO 身份源不匹配，请重新登录");
        }
        TenantSsoProviderEntity provider = loadProvider(parsedState.tenantId(), providerId);
        if (!"oidc".equals(provider.getProviderType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AUTH_SSO_PROVIDER_TYPE_INVALID", "当前身份源不是 OAuth2/OIDC 登录方式");
        }
        TenantEntity tenant = tenantRepository.findByIdAndStatus(parsedState.tenantId(), ACTIVE_STATUS)
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "TENANT_NOT_AVAILABLE", "当前租户不可用或已停用"));
        OidcExternalIdentity externalIdentity = oidcIdentityClient.exchangeCode(provider, code, callbackUri(providerId), parsedState.nonce());
        return issueExternalSession(provider, tenant, parsedState.portal(), externalIdentity);
    }

    @Transactional
    public AuthSessionResult handleBasicEntry(String authorizationHeader, String portal, String remoteAddress, String origin, String referer) {
        PortalType portalType = PortalType.fromCode(portal);
        if (!portalType.isTenantScoped()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AUTH_SSO_PORTAL_UNSUPPORTED", "企业 Basic 认证仅支持租户型入口");
        }
        BasicCredential credential = parseBasicCredential(authorizationHeader);
        BasicPrincipal principal = parseBasicPrincipal(credential.username());
        TenantEntity tenant = tenantRepository.findByCodeAndStatus(principal.tenantCode(), ACTIVE_STATUS)
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "TENANT_NOT_AVAILABLE", "当前租户不可用或已停用"));
        TenantSsoProviderEntity provider = providerRepository.findByTenantIdAndProviderType(tenant.getId(), "basic")
            .filter(item -> ENABLED_STATUS.equals(item.getStatus()))
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "AUTH_SSO_PROVIDER_NOT_AVAILABLE", "当前租户未启用 Basic 单点入口"));
        verifyBasicSource(provider, remoteAddress, origin, referer);
        verifyBasicSharedPassword(provider, credential.password());
        return issueBasicSession(provider, tenant, portalType.code(), principal.username());
    }

    private AuthSessionResult issueExternalSession(
        TenantSsoProviderEntity provider,
        TenantEntity tenant,
        String portal,
        OidcExternalIdentity externalIdentity
    ) {
        UserExternalIdentityEntity binding = resolveBinding(provider, externalIdentity);
        UserAccount user = userAccountRepository.findById(binding.getUserId())
            .filter(account -> ACTIVE_STATUS.equals(account.getStatus()))
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_SSO_USER_DISABLED", "企业 SSO 对应账号不可用"));

        List<UserRoleAssignmentEntity> assignments = roleAssignmentRepository.findByUserIdOrderByDefaultAssignmentDesc(user.getId());
        UserRoleAssignmentEntity activeAssignment = assignments.stream()
            .filter(assignment -> portal.equals(assignment.getRole()))
            .filter(assignment -> provider.getTenantId().equals(assignment.getTenantId()))
            .findFirst()
            .orElseThrow(() -> {
                log.warn(
                    "企业 SSO 登录失败：用户没有入口权限 userId={} tenantId={} portal={} requestId={}",
                    user.getId(), provider.getTenantId(), portal, RequestIds.current()
                );
                return new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_ROLE_NOT_ALLOWED", "当前用户没有所选入口权限");
            });

        user.markLoggedIn(clock.instant());
        binding.markLoggedIn(externalIdentity.email(), externalIdentity.displayName(), clock.instant());
        externalIdentityRepository.save(binding);

        List<RoleInfoResponse> roles = buildRoleInfoList(assignments);
        RoleInfoResponse activeRole = buildRoleInfo(activeAssignment, tenant);
        List<MenuItemResponse> menus = menuService.resolveMenus(activeAssignment.getRole(), activeAssignment.getTenantId(), user.getId());
        List<String> permissions = menuService.resolvePermissions(activeAssignment.getRole(), activeAssignment.getTenantId(), user.getId());
        CurrentUserPrincipal principal = new CurrentUserPrincipal(user.getId(), user.getUsername(), activeAssignment.getTenantId(), activeAssignment.getRole(), portal, activeAssignment.getId());
        String token = authTokenService.createToken(principal);

        log.info(
            "企业 SSO 登录成功 userId={} tenantId={} providerId={} portal={} roleAssignmentId={} requestId={}",
            user.getId(), provider.getTenantId(), provider.getId(), portal, activeAssignment.getId(), RequestIds.current()
        );
        LoginResponse response = new LoginResponse(token, buildUserResponse(user, activeAssignment, tenant), roles, activeRole, permissions, menus);
        return new AuthSessionResult(response, refreshTokenService.issue(user.getId(), activeAssignment.getId()));
    }

    private AuthSessionResult issueBasicSession(TenantSsoProviderEntity provider, TenantEntity tenant, String portal, String username) {
        UserExternalIdentityEntity binding = externalIdentityRepository.findByProviderIdAndSubject(provider.getId(), username)
            .orElseGet(() -> bindBasicByUsername(provider, username));
        UserAccount user = userAccountRepository.findById(binding.getUserId())
            .filter(account -> ACTIVE_STATUS.equals(account.getStatus()))
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_SSO_USER_DISABLED", "企业 Basic 对应账号不可用"));
        binding.markLoggedIn(user.getEmail(), user.getDisplayName(), clock.instant());
        externalIdentityRepository.save(binding);

        List<UserRoleAssignmentEntity> assignments = roleAssignmentRepository.findByUserIdOrderByDefaultAssignmentDesc(user.getId());
        UserRoleAssignmentEntity activeAssignment = assignments.stream()
            .filter(assignment -> portal.equals(assignment.getRole()))
            .filter(assignment -> tenant.getId().equals(assignment.getTenantId()))
            .findFirst()
            .orElseThrow(() -> {
                log.warn("企业 Basic 登录失败：用户没有入口权限 userId={} tenantId={} portal={} requestId={}", user.getId(), tenant.getId(), portal, RequestIds.current());
                return new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_ROLE_NOT_ALLOWED", "当前用户没有所选入口权限");
            });

        user.markLoggedIn(clock.instant());
        List<RoleInfoResponse> roles = buildRoleInfoList(assignments);
        RoleInfoResponse activeRole = buildRoleInfo(activeAssignment, tenant);
        List<MenuItemResponse> menus = menuService.resolveMenus(activeAssignment.getRole(), activeAssignment.getTenantId(), user.getId());
        List<String> permissions = menuService.resolvePermissions(activeAssignment.getRole(), activeAssignment.getTenantId(), user.getId());
        CurrentUserPrincipal principal = new CurrentUserPrincipal(user.getId(), user.getUsername(), activeAssignment.getTenantId(), activeAssignment.getRole(), portal, activeAssignment.getId());
        LoginResponse response = new LoginResponse(
            authTokenService.createToken(principal),
            buildUserResponse(user, activeAssignment, tenant),
            roles,
            activeRole,
            permissions,
            menus
        );
        log.info("企业 Basic 登录成功 userId={} tenantId={} providerId={} portal={} roleAssignmentId={} requestId={}", user.getId(), tenant.getId(), provider.getId(), portal, activeAssignment.getId(), RequestIds.current());
        return new AuthSessionResult(response, refreshTokenService.issue(user.getId(), activeAssignment.getId()));
    }

    private TenantSsoProviderEntity loadProvider(UUID tenantId, UUID providerId) {
        return providerRepository.findByIdAndTenantIdAndStatus(providerId, tenantId, ENABLED_STATUS)
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "AUTH_SSO_PROVIDER_NOT_AVAILABLE", "当前租户未启用该企业 SSO 身份源"));
    }

    private UserExternalIdentityEntity resolveBinding(TenantSsoProviderEntity provider, OidcExternalIdentity externalIdentity) {
        return externalIdentityRepository.findByProviderIdAndSubject(provider.getId(), externalIdentity.subject())
            .orElseGet(() -> bindByUsername(provider, externalIdentity));
    }

    private UserExternalIdentityEntity bindByUsername(TenantSsoProviderEntity provider, OidcExternalIdentity externalIdentity) {
        String username = firstNonBlank(externalIdentity.username(), externalIdentity.subject());
        UserAccount user = userAccountRepository.findByUsername(username)
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

    private UserExternalIdentityEntity bindBasicByUsername(TenantSsoProviderEntity provider, String username) {
        UserAccount user = userAccountRepository.findByUsername(username)
            .filter(account -> ACTIVE_STATUS.equals(account.getStatus()))
            .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "AUTH_SSO_USER_NOT_BOUND", "企业 Basic 对应的 Agentum 用户不存在或已停用"));
        return externalIdentityRepository.save(UserExternalIdentityEntity.create(
            user.getId(),
            provider.getTenantId(),
            provider.getId(),
            username,
            user.getEmail(),
            user.getDisplayName(),
            clock.instant()
        ));
    }

    private void verifyBasicSharedPassword(TenantSsoProviderEntity provider, String password) {
        String encrypted = provider.getEncryptedBasicPassword();
        if (encrypted == null || encrypted.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AUTH_SSO_BASIC_PASSWORD_MISSING", "企业 Basic 共享密码未配置");
        }
        String expected = fieldEncryptionService.decrypt(encrypted);
        if (!constantTimeEquals(expected, password)) {
            log.warn("企业 Basic 登录失败：共享密码不匹配 providerId={} requestId={}", provider.getId(), RequestIds.current());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_SSO_BASIC_PASSWORD_INVALID", "企业 Basic 登录凭据无效");
        }
    }

    private void verifyBasicSource(TenantSsoProviderEntity provider, String remoteAddress, String origin, String referer) {
        String allowedIps = provider.getAllowedIpRanges();
        if (allowedIps != null && !allowedIps.isBlank() && !matchesCsv(allowedIps, remoteAddress)) {
            log.warn("企业 Basic 登录失败：来源 IP 不在白名单 providerId={} remoteAddress={} requestId={}", provider.getId(), remoteAddress, RequestIds.current());
            throw new ApiException(HttpStatus.FORBIDDEN, "AUTH_SSO_BASIC_IP_DENIED", "当前来源 IP 不允许使用企业 Basic 单点入口");
        }
        String allowedDomains = provider.getAllowedDomains();
        if (allowedDomains != null && !allowedDomains.isBlank()) {
            String sourceHost = firstNonBlank(extractHost(origin), extractHost(referer));
            if (sourceHost == null || !matchesDomainCsv(allowedDomains, sourceHost)) {
                log.warn("企业 Basic 登录失败：来源域名不在白名单 providerId={} sourceHost={} requestId={}", provider.getId(), sourceHost, RequestIds.current());
                throw new ApiException(HttpStatus.FORBIDDEN, "AUTH_SSO_BASIC_DOMAIN_DENIED", "当前来源域名不允许使用企业 Basic 单点入口");
            }
        }
    }

    private static BasicCredential parseBasicCredential(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.regionMatches(true, 0, "Basic ", 0, 6)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_SSO_BASIC_HEADER_MISSING", "缺少企业 Basic 登录凭据");
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(authorizationHeader.substring(6).trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_SSO_BASIC_HEADER_INVALID", "企业 Basic 登录凭据格式不正确");
        }
        int separator = decoded.indexOf(':');
        if (separator <= 0) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_SSO_BASIC_HEADER_INVALID", "企业 Basic 登录凭据格式不正确");
        }
        return new BasicCredential(decoded.substring(0, separator), decoded.substring(separator + 1));
    }

    private static BasicPrincipal parseBasicPrincipal(String username) {
        int separator = username.indexOf('/');
        if (separator <= 0 || separator == username.length() - 1) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_SSO_BASIC_USERNAME_INVALID", "Basic 用户名格式应为 tenantCode/username");
        }
        return new BasicPrincipal(username.substring(0, separator), username.substring(separator + 1));
    }

    private static boolean matchesCsv(String csv, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (String item : csv.split(",")) {
            if (value.trim().equals(item.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesDomainCsv(String csv, String host) {
        String normalizedHost = host.toLowerCase();
        for (String item : csv.split(",")) {
            String domain = item.trim().toLowerCase();
            if (!domain.isBlank() && (normalizedHost.equals(domain) || normalizedHost.endsWith("." + domain))) {
                return true;
            }
        }
        return false;
    }

    private static String extractHost(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            return java.net.URI.create(url).getHost();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(expectedBytes, actualBytes);
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

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record BasicCredential(String username, String password) {
    }

    private record BasicPrincipal(String tenantCode, String username) {
    }
}
