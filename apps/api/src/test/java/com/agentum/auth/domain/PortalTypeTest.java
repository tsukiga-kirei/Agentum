package com.agentum.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentum.shared.api.ApiException;
import org.junit.jupiter.api.Test;

class PortalTypeTest {

    @Test
    void shouldResolvePortalCode() {
        assertThat(PortalType.fromCode("business")).isEqualTo(PortalType.BUSINESS);
        assertThat(PortalType.fromCode("tenant_admin")).isEqualTo(PortalType.TENANT_ADMIN);
        assertThat(PortalType.fromCode("system_admin")).isEqualTo(PortalType.SYSTEM_ADMIN);
    }

    @Test
    void shouldKeepTenantScopeRules() {
        assertThat(PortalType.BUSINESS.isTenantScoped()).isTrue();
        assertThat(PortalType.TENANT_ADMIN.isTenantScoped()).isTrue();
        assertThat(PortalType.SYSTEM_ADMIN.isTenantScoped()).isFalse();
    }

    @Test
    void shouldRejectUnknownPortalCode() {
        assertThatThrownBy(() -> PortalType.fromCode("unknown"))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("AUTH_PORTAL_INVALID");
    }
}
