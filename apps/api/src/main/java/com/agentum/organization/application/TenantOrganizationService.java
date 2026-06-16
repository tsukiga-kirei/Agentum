package com.agentum.organization.application;

import com.agentum.auth.domain.UserAccount;
import com.agentum.auth.domain.UserRoleAssignmentEntity;
import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.auth.infrastructure.UserRoleAssignmentRepository;
import com.agentum.organization.domain.DepartmentEntity;
import com.agentum.organization.domain.TenantOrgRoleEntity;
import com.agentum.organization.domain.UserMembershipEntity;
import com.agentum.organization.domain.UserMembershipRoleEntity;
import com.agentum.organization.infrastructure.DepartmentRepository;
import com.agentum.organization.infrastructure.TenantOrgRoleRepository;
import com.agentum.organization.infrastructure.UserMembershipRepository;
import com.agentum.organization.infrastructure.UserMembershipRoleRepository;
import com.agentum.organization.interfaces.CreateMemberRequest;
import com.agentum.organization.interfaces.CreateDepartmentRequest;
import com.agentum.organization.interfaces.CreatePageGrantRequest;
import com.agentum.organization.interfaces.CreateResourceGrantRequest;
import com.agentum.organization.interfaces.CreateTenantOrgRoleRequest;
import com.agentum.organization.interfaces.CreateTenantRoleRequest;
import com.agentum.organization.interfaces.DepartmentResponse;
import com.agentum.organization.interfaces.GrantPrincipalRequest;
import com.agentum.organization.interfaces.GrantPrincipalResponse;
import com.agentum.organization.interfaces.MemberResponse;
import com.agentum.organization.interfaces.MembershipResponse;
import com.agentum.organization.interfaces.MembershipRoleResponse;
import com.agentum.organization.interfaces.PageGrantItemResponse;
import com.agentum.organization.interfaces.PageGrantResponse;
import com.agentum.organization.interfaces.PrincipalGrantUsageResponse;
import com.agentum.organization.interfaces.ResourceGrantItemRequest;
import com.agentum.organization.interfaces.ResourceGrantItemResponse;
import com.agentum.organization.interfaces.ResourceGrantResponse;
import com.agentum.organization.interfaces.RoleResponse;
import com.agentum.organization.interfaces.TenantOrgRoleResponse;
import com.agentum.organization.interfaces.TenantOrganizationOverviewResponse;
import com.agentum.organization.interfaces.TenantResourceOptionResponse;
import com.agentum.organization.interfaces.TenantResourcePermissionRequest;
import com.agentum.organization.interfaces.TenantResourcePermissionResponse;
import com.agentum.organization.interfaces.UpdateMembershipDepartmentRequest;
import com.agentum.organization.interfaces.UpdateMembershipRoleRequest;
import com.agentum.organization.interfaces.UpdateMembershipStatusRequest;
import com.agentum.organization.interfaces.UpdateMemberProfileRequest;
import com.agentum.organization.interfaces.UpdateDepartmentRequest;
import com.agentum.organization.interfaces.UpdateTenantOrgRoleRequest;
import com.agentum.organization.interfaces.UpdateTenantRoleRequest;
import com.agentum.permission.domain.ResourceGrantEntity;
import com.agentum.permission.domain.PageGrantEntity;
import com.agentum.permission.domain.RoleEntity;
import com.agentum.permission.infrastructure.PageGrantRepository;
import com.agentum.permission.infrastructure.ResourceGrantRepository;
import com.agentum.permission.infrastructure.RoleRepository;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.agentum.shared.util.UsernameValidator;
import com.agentum.shared.pagination.PageQuery;
import com.agentum.shared.pagination.PageResponse;
import com.agentum.shared.pagination.PageableFactory;
import com.agentum.shared.pagination.SortWhitelist;
import com.agentum.system.domain.SystemCapabilityEntity;
import com.agentum.system.domain.TenantCapabilityGrantEntity;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import com.agentum.system.infrastructure.TenantCapabilityGrantRepository;
import com.agentum.tenant.domain.TenantEntity;
import com.agentum.tenant.infrastructure.TenantRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantOrganizationService {

    private static final Logger log = LoggerFactory.getLogger(TenantOrganizationService.class);
    private static final String ACTIVE_STATUS = "active";
    private static final SortWhitelist TENANT_ORG_ROLE_SORT = SortWhitelist.of("updatedAt", "name", "status", "createdAt", "updatedAt");
    private static final Set<String> ALLOWED_ORG_ROLE_STATUS = Set.of("active", "disabled");
    private static final Set<String> ALLOWED_MEMBERSHIP_STATUS = Set.of("active", "disabled");
    private static final Set<String> ALLOWED_PAGE_PERMISSIONS = Set.of("workbench", "designer", "assets", "audit");
    private static final Set<String> ALLOWED_RESOURCE_TYPES = Set.of("mcp", "skill", "prompt_template", "delivery");
    private static final Set<String> ALLOWED_RESOURCE_ACTIONS = Set.of("use", "view", "execute", "manage");
    private static final Set<String> ALLOWED_PRINCIPAL_TYPES = Set.of("role", "department", "user");

    private final TenantRepository tenantRepository;
    private final UserAccountRepository userAccountRepository;
    private final UserRoleAssignmentRepository userRoleAssignmentRepository;
    private final UserMembershipRepository userMembershipRepository;
    private final UserMembershipRoleRepository userMembershipRoleRepository;
    private final DepartmentRepository departmentRepository;
    private final RoleRepository roleRepository;
    private final PageGrantRepository pageGrantRepository;
    private final ResourceGrantRepository resourceGrantRepository;
    private final TenantOrgRoleRepository tenantOrgRoleRepository;
    private final TenantCapabilityGrantRepository tenantCapabilityGrantRepository;
    private final SystemCapabilityRepository systemCapabilityRepository;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    public TenantOrganizationService(
        TenantRepository tenantRepository,
        UserAccountRepository userAccountRepository,
        UserRoleAssignmentRepository userRoleAssignmentRepository,
        UserMembershipRepository userMembershipRepository,
        UserMembershipRoleRepository userMembershipRoleRepository,
        DepartmentRepository departmentRepository,
        RoleRepository roleRepository,
        PageGrantRepository pageGrantRepository,
        ResourceGrantRepository resourceGrantRepository,
        TenantOrgRoleRepository tenantOrgRoleRepository,
        TenantCapabilityGrantRepository tenantCapabilityGrantRepository,
        SystemCapabilityRepository systemCapabilityRepository,
        PasswordEncoder passwordEncoder,
        ObjectMapper objectMapper
    ) {
        this.tenantRepository = tenantRepository;
        this.userAccountRepository = userAccountRepository;
        this.userRoleAssignmentRepository = userRoleAssignmentRepository;
        this.userMembershipRepository = userMembershipRepository;
        this.userMembershipRoleRepository = userMembershipRoleRepository;
        this.departmentRepository = departmentRepository;
        this.roleRepository = roleRepository;
        this.pageGrantRepository = pageGrantRepository;
        this.resourceGrantRepository = resourceGrantRepository;
        this.tenantOrgRoleRepository = tenantOrgRoleRepository;
        this.tenantCapabilityGrantRepository = tenantCapabilityGrantRepository;
        this.systemCapabilityRepository = systemCapabilityRepository;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TenantOrganizationOverviewResponse createMember(UUID tenantId, UUID operatorUserId, CreateMemberRequest request) {
        tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> {
                log.warn("成员创建失败：租户不可用 tenantId={} operatorUserId={} requestId={}", tenantId, operatorUserId, RequestIds.current());
                return new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "租户不存在或已停用");
            });

        String username = normalizeRequired(request.username());
        validateUsername(username);

        if (userAccountRepository.existsByUsername(username)) {
            log.warn("成员创建失败：用户名已存在 tenantId={} operatorUserId={} username={} requestId={}", tenantId, operatorUserId, username, RequestIds.current());
            throw new ApiException(HttpStatus.CONFLICT, "ORG_USER_USERNAME_EXISTS", "用户名已存在，请换一个用户名");
        }

        UUID roleId = request.roleId();

        if (roleId == null) {
            log.warn("成员创建失败：缺少角色 tenantId={} operatorUserId={} username={} requestId={}", tenantId, operatorUserId, username, RequestIds.current());
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_MEMBER_ROLE_REQUIRED", "请选择成员角色");
        }

        RoleEntity role = roleRepository.findByIdAndTenantIdAndStatus(roleId, tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> {
                log.warn("成员创建失败：角色不可用 tenantId={} operatorUserId={} roleId={} username={} requestId={}", tenantId, operatorUserId, roleId, username, RequestIds.current());
                return new ApiException(HttpStatus.BAD_REQUEST, "ORG_ROLE_NOT_AVAILABLE", "所选角色不属于当前租户或已停用");
            });
        assertNotTenantAdminRole(role);

        UUID departmentId = request.departmentId();

        if (departmentId != null) {
            departmentRepository.findByIdAndTenantIdAndStatus(departmentId, tenantId, ACTIVE_STATUS)
                .orElseThrow(() -> {
                    log.warn("成员创建失败：部门不可用 tenantId={} operatorUserId={} departmentId={} username={} requestId={}", tenantId, operatorUserId, departmentId, username, RequestIds.current());
                    return new ApiException(HttpStatus.BAD_REQUEST, "ORG_DEPARTMENT_NOT_AVAILABLE", "所选部门不属于当前租户或已停用");
                });
        }

        // 成员创建是权限治理的第一批写动作：当前直接生成本地密码哈希，后续应替换为邀请链接、首次登录改密和审计事件。
        UserAccount user = UserAccount.create(
            username,
            passwordEncoder.encode(request.password()),
            normalizeRequired(request.displayName()),
            normalizeOptional(request.email())
        );
        userAccountRepository.save(user);

        UserMembershipEntity membership = UserMembershipEntity.create(
            tenantId,
            user.getId(),
            departmentId
        );
        userMembershipRepository.save(membership);
        userMembershipRoleRepository.save(UserMembershipRoleEntity.create(membership.getId(), roleId));
        ensureLoginAssignment(user.getId(), tenantId, role, membership.isDefaultMembership());
        log.info(
            "成员创建成功 tenantId={} operatorUserId={} userId={} username={} roleId={} departmentId={} requestId={}",
            tenantId,
            operatorUserId,
            user.getId(),
            username,
            roleId,
            departmentId,
            RequestIds.current()
        );

        return getOverview(tenantId);
    }

    @Transactional
    public TenantOrganizationOverviewResponse createDepartment(UUID tenantId, UUID operatorUserId, CreateDepartmentRequest request) {
        tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> {
                log.warn("部门创建失败：租户不可用 tenantId={} operatorUserId={} requestId={}", tenantId, operatorUserId, RequestIds.current());
                return new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "租户不存在或已停用");
            });

        UUID parentId = request.parentId();

        if (parentId != null) {
            departmentRepository.findByIdAndTenantIdAndStatus(parentId, tenantId, ACTIVE_STATUS)
                .orElseThrow(() -> {
                    log.warn("部门创建失败：上级部门不可用 tenantId={} operatorUserId={} parentId={} requestId={}", tenantId, operatorUserId, parentId, RequestIds.current());
                    return new ApiException(HttpStatus.BAD_REQUEST, "ORG_PARENT_DEPARTMENT_NOT_AVAILABLE", "上级部门不属于当前租户或已停用");
                });
        }

        String code = generateDepartmentCode(tenantId, request.name());

        // 部门树是待办分派和资源过滤的基础；编码由后端生成，避免前端暴露技术字段给租户管理员。
        DepartmentEntity department = DepartmentEntity.create(
            tenantId,
            parentId,
            normalizeRequired(request.name()),
            code,
            request.sortOrder() == null ? 0 : request.sortOrder()
        );
        departmentRepository.save(department);
        log.info(
            "部门创建成功 tenantId={} operatorUserId={} departmentId={} parentId={} code={} sortOrder={} requestId={}",
            tenantId,
            operatorUserId,
            department.getId(),
            parentId,
            department.getCode(),
            department.getSortOrder(),
            RequestIds.current()
        );

        return getOverview(tenantId);
    }

    @Transactional
    public TenantOrganizationOverviewResponse updateDepartment(UUID tenantId, UUID operatorUserId, UUID departmentId, UpdateDepartmentRequest request) {
        tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "租户不存在或已停用"));

        DepartmentEntity department = departmentRepository.findByIdAndTenantId(departmentId, tenantId)
            .orElseThrow(() -> {
                log.warn("部门更新失败：部门不存在 tenantId={} operatorUserId={} departmentId={} requestId={}", tenantId, operatorUserId, departmentId, RequestIds.current());
                return new ApiException(HttpStatus.NOT_FOUND, "ORG_DEPARTMENT_NOT_FOUND", "部门不存在");
            });

        UUID parentId = request.parentId();
        if (parentId != null && parentId.equals(departmentId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_DEPARTMENT_PARENT_INVALID", "上级部门不能选择自己");
        }
        if (parentId != null) {
            departmentRepository.findByIdAndTenantIdAndStatus(parentId, tenantId, ACTIVE_STATUS)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "ORG_PARENT_DEPARTMENT_NOT_AVAILABLE", "上级部门不属于当前租户或已停用"));
            assertDepartmentParentNotDescendant(tenantId, departmentId, parentId);
        }

        department.update(normalizeRequired(request.name()), parentId, request.sortOrder() == null ? department.getSortOrder() : request.sortOrder());
        departmentRepository.save(department);
        log.info("部门更新成功 tenantId={} operatorUserId={} departmentId={} requestId={}", tenantId, operatorUserId, departmentId, RequestIds.current());
        return getOverview(tenantId);
    }

    @Transactional
    public TenantOrganizationOverviewResponse updateDepartmentStatus(UUID tenantId, UUID operatorUserId, UUID departmentId, String status) {
        DepartmentEntity department = departmentRepository.findByIdAndTenantId(departmentId, tenantId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORG_DEPARTMENT_NOT_FOUND", "部门不存在"));
        String normalizedStatus = normalizeRequired(status);

        if (ACTIVE_STATUS.equals(normalizedStatus)) {
            department.enable();
            departmentRepository.save(department);
            log.info("部门启用成功 tenantId={} operatorUserId={} departmentId={} requestId={}", tenantId, operatorUserId, departmentId, RequestIds.current());
            return getOverview(tenantId);
        }

        if (!"disabled".equals(normalizedStatus)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_DEPARTMENT_STATUS_INVALID", "部门状态只能为启用或停用");
        }

        long activeMembers = userMembershipRepository.countByTenantIdAndDepartmentIdAndStatus(tenantId, departmentId, ACTIVE_STATUS);
        long activeChildren = departmentRepository.countByTenantIdAndParentIdAndStatus(tenantId, departmentId, ACTIVE_STATUS);
        if (activeMembers > 0 || activeChildren > 0) {
            log.warn(
                "部门停用失败：仍有关联启用资源 tenantId={} operatorUserId={} departmentId={} activeMembers={} activeChildren={} requestId={}",
                tenantId,
                operatorUserId,
                departmentId,
                activeMembers,
                activeChildren,
                RequestIds.current()
            );
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_DEPARTMENT_IN_USE", "部门下仍有启用成员或启用下级部门，请先调整后再停用");
        }
        assertPrincipalHasNoGrantsBeforeDisable(tenantId, "department", departmentId, "部门");
        department.disable();
        departmentRepository.save(department);
        log.info("部门停用成功 tenantId={} operatorUserId={} departmentId={} requestId={}", tenantId, operatorUserId, departmentId, RequestIds.current());
        return getOverview(tenantId);
    }

    @Transactional
    public void deleteDepartment(UUID tenantId, UUID operatorUserId, UUID departmentId) {
        DepartmentEntity department = departmentRepository.findByIdAndTenantId(departmentId, tenantId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORG_DEPARTMENT_NOT_FOUND", "部门不存在"));
        long members = userMembershipRepository.countByTenantIdAndDepartmentId(tenantId, departmentId);
        long children = departmentRepository.countByTenantIdAndParentId(tenantId, departmentId);
        long pageGrants = pageGrantRepository.countByTenantIdAndPrincipalTypeAndPrincipalId(tenantId, "department", departmentId);
        long resourceGrants = resourceGrantRepository.countByTenantIdAndPrincipalTypeAndPrincipalId(tenantId, "department", departmentId);
        if (members > 0 || children > 0 || pageGrants > 0 || resourceGrants > 0) {
            log.warn(
                "部门删除失败：仍有关联数据 tenantId={} operatorUserId={} departmentId={} members={} children={} pageGrants={} resourceGrants={} requestId={}",
                tenantId,
                operatorUserId,
                departmentId,
                members,
                children,
                pageGrants,
                resourceGrants,
                RequestIds.current()
            );
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_DEPARTMENT_DELETE_BLOCKED", "部门仍有关联成员、下级部门或授权记录，不能彻底删除");
        }
        departmentRepository.delete(department);
        log.info("部门已彻底删除 tenantId={} operatorUserId={} departmentId={} requestId={}", tenantId, operatorUserId, departmentId, RequestIds.current());
    }

    @Transactional
    public TenantOrganizationOverviewResponse createTenantRole(UUID tenantId, UUID operatorUserId, CreateTenantRoleRequest request) {
        tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "租户不存在或已停用"));
        String code = generateRoleCode(tenantId, request.name());
        RoleEntity role = RoleEntity.create(tenantId, code, normalizeRequired(request.name()), normalizeOptional(request.description()));
        roleRepository.save(role);
        log.info("租户业务角色创建成功 tenantId={} operatorUserId={} roleId={} code={} requestId={}", tenantId, operatorUserId, role.getId(), code, RequestIds.current());
        return getOverview(tenantId);
    }

    @Transactional
    public TenantOrganizationOverviewResponse updateTenantRole(UUID tenantId, UUID operatorUserId, UUID roleId, UpdateTenantRoleRequest request) {
        RoleEntity role = roleRepository.findByIdAndTenantId(roleId, tenantId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORG_ROLE_NOT_FOUND", "角色不存在"));
        assertTenantAdminRoleManagedBySystem(role);
        String status = normalizeOptional(request.status());
        if (status == null || status.isBlank()) {
            status = role.getStatus();
        }
        if (!ALLOWED_ORG_ROLE_STATUS.contains(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_ROLE_STATUS_INVALID", "角色状态只能是 active 或 disabled");
        }
        if (request.membershipIds() != null) {
            syncTenantRoleMemberships(tenantId, operatorUserId, role, request.membershipIds(), status);
        }
        if ("disabled".equals(status)) {
            long activeMembers = userMembershipRoleRepository.countActiveMembershipsByTenantIdAndRoleId(tenantId, roleId, ACTIVE_STATUS, ACTIVE_STATUS);
            if (activeMembers > 0) {
                log.warn(
                    "租户业务角色更新失败：停用前仍有成员 tenantId={} operatorUserId={} roleId={} activeMembers={} requestId={}",
                    tenantId,
                    operatorUserId,
                    roleId,
                    activeMembers,
                    RequestIds.current()
                );
                throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_ROLE_HAS_MEMBERS", "角色下仍有启用成员，请先调整成员角色");
            }
            assertPrincipalHasNoGrantsBeforeDisable(tenantId, "role", roleId, "角色");
        }
        role.update(normalizeRequired(request.name()), normalizeOptional(request.description()), status);
        roleRepository.save(role);
        log.info("租户业务角色更新成功 tenantId={} operatorUserId={} roleId={} status={} requestId={}", tenantId, operatorUserId, roleId, status, RequestIds.current());
        return getOverview(tenantId);
    }

    @Transactional
    public TenantOrganizationOverviewResponse updateRoleStatus(UUID tenantId, UUID operatorUserId, UUID roleId, String status) {
        RoleEntity role = roleRepository.findByIdAndTenantId(roleId, tenantId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORG_ROLE_NOT_FOUND", "角色不存在"));
        assertTenantAdminRoleManagedBySystem(role);
        String normalizedStatus = normalizeRequired(status);

        if (ACTIVE_STATUS.equals(normalizedStatus)) {
            role.update(role.getName(), role.getDescription(), ACTIVE_STATUS);
            roleRepository.save(role);
            log.info("租户业务角色启用成功 tenantId={} operatorUserId={} roleId={} requestId={}", tenantId, operatorUserId, roleId, RequestIds.current());
            return getOverview(tenantId);
        }

        if (!"disabled".equals(normalizedStatus)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_ROLE_STATUS_INVALID", "角色状态只能是 active 或 disabled");
        }

        long activeMembers = userMembershipRoleRepository.countActiveMembershipsByTenantIdAndRoleId(tenantId, roleId, ACTIVE_STATUS, ACTIVE_STATUS);
        if (activeMembers > 0) {
            log.warn("租户业务角色停用失败：仍有成员 tenantId={} operatorUserId={} roleId={} activeMembers={} requestId={}", tenantId, operatorUserId, roleId, activeMembers, RequestIds.current());
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_ROLE_HAS_MEMBERS", "角色下仍有启用成员，请先调整成员角色");
        }
        assertPrincipalHasNoGrantsBeforeDisable(tenantId, "role", roleId, "角色");
        role.update(role.getName(), role.getDescription(), "disabled");
        roleRepository.save(role);
        log.info("租户业务角色停用成功 tenantId={} operatorUserId={} roleId={} requestId={}", tenantId, operatorUserId, roleId, RequestIds.current());
        return getOverview(tenantId);
    }

    @Transactional
    public void deleteTenantRole(UUID tenantId, UUID operatorUserId, UUID roleId) {
        RoleEntity role = roleRepository.findByIdAndTenantId(roleId, tenantId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORG_ROLE_NOT_FOUND", "角色不存在"));
        assertTenantAdminRoleManagedBySystem(role);
        long activeMembers = userMembershipRoleRepository.countActiveMembershipsByTenantIdAndRoleId(tenantId, roleId, ACTIVE_STATUS, ACTIVE_STATUS);
        if (activeMembers > 0) {
            log.warn("租户业务角色删除失败：仍有启用成员 tenantId={} operatorUserId={} roleId={} activeMembers={} requestId={}", tenantId, operatorUserId, roleId, activeMembers, RequestIds.current());
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_ROLE_HAS_MEMBERS", "角色下仍有启用成员，请先调整成员角色");
        }

        long pageGrantCount = pageGrantRepository.countByTenantIdAndPrincipalTypeAndPrincipalId(tenantId, "role", roleId);
        long resourceGrantCount = resourceGrantRepository.countByTenantIdAndPrincipalTypeAndPrincipalId(tenantId, "role", roleId);
        if (pageGrantCount + resourceGrantCount > 0) {
            log.warn(
                "租户业务角色删除失败：仍被授权卡片引用 tenantId={} operatorUserId={} roleId={} pageGrantCount={} resourceGrantCount={} requestId={}",
                tenantId,
                operatorUserId,
                roleId,
                pageGrantCount,
                resourceGrantCount,
                RequestIds.current()
            );
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_ROLE_HAS_GRANTS", "角色仍被页签或能力分配引用，请先调整分配对象");
        }

        // user_membership_roles 对 roles 使用 RESTRICT；删除角色前先清理历史停用关系，避免外键阻止租户管理员删除空角色。
        userMembershipRoleRepository.deleteByRoleId(roleId);
        roleRepository.delete(role);
        log.info("租户业务角色删除成功 tenantId={} operatorUserId={} roleId={} requestId={}", tenantId, operatorUserId, roleId, RequestIds.current());
    }

    @Transactional
    public TenantOrganizationOverviewResponse updateMembershipRole(
        UUID tenantId,
        UUID operatorUserId,
        UUID membershipId,
        UpdateMembershipRoleRequest request
    ) {
        tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> {
                log.warn("成员角色调整失败：租户不可用 tenantId={} operatorUserId={} requestId={}", tenantId, operatorUserId, RequestIds.current());
                return new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "租户不存在或已停用");
            });

        UserMembershipEntity membership = userMembershipRepository.findByIdAndTenantId(membershipId, tenantId)
            .orElseThrow(() -> {
                log.warn(
                    "成员角色调整失败：成员关系不存在 tenantId={} operatorUserId={} membershipId={} requestId={}",
                    tenantId,
                    operatorUserId,
                    membershipId,
                    RequestIds.current()
                );
                return new ApiException(HttpStatus.NOT_FOUND, "ORG_MEMBERSHIP_NOT_FOUND", "成员关系不存在");
        });

        List<RoleEntity> roles = validateRoles(tenantId, request.roleIds());
        roles = preserveTenantAdminRoleWhenNeeded(tenantId, membership.getId(), roles);
        syncMembershipRoles(tenantId, membership, roles);
        log.info(
            "成员角色调整成功 tenantId={} operatorUserId={} membershipId={} roleIds={} requestId={}",
            tenantId,
            operatorUserId,
            membershipId,
            roles.stream().map(RoleEntity::getId).toList(),
            RequestIds.current()
        );
        return getOverview(tenantId);
    }

    private List<RoleEntity> preserveTenantAdminRoleWhenNeeded(UUID tenantId, UUID membershipId, List<RoleEntity> requestedRoles) {
        Optional<RoleEntity> activeTenantAdminRole = loadActiveRolesForMembership(tenantId, membershipId).stream()
            .filter(role -> "tenant_admin".equals(role.getCode()))
            .findFirst();
        if (activeTenantAdminRole.isEmpty()) {
            requestedRoles.forEach(this::assertNotTenantAdminRole);
            return requestedRoles;
        }

        // 租户管理员身份由系统管理授予和回收；租户管理只允许调整其业务角色，所以这里强制保留 tenant_admin 角色关系。
        LinkedHashMap<UUID, RoleEntity> rolesById = new LinkedHashMap<>();
        requestedRoles.forEach(role -> rolesById.put(role.getId(), role));
        rolesById.put(activeTenantAdminRole.get().getId(), activeTenantAdminRole.get());
        return List.copyOf(rolesById.values());
    }

    @Transactional
    public TenantOrganizationOverviewResponse updateMemberProfile(
        UUID tenantId,
        UUID operatorUserId,
        UUID membershipId,
        UpdateMemberProfileRequest request
    ) {
        tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> {
                log.warn("成员基本信息更新失败：租户不可用 tenantId={} operatorUserId={} membershipId={} requestId={}", tenantId, operatorUserId, membershipId, RequestIds.current());
                return new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "租户不存在或已停用");
            });

        UserMembershipEntity membership = userMembershipRepository.findByIdAndTenantId(membershipId, tenantId)
            .orElseThrow(() -> {
                log.warn("成员基本信息更新失败：成员关系不存在 tenantId={} operatorUserId={} membershipId={} requestId={}", tenantId, operatorUserId, membershipId, RequestIds.current());
                return new ApiException(HttpStatus.NOT_FOUND, "ORG_MEMBERSHIP_NOT_FOUND", "成员关系不存在");
            });

        UserAccount account = userAccountRepository.findById(membership.getUserId())
            .orElseThrow(() -> {
                log.warn("成员基本信息更新失败：账号不存在 tenantId={} operatorUserId={} membershipId={} userId={} requestId={}", tenantId, operatorUserId, membershipId, membership.getUserId(), RequestIds.current());
                return new ApiException(HttpStatus.NOT_FOUND, "ORG_MEMBER_ACCOUNT_NOT_FOUND", "成员账号不存在");
            });

        String username = normalizeRequired(request.username());
        validateUsername(username);
        if (userAccountRepository.existsByUsernameAndIdNot(username, account.getId())) {
            log.warn("成员基本信息更新失败：用户名已存在 tenantId={} operatorUserId={} membershipId={} username={} requestId={}", tenantId, operatorUserId, membershipId, username, RequestIds.current());
            throw new ApiException(HttpStatus.CONFLICT, "ORG_USER_USERNAME_EXISTS", "用户名已存在，请换一个用户名");
        }

        // 租户管理员只能维护人员展示信息和登录名；密码、状态与租户权限分别走独立动作，便于审计和后续安全策略拆分。
        account.updateProfile(username, normalizeRequired(request.displayName()), normalizeOptional(request.email()));
        userAccountRepository.save(account);
        log.info("成员基本信息更新成功 tenantId={} operatorUserId={} membershipId={} userId={} requestId={}", tenantId, operatorUserId, membershipId, account.getId(), RequestIds.current());
        return getOverview(tenantId);
    }

    private void syncTenantRoleMemberships(
        UUID tenantId,
        UUID operatorUserId,
        RoleEntity targetRole,
        List<UUID> membershipIds,
        String targetStatus
    ) {
        List<UserMembershipEntity> tenantMemberships = userMembershipRepository.findByTenantId(tenantId);
        Map<UUID, UserMembershipEntity> membershipsById = tenantMemberships.stream()
            .collect(Collectors.toMap(UserMembershipEntity::getId, Function.identity()));
        Set<UUID> desiredMembershipIds = membershipIds.stream()
            .filter(id -> id != null)
            .collect(Collectors.toSet());

        if ("disabled".equals(targetStatus) && !desiredMembershipIds.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_ROLE_DISABLE_WITH_MEMBERS", "停用角色前必须先移出所有启用成员");
        }

        for (UUID membershipId : desiredMembershipIds) {
            UserMembershipEntity membership = membershipsById.get(membershipId);
            if (membership == null || !ACTIVE_STATUS.equals(membership.getStatus())) {
                log.warn(
                    "角色成员同步失败：成员关系不可用 tenantId={} operatorUserId={} roleId={} membershipId={} requestId={}",
                    tenantId,
                    operatorUserId,
                    targetRole.getId(),
                    membershipId,
                    RequestIds.current()
                );
                throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_ROLE_MEMBER_NOT_AVAILABLE", "所选成员不属于当前租户或已停用");
            }
        }

        Map<UUID, UserMembershipRoleEntity> activeRoleLinksByMembershipId = userMembershipRoleRepository
            .findByMembershipIdInAndStatus(membershipsById.keySet(), ACTIVE_STATUS)
            .stream()
            .filter(link -> targetRole.getId().equals(link.getRoleId()))
            .collect(Collectors.toMap(UserMembershipRoleEntity::getMembershipId, Function.identity()));
        List<UserMembershipEntity> currentActiveMembers = tenantMemberships.stream()
            .filter(membership -> ACTIVE_STATUS.equals(membership.getStatus()))
            .filter(membership -> activeRoleLinksByMembershipId.containsKey(membership.getId()))
            .toList();
        List<UserMembershipEntity> membersToAdd = desiredMembershipIds.stream()
            .map(membershipsById::get)
            .filter(membership -> !activeRoleLinksByMembershipId.containsKey(membership.getId()))
            .toList();
        List<UserMembershipEntity> membersToRemove = currentActiveMembers.stream()
            .filter(membership -> !desiredMembershipIds.contains(membership.getId()))
            .toList();

        // 用户支持多角色。角色维护只增删“该角色关系”，不会把人员从原有角色挪走，避免误伤业务入口和历史授权。
        for (UserMembershipEntity membership : membersToAdd) {
            userMembershipRoleRepository.save(UserMembershipRoleEntity.create(membership.getId(), targetRole.getId()));
            ensureLoginAssignment(membership.getUserId(), tenantId, targetRole, membership.isDefaultMembership());
        }

        for (UserMembershipEntity membership : membersToRemove) {
            UserMembershipRoleEntity link = activeRoleLinksByMembershipId.get(membership.getId());
            link.disable();
            userMembershipRoleRepository.save(link);
            removeLoginAssignmentIfNoOtherActiveRole(membership.getUserId(), tenantId, resolveLoginRole(targetRole));
        }

        log.info(
            "角色成员同步成功 tenantId={} operatorUserId={} roleId={} addCount={} removeCount={} requestId={}",
            tenantId,
            operatorUserId,
            targetRole.getId(),
            membersToAdd.size(),
            membersToRemove.size(),
            RequestIds.current()
        );
    }

    private List<RoleEntity> validateRoles(UUID tenantId, List<UUID> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_MEMBER_ROLE_REQUIRED", "请至少选择一个成员角色");
        }
        List<UUID> normalizedRoleIds = roleIds.stream()
            .filter(id -> id != null)
            .distinct()
            .toList();
        if (normalizedRoleIds.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_MEMBER_ROLE_REQUIRED", "请至少选择一个成员角色");
        }

        Map<UUID, RoleEntity> rolesById = roleRepository.findAllById(normalizedRoleIds).stream()
            .filter(role -> tenantId.equals(role.getTenantId()))
            .filter(role -> ACTIVE_STATUS.equals(role.getStatus()))
            .collect(Collectors.toMap(RoleEntity::getId, Function.identity()));
        List<UUID> missingRoleIds = normalizedRoleIds.stream()
            .filter(roleId -> !rolesById.containsKey(roleId))
            .toList();
        if (!missingRoleIds.isEmpty()) {
            log.warn("成员角色调整失败：角色不可用 tenantId={} roleIds={} requestId={}", tenantId, missingRoleIds, RequestIds.current());
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_ROLE_NOT_AVAILABLE", "所选角色不属于当前租户或已停用");
        }
        return normalizedRoleIds.stream().map(rolesById::get).toList();
    }

    private void assertTenantAdminMembershipManagedBySystem(UUID tenantId, UUID membershipId, String actionLabel) {
        boolean isTenantAdmin = loadActiveRolesForMembership(tenantId, membershipId).stream()
            .anyMatch(role -> "tenant_admin".equals(role.getCode()));
        if (isTenantAdmin) {
            log.warn("{}：租户管理员身份只能由系统管理维护 tenantId={} membershipId={} requestId={}", actionLabel, tenantId, membershipId, RequestIds.current());
            throw new ApiException(HttpStatus.FORBIDDEN, "ORG_TENANT_ADMIN_MANAGED_BY_SYSTEM", "租户管理员身份只能由系统管理维护");
        }
    }

    private void assertTenantAdminRoleManagedBySystem(RoleEntity role) {
        if ("tenant_admin".equals(role.getCode())) {
            log.warn("租户管理员角色维护被拒绝：该角色只能由系统管理维护 tenantId={} roleId={} requestId={}", role.getTenantId(), role.getId(), RequestIds.current());
            throw new ApiException(HttpStatus.FORBIDDEN, "ORG_TENANT_ADMIN_MANAGED_BY_SYSTEM", "租户管理员身份只能由系统管理维护");
        }
    }

    private void assertNotTenantAdminRole(RoleEntity role) {
        if ("tenant_admin".equals(role.getCode())) {
            log.warn("租户成员角色授权被拒绝：租户管理员只能由系统管理授予 tenantId={} roleId={} requestId={}", role.getTenantId(), role.getId(), RequestIds.current());
            throw new ApiException(HttpStatus.FORBIDDEN, "ORG_TENANT_ADMIN_MANAGED_BY_SYSTEM", "租户管理员身份只能由系统管理维护");
        }
    }

    private void syncMembershipRoles(UUID tenantId, UserMembershipEntity membership, List<RoleEntity> desiredRoles) {
        Map<UUID, UserMembershipRoleEntity> activeLinksByRoleId = userMembershipRoleRepository
            .findByMembershipIdAndStatus(membership.getId(), ACTIVE_STATUS)
            .stream()
            .collect(Collectors.toMap(UserMembershipRoleEntity::getRoleId, Function.identity()));
        Set<UUID> desiredRoleIds = desiredRoles.stream().map(RoleEntity::getId).collect(Collectors.toSet());

        for (RoleEntity role : desiredRoles) {
            if (!activeLinksByRoleId.containsKey(role.getId())) {
                userMembershipRoleRepository.save(UserMembershipRoleEntity.create(membership.getId(), role.getId()));
                ensureLoginAssignment(membership.getUserId(), tenantId, role, membership.isDefaultMembership());
            }
        }

        for (UserMembershipRoleEntity link : activeLinksByRoleId.values()) {
            if (!desiredRoleIds.contains(link.getRoleId())) {
                RoleEntity oldRole = roleRepository.findByIdAndTenantIdAndStatus(link.getRoleId(), tenantId, ACTIVE_STATUS).orElse(null);
                link.disable();
                userMembershipRoleRepository.save(link);
                if (oldRole != null) {
                    removeLoginAssignmentIfNoOtherActiveRole(membership.getUserId(), tenantId, resolveLoginRole(oldRole));
                }
            }
        }
    }

    @Transactional
    public TenantOrganizationOverviewResponse updateMembershipDepartment(
        UUID tenantId,
        UUID operatorUserId,
        UUID membershipId,
        UpdateMembershipDepartmentRequest request
    ) {
        tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> {
                log.warn("成员部门调整失败：租户不可用 tenantId={} operatorUserId={} requestId={}", tenantId, operatorUserId, RequestIds.current());
                return new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "租户不存在或已停用");
            });

        UserMembershipEntity membership = userMembershipRepository.findByIdAndTenantId(membershipId, tenantId)
            .orElseThrow(() -> {
                log.warn(
                    "成员部门调整失败：成员关系不存在 tenantId={} operatorUserId={} membershipId={} requestId={}",
                    tenantId,
                    operatorUserId,
                    membershipId,
                    RequestIds.current()
                );
                return new ApiException(HttpStatus.NOT_FOUND, "ORG_MEMBERSHIP_NOT_FOUND", "成员关系不存在");
            });
        UUID departmentId = request.departmentId();
        if (departmentId != null) {
            departmentRepository.findByIdAndTenantIdAndStatus(departmentId, tenantId, ACTIVE_STATUS)
                .orElseThrow(() -> {
                    log.warn(
                        "成员部门调整失败：部门不可用 tenantId={} operatorUserId={} membershipId={} departmentId={} requestId={}",
                        tenantId,
                        operatorUserId,
                        membershipId,
                        departmentId,
                        RequestIds.current()
                    );
                    return new ApiException(HttpStatus.BAD_REQUEST, "ORG_DEPARTMENT_NOT_AVAILABLE", "所选部门不属于当前租户或已停用");
                });
        }

        membership.assignDepartment(departmentId);
        userMembershipRepository.save(membership);
        log.info(
            "成员部门调整成功 tenantId={} operatorUserId={} membershipId={} departmentId={} requestId={}",
            tenantId,
            operatorUserId,
            membershipId,
            departmentId,
            RequestIds.current()
        );
        return getOverview(tenantId);
    }

    @Transactional
    public TenantOrganizationOverviewResponse updateMembershipStatus(
        UUID tenantId,
        UUID operatorUserId,
        UUID membershipId,
        UpdateMembershipStatusRequest request
    ) {
        tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> {
                log.warn("成员状态调整失败：租户不可用 tenantId={} operatorUserId={} requestId={}", tenantId, operatorUserId, RequestIds.current());
                return new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "租户不存在或已停用");
            });

        UserMembershipEntity membership = userMembershipRepository.findByIdAndTenantId(membershipId, tenantId)
            .orElseThrow(() -> {
                log.warn(
                    "成员状态调整失败：成员关系不存在 tenantId={} operatorUserId={} membershipId={} requestId={}",
                    tenantId,
                    operatorUserId,
                    membershipId,
                    RequestIds.current()
                );
                return new ApiException(HttpStatus.NOT_FOUND, "ORG_MEMBERSHIP_NOT_FOUND", "成员关系不存在");
            });
        assertTenantAdminMembershipManagedBySystem(tenantId, membership.getId(), "成员状态调整失败");

        String status = normalizeRequired(request.status());
        if (!ALLOWED_MEMBERSHIP_STATUS.contains(status)) {
            log.warn("成员状态调整失败：状态非法 tenantId={} operatorUserId={} membershipId={} status={} requestId={}", tenantId, operatorUserId, membershipId, status, RequestIds.current());
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_MEMBERSHIP_STATUS_INVALID", "成员状态只能是 active 或 disabled");
        }

        List<RoleEntity> activeRoles = loadActiveRolesForMembership(tenantId, membership.getId());
        if ("disabled".equals(status)) {
            assertPrincipalHasNoGrantsBeforeDisable(tenantId, "user", membership.getUserId(), "成员");
        }
        membership.updateStatus(status);
        userMembershipRepository.save(membership);

        if (ACTIVE_STATUS.equals(status)) {
            for (RoleEntity role : activeRoles) {
                ensureLoginAssignment(membership.getUserId(), tenantId, role, membership.isDefaultMembership());
            }
        } else {
            // 禁用成员关系时同步移除三大入口中的对应租户登录角色，避免前端隐藏入口之外仍可切换进入。
            for (RoleEntity role : activeRoles) {
                removeLoginAssignmentIfNoOtherActiveRole(membership.getUserId(), tenantId, resolveLoginRole(role));
            }
            removeLoginAssignmentIfNoOtherActiveRole(membership.getUserId(), tenantId, "business");
        }

        log.info(
            "成员状态调整成功 tenantId={} operatorUserId={} membershipId={} status={} requestId={}",
            tenantId,
            operatorUserId,
            membershipId,
            status,
            RequestIds.current()
        );
        return getOverview(tenantId);
    }

    @Transactional(readOnly = true)
    public PageResponse<TenantOrgRoleResponse> listTenantOrgRoles(UUID tenantId, int page, int size, String sort) {
        tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> {
                log.warn("租户角色查询失败：租户不可用 tenantId={} requestId={}", tenantId, RequestIds.current());
                return new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "租户不存在或已停用");
            });

        Pageable pageable = PageableFactory.from(PageQuery.of(page, size, sort), TENANT_ORG_ROLE_SORT);

        return PageResponse.from(tenantOrgRoleRepository.findByTenantId(tenantId, pageable).map(this::toTenantOrgRoleResponse));
    }

    @Transactional(readOnly = true)
    public List<TenantResourceOptionResponse> listTenantResourceOptions(UUID tenantId) {
        tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> {
                log.warn("租户资源选项查询失败：租户不可用 tenantId={} requestId={}", tenantId, RequestIds.current());
                return new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "租户不存在或已停用");
            });

        Map<UUID, SystemCapabilityEntity> capabilitiesById = loadEnabledCapabilitiesById(tenantId);
        return capabilitiesById.values().stream()
            .sorted(Comparator.comparing(SystemCapabilityEntity::getCapabilityType).thenComparing(SystemCapabilityEntity::getName))
            .map(capability -> new TenantResourceOptionResponse(
                capability.getCapabilityType(),
                capability.getId().toString(),
                capability.getName(),
                capability.getCode(),
                capability.getVersion(),
                capability.getRiskLevel()
            ))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<PageGrantResponse> listPageGrants(UUID tenantId) {
        tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "租户不存在或已停用"));
        PrincipalNameResolver principalNameResolver = loadPrincipalNameResolver(tenantId);
        return pageGrantRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
            .collect(Collectors.groupingBy(PageGrantEntity::getGrantGroupId, LinkedHashMap::new, Collectors.toList()))
            .values()
            .stream()
            .map(grants -> toPageGrantResponse(grants, principalNameResolver))
            .toList();
    }

    @Transactional
    public PageGrantResponse createPageGrant(UUID tenantId, UUID operatorUserId, CreatePageGrantRequest request) {
        ensureActiveTenant(tenantId);
        UUID grantGroupId = UUID.randomUUID();
        PageGrantResponse response = savePageGrantGroup(tenantId, operatorUserId, grantGroupId, request);
        log.info(
            "租户页签分配卡片创建成功 tenantId={} operatorUserId={} grantGroupId={} principalCount={} pageCount={} requestId={}",
            tenantId,
            operatorUserId,
            grantGroupId,
            response.principals().size(),
            response.pages().size(),
            RequestIds.current()
        );
        return response;
    }

    @Transactional
    public PageGrantResponse updatePageGrant(UUID tenantId, UUID operatorUserId, UUID grantGroupId, CreatePageGrantRequest request) {
        ensureActiveTenant(tenantId);
        List<PageGrantEntity> existingGrants = pageGrantRepository.findByTenantIdAndGrantGroupId(tenantId, grantGroupId);
        if (existingGrants.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ORG_PAGE_GRANT_NOT_FOUND", "页签分配不存在");
        }
        // 分配卡片以主体集合和页签集合为整体编辑，明细行整体重建可以避免保留脏关系。
        pageGrantRepository.deleteAll(existingGrants);
        pageGrantRepository.flush();
        PageGrantResponse response = savePageGrantGroup(tenantId, operatorUserId, grantGroupId, request);
        log.info(
            "租户页签分配卡片更新成功 tenantId={} operatorUserId={} grantGroupId={} principalCount={} pageCount={} requestId={}",
            tenantId,
            operatorUserId,
            grantGroupId,
            response.principals().size(),
            response.pages().size(),
            RequestIds.current()
        );
        return response;
    }

    private PageGrantResponse savePageGrantGroup(UUID tenantId, UUID operatorUserId, UUID grantGroupId, CreatePageGrantRequest request) {
        tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "租户不存在或已停用"));
        String groupName = normalizeGrantGroupName(request.groupName());
        List<NormalizedPrincipal> principals = normalizeGrantPrincipals(tenantId, request.principals(), "ORG_PAGE_GRANT_PRINCIPAL_INVALID");
        List<String> pageKeys = normalizePageGrantKeys(request.pageKeys());
        List<PageGrantEntity> grants = new ArrayList<>();

        for (NormalizedPrincipal principal : principals) {
            for (String pageKey : pageKeys) {
                if (pageGrantRepository.existsByTenantIdAndPrincipalTypeAndPrincipalIdAndPageKey(tenantId, principal.type(), principal.id(), pageKey)) {
                    throw new ApiException(HttpStatus.CONFLICT, "ORG_PAGE_GRANT_EXISTS", "所选对象中已存在相同页签分配");
                }
                grants.add(PageGrantEntity.create(tenantId, grantGroupId, groupName, pageKey, principal.type(), principal.id()));
            }
        }

        pageGrantRepository.saveAll(grants);
        return toPageGrantResponse(grants, loadPrincipalNameResolver(tenantId));
    }

    @Transactional
    public void deletePageGrant(UUID tenantId, UUID operatorUserId, UUID grantGroupId) {
        List<PageGrantEntity> grants = pageGrantRepository.findByTenantIdAndGrantGroupId(tenantId, grantGroupId);
        if (grants.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ORG_PAGE_GRANT_NOT_FOUND", "页签分配不存在");
        }
        pageGrantRepository.deleteAll(grants);
        log.info("租户页签分配卡片删除成功 tenantId={} operatorUserId={} grantGroupId={} detailCount={} requestId={}", tenantId, operatorUserId, grantGroupId, grants.size(), RequestIds.current());
    }

    @Transactional(readOnly = true)
    public List<ResourceGrantResponse> listResourceGrants(UUID tenantId) {
        tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "租户不存在或已停用"));
        Map<UUID, SystemCapabilityEntity> capabilitiesById = loadEnabledCapabilitiesById(tenantId);
        PrincipalNameResolver principalNameResolver = loadPrincipalNameResolver(tenantId);
        return resourceGrantRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
            .collect(Collectors.groupingBy(ResourceGrantEntity::getGrantGroupId, LinkedHashMap::new, Collectors.toList()))
            .values()
            .stream()
            .map(grants -> toResourceGrantResponse(grants, capabilitiesById, principalNameResolver))
            .toList();
    }

    @Transactional
    public ResourceGrantResponse createResourceGrant(UUID tenantId, UUID operatorUserId, CreateResourceGrantRequest request) {
        ensureActiveTenant(tenantId);
        UUID grantGroupId = UUID.randomUUID();
        ResourceGrantResponse response = saveResourceGrantGroup(tenantId, grantGroupId, request);
        log.info(
            "租户能力分配卡片创建成功 tenantId={} operatorUserId={} grantGroupId={} principalCount={} resourceCount={} requestId={}",
            tenantId,
            operatorUserId,
            grantGroupId,
            response.principals().size(),
            response.resources().size(),
            RequestIds.current()
        );
        return response;
    }

    @Transactional
    public ResourceGrantResponse updateResourceGrant(UUID tenantId, UUID operatorUserId, UUID grantGroupId, CreateResourceGrantRequest request) {
        ensureActiveTenant(tenantId);
        List<ResourceGrantEntity> existingGrants = resourceGrantRepository.findByTenantIdAndGrantGroupId(tenantId, grantGroupId);
        if (existingGrants.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ORG_RESOURCE_GRANT_NOT_FOUND", "能力分配不存在");
        }
        // 能力分配编辑同样按卡片整体重建，避免动作和明细残留。当前能力授权统一等价于“可使用”。
        resourceGrantRepository.deleteAll(existingGrants);
        resourceGrantRepository.flush();
        ResourceGrantResponse response = saveResourceGrantGroup(tenantId, grantGroupId, request);
        log.info(
            "租户能力分配卡片更新成功 tenantId={} operatorUserId={} grantGroupId={} principalCount={} resourceCount={} requestId={}",
            tenantId,
            operatorUserId,
            grantGroupId,
            response.principals().size(),
            response.resources().size(),
            RequestIds.current()
        );
        return response;
    }

    private ResourceGrantResponse saveResourceGrantGroup(UUID tenantId, UUID grantGroupId, CreateResourceGrantRequest request) {
        tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "租户不存在或已停用"));
        String groupName = normalizeGrantGroupName(request.groupName());
        List<NormalizedPrincipal> principals = normalizeGrantPrincipals(tenantId, request.principals(), "ORG_RESOURCE_GRANT_PRINCIPAL_INVALID");
        Map<UUID, SystemCapabilityEntity> capabilitiesById = loadEnabledCapabilitiesById(tenantId);
        List<NormalizedResource> resources = normalizeResourceGrantItems(request.resources(), capabilitiesById);
        List<ResourceGrantEntity> grants = new ArrayList<>();

        for (NormalizedPrincipal principal : principals) {
            for (NormalizedResource resource : resources) {
                if (resourceGrantRepository.existsByTenantIdAndPrincipalTypeAndPrincipalIdAndResourceTypeAndResourceId(
                    tenantId,
                    principal.type(),
                    principal.id(),
                    resource.type(),
                    resource.id()
                )) {
                    throw new ApiException(HttpStatus.CONFLICT, "ORG_RESOURCE_GRANT_EXISTS", "所选对象中已存在相同能力分配");
                }
                grants.add(ResourceGrantEntity.create(tenantId, grantGroupId, groupName, resource.type(), resource.id(), principal.type(), principal.id(), new String[] { "use" }));
            }
        }

        resourceGrantRepository.saveAll(grants);
        return toResourceGrantResponse(grants, capabilitiesById, loadPrincipalNameResolver(tenantId));
    }

    @Transactional
    public void deleteResourceGrant(UUID tenantId, UUID operatorUserId, UUID grantGroupId) {
        List<ResourceGrantEntity> grants = resourceGrantRepository.findByTenantIdAndGrantGroupId(tenantId, grantGroupId);
        if (grants.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ORG_RESOURCE_GRANT_NOT_FOUND", "能力分配不存在");
        }
        resourceGrantRepository.deleteAll(grants);
        log.info("租户能力分配卡片删除成功 tenantId={} operatorUserId={} grantGroupId={} detailCount={} requestId={}", tenantId, operatorUserId, grantGroupId, grants.size(), RequestIds.current());
    }

    @Transactional
    public TenantOrgRoleResponse createTenantOrgRole(UUID tenantId, UUID operatorUserId, CreateTenantOrgRoleRequest request) {
        tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> {
                log.warn("租户角色创建失败：租户不可用 tenantId={} operatorUserId={} requestId={}", tenantId, operatorUserId, RequestIds.current());
                return new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "租户不存在或已停用");
            });

        String name = normalizeRequired(request.name());
        if (tenantOrgRoleRepository.existsByTenantIdAndNameIgnoreCase(tenantId, name)) {
            log.warn("租户角色创建失败：名称重复 tenantId={} operatorUserId={} name={} requestId={}", tenantId, operatorUserId, name, RequestIds.current());
            throw new ApiException(HttpStatus.CONFLICT, "ORG_ROLE_NAME_EXISTS", "租户内角色名称已存在");
        }

        String pagePermissions = encodePagePermissions(request.pagePermissions(), tenantId, operatorUserId);
        String resourcePermissions = encodeResourcePermissions(request.resourcePermissions(), tenantId, operatorUserId);
        TenantOrgRoleEntity role = TenantOrgRoleEntity.create(tenantId, name, normalizeOptional(request.description()), pagePermissions, resourcePermissions);
        tenantOrgRoleRepository.save(role);
        log.info(
            "租户角色创建成功 tenantId={} operatorUserId={} orgRoleId={} name={} pagePermissions={} resourcePermissions={} requestId={}",
            tenantId,
            operatorUserId,
            role.getId(),
            name,
            pagePermissions,
            resourcePermissions,
            RequestIds.current()
        );
        return toTenantOrgRoleResponse(role);
    }

    @Transactional
    public TenantOrgRoleResponse updateTenantOrgRole(UUID tenantId, UUID operatorUserId, UUID roleId, UpdateTenantOrgRoleRequest request) {
        tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> {
                log.warn("租户角色更新失败：租户不可用 tenantId={} operatorUserId={} orgRoleId={} requestId={}", tenantId, operatorUserId, roleId, RequestIds.current());
                return new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "租户不存在或已停用");
            });

        TenantOrgRoleEntity role = tenantOrgRoleRepository.findByIdAndTenantId(roleId, tenantId)
            .orElseThrow(() -> {
                log.warn("租户角色更新失败：角色不存在 tenantId={} operatorUserId={} orgRoleId={} requestId={}", tenantId, operatorUserId, roleId, RequestIds.current());
                return new ApiException(HttpStatus.NOT_FOUND, "ORG_ROLE_NOT_FOUND", "租户内角色不存在");
            });

        String status = normalizeRequired(request.status());
        if (!ALLOWED_ORG_ROLE_STATUS.contains(status)) {
            log.warn("租户角色更新失败：状态非法 tenantId={} operatorUserId={} orgRoleId={} status={} requestId={}", tenantId, operatorUserId, roleId, status, RequestIds.current());
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_ROLE_STATUS_INVALID", "角色状态只能是 active 或 disabled");
        }

        String pagePermissions = encodePagePermissions(request.pagePermissions(), tenantId, operatorUserId);
        String resourcePermissions = encodeResourcePermissions(request.resourcePermissions(), tenantId, operatorUserId);
        role.update(normalizeRequired(request.name()), normalizeOptional(request.description()), pagePermissions, resourcePermissions, status);
        tenantOrgRoleRepository.save(role);
        log.info(
            "租户角色更新成功 tenantId={} operatorUserId={} orgRoleId={} status={} pagePermissions={} resourcePermissions={} requestId={}",
            tenantId,
            operatorUserId,
            roleId,
            status,
            pagePermissions,
            resourcePermissions,
            RequestIds.current()
        );
        return toTenantOrgRoleResponse(role);
    }

    @Transactional(readOnly = true)
    public PrincipalGrantUsageResponse getPrincipalGrantUsage(UUID tenantId, String principalType, UUID principalId) {
        ensureActiveTenant(tenantId);
        String normalizedType = normalizeRequired(principalType);
        if (!ALLOWED_PRINCIPAL_TYPES.contains(normalizedType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_PRINCIPAL_TYPE_INVALID", "分配主体类型只能是 role、department 或 user");
        }
        if (principalId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_PRINCIPAL_ID_REQUIRED", "请指定分配主体");
        }
        PrincipalGrantCounts counts = countPrincipalGrants(tenantId, normalizedType, principalId);
        return new PrincipalGrantUsageResponse(
            normalizedType,
            principalId.toString(),
            counts.pageGrantRows(),
            counts.resourceGrantRows()
        );
    }

    @Transactional(readOnly = true)
    public TenantOrganizationOverviewResponse getOverview(UUID tenantId) {
        TenantEntity tenant = tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> {
                log.warn("组织概览查询失败：租户不可用 tenantId={} requestId={}", tenantId, RequestIds.current());
                return new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "租户不存在或已停用");
            });

        // 概览接口是租户管理页的聚合视图，不要求前端理解底层表结构；后续写动作仍按具体资源接口拆分。
        List<UserMembershipEntity> memberships = userMembershipRepository.findByTenantId(tenantId);
        Map<UUID, UserAccount> usersById = userAccountRepository.findAllById(
                memberships.stream().map(UserMembershipEntity::getUserId).collect(Collectors.toSet())
            )
            .stream()
            .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
        Map<UUID, DepartmentEntity> departmentsById = departmentRepository.findByTenantIdOrderBySortOrderAscNameAsc(tenantId)
            .stream()
            .collect(Collectors.toMap(DepartmentEntity::getId, Function.identity()));
        Map<UUID, RoleEntity> rolesById = roleRepository.findByTenantIdOrderByNameAsc(tenantId)
            .stream()
            .collect(Collectors.toMap(RoleEntity::getId, Function.identity()));
        Map<UUID, List<MembershipRoleResponse>> rolesByMembershipId = userMembershipRoleRepository
            .findByMembershipIdInAndStatus(memberships.stream().map(UserMembershipEntity::getId).collect(Collectors.toSet()), ACTIVE_STATUS)
            .stream()
            .collect(Collectors.groupingBy(
                UserMembershipRoleEntity::getMembershipId,
                Collectors.mapping(link -> {
                    RoleEntity role = rolesById.get(link.getRoleId());
                    return new MembershipRoleResponse(
                        link.getRoleId().toString(),
                        role == null ? "" : role.getCode(),
                        role == null ? "已失效角色" : role.getName()
                    );
                }, Collectors.toList())
            ));

        List<MemberResponse> members = usersById.values().stream()
            .sorted(Comparator.comparing(UserAccount::getDisplayName))
            .map(user -> new MemberResponse(
                user.getId().toString(),
                user.getUsername(),
                user.getDisplayName(),
                user.getEmail() == null ? "" : user.getEmail(),
                user.getStatus(),
                user.getLastLoginAt() == null ? "" : user.getLastLoginAt().toString()
            ))
            .toList();

        List<DepartmentResponse> departments = departmentsById.values().stream()
            .sorted(Comparator.comparing(DepartmentEntity::getSortOrder).thenComparing(DepartmentEntity::getName))
            .map(department -> new DepartmentResponse(
                department.getId().toString(),
                department.getParentId() == null ? null : department.getParentId().toString(),
                department.getName(),
                department.getCode() == null ? "" : department.getCode(),
                department.getSortOrder(),
                department.getStatus()
            ))
            .toList();

        List<RoleResponse> roles = rolesById.values().stream()
            .sorted(Comparator.comparing(RoleEntity::getName))
            .map(role -> new RoleResponse(
                role.getId().toString(),
                role.getCode(),
                role.getName(),
                role.getStatus(),
                role.getDescription() == null ? "" : role.getDescription()
            ))
            .toList();

        List<MembershipResponse> membershipResponses = memberships.stream()
            .sorted(Comparator.comparing(UserMembershipEntity::isDefaultMembership).reversed())
            .map(membership -> {
                UserAccount user = usersById.get(membership.getUserId());
                DepartmentEntity department = membership.getDepartmentId() == null ? null : departmentsById.get(membership.getDepartmentId());
                List<MembershipRoleResponse> membershipRoles = rolesByMembershipId.getOrDefault(membership.getId(), List.of());

                return new MembershipResponse(
                    membership.getId().toString(),
                    membership.getUserId().toString(),
                    user == null ? "" : user.getDisplayName(),
                    membership.getDepartmentId() == null ? null : membership.getDepartmentId().toString(),
                    department == null ? "" : department.getName(),
                    membershipRoles,
                    membership.isDefaultMembership(),
                    membershipRoles.stream().anyMatch(role -> "tenant_admin".equals(role.code())),
                    membership.getStatus()
                );
            })
            .toList();

        return new TenantOrganizationOverviewResponse(
            tenant.getId().toString(),
            tenant.getName(),
            tenant.getCode(),
            members,
            departments,
            roles,
            membershipResponses
        );
    }

    private static String normalizeRequired(String value) {
        return value == null ? "" : value.trim();
    }

    private static void validateUsername(String username) {
        if (!UsernameValidator.isValid(username)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_USER_USERNAME_INVALID", UsernameValidator.RULE_MESSAGE);
        }
    }

    private static String normalizeOptional(String value) {
        return value == null ? "" : value.trim();
    }

    private TenantOrgRoleResponse toTenantOrgRoleResponse(TenantOrgRoleEntity role) {
        return new TenantOrgRoleResponse(
            role.getId().toString(),
            role.getName(),
            role.getDescription() == null ? "" : role.getDescription(),
            decodePagePermissions(role.getPagePermissions()),
            decodeResourcePermissions(role.getTenantId(), role.getResourcePermissions()),
            role.isSystemRole(),
            role.getStatus(),
            role.getCreatedAt() == null ? "" : role.getCreatedAt().toString(),
            role.getUpdatedAt() == null ? "" : role.getUpdatedAt().toString()
        );
    }

    private List<String> decodePagePermissions(String pagePermissions) {
        if (pagePermissions == null || pagePermissions.isBlank()) {
            return List.of();
        }

        try {
            return objectMapper.readValue(pagePermissions, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException exception) {
            log.warn("租户角色页签权限解析失败 pagePermissions={} requestId={}", pagePermissions, RequestIds.current());
            return List.of();
        }
    }

    private PageGrantResponse toPageGrantResponse(List<PageGrantEntity> grants, PrincipalNameResolver principalNameResolver) {
        if (grants.isEmpty()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ORG_PAGE_GRANT_GROUP_EMPTY", "页签分配数据异常");
        }
        PageGrantEntity firstGrant = grants.get(0);
        List<GrantPrincipalResponse> principals = grants.stream()
            .collect(Collectors.toMap(
                grant -> grant.getPrincipalType() + ":" + grant.getPrincipalId(),
                grant -> new GrantPrincipalResponse(
                    grant.getPrincipalType(),
                    grant.getPrincipalId().toString(),
                    principalNameResolver.resolve(grant.getPrincipalType(), grant.getPrincipalId())
                ),
                (left, right) -> left,
                LinkedHashMap::new
            ))
            .values()
            .stream()
            .toList();
        List<PageGrantItemResponse> pages = grants.stream()
            .collect(Collectors.toMap(
                PageGrantEntity::getPageKey,
                grant -> new PageGrantItemResponse(grant.getPageKey(), formatPagePermissionName(grant.getPageKey())),
                (left, right) -> left,
                LinkedHashMap::new
            ))
            .values()
            .stream()
            .toList();
        return new PageGrantResponse(
            firstGrant.getGrantGroupId().toString(),
            firstGrant.getGrantGroupName(),
            principals,
            pages,
            firstGrant.getCreatedAt() == null ? "" : firstGrant.getCreatedAt().toString()
        );
    }

    private ResourceGrantResponse toResourceGrantResponse(
        List<ResourceGrantEntity> grants,
        Map<UUID, SystemCapabilityEntity> capabilitiesById,
        PrincipalNameResolver principalNameResolver
    ) {
        if (grants.isEmpty()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ORG_RESOURCE_GRANT_GROUP_EMPTY", "能力分配数据异常");
        }
        ResourceGrantEntity firstGrant = grants.get(0);
        List<GrantPrincipalResponse> principals = grants.stream()
            .collect(Collectors.toMap(
                grant -> grant.getPrincipalType() + ":" + grant.getPrincipalId(),
                grant -> new GrantPrincipalResponse(
                    grant.getPrincipalType(),
                    grant.getPrincipalId().toString(),
                    principalNameResolver.resolve(grant.getPrincipalType(), grant.getPrincipalId())
                ),
                (left, right) -> left,
                LinkedHashMap::new
            ))
            .values()
            .stream()
            .toList();
        List<ResourceGrantItemResponse> resources = grants.stream()
            .collect(Collectors.toMap(
                grant -> grant.getResourceType() + ":" + grant.getResourceId(),
                grant -> {
                    SystemCapabilityEntity capability = capabilitiesById.get(grant.getResourceId());
                    return new ResourceGrantItemResponse(
                        grant.getResourceType(),
                        grant.getResourceId().toString(),
                        capability == null ? "已失效资源" : capability.getName(),
                        capability == null ? "" : capability.getCode()
                    );
                },
                (left, right) -> left,
                LinkedHashMap::new
            ))
            .values()
            .stream()
            .toList();
        return new ResourceGrantResponse(
            firstGrant.getGrantGroupId().toString(),
            firstGrant.getGrantGroupName(),
            principals,
            resources,
            firstGrant.getCreatedAt() == null ? "" : firstGrant.getCreatedAt().toString()
        );
    }

    private static String formatPagePermissionName(String pageKey) {
        return switch (pageKey) {
            case "workbench" -> "业务工作台";
            case "designer" -> "流程设计";
            case "assets" -> "能力资产";
            default -> pageKey;
        };
    }

    private String encodePagePermissions(List<String> permissions, UUID tenantId, UUID operatorUserId) {
        List<String> normalized = permissions == null
            ? List.of()
            : permissions.stream()
                .map(TenantOrganizationService::normalizeOptional)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();

        List<String> invalid = normalized.stream()
            .filter(value -> !ALLOWED_PAGE_PERMISSIONS.contains(value))
            .toList();

        if (!invalid.isEmpty()) {
            log.warn(
                "租户角色页签权限非法 tenantId={} operatorUserId={} invalidPermissions={} requestId={}",
                tenantId,
                operatorUserId,
                invalid,
                RequestIds.current()
            );
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_ROLE_PAGE_PERMISSION_INVALID", "包含不支持的页面权限");
        }

        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException exception) {
            log.error("租户角色页签权限序列化失败 tenantId={} operatorUserId={} requestId={}", tenantId, operatorUserId, RequestIds.current(), exception);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SYSTEM_JSON_SERIALIZE_FAILED", "系统暂时无法保存页面权限");
        }
    }

    private List<TenantResourcePermissionResponse> decodeResourcePermissions(UUID tenantId, String resourcePermissions) {
        if (resourcePermissions == null || resourcePermissions.isBlank()) {
            return List.of();
        }

        try {
            List<TenantResourcePermissionPayload> payloads = objectMapper.readValue(resourcePermissions, new TypeReference<List<TenantResourcePermissionPayload>>() {});
            Map<UUID, SystemCapabilityEntity> capabilitiesById = loadEnabledCapabilitiesById(tenantId);
            return payloads.stream()
                .map(payload -> {
                    UUID resourceId = parseResourceId(payload.resourceId());
                    SystemCapabilityEntity capability = resourceId == null ? null : capabilitiesById.get(resourceId);
                    return new TenantResourcePermissionResponse(
                        normalizeOptional(payload.resourceType()),
                        normalizeOptional(payload.resourceId()),
                        capability == null ? "已失效资源" : capability.getName(),
                        capability == null ? "" : capability.getCode(),
                        payload.actions() == null ? List.of() : payload.actions()
                    );
                })
                .toList();
        } catch (JsonProcessingException exception) {
            log.warn("租户角色资源权限解析失败 resourcePermissions={} requestId={}", resourcePermissions, RequestIds.current());
            return List.of();
        }
    }

    private String encodeResourcePermissions(List<TenantResourcePermissionRequest> permissions, UUID tenantId, UUID operatorUserId) {
        if (permissions == null || permissions.isEmpty()) {
            return "[]";
        }

        Map<UUID, SystemCapabilityEntity> enabledCapabilities = loadEnabledCapabilitiesById(tenantId);
        Map<String, TenantResourcePermissionPayload> normalizedByKey = new LinkedHashMap<>();

        for (TenantResourcePermissionRequest permission : permissions) {
            String resourceType = normalizeOptional(permission.resourceType());
            String resourceIdText = normalizeOptional(permission.resourceId());

            if (!ALLOWED_RESOURCE_TYPES.contains(resourceType)) {
                log.warn(
                    "租户资源授权失败：资源类型非法 tenantId={} operatorUserId={} resourceType={} requestId={}",
                    tenantId,
                    operatorUserId,
                    resourceType,
                    RequestIds.current()
                );
                throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_ROLE_RESOURCE_TYPE_INVALID", "包含不支持的资源类型");
            }

            UUID resourceId = parseResourceId(resourceIdText);
            if (resourceId == null) {
                log.warn(
                    "租户资源授权失败：资源 ID 非法 tenantId={} operatorUserId={} resourceType={} resourceId={} requestId={}",
                    tenantId,
                    operatorUserId,
                    resourceType,
                    resourceIdText,
                    RequestIds.current()
                );
                throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_ROLE_RESOURCE_ID_INVALID", "资源 ID 格式不正确");
            }

            SystemCapabilityEntity capability = enabledCapabilities.get(resourceId);
            if (capability == null || !resourceType.equals(capability.getCapabilityType())) {
                log.warn(
                    "租户资源授权失败：资源未启用或类型不匹配 tenantId={} operatorUserId={} resourceType={} resourceId={} requestId={}",
                    tenantId,
                    operatorUserId,
                    resourceType,
                    resourceId,
                    RequestIds.current()
                );
                throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_ROLE_RESOURCE_NOT_AVAILABLE", "只能授权系统管理已启用给当前租户的能力资源");
            }

            List<String> actions = normalizeResourceActions(permission.actions());
            normalizedByKey.put(
                resourceType + ":" + resourceId,
                new TenantResourcePermissionPayload(resourceType, resourceId.toString(), actions)
            );
        }

        try {
            return objectMapper.writeValueAsString(new ArrayList<>(normalizedByKey.values()));
        } catch (JsonProcessingException exception) {
            log.error("租户资源权限序列化失败 tenantId={} operatorUserId={} requestId={}", tenantId, operatorUserId, RequestIds.current(), exception);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SYSTEM_JSON_SERIALIZE_FAILED", "系统暂时无法保存资源权限");
        }
    }

    private List<String> normalizeResourceActions(List<String> actions) {
        List<String> normalized = actions == null || actions.isEmpty()
            ? List.of("use")
            : actions.stream()
                .map(TenantOrganizationService::normalizeOptional)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();

        List<String> invalid = normalized.stream()
            .filter(value -> !ALLOWED_RESOURCE_ACTIONS.contains(value))
            .toList();

        if (!invalid.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_ROLE_RESOURCE_ACTION_INVALID", "包含不支持的资源动作");
        }

        return normalized.isEmpty() ? List.of("use") : normalized;
    }

    private void ensureActiveTenant(UUID tenantId) {
        tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "租户不存在或已停用"));
    }

    private String normalizeGrantGroupName(String groupName) {
        String normalized = normalizeRequired(groupName);
        if (normalized.length() > 120) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_GRANT_GROUP_NAME_TOO_LONG", "分配名称不能超过 120 个字符");
        }
        return normalized;
    }

    private List<NormalizedPrincipal> normalizeGrantPrincipals(UUID tenantId, List<GrantPrincipalRequest> principals, String invalidCode) {
        if (principals == null || principals.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, invalidCode, "请选择分配对象");
        }

        Map<String, NormalizedPrincipal> normalized = new LinkedHashMap<>();
        for (GrantPrincipalRequest principal : principals) {
            String principalType = normalizeRequired(principal.principalType());
            UUID principalId = principal.principalId();
            if (!ALLOWED_PRINCIPAL_TYPES.contains(principalType) || principalId == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, invalidCode, "分配对象只能是角色、部门或用户");
            }
            validatePrincipal(tenantId, principalType, principalId);
            normalized.putIfAbsent(principalType + ":" + principalId, new NormalizedPrincipal(principalType, principalId));
        }
        return List.copyOf(normalized.values());
    }

    private List<String> normalizePageGrantKeys(List<String> pageKeys) {
        if (pageKeys == null || pageKeys.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_PAGE_GRANT_PAGE_INVALID", "请选择页签");
        }

        List<String> normalized = pageKeys.stream()
            .map(TenantOrganizationService::normalizeOptional)
            .filter(value -> !value.isBlank())
            .distinct()
            .toList();
        if (normalized.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_PAGE_GRANT_PAGE_INVALID", "请选择页签");
        }
        List<String> invalid = normalized.stream()
            .filter(value -> !ALLOWED_PAGE_PERMISSIONS.contains(value))
            .toList();
        if (!invalid.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_PAGE_GRANT_PAGE_INVALID", "包含不支持的页签");
        }
        return normalized;
    }

    private List<NormalizedResource> normalizeResourceGrantItems(List<ResourceGrantItemRequest> resources, Map<UUID, SystemCapabilityEntity> capabilitiesById) {
        if (resources == null || resources.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_RESOURCE_GRANT_RESOURCE_NOT_AVAILABLE", "请选择能力资源");
        }

        Map<String, NormalizedResource> normalized = new LinkedHashMap<>();
        for (ResourceGrantItemRequest resource : resources) {
            String resourceType = normalizeRequired(resource.resourceType());
            UUID resourceId = resource.resourceId();
            if (!ALLOWED_RESOURCE_TYPES.contains(resourceType) || resourceId == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_RESOURCE_GRANT_RESOURCE_TYPE_INVALID", "包含不支持的资源类型");
            }
            SystemCapabilityEntity capability = capabilitiesById.get(resourceId);
            if (capability == null || !resourceType.equals(capability.getCapabilityType())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_RESOURCE_GRANT_RESOURCE_NOT_AVAILABLE", "只能分配系统管理员已启用给当前租户的能力资源");
            }
            normalized.putIfAbsent(resourceType + ":" + resourceId, new NormalizedResource(resourceType, resourceId));
        }
        return List.copyOf(normalized.values());
    }

    private Map<UUID, SystemCapabilityEntity> loadEnabledCapabilitiesById(UUID tenantId) {
        List<TenantCapabilityGrantEntity> grants = tenantCapabilityGrantRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)
            .stream()
            .filter(grant -> "enabled".equals(grant.getStatus()))
            .toList();

        if (grants.isEmpty()) {
            return Map.of();
        }

        Set<UUID> capabilityIds = grants.stream()
            .map(TenantCapabilityGrantEntity::getCapabilityId)
            .collect(Collectors.toSet());

        return systemCapabilityRepository.findAllById(capabilityIds)
            .stream()
            .filter(capability -> ACTIVE_STATUS.equals(capability.getStatus()))
            .filter(capability -> ALLOWED_RESOURCE_TYPES.contains(capability.getCapabilityType()))
            .collect(Collectors.toMap(SystemCapabilityEntity::getId, Function.identity()));
    }

    private void validatePrincipal(UUID tenantId, String principalType, UUID principalId) {
        if ("role".equals(principalType)) {
            roleRepository.findByIdAndTenantIdAndStatus(principalId, tenantId, ACTIVE_STATUS)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "ORG_RESOURCE_GRANT_PRINCIPAL_NOT_AVAILABLE", "所选角色不属于当前租户或已停用"));
            return;
        }
        if ("department".equals(principalType)) {
            departmentRepository.findByIdAndTenantIdAndStatus(principalId, tenantId, ACTIVE_STATUS)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "ORG_RESOURCE_GRANT_PRINCIPAL_NOT_AVAILABLE", "所选部门不属于当前租户或已停用"));
            return;
        }
        if ("user".equals(principalType)) {
            boolean activeMember = userMembershipRepository.findByUserIdAndTenantIdAndStatus(principalId, tenantId, ACTIVE_STATUS).size() > 0;
            if (!activeMember) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_RESOURCE_GRANT_PRINCIPAL_NOT_AVAILABLE", "所选用户不是当前租户启用成员");
            }
        }
    }

    private PrincipalNameResolver loadPrincipalNameResolver(UUID tenantId) {
        Map<UUID, RoleEntity> roles = roleRepository.findByTenantIdAndStatusOrderByNameAsc(tenantId, ACTIVE_STATUS)
            .stream()
            .collect(Collectors.toMap(RoleEntity::getId, Function.identity()));
        Map<UUID, DepartmentEntity> departments = departmentRepository.findByTenantIdAndStatusOrderBySortOrderAscNameAsc(tenantId, ACTIVE_STATUS)
            .stream()
            .collect(Collectors.toMap(DepartmentEntity::getId, Function.identity()));
        List<UserMembershipEntity> memberships = userMembershipRepository.findByTenantIdAndStatus(tenantId, ACTIVE_STATUS);
        Map<UUID, UserAccount> users = userAccountRepository.findAllById(memberships.stream().map(UserMembershipEntity::getUserId).collect(Collectors.toSet()))
            .stream()
            .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
        return (principalType, principalId) -> {
            if ("role".equals(principalType)) {
                RoleEntity role = roles.get(principalId);
                return role == null ? "已失效角色" : role.getName();
            }
            if ("department".equals(principalType)) {
                DepartmentEntity department = departments.get(principalId);
                return department == null ? "已失效部门" : department.getName();
            }
            UserAccount user = users.get(principalId);
            return user == null ? "已失效用户" : user.getDisplayName();
        };
    }

    private String generateDepartmentCode(UUID tenantId, String name) {
        String base = "dept_" + Integer.toHexString(Math.abs(normalizeRequired(name).hashCode()));
        String code = base;
        int suffix = 1;
        while (departmentRepository.countByTenantIdAndCode(tenantId, code) > 0) {
            code = base + "_" + suffix++;
        }
        return code;
    }

    private String generateRoleCode(UUID tenantId, String name) {
        String base = "role_" + Integer.toHexString(Math.abs(normalizeRequired(name).hashCode()));
        String code = base;
        int suffix = 1;
        while (roleRepository.existsByTenantIdAndCode(tenantId, code)) {
            code = base + "_" + suffix++;
        }
        return code;
    }

    private static UUID parseResourceId(String resourceId) {
        try {
            return resourceId == null || resourceId.isBlank() ? null : UUID.fromString(resourceId);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void assertPrincipalHasNoGrantsBeforeDisable(UUID tenantId, String principalType, UUID principalId, String subjectLabel) {
        PrincipalGrantCounts counts = countPrincipalGrants(tenantId, principalType, principalId);
        if (counts.pageGrantRows() == 0 && counts.resourceGrantRows() == 0) {
            return;
        }
        log.warn(
            "{}停用失败：仍被资源分配引用 tenantId={} principalType={} principalId={} pageGrantRows={} resourceGrantRows={} requestId={}",
            subjectLabel,
            tenantId,
            principalType,
            principalId,
            counts.pageGrantRows(),
            counts.resourceGrantRows(),
            RequestIds.current()
        );
        throw buildPrincipalHasGrantsException(counts);
    }

    private ApiException buildPrincipalHasGrantsException(PrincipalGrantCounts counts) {
        if (counts.pageGrantRows() > 0 && counts.resourceGrantRows() == 0) {
            return new ApiException(
                HttpStatus.BAD_REQUEST,
                "ORG_PRINCIPAL_HAS_PAGE_GRANTS",
                "仍被 " + counts.pageGrantRows() + " 条页签分配引用，请先在资源范围中调整分配对象"
            );
        }
        if (counts.resourceGrantRows() > 0 && counts.pageGrantRows() == 0) {
            return new ApiException(
                HttpStatus.BAD_REQUEST,
                "ORG_PRINCIPAL_HAS_RESOURCE_GRANTS",
                "仍被 " + counts.resourceGrantRows() + " 条能力分配引用，请先在资源范围中调整分配对象"
            );
        }
        return new ApiException(
            HttpStatus.BAD_REQUEST,
            "ORG_PRINCIPAL_HAS_GRANTS",
            "仍被 " + counts.pageGrantRows() + " 条页签分配和 " + counts.resourceGrantRows() + " 条能力分配引用，请先在资源范围中调整分配对象"
        );
    }

    private PrincipalGrantCounts countPrincipalGrants(UUID tenantId, String principalType, UUID principalId) {
        return new PrincipalGrantCounts(
            pageGrantRepository.countByTenantIdAndPrincipalTypeAndPrincipalId(tenantId, principalType, principalId),
            resourceGrantRepository.countByTenantIdAndPrincipalTypeAndPrincipalId(tenantId, principalType, principalId)
        );
    }

    private void assertDepartmentParentNotDescendant(UUID tenantId, UUID departmentId, UUID parentId) {
        UUID cursor = parentId;
        while (cursor != null) {
            if (cursor.equals(departmentId)) {
                log.warn(
                    "部门上级调整失败：形成组织环 tenantId={} departmentId={} parentId={} requestId={}",
                    tenantId,
                    departmentId,
                    parentId,
                    RequestIds.current()
                );
                throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_DEPARTMENT_CYCLE", "上级部门不能选择当前部门或其下级部门");
            }
            cursor = departmentRepository.findByIdAndTenantId(cursor, tenantId)
                .map(DepartmentEntity::getParentId)
                .orElse(null);
        }
    }

    private void ensureLoginAssignment(UUID userId, UUID tenantId, RoleEntity role, boolean defaultAssignment) {
        String systemRole = resolveLoginRole(role);
        String label = role.getName() == null || role.getName().isBlank() ? systemRole : role.getName();

        // user_memberships 负责租户内组织关系，user_role_assignments 负责三大登录入口；两边必须同步，避免成员可见但无法登录。
        userRoleAssignmentRepository.findByUserIdAndRoleAndTenantId(userId, systemRole, tenantId)
            .orElseGet(() -> userRoleAssignmentRepository.save(
                UserRoleAssignmentEntity.create(userId, systemRole, tenantId, label, defaultAssignment)
            ));
    }

    private List<RoleEntity> loadActiveRolesForMembership(UUID tenantId, UUID membershipId) {
        List<UserMembershipRoleEntity> links = userMembershipRoleRepository.findByMembershipIdAndStatus(membershipId, ACTIVE_STATUS);
        if (links.isEmpty()) {
            return List.of();
        }
        Map<UUID, RoleEntity> rolesById = roleRepository.findAllById(links.stream().map(UserMembershipRoleEntity::getRoleId).collect(Collectors.toSet()))
            .stream()
            .filter(role -> tenantId.equals(role.getTenantId()))
            .filter(role -> ACTIVE_STATUS.equals(role.getStatus()))
            .collect(Collectors.toMap(RoleEntity::getId, Function.identity()));
        return links.stream()
            .map(link -> rolesById.get(link.getRoleId()))
            .filter(role -> role != null)
            .toList();
    }

    private void removeLoginAssignmentIfNoOtherActiveRole(UUID userId, UUID tenantId, String loginRole) {
        boolean hasOtherActiveRole = userMembershipRepository.findByUserIdAndTenantIdAndStatus(userId, tenantId, ACTIVE_STATUS)
            .stream()
            .flatMap(membership -> loadActiveRolesForMembership(tenantId, membership.getId()).stream())
            .anyMatch(role -> loginRole.equals(resolveLoginRole(role)));

        if (!hasOtherActiveRole) {
            userRoleAssignmentRepository.deleteByUserIdAndRoleAndTenantId(userId, loginRole, tenantId);
        }
    }

    private static String resolveLoginRole(RoleEntity role) {
        return "tenant_admin".equals(role.getCode()) ? "tenant_admin" : "business";
    }

    private record TenantResourcePermissionPayload(String resourceType, String resourceId, List<String> actions) {
    }

    private record PrincipalGrantCounts(long pageGrantRows, long resourceGrantRows) {
    }

    private record NormalizedPrincipal(String type, UUID id) {
    }

    private record NormalizedResource(String type, UUID id) {
    }

    private interface PrincipalNameResolver {
        String resolve(String principalType, UUID principalId);
    }
}
