package com.agentum.system.application;

import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.agentum.shared.pagination.PageQuery;
import com.agentum.shared.pagination.PageResponse;
import com.agentum.shared.pagination.PageableFactory;
import com.agentum.shared.pagination.SortWhitelist;
import com.agentum.shared.security.FieldEncryptionService;
import com.agentum.shared.util.CapabilityCodeGenerator;
import com.agentum.system.domain.ModelProviderEntity;
import com.agentum.system.domain.ModelProviderTypeEntity;
import com.agentum.system.domain.SystemCapabilityEntity;
import com.agentum.system.domain.TenantCapabilityGrantEntity;
import com.agentum.system.domain.TenantModelAssignmentEntity;
import com.agentum.system.infrastructure.ModelProviderRepository;
import com.agentum.system.infrastructure.ModelProviderTypeRepository;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import com.agentum.system.infrastructure.TenantCapabilityGrantRepository;
import com.agentum.system.infrastructure.TenantModelAssignmentRepository;
import com.agentum.system.application.McpSseTestOutcome.McpToolDescriptor;
import com.agentum.system.interfaces.SystemManagementApi;
import com.agentum.auth.domain.UserAccount;
import com.agentum.auth.domain.UserRoleAssignmentEntity;
import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.auth.infrastructure.UserRoleAssignmentRepository;
import com.agentum.organization.domain.DepartmentEntity;
import com.agentum.organization.domain.UserMembershipEntity;
import com.agentum.organization.domain.UserMembershipRoleEntity;
import com.agentum.organization.infrastructure.DepartmentRepository;
import com.agentum.organization.infrastructure.UserMembershipRepository;
import com.agentum.organization.infrastructure.UserMembershipRoleRepository;
import com.agentum.permission.domain.RoleEntity;
import com.agentum.permission.infrastructure.RoleRepository;
import com.agentum.tenant.domain.TenantEntity;
import com.agentum.tenant.infrastructure.TenantRepository;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SystemManagementService {

    private static final Logger log = LoggerFactory.getLogger(SystemManagementService.class);
    private static final String ACTIVE = "active";
    private static final String SUSPENDED = "suspended";
    private static final Set<String> CAPABILITY_TYPES = Set.of("mcp", "skill", "prompt_template", "delivery");
    private static final SortWhitelist TENANT_SORT = SortWhitelist.of("createdAt", "name", "code", "status", "createdAt", "updatedAt");
    private static final SortWhitelist MODEL_PROVIDER_SORT = SortWhitelist.of("createdAt", "name", "providerType", "status", "createdAt", "updatedAt");
    private static final SortWhitelist CAPABILITY_SORT = SortWhitelist.of(
        "createdAt",
        "name",
        "capabilityType",
        "code",
        "version",
        "riskLevel",
        "status",
        "createdAt",
        "updatedAt"
    );

    private final TenantRepository tenantRepository;
    private final ModelProviderRepository modelProviderRepository;
    private final ModelProviderTypeRepository modelProviderTypeRepository;
    private final SystemCapabilityRepository systemCapabilityRepository;
    private final TenantCapabilityGrantRepository tenantCapabilityGrantRepository;
    private final TenantModelAssignmentRepository tenantModelAssignmentRepository;
    private final UserAccountRepository userAccountRepository;
    private final UserRoleAssignmentRepository userRoleAssignmentRepository;
    private final RoleRepository roleRepository;
    private final DepartmentRepository departmentRepository;
    private final UserMembershipRepository userMembershipRepository;
    private final UserMembershipRoleRepository userMembershipRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final FieldEncryptionService fieldEncryptionService;
    private final ModelProviderConnectionTester modelProviderConnectionTester;
    private final McpSseConnectionTester mcpSseConnectionTester;
    private final Clock clock;

    public SystemManagementService(
        TenantRepository tenantRepository,
        ModelProviderRepository modelProviderRepository,
        ModelProviderTypeRepository modelProviderTypeRepository,
        SystemCapabilityRepository systemCapabilityRepository,
        TenantCapabilityGrantRepository tenantCapabilityGrantRepository,
        TenantModelAssignmentRepository tenantModelAssignmentRepository,
        UserAccountRepository userAccountRepository,
        UserRoleAssignmentRepository userRoleAssignmentRepository,
        RoleRepository roleRepository,
        DepartmentRepository departmentRepository,
        UserMembershipRepository userMembershipRepository,
        UserMembershipRoleRepository userMembershipRoleRepository,
        PasswordEncoder passwordEncoder,
        FieldEncryptionService fieldEncryptionService,
        ModelProviderConnectionTester modelProviderConnectionTester,
        McpSseConnectionTester mcpSseConnectionTester,
        Clock clock
    ) {
        this.tenantRepository = tenantRepository;
        this.modelProviderRepository = modelProviderRepository;
        this.modelProviderTypeRepository = modelProviderTypeRepository;
        this.systemCapabilityRepository = systemCapabilityRepository;
        this.tenantCapabilityGrantRepository = tenantCapabilityGrantRepository;
        this.tenantModelAssignmentRepository = tenantModelAssignmentRepository;
        this.userAccountRepository = userAccountRepository;
        this.userRoleAssignmentRepository = userRoleAssignmentRepository;
        this.roleRepository = roleRepository;
        this.departmentRepository = departmentRepository;
        this.userMembershipRepository = userMembershipRepository;
        this.userMembershipRoleRepository = userMembershipRoleRepository;
        this.passwordEncoder = passwordEncoder;
        this.fieldEncryptionService = fieldEncryptionService;
        this.modelProviderConnectionTester = modelProviderConnectionTester;
        this.mcpSseConnectionTester = mcpSseConnectionTester;
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
    public PageResponse<SystemManagementApi.TenantRow> listTenants(int page, int size, String sort) {
        return PageResponse.from(tenantRepository.findAll(PageableFactory.from(PageQuery.of(page, size, sort), TENANT_SORT))
            .map(tenant -> new SystemManagementApi.TenantRow(tenant.getId(), tenant.getName(), tenant.getCode(), tenant.getStatus())));
    }

    @Transactional
    public SystemManagementApi.TenantRow createTenant(SystemManagementApi.CreateTenantRequest request) {
        if (tenantRepository.existsByCode(request.code().trim())) {
            throw new ApiException(HttpStatus.CONFLICT, "SYSTEM_TENANT_DUPLICATE", "租户编码已被占用");
        }
        if (userAccountRepository.existsByUsername(request.adminUsername().trim())) {
            throw new ApiException(HttpStatus.CONFLICT, "SYSTEM_USER_DUPLICATE", "管理员账号已被占用");
        }

        // 1. 创建租户
        TenantEntity tenant = TenantEntity.create(request.name().trim(), request.code().trim(), clock.instant());
        tenantRepository.save(tenant);

        // 2. 为该租户初始化基础内置角色。roles 仍服务成员关系和资源授权，登录入口由 user_role_assignments 控制。
        RoleEntity tenantAdminRole = RoleEntity.create(tenant.getId(), "tenant_admin", "租户管理员", "tenant", "管理租户内配置与人员");
        roleRepository.save(tenantAdminRole);
        // 3. 创建默认部门
        DepartmentEntity defaultDept = DepartmentEntity.create(tenant.getId(), null, "默认部门", "default", 0);
        departmentRepository.save(defaultDept);

        // 4. 创建管理员账号
        String encodedPass = passwordEncoder.encode(request.adminPassword());
        String adminEmail = request.adminEmail() != null ? request.adminEmail().trim() : null;
        UserAccount adminUser = UserAccount.create(request.adminUsername().trim(), encodedPass, request.adminDisplayName().trim(), adminEmail);
        userAccountRepository.save(adminUser);

        // 5. 绑定成员关系，并同步写入统一登录角色分配表；否则新管理员只能出现在组织关系里，无法通过新认证模型登录。
        UserMembershipEntity membership = UserMembershipEntity.create(tenant.getId(), adminUser.getId(), defaultDept.getId(), "默认空间");
        userMembershipRepository.save(membership);
        userMembershipRoleRepository.save(UserMembershipRoleEntity.create(membership.getId(), tenantAdminRole.getId()));
        userRoleAssignmentRepository.save(UserRoleAssignmentEntity.create(adminUser.getId(), "tenant_admin", tenant.getId(), tenant.getName() + " - 租户管理", true));
        // 租户管理员也应能切换到业务视图；历史迁移已补 business 入口，新建租户必须保持同一角色切换语义。
        userRoleAssignmentRepository.save(UserRoleAssignmentEntity.create(adminUser.getId(), "business", tenant.getId(), tenant.getName() + " - 业务用户", false));

        log.info("系统管理创建租户及初始管理员成功 tenantId={} adminId={} requestId={}", tenant.getId(), adminUser.getId(), RequestIds.current());
        return new SystemManagementApi.TenantRow(tenant.getId(), tenant.getName(), tenant.getCode(), tenant.getStatus());
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
    public PageResponse<SystemManagementApi.ModelProviderRow> listModelProviders(int page, int size, String sort) {
        return PageResponse.from(modelProviderRepository.findAll(PageableFactory.from(PageQuery.of(page, size, sort), MODEL_PROVIDER_SORT))
            .map(SystemManagementService::toModelRow));
    }

    @Transactional(readOnly = true)
    public List<SystemManagementApi.ModelProviderTypeRow> listModelProviderTypes() {
        return modelProviderTypeRepository.findByStatusOrderByNameAsc(ACTIVE).stream()
            .map(SystemManagementService::toModelProviderTypeRow)
            .toList();
    }

    @Transactional
    public SystemManagementApi.ModelProviderRow createModelProvider(SystemManagementApi.CreateModelProviderRequest request) {
        ModelProviderTypeEntity providerType = modelProviderTypeRepository.findByCodeAndStatus(request.providerType().trim(), ACTIVE)
            .orElseThrow(() -> {
                log.warn("系统管理注册模型供应商失败：供应商类型不可用 providerType={} requestId={}", request.providerType(), RequestIds.current());
                return new ApiException(HttpStatus.BAD_REQUEST, "SYSTEM_MODEL_PROVIDER_TYPE_INVALID", "模型供应商类型不存在或已停用");
            });
        String defaultModel = requireDefaultModel(request.defaultModel());

        ModelProviderEntity entity = ModelProviderEntity.create(
            request.name().trim(),
            providerType.getCode(),
            firstNonBlank(request.baseUrl(), providerType.getDefaultBaseUrl()),
            defaultModel,
            request.status() == null ? null : request.status().trim(),
            clock.instant()
        );
        String apiKey = stringValue(request.apiKey());
        if (apiKey != null) {
            entity.storeEncryptedApiKey(fieldEncryptionService.encrypt(apiKey), clock.instant());
        }
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

    @Transactional
    public SystemManagementApi.ModelProviderRow updateModelProvider(UUID providerId, SystemManagementApi.UpdateModelProviderRequest request) {
        ModelProviderEntity entity = modelProviderRepository.findById(providerId)
            .orElseThrow(() -> {
                log.warn("系统管理更新模型供应商失败：供应商不存在 providerId={} requestId={}", providerId, RequestIds.current());
                return new ApiException(HttpStatus.NOT_FOUND, "SYSTEM_MODEL_PROVIDER_NOT_FOUND", "模型供应商不存在");
            });
        ModelProviderTypeEntity providerType = modelProviderTypeRepository.findByCodeAndStatus(request.providerType().trim(), ACTIVE)
            .orElseThrow(() -> {
                log.warn("系统管理更新模型供应商失败：供应商类型不可用 providerId={} providerType={} requestId={}", providerId, request.providerType(), RequestIds.current());
                return new ApiException(HttpStatus.BAD_REQUEST, "SYSTEM_MODEL_PROVIDER_TYPE_INVALID", "模型供应商类型不存在或已停用");
            });
        String targetStatus = request.status() == null ? "draft" : request.status().trim();
        ensureModelProviderCanEnterStatus(providerId, targetStatus);
        entity.updateProfile(
            request.name().trim(),
            providerType.getCode(),
            firstNonBlank(request.baseUrl(), providerType.getDefaultBaseUrl()),
            requireDefaultModel(request.defaultModel()),
            targetStatus,
            clock.instant()
        );
        String apiKey = stringValue(request.apiKey());
        if (apiKey != null) {
            entity.storeEncryptedApiKey(fieldEncryptionService.encrypt(apiKey), clock.instant());
        }
        modelProviderRepository.save(entity);
        log.info("系统管理更新模型供应商成功 providerId={} type={} requestId={}", entity.getId(), entity.getProviderType(), RequestIds.current());
        return toModelRow(entity);
    }

    @Transactional(readOnly = true)
    public SystemManagementApi.ModelProviderTestResult testModelProvider(UUID providerId) {
        ModelProviderEntity provider = modelProviderRepository.findById(providerId)
            .orElseThrow(() -> {
                log.warn("系统管理测试模型供应商失败：供应商不存在 providerId={} requestId={}", providerId, RequestIds.current());
                return new ApiException(HttpStatus.NOT_FOUND, "SYSTEM_MODEL_PROVIDER_NOT_FOUND", "模型供应商不存在");
            });
        ModelProviderTypeEntity providerType = modelProviderTypeRepository.findByCodeAndStatus(provider.getProviderType(), ACTIVE)
            .orElseThrow(() -> {
                log.warn(
                    "系统管理测试模型供应商失败：供应商类型不可用 providerId={} providerType={} requestId={}",
                    provider.getId(),
                    provider.getProviderType(),
                    RequestIds.current()
                );
                return new ApiException(HttpStatus.BAD_REQUEST, "SYSTEM_MODEL_PROVIDER_TYPE_INVALID", "模型供应商类型不存在或已停用");
            });
        if (stringValue(provider.getBaseUrl()) == null) {
            return new SystemManagementApi.ModelProviderTestResult(provider.getId(), "failed", "模型供应商基址 URL 未配置", List.of(), 0, clock.instant());
        }
        String encryptedApiKey = provider.getEncryptedApiKey();

        // API Key 是可选字段：本地模型网关或内网代理可能不需要认证；有密钥时才在服务端解密后传入测试器。
        // 日志和响应均不得包含明文或完整供应商原始响应。
        ModelProviderTestOutcome outcome = modelProviderConnectionTester.test(new ModelProviderTestRequest(
            provider.getId(),
            provider.getProviderType(),
            provider.getBaseUrl(),
            firstNonBlank(providerType.getModelListEndpoint(), "/models"),
            provider.getDefaultModel(),
            providerType.getAuthScheme(),
            encryptedApiKey == null ? null : fieldEncryptionService.decrypt(encryptedApiKey)
        ));
        log.info("系统管理测试模型供应商完成 providerId={} type={} status={} requestId={}", provider.getId(), provider.getProviderType(), outcome.status(), RequestIds.current());
        return new SystemManagementApi.ModelProviderTestResult(
            provider.getId(),
            outcome.status(),
            outcome.summary(),
            outcome.availableModels(),
            outcome.latencyMs(),
            clock.instant()
        );
    }

    @Transactional
    public void deleteModelProvider(UUID providerId) {
        ModelProviderEntity entity = modelProviderRepository.findById(providerId)
            .orElseThrow(() -> {
                log.warn("系统管理删除模型供应商失败：供应商不存在 providerId={} requestId={}", providerId, RequestIds.current());
                return new ApiException(HttpStatus.NOT_FOUND, "SYSTEM_MODEL_PROVIDER_NOT_FOUND", "模型供应商不存在");
            });
        if (tenantModelAssignmentRepository.existsByProviderIdAndStatus(providerId, "enabled")) {
            log.warn("系统管理删除模型供应商失败：供应商仍被租户启用 providerId={} requestId={}", providerId, RequestIds.current());
            throw new ApiException(HttpStatus.BAD_REQUEST, "SYSTEM_MODEL_PROVIDER_IN_USE", "模型供应商已被租户启用，请先取消租户模型分配");
        }
        modelProviderRepository.delete(entity);
        log.info(
            "系统管理删除模型供应商成功 providerId={} name={} requestId={}",
            entity.getId(),
            entity.getName(),
            RequestIds.current()
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<SystemManagementApi.CapabilityRow> listCapabilities(int page, int size, String sort) {
        return PageResponse.from(systemCapabilityRepository.findAll(PageableFactory.from(PageQuery.of(page, size, sort), CAPABILITY_SORT))
            .map(SystemManagementService::toCapabilityRow));
    }

    @Transactional
    public SystemManagementApi.CapabilityRow createCapability(SystemManagementApi.CreateCapabilityRequest request) {
        String version = normalizeVersion(request.version());
        String capabilityType = request.capabilityType().trim();
        String name = request.name().trim();
        if (!CAPABILITY_TYPES.contains(capabilityType)) {
            log.warn("系统管理注册能力失败：能力类型不在全局能力范围 capabilityType={} requestId={}", capabilityType, RequestIds.current());
            throw new ApiException(HttpStatus.BAD_REQUEST, "SYSTEM_CAPABILITY_TYPE_INVALID", "全局能力类型只能是 MCP、Skill、提示词模板或交付能力");
        }
        String code = CapabilityCodeGenerator.resolveUniqueCode(
            name,
            version,
            (candidate, candidateVersion) -> systemCapabilityRepository.findByCodeAndVersion(candidate, candidateVersion).isPresent()
        );

        SystemCapabilityEntity entity = SystemCapabilityEntity.create(
            capabilityType,
            name,
            code,
            version,
            normalizeOptional(request.description()),
            request.riskLevel() == null ? null : request.riskLevel().trim(),
            request.status() == null ? null : request.status().trim(),
            sanitizeCapabilityConfig(capabilityType, request.config(), Map.of()),
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

    @Transactional
    public SystemManagementApi.CapabilityRow updateCapability(UUID capabilityId, SystemManagementApi.UpdateCapabilityRequest request) {
        String version = normalizeVersion(request.version());
        String capabilityType = request.capabilityType().trim();
        if (!CAPABILITY_TYPES.contains(capabilityType)) {
            log.warn("系统管理更新能力失败：能力类型不在全局能力范围 capabilityId={} capabilityType={} requestId={}", capabilityId, capabilityType, RequestIds.current());
            throw new ApiException(HttpStatus.BAD_REQUEST, "SYSTEM_CAPABILITY_TYPE_INVALID", "全局能力类型只能是 MCP、Skill、提示词模板或交付能力");
        }
        SystemCapabilityEntity entity = systemCapabilityRepository.findById(capabilityId)
            .orElseThrow(() -> {
                log.warn("系统管理更新能力失败：能力不存在 capabilityId={} requestId={}", capabilityId, RequestIds.current());
                return new ApiException(HttpStatus.NOT_FOUND, "SYSTEM_CAPABILITY_NOT_FOUND", "系统能力不存在");
            });
        String targetStatus = request.status() == null ? "draft" : request.status().trim();
        ensureCapabilityCanEnterStatus(capabilityId, targetStatus);
        systemCapabilityRepository.findByCodeAndVersion(entity.getCode(), version)
            .filter(existing -> !existing.getId().equals(capabilityId))
            .ifPresent(existing -> {
                log.warn(
                    "系统管理更新能力失败：编码与版本已存在 capabilityId={} duplicatedCapabilityId={} code={} version={} requestId={}",
                    capabilityId,
                    existing.getId(),
                    entity.getCode(),
                    version,
                    RequestIds.current()
                );
                throw new ApiException(HttpStatus.CONFLICT, "SYSTEM_CAPABILITY_DUPLICATE", "同一能力编码与版本已存在");
            });
        entity.updateProfile(
            capabilityType,
            request.name().trim(),
            version,
            normalizeOptional(request.description()),
            request.riskLevel() == null ? null : request.riskLevel().trim(),
            targetStatus,
            sanitizeCapabilityConfig(capabilityType, request.config(), entity.getConfig()),
            clock.instant()
        );
        systemCapabilityRepository.save(entity);
        log.info("系统管理更新全局能力成功 capabilityId={} code={} version={} requestId={}", entity.getId(), entity.getCode(), entity.getVersion(), RequestIds.current());
        return toCapabilityRow(entity);
    }

    @Transactional
    public void deleteCapability(UUID capabilityId) {
        SystemCapabilityEntity entity = systemCapabilityRepository.findById(capabilityId)
            .orElseThrow(() -> {
                log.warn("系统管理删除能力失败：能力不存在 capabilityId={} requestId={}", capabilityId, RequestIds.current());
                return new ApiException(HttpStatus.NOT_FOUND, "SYSTEM_CAPABILITY_NOT_FOUND", "系统能力不存在");
            });
        if (tenantCapabilityGrantRepository.existsByCapabilityIdAndStatus(capabilityId, "enabled")) {
            log.warn("系统管理删除能力失败：能力仍被租户启用 capabilityId={} requestId={}", capabilityId, RequestIds.current());
            throw new ApiException(HttpStatus.BAD_REQUEST, "SYSTEM_CAPABILITY_IN_USE", "全局能力已被租户启用，请先取消租户能力配置");
        }
        systemCapabilityRepository.delete(entity);
        log.info(
            "系统管理删除全局能力成功 capabilityId={} code={} version={} requestId={}",
            entity.getId(),
            entity.getCode(),
            entity.getVersion(),
            RequestIds.current()
        );
    }

    @Transactional(readOnly = true)
    public SystemManagementApi.CapabilityTestResult testCapability(UUID capabilityId) {
        SystemCapabilityEntity capability = systemCapabilityRepository.findById(capabilityId)
            .orElseThrow(() -> {
                log.warn("系统管理测试能力失败：能力不存在 capabilityId={} requestId={}", capabilityId, RequestIds.current());
                return new ApiException(HttpStatus.NOT_FOUND, "SYSTEM_CAPABILITY_NOT_FOUND", "系统能力不存在");
            });

        // 当前阶段系统管理先负责“底层能力是否可连接、是否可展示工具清单”的平台侧测试；
        // 真正运行时仍要经过后续 MCP 网关、Skill 沙箱和交付审计链路重新校验。
        SystemManagementApi.CapabilityTestResult result = switch (capability.getCapabilityType()) {
            case "mcp" -> testMcpConfig(capability);
            case "skill" -> testSourceConfig(capability, "Skill 源配置检查通过，后续接入 Skill manifest 校验与样例运行");
            case "prompt_template" -> testPromptTemplateConfig(capability);
            case "delivery" -> testDeliveryConfig(capability);
            default -> new SystemManagementApi.CapabilityTestResult(capability.getId(), "failed", "能力类型不在平台全局能力范围", List.of(), clock.instant());
        };

        log.info(
            "系统管理测试全局能力完成 capabilityId={} type={} status={} requestId={}",
            capability.getId(),
            capability.getCapabilityType(),
            result.status(),
            RequestIds.current()
        );
        return result;
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
        String targetStatus = request.status() == null ? "enabled" : request.status().trim();
        if ("enabled".equals(targetStatus) && !"active".equals(capability.getStatus())) {
            log.warn(
                "系统管理授权失败：草稿能力不能进入租户可用能力池 tenantId={} capabilityId={} capabilityStatus={} requestId={}",
                tenant.getId(),
                capability.getId(),
                capability.getStatus(),
                RequestIds.current()
            );
            throw new ApiException(HttpStatus.BAD_REQUEST, "SYSTEM_CAPABILITY_NOT_ACTIVE", "全局能力仍是草稿，请先将能力状态改为启用");
        }

        TenantCapabilityGrantEntity existing = tenantCapabilityGrantRepository.findByTenantIdAndCapabilityId(tenant.getId(), capability.getId()).orElse(null);
        if (existing != null) {
            // 单租户能力配置是开关型配置。已存在记录时允许从 disabled 重新启用，避免前端取消后无法再次启用。
            existing.updateStatus(targetStatus);
            tenantCapabilityGrantRepository.save(existing);
            log.info("系统管理更新租户能力配置成功 grantId={} tenantId={} capabilityId={} requestId={}", existing.getId(), tenant.getId(), capability.getId(), RequestIds.current());
            return toGrantRow(existing);
        }

        TenantCapabilityGrantEntity entity = TenantCapabilityGrantEntity.create(
            tenant.getId(),
            capability.getId(),
            targetStatus,
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

    @Transactional
    public SystemManagementApi.GrantRow updateGrantStatus(UUID grantId, SystemManagementApi.UpdateGrantStatusRequest request) {
        String status = request.status().trim();
        if (!"enabled".equals(status) && !"disabled".equals(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SYSTEM_GRANT_STATUS_INVALID", "能力配置状态只能是 enabled 或 disabled");
        }

        TenantCapabilityGrantEntity entity = tenantCapabilityGrantRepository.findById(grantId)
            .orElseThrow(() -> {
                log.warn("系统管理更新租户能力配置失败：配置不存在 grantId={} requestId={}", grantId, RequestIds.current());
                return new ApiException(HttpStatus.NOT_FOUND, "SYSTEM_GRANT_NOT_FOUND", "租户能力配置不存在");
            });
        if ("enabled".equals(status)) {
            SystemCapabilityEntity capability = systemCapabilityRepository.findById(entity.getCapabilityId())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "SYSTEM_CAPABILITY_NOT_FOUND", "系统能力不存在"));
            if (!"active".equals(capability.getStatus())) {
                log.warn(
                    "系统管理更新租户能力配置失败：草稿能力不能启用 grantId={} capabilityId={} capabilityStatus={} requestId={}",
                    grantId,
                    capability.getId(),
                    capability.getStatus(),
                    RequestIds.current()
                );
                throw new ApiException(HttpStatus.BAD_REQUEST, "SYSTEM_CAPABILITY_NOT_ACTIVE", "全局能力仍是草稿，请先将能力状态改为启用");
            }
        }
        entity.updateStatus(status);
        tenantCapabilityGrantRepository.save(entity);
        log.info("系统管理更新租户能力配置状态成功 grantId={} status={} requestId={}", grantId, status, RequestIds.current());
        return toGrantRow(entity);
    }

    @Transactional(readOnly = true)
    public List<SystemManagementApi.TenantModelAssignmentRow> listTenantModelAssignments(UUID tenantId) {
        ensureTenantExists(tenantId);
        return tenantModelAssignmentRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
            .map(this::toTenantModelAssignmentRow)
            .toList();
    }

    @Transactional
    public SystemManagementApi.TenantModelAssignmentRow createTenantModelAssignment(SystemManagementApi.CreateTenantModelAssignmentRequest request) {
        TenantEntity tenant = ensureTenantExists(request.tenantId());
        ModelProviderEntity provider = modelProviderRepository.findById(request.providerId())
            .orElseThrow(() -> {
                log.warn("系统管理模型分配失败：供应商不存在 tenantId={} providerId={} requestId={}", request.tenantId(), request.providerId(), RequestIds.current());
                return new ApiException(HttpStatus.BAD_REQUEST, "SYSTEM_MODEL_PROVIDER_NOT_FOUND", "模型供应商不存在");
            });
        String targetStatus = request.status() == null ? "enabled" : request.status().trim();
        ensureModelProviderCanBeEnabled(provider, targetStatus);

        TenantModelAssignmentEntity existing = tenantModelAssignmentRepository.findByTenantIdAndProviderId(tenant.getId(), provider.getId()).orElse(null);
        if (existing != null) {
            // 模型分配是租户可用能力池的一部分，前端允许取消后再次启用，因此已有记录应复用并切回 enabled。
            existing.updateDefaultModel(firstNonBlank(request.defaultModel(), provider.getDefaultModel()), clock.instant());
            existing.updateStatus(targetStatus, clock.instant());
            tenantModelAssignmentRepository.save(existing);
            log.info("系统管理更新租户模型分配成功 tenantId={} providerId={} assignmentId={} requestId={}", tenant.getId(), provider.getId(), existing.getId(), RequestIds.current());
            return toTenantModelAssignmentRow(existing);
        }

        TenantModelAssignmentEntity entity = TenantModelAssignmentEntity.create(
            tenant.getId(),
            provider.getId(),
            firstNonBlank(request.defaultModel(), provider.getDefaultModel()),
            targetStatus,
            clock.instant()
        );
        tenantModelAssignmentRepository.save(entity);
        log.info("系统管理写入租户模型分配成功 tenantId={} providerId={} assignmentId={} requestId={}", tenant.getId(), provider.getId(), entity.getId(), RequestIds.current());
        return toTenantModelAssignmentRow(entity);
    }

    @Transactional
    public SystemManagementApi.TenantModelAssignmentRow updateTenantModelAssignmentStatus(UUID assignmentId, SystemManagementApi.UpdateTenantModelAssignmentStatusRequest request) {
        String status = request.status().trim();
        if (!"enabled".equals(status) && !"disabled".equals(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SYSTEM_MODEL_ASSIGNMENT_STATUS_INVALID", "模型分配状态只能是 enabled 或 disabled");
        }
        TenantModelAssignmentEntity entity = tenantModelAssignmentRepository.findById(assignmentId)
            .orElseThrow(() -> {
                log.warn("系统管理更新租户模型分配失败：分配记录不存在 assignmentId={} requestId={}", assignmentId, RequestIds.current());
                return new ApiException(HttpStatus.NOT_FOUND, "SYSTEM_MODEL_ASSIGNMENT_NOT_FOUND", "租户模型分配不存在");
            });
        if ("enabled".equals(status)) {
            ModelProviderEntity provider = modelProviderRepository.findById(entity.getProviderId())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "SYSTEM_MODEL_PROVIDER_NOT_FOUND", "模型供应商不存在"));
            ensureModelProviderCanBeEnabled(provider, status);
        }
        entity.updateStatus(status, clock.instant());
        tenantModelAssignmentRepository.save(entity);
        log.info("系统管理更新租户模型分配状态成功 assignmentId={} status={} requestId={}", assignmentId, status, RequestIds.current());
        return toTenantModelAssignmentRow(entity);
    }

    private TenantEntity ensureTenantExists(UUID tenantId) {
        return tenantRepository.findById(tenantId)
            .orElseThrow(() -> {
                log.warn("系统管理查询租户配置失败：租户不存在 tenantId={} requestId={}", tenantId, RequestIds.current());
                return new ApiException(HttpStatus.BAD_REQUEST, "SYSTEM_TENANT_NOT_FOUND", "租户不存在");
            });
    }

    private void ensureModelProviderCanEnterStatus(UUID providerId, String targetStatus) {
        if ("draft".equals(targetStatus) && tenantModelAssignmentRepository.existsByProviderIdAndStatus(providerId, "enabled")) {
            log.warn("系统管理更新模型供应商失败：供应商仍被租户启用 providerId={} targetStatus={} requestId={}", providerId, targetStatus, RequestIds.current());
            throw new ApiException(HttpStatus.BAD_REQUEST, "SYSTEM_MODEL_PROVIDER_IN_USE", "模型供应商已被租户启用，请先取消租户模型分配");
        }
    }

    private void ensureCapabilityCanEnterStatus(UUID capabilityId, String targetStatus) {
        if ("draft".equals(targetStatus) && tenantCapabilityGrantRepository.existsByCapabilityIdAndStatus(capabilityId, "enabled")) {
            log.warn("系统管理更新能力失败：能力仍被租户启用 capabilityId={} targetStatus={} requestId={}", capabilityId, targetStatus, RequestIds.current());
            throw new ApiException(HttpStatus.BAD_REQUEST, "SYSTEM_CAPABILITY_IN_USE", "全局能力已被租户启用，请先取消租户能力配置");
        }
    }

    private void ensureModelProviderCanBeEnabled(ModelProviderEntity provider, String targetStatus) {
        if ("enabled".equals(targetStatus) && !"active".equals(provider.getStatus())) {
            log.warn(
                "系统管理模型分配失败：草稿模型供应商不能启用 providerId={} providerStatus={} requestId={}",
                provider.getId(),
                provider.getStatus(),
                RequestIds.current()
            );
            throw new ApiException(HttpStatus.BAD_REQUEST, "SYSTEM_MODEL_PROVIDER_NOT_ACTIVE", "模型供应商仍是草稿，请先将供应商状态改为可用");
        }
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

    private SystemManagementApi.TenantModelAssignmentRow toTenantModelAssignmentRow(TenantModelAssignmentEntity entity) {
        ModelProviderEntity provider = modelProviderRepository.findById(entity.getProviderId())
            .orElseThrow(() -> new IllegalStateException("模型分配记录指向的供应商缺失: " + entity.getProviderId()));
        return new SystemManagementApi.TenantModelAssignmentRow(
            entity.getId(),
            entity.getTenantId(),
            provider.getId(),
            provider.getName(),
            provider.getProviderType(),
            entity.getDefaultModel(),
            entity.getStatus()
        );
    }

    private SystemManagementApi.CapabilityTestResult testMcpConfig(SystemCapabilityEntity capability) {
        Map<String, Object> config = capability.getConfig();
        String sseUrl = stringValue(config.get("sseUrl"));
        if (sseUrl == null) {
            return new SystemManagementApi.CapabilityTestResult(capability.getId(), "failed", "SSE 类型 MCP 必须配置 sseUrl", List.of(), clock.instant());
        }

        // 系统管理阶段按 MCP 标准协议探测：SSE 建连后执行 initialize 与 tools/list，避免依赖各服务自定义 REST 预览接口。
        McpSseTestOutcome outcome = mcpSseConnectionTester.test(new McpSseTestRequest(capability.getId(), sseUrl));
        List<SystemManagementApi.CapabilityToolRow> tools = outcome.tools().stream()
            .map(this::toCapabilityToolRow)
            .toList();
        return new SystemManagementApi.CapabilityTestResult(
            capability.getId(),
            outcome.status(),
            outcome.summary(),
            tools,
            clock.instant()
        );
    }

    private SystemManagementApi.CapabilityToolRow toCapabilityToolRow(McpToolDescriptor tool) {
        return new SystemManagementApi.CapabilityToolRow(tool.name(), tool.description(), tool.inputSchema());
    }

    private SystemManagementApi.CapabilityTestResult testSourceConfig(SystemCapabilityEntity capability, String successSummary) {
        Map<String, Object> config = capability.getConfig();
        if (stringValue(config.get("sourcePath")) == null && stringValue(config.get("manifestPath")) == null) {
            return new SystemManagementApi.CapabilityTestResult(capability.getId(), "failed", "请配置 Skill 源码路径或 Manifest 路径，便于后续发布和测试", List.of(), clock.instant());
        }
        return new SystemManagementApi.CapabilityTestResult(capability.getId(), "success", successSummary, List.of(), clock.instant());
    }

    private SystemManagementApi.CapabilityTestResult testDeliveryConfig(SystemCapabilityEntity capability) {
        Map<String, Object> config = capability.getConfig();
        String sourceType = firstNonBlank(stringValue(config.get("sourceType")), "builtin");
        if ("custom".equals(sourceType)) {
            List<SystemManagementApi.CapabilityToolRow> tools = List.of(new SystemManagementApi.CapabilityToolRow(
                capability.getCode() + ".deliver",
                "调用自定义交付适配器，运行时必须经过权限、审批和审计链路",
                Map.of(
                    "type", "object",
                    "required", List.of("payload"),
                    "properties", Map.of(
                        "payload", Map.of("type", "object"),
                        "attachments", Map.of("type", "array", "items", Map.of("type", "string"))
                    )
                )
            ));
            return new SystemManagementApi.CapabilityTestResult(capability.getId(), "success", "自定义交付适配器配置检查通过，后续按统一协议调用并写入审计", tools, clock.instant());
        }
        if (!"email".equals(stringValue(config.get("deliveryChannel")))) {
            return new SystemManagementApi.CapabilityTestResult(capability.getId(), "failed", "系统内置交付当前只支持邮箱通道", List.of(), clock.instant());
        }
        List<SystemManagementApi.CapabilityToolRow> tools = List.of(new SystemManagementApi.CapabilityToolRow(
            capability.getCode() + ".send_email",
            "发送邮件正文与附件，运行时必须写入交付记录和审计日志",
            Map.of(
                "type", "object",
                "required", List.of("to", "subject", "body"),
                "properties", Map.of(
                    "to", Map.of("type", "array", "items", Map.of("type", "string")),
                    "subject", Map.of("type", "string"),
                    "body", Map.of("type", "string"),
                    "attachmentPaths", Map.of("type", "array", "items", Map.of("type", "string"))
                )
            )
        ));
        return new SystemManagementApi.CapabilityTestResult(capability.getId(), "success", "邮箱交付配置检查通过，可用于本地 Mailpit 或 SMTP 服务验证发件", tools, clock.instant());
    }

    private SystemManagementApi.CapabilityTestResult testPromptTemplateConfig(SystemCapabilityEntity capability) {
        if (stringValue(capability.getConfig().get("promptContent")) == null) {
            return new SystemManagementApi.CapabilityTestResult(capability.getId(), "failed", "请填写提示词内容，便于后续模板变量解析与渲染测试", List.of(), clock.instant());
        }
        return new SystemManagementApi.CapabilityTestResult(
            capability.getId(),
            "success",
            "提示词模板配置检查通过，后续接入模板变量解析与渲染测试",
            List.of(),
            clock.instant()
        );
    }

    private Map<String, Object> sanitizeCapabilityConfig(String capabilityType, Map<String, Object> rawConfig, Map<String, Object> existingConfig) {
        Map<String, Object> config = rawConfig == null ? Map.of() : rawConfig;
        if ("mcp".equals(capabilityType)) {
            String sseUrl = stringValue(config.get("sseUrl"));
            if (sseUrl == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "SYSTEM_MCP_SSE_URL_REQUIRED", "MCP SSE 地址不能为空");
            }
            Map<String, Object> result = new HashMap<>();
            result.put("transport", "sse");
            result.put("sseUrl", sseUrl);
            Object tools = config.get("tools");
            if (tools instanceof List<?>) {
                result.put("tools", tools);
            }
            return result;
        }
        if ("delivery".equals(capabilityType)) {
            String sourceType = firstNonBlank(stringValue(config.get("sourceType")), "builtin");
            if (!"builtin".equals(sourceType) && !"custom".equals(sourceType)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "SYSTEM_DELIVERY_SOURCE_TYPE_INVALID", "交付能力来源只能是 builtin 或 custom");
            }
            if ("custom".equals(sourceType)) {
                String implementationKey = requireConfig(config, "implementationKey", "自定义交付实现标识不能为空");
                String manifestPath = requireConfig(config, "manifestPath", "自定义交付 Manifest 路径不能为空");
                String protocol = firstNonBlank(stringValue(config.get("protocol")), "http");
                String endpointUrl = nullableString(config.get("endpointUrl"));
                Map<String, Object> result = new HashMap<>();
                result.put("sourceType", "custom");
                result.put("implementationKey", implementationKey);
                result.put("manifestPath", manifestPath);
                result.put("protocol", protocol);
                result.put("endpointUrl", endpointUrl);
                return result;
            }
            if (!"email".equals(stringValue(config.get("deliveryChannel")))) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "SYSTEM_DELIVERY_CHANNEL_INVALID", "系统内置交付当前只支持邮箱通道");
            }
            String smtpHost = requireConfig(config, "smtpHost", "SMTP 主机不能为空");
            int smtpPort = parsePort(config.get("smtpPort"));
            String fromAddress = requireConfig(config, "fromAddress", "发件邮箱不能为空");
            Map<String, Object> result = new HashMap<>();
            result.put("sourceType", "builtin");
            result.put("deliveryChannel", "email");
            result.put("smtpHost", smtpHost);
            result.put("smtpPort", smtpPort);
            result.put("smtpUsername", nullableString(config.get("smtpUsername")));
            result.put("fromAddress", fromAddress);
            result.put("useTls", booleanValue(config.get("useTls")));
            String smtpPassword = stringValue(config.get("smtpPassword"));
            if (smtpPassword != null) {
                result.put("encryptedSmtpPassword", fieldEncryptionService.encrypt(smtpPassword));
            } else if (existingConfig != null && existingConfig.get("encryptedSmtpPassword") != null) {
                result.put("encryptedSmtpPassword", existingConfig.get("encryptedSmtpPassword"));
            }
            return result;
        }
        if ("prompt_template".equals(capabilityType)) {
            return Map.of("promptContent", nullableString(config.get("promptContent")));
        }
        return Map.of(
            "sourcePath", nullableString(config.get("sourcePath")),
            "manifestPath", nullableString(config.get("manifestPath"))
        );
    }

    private static String normalizeVersion(String version) {
        String normalized = normalizeOptional(version);
        return normalized.isBlank() ? "v1" : normalized;
    }

    private static String normalizeOptional(String value) {
        return value == null ? "" : value.trim();
    }

    private static String firstNonBlank(String value, String fallback) {
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        return fallback == null || fallback.isBlank() ? null : fallback.trim();
    }

    private static String requireDefaultModel(String defaultModel) {
        String value = stringValue(defaultModel);
        if (value == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SYSTEM_MODEL_DEFAULT_MODEL_REQUIRED", "默认模型不能为空");
        }
        return value;
    }

    private static String requireConfig(Map<String, Object> config, String key, String message) {
        String value = stringValue(config.get(key));
        if (value == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SYSTEM_CAPABILITY_CONFIG_REQUIRED", message);
        }
        return value;
    }

    private static int parsePort(Object value) {
        String text = stringValue(value);
        if (text == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SYSTEM_DELIVERY_SMTP_PORT_REQUIRED", "SMTP 端口不能为空");
        }
        try {
            int port = Integer.parseInt(text);
            if (port <= 0 || port > 65535) {
                throw new NumberFormatException("out of range");
            }
            return port;
        } catch (NumberFormatException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SYSTEM_DELIVERY_SMTP_PORT_INVALID", "SMTP 端口必须是 1 到 65535 之间的数字");
        }
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        String text = stringValue(value);
        return text != null && ("true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text));
    }

    private static String nullableString(Object value) {
        String text = stringValue(value);
        return text == null ? "" : text;
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static SystemManagementApi.ModelProviderRow toModelRow(ModelProviderEntity entity) {
        return new SystemManagementApi.ModelProviderRow(
            entity.getId(),
            entity.getName(),
            entity.getProviderType(),
            entity.getBaseUrl(),
            entity.getDefaultModel(),
            entity.hasCredentialRef(),
            entity.getStatus()
        );
    }

    private static SystemManagementApi.ModelProviderTypeRow toModelProviderTypeRow(ModelProviderTypeEntity entity) {
        return new SystemManagementApi.ModelProviderTypeRow(
            entity.getCode(),
            entity.getName(),
            entity.getDescription(),
            entity.getAuthScheme(),
            entity.getDefaultBaseUrl(),
            entity.getModelListEndpoint()
        );
    }

    private static SystemManagementApi.CapabilityRow toCapabilityRow(SystemCapabilityEntity entity) {
        return new SystemManagementApi.CapabilityRow(
            entity.getId(),
            entity.getCapabilityType(),
            entity.getName(),
            entity.getCode(),
            entity.getVersion(),
            entity.getDescription(),
            entity.getRiskLevel(),
            entity.getStatus(),
            publicCapabilityConfig(entity.getCapabilityType(), entity.getConfig())
        );
    }

    private static Map<String, Object> publicCapabilityConfig(String capabilityType, Map<String, Object> config) {
        if (!"delivery".equals(capabilityType)) {
            return config;
        }
        Map<String, Object> publicConfig = new HashMap<>(config);
        boolean passwordConfigured = publicConfig.remove("encryptedSmtpPassword") != null;
        publicConfig.remove("smtpPassword");
        publicConfig.put("smtpPasswordConfigured", passwordConfigured);
        return publicConfig;
    }
}
