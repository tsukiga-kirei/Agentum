package com.agentum.auth.infrastructure;

import com.agentum.auth.domain.UserRoleAssignmentEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

// 角色分配仓储负责查询用户的所有系统级角色，支持登录时匹配入口和角色切换。
public interface UserRoleAssignmentRepository extends JpaRepository<UserRoleAssignmentEntity, UUID> {

    List<UserRoleAssignmentEntity> findByUserIdOrderByDefaultAssignmentDesc(UUID userId);

    Optional<UserRoleAssignmentEntity> findByUserIdAndRoleAndTenantId(UUID userId, String role, UUID tenantId);

    // 系统管理员的 tenant_id 为 NULL，需要特殊查询
    Optional<UserRoleAssignmentEntity> findByUserIdAndRoleAndTenantIdIsNull(UUID userId, String role);

    List<UserRoleAssignmentEntity> findByTenantId(UUID tenantId);
}
