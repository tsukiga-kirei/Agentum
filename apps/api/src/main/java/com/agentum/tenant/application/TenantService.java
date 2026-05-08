package com.agentum.tenant.application;

import com.agentum.tenant.infrastructure.TenantRepository;
import com.agentum.tenant.interfaces.TenantOptionResponse;
import com.agentum.shared.api.RequestIds;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantService {

    private static final Logger log = LoggerFactory.getLogger(TenantService.class);
    private static final String ACTIVE_STATUS = "active";

    private final TenantRepository tenantRepository;

    public TenantService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Transactional(readOnly = true)
    public List<TenantOptionResponse> listActiveTenants() {
        // 公开租户列表仅面向当前本地 / 内网阶段；公网 SaaS 后续应改为租户编码、邮箱域名或子域名识别。
        List<TenantOptionResponse> tenants = tenantRepository.findByStatusOrderByNameAsc(ACTIVE_STATUS).stream()
            .map(tenant -> new TenantOptionResponse(tenant.getId().toString(), tenant.getName(), tenant.getCode()))
            .toList();
        log.debug("公开租户列表查询完成 activeTenantCount={} requestId={}", tenants.size(), RequestIds.current());
        return tenants;
    }
}
