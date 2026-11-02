package com.agentum.delivery.template;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface DeliveryTemplateRepository extends JpaRepository<DeliveryTemplateEntity, UUID> {
    Optional<DeliveryTemplateEntity> findByIdAndTenantId(UUID id, UUID tenantId);
    Optional<DeliveryTemplateEntity> findFirstByTemplateIdOrderByVersionDesc(UUID templateId);
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from DeliveryTemplateEntity t where t.id = :id and t.tenantId = :tenantId")
    Optional<DeliveryTemplateEntity> lockVersion(@Param("id") UUID id, @Param("tenantId") UUID tenantId);
    @Query("""
        select t from DeliveryTemplateEntity t where t.tenantId = :tenantId
        and (t.createdBy = :userId or (t.readScope = 'all' and t.status = 'published'))
        and (:published = false or t.status = 'published')
        and lower(t.name) like lower(concat('%', :keyword, '%'))
        """)
    Page<DeliveryTemplateEntity> search(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId,
        @Param("published") boolean published, @Param("keyword") String keyword, Pageable pageable);
}
