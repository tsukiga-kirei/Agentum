package com.agentum.organization.infrastructure;

import com.agentum.organization.domain.TenantOrgRoleEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

// 租户内自定义角色仓储，用于查询业务用户的页签权限。
public interface TenantOrgRoleRepository extends JpaRepository<TenantOrgRoleEntity, UUID> {

    List<TenantOrgRoleEntity> findByTenantIdAndStatus(UUID tenantId, String status);
}
