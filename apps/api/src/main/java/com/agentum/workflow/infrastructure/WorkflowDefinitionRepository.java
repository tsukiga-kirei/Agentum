package com.agentum.workflow.infrastructure;

import com.agentum.workflow.domain.WorkflowDefinitionEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinitionEntity, UUID> {

    Optional<WorkflowDefinitionEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    @Query("""
        select definition from WorkflowDefinitionEntity definition
        where definition.tenantId = :tenantId
          and (
            lower(definition.name) like lower(concat('%', :keyword, '%'))
            or lower(coalesce(definition.description, '')) like lower(concat('%', :keyword, '%'))
          )
        """)
    Page<WorkflowDefinitionEntity> searchDrafts(@Param("tenantId") UUID tenantId, @Param("keyword") String keyword, Pageable pageable);
}
