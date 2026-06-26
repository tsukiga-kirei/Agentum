package com.agentum.tenant.infrastructure;

import com.agentum.tenant.domain.TenantEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<TenantEntity, UUID> {

    List<TenantEntity> findByStatusOrderByNameAsc(String status);

    List<TenantEntity> findAllByOrderByNameAsc();

    long countByStatus(String status);

    boolean existsByCode(String code);

    Optional<TenantEntity> findByIdAndStatus(UUID id, String status);

    Optional<TenantEntity> findByCodeAndStatus(String code, String status);
}
