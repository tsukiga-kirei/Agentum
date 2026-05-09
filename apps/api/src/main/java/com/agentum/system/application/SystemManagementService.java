package com.agentum.system.application;

import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.agentum.system.domain.ModelProviderEntity;
import com.agentum.system.domain.SystemCapabilityEntity;
import com.agentum.system.domain.TenantCapabilityGrantEntity;
import com.agentum.system.infrastructure.ModelProviderRepository;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import com.agentum.system.infrastructure.TenantCapabilityGrantRepository;
import com.agentum.system.interfaces.SystemManagementApi;
import com.agentum.tenant.domain.TenantEntity;
import com.agentum.tenant.infrastructure.TenantRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SystemManagementService {

    private static final Logger log = LoggerFactory.getLogger(SystemManagementService.class);
    private static final String ACTIVE = "active";
    private static final String SUSPENDED = "suspended";

    private final TenantRepository tenantRepository;
    private final ModelProviderRepository modelProviderRepository;
    private final SystemCapabilityRepository systemCapabilityRepository;
    private final TenantCapabilityGrantRepository tenantCapabilityGrantRepository;
    private final Clock clock;

    public SystemManagementService(
        TenantRepository tenantRepository,
        ModelProviderRepository modelProviderRepository,
        SystemCapabilityRepository systemCapabilityRepository,
        TenantCapabilityGrantRepository tenantCapabilityGrantRepository,
        Clock clock
    ) {
        this.tenantRepository = tenantRepository;
        this.modelProviderRepository = modelProviderRepository;
        this.systemCapabilityRepository = systemCapabilityRepository;
        this.tenantCapabilityGrantRepository = tenantCapabilityGrantRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SystemManagementApi.Summary getSummary() {
        long tenantTotal = tenantRepository.count();
        long tenantActive = tenantRepository.countByStatus(ACTIVE);
        long modelTotal = modelProviderRepository.count();
        long capabilityTotal = systemCapabilityRepository.count();
        long grantTotal = tenantCapabilityGrantRepository.count();
        log.debug(
            "系统管理概览统计 tenantTotal={} tenantActive={} modelTotal={} capabilityTotal={} grantTotal={} requestId={}",
            tenantTotal,
            tenantActive,
            modelTotal,
            capabilityTotal,
            grantTotal,
            RequestIds.current()
        );
        return new SystemManagementApi.Summary(tenantTotal, tenantActive, modelTotal, capabilityTotal, grantTotal);
    }

    @Transactional(readOnly = true)
    public List<SystemManagementApi.TenantRow> listTenants() {
        return tenantRepository.findAllByOrderByNameAsc().stream()
            .map(tenant -> new SystemManagementApi.TenantRow(tenant.getId(), tenant.getName(), tenant.getCode(), tenant.getStatus()))
            .toList();
    }

    @Transactional
    public SystemManagementApi.TenantRow updateTenantStatus(UUID tenantId, SystemManagementApi.UpdateTenantStatusRequest request) {
        String status = request.status().trim();
        if (!ACTIVE.equals(status) && !SUSPENDED.equals(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SYSTEM_TENANT_STATUS_INVALID", "租户状态只能是 active 或 suspended");
        }

        TenantEntity tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> {
                log.warn("系统管理更新租户状态失败：租户不存在 tenantId={} requestId={}", tenantId, RequestIds.current());
                return new ApiException(HttpStatus.NOT_FOUND, "SYSTEM_TENANT_NOT_FOUND", "租户不存在");
            });

        tenant.applyPlatformStatus(status, clock.instant());
        tenantRepository.save(tenant);
        log.info("系统管理更新租户状态成功 tenantId={} status={} requestId={}", tenantId, status, RequestIds.current());
        return new SystemManagementApi.TenantRow(tenant.getId(), tenant.getName(), tenant.getCode(), tenant.getStatus());
    }

    @Transactional(readOnly = true)
    public List<SystemManagementApi.ModelProviderRow> listModelProviders() {
        return modelProviderRepository.findAllByOrderByNameAsc().stream()
            .map(SystemManagementService::toModelRow)
            .toList();
    }

    @Transactional
    public SystemManagementApi.ModelProviderRow createModelProvider(SystemManagementApi.CreateModelProviderRequest request) {
        ModelProviderEntity entity = ModelProviderEntity.create(
            request.name().trim(),
            request.providerType().trim(),
            request.baseUrl() == null ? null : request.baseUrl().trim(),
            request.defaultModel() == null ? null : request.defaultModel().trim(),
            request.status() == null ? null : request.status().trim(),
            clock.instant()
        );
        modelProviderRepository.save(entity);
        log.info(
            "系统管理注册模型供应商成功 providerId={} name={} type={} requestId={}",
            entity.getId(),
            entity.getName(),
            entity.getProviderType(),
            RequestIds.current()
        );
        return toModelRow(entity);
    }

    @Transactional(readOnly = true)
    public List<SystemManagementApi.CapabilityRow> listCapabilities() {
        return systemCapabilityRepository.findAllByOrderByNameAsc().stream()
            .map(SystemManagementService::toCapabilityRow)
            .toList();
    }

    @Transactional
    public SystemManagementApi.CapabilityRow createCapability(SystemManagementApi.CreateCapabilityRequest request) {
        String version = request.version() == null ? "v1" : request.version().trim();
        if (systemCapabilityRepository.findByCodeAndVersion(request.code().trim(), version).isPresent()) {
            log.warn(
                "系统管理注册能力失败：编码与版本已存在 code={} version={} requestId={}",
                request.code(),
                version,
                RequestIds.current()
            );
            throw new ApiException(HttpStatus.CONFLICT, "SYSTEM_CAPABILITY_DUPLICATE", "同一能力编码与版本已存在");
        }

        SystemCapabilityEntity entity = SystemCapabilityEntity.create(
            request.capabilityType().trim(),
            request.name().trim(),
            request.code().trim(),
            version,
            request.riskLevel() == null ? null : request.riskLevel().trim(),
            request.status() == null ? null : request.status().trim(),
            clock.instant()
        );
        systemCapabilityRepository.save(entity);
        log.info(
            "系统管理注册全局能力成功 capabilityId={} code={} version={} requestId={}",
            entity.getId(),
            entity.getCode(),
            entity.getVersion(),
            RequestIds.current()
        );
        return toCapabilityRow(entity);
    }

    @Transactional(readOnly = true)
    public List<SystemManagementApi.GrantRow> listGrants(UUID tenantIdFilter) {
        List<TenantCapabilityGrantEntity> grants = tenantIdFilter == null
            ? tenantCapabilityGrantRepository.findAllByOrderByCreatedAtDesc()
            : tenantCapabilityGrantRepository.findByTenantIdOrderByCreatedAtDesc(tenantIdFilter);

        return grants.stream().map(this::toGrantRow).toList();
    }

    @Transactional
    public SystemManagementApi.GrantRow createGrant(SystemManagementApi.CreateGrantRequest request) {
        TenantEntity tenant = tenantRepository.findById(request.tenantId())
            .orElseThrow(() -> {
                log.warn("系统管理授权失败：租户不存在 tenantId={} requestId={}", request.tenantId(), RequestIds.current());
                return new ApiException(HttpStatus.BAD_REQUEST, "SYSTEM_TENANT_NOT_FOUND", "租户不存在");
            });

        SystemCapabilityEntity capability = systemCapabilityRepository.findById(request.capabilityId())
            .orElseThrow(() -> {
                log.warn("系统管理授权失败：能力不存在 capabilityId={} requestId={}", request.capabilityId(), RequestIds.current());
                return new ApiException(HttpStatus.BAD_REQUEST, "SYSTEM_CAPABILITY_NOT_FOUND", "系统能力不存在");
            });

        if (tenantCapabilityGrantRepository.findByTenantIdAndCapabilityId(tenant.getId(), capability.getId()).isPresent()) {
            log.warn(
                "系统管理授权失败：重复授权 tenantId={} capabilityId={} requestId={}",
                tenant.getId(),
                capability.getId(),
                RequestIds.current()
            );
            throw new ApiException(HttpStatus.CONFLICT, "SYSTEM_GRANT_DUPLICATE", "该租户已拥有此能力的授权记录");
        }

        TenantCapabilityGrantEntity entity = TenantCapabilityGrantEntity.create(
            tenant.getId(),
            capability.getId(),
            request.status() == null ? null : request.status().trim(),
            clock.instant()
        );
        tenantCapabilityGrantRepository.save(entity);
        log.info(
            "系统管理写入租户能力授权成功 grantId={} tenantId={} capabilityId={} requestId={}",
            entity.getId(),
            tenant.getId(),
            capability.getId(),
            RequestIds.current()
        );
        return toGrantRow(entity);
    }

    private SystemManagementApi.GrantRow toGrantRow(TenantCapabilityGrantEntity entity) {
        TenantEntity tenant = tenantRepository.findById(entity.getTenantId())
            .orElseThrow(() -> new IllegalStateException("授权记录指向的租户缺失: " + entity.getTenantId()));
        SystemCapabilityEntity capability = systemCapabilityRepository.findById(entity.getCapabilityId())
            .orElseThrow(() -> new IllegalStateException("授权记录指向的能力缺失: " + entity.getCapabilityId()));

        return new SystemManagementApi.GrantRow(
            entity.getId(),
            tenant.getId(),
            tenant.getName(),
            tenant.getCode(),
            capability.getId(),
            capability.getName(),
            capability.getCode(),
            capability.getCapabilityType(),
            entity.getStatus()
        );
    }

    private static SystemManagementApi.ModelProviderRow toModelRow(ModelProviderEntity entity) {
        return new SystemManagementApi.ModelProviderRow(
            entity.getId(),
            entity.getName(),
            entity.getProviderType(),
            entity.getBaseUrl(),
            entity.getDefaultModel(),
            entity.getStatus()
        );
    }

    private static SystemManagementApi.CapabilityRow toCapabilityRow(SystemCapabilityEntity entity) {
        return new SystemManagementApi.CapabilityRow(
            entity.getId(),
            entity.getCapabilityType(),
            entity.getName(),
            entity.getCode(),
            entity.getVersion(),
            entity.getRiskLevel(),
            entity.getStatus()
        );
    }
}
