package com.agentum.audit.interfaces;

import com.agentum.audit.application.AuditEvidenceDto;
import com.agentum.audit.application.AuditOperationLogDto;
import com.agentum.audit.application.AuditRunSummaryDto;
import com.agentum.audit.application.AuditService;
import com.agentum.audit.application.AuditToolCallDto;
import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.permission.application.BusinessPageAccess;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.ApiResponse;
import com.agentum.shared.api.RequestIds;
import com.agentum.shared.pagination.PageQuery;
import com.agentum.shared.pagination.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行审计与配置改动审计 HTTP 接口控制器。
 */
@RestController
@RequestMapping("/api/tenants/{tenantId}/audit")
public class AuditController {

    private final AuditService auditService;
    private final BusinessPageAccess businessPageAccess;

    public AuditController(AuditService auditService, BusinessPageAccess businessPageAccess) {
        this.auditService = auditService;
        this.businessPageAccess = businessPageAccess;
    }

    /**
     * 校验是否有审计模块的访问权限。
     * 系统管理员与租户管理员默认可访问；业务用户必须被授权了 audit 页签。
     */
    private void assertAuditAccess(CurrentUserPrincipal principal, UUID tenantId) {
        if (principal == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "访问审计模块需要登录");
        }
        if ("system_admin".equals(principal.role())) {
            // 系统管理员允许进行跨租户审计访问
            return;
        }
        if ("tenant_admin".equals(principal.role())) {
            // 租户管理员允许审计本租户
            if (!tenantId.equals(principal.tenantId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "AUDIT_CROSS_TENANT_DENIED", "禁止跨租户访问审计数据");
            }
            return;
        }
        // 普通业务角色，验证租户内分配的 audit 页签权限
        if (!tenantId.equals(principal.tenantId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "AUDIT_CROSS_TENANT_DENIED", "禁止跨租户访问审计数据");
        }
        if (!businessPageAccess.hasPageGrant(tenantId, principal.userId(), "audit")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "AUDIT_ACCESS_DENIED", "您没有访问运行审计模块的权限");
        }
    }

    /**
     * 查询运行审计列表。
     */
    @GetMapping("/runs")
    public ApiResponse<PageResponse<AuditRunSummaryDto>> listRuns(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @RequestParam(defaultValue = "") String keyword,
        @RequestParam(defaultValue = "") String state,
        @RequestParam(defaultValue = "") String triggerSource,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "startedAt,desc") String sort,
        HttpServletRequest request
    ) {
        assertAuditAccess(principal, tenantId);
        PageQuery pageQuery = PageQuery.of(page, size, sort);
        PageResponse<AuditRunSummaryDto> response = auditService.getRunAuditList(tenantId, pageQuery, keyword, state, triggerSource);
        return ApiResponse.success(response, RequestIds.current(request));
    }

    /**
     * 获取全链路运行证据链详情 (只读证据链)。
     */
    @GetMapping("/runs/{runId}/evidence")
    public ApiResponse<AuditEvidenceDto> getEvidence(
        @PathVariable UUID tenantId,
        @PathVariable UUID runId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        assertAuditAccess(principal, tenantId);
        AuditEvidenceDto evidence = auditService.getRunEvidence(tenantId, runId);
        return ApiResponse.success(evidence, RequestIds.current(request));
    }

    /**
     * 查询外部工具（MCP、Skill、模型）调用审计台账。
     */
    @GetMapping("/tools")
    public ApiResponse<PageResponse<AuditToolCallDto>> listToolCalls(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @RequestParam(defaultValue = "all") String toolType,
        @RequestParam(defaultValue = "") String status,
        @RequestParam(defaultValue = "") String keyword,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "createdAt,desc") String sort,
        HttpServletRequest request
    ) {
        assertAuditAccess(principal, tenantId);
        PageQuery pageQuery = PageQuery.of(page, size, sort);
        PageResponse<AuditToolCallDto> response = auditService.getToolCallAuditList(tenantId, pageQuery, toolType, status, keyword);
        return ApiResponse.success(response, RequestIds.current(request));
    }

    /**
     * 查询管理和配置变动操作审计日志。
     */
    @GetMapping("/operations")
    public ApiResponse<PageResponse<AuditOperationLogDto>> listOperationLogs(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @RequestParam(defaultValue = "") String actionType,
        @RequestParam(required = false) UUID operatorId,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "createdAt,desc") String sort,
        HttpServletRequest request
    ) {
        assertAuditAccess(principal, tenantId);
        PageQuery pageQuery = PageQuery.of(page, size, sort);
        PageResponse<AuditOperationLogDto> response = auditService.getOperationLogs(tenantId, pageQuery, actionType, operatorId);
        return ApiResponse.success(response, RequestIds.current(request));
    }
}
