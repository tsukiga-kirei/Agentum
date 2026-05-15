package com.agentum.auth.application;

import com.agentum.auth.domain.PortalType;
import com.agentum.auth.domain.UserAccount;
import com.agentum.auth.domain.UserRoleAssignmentEntity;
import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.auth.infrastructure.UserRoleAssignmentRepository;
import com.agentum.auth.interfaces.AuthUserResponse;
import com.agentum.auth.interfaces.LoginRequest;
import com.agentum.auth.interfaces.LoginResponse;
import com.agentum.auth.interfaces.MenuItemResponse;
import com.agentum.auth.interfaces.RoleInfoResponse;
import com.agentum.auth.interfaces.SwitchRoleResponse;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.agentum.tenant.domain.TenantEntity;
import com.agentum.tenant.infrastructure.TenantRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String ACTIVE_STATUS = "active";

    private final UserAccountRepository userAccountRepository;
    private final TenantRepository tenantRepository;
    private final UserRoleAssignmentRepository roleAssignmentRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthTokenService authTokenService;
    private final MenuService menuService;
    private final Clock clock;

    @Autowired
    public AuthService(
        UserAccountRepository userAccountRepository,
        TenantRepository tenantRepository,
        UserRoleAssignmentRepository roleAssignmentRepository,
        PasswordEncoder passwordEncoder,
        AuthTokenService authTokenService,
        MenuService menuService
    ) {
        this(userAccountRepository, tenantRepository, roleAssignmentRepository,
            passwordEncoder, authTokenService, menuService, Clock.systemUTC());
    }

    AuthService(
        UserAccountRepository userAccountRepository,
        TenantRepository tenantRepository,
        UserRoleAssignmentRepository roleAssignmentRepository,
        PasswordEncoder passwordEncoder,
        AuthTokenService authTokenService,
        MenuService menuService,
        Clock clock
    ) {
        this.userAccountRepository = userAccountRepository;
        this.tenantRepository = tenantRepository;
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.passwordEncoder = passwordEncoder;
        this.authTokenService = authTokenService;
        this.menuService = menuService;
        this.clock = clock;
    }

    // -------------------------------------------------------------------------
    // login
    // -------------------------------------------------------------------------

    @Transactional
    public LoginResponse login(LoginRequest request) {
        PortalType portal = PortalType.fromCode(request.portal());

        if (portal.isTenantScoped() && request.tenantId() == null) {
            log.warn("租户入口登录缺少 tenantId username={} portal={} requestId={}", request.username(), portal.code(), RequestIds.current());
            throw new ApiException(HttpStatus.BAD_REQUEST, "TENANT_REQUIRED", "请选择租户后再登录");
        }

        // 验证用户身份
        UserAccount user = findActiveUser(request.username());
        verifyPassword(user, request.password());

        // 查询用户的所有系统级角色分配
        List<UserRoleAssignmentEntity> allAssignments = roleAssignmentRepository.findByUserIdOrderByDefaultAssignmentDesc(user.getId());

        // 查找匹配当前入口的活跃角色分配
        UserRoleAssignmentEntity activeAssignment = findMatchingAssignment(allAssignments, portal, request.tenantId(), user.getId());

        // 校验租户状态（租户入口必须确认租户可用）
        TenantEntity tenant = null;
        if (activeAssignment.getTenantId() != null) {
            tenant = tenantRepository.findByIdAndStatus(activeAssignment.getTenantId(), ACTIVE_STATUS)
                .orElseThrow(() -> {
                    log.warn("登录失败：租户不可用 userId={} tenantId={} requestId={}", user.getId(), activeAssignment.getTenantId(), RequestIds.current());
                    return new ApiException(HttpStatus.BAD_REQUEST, "TENANT_NOT_AVAILABLE", "当前租户不可用或已停用");
                });
        }

        user.markLoggedIn(clock.instant());

        // 构建角色列表（过滤掉已停用租户的角色）
        List<RoleInfoResponse> roles = buildRoleInfoList(allAssignments);
        RoleInfoResponse activeRole = buildRoleInfo(activeAssignment, tenant);

        // 计算菜单和权限
        List<MenuItemResponse> menus = menuService.resolveMenus(activeAssignment.getRole());
        List<String> permissions = List.of();

        // 签发 token
        CurrentUserPrincipal principal = buildPrincipal(user, activeAssignment, portal.code());
        String token = authTokenService.createToken(principal);

        log.info(
            "登录成功 userId={} username={} portal={} tenantId={} role={} roleAssignmentId={} requestId={}",
            user.getId(), user.getUsername(), portal.code(),
            activeAssignment.getTenantId(), activeAssignment.getRole(),
            activeAssignment.getId(), RequestIds.current()
        );

        AuthUserResponse userResponse = buildUserResponse(user, activeAssignment, tenant);
        return new LoginResponse(token, userResponse, roles, activeRole, permissions, menus);
    }

    // -------------------------------------------------------------------------
    // currentUser（/api/auth/me）
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public LoginResponse currentUser(CurrentUserPrincipal principal) {
        UserAccount user = userAccountRepository.findById(principal.userId())
            .filter(account -> ACTIVE_STATUS.equals(account.getStatus()))
            .orElseThrow(() -> {
                log.warn("当前用户查询失败：Token 中用户不可用 userId={} requestId={}", principal.userId(), RequestIds.current());
                return new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_TOKEN_INVALID", "登录状态无效，请重新登录");
            });

        // 查询所有角色分配
        List<UserRoleAssignmentEntity> allAssignments = roleAssignmentRepository.findByUserIdOrderByDefaultAssignmentDesc(user.getId());

        // 从 token 中的 roleAssignmentId 定位当前活跃角色
        UserRoleAssignmentEntity activeAssignment = findAssignmentById(allAssignments, principal.roleAssignmentId(), user.getId());

        TenantEntity tenant = null;
        if (activeAssignment.getTenantId() != null) {
            // /me 用于刷新恢复会话，不能把已停用租户当作有效上下文返回，否则旧 token 会继续暴露菜单入口。
            tenant = tenantRepository.findByIdAndStatus(activeAssignment.getTenantId(), ACTIVE_STATUS)
                .orElseThrow(() -> {
                    log.warn(
                        "当前用户查询失败：租户不可用 userId={} tenantId={} roleAssignmentId={} requestId={}",
                        user.getId(),
                        activeAssignment.getTenantId(),
                        activeAssignment.getId(),
                        RequestIds.current()
                    );
                    return new ApiException(HttpStatus.BAD_REQUEST, "TENANT_NOT_AVAILABLE", "当前租户不可用或已停用");
                });
        }

        List<RoleInfoResponse> roles = buildRoleInfoList(allAssignments);
        RoleInfoResponse activeRole = buildRoleInfo(activeAssignment, tenant);
        List<MenuItemResponse> menus = menuService.resolveMenus(activeAssignment.getRole());

        AuthUserResponse userResponse = buildUserResponse(user, activeAssignment, tenant);
        // 复用 LoginResponse 结构（不含 token），前端通过已有 token 识别
        return new LoginResponse(null, userResponse, roles, activeRole, List.of(), menus);
    }

    // -------------------------------------------------------------------------
    // switchRole（角色切换）
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public SwitchRoleResponse switchRole(CurrentUserPrincipal currentPrincipal, UUID targetRoleId) {
        UserAccount user = userAccountRepository.findById(currentPrincipal.userId())
            .filter(account -> ACTIVE_STATUS.equals(account.getStatus()))
            .orElseThrow(() -> {
                log.warn("角色切换失败：用户不可用 userId={} requestId={}", currentPrincipal.userId(), RequestIds.current());
                return new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_TOKEN_INVALID", "登录状态无效，请重新登录");
            });

        // 校验目标角色属于当前用户
        UserRoleAssignmentEntity targetAssignment = roleAssignmentRepository.findById(targetRoleId)
            .filter(assignment -> assignment.getUserId().equals(user.getId()))
            .orElseThrow(() -> {
                log.warn("角色切换失败：角色不属于该用户 userId={} targetRoleId={} requestId={}",
                    currentPrincipal.userId(), targetRoleId, RequestIds.current());
                return new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_ROLE_SWITCH_DENIED", "角色切换失败");
            });

        // 校验目标租户状态
        TenantEntity tenant = null;
        if (targetAssignment.getTenantId() != null) {
            tenant = tenantRepository.findByIdAndStatus(targetAssignment.getTenantId(), ACTIVE_STATUS)
                .orElseThrow(() -> {
                    log.warn("角色切换失败：目标租户不可用 userId={} tenantId={} requestId={}",
                        user.getId(), targetAssignment.getTenantId(), RequestIds.current());
                    return new ApiException(HttpStatus.BAD_REQUEST, "TENANT_NOT_AVAILABLE", "目标租户不可用或已停用");
                });
        }

        // 签发新 token（切换角色后旧 token 仍有效直到过期，后续接入 Redis 后可加黑名单机制）
        String portalCode = targetAssignment.getRole(); // 角色切换后 portal 与 role 一致
        CurrentUserPrincipal newPrincipal = buildPrincipal(user, targetAssignment, portalCode);
        String newToken = authTokenService.createToken(newPrincipal);

        RoleInfoResponse activeRole = buildRoleInfo(targetAssignment, tenant);
        List<MenuItemResponse> menus = menuService.resolveMenus(targetAssignment.getRole());
        AuthUserResponse userResponse = buildUserResponse(user, targetAssignment, tenant);

        log.info(
            "角色切换成功 userId={} fromRole={} toRole={} tenantId={} requestId={}",
            user.getId(), currentPrincipal.role(), targetAssignment.getRole(),
            targetAssignment.getTenantId(), RequestIds.current()
        );

        return new SwitchRoleResponse(newToken, userResponse, activeRole, List.of(), menus);
    }

    // -------------------------------------------------------------------------
    // 私有方法
    // -------------------------------------------------------------------------

    private UserAccount findActiveUser(String username) {
        return userAccountRepository.findByUsername(username.trim())
            .filter(account -> ACTIVE_STATUS.equals(account.getStatus()))
            .orElseThrow(() -> {
                log.warn("登录失败：用户不存在或已停用 username={} requestId={}", username, RequestIds.current());
                return invalidCredential();
            });
    }

    private void verifyPassword(UserAccount user, String password) {
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            log.warn("登录失败：密码不匹配 userId={} requestId={}", user.getId(), RequestIds.current());
            throw invalidCredential();
        }
    }

    /**
     * 在用户的角色分配列表中，查找匹配当前入口和租户的角色。
     * 系统管理员入口查找 role='system_admin' 且 tenant_id IS NULL 的记录。
     * 租户入口查找 role=portal.code() 且 tenant_id 匹配的记录。
     */
    private UserRoleAssignmentEntity findMatchingAssignment(
        List<UserRoleAssignmentEntity> assignments,
        PortalType portal,
        UUID tenantId,
        UUID userId
    ) {
        String expectedRole = portal.code();

        return assignments.stream()
            .filter(a -> a.getRole().equals(expectedRole))
            .filter(a -> portal == PortalType.SYSTEM_ADMIN
                ? a.getTenantId() == null
                : tenantId != null && tenantId.equals(a.getTenantId()))
            .findFirst()
            .orElseThrow(() -> {
                log.warn("登录失败：用户没有所选入口权限 userId={} portal={} tenantId={} requestId={}",
                    userId, portal.code(), tenantId, RequestIds.current());
                return roleNotAllowed();
            });
    }

    /**
     * 从角色列表中按 ID 定位活跃角色（用于 /me 和 switch-role 场景）。
     */
    private UserRoleAssignmentEntity findAssignmentById(
        List<UserRoleAssignmentEntity> assignments,
        UUID roleAssignmentId,
        UUID userId
    ) {
        if (roleAssignmentId == null) {
            // 兼容旧 token（没有 roleAssignmentId），取默认角色
            return assignments.stream().findFirst()
                .orElseThrow(() -> {
                    log.warn("当前用户无有效角色分配 userId={} requestId={}", userId, RequestIds.current());
                    return new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_NO_ROLE", "当前用户没有任何角色分配");
                });
        }

        return assignments.stream()
            .filter(a -> a.getId().equals(roleAssignmentId))
            .findFirst()
            .orElseThrow(() -> {
                log.warn("角色分配不存在或已变更 userId={} roleAssignmentId={} requestId={}",
                    userId, roleAssignmentId, RequestIds.current());
                return new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_TOKEN_INVALID", "登录状态无效，请重新登录");
            });
    }

    /**
     * 构建完整角色信息列表，跳过已停用租户的角色。
     */
    private List<RoleInfoResponse> buildRoleInfoList(List<UserRoleAssignmentEntity> assignments) {
        // 批量缓存租户名称，避免 N+1 查询
        Map<UUID, TenantEntity> tenantCache = new HashMap<>();
        List<RoleInfoResponse> roles = new ArrayList<>();

        for (UserRoleAssignmentEntity assignment : assignments) {
            TenantEntity tenant = null;
            if (assignment.getTenantId() != null) {
                tenant = tenantCache.computeIfAbsent(assignment.getTenantId(),
                    id -> tenantRepository.findByIdAndStatus(id, ACTIVE_STATUS).orElse(null));
                // 跳过已停用租户的角色
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
        String tenantName = tenant == null ? "平台管理" : tenant.getName();
        String tenantCode = tenant == null ? "SYSTEM" : tenant.getCode();
        String organization = tenant == null ? "Agentum 平台" : tenant.getName();
        String space = tenant == null ? "全局系统管理" : "默认空间";

        return new AuthUserResponse(
            user.getId().toString(),
            user.getUsername(),
            user.getDisplayName(),
            user.getEmail() == null ? "" : user.getEmail(),
            user.getAvatarUrl() == null ? "" : user.getAvatarUrl(),
            assignment.getRole(),
            assignment.getTenantId() == null ? null : assignment.getTenantId().toString(),
            tenantName,
            tenantCode,
            organization,
            space,
            user.getLastLoginAt() == null ? "" : user.getLastLoginAt().toString()
        );
    }

    private CurrentUserPrincipal buildPrincipal(UserAccount user, UserRoleAssignmentEntity assignment, String portalCode) {
        return new CurrentUserPrincipal(
            user.getId(),
            user.getUsername(),
            assignment.getTenantId(),
            assignment.getRole(),
            portalCode,
            assignment.getTenantId() == null ? "system" : "default",
            assignment.getId()
        );
    }

    private static ApiException invalidCredential() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_CREDENTIAL_INVALID", "用户名、密码或入口权限不正确");
    }

    private static ApiException roleNotAllowed() {
        return new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_ROLE_NOT_ALLOWED", "当前用户没有所选入口权限");
    }
}
