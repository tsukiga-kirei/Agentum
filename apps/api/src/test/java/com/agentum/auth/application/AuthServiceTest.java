package com.agentum.auth.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.agentum.auth.domain.UserAccount;
import com.agentum.auth.domain.UserRoleAssignmentEntity;
import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.auth.infrastructure.UserRoleAssignmentRepository;
import com.agentum.auth.interfaces.BootstrapAdminRequest;
import com.agentum.auth.interfaces.ChangeMyPasswordRequest;
import com.agentum.shared.api.ApiException;
import com.agentum.tenant.infrastructure.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final Instant NOW = Instant.parse("2026-05-15T08:00:00Z");

    @Test
    void shouldReportBootstrapRequiredWhenNoUserExists() {
        UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
        AuthService authService = newAuthService(userAccountRepository);

        when(userAccountRepository.count()).thenReturn(0L);

        assertThat(authService.bootstrapStatus().needsSetup()).isTrue();
    }

    @Test
    void shouldCreateFirstSystemAdminDuringBootstrap() {
        UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
        UserRoleAssignmentRepository roleAssignmentRepository = mock(UserRoleAssignmentRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        AuthService authService = newAuthService(userAccountRepository, roleAssignmentRepository, passwordEncoder);

        when(userAccountRepository.count()).thenReturn(0L);
        when(userAccountRepository.existsByUsername("root_admin")).thenReturn(false);
        when(passwordEncoder.encode("agentum123")).thenReturn("hashed-password");

        authService.bootstrapAdmin(new BootstrapAdminRequest(" root_admin ", "平台管理员", "agentum123", "root@agentum.dev"));

        ArgumentCaptor<UserAccount> userCaptor = ArgumentCaptor.forClass(UserAccount.class);
        ArgumentCaptor<UserRoleAssignmentEntity> roleCaptor = ArgumentCaptor.forClass(UserRoleAssignmentEntity.class);
        verify(userAccountRepository).save(userCaptor.capture());
        verify(roleAssignmentRepository).save(roleCaptor.capture());

        UserAccount user = userCaptor.getValue();
        UserRoleAssignmentEntity role = roleCaptor.getValue();
        assertThat(user.getUsername()).isEqualTo("root_admin");
        assertThat(user.getPasswordHash()).isEqualTo("hashed-password");
        assertThat(role.getUserId()).isEqualTo(user.getId());
        assertThat(role.getRole()).isEqualTo("system_admin");
        assertThat(role.getTenantId()).isNull();
        assertThat(role.isDefaultAssignment()).isTrue();
    }

    @Test
    void shouldRejectBootstrapWhenUserAlreadyExists() {
        UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
        UserRoleAssignmentRepository roleAssignmentRepository = mock(UserRoleAssignmentRepository.class);
        AuthService authService = newAuthService(userAccountRepository, roleAssignmentRepository, mock(PasswordEncoder.class));

        when(userAccountRepository.count()).thenReturn(1L);

        assertThatThrownBy(() -> authService.bootstrapAdmin(new BootstrapAdminRequest("root_admin", "平台管理员", "agentum123", "")))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("AUTH_BOOTSTRAP_ALREADY_INITIALIZED");
        verify(userAccountRepository, never()).save(any());
        verify(roleAssignmentRepository, never()).save(any());
    }

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

    @Test
    void shouldChangePasswordAndRevokeRefreshToken() {
        UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        AuthRefreshTokenService refreshTokenService = mock(AuthRefreshTokenService.class);
        AuthService authService = new AuthService(
            userAccountRepository,
            mock(TenantRepository.class),
            mock(UserRoleAssignmentRepository.class),
            passwordEncoder,
            mock(AuthTokenService.class),
            refreshTokenService,
            mock(MenuService.class),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        UserAccount user = UserAccount.create("operator", "old-hash", "业务用户", "operator@agentum.dev");
        CurrentUserPrincipal principal = new CurrentUserPrincipal(user.getId(), "operator", TENANT_ID, "business", "business", UUID.randomUUID());

        when(userAccountRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("old-password", "old-hash")).thenReturn(true);
        when(passwordEncoder.matches("new-password", "old-hash")).thenReturn(false);
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");

        authService.changeMyPassword(principal, new ChangeMyPasswordRequest("old-password", "new-password"), "refresh-token");

        ArgumentCaptor<UserAccount> userCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository).save(userCaptor.capture());
        verify(refreshTokenService).revoke("refresh-token");
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("new-hash");
    }

    @Test
    void shouldRejectPasswordChangeWhenCurrentPasswordIsWrong() {
        UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        AuthService authService = newAuthService(userAccountRepository, mock(UserRoleAssignmentRepository.class), passwordEncoder);
        UserAccount user = UserAccount.create("operator", "old-hash", "业务用户", "operator@agentum.dev");
        CurrentUserPrincipal principal = new CurrentUserPrincipal(user.getId(), "operator", TENANT_ID, "business", "business", UUID.randomUUID());

        when(userAccountRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("bad-password", "old-hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.changeMyPassword(principal, new ChangeMyPasswordRequest("bad-password", "new-password"), "refresh-token"))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("AUTH_CURRENT_PASSWORD_INVALID");
        verify(userAccountRepository, never()).save(any());
    }

    private AuthService newAuthService(UserAccountRepository userAccountRepository) {
        return newAuthService(userAccountRepository, mock(UserRoleAssignmentRepository.class), mock(PasswordEncoder.class));
    }

    private AuthService newAuthService(
        UserAccountRepository userAccountRepository,
        UserRoleAssignmentRepository roleAssignmentRepository,
        PasswordEncoder passwordEncoder
    ) {
        return new AuthService(
            userAccountRepository,
            mock(TenantRepository.class),
            roleAssignmentRepository,
            passwordEncoder,
            mock(AuthTokenService.class),
            mock(AuthRefreshTokenService.class),
            mock(MenuService.class),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }
}
