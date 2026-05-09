package com.agentum.system.infrastructure;

import com.agentum.system.domain.ModelProviderTypeEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelProviderTypeRepository extends JpaRepository<ModelProviderTypeEntity, UUID> {

    List<ModelProviderTypeEntity> findByStatusOrderByNameAsc(String status);

    Optional<ModelProviderTypeEntity> findByCodeAndStatus(String code, String status);
}
