package com.agentum.permission.application;

import com.agentum.organization.domain.UserMembershipEntity;
import com.agentum.organization.domain.UserMembershipRoleEntity;
import com.agentum.organization.infrastructure.UserMembershipRepository;
import com.agentum.organization.infrastructure.UserMembershipRoleRepository;
import com.agentum.permission.domain.PageGrantEntity;
import com.agentum.permission.infrastructure.PageGrantRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 租户内业务页签授权解析。
 *
 * <p>页签分配是业务用户能否进入工作台、流程设计、能力资产等模块的第二重权限；
 * 菜单展示与后端模块入口都必须基于同一套主体匹配规则复核，避免出现“看得见页签但接口拒绝”的割裂体验。</p>
 */
@Component
public class BusinessPageAccess {

    private static final String ACTIVE_STATUS = "active";

    private final PageGrantRepository pageGrantRepository;
    private final UserMembershipRepository userMembershipRepository;
    private final UserMembershipRoleRepository userMembershipRoleRepository;

    public BusinessPageAccess(
        PageGrantRepository pageGrantRepository,
        UserMembershipRepository userMembershipRepository,
        UserMembershipRoleRepository userMembershipRoleRepository
    ) {
        this.pageGrantRepository = pageGrantRepository;
        this.userMembershipRepository = userMembershipRepository;
        this.userMembershipRoleRepository = userMembershipRoleRepository;
    }

    public boolean hasPageGrant(UUID tenantId, UUID userId, String pageKey) {
        return resolveGrantedPageKeys(tenantId, userId).contains(pageKey);
    }

    public Set<String> resolveGrantedPageKeys(UUID tenantId, UUID userId) {
        if (tenantId == null || userId == null) {
            return Set.of();
        }

        Set<String> principalKeys = resolvePrincipalKeys(tenantId, userId);
        if (principalKeys.isEmpty()) {
            return Set.of();
        }

        return pageGrantRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
            .filter(grant -> principalKeys.contains(grant.getPrincipalType() + ":" + grant.getPrincipalId()))
            .map(PageGrantEntity::getPageKey)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> resolvePrincipalKeys(UUID tenantId, UUID userId) {
        List<UserMembershipEntity> memberships = userMembershipRepository.findByUserIdAndTenantIdAndStatus(userId, tenantId, ACTIVE_STATUS);
        Set<String> principalKeys = new LinkedHashSet<>();
        principalKeys.add("user:" + userId);
        memberships.stream()
            .map(UserMembershipEntity::getDepartmentId)
            .filter(departmentId -> departmentId != null)
            .map(departmentId -> "department:" + departmentId)
            .forEach(principalKeys::add);

        Set<UUID> membershipIds = memberships.stream().map(UserMembershipEntity::getId).collect(Collectors.toSet());
        if (!membershipIds.isEmpty()) {
            userMembershipRoleRepository.findByMembershipIdInAndStatus(membershipIds, ACTIVE_STATUS)
                .stream()
                .map(UserMembershipRoleEntity::getRoleId)
                .map(roleId -> "role:" + roleId)
                .forEach(principalKeys::add);
        }

        return principalKeys;
    }
}
