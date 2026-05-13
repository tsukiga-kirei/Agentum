package com.agentum.workflow.application;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.organization.domain.UserMembershipEntity;
import com.agentum.organization.infrastructure.UserMembershipRepository;
import com.agentum.permission.domain.RoleEntity;
import com.agentum.permission.infrastructure.RoleRepository;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class WorkflowDesignAccess {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDesignAccess.class);
    private static final String ACTIVE_STATUS = "active";
    private static final Set<String> DESIGN_ROLE_CODES = Set.of("workflow_designer", "tenant_admin");

    private final UserMembershipRepository userMembershipRepository;
    private final RoleRepository roleRepository;

    public WorkflowDesignAccess(UserMembershipRepository userMembershipRepository, RoleRepository roleRepository) {
        this.userMembershipRepository = userMembershipRepository;
        this.roleRepository = roleRepository;
    }

    public void assertCanDesign(CurrentUserPrincipal principal, UUID tenantId) {
        if (principal == null) {
            log.warn("工作流设计访问被拒绝：未登录 tenantId={} requestId={}", tenantId, RequestIds.current());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请先登录后再访问");
        }

        if ("system_admin".equals(principal.role())) {
            log.debug("工作流设计访问通过：系统管理员 userId={} targetTenantId={} requestId={}", principal.userId(), tenantId, RequestIds.current());
            return;
        }

        if (principal.tenantId() == null || !principal.tenantId().equals(tenantId)) {
            log.warn(
                "工作流设计访问被拒绝：租户上下文不匹配 userId={} principalTenantId={} targetTenantId={} requestId={}",
                principal.userId(),
                principal.tenantId(),
                tenantId,
                RequestIds.current()
            );
            throw denied();
        }

        if ("tenant_admin".equals(principal.role())) {
            log.debug("工作流设计访问通过：租户管理员 userId={} tenantId={} requestId={}", principal.userId(), tenantId, RequestIds.current());
            return;
        }

        // 第一阶段尚未把 tenant_org_roles 绑定到成员关系，这里先用租户内内置角色 workflow_designer 控制流程设计入口。
        List<UserMembershipEntity> memberships = userMembershipRepository.findByUserIdAndTenantIdAndStatus(principal.userId(), tenantId, ACTIVE_STATUS);
        Map<UUID, RoleEntity> rolesById = roleRepository.findAllById(memberships.stream().map(UserMembershipEntity::getRoleId).collect(Collectors.toSet()))
            .stream()
            .collect(Collectors.toMap(RoleEntity::getId, Function.identity()));
        boolean allowed = memberships.stream()
            .map(membership -> rolesById.get(membership.getRoleId()))
            .anyMatch(role -> role != null && DESIGN_ROLE_CODES.contains(role.getCode()));

        if (!allowed) {
            log.warn(
                "工作流设计访问被拒绝：缺少流程设计角色 userId={} tenantId={} systemRole={} requestId={}",
                principal.userId(),
                tenantId,
                principal.role(),
                RequestIds.current()
            );
            throw denied();
        }

        log.debug("工作流设计访问通过 userId={} tenantId={} requestId={}", principal.userId(), tenantId, RequestIds.current());
    }

    private static ApiException denied() {
        return new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_WORKFLOW_DESIGN_DENIED", "当前账号没有流程设计权限");
    }
}
