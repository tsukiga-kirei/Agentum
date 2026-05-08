package com.agentum.organization.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class DepartmentEntityTest {

    @Test
    void shouldCreateActiveDepartment() {
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID parentId = UUID.fromString("00000000-0000-0000-0000-000000000201");

        DepartmentEntity department = DepartmentEntity.create(tenantId, parentId, "风控部", "risk", 10);

        assertThat(department.getId()).isNotNull();
        assertThat(department.getTenantId()).isEqualTo(tenantId);
        assertThat(department.getParentId()).isEqualTo(parentId);
        assertThat(department.getName()).isEqualTo("风控部");
        assertThat(department.getCode()).isEqualTo("risk");
        assertThat(department.getSortOrder()).isEqualTo(10);
        assertThat(department.getStatus()).isEqualTo("active");
    }
}
