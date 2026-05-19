package com.agentum.asset.application;

import com.agentum.asset.domain.TenantAssetCapabilityEntity;
import com.agentum.asset.infrastructure.TenantAssetCapabilityRepository;
import com.agentum.asset.interfaces.AssetManagementApi;
import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.organization.domain.UserMembershipEntity;
import com.agentum.organization.domain.UserMembershipRoleEntity;
import com.agentum.organization.infrastructure.UserMembershipRepository;
import com.agentum.organization.infrastructure.UserMembershipRoleRepository;
import com.agentum.permission.domain.ResourceGrantEntity;
import com.agentum.permission.infrastructure.ResourceGrantRepository;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.agentum.shared.pagination.PageQuery;
import com.agentum.shared.pagination.PageResponse;
import com.agentum.shared.pagination.PageableFactory;
import com.agentum.shared.pagination.SortWhitelist;
import com.agentum.system.domain.SystemCapabilityEntity;
import com.agentum.system.domain.TenantCapabilityGrantEntity;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import com.agentum.system.infrastructure.TenantCapabilityGrantRepository;
import com.agentum.tenant.infrastructure.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssetManagementService {

    private static final Logger log = LoggerFactory.getLogger(AssetManagementService.class);
    private static final String ACTIVE_STATUS = "active";
    private static final Set<String> ALLOWED_ASSET_TYPES = Set.of("agent_template", "skill", "mcp", "prompt_template", "delivery");
    private static final Set<String> ALLOWED_RISK_LEVELS = Set.of("low", "medium", "high");
    private static final Set<String> ALLOWED_VISIBILITY = Set.of("private", "tenant");
    private static final SortWhitelist SYSTEM_CAPABILITY_SORT = SortWhitelist.of("openedAt", "name", "assetType", "riskLevel", "openedAt");
    private static final SortWhitelist MY_ASSET_SORT = SortWhitelist.of("updatedAt", "name", "assetType", "status", "createdAt", "updatedAt");

    private final TenantRepository tenantRepository;
    private final TenantCapabilityGrantRepository tenantCapabilityGrantRepository;
    private final SystemCapabilityRepository systemCapabilityRepository;
    private final ResourceGrantRepository resourceGrantRepository;
    private final UserMembershipRepository userMembershipRepository;
    private final UserMembershipRoleRepository userMembershipRoleRepository;
    private final TenantAssetCapabilityRepository tenantAssetCapabilityRepository;
    private final Clock clock;

    public AssetManagementService(
        TenantRepository tenantRepository,
        TenantCapabilityGrantRepository tenantCapabilityGrantRepository,
        SystemCapabilityRepository systemCapabilityRepository,
        ResourceGrantRepository resourceGrantRepository,
        UserMembershipRepository userMembershipRepository,
        UserMembershipRoleRepository userMembershipRoleRepository,
        TenantAssetCapabilityRepository tenantAssetCapabilityRepository,
        Clock clock
    ) {
        this.tenantRepository = tenantRepository;
        this.tenantCapabilityGrantRepository = tenantCapabilityGrantRepository;
        this.systemCapabilityRepository = systemCapabilityRepository;
        this.resourceGrantRepository = resourceGrantRepository;
        this.userMembershipRepository = userMembershipRepository;
        this.userMembershipRoleRepository = userMembershipRoleRepository;
        this.tenantAssetCapabilityRepository = tenantAssetCapabilityRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AssetManagementApi.AssetSummary getSummary(UUID tenantId, CurrentUserPrincipal principal) {
        ensureActiveTenant(tenantId);
        List<SystemCapabilityAsset> tenantOpenCapabilities = loadTenantOpenCapabilities(tenantId);
        List<SystemCapabilityAsset> visibleCapabilities = filterVisibleCapabilities(tenantId, principal, tenantOpenCapabilities);

        return new AssetManagementApi.AssetSummary(
            visibleCapabilities.size(),
            tenantOpenCapabilities.size(),
            tenantAssetCapabilityRepository.countByTenantIdAndCreatedBy(tenantId, principal.userId())
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<AssetManagementApi.SystemCapabilityAssetRow> listTenantSystemCapabilities(
        UUID tenantId,
        CurrentUserPrincipal principal,
        int page,
        int size,
        String sort
    ) {
        ensureActiveTenant(tenantId);
        PageQuery query = PageQuery.of(page, size, sort);
        Pageable pageable = PageableFactory.from(query, SYSTEM_CAPABILITY_SORT);
        boolean manager = isTenantManager(principal);
        List<SystemCapabilityAsset> assets = filterVisibleCapabilities(tenantId, principal, loadTenantOpenCapabilities(tenantId));
        List<AssetManagementApi.SystemCapabilityAssetRow> rows = assets.stream()
            .map(asset -> toSystemCapabilityRow(asset, true, manager))
            .toList();

        return PageResponse.from(slice(rows, pageable));
    }

    @Transactional(readOnly = true)
    public PageResponse<AssetManagementApi.MyAssetRow> listMyAssets(
        UUID tenantId,
        CurrentUserPrincipal principal,
        String keyword,
        int page,
        int size,
        String sort
    ) {
        ensureActiveTenant(tenantId);
        Pageable pageable = PageableFactory.from(PageQuery.of(page, size, sort), MY_ASSET_SORT);
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        return PageResponse.from(tenantAssetCapabilityRepository.searchMine(tenantId, principal.userId(), normalizedKeyword, pageable).map(this::toMyAssetRow));
    }

    @Transactional
    public AssetManagementApi.MyAssetRow createMyAsset(UUID tenantId, CurrentUserPrincipal principal, AssetManagementApi.CreateMyAssetRequest request) {
        ensureActiveTenant(tenantId);
        String assetType = normalizeAssetType(request.assetType());
        String name = normalizeRequired(request.name(), "ASSET_NAME_REQUIRED", "请输入能力名称");
        String code = normalizeCode(request.code());
        String version = normalizeVersion(request.version());
        String riskLevel = normalizeRiskLevel(request.riskLevel());
        String visibility = normalizeVisibility(request.visibility());
        UUID baseSystemCapabilityId = normalizeBaseSystemCapabilityId(tenantId, principal, assetType, request.baseSystemCapabilityId());
        Instant now = clock.instant();

        if (tenantAssetCapabilityRepository.existsByTenantIdAndCodeAndVersion(tenantId, code, version)) {
            throw new ApiException(HttpStatus.CONFLICT, "ASSET_CODE_VERSION_EXISTS", "同一租户下已存在相同编码和版本的能力资产");
        }

        TenantAssetCapabilityEntity entity = TenantAssetCapabilityEntity.create(
            tenantId,
            assetType,
            name,
            code,
            version,
            normalizeOptional(request.description()),
            riskLevel,
            "draft",
            visibility,
            baseSystemCapabilityId,
            request.config(),
            principal.userId(),
            now
        );

        try {
            TenantAssetCapabilityEntity saved = tenantAssetCapabilityRepository.save(entity);
            log.info(
                "租户自建能力资产已创建 tenantId={} userId={} assetId={} assetType={} requestId={}",
                tenantId,
                principal.userId(),
                saved.getId(),
                assetType,
                RequestIds.current()
            );
            return toMyAssetRow(saved);
        } catch (DataIntegrityViolationException exception) {
            log.warn(
                "租户自建能力资产创建失败：编码版本冲突 tenantId={} userId={} assetType={} code={} version={} requestId={}",
                tenantId,
                principal.userId(),
                assetType,
                code,
                version,
                RequestIds.current()
            );
            throw new ApiException(HttpStatus.CONFLICT, "ASSET_CODE_VERSION_EXISTS", "同一租户下已存在相同编码和版本的能力资产");
        }
    }

    private AssetManagementApi.SystemCapabilityAssetRow toSystemCapabilityRow(SystemCapabilityAsset asset, boolean assignedToMe, boolean manager) {
        SystemCapabilityEntity capability = asset.capability();
        return new AssetManagementApi.SystemCapabilityAssetRow(
            capability.getId(),
            capability.getCapabilityType(),
            capability.getName(),
            capability.getCode(),
            capability.getVersion(),
            capability.getRiskLevel(),
            capability.getStatus(),
            assignedToMe,
            assignedToMe ? (manager ? "管理入口可用" : "租户管理已分配") : "待租户管理分配",
            asset.openedAt()
        );
    }

    private AssetManagementApi.MyAssetRow toMyAssetRow(TenantAssetCapabilityEntity asset) {
        return new AssetManagementApi.MyAssetRow(
            asset.getId(),
            asset.getAssetType(),
            asset.getName(),
            asset.getCode(),
            asset.getVersion(),
            asset.getDescription(),
            asset.getRiskLevel(),
            asset.getStatus(),
            asset.getVisibility(),
            asset.getSourceType(),
            asset.getBaseSystemCapabilityId(),
            asset.getCreatedAt(),
            asset.getUpdatedAt()
        );
    }

    private List<SystemCapabilityAsset> loadTenantOpenCapabilities(UUID tenantId) {
        List<TenantCapabilityGrantEntity> enabledGrants = tenantCapabilityGrantRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)
            .stream()
            .filter(grant -> "enabled".equals(grant.getStatus()))
            .toList();
        if (enabledGrants.isEmpty()) {
            return List.of();
        }

        Map<UUID, TenantCapabilityGrantEntity> grantsByCapabilityId = enabledGrants.stream()
            .collect(Collectors.toMap(TenantCapabilityGrantEntity::getCapabilityId, Function.identity(), (left, right) -> left));
        return systemCapabilityRepository.findAllById(grantsByCapabilityId.keySet())
            .stream()
            .filter(capability -> ACTIVE_STATUS.equals(capability.getStatus()))
            .filter(capability -> ALLOWED_ASSET_TYPES.contains(capability.getCapabilityType()))
            .sorted(Comparator.comparing(SystemCapabilityEntity::getCapabilityType).thenComparing(SystemCapabilityEntity::getName))
            .map(capability -> new SystemCapabilityAsset(capability, grantsByCapabilityId.get(capability.getId()).getCreatedAt()))
            .toList();
    }

    private List<SystemCapabilityAsset> filterVisibleCapabilities(UUID tenantId, CurrentUserPrincipal principal, List<SystemCapabilityAsset> tenantOpenCapabilities) {
        if (isTenantManager(principal)) {
            return tenantOpenCapabilities;
        }

        Set<UUID> assignedCapabilityIds = resolveAssignedCapabilityIds(tenantId, principal);
        if (assignedCapabilityIds.isEmpty()) {
            return List.of();
        }

        // 业务侧“对我开放”只展示租户管理已分配给当前用户、部门或角色的能力；
        // 系统管理放入租户池但尚未分配的能力不应泄露到普通业务视图。
        return tenantOpenCapabilities.stream()
            .filter(asset -> assignedCapabilityIds.contains(asset.capability().getId()))
            .toList();
    }

    private Set<UUID> resolveAssignedCapabilityIds(UUID tenantId, CurrentUserPrincipal principal) {
        if (principal == null || isTenantManager(principal)) {
            return Set.of();
        }

        List<UserMembershipEntity> memberships = userMembershipRepository.findByUserIdAndTenantIdAndStatus(principal.userId(), tenantId, ACTIVE_STATUS);
        Set<String> principalKeys = new LinkedHashSet<>();
        principalKeys.add("user:" + principal.userId());
        memberships.stream()
            .map(UserMembershipEntity::getDepartmentId)
            .filter(departmentId -> departmentId != null)
            .map(departmentId -> "department:" + departmentId)
            .forEach(principalKeys::add);

        Set<UUID> membershipIds = memberships.stream().map(UserMembershipEntity::getId).collect(Collectors.toSet());
        if (!membershipIds.isEmpty()) {
            userMembershipRoleRepository.findByMembershipIdInAndStatus(membershipIds, ACTIVE_STATUS)
                .stream()
                .map(UserMembershipRoleEntity::getRoleId)
                .map(roleId -> "role:" + roleId)
                .forEach(principalKeys::add);
        }

        if (principalKeys.isEmpty()) {
            return Set.of();
        }

        // 能力池分配当前以 resource_grants 明细承接，运行时可复用同一批主体和资源判断。
        return resourceGrantRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)
            .stream()
            .filter(grant -> principalKeys.contains(grant.getPrincipalType() + ":" + grant.getPrincipalId()))
            .map(ResourceGrantEntity::getResourceId)
            .collect(Collectors.toSet());
    }

    private UUID normalizeBaseSystemCapabilityId(UUID tenantId, CurrentUserPrincipal principal, String assetType, UUID baseSystemCapabilityId) {
        if (baseSystemCapabilityId == null) {
            return null;
        }
        // 自建能力从系统能力派生时，也必须沿用“对我开放”的权限边界，避免业务用户绕过租户分配直接引用租户池能力。
        SystemCapabilityEntity capability = filterVisibleCapabilities(tenantId, principal, loadTenantOpenCapabilities(tenantId))
            .stream()
            .map(SystemCapabilityAsset::capability)
            .filter(candidate -> candidate.getId().equals(baseSystemCapabilityId))
            .findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "ASSET_BASE_CAPABILITY_NOT_AVAILABLE", "只能基于租户管理已分配给当前主体的系统能力创建派生资产"));
        if (!assetType.equals(capability.getCapabilityType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ASSET_BASE_CAPABILITY_TYPE_MISMATCH", "派生资产类型必须与系统能力类型一致");
        }
        return baseSystemCapabilityId;
    }

    private void ensureActiveTenant(UUID tenantId) {
        tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "租户不存在或已停用"));
    }

    private boolean isTenantManager(CurrentUserPrincipal principal) {
        return principal != null && ("tenant_admin".equals(principal.role()) || "system_admin".equals(principal.role()));
    }

    private String normalizeAssetType(String assetType) {
        String normalized = normalizeRequired(assetType, "ASSET_TYPE_REQUIRED", "请选择能力类型");
        if (!ALLOWED_ASSET_TYPES.contains(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ASSET_TYPE_INVALID", "能力类型不受支持");
        }
        return normalized;
    }

    private String normalizeRiskLevel(String riskLevel) {
        String normalized = normalizeOptional(riskLevel);
        if (normalized.isBlank()) {
            return "low";
        }
        if (!ALLOWED_RISK_LEVELS.contains(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ASSET_RISK_LEVEL_INVALID", "风险等级不受支持");
        }
        return normalized;
    }

    private String normalizeVisibility(String visibility) {
        String normalized = normalizeOptional(visibility);
        if (normalized.isBlank()) {
            return "private";
        }
        if (!ALLOWED_VISIBILITY.contains(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ASSET_VISIBILITY_INVALID", "可见范围不受支持");
        }
        return normalized;
    }

    private String normalizeCode(String code) {
        String normalized = normalizeRequired(code, "ASSET_CODE_REQUIRED", "请输入能力编码");
        if (!normalized.matches("[a-z][a-z0-9_\\-]{1,99}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ASSET_CODE_INVALID", "能力编码需以小写字母开头，仅包含小写字母、数字、下划线或短横线");
        }
        return normalized;
    }

    private String normalizeVersion(String version) {
        String normalized = normalizeOptional(version);
        return normalized.isBlank() ? "v1" : normalized;
    }

    private String normalizeRequired(String value, String code, String message) {
        String normalized = normalizeOptional(value);
        if (normalized.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, code, message);
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        return value == null ? "" : value.trim();
    }

    private static <T> Page<T> slice(List<T> rows, Pageable pageable) {
        int start = Math.min((int) pageable.getOffset(), rows.size());
        int end = Math.min(start + pageable.getPageSize(), rows.size());
        return new PageImpl<>(new ArrayList<>(rows.subList(start, end)), pageable, rows.size());
    }

    private record SystemCapabilityAsset(SystemCapabilityEntity capability, Instant openedAt) {
    }
}
