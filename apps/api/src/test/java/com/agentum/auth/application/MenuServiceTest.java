package com.agentum.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.agentum.organization.domain.UserMembershipEntity;
import com.agentum.organization.domain.UserMembershipRoleEntity;
import com.agentum.organization.infrastructure.UserMembershipRepository;
import com.agentum.organization.infrastructure.UserMembershipRoleRepository;
import com.agentum.permission.domain.PageGrantEntity;
import com.agentum.permission.infrastructure.PageGrantRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MenuServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID DEPARTMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000211");

    @Mock
    private PageGrantRepository pageGrantRepository;
    @Mock
    private UserMembershipRepository userMembershipRepository;
    @Mock
    private UserMembershipRoleRepository userMembershipRoleRepository;

    @Test
    void shouldFilterBusinessMenusByTenantPageGrants() {
        MenuService menuService = newService();
        UserMembershipEntity membership = UserMembershipEntity.create(TENANT_ID, USER_ID, DEPARTMENT_ID);
        UserMembershipRoleEntity membershipRole = UserMembershipRoleEntity.create(membership.getId(), ROLE_ID);

        when(userMembershipRepository.findByUserIdAndTenantIdAndStatus(USER_ID, TENANT_ID, "active")).thenReturn(List.of(membership));
        when(userMembershipRoleRepository.findByMembershipIdInAndStatus(any(), eq("active"))).thenReturn(List.of(membershipRole));
        when(pageGrantRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).thenReturn(List.of(
            PageGrantEntity.create(TENANT_ID, UUID.randomUUID(), "人员工作台", "workbench", "user", USER_ID),
            PageGrantEntity.create(TENANT_ID, UUID.randomUUID(), "角色流程设计", "designer", "role", ROLE_ID),
            PageGrantEntity.create(TENANT_ID, UUID.randomUUID(), "外部能力资产", "assets", "user", UUID.randomUUID())
        ));

        assertThat(menuService.resolveMenus("business", TENANT_ID, USER_ID))
            .extracting("key")
            .containsExactly("workbench", "designer");
    }

    @Test
    void shouldReturnEmptyBusinessMenusWhenNoPageGrantsExist() {
        MenuService menuService = newService();
        UserMembershipEntity membership = UserMembershipEntity.create(TENANT_ID, USER_ID, null);

        when(userMembershipRepository.findByUserIdAndTenantIdAndStatus(USER_ID, TENANT_ID, "active")).thenReturn(List.of(membership));
        when(userMembershipRoleRepository.findByMembershipIdInAndStatus(any(), eq("active"))).thenReturn(List.of());
        when(pageGrantRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).thenReturn(List.of());

        assertThat(menuService.resolveMenus("business", TENANT_ID, USER_ID)).isEmpty();
    }

    @Test
    void shouldNotExposeDeprecatedAuditMenuEvenWhenPageGrantExists() {
        MenuService menuService = newService();
        UserMembershipEntity membership = UserMembershipEntity.create(TENANT_ID, USER_ID, null);

        when(userMembershipRepository.findByUserIdAndTenantIdAndStatus(USER_ID, TENANT_ID, "active")).thenReturn(List.of(membership));
        when(userMembershipRoleRepository.findByMembershipIdInAndStatus(any(), eq("active"))).thenReturn(List.of());
        when(pageGrantRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).thenReturn(List.of(
            PageGrantEntity.create(TENANT_ID, UUID.randomUUID(), "历史审计入口", "audit", "user", USER_ID),
            PageGrantEntity.create(TENANT_ID, UUID.randomUUID(), "人员工作台", "workbench", "user", USER_ID)
        ));

        assertThat(menuService.resolveMenus("business", TENANT_ID, USER_ID))
            .extracting("key")
            .containsExactly("workbench");
    }

    @Test
    void shouldKeepSystemRoleMenusIndependentFromTenantPageGrants() {
        MenuService menuService = newService();

        assertThat(menuService.resolveMenus("tenant_admin", TENANT_ID, USER_ID))
            .extracting("key")
            .containsExactly("tenant");
        assertThat(menuService.resolveMenus("system_admin", null, USER_ID))
            .extracting("key")
            .containsExactly("system");
    }

    private MenuService newService() {
        return new MenuService(pageGrantRepository, userMembershipRepository, userMembershipRoleRepository);
    }
}
