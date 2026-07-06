package com.agentum.auth.application;

import com.agentum.auth.interfaces.MenuItemResponse;
import com.agentum.permission.application.BusinessPageAccess;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

    // 阶段一已下线页签；过滤旧 page_grants 或未重启实例，避免侧栏继续展示运行审计入口。
    private static final Set<String> DEPRECATED_MENU_KEYS = Set.of();
    private static final String WORKBENCH_MENU_KEY = "workbench";
    private static final String WORKBENCH_SCHEDULES_PAGE_KEY = "workbench_schedules";

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
            new MenuItemResponse("audit", "运行审计", "Eye", "查看链路、快照、工具和日志")
        )
    );

    private final BusinessPageAccess businessPageAccess;

    public MenuService(BusinessPageAccess businessPageAccess) {
        this.businessPageAccess = businessPageAccess;
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

        Set<String> grantedPageKeys = businessPageAccess.resolveGrantedPageKeys(tenantId, userId);
        if (grantedPageKeys.isEmpty()) {
            return List.of();
        }

        return SYSTEM_ROLE_MENUS.getOrDefault("business", List.of())
            .stream()
            .filter(menu -> grantedPageKeys.contains(menu.key()) || isWorkbenchMenuVisibleByScheduleTab(menu.key(), grantedPageKeys))
            .filter(menu -> !DEPRECATED_MENU_KEYS.contains(menu.key()))
            .toList();
    }

    /**
     * 返回当前业务角色被分配的页签键，前端据此收敛模块内页签。
     *
     * <p>定时任务在租户管理中作为业务侧同级页签分配，当前路由承载在业务工作台模块下，
     * 所以需要把原始页签权限返回给前端做模块内页签过滤。</p>
     */
    public List<String> resolvePermissions(String systemRole, UUID tenantId, UUID userId) {
        if (!"business".equals(systemRole) || tenantId == null || userId == null) {
            return List.of();
        }
        return List.copyOf(businessPageAccess.resolveGrantedPageKeys(tenantId, userId));
    }

    private static boolean isWorkbenchMenuVisibleByScheduleTab(String menuKey, Set<String> grantedPageKeys) {
        return WORKBENCH_MENU_KEY.equals(menuKey) && grantedPageKeys.contains(WORKBENCH_SCHEDULES_PAGE_KEY);
    }
}
