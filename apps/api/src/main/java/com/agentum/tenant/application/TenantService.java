package com.agentum.tenant.application;

import com.agentum.tenant.infrastructure.TenantRepository;
import com.agentum.tenant.interfaces.TenantOptionResponse;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantService {

    private static final String ACTIVE_STATUS = "active";

    private final TenantRepository tenantRepository;

    public TenantService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Transactional(readOnly = true)
    public List<TenantOptionResponse> listActiveTenants() {
        return tenantRepository.findByStatusOrderByNameAsc(ACTIVE_STATUS).stream()
            .map(tenant -> new TenantOptionResponse(tenant.getId().toString(), tenant.getName(), tenant.getCode()))
            .toList();
    }
}
