package com.agentum.asset.application;

import com.agentum.asset.domain.TenantAssetAccessGrantEntity;
import com.agentum.asset.domain.TenantAssetCapabilityEntity;
import com.agentum.asset.infrastructure.TenantAssetAccessGrantRepository;
import com.agentum.asset.infrastructure.TenantAssetCapabilityRepository;
import com.agentum.asset.interfaces.AssetManagementApi;
import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.auth.domain.UserAccount;
import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.organization.domain.UserMembershipEntity;
import com.agentum.organization.domain.UserMembershipRoleEntity;
import com.agentum.organization.infrastructure.UserMembershipRepository;
import com.agentum.organization.infrastructure.UserMembershipRoleRepository;
import com.agentum.permission.domain.ResourceGrantEntity;
import com.agentum.permission.infrastructure.ResourceGrantRepository;
import com.agentum.permission.application.CollaborationAccessPolicy;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.agentum.shared.util.CapabilityCodeGenerator;
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
import java.util.HashMap;
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
    private static final Set<String> USER_CREATABLE_ASSET_TYPES = Set.of("agent_template", "prompt_template");
    private static final Set<String> SYSTEM_CAPABILITY_TYPES = Set.of("skill", "mcp", "prompt_template", "delivery");
    private static final Set<String> ALLOWED_RISK_LEVELS = Set.of("low", "medium", "high");
    private static final SortWhitelist SYSTEM_CAPABILITY_SORT = SortWhitelist.of("openedAt", "name", "assetType", "riskLevel", "openedAt");
    private static final SortWhitelist MY_ASSET_SORT = SortWhitelist.of("updatedAt", "name", "assetType", "status", "createdAt", "updatedAt");

    private final TenantRepository tenantRepository;
    private final TenantCapabilityGrantRepository tenantCapabilityGrantRepository;
    private final SystemCapabilityRepository systemCapabilityRepository;
    private final ResourceGrantRepository resourceGrantRepository;
    private final UserMembershipRepository userMembershipRepository;
    private final UserMembershipRoleRepository userMembershipRoleRepository;
    private final TenantAssetCapabilityRepository tenantAssetCapabilityRepository;
    private final TenantAssetAccessGrantRepository tenantAssetAccessGrantRepository;
    private final UserAccountRepository userAccountRepository;
    private final CollaborationAccessPolicy collaborationAccessPolicy;
    private final Clock clock;

    public AssetManagementService(
        TenantRepository tenantRepository,
        TenantCapabilityGrantRepository tenantCapabilityGrantRepository,
        SystemCapabilityRepository systemCapabilityRepository,
        ResourceGrantRepository resourceGrantRepository,
        UserMembershipRepository userMembershipRepository,
        UserMembershipRoleRepository userMembershipRoleRepository,
        TenantAssetCapabilityRepository tenantAssetCapabilityRepository,
        TenantAssetAccessGrantRepository tenantAssetAccessGrantRepository,
        UserAccountRepository userAccountRepository,
        CollaborationAccessPolicy collaborationAccessPolicy,
        Clock clock
    ) {
        this.tenantRepository = tenantRepository;
        this.tenantCapabilityGrantRepository = tenantCapabilityGrantRepository;
        this.systemCapabilityRepository = systemCapabilityRepository;
        this.resourceGrantRepository = resourceGrantRepository;
        this.userMembershipRepository = userMembershipRepository;
        this.userMembershipRoleRepository = userMembershipRoleRepository;
        this.tenantAssetCapabilityRepository = tenantAssetCapabilityRepository;
        this.tenantAssetAccessGrantRepository = tenantAssetAccessGrantRepository;
        this.userAccountRepository = userAccountRepository;
        this.collaborationAccessPolicy = collaborationAccessPolicy;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AssetManagementApi.AssetSummary getSummary(UUID tenantId, CurrentUserPrincipal principal) {
        ensureActiveTenant(tenantId);
        List<SystemCapabilityAsset> tenantOpenCapabilities = loadTenantOpenCapabilities(tenantId);
        List<SystemCapabilityAsset> visibleCapabilities = filterVisibleCapabilities(tenantId, principal, tenantOpenCapabilities);
        List<AccessibleTenantAsset> sharedAssets = loadAccessibleTenantAssetsForUser(tenantId, principal.userId());

        return new AssetManagementApi.AssetSummary(
            visibleCapabilities.size() + sharedAssets.size(),
            tenantOpenCapabilities.size(),
            tenantAssetCapabilityRepository.countByTenantIdAndCreatedBy(tenantId, principal.userId())
        );
    }

    @Transactional(readOnly = true)
    public List<AssetManagementApi.ShareableMemberRow> listShareableMembers(UUID tenantId, CurrentUserPrincipal principal) {
        ensureActiveTenant(tenantId);
        return userMembershipRepository.findByTenantIdAndStatus(tenantId, ACTIVE_STATUS)
            .stream()
            .map(UserMembershipEntity::getUserId)
            .filter(userId -> principal != null && !userId.equals(principal.userId()))
            .distinct()
            .map(userId -> userAccountRepository.findById(userId).orElse(null))
            .filter(user -> user != null && ACTIVE_STATUS.equals(user.getStatus()))
            .sorted(Comparator.comparing(UserAccount::getDisplayName))
            .map(user -> new AssetManagementApi.ShareableMemberRow(user.getId(), user.getUsername(), user.getDisplayName()))
            .toList();
    }

    @Transactional(readOnly = true)
    public boolean canUseTenantAssetReference(UUID tenantId, UUID operatorUserId, UUID assetId, String expectedType) {
        TenantAssetCapabilityEntity asset = tenantAssetCapabilityRepository.findByIdAndTenantId(assetId, tenantId).orElse(null);
        return asset != null
            && expectedType.equals(asset.getAssetType())
            && "published".equals(asset.getStatus())
            && resolveAssetAccess(asset, operatorUserId).canRead();
    }

    @Transactional(readOnly = true)
    public boolean canUseSystemCapabilityReference(UUID tenantId, UUID operatorUserId, UUID capabilityId, String expectedType) {
        if (!resolveAssignedCapabilityIds(tenantId, operatorUserId).contains(capabilityId)) {
            return false;
        }
        return loadTenantOpenCapabilities(tenantId).stream()
            .map(SystemCapabilityAsset::capability)
            .anyMatch(capability -> capability.getId().equals(capabilityId) && expectedType.equals(capability.getCapabilityType()));
    }

    @Transactional(readOnly = true)
    public PageResponse<AssetManagementApi.SystemCapabilityAssetRow> listTenantSystemCapabilities(
        UUID tenantId,
        CurrentUserPrincipal principal,
        int page,
        int size,
        String sort,
        String assetType,
        String keyword
    ) {
        ensureActiveTenant(tenantId);
        PageQuery query = PageQuery.of(page, size, sort);
        Pageable pageable = PageableFactory.from(query, SYSTEM_CAPABILITY_SORT);
        boolean manager = isTenantManager(principal);
        List<SystemCapabilityAsset> assets = filterVisibleCapabilities(tenantId, principal, loadTenantOpenCapabilities(tenantId));
        List<AccessibleTenantAsset> sharedAssets = loadAccessibleTenantAssetsForUser(tenantId, principal.userId());
        Map<UUID, String> ownerNames = loadUserDisplayNames(sharedAssets.stream().map(AccessibleTenantAsset::ownerUserId).collect(Collectors.toSet()));
        // 服务端按能力类型和关键字过滤，避免客户端在已分页结果上再过滤导致显示不一致。
        String normalizedAssetType = assetType == null || assetType.isBlank() || "all".equals(assetType) ? null : assetType.trim();
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase();
        List<AssetManagementApi.SystemCapabilityAssetRow> systemRows = assets.stream()
            .filter(asset -> normalizedAssetType == null || normalizedAssetType.equals(asset.capability().getCapabilityType()))
            .filter(asset -> normalizedKeyword.isEmpty() || asset.capability().getName().toLowerCase().contains(normalizedKeyword)
                || asset.capability().getCode().toLowerCase().contains(normalizedKeyword)
                || asset.capability().getVersion().toLowerCase().contains(normalizedKeyword))
            .map(asset -> toSystemCapabilityRow(asset, true, manager))
            .toList();
        List<AssetManagementApi.SystemCapabilityAssetRow> sharedRows = sharedAssets.stream()
            .filter(item -> normalizedAssetType == null || normalizedAssetType.equals(item.asset().getAssetType()))
            .filter(item -> normalizedKeyword.isEmpty() || item.asset().getName().toLowerCase().contains(normalizedKeyword)
                || item.asset().getCode().toLowerCase().contains(normalizedKeyword)
                || item.asset().getVersion().toLowerCase().contains(normalizedKeyword))
            .map(item -> toAccessibleTenantAssetRow(item, ownerNames.getOrDefault(item.ownerUserId(), "")))
            .toList();
        List<AssetManagementApi.SystemCapabilityAssetRow> rows = new ArrayList<>(systemRows.size() + sharedRows.size());
        rows.addAll(systemRows);
        rows.addAll(sharedRows);
        rows.sort(Comparator.comparing(AssetManagementApi.SystemCapabilityAssetRow::openedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(AssetManagementApi.SystemCapabilityAssetRow::name));

        return PageResponse.from(slice(rows, pageable));
    }

    @Transactional(readOnly = true)
    public PageResponse<AssetManagementApi.MyAssetRow> listMyAssets(
        UUID tenantId,
        CurrentUserPrincipal principal,
        String keyword,
        int page,
        int size,
        String sort,
        String assetType,
        String status
    ) {
        ensureActiveTenant(tenantId);
        Pageable pageable = PageableFactory.from(PageQuery.of(page, size, sort), MY_ASSET_SORT);
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        // 能力类型和状态过滤在服务端执行，避免前端在已分页结果上再过滤导致页面记录数和分页总数不一致。
        String normalizedAssetType = assetType == null || assetType.isBlank() || "all".equals(assetType) ? null : assetType.trim();
        String normalizedStatus = status == null || status.isBlank() || "all".equals(status) ? null : status.trim();
        return PageResponse.from(tenantAssetCapabilityRepository.searchMine(tenantId, principal.userId(), normalizedKeyword, normalizedAssetType, normalizedStatus, pageable).map(this::toMyAssetRow));
    }

    @Transactional
    public AssetManagementApi.MyAssetRow createMyAsset(UUID tenantId, CurrentUserPrincipal principal, AssetManagementApi.CreateMyAssetRequest request) {
        ensureActiveTenant(tenantId);
        String assetType = normalizeAssetType(request.assetType());
        String name = normalizeRequired(request.name(), "ASSET_NAME_REQUIRED", "请输入能力名称");
        String version = normalizeVersion(request.version());
        String code = CapabilityCodeGenerator.resolveUniqueCode(
            name,
            version,
            (candidate, candidateVersion) -> tenantAssetCapabilityRepository.existsByTenantIdAndCodeAndVersion(tenantId, candidate, candidateVersion)
        );
        String riskLevel = normalizeRiskLevel(request.riskLevel());
        String readScope = normalizeScope(request.readScope());
        String editScope = normalizeScope(request.editScope());
        if (request.baseSystemCapabilityId() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ASSET_BASE_CAPABILITY_NOT_SUPPORTED", "当前只能新建提示词模板草稿或智能体模板草稿，不能派生底层系统能力");
        }
        Map<String, Object> config = normalizeAssetConfig(tenantId, principal, assetType, request.config(), false);
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
            readScope,
            editScope,
            null,
            config,
            principal.userId(),
            now
        );

        try {
            TenantAssetCapabilityEntity saved = tenantAssetCapabilityRepository.save(entity);
            replaceAssetAccessGrants(tenantId, saved, principal, readScope, request.readUserIds(), editScope, request.editUserIds());
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

    @Transactional(readOnly = true)
    public AssetManagementApi.MyAssetDetail getMyAsset(UUID tenantId, UUID assetId, CurrentUserPrincipal principal) {
        ensureActiveTenant(tenantId);
        TenantAssetCapabilityEntity asset = loadAssetForRead(tenantId, assetId, principal);
        return toMyAssetDetail(asset, principal);
    }

    @Transactional
    public AssetManagementApi.MyAssetDetail updateMyAsset(
        UUID tenantId,
        UUID assetId,
        CurrentUserPrincipal principal,
        AssetManagementApi.UpdateMyAssetRequest request
    ) {
        ensureActiveTenant(tenantId);
        TenantAssetCapabilityEntity asset = loadAssetForEdit(tenantId, assetId, principal);
        if (!"draft".equals(asset.getStatus())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ASSET_ONLY_DRAFT_EDITABLE", "只有能力草稿可以继续编辑");
        }

        String name = normalizeRequired(request.name(), "ASSET_NAME_REQUIRED", "请输入能力名称");
        String code = asset.getCode();
        String version = normalizeVersion(request.version());
        String riskLevel = normalizeRiskLevel(request.riskLevel());
        if (tenantAssetCapabilityRepository.existsByTenantIdAndCodeAndVersionAndIdNot(tenantId, code, version, assetId)) {
            throw new ApiException(HttpStatus.CONFLICT, "ASSET_CODE_VERSION_EXISTS", "同一租户下已存在相同编码和版本的能力资产");
        }

        Map<String, Object> config = normalizeAssetConfig(tenantId, principal, asset.getAssetType(), request.config(), false);
        asset.updateDraft(name, code, version, normalizeOptional(request.description()), riskLevel, config, principal.userId(), clock.instant());
        log.info(
            "租户能力草稿已更新 tenantId={} userId={} assetId={} assetType={} requestId={}",
            tenantId,
            principal.userId(),
            assetId,
            asset.getAssetType(),
            RequestIds.current()
        );
        return toMyAssetDetail(asset, principal);
    }

    @Transactional
    public AssetManagementApi.MyAssetDetail publishMyAsset(UUID tenantId, UUID assetId, CurrentUserPrincipal principal) {
        ensureActiveTenant(tenantId);
        TenantAssetCapabilityEntity asset = loadAssetForEdit(tenantId, assetId, principal);
        if ("published".equals(asset.getStatus())) {
            return toMyAssetDetail(asset, principal);
        }
        if (!"draft".equals(asset.getStatus())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ASSET_STATUS_NOT_PUBLISHABLE", "当前能力状态不能发布");
        }

        // 发布前重新校验配置引用，避免草稿期间权限变化后仍把未授权 Skill/MCP 固化进智能体模板。
        normalizeAssetConfig(tenantId, principal, asset.getAssetType(), asset.getConfig(), true);
        asset.publish(principal.userId(), clock.instant());
        log.info(
            "租户能力草稿已发布 tenantId={} userId={} assetId={} assetType={} requestId={}",
            tenantId,
            principal.userId(),
            assetId,
            asset.getAssetType(),
            RequestIds.current()
        );
        return toMyAssetDetail(asset, principal);
    }

    @Transactional
    public AssetManagementApi.MyAssetDetail revertMyAssetToDraft(UUID tenantId, UUID assetId, CurrentUserPrincipal principal) {
        ensureActiveTenant(tenantId);
        TenantAssetCapabilityEntity asset = loadAssetForEdit(tenantId, assetId, principal);
        if ("draft".equals(asset.getStatus())) {
            return toMyAssetDetail(asset, principal);
        }
        if (!"published".equals(asset.getStatus())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ASSET_STATUS_NOT_REVERTIBLE", "当前能力状态不能改回草稿");
        }

        // 已发布能力改回草稿后重新进入编辑态；后续接入引用关系索引后，被其他已发布资产或流程引用的能力应禁止回退。
        asset.revertToDraft(principal.userId(), clock.instant());
        log.info(
            "租户能力已改回草稿 tenantId={} userId={} assetId={} assetType={} requestId={}",
            tenantId,
            principal.userId(),
            assetId,
            asset.getAssetType(),
            RequestIds.current()
        );
        return toMyAssetDetail(asset, principal);
    }

    @Transactional
    public AssetManagementApi.MyAssetDetail updateMyAssetAccess(
        UUID tenantId,
        UUID assetId,
        CurrentUserPrincipal principal,
        AssetManagementApi.UpdateMyAssetAccessRequest request
    ) {
        ensureActiveTenant(tenantId);
        TenantAssetCapabilityEntity asset = loadMyAssetForOwner(tenantId, assetId, principal);
        String readScope = normalizeScope(request.readScope());
        String editScope = normalizeScope(request.editScope());
        asset.updateAccess(readScope, editScope, principal.userId(), clock.instant());
        replaceAssetAccessGrants(tenantId, asset, principal, readScope, request.readUserIds(), editScope, request.editUserIds());
        log.info(
            "租户能力读取编辑权限已更新 tenantId={} userId={} assetId={} readScope={} editScope={} requestId={}",
            tenantId,
            principal.userId(),
            assetId,
            readScope,
            editScope,
            RequestIds.current()
        );
        return toMyAssetDetail(asset, principal);
    }

    @Transactional
    public void deleteMyAsset(UUID tenantId, UUID assetId, CurrentUserPrincipal principal) {
        ensureActiveTenant(tenantId);
        TenantAssetCapabilityEntity asset = loadMyAssetForOwner(tenantId, assetId, principal);

        // 当前阶段流程节点尚未落完整资产引用表，因此只能校验创建者边界。
        // 后续接入 workflow_versions / node 配置引用索引后，应先判断是否被草稿、已发布流程或运行快照引用，被使用时禁止删除。
        tenantAssetAccessGrantRepository.deleteByAssetId(asset.getId());
        tenantAssetCapabilityRepository.delete(asset);
        log.info(
            "租户我的能力已删除 tenantId={} userId={} assetId={} assetType={} status={} requestId={}",
            tenantId,
            principal.userId(),
            assetId,
            asset.getAssetType(),
            asset.getStatus(),
            RequestIds.current()
        );
    }

    private AssetManagementApi.SystemCapabilityAssetRow toSystemCapabilityRow(SystemCapabilityAsset asset, boolean assignedToMe, boolean manager) {
        SystemCapabilityEntity capability = asset.capability();
        String promptContent = "";
        if ("prompt_template".equals(capability.getCapabilityType())) {
            Object rawPrompt = capability.getConfig().get("promptContent");
            promptContent = rawPrompt == null ? "" : rawPrompt.toString();
        }
        return new AssetManagementApi.SystemCapabilityAssetRow(
            capability.getId(),
            capability.getCapabilityType(),
            capability.getName(),
            capability.getCode(),
            capability.getVersion(),
            capability.getDescription() == null ? "" : capability.getDescription(),
            promptContent,
            capability.getRiskLevel(),
            capability.getStatus(),
            assignedToMe,
            assignedToMe ? (manager ? "管理入口可用" : "租户管理已分配") : "待租户管理分配",
            "tenant_admin",
            assignedToMe ? "read" : "none",
            "",
            asset.openedAt()
        );
    }

    private AssetManagementApi.SystemCapabilityAssetRow toAccessibleTenantAssetRow(AccessibleTenantAsset accessibleAsset, String ownerDisplayName) {
        TenantAssetCapabilityEntity asset = accessibleAsset.asset();
        String promptContent = "";
        if ("published".equals(asset.getStatus()) && "prompt_template".equals(asset.getAssetType())) {
            Object rawPrompt = asset.getConfig().get("promptContent");
            promptContent = rawPrompt == null ? "" : rawPrompt.toString();
        }
        String ownerLabel = ownerDisplayName.isBlank() ? "同事" : ownerDisplayName;
        return new AssetManagementApi.SystemCapabilityAssetRow(
            asset.getId(),
            asset.getAssetType(),
            asset.getName(),
            asset.getCode(),
            asset.getVersion(),
            asset.getDescription() == null ? "" : asset.getDescription(),
            promptContent,
            asset.getRiskLevel(),
            asset.getStatus(),
            "published".equals(asset.getStatus()),
            ownerLabel + ("edit".equals(accessibleAsset.accessLevel()) ? " 开放编辑" : " 开放读取"),
            "user_shared",
            accessibleAsset.accessLevel(),
            ownerDisplayName,
            accessibleAsset.openedAt()
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
            asset.getReadScope(),
            asset.getEditScope(),
            "owner",
            true,
            asset.getSourceType(),
            asset.getBaseSystemCapabilityId(),
            asset.getCreatedAt(),
            asset.getUpdatedAt(),
            asset.getPublishedAt()
        );
    }

    private AssetManagementApi.MyAssetDetail toMyAssetDetail(TenantAssetCapabilityEntity asset, CurrentUserPrincipal principal) {
        String accessLevel = resolveAssetAccess(asset, principal == null ? null : principal.userId()).name().toLowerCase();
        boolean canManageAccess = principal != null && principal.userId().equals(asset.getCreatedBy());
        return new AssetManagementApi.MyAssetDetail(
            asset.getId(),
            asset.getAssetType(),
            asset.getName(),
            asset.getCode(),
            asset.getVersion(),
            asset.getDescription(),
            asset.getRiskLevel(),
            asset.getStatus(),
            asset.getReadScope(),
            asset.getEditScope(),
            accessLevel,
            canManageAccess,
            asset.getSourceType(),
            asset.getBaseSystemCapabilityId(),
            asset.getConfig(),
            canManageAccess ? loadAccessUserIds(asset.getId(), "read") : List.of(),
            canManageAccess ? loadAccessUserIds(asset.getId(), "edit") : List.of(),
            asset.getCreatedAt(),
            asset.getUpdatedAt(),
            asset.getPublishedAt()
        );
    }

    private List<UUID> loadAccessUserIds(UUID assetId, String accessLevel) {
        return tenantAssetAccessGrantRepository.findByAssetId(assetId).stream()
            .filter(grant -> accessLevel.equals(grant.getAccessLevel()))
            .map(TenantAssetAccessGrantEntity::getGranteeUserId)
            .toList();
    }

    private List<AccessibleTenantAsset> loadAccessibleTenantAssetsForUser(UUID tenantId, UUID userId) {
        if (userId == null) {
            return List.of();
        }
        return tenantAssetCapabilityRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId).stream()
            .filter(asset -> asset.getCreatedBy() != null
                && !asset.getCreatedBy().equals(userId)
                && USER_CREATABLE_ASSET_TYPES.contains(asset.getAssetType()))
            .map(asset -> new AccessibleTenantAsset(
                asset,
                asset.getPublishedAt() == null ? asset.getUpdatedAt() : asset.getPublishedAt(),
                asset.getCreatedBy(),
                resolveAssetAccess(asset, userId).name().toLowerCase()
            ))
            .filter(asset -> !"none".equals(asset.accessLevel()))
            .toList();
    }

    private Map<UUID, String> loadUserDisplayNames(Set<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userAccountRepository.findAllById(userIds)
            .stream()
            .collect(Collectors.toMap(UserAccount::getId, UserAccount::getDisplayName, (left, right) -> left));
    }

    private void replaceAssetAccessGrants(
        UUID tenantId,
        TenantAssetCapabilityEntity asset,
        CurrentUserPrincipal principal,
        String readScope,
        List<UUID> readUserIds,
        String editScope,
        List<UUID> editUserIds
    ) {
        tenantAssetAccessGrantRepository.deleteByAssetId(asset.getId());
        // 必须立即 flush，确保物理删除先于新授权插入执行，避免 uk_tenant_asset_access_grants_asset_user_level 冲突。
        tenantAssetAccessGrantRepository.flush();
        List<UUID> normalizedReadUserIds = normalizeAccessUserIds(tenantId, principal, readScope, readUserIds, "读取");
        List<UUID> normalizedEditUserIds = normalizeAccessUserIds(tenantId, principal, editScope, editUserIds, "编辑");
        Instant now = clock.instant();
        for (UUID granteeUserId : normalizedReadUserIds) {
            tenantAssetAccessGrantRepository.save(TenantAssetAccessGrantEntity.create(tenantId, asset.getId(), granteeUserId, "read", principal.userId(), now));
        }
        for (UUID granteeUserId : normalizedEditUserIds) {
            tenantAssetAccessGrantRepository.save(TenantAssetAccessGrantEntity.create(tenantId, asset.getId(), granteeUserId, "edit", principal.userId(), now));
        }
    }

    private List<UUID> normalizeAccessUserIds(UUID tenantId, CurrentUserPrincipal principal, String scope, List<UUID> userIds, String label) {
        if (!CollaborationAccessPolicy.SCOPE_SPECIFIED.equals(scope)) {
            return List.of();
        }
        if (userIds == null || userIds.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ASSET_ACCESS_USERS_REQUIRED", label + "权限选择指定同事时，必须至少选择一名有效成员");
        }
        Set<UUID> activeMemberIds = userMembershipRepository.findByTenantIdAndStatus(tenantId, ACTIVE_STATUS)
            .stream()
            .map(UserMembershipEntity::getUserId)
            .collect(Collectors.toSet());
        LinkedHashSet<UUID> normalized = new LinkedHashSet<>();
        for (UUID userId : userIds) {
            if (userId == null || principal.userId().equals(userId)) {
                continue;
            }
            if (!activeMemberIds.contains(userId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ASSET_ACCESS_USER_INVALID", "权限对象必须是当前租户内的有效成员");
            }
            normalized.add(userId);
        }
        return new ArrayList<>(normalized);
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
            .filter(capability -> SYSTEM_CAPABILITY_TYPES.contains(capability.getCapabilityType()))
            .sorted(Comparator.comparing(SystemCapabilityEntity::getCapabilityType).thenComparing(SystemCapabilityEntity::getName))
            .map(capability -> new SystemCapabilityAsset(capability, grantsByCapabilityId.get(capability.getId()).getCreatedAt()))
            .toList();
    }

    private List<SystemCapabilityAsset> filterVisibleCapabilities(UUID tenantId, CurrentUserPrincipal principal, List<SystemCapabilityAsset> tenantOpenCapabilities) {
        if (isTenantManager(principal)) {
            return tenantOpenCapabilities;
        }

        Set<UUID> assignedCapabilityIds = resolveAssignedCapabilityIds(tenantId, principal.userId());
        if (assignedCapabilityIds.isEmpty()) {
            return List.of();
        }

        // 业务侧“对我开放”只展示租户管理已分配给当前用户、部门或角色的能力；
        // 系统管理放入租户池但尚未分配的能力不应泄露到普通业务视图。
        return tenantOpenCapabilities.stream()
            .filter(asset -> assignedCapabilityIds.contains(asset.capability().getId()))
            .toList();
    }

    private Set<UUID> resolveAssignedCapabilityIds(UUID tenantId, UUID userId) {
        if (userId == null) {
            return Set.of();
        }

        List<UserMembershipEntity> memberships = userMembershipRepository.findByUserIdAndTenantIdAndStatus(userId, tenantId, ACTIVE_STATUS);
        Set<String> principalKeys = new LinkedHashSet<>();
        principalKeys.add("user:" + userId);
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

        // 能力池分配以 resource_grants 明细承接；resourceType 存储具体能力类型（skill/mcp/delivery/prompt_template），
        // 需按系统能力类型集合过滤，排除将来可能新增的非能力类资源。
        return resourceGrantRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)
            .stream()
            .filter(grant -> SYSTEM_CAPABILITY_TYPES.contains(grant.getResourceType()))
            .filter(grant -> principalKeys.contains(grant.getPrincipalType() + ":" + grant.getPrincipalId()))
            .map(ResourceGrantEntity::getResourceId)
            .collect(Collectors.toSet());
    }

    private TenantAssetCapabilityEntity loadMyAssetForOwner(UUID tenantId, UUID assetId, CurrentUserPrincipal principal) {
        TenantAssetCapabilityEntity asset = tenantAssetCapabilityRepository.findByIdAndTenantId(assetId, tenantId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "能力资产不存在"));
        // “我的能力”只允许创建者维护，避免租户内其他有资产入口的人修改不属于自己的草稿。
        if (principal == null || asset.getCreatedBy() == null || !asset.getCreatedBy().equals(principal.userId())) {
            log.warn(
                "租户能力资产访问被拒绝：非创建者 tenantId={} assetId={} userId={} requestId={}",
                tenantId,
                assetId,
                principal == null ? null : principal.userId(),
                RequestIds.current()
            );
            throw new ApiException(HttpStatus.FORBIDDEN, "ASSET_OWNER_REQUIRED", "只能维护自己创建的能力");
        }
        return asset;
    }

    private TenantAssetCapabilityEntity loadAssetForRead(UUID tenantId, UUID assetId, CurrentUserPrincipal principal) {
        TenantAssetCapabilityEntity asset = loadAsset(tenantId, assetId);
        if (!resolveAssetAccess(asset, principal == null ? null : principal.userId()).canRead()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ASSET_READ_ACCESS_REQUIRED", "当前账号没有读取该能力的权限");
        }
        return asset;
    }

    private TenantAssetCapabilityEntity loadAssetForEdit(UUID tenantId, UUID assetId, CurrentUserPrincipal principal) {
        TenantAssetCapabilityEntity asset = loadAsset(tenantId, assetId);
        if (!resolveAssetAccess(asset, principal == null ? null : principal.userId()).canEdit()) {
            log.warn(
                "租户能力资产编辑被拒绝 tenantId={} assetId={} userId={} requestId={}",
                tenantId,
                assetId,
                principal == null ? null : principal.userId(),
                RequestIds.current()
            );
            throw new ApiException(HttpStatus.FORBIDDEN, "ASSET_EDIT_ACCESS_REQUIRED", "当前账号没有编辑该能力的权限");
        }
        return asset;
    }

    private TenantAssetCapabilityEntity loadAsset(UUID tenantId, UUID assetId) {
        return tenantAssetCapabilityRepository.findByIdAndTenantId(assetId, tenantId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "能力资产不存在"));
    }

    private CollaborationAccessPolicy.AccessLevel resolveAssetAccess(TenantAssetCapabilityEntity asset, UUID userId) {
        List<TenantAssetAccessGrantEntity> grants = tenantAssetAccessGrantRepository.findByAssetId(asset.getId());
        Set<UUID> readUserIds = grants.stream()
            .filter(grant -> "read".equals(grant.getAccessLevel()))
            .map(TenantAssetAccessGrantEntity::getGranteeUserId)
            .collect(Collectors.toSet());
        Set<UUID> editUserIds = grants.stream()
            .filter(grant -> "edit".equals(grant.getAccessLevel()))
            .map(TenantAssetAccessGrantEntity::getGranteeUserId)
            .collect(Collectors.toSet());
        return collaborationAccessPolicy.resolve(asset.getCreatedBy(), userId, asset.getReadScope(), readUserIds, asset.getEditScope(), editUserIds);
    }

    private Map<String, Object> normalizeAssetConfig(
        UUID tenantId,
        CurrentUserPrincipal principal,
        String assetType,
        Map<String, Object> config,
        boolean publishing
    ) {
        Map<String, Object> normalized = new HashMap<>();
        Map<String, Object> source = config == null ? Map.of() : config;
        if ("prompt_template".equals(assetType)) {
            String promptContent = valueAsString(source.get("promptContent"));
            if (publishing && promptContent.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ASSET_PROMPT_CONTENT_REQUIRED", "发布提示词模板前必须填写提示词内容");
            }
            normalized.put("promptContent", promptContent);
            return normalized;
        }
        if ("agent_template".equals(assetType)) {
            String systemPrompt = valueAsString(source.get("systemPrompt"));
            String systemPromptTemplateId = normalizePromptTemplateReference(source.get("systemPromptTemplateId"));
            boolean hasPromptTemplateReference = !"none".equals(systemPromptTemplateId);
            if (publishing && systemPrompt.isBlank() && !hasPromptTemplateReference) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ASSET_AGENT_SYSTEM_PROMPT_REQUIRED", "发布智能体模板前必须填写系统提示词或选择提示词模板");
            }
            List<UUID> skillIds = normalizeUuidList(source.get("skillIds"), "ASSET_AGENT_SKILL_REFERENCE_INVALID", "智能体模板引用的 Skill 不合法");
            List<UUID> mcpIds = normalizeUuidList(source.get("mcpIds"), "ASSET_AGENT_MCP_REFERENCE_INVALID", "智能体模板引用的 MCP 不合法");
            validateAgentCapabilityReferences(tenantId, principal, skillIds, "skill");
            validateAgentCapabilityReferences(tenantId, principal, mcpIds, "mcp");
            if (hasPromptTemplateReference) {
                validateSystemPromptTemplateReference(tenantId, principal, UUID.fromString(systemPromptTemplateId));
            }
            normalized.put("systemPrompt", systemPrompt);
            normalized.put("systemPromptTemplateId", systemPromptTemplateId);
            normalized.put("skillIds", skillIds.stream().map(UUID::toString).toList());
            normalized.put("mcpIds", mcpIds.stream().map(UUID::toString).toList());
            return normalized;
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "ASSET_TYPE_INVALID", "当前只能维护提示词模板草稿或智能体模板草稿");
    }

    private void validateAgentCapabilityReferences(UUID tenantId, CurrentUserPrincipal principal, List<UUID> capabilityIds, String expectedType) {
        if (capabilityIds.isEmpty()) {
            return;
        }
        Map<UUID, SystemCapabilityEntity> visibleCapabilities = filterVisibleCapabilities(tenantId, principal, loadTenantOpenCapabilities(tenantId))
            .stream()
            .map(SystemCapabilityAsset::capability)
            .collect(Collectors.toMap(SystemCapabilityEntity::getId, Function.identity()));
        for (UUID capabilityId : capabilityIds) {
            SystemCapabilityEntity capability = visibleCapabilities.get(capabilityId);
            if (capability == null || !expectedType.equals(capability.getCapabilityType())) {
                // 智能体草稿只能引用当前主体已开放的 Skill/MCP，不能通过手写 ID 绕过租户能力池和分配边界。
                throw new ApiException(HttpStatus.BAD_REQUEST, "ASSET_AGENT_CAPABILITY_NOT_AVAILABLE", "智能体模板只能引用已对当前主体开放的 Skill 或 MCP");
            }
        }
    }

    private void validateSystemPromptTemplateReference(UUID tenantId, CurrentUserPrincipal principal, UUID templateId) {
        Map<UUID, SystemCapabilityEntity> visibleCapabilities = filterVisibleCapabilities(tenantId, principal, loadTenantOpenCapabilities(tenantId))
            .stream()
            .map(SystemCapabilityAsset::capability)
            .collect(Collectors.toMap(SystemCapabilityEntity::getId, Function.identity()));
        SystemCapabilityEntity capability = visibleCapabilities.get(templateId);
        if (capability != null && "prompt_template".equals(capability.getCapabilityType())) {
            return;
        }

        TenantAssetCapabilityEntity tenantAsset = tenantAssetCapabilityRepository.findByIdAndTenantId(templateId, tenantId).orElse(null);
        if (tenantAsset != null
            && "prompt_template".equals(tenantAsset.getAssetType())
            && "published".equals(tenantAsset.getStatus())
            && principal != null
            && principal.userId() != null) {
            if (principal.userId().equals(tenantAsset.getCreatedBy())) {
                return;
            }
            if (resolveAssetAccess(tenantAsset, principal.userId()).canRead()) {
                return;
            }
        }

        // 智能体模板只能引用当前主体已开放的系统提示词，或已发布且对当前主体可见的提示词模板。
        throw new ApiException(HttpStatus.BAD_REQUEST, "ASSET_AGENT_PROMPT_TEMPLATE_NOT_AVAILABLE", "智能体模板只能引用已发布的提示词模板或已对当前主体开放的系统提示词");
    }

    private String normalizePromptTemplateReference(Object value) {
        String normalized = valueAsString(value);
        if (normalized.isBlank() || "none".equals(normalized)) {
            return "none";
        }
        try {
            return UUID.fromString(normalized).toString();
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ASSET_AGENT_PROMPT_TEMPLATE_REFERENCE_INVALID", "智能体模板引用的提示词模板不合法");
        }
    }

    private List<UUID> normalizeUuidList(Object value, String code, String message) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> rawList)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, code, message);
        }
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (Object item : rawList) {
            try {
                ids.add(UUID.fromString(valueAsString(item)));
            } catch (IllegalArgumentException exception) {
                throw new ApiException(HttpStatus.BAD_REQUEST, code, message);
            }
        }
        return new ArrayList<>(ids);
    }

    private String valueAsString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
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
        if (!USER_CREATABLE_ASSET_TYPES.contains(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ASSET_TYPE_INVALID", "当前只能新建提示词模板草稿或智能体模板草稿");
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

    private String normalizeScope(String scope) {
        String normalized = normalizeOptional(scope);
        if (normalized.isBlank()) {
            return CollaborationAccessPolicy.SCOPE_SELF;
        }
        if (!collaborationAccessPolicy.isSupportedScope(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ASSET_ACCESS_SCOPE_INVALID", "权限范围不受支持");
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

    private record AccessibleTenantAsset(TenantAssetCapabilityEntity asset, Instant openedAt, UUID ownerUserId, String accessLevel) {
    }
}
