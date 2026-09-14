package com.agentum.workflow.application;

import com.agentum.auth.domain.UserAccount;
import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.organization.domain.DepartmentEntity;
import com.agentum.organization.domain.UserMembershipEntity;
import com.agentum.organization.infrastructure.DepartmentRepository;
import com.agentum.organization.infrastructure.UserMembershipRepository;
import com.agentum.permission.application.CollaborationAccessPolicy;
import com.agentum.permission.application.TenantPrincipalResolver;
import com.agentum.permission.domain.RoleEntity;
import com.agentum.permission.infrastructure.RoleRepository;
import com.agentum.shared.api.ApiException;
import com.agentum.workflow.domain.WorkflowAccessGrantEntity;
import com.agentum.workflow.domain.WorkflowDefinitionEntity;
import com.agentum.workflow.interfaces.WorkflowDraftApi;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 流程协作权限统一处理角色、部门、人员主体，并负责把授权解析成最终可见人员。 */
@Service
public class WorkflowAccessService {

    private static final String ACTIVE_STATUS = "active";
    private static final Set<String> READ_PRINCIPAL_TYPES = Set.of("role", "department", "user");

    private final UserMembershipRepository userMembershipRepository;
    private final RoleRepository roleRepository;
    private final DepartmentRepository departmentRepository;
    private final UserAccountRepository userAccountRepository;
    private final TenantPrincipalResolver tenantPrincipalResolver;
    private final CollaborationAccessPolicy collaborationAccessPolicy;

    public WorkflowAccessService(
        UserMembershipRepository userMembershipRepository,
        RoleRepository roleRepository,
        DepartmentRepository departmentRepository,
        UserAccountRepository userAccountRepository,
        TenantPrincipalResolver tenantPrincipalResolver,
        CollaborationAccessPolicy collaborationAccessPolicy
    ) {
        this.userMembershipRepository = userMembershipRepository;
        this.roleRepository = roleRepository;
        this.departmentRepository = departmentRepository;
        this.userAccountRepository = userAccountRepository;
        this.tenantPrincipalResolver = tenantPrincipalResolver;
        this.collaborationAccessPolicy = collaborationAccessPolicy;
    }

    public CollaborationAccessPolicy.AccessLevel resolve(
        WorkflowDefinitionEntity definition,
        UUID operatorUserId,
        List<WorkflowAccessGrantEntity> grants
    ) {
        Set<String> principalKeys = tenantPrincipalResolver.resolvePrincipalKeys(definition.getTenantId(), operatorUserId);
        boolean hasReadGrant = grants.stream().anyMatch(grant -> "read".equals(grant.getAccessLevel()) && matches(grant, principalKeys));
        boolean hasEditGrant = grants.stream().anyMatch(grant -> "edit".equals(grant.getAccessLevel()) && matches(grant, principalKeys));
        return collaborationAccessPolicy.resolve(
            definition.getCreatedBy(),
            operatorUserId,
            definition.getReadScope(),
            hasReadGrant ? Set.of(operatorUserId) : Set.of(),
            definition.getEditScope(),
            hasEditGrant ? Set.of(operatorUserId) : Set.of()
        );
    }

    public List<WorkflowDraftApi.WorkflowPrincipalRef> normalizeReadPrincipals(
        UUID tenantId,
        UUID ownerUserId,
        String scope,
        List<WorkflowDraftApi.WorkflowPrincipalRef> principals
    ) {
        if (!CollaborationAccessPolicy.SCOPE_SPECIFIED.equals(scope)) {
            return List.of();
        }
        if (principals == null || principals.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_ACCESS_PRINCIPALS_REQUIRED", "读取权限选择指定对象时，必须至少选择一个有效角色、部门或人员");
        }

        LinkedHashMap<String, WorkflowDraftApi.WorkflowPrincipalRef> normalized = new LinkedHashMap<>();
        for (WorkflowDraftApi.WorkflowPrincipalRef principal : principals) {
            if (principal == null || principal.principalId() == null || !READ_PRINCIPAL_TYPES.contains(principal.principalType())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_ACCESS_PRINCIPAL_INVALID", "读取权限对象类型无效");
            }
            if ("user".equals(principal.principalType()) && principal.principalId().equals(ownerUserId)) {
                continue;
            }
            validatePrincipal(tenantId, principal);
            normalized.putIfAbsent(principal.principalType() + ":" + principal.principalId(), principal);
        }
        if (normalized.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_ACCESS_PRINCIPALS_REQUIRED", "读取权限选择指定对象时，必须至少选择一个有效角色、部门或人员");
        }
        return new ArrayList<>(normalized.values());
    }

