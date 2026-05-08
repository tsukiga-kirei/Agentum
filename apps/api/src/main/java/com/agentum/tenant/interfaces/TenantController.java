package com.agentum.tenant.interfaces;

import com.agentum.shared.api.ApiResponse;
import com.agentum.shared.api.RequestIds;
import com.agentum.tenant.application.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/tenants")
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @GetMapping
    public ApiResponse<List<TenantOptionResponse>> listActiveTenants(HttpServletRequest request) {
        // 公开租户列表只服务当前内网/本地登录体验；公网 SaaS 模式下应改为编码或域名识别。
        return ApiResponse.success(tenantService.listActiveTenants(), RequestIds.current(request));
    }
}
