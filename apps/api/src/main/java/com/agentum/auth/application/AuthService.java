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
import com.agentum.shared.api.RequestIds;
import com.agentum.tenant.domain.TenantEntity;
import com.agentum.tenant.infrastructure.TenantRepository;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
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
            log.warn("租户入口登录缺少 tenantId username={} portal={} requestId={}", request.username(), portal.code(), RequestIds.current());
            throw new ApiException(HttpStatus.BAD_REQUEST, "TENANT_REQUIRED", "请选择租户后再登录");
        }

        UserAccount user = userAccountRepository.findByUsername(request.username().trim())
            .orElseThrow(() -> {
                log.warn("登录失败：用户不存在 username={} portal={} tenantId={} requestId={}", request.username(), portal.code(), request.tenantId(), RequestIds.current());
                return invalidCredential();
            });

        if (!ACTIVE_STATUS.equals(user.getStatus()) || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("登录失败：账号状态或密码不匹配 userId={} username={} portal={} tenantId={} requestId={}", user.getId(), user.getUsername(), portal.code(), request.tenantId(), RequestIds.current());
            throw invalidCredential();
        }

        // 登录阶段必须确定活跃入口、租户和角色；前端展示入口不能替代后端成员关系校验。
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
        log.info(
            "登录成功 userId={} username={} portal={} tenantId={} role={} spaceCode={} requestId={}",
            user.getId(),
            user.getUsername(),
            portal.code(),
            session.tenant() == null ? null : session.tenant().getId(),
            session.roleCode(),
            session.spaceCode(),
            RequestIds.current()
        );
        return new LoginResponse(token, buildUserResponse(user, session));
    }

    @Transactional(readOnly = true)
    public AuthUserResponse currentUser(CurrentUserPrincipal principal) {
        UserAccount user = userAccountRepository.findById(principal.userId())
            .filter(account -> ACTIVE_STATUS.equals(account.getStatus()))
            .orElseThrow(() -> {
                log.warn("当前用户查询失败：Token 中用户不可用 userId={} tenantId={} requestId={}", principal.userId(), principal.tenantId(), RequestIds.current());
                return new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_TOKEN_INVALID", "登录状态无效，请重新登录");
            });

        TenantEntity tenant = principal.tenantId() == null
            ? null
            : tenantRepository.findByIdAndStatus(principal.tenantId(), ACTIVE_STATUS)
                .orElseThrow(() -> {
                    log.warn("当前用户查询失败：Token 中租户不可用 userId={} tenantId={} requestId={}", principal.userId(), principal.tenantId(), RequestIds.current());
                    return new ApiException(HttpStatus.UNAUTHORIZED, "TENANT_NOT_AVAILABLE", "当前租户不可用");
                });

        return buildUserResponse(user, new AuthSession(tenant, principal.role(), principal.spaceCode()));
    }

    private AuthSession resolveSystemSession(UserAccount user, PortalType portal) {
        // 系统管理员不绑定租户，只能通过系统级角色关系进入平台治理入口。
        RoleEntity role = systemUserRoleRepository.findByUserId(user.getId()).stream()
            .map(systemRole -> roleRepository.findByIdAndStatus(systemRole.getRoleId(), ACTIVE_STATUS))
            .flatMap(OptionalUtils::stream)
            .filter(candidate -> portal.allowsRole(candidate.getCode()))
            .findFirst()
            .orElseThrow(() -> {
                log.warn("系统入口登录失败：用户没有系统入口角色 userId={} portal={} requestId={}", user.getId(), portal.code(), RequestIds.current());
                return roleNotAllowed();
            });

        return new AuthSession(null, role.getCode(), "system");
    }

    private AuthSession resolveTenantSession(UserAccount user, PortalType portal, UUID tenantId) {
        // 业务和空间管理入口必须锁定租户成员关系，避免用户仅凭前端入口访问其他租户。
        TenantEntity tenant = tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> {
                log.warn("租户入口登录失败：租户不可用 userId={} portal={} tenantId={} requestId={}", user.getId(), portal.code(), tenantId, RequestIds.current());
                return new ApiException(HttpStatus.BAD_REQUEST, "TENANT_NOT_AVAILABLE", "当前租户不可用或已停用");
            });

        List<UserMembershipEntity> memberships = userMembershipRepository.findByUserIdAndTenantIdAndStatus(user.getId(), tenantId, ACTIVE_STATUS);

        return memberships.stream()
            .sorted(Comparator.comparing(UserMembershipEntity::isDefaultMembership).reversed())
            .map(membership -> roleRepository.findByIdAndStatus(membership.getRoleId(), ACTIVE_STATUS)
                .filter(role -> portal.allowsRole(role.getCode()))
                .map(role -> new AuthSession(tenant, role.getCode(), membership.getSpaceCode())))
            .flatMap(OptionalUtils::stream)
            .findFirst()
            .orElseThrow(() -> {
                log.warn("租户入口登录失败：用户没有入口匹配角色 userId={} portal={} tenantId={} requestId={}", user.getId(), portal.code(), tenantId, RequestIds.current());
                return roleNotAllowed();
            });
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