    public List<UUID> normalizeEditUserIds(UUID tenantId, UUID ownerUserId, String scope, List<UUID> userIds) {
        if (!CollaborationAccessPolicy.SCOPE_SPECIFIED.equals(scope)) {
            return List.of();
        }
        if (userIds == null || userIds.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_ACCESS_USERS_REQUIRED", "编辑权限选择指定同事时，必须至少选择一名有效成员");
        }
        Set<UUID> activeMemberIds = activeMemberships(tenantId).stream().map(UserMembershipEntity::getUserId).collect(Collectors.toSet());
        LinkedHashSet<UUID> normalized = new LinkedHashSet<>();
        for (UUID userId : userIds) {
            if (userId == null || userId.equals(ownerUserId)) {
                continue;
            }
            if (!activeMemberIds.contains(userId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_ACCESS_USER_INVALID", "编辑权限对象必须是当前租户内的有效成员");
            }
            normalized.add(userId);
        }
        if (normalized.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_ACCESS_USERS_REQUIRED", "编辑权限选择指定同事时，必须至少选择一名有效成员");
        }
        return new ArrayList<>(normalized);
    }

    public WorkflowDraftApi.WorkflowAccessCatalog getCatalog(UUID tenantId, UUID operatorUserId) {
        List<WorkflowDraftApi.WorkflowAccessRoleRow> roles = roleRepository.findByTenantIdAndStatusOrderByNameAsc(tenantId, ACTIVE_STATUS).stream()
            .map(role -> new WorkflowDraftApi.WorkflowAccessRoleRow(
                role.getId(), role.getCode(), role.getName(), role.getDescription() == null ? "" : role.getDescription(), role.getStatus()
            ))
            .toList();
        List<WorkflowDraftApi.WorkflowAccessDepartmentRow> departments = departmentRepository
            .findByTenantIdAndStatusOrderBySortOrderAscNameAsc(tenantId, ACTIVE_STATUS).stream()
            .map(department -> new WorkflowDraftApi.WorkflowAccessDepartmentRow(
                department.getId(),
                department.getParentId(),
                department.getName(),
                department.getCode() == null ? "" : department.getCode(),
                department.getSortOrder(),
                department.getStatus()
            ))
            .toList();
        List<UUID> userIds = activeMemberships(tenantId).stream()
            .map(UserMembershipEntity::getUserId)
            .filter(userId -> !userId.equals(operatorUserId))
            .distinct()
            .toList();
        Map<UUID, UserAccount> usersById = userAccountRepository.findAllById(userIds).stream()
            .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
        List<WorkflowDraftApi.WorkflowAccessMemberRow> members = userIds.stream()
            .map(usersById::get)
            .filter(user -> user != null && ACTIVE_STATUS.equals(user.getStatus()))
            .sorted(Comparator.comparing(UserAccount::getDisplayName, Comparator.nullsLast(String::compareToIgnoreCase)))
            .map(user -> new WorkflowDraftApi.WorkflowAccessMemberRow(
                user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail() == null ? "" : user.getEmail(), user.getStatus()
            ))
            .toList();
        return new WorkflowDraftApi.WorkflowAccessCatalog(roles, departments, members);
    }

    public List<WorkflowDraftApi.WorkflowEffectiveReaderRow> resolveEffectiveReaders(
        WorkflowDefinitionEntity definition,
        List<WorkflowAccessGrantEntity> grants
    ) {
        Map<UUID, Set<String>> principalKeysByUser = new LinkedHashMap<>(
            tenantPrincipalResolver.resolveActivePrincipalKeysByUser(definition.getTenantId())
        );
        if (definition.getCreatedBy() != null) {
            principalKeysByUser.computeIfAbsent(definition.getCreatedBy(), ignored -> Set.of("user:" + definition.getCreatedBy()));
        }

        Map<UUID, UserAccount> usersById = userAccountRepository.findAllById(principalKeysByUser.keySet()).stream()
            .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
        Map<UUID, LinkedHashSet<String>> sourcesByUser = new LinkedHashMap<>();
        Map<UUID, String> roleNames = roleRepository.findByTenantIdOrderByNameAsc(definition.getTenantId()).stream()
            .collect(Collectors.toMap(RoleEntity::getId, RoleEntity::getName));
        Map<UUID, String> departmentNames = departmentRepository.findByTenantIdOrderBySortOrderAscNameAsc(definition.getTenantId()).stream()
            .collect(Collectors.toMap(DepartmentEntity::getId, DepartmentEntity::getName));

        for (Map.Entry<UUID, Set<String>> entry : principalKeysByUser.entrySet()) {
            UUID userId = entry.getKey();
            LinkedHashSet<String> sources = new LinkedHashSet<>();
            if (userId.equals(definition.getCreatedBy())) {
                sources.add("流程负责人");
            }
            if (CollaborationAccessPolicy.SCOPE_ALL.equals(definition.getEditScope())) {
                sources.add("可编辑 · 全体同事");
            } else if (CollaborationAccessPolicy.SCOPE_SPECIFIED.equals(definition.getEditScope())) {
                grants.stream().filter(grant -> "edit".equals(grant.getAccessLevel()) && matches(grant, entry.getValue()))
                    .forEach(ignored -> sources.add("可编辑 · 单独指定"));
            }
            if (CollaborationAccessPolicy.SCOPE_ALL.equals(definition.getReadScope())) {
                sources.add("读取 · 全体同事");
            } else if (CollaborationAccessPolicy.SCOPE_SPECIFIED.equals(definition.getReadScope())) {
                grants.stream().filter(grant -> "read".equals(grant.getAccessLevel()) && matches(grant, entry.getValue()))
                    .forEach(grant -> sources.add(formatSource(grant, roleNames, departmentNames)));
            }
            if (!sources.isEmpty()) {
                sourcesByUser.put(userId, sources);
            }
        }

        return sourcesByUser.entrySet().stream()
            .map(entry -> {
                UserAccount user = usersById.get(entry.getKey());
                return new WorkflowDraftApi.WorkflowEffectiveReaderRow(
                    entry.getKey(),
                    user == null ? "" : user.getUsername(),
                    user == null ? "已失效成员" : user.getDisplayName(),
                    List.copyOf(entry.getValue())
                );
            })
            .sorted(Comparator
                .comparing((WorkflowDraftApi.WorkflowEffectiveReaderRow row) -> !row.userId().equals(definition.getCreatedBy()))
                .thenComparing(WorkflowDraftApi.WorkflowEffectiveReaderRow::displayName, Comparator.nullsLast(String::compareToIgnoreCase)))
            .toList();
    }

    private void validatePrincipal(UUID tenantId, WorkflowDraftApi.WorkflowPrincipalRef principal) {
        if ("role".equals(principal.principalType())) {
            roleRepository.findByIdAndTenantIdAndStatus(principal.principalId(), tenantId, ACTIVE_STATUS)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_ACCESS_PRINCIPAL_INVALID", "所选角色不属于当前租户或已停用"));
            return;
        }
        if ("department".equals(principal.principalType())) {
            departmentRepository.findByIdAndTenantIdAndStatus(principal.principalId(), tenantId, ACTIVE_STATUS)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_ACCESS_PRINCIPAL_INVALID", "所选部门不属于当前租户或已停用"));
            return;
        }
        boolean activeMember = activeMemberships(tenantId).stream().anyMatch(membership -> membership.getUserId().equals(principal.principalId()));
        if (!activeMember) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_ACCESS_PRINCIPAL_INVALID", "所选人员不是当前租户启用成员");
        }
    }

    private List<UserMembershipEntity> activeMemberships(UUID tenantId) {
        return userMembershipRepository.findByTenantIdAndStatus(tenantId, ACTIVE_STATUS);
    }

    private boolean matches(WorkflowAccessGrantEntity grant, Set<String> principalKeys) {
        return principalKeys.contains(grant.getPrincipalType() + ":" + grant.getPrincipalId());
    }

    private String formatSource(
        WorkflowAccessGrantEntity grant,
        Map<UUID, String> roleNames,
        Map<UUID, String> departmentNames
    ) {
        return switch (grant.getPrincipalType()) {
            case "role" -> "角色 · " + roleNames.getOrDefault(grant.getPrincipalId(), "已失效角色");
            case "department" -> "部门 · " + departmentNames.getOrDefault(grant.getPrincipalId(), "已失效部门");
            default -> "读取 · 单独指定";
        };
    }
}
