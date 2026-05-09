package com.agentum.system.infrastructure;

import com.agentum.system.domain.SystemCapabilityEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SystemCapabilityRepository extends JpaRepository<SystemCapabilityEntity, UUID> {

    List<SystemCapabilityEntity> findAllByOrderByNameAsc();

    Optional<SystemCapabilityEntity> findByCodeAndVersion(String code, String version);
}
