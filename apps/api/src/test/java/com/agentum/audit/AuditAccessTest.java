package com.agentum.audit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.agentum.audit.application.AuditService;
import com.agentum.audit.interfaces.AuditController;
import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.organization.domain.UserMembershipEntity;
import com.agentum.organization.infrastructure.UserMembershipRepository;
import com.agentum.organization.infrastructure.UserMembershipRoleRepository;
import com.agentum.permission.application.BusinessPageAccess;
import com.agentum.permission.domain.PageGrantEntity;
import com.agentum.permission.infrastructure.PageGrantRepository;
import com.agentum.shared.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class AuditAccessTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID CROSS_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ROLE_ASSIGNMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000502");

    @Mock
    private AuditService auditService;

    @Mock
    private UserMembershipRepository userMembershipRepository;

    @Mock
    private UserMembershipRoleRepository userMembershipRoleRepository;

    @Mock
    private PageGrantRepository pageGrantRepository;

    @Mock
    private HttpServletRequest request;

    private BusinessPageAccess businessPageAccess;
    private AuditController auditController;

    @BeforeEach
    void setUp() {
        businessPageAccess = new BusinessPageAccess(pageGrantRepository, userMembershipRepository, userMembershipRoleRepository);
        auditController = new AuditController(auditService, businessPageAccess);
    }

    @Test
    void shouldAllowSystemAdminEvenCrossTenant() {
        CurrentUserPrincipal systemAdmin = newPrincipal("system_admin", TENANT_ID);
        // 系统管理员跨租户查询审计应能放行
        assertThatCode(() -> invokeControllerCheck(systemAdmin, CROSS_TENANT_ID))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldAllowTenantAdminInOwnTenant() {
        CurrentUserPrincipal tenantAdmin = newPrincipal("tenant_admin", TENANT_ID);
        assertThatCode(() -> invokeControllerCheck(tenantAdmin, TENANT_ID))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectTenantAdminCrossTenant() {
        CurrentUserPrincipal tenantAdmin = newPrincipal("tenant_admin", TENANT_ID);
        assertThatThrownBy(() -> invokeControllerCheck(tenantAdmin, CROSS_TENANT_ID))
            .isInstanceOf(ApiException.class)
            .extracting("code", "status")
            .containsExactly("AUDIT_CROSS_TENANT_DENIED", HttpStatus.FORBIDDEN);
    }

    @Test
    void shouldAllowBusinessUserWithAuditPageGrant() {
        CurrentUserPrincipal businessUser = newPrincipal("business", TENANT_ID);
        UserMembershipEntity membership = UserMembershipEntity.create(TENANT_ID, USER_ID, null);

        when(userMembershipRepository.findByUserIdAndTenantIdAndStatus(USER_ID, TENANT_ID, "active")).thenReturn(List.of(membership));
        when(userMembershipRoleRepository.findByMembershipIdInAndStatus(any(), eq("active"))).thenReturn(List.of());
        when(pageGrantRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).thenReturn(List.of(
            PageGrantEntity.create(TENANT_ID, UUID.randomUUID(), "运行审计页签", "audit", "user", USER_ID)
        ));

        assertThatCode(() -> invokeControllerCheck(businessUser, TENANT_ID))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectBusinessUserWithoutAuditPageGrant() {
        CurrentUserPrincipal businessUser = newPrincipal("business", TENANT_ID);
        UserMembershipEntity membership = UserMembershipEntity.create(TENANT_ID, USER_ID, null);

        when(userMembershipRepository.findByUserIdAndTenantIdAndStatus(USER_ID, TENANT_ID, "active")).thenReturn(List.of(membership));
        when(userMembershipRoleRepository.findByMembershipIdInAndStatus(any(), eq("active"))).thenReturn(List.of());
        when(pageGrantRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> invokeControllerCheck(businessUser, TENANT_ID))
            .isInstanceOf(ApiException.class)
            .extracting("code", "status")
            .containsExactly("AUDIT_ACCESS_DENIED", HttpStatus.FORBIDDEN);
    }

    @Test
    void shouldRejectBusinessUserCrossTenant() {
        CurrentUserPrincipal businessUser = newPrincipal("business", TENANT_ID);
        assertThatThrownBy(() -> invokeControllerCheck(businessUser, CROSS_TENANT_ID))
            .isInstanceOf(ApiException.class)
            .extracting("code", "status")
            .containsExactly("AUDIT_CROSS_TENANT_DENIED", HttpStatus.FORBIDDEN);
    }

    // 辅助方法，用反射或调用 Controller 里的私有方法间接调用 assertAuditAccess
    // 我们可以直接通过 listRuns 抛出的异常来验证其权限断言
    private void invokeControllerCheck(CurrentUserPrincipal principal, UUID targetTenantId) {
        auditController.listRuns(targetTenantId, principal, "", "", 1, 20, "startedAt,desc", request);
    }

    private static CurrentUserPrincipal newPrincipal(String role, UUID tenantId) {
        return new CurrentUserPrincipal(USER_ID, "audit-tester", tenantId, role, role, ROLE_ASSIGNMENT_ID);
    }
}
