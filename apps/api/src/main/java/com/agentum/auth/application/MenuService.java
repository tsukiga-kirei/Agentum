package com.agentum.auth.application;

import com.agentum.auth.interfaces.MenuItemResponse;
import com.agentum.organization.domain.UserMembershipEntity;
import com.agentum.organization.domain.UserMembershipRoleEntity;
import com.agentum.organization.infrastructure.UserMembershipRepository;
import com.agentum.organization.infrastructure.UserMembershipRoleRepository;
import com.agentum.permission.domain.PageGrantEntity;
import com.agentum.permission.infrastructure.PageGrantRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 菜单服务：根据系统角色计算用户可见的左侧菜单。
 *
 * 第一层：系统角色决定默认菜单集合。
 *   - system_admin → 系统管理
 *   - tenant_admin → 租户管理
 *   - business → 业务工作台（第二层可扩展）
 *
 * 第二层：业务用户继续按租户内页签分配过滤，分配主体支持人员、部门和租户内角色。
 */
@Service
public class MenuService {

    private static final String ACTIVE_STATUS = "active";

    // 系统角色 → 默认菜单映射。
    private static final Map<String, List<MenuItemResponse>> SYSTEM_ROLE_MENUS = Map.of(
        "system_admin", List.of(
            new MenuItemResponse("system", "系统管理", "Settings", "租户、模型、底座")
        ),
        "tenant_admin", List.of(
            new MenuItemResponse("tenant", "租户管理", "ShieldCheck", "人员、角色、权限")
        ),
        "business", List.of(
            new MenuItemResponse("workbench", "业务工作台", "LayoutDashboard", "待办、发起和结果"),
            new MenuItemResponse("designer", "流程设计", "GitBranch", "画布与节点配置"),
            new MenuItemResponse("assets", "能力资产", "Library", "智能体、Skills、MCP"),
            new MenuItemResponse("audit", "运行审计", "Activity", "只读证据链")
        )
    );

    private final PageGrantRepository pageGrantRepository;
    private final UserMembershipRepository userMembershipRepository;
    private final UserMembershipRoleRepository userMembershipRoleRepository;

    public MenuService(
        PageGrantRepository pageGrantRepository,
        UserMembershipRepository userMembershipRepository,
        UserMembershipRoleRepository userMembershipRoleRepository
    ) {
        this.pageGrantRepository = pageGrantRepository;
        this.userMembershipRepository = userMembershipRepository;
        this.userMembershipRoleRepository = userMembershipRoleRepository;
    }

    /**
     * 根据系统角色计算可见菜单。
     * 第一层入口仍由系统角色控制；业务侧页签必须叠加租户内页签分配，避免无授权用户看到业务菜单。
     */
    public List<MenuItemResponse> resolveMenus(String systemRole, UUID tenantId, UUID userId) {
        if (!"business".equals(systemRole)) {
            List<MenuItemResponse> menus = SYSTEM_ROLE_MENUS.get(systemRole);
            return menus != null ? menus : new ArrayList<>();
        }

        if (tenantId == null || userId == null) {
            return List.of();
        }

        Set<String> principalKeys = resolvePrincipalKeys(tenantId, userId);
        if (principalKeys.isEmpty()) {
            return List.of();
        }

        Set<String> grantedPageKeys = pageGrantRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)
            .stream()
            .filter(grant -> principalKeys.contains(grant.getPrincipalType() + ":" + grant.getPrincipalId()))
            .map(PageGrantEntity::getPageKey)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        return SYSTEM_ROLE_MENUS.getOrDefault("business", List.of())
            .stream()
            .filter(menu -> grantedPageKeys.contains(menu.key()))
            .toList();
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
