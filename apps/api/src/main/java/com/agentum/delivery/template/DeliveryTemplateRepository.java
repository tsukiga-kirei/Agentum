package com.agentum.delivery.template;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface DeliveryTemplateRepository extends JpaRepository<DeliveryTemplateEntity, UUID> {
    Optional<DeliveryTemplateEntity> findByIdAndTenantId(UUID id, UUID tenantId);
    Optional<DeliveryTemplateEntity> findFirstByTemplateIdOrderByVersionDesc(UUID templateId);
    java.util.List<DeliveryTemplateEntity> findByTemplateIdAndTenantId(UUID templateId, UUID tenantId);
    Optional<DeliveryTemplateEntity> findFirstByTemplateIdAndStatusAndDeletedAtIsNullOrderByVersionDesc(UUID templateId, String status);
    @Query("""
        select t from DeliveryTemplateEntity t where t.templateId = :templateId and t.tenantId = :tenantId
        and (t.createdBy = :userId or (t.readScope = 'all' and t.status = 'published'))
        """)
    Page<DeliveryTemplateEntity> history(@Param("templateId") UUID templateId, @Param("tenantId") UUID tenantId,
        @Param("userId") UUID userId, Pageable pageable);
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from DeliveryTemplateEntity t where t.id = :id and t.tenantId = :tenantId")
    Optional<DeliveryTemplateEntity> lockVersion(@Param("id") UUID id, @Param("tenantId") UUID tenantId);
    @Query("""
        select t from DeliveryTemplateEntity t where t.tenantId = :tenantId and t.deletedAt is null
        and (t.createdBy = :userId or (t.readScope = 'all' and t.status = 'published'))
        and (:published = false or t.status = 'published')
        and not exists (select n.id from DeliveryTemplateEntity n where n.templateId = t.templateId
          and n.tenantId = :tenantId and n.deletedAt is null and n.version > t.version
          and (:published = false or n.status = 'published')
          and (n.createdBy = :userId or (n.readScope = 'all' and n.status = 'published')))
        and lower(t.name) like lower(concat('%', :keyword, '%'))
        """)
    Page<DeliveryTemplateEntity> search(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId,
        @Param("published") boolean published, @Param("keyword") String keyword, Pageable pageable);
}
