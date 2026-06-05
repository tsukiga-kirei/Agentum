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
        select count(definition) from WorkflowDefinitionEntity definition
        where definition.tenantId = :tenantId
          and definition.launchEnabled = true
          and exists (
            select version.id from WorkflowVersionEntity version
            where version.workflowId = definition.id
          )
        """)
    long countLaunchableByTenantId(@Param("tenantId") UUID tenantId);

    @Query("""
        select count(definition) from WorkflowDefinitionEntity definition
        where definition.tenantId = :tenantId
          and (
            definition.createdBy = :operatorUserId
            or definition.readScope = 'all'
            or definition.editScope = 'all'
            or exists (
              select grant.id from WorkflowAccessGrantEntity grant
              where grant.workflowId = definition.id and grant.granteeUserId = :operatorUserId
            )
          )
          and definition.launchEnabled = true
          and exists (
            select version.id from WorkflowVersionEntity version
            where version.workflowId = definition.id
          )
        """)
    long countVisibleLaunchableByTenantId(
        @Param("tenantId") UUID tenantId,
        @Param("operatorUserId") UUID operatorUserId
    );

    @Query("""
        select definition from WorkflowDefinitionEntity definition
        where definition.tenantId = :tenantId
          and (
            lower(definition.name) like lower(concat('%', :keyword, '%'))
            or lower(coalesce(definition.description, '')) like lower(concat('%', :keyword, '%'))
          )
          and (
            definition.createdBy = :operatorUserId
            or definition.readScope = 'all'
            or definition.editScope = 'all'
            or exists (
              select grant.id from WorkflowAccessGrantEntity grant
              where grant.workflowId = definition.id and grant.granteeUserId = :operatorUserId
            )
          )
          and (:onlyMine = false or definition.createdBy = :operatorUserId)
          and (:onlyShared = false or definition.createdBy is null or definition.createdBy <> :operatorUserId)
          and (:status is null or definition.status = :status)
        """)
        Page<WorkflowDefinitionEntity> searchDrafts(
        @Param("tenantId") UUID tenantId,
        @Param("keyword") String keyword,
        @Param("operatorUserId") UUID operatorUserId,
        @Param("onlyMine") boolean onlyMine,
        @Param("onlyShared") boolean onlyShared,
        @Param("status") String status,
        Pageable pageable
    );

    @Query("""
        select definition from WorkflowDefinitionEntity definition
        where definition.tenantId = :tenantId
          and (
            :keyword = ''
            or lower(definition.name) like lower(concat('%', :keyword, '%'))
            or lower(coalesce(definition.description, '')) like lower(concat('%', :keyword, '%'))
          )
          and (
            definition.createdBy = :operatorUserId
            or definition.readScope = 'all'
            or definition.editScope = 'all'
            or exists (
              select grant.id from WorkflowAccessGrantEntity grant
              where grant.workflowId = definition.id and grant.granteeUserId = :operatorUserId
            )
          )
          and definition.launchEnabled = true
          and exists (
            select version.id from WorkflowVersionEntity version
            where version.workflowId = definition.id
          )
        """)
    Page<WorkflowDefinitionEntity> searchLaunchableWorkflows(
        @Param("tenantId") UUID tenantId,
        @Param("keyword") String keyword,
        @Param("operatorUserId") UUID operatorUserId,
        Pageable pageable
    );
}
