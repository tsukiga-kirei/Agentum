package com.agentum.organization.application;

import com.agentum.auth.domain.UserAccount;
import com.agentum.auth.domain.UserRoleAssignmentEntity;
import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.auth.infrastructure.UserRoleAssignmentRepository;
import com.agentum.organization.domain.DepartmentEntity;
import com.agentum.organization.domain.TenantOrgRoleEntity;
import com.agentum.organization.domain.UserMembershipEntity;
import com.agentum.organization.infrastructure.DepartmentRepository;
import com.agentum.organization.infrastructure.TenantOrgRoleRepository;
import com.agentum.organization.infrastructure.UserMembershipRepository;
import com.agentum.organization.interfaces.CreateMemberRequest;
import com.agentum.organization.interfaces.CreateDepartmentRequest;
import com.agentum.organization.interfaces.CreatePageGrantRequest;
import com.agentum.organization.interfaces.CreateResourceGrantRequest;
import com.agentum.organization.interfaces.CreateTenantOrgRoleRequest;
import com.agentum.organization.interfaces.CreateTenantRoleRequest;
import com.agentum.organization.interfaces.DepartmentResponse;
import com.agentum.organization.interfaces.MemberResponse;
import com.agentum.organization.interfaces.MembershipResponse;
import com.agentum.organization.interfaces.PageGrantResponse;
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
import com.agentum.shared.pagination.PageResponse;
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
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
            departmentId,
            roleId,
            normalizeSpaceCode(request.spaceCode())
        );
        userMembershipRepository.save(membership);
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
        }

        department.update(normalizeRequired(request.name()), parentId, request.sortOrder() == null ? department.getSortOrder() : request.sortOrder());
        departmentRepository.save(department);
        log.info("部门更新成功 tenantId={} operatorUserId={} departmentId={} requestId={}", tenantId, operatorUserId, departmentId, RequestIds.current());
        return getOverview(tenantId);
    }

    @Transactional
    public TenantOrganizationOverviewResponse disableDepartment(UUID tenantId, UUID operatorUserId, UUID departmentId) {
        DepartmentEntity department = departmentRepository.findByIdAndTenantId(departmentId, tenantId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORG_DEPARTMENT_NOT_FOUND", "部门不存在"));
        long activeMembers = userMembershipRepository.countByTenantIdAndDepartmentIdAndStatus(tenantId, departmentId, ACTIVE_STATUS);
        if (activeMembers > 0) {
            log.warn("部门停用失败：仍有成员 tenantId={} operatorUserId={} departmentId={} activeMembers={} requestId={}", tenantId, operatorUserId, departmentId, activeMembers, RequestIds.current());
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_DEPARTMENT_HAS_MEMBERS", "部门下仍有启用成员，请先调整成员部门");
        }
        department.disable();
        departmentRepository.save(department);
        log.info("部门停用成功 tenantId={} operatorUserId={} departmentId={} requestId={}", tenantId, operatorUserId, departmentId, RequestIds.current());
        return getOverview(tenantId);
    }

    @Transactional
    public TenantOrganizationOverviewResponse createTenantRole(UUID tenantId, UUID operatorUserId, CreateTenantRoleRequest request) {
        tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "租户不存在或已停用"));
        String code = generateRoleCode(tenantId, request.name());
        RoleEntity role = RoleEntity.create(tenantId, code, normalizeRequired(request.name()), "business", normalizeOptional(request.description()));
        roleRepository.save(role);
        log.info("租户业务角色创建成功 tenantId={} operatorUserId={} roleId={} code={} requestId={}", tenantId, operatorUserId, role.getId(), code, RequestIds.current());
        return getOverview(tenantId);
    }

    @Transactional
    public TenantOrganizationOverviewResponse updateTenantRole(UUID tenantId, UUID operatorUserId, UUID roleId, UpdateTenantRoleRequest request) {
        RoleEntity role = roleRepository.findByIdAndTenantId(roleId, tenantId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORG_ROLE_NOT_FOUND", "角色不存在"));
        String status = normalizeOptional(request.status());
        if (status == null || status.isBlank()) {
            status = ACTIVE_STATUS;
        }
        if (!ALLOWED_ORG_ROLE_STATUS.contains(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_ROLE_STATUS_INVALID", "角色状态只能是 active 或 disabled");
        }
        role.update(normalizeRequired(request.name()), normalizeOptional(request.description()), status);
        roleRepository.save(role);
        log.info("租户业务角色更新成功 tenantId={} operatorUserId={} roleId={} status={} requestId={}", tenantId, operatorUserId, roleId, status, RequestIds.current());
        return getOverview(tenantId);
    }

    @Transactional
    public TenantOrganizationOverviewResponse disableTenantRole(UUID tenantId, UUID operatorUserId, UUID roleId) {
        RoleEntity role = roleRepository.findByIdAndTenantId(roleId, tenantId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORG_ROLE_NOT_FOUND", "角色不存在"));
        long activeMembers = userMembershipRepository.countByTenantIdAndRoleIdAndStatus(tenantId, roleId, ACTIVE_STATUS);
        if (activeMembers > 0) {
            log.warn("租户业务角色停用失败：仍有成员 tenantId={} operatorUserId={} roleId={} activeMembers={} requestId={}", tenantId, operatorUserId, roleId, activeMembers, RequestIds.current());
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_ROLE_HAS_MEMBERS", "角色下仍有启用成员，请先调整成员角色");
        }
        role.update(role.getName(), role.getDescription(), "disabled");
        roleRepository.save(role);
        log.info("租户业务角色停用成功 tenantId={} operatorUserId={} roleId={} requestId={}", tenantId, operatorUserId, roleId, RequestIds.current());
        return getOverview(tenantId);
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

        UUID roleId = request.roleId();
        RoleEntity oldRole = roleRepository.findByIdAndTenantIdAndStatus(membership.getRoleId(), tenantId, ACTIVE_STATUS).orElse(null);
        RoleEntity role = roleRepository.findByIdAndTenantIdAndStatus(roleId, tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> {
                log.warn(
                    "成员角色调整失败：角色不可用 tenantId={} operatorUserId={} membershipId={} roleId={} requestId={}",
                    tenantId,
                    operatorUserId,
                    membershipId,
                    roleId,
                    RequestIds.current()
                );
                return new ApiException(HttpStatus.BAD_REQUEST, "ORG_ROLE_NOT_AVAILABLE", "所选角色不属于当前租户或已停用");
            });

        removeChangedLoginAssignment(membership.getUserId(), tenantId, oldRole, role);
        membership.assignRole(roleId);
        userMembershipRepository.save(membership);
        ensureLoginAssignment(membership.getUserId(), tenantId, role, membership.isDefaultMembership());
        log.info(
            "成员角色调整成功 tenantId={} operatorUserId={} membershipId={} roleId={} requestId={}",
            tenantId,
            operatorUserId,
            membershipId,
            roleId,
            RequestIds.current()
        );
        return getOverview(tenantId);
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

        String status = normalizeRequired(request.status());
        if (!ALLOWED_MEMBERSHIP_STATUS.contains(status)) {
            log.warn("成员状态调整失败：状态非法 tenantId={} operatorUserId={} membershipId={} status={} requestId={}", tenantId, operatorUserId, membershipId, status, RequestIds.current());
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_MEMBERSHIP_STATUS_INVALID", "成员状态只能是 active 或 disabled");
        }

        RoleEntity role = roleRepository.findByIdAndTenantIdAndStatus(membership.getRoleId(), tenantId, ACTIVE_STATUS)
            .orElse(null);
        membership.updateStatus(status);
        userMembershipRepository.save(membership);

        if (role != null) {
            if (ACTIVE_STATUS.equals(status)) {
                ensureLoginAssignment(membership.getUserId(), tenantId, role, membership.isDefaultMembership());
            } else {
                // 禁用成员关系时同步移除三大入口中的对应租户登录角色，避免前端隐藏入口之外仍可切换进入。
                userRoleAssignmentRepository.deleteByUserIdAndRoleAndTenantId(membership.getUserId(), resolveLoginRole(role), tenantId);
                if ("tenant_admin".equals(resolveLoginRole(role))) {
                    userRoleAssignmentRepository.deleteByUserIdAndRoleAndTenantId(membership.getUserId(), "business", tenantId);
                }
            }
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

        Pageable pageable = PageRequest.of(
            Math.max(page, 1) - 1,
            Math.min(Math.max(size, 1), 100),
            parseRoleSort(sort)
        );

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
            .map(grant -> toPageGrantResponse(grant, principalNameResolver))
            .toList();
    }

    @Transactional
    public PageGrantResponse createPageGrant(UUID tenantId, UUID operatorUserId, CreatePageGrantRequest request) {
        tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "租户不存在或已停用"));
        String principalType = normalizeRequired(request.principalType());
        String pageKey = normalizeRequired(request.pageKey());
        if (!ALLOWED_PRINCIPAL_TYPES.contains(principalType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_PAGE_GRANT_PRINCIPAL_INVALID", "授权主体只能是角色、部门或用户");
        }
        if (!ALLOWED_PAGE_PERMISSIONS.contains(pageKey)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_PAGE_GRANT_PAGE_INVALID", "包含不支持的页签");
        }
        validatePrincipal(tenantId, principalType, request.principalId());
        if (pageGrantRepository.existsByTenantIdAndPrincipalTypeAndPrincipalIdAndPageKey(tenantId, principalType, request.principalId(), pageKey)) {
            throw new ApiException(HttpStatus.CONFLICT, "ORG_PAGE_GRANT_EXISTS", "该主体已经拥有该页签授权");
        }
        PageGrantEntity grant = PageGrantEntity.create(tenantId, pageKey, principalType, request.principalId());
        pageGrantRepository.save(grant);
        log.info(
            "租户页签授权创建成功 tenantId={} operatorUserId={} grantId={} principalType={} principalId={} pageKey={} requestId={}",
            tenantId,
            operatorUserId,
            grant.getId(),
            principalType,
            request.principalId(),
            pageKey,
            RequestIds.current()
        );
        return toPageGrantResponse(grant, loadPrincipalNameResolver(tenantId));
    }

    @Transactional
    public void deletePageGrant(UUID tenantId, UUID operatorUserId, UUID grantId) {
        PageGrantEntity grant = pageGrantRepository.findByIdAndTenantId(grantId, tenantId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORG_PAGE_GRANT_NOT_FOUND", "页签授权不存在"));
        pageGrantRepository.delete(grant);
        log.info("租户页签授权删除成功 tenantId={} operatorUserId={} grantId={} requestId={}", tenantId, operatorUserId, grantId, RequestIds.current());
    }

    @Transactional(readOnly = true)
    public List<ResourceGrantResponse> listResourceGrants(UUID tenantId) {
        tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "租户不存在或已停用"));
        Map<UUID, SystemCapabilityEntity> capabilitiesById = loadEnabledCapabilitiesById(tenantId);
        PrincipalNameResolver principalNameResolver = loadPrincipalNameResolver(tenantId);
        return resourceGrantRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
            .map(grant -> {
                SystemCapabilityEntity capability = capabilitiesById.get(grant.getResourceId());
                return new ResourceGrantResponse(
                    grant.getId().toString(),
                    grant.getPrincipalType(),
                    grant.getPrincipalId().toString(),
                    principalNameResolver.resolve(grant.getPrincipalType(), grant.getPrincipalId()),
                    grant.getResourceType(),
                    grant.getResourceId().toString(),
                    capability == null ? "已失效资源" : capability.getName(),
                    capability == null ? "" : capability.getCode(),
                    List.of(grant.getActions()),
                    grant.getCreatedAt() == null ? "" : grant.getCreatedAt().toString()
                );
            })
            .toList();
    }

    @Transactional
    public ResourceGrantResponse createResourceGrant(UUID tenantId, UUID operatorUserId, CreateResourceGrantRequest request) {
        tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "租户不存在或已停用"));
        String principalType = normalizeRequired(request.principalType());
        String resourceType = normalizeRequired(request.resourceType());
        if (!ALLOWED_PRINCIPAL_TYPES.contains(principalType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_RESOURCE_GRANT_PRINCIPAL_INVALID", "授权主体只能是角色、部门或用户");
        }
        if (!ALLOWED_RESOURCE_TYPES.contains(resourceType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_RESOURCE_GRANT_RESOURCE_TYPE_INVALID", "包含不支持的资源类型");
        }
        validatePrincipal(tenantId, principalType, request.principalId());
        SystemCapabilityEntity capability = loadEnabledCapabilitiesById(tenantId).get(request.resourceId());
        if (capability == null || !resourceType.equals(capability.getCapabilityType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_RESOURCE_GRANT_RESOURCE_NOT_AVAILABLE", "只能授权系统管理已启用给当前租户的能力资源");
        }
        if (resourceGrantRepository.existsByTenantIdAndPrincipalTypeAndPrincipalIdAndResourceTypeAndResourceId(
            tenantId,
            principalType,
            request.principalId(),
            resourceType,
            request.resourceId()
        )) {
            throw new ApiException(HttpStatus.CONFLICT, "ORG_RESOURCE_GRANT_EXISTS", "该主体已经拥有该资源授权");
        }
        ResourceGrantEntity grant = ResourceGrantEntity.create(
            tenantId,
            resourceType,
            request.resourceId(),
            principalType,
            request.principalId(),
            normalizeResourceActions(request.actions()).toArray(String[]::new)
        );
        resourceGrantRepository.save(grant);
        log.info(
            "租户资源授权创建成功 tenantId={} operatorUserId={} grantId={} principalType={} principalId={} resourceType={} resourceId={} requestId={}",
            tenantId,
            operatorUserId,
            grant.getId(),
            principalType,
            request.principalId(),
            resourceType,
            request.resourceId(),
            RequestIds.current()
        );
        return listResourceGrants(tenantId).stream()
            .filter(row -> row.id().equals(grant.getId().toString()))
            .findFirst()
            .orElseThrow();
    }

    @Transactional
    public void deleteResourceGrant(UUID tenantId, UUID operatorUserId, UUID grantId) {
        ResourceGrantEntity grant = resourceGrantRepository.findByIdAndTenantId(grantId, tenantId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORG_RESOURCE_GRANT_NOT_FOUND", "资源授权不存在"));
        resourceGrantRepository.delete(grant);
        log.info("租户资源授权删除成功 tenantId={} operatorUserId={} grantId={} requestId={}", tenantId, operatorUserId, grantId, RequestIds.current());
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
        Map<UUID, DepartmentEntity> departmentsById = departmentRepository.findByTenantIdAndStatusOrderBySortOrderAscNameAsc(tenantId, ACTIVE_STATUS)
            .stream()
            .collect(Collectors.toMap(DepartmentEntity::getId, Function.identity()));
        Map<UUID, RoleEntity> rolesById = roleRepository.findByTenantIdAndStatusOrderByNameAsc(tenantId, ACTIVE_STATUS)
            .stream()
            .collect(Collectors.toMap(RoleEntity::getId, Function.identity()));

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
                role.getScope(),
                role.getStatus()
            ))
            .toList();

        List<MembershipResponse> membershipResponses = memberships.stream()
            .sorted(Comparator.comparing(UserMembershipEntity::isDefaultMembership).reversed())
            .map(membership -> {
                UserAccount user = usersById.get(membership.getUserId());
                DepartmentEntity department = membership.getDepartmentId() == null ? null : departmentsById.get(membership.getDepartmentId());
                RoleEntity role = rolesById.get(membership.getRoleId());

                return new MembershipResponse(
                    membership.getId().toString(),
                    membership.getUserId().toString(),
                    user == null ? "" : user.getDisplayName(),
                    membership.getDepartmentId() == null ? null : membership.getDepartmentId().toString(),
                    department == null ? "" : department.getName(),
                    membership.getRoleId().toString(),
                    role == null ? "" : role.getName(),
                    role == null ? "" : role.getCode(),
                    membership.getSpaceCode(),
                    membership.isDefaultMembership(),
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

    private static String normalizeOptional(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeSpaceCode(String value) {
        String normalized = normalizeOptional(value);
        return normalized.isBlank() ? "默认空间" : normalized;
    }

    private Sort parseRoleSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "updatedAt");
        }

        String[] parts = sort.split(",", 2);
        String field = switch (parts[0]) {
            case "name", "status", "createdAt", "updatedAt" -> parts[0];
            default -> "updatedAt";
        };
        Sort.Direction direction = parts.length > 1 && "asc".equalsIgnoreCase(parts[1])
            ? Sort.Direction.ASC
            : Sort.Direction.DESC;
        return Sort.by(direction, field);
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

    private PageGrantResponse toPageGrantResponse(PageGrantEntity grant, PrincipalNameResolver principalNameResolver) {
        return new PageGrantResponse(
            grant.getId().toString(),
            grant.getPrincipalType(),
            grant.getPrincipalId().toString(),
            principalNameResolver.resolve(grant.getPrincipalType(), grant.getPrincipalId()),
            grant.getPageKey(),
            formatPagePermissionName(grant.getPageKey()),
            grant.getCreatedAt() == null ? "" : grant.getCreatedAt().toString()
        );
    }

    private static String formatPagePermissionName(String pageKey) {
        return switch (pageKey) {
            case "workbench" -> "业务工作台";
            case "designer" -> "流程设计";
            case "assets" -> "能力资产";
            case "audit" -> "运行审计";
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

    private void ensureLoginAssignment(UUID userId, UUID tenantId, RoleEntity role, boolean defaultAssignment) {
        String systemRole = resolveLoginRole(role);
        String label = role.getName() == null || role.getName().isBlank() ? systemRole : role.getName();

        // user_memberships 负责租户内组织关系，user_role_assignments 负责三大登录入口；两边必须同步，避免成员可见但无法登录。
        userRoleAssignmentRepository.findByUserIdAndRoleAndTenantId(userId, systemRole, tenantId)
            .orElseGet(() -> userRoleAssignmentRepository.save(
                UserRoleAssignmentEntity.create(userId, systemRole, tenantId, label, defaultAssignment)
            ));
    }

    private void removeChangedLoginAssignment(UUID userId, UUID tenantId, RoleEntity oldRole, RoleEntity newRole) {
        if (oldRole == null) {
            return;
        }

        String oldLoginRole = resolveLoginRole(oldRole);
        String newLoginRole = resolveLoginRole(newRole);

        if (!oldLoginRole.equals(newLoginRole)) {
            userRoleAssignmentRepository.deleteByUserIdAndRoleAndTenantId(userId, oldLoginRole, tenantId);
        }
    }

    private static String resolveLoginRole(RoleEntity role) {
        return "tenant_admin".equals(role.getCode()) ? "tenant_admin" : "business";
    }

    private record TenantResourcePermissionPayload(String resourceType, String resourceId, List<String> actions) {
    }

    private interface PrincipalNameResolver {
        String resolve(String principalType, UUID principalId);
    }
}
