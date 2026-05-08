package com.agentum.organization.application;

import com.agentum.auth.domain.UserAccount;
import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.organization.domain.DepartmentEntity;
import com.agentum.organization.domain.UserMembershipEntity;
import com.agentum.organization.infrastructure.DepartmentRepository;
import com.agentum.organization.infrastructure.UserMembershipRepository;
import com.agentum.organization.interfaces.DepartmentResponse;
import com.agentum.organization.interfaces.CreateMemberRequest;
import com.agentum.organization.interfaces.MemberResponse;
import com.agentum.organization.interfaces.MembershipResponse;
import com.agentum.organization.interfaces.RoleResponse;
import com.agentum.organization.interfaces.TenantOrganizationOverviewResponse;
import com.agentum.permission.domain.RoleEntity;
import com.agentum.permission.infrastructure.RoleRepository;
import com.agentum.shared.api.ApiException;
import com.agentum.tenant.domain.TenantEntity;
import com.agentum.tenant.infrastructure.TenantRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantOrganizationService {

    private static final String ACTIVE_STATUS = "active";

    private final TenantRepository tenantRepository;
    private final UserAccountRepository userAccountRepository;
    private final UserMembershipRepository userMembershipRepository;
    private final DepartmentRepository departmentRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public TenantOrganizationService(
        TenantRepository tenantRepository,
        UserAccountRepository userAccountRepository,
        UserMembershipRepository userMembershipRepository,
        DepartmentRepository departmentRepository,
        RoleRepository roleRepository,
        PasswordEncoder passwordEncoder
    ) {
        this.tenantRepository = tenantRepository;
        this.userAccountRepository = userAccountRepository;
        this.userMembershipRepository = userMembershipRepository;
        this.departmentRepository = departmentRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public TenantOrganizationOverviewResponse createMember(UUID tenantId, CreateMemberRequest request) {
        tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "租户不存在或已停用"));

        String username = normalizeRequired(request.username());

        if (userAccountRepository.existsByUsername(username)) {
            throw new ApiException(HttpStatus.CONFLICT, "ORG_USER_USERNAME_EXISTS", "用户名已存在，请换一个用户名");
        }

        UUID roleId = request.roleId();

        if (roleId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORG_MEMBER_ROLE_REQUIRED", "请选择成员角色");
        }

        roleRepository.findByIdAndTenantIdAndStatus(roleId, tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "ORG_ROLE_NOT_AVAILABLE", "所选角色不属于当前租户或已停用"));

        UUID departmentId = request.departmentId();

        if (departmentId != null) {
            departmentRepository.findByIdAndTenantIdAndStatus(departmentId, tenantId, ACTIVE_STATUS)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "ORG_DEPARTMENT_NOT_AVAILABLE", "所选部门不属于当前租户或已停用"));
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

        return getOverview(tenantId);
    }

    @Transactional(readOnly = true)
    public TenantOrganizationOverviewResponse getOverview(UUID tenantId) {
        TenantEntity tenant = tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "租户不存在或已停用"));

        List<UserMembershipEntity> memberships = userMembershipRepository.findByTenantIdAndStatus(tenantId, ACTIVE_STATUS);
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
}
