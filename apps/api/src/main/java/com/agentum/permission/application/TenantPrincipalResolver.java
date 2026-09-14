package com.agentum.permission.application;

import com.agentum.organization.domain.UserMembershipEntity;
import com.agentum.organization.domain.UserMembershipRoleEntity;
import com.agentum.organization.infrastructure.UserMembershipRepository;
import com.agentum.organization.infrastructure.UserMembershipRoleRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 把租户成员解析为人员、部门、角色三类授权主体。
 *
 * <p>页面控制、流程协作权限等资源范围必须使用同一套主体匹配语义，避免角色或部门授权
 * 在不同业务入口中产生不一致的可见结果。</p>
 */
@Component
public class TenantPrincipalResolver {

    private static final String ACTIVE_STATUS = "active";

    private final UserMembershipRepository userMembershipRepository;
    private final UserMembershipRoleRepository userMembershipRoleRepository;

    public TenantPrincipalResolver(
        UserMembershipRepository userMembershipRepository,
        UserMembershipRoleRepository userMembershipRoleRepository
    ) {
        this.userMembershipRepository = userMembershipRepository;
        this.userMembershipRoleRepository = userMembershipRoleRepository;
    }

    public Set<String> resolvePrincipalKeys(UUID tenantId, UUID userId) {
        if (tenantId == null || userId == null) {
            return Set.of();
        }
        List<UserMembershipEntity> memberships = userMembershipRepository.findByUserIdAndTenantIdAndStatus(userId, tenantId, ACTIVE_STATUS);
        return buildPrincipalKeysByUser(memberships).getOrDefault(userId, Set.of("user:" + userId));
    }

    public Map<UUID, Set<String>> resolveActivePrincipalKeysByUser(UUID tenantId) {
        if (tenantId == null) {
            return Map.of();
        }
        return buildPrincipalKeysByUser(userMembershipRepository.findByTenantIdAndStatus(tenantId, ACTIVE_STATUS));
    }

    private Map<UUID, Set<String>> buildPrincipalKeysByUser(List<UserMembershipEntity> memberships) {
        Map<UUID, Set<String>> keysByUser = new LinkedHashMap<>();
        Map<UUID, UUID> userIdByMembershipId = new LinkedHashMap<>();
        for (UserMembershipEntity membership : memberships) {
            userIdByMembershipId.put(membership.getId(), membership.getUserId());
            Set<String> keys = keysByUser.computeIfAbsent(membership.getUserId(), ignored -> new LinkedHashSet<>());
            keys.add("user:" + membership.getUserId());
            if (membership.getDepartmentId() != null) {
                keys.add("department:" + membership.getDepartmentId());
            }
        }

        Collection<UUID> membershipIds = userIdByMembershipId.keySet();
        if (!membershipIds.isEmpty()) {
            for (UserMembershipRoleEntity link : userMembershipRoleRepository.findByMembershipIdInAndStatus(membershipIds, ACTIVE_STATUS)) {
                UUID userId = userIdByMembershipId.get(link.getMembershipId());
                if (userId != null) {
                    keysByUser.computeIfAbsent(userId, ignored -> new LinkedHashSet<>()).add("role:" + link.getRoleId());
                }
            }
        }
        return keysByUser.entrySet().stream().collect(Collectors.toMap(
            Map.Entry::getKey,
            entry -> Set.copyOf(entry.getValue()),
            (left, right) -> left,
            LinkedHashMap::new
        ));
    }
}
