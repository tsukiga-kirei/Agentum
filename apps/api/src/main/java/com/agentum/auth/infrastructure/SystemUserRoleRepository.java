package com.agentum.auth.infrastructure;

import com.agentum.auth.domain.SystemUserRoleEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SystemUserRoleRepository extends JpaRepository<SystemUserRoleEntity, UUID> {

    List<SystemUserRoleEntity> findByUserId(UUID userId);
}
