package com.agentum.organization.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserMembershipEntityTest {

    @Test
    void shouldCreateDefaultActiveMembership() {
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        UUID departmentId = UUID.fromString("00000000-0000-0000-0000-000000000301");

        UserMembershipEntity membership = UserMembershipEntity.create(tenantId, userId, departmentId, "默认空间");

        assertThat(membership.getId()).isNotNull();
        assertThat(membership.getTenantId()).isEqualTo(tenantId);
        assertThat(membership.getUserId()).isEqualTo(userId);
        assertThat(membership.getDepartmentId()).isEqualTo(departmentId);
        assertThat(membership.getSpaceCode()).isEqualTo("默认空间");
        assertThat(membership.isDefaultMembership()).isTrue();
        assertThat(membership.getStatus()).isEqualTo("active");
    }
}
