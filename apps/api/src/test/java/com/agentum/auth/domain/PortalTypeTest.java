package com.agentum.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentum.shared.api.ApiException;
import org.junit.jupiter.api.Test;

class PortalTypeTest {

    @Test
    void shouldResolvePortalCode() {
        assertThat(PortalType.fromCode("business")).isEqualTo(PortalType.BUSINESS);
        assertThat(PortalType.fromCode("space_admin")).isEqualTo(PortalType.SPACE_ADMIN);
        assertThat(PortalType.fromCode("system_admin")).isEqualTo(PortalType.SYSTEM_ADMIN);
    }

    @Test
    void shouldKeepTenantScopeRules() {
        assertThat(PortalType.BUSINESS.isTenantScoped()).isTrue();
        assertThat(PortalType.SPACE_ADMIN.isTenantScoped()).isTrue();
        assertThat(PortalType.SYSTEM_ADMIN.isTenantScoped()).isFalse();
    }

    @Test
    void shouldRestrictRolesByPortal() {
        assertThat(PortalType.BUSINESS.allowsRole("executor")).isTrue();
        assertThat(PortalType.BUSINESS.allowsRole("workflow_designer")).isTrue();
        assertThat(PortalType.BUSINESS.allowsRole("space_admin")).isFalse();
        assertThat(PortalType.SPACE_ADMIN.allowsRole("space_admin")).isTrue();
        assertThat(PortalType.SYSTEM_ADMIN.allowsRole("system_admin")).isTrue();
    }

    @Test
    void shouldRejectUnknownPortalCode() {
        assertThatThrownBy(() -> PortalType.fromCode("unknown"))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("AUTH_PORTAL_INVALID");
    }
}
