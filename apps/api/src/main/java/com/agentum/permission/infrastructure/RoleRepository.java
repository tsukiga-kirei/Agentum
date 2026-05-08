package com.agentum.permission.infrastructure;

import com.agentum.permission.domain.RoleEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<RoleEntity, UUID> {

    Optional<RoleEntity> findByIdAndStatus(UUID id, String status);
}
