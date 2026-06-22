package com.agentum.auth.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentum.auth.domain.UserAccount;
import com.agentum.auth.domain.UserRoleAssignmentEntity;
import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.auth.infrastructure.UserRoleAssignmentRepository;
import com.agentum.shared.api.ApiException;
import com.agentum.tenant.infrastructure.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final Instant NOW = Instant.parse("2026-05-15T08:00:00Z");

    @Test
    void shouldRejectCurrentUserWhenTenantIsSuspended() {
        UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
        TenantRepository tenantRepository = mock(TenantRepository.class);
        UserRoleAssignmentRepository roleAssignmentRepository = mock(UserRoleAssignmentRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        AuthTokenService authTokenService = mock(AuthTokenService.class);
        AuthRefreshTokenService refreshTokenService = mock(AuthRefreshTokenService.class);
        MenuService menuService = mock(MenuService.class);
        AuthService authService = new AuthService(
            userAccountRepository,
            tenantRepository,
            roleAssignmentRepository,
            passwordEncoder,
            authTokenService,
            refreshTokenService,
            menuService,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        UserAccount user = UserAccount.create("operator", "hash", "业务用户", "operator@agentum.dev");
        UserRoleAssignmentEntity assignment = UserRoleAssignmentEntity.create(user.getId(), "business", TENANT_ID, "云程科技 - 业务用户", true);
        CurrentUserPrincipal principal = new CurrentUserPrincipal(
            user.getId(),
            "operator",
            TENANT_ID,
            "business",
            "business",
            assignment.getId()
        );

        when(userAccountRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(roleAssignmentRepository.findByUserIdOrderByDefaultAssignmentDesc(user.getId())).thenReturn(List.of(assignment));
        // 租户已停用时，按 active 查询为空；/me 必须拒绝旧 token 恢复会话。
        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.currentUser(principal))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("TENANT_NOT_AVAILABLE");
    }
}
