package com.agentum.auth.application;

import com.agentum.auth.interfaces.MenuItemResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 菜单服务：根据系统角色计算用户可见的左侧菜单。
 *
 * 第一层：系统角色决定默认菜单集合。
 *   - system_admin → 系统管理
 *   - tenant_admin → 租户管理
 *   - business → 业务工作台（第二层可扩展）
 *
 * 第二层（后续实现）：业务用户的菜单可通过租户内自定义角色的 page_permissions 扩展。
 */
@Service
public class MenuService {

    // 系统角色 → 默认菜单映射。
    // business 角色第一阶段默认给全功能菜单，后续由 tenant_org_roles.page_permissions 动态控制。
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

    /**
     * 根据系统角色计算可见菜单。
     * 第一阶段：直接返回角色默认菜单。
     * 后续：business 角色需要查询 tenant_org_roles.page_permissions 做进一步过滤。
     */
    public List<MenuItemResponse> resolveMenus(String systemRole) {
        List<MenuItemResponse> menus = SYSTEM_ROLE_MENUS.get(systemRole);
        return menus != null ? menus : new ArrayList<>();
    }
}
