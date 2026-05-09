package com.agentum.system.infrastructure;

import com.agentum.system.domain.ModelProviderEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelProviderRepository extends JpaRepository<ModelProviderEntity, UUID> {

    List<ModelProviderEntity> findAllByOrderByNameAsc();
}
