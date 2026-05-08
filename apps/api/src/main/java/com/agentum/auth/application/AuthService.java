package com.agentum.auth.application;

import com.agentum.auth.domain.PortalType;
import com.agentum.auth.domain.UserAccount;
import com.agentum.auth.infrastructure.SystemUserRoleRepository;
import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.auth.interfaces.AuthUserResponse;
import com.agentum.auth.interfaces.LoginRequest;
import com.agentum.auth.interfaces.LoginResponse;
import com.agentum.organization.domain.UserMembershipEntity;
import com.agentum.organization.infrastructure.UserMembershipRepository;
import com.agentum.permission.domain.RoleEntity;
import com.agentum.permission.infrastructure.RoleRepository;
import com.agentum.shared.api.ApiException;
import com.agentum.tenant.domain.TenantEntity;
import com.agentum.tenant.infrastructure.TenantRepository;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final String ACTIVE_STATUS = "active";

    private final UserAccountRepository userAccountRepository;
    private final TenantRepository tenantRepository;
    private final UserMembershipRepository userMembershipRepository;
    private final RoleRepository roleRepository;
    private final SystemUserRoleRepository systemUserRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthTokenService authTokenService;
    private final Clock clock;

    @Autowired
    public AuthService(
        UserAccountRepository userAccountRepository,
        TenantRepository tenantRepository,
        UserMembershipRepository userMembershipRepository,
        RoleRepository roleRepository,
        SystemUserRoleRepository systemUserRoleRepository,
        PasswordEncoder passwordEncoder,
        AuthTokenService authTokenService
    ) {
        this(
            userAccountRepository,
            tenantRepository,
            userMembershipRepository,
            roleRepository,
            systemUserRoleRepository,
            passwordEncoder,
            authTokenService,
            Clock.systemUTC()
        );
    }

    AuthService(
        UserAccountRepository userAccountRepository,
        TenantRepository tenantRepository,
        UserMembershipRepository userMembershipRepository,
        RoleRepository roleRepository,
        SystemUserRoleRepository systemUserRoleRepository,
        PasswordEncoder passwordEncoder,
        AuthTokenService authTokenService,
        Clock clock
    ) {
        this.userAccountRepository = userAccountRepository;
        this.tenantRepository = tenantRepository;
        this.userMembershipRepository = userMembershipRepository;
        this.roleRepository = roleRepository;
        this.systemUserRoleRepository = systemUserRoleRepository;
        this.passwordEncoder = passwordEncoder;
        this.authTokenService = authTokenService;
        this.clock = clock;
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        PortalType portal = PortalType.fromCode(request.portal());

        if (portal.isTenantScoped() && request.tenantId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TENANT_REQUIRED", "请选择租户后再登录");
        }

        UserAccount user = userAccountRepository.findByUsername(request.username().trim())
            .orElseThrow(AuthService::invalidCredential);

        if (!ACTIVE_STATUS.equals(user.getStatus()) || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredential();
        }

        AuthSession session = portal == PortalType.SYSTEM_ADMIN
            ? resolveSystemSession(user, portal)
            : resolveTenantSession(user, portal, request.tenantId());

        user.markLoggedIn(clock.instant());

        CurrentUserPrincipal principal = new CurrentUserPrincipal(
            user.getId(),
            user.getUsername(),
            session.tenant() == null ? null : session.tenant().getId(),
            session.roleCode(),
            portal.code(),
            session.spaceCode()
        );

        String token = authTokenService.createToken(principal);
        return new LoginResponse(token, buildUserResponse(user, session));
    }

    @Transactional(readOnly = true)
    public AuthUserResponse currentUser(CurrentUserPrincipal principal) {
        UserAccount user = userAccountRepository.findById(principal.userId())
            .filter(account -> ACTIVE_STATUS.equals(account.getStatus()))
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_TOKEN_INVALID", "登录状态无效，请重新登录"));

        TenantEntity tenant = principal.tenantId() == null
            ? null
            : tenantRepository.findByIdAndStatus(principal.tenantId(), ACTIVE_STATUS)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "TENANT_NOT_AVAILABLE", "当前租户不可用"));

        return buildUserResponse(user, new AuthSession(tenant, principal.role(), principal.spaceCode()));
    }

    private AuthSession resolveSystemSession(UserAccount user, PortalType portal) {
        RoleEntity role = systemUserRoleRepository.findByUserId(user.getId()).stream()
            .map(systemRole -> roleRepository.findByIdAndStatus(systemRole.getRoleId(), ACTIVE_STATUS))
            .flatMap(OptionalUtils::stream)
            .filter(candidate -> portal.allowsRole(candidate.getCode()))
            .findFirst()
            .orElseThrow(AuthService::roleNotAllowed);

        return new AuthSession(null, role.getCode(), "system");
    }

    private AuthSession resolveTenantSession(UserAccount user, PortalType portal, UUID tenantId) {
        TenantEntity tenant = tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "TENANT_NOT_AVAILABLE", "当前租户不可用或已停用"));

        List<UserMembershipEntity> memberships = userMembershipRepository.findByUserIdAndTenantIdAndStatus(user.getId(), tenantId, ACTIVE_STATUS);

        return memberships.stream()
            .sorted(Comparator.comparing(UserMembershipEntity::isDefaultMembership).reversed())
            .map(membership -> roleRepository.findByIdAndStatus(membership.getRoleId(), ACTIVE_STATUS)
                .filter(role -> portal.allowsRole(role.getCode()))
                .map(role -> new AuthSession(tenant, role.getCode(), membership.getSpaceCode())))
            .flatMap(OptionalUtils::stream)
            .findFirst()
            .orElseThrow(AuthService::roleNotAllowed);
    }

    private AuthUserResponse buildUserResponse(UserAccount user, AuthSession session) {
        TenantEntity tenant = session.tenant();
        String tenantName = tenant == null ? "平台管理" : tenant.getName();
        String tenantCode = tenant == null ? "SYSTEM" : tenant.getCode();
        String organization = tenant == null ? "Agentum 平台" : tenant.getName() + " / " + session.spaceCode();
        String space = tenant == null ? "全局系统管理" : session.spaceCode();

        return new AuthUserResponse(
            user.getId().toString(),
            user.getUsername(),
            user.getDisplayName(),
            user.getEmail() == null ? "" : user.getEmail(),
            user.getAvatarUrl() == null ? "" : user.getAvatarUrl(),
            toClientRole(session.roleCode()),
            tenant == null ? null : tenant.getId().toString(),
            tenantName,
            tenantCode,
            organization,
            space,
            user.getLastLoginAt() == null ? "" : user.getLastLoginAt().toString()
        );
    }

    private static String toClientRole(String roleCode) {
        return switch (roleCode.toLowerCase(Locale.ROOT)) {
            case "workflow_designer" -> "designer";
            case "tenant_admin" -> "space_admin";
            default -> roleCode;
        };
    }

    private static ApiException invalidCredential() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_CREDENTIAL_INVALID", "用户名、密码或入口权限不正确");
    }

    private static ApiException roleNotAllowed() {
        return new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_ROLE_NOT_ALLOWED", "当前用户没有所选入口权限");
    }

    private record AuthSession(TenantEntity tenant, String roleCode, String spaceCode) {
    }

    private static final class OptionalUtils {
        private OptionalUtils() {
        }

        private static <T> java.util.stream.Stream<T> stream(java.util.Optional<T> optional) {
            return optional.stream();
        }
    }

}
