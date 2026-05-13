package com.agentum.workflow.interfaces;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.shared.api.ApiResponse;
import com.agentum.shared.api.RequestIds;
import com.agentum.shared.pagination.PageResponse;
import com.agentum.workflow.application.WorkflowDesignAccess;
import com.agentum.workflow.application.WorkflowDraftService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tenants/{tenantId}/workflows/drafts")
public class WorkflowDraftController {

    private final WorkflowDesignAccess workflowDesignAccess;
    private final WorkflowDraftService workflowDraftService;

    public WorkflowDraftController(WorkflowDesignAccess workflowDesignAccess, WorkflowDraftService workflowDraftService) {
        this.workflowDesignAccess = workflowDesignAccess;
        this.workflowDraftService = workflowDraftService;
    }

    @GetMapping
    public ApiResponse<PageResponse<WorkflowDraftApi.WorkflowDraftRow>> listDrafts(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @RequestParam(defaultValue = "") String keyword,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(defaultValue = "updatedAt,desc") String sort,
        HttpServletRequest request
    ) {
        workflowDesignAccess.assertCanDesign(principal, tenantId);
        return ApiResponse.success(workflowDraftService.listDrafts(tenantId, keyword, page, size, sort), RequestIds.current(request));
    }

    @PostMapping
    public ApiResponse<WorkflowDraftApi.WorkflowDraftRow> createDraft(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @Valid @RequestBody WorkflowDraftApi.CreateWorkflowDraftRequest createRequest,
        HttpServletRequest request
    ) {
        workflowDesignAccess.assertCanDesign(principal, tenantId);
        return ApiResponse.success(workflowDraftService.createDraft(tenantId, principal.userId(), createRequest), RequestIds.current(request));
    }

    @GetMapping("/{workflowId}")
    public ApiResponse<WorkflowDraftApi.WorkflowDraftDetail> getDraft(
        @PathVariable UUID tenantId,
        @PathVariable UUID workflowId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        workflowDesignAccess.assertCanDesign(principal, tenantId);
        return ApiResponse.success(workflowDraftService.getDraft(tenantId, workflowId), RequestIds.current(request));
    }

    @PutMapping("/{workflowId}/graph")
    public ApiResponse<WorkflowDraftApi.WorkflowDraftDetail> saveGraph(
        @PathVariable UUID tenantId,
        @PathVariable UUID workflowId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @Valid @RequestBody WorkflowDraftApi.SaveWorkflowDraftGraphRequest saveRequest,
        HttpServletRequest request
    ) {
        workflowDesignAccess.assertCanDesign(principal, tenantId);
        // 设计态保存只表达草稿意图；发布校验和运行时仍会重新校验节点协议、变量引用和能力授权。
        return ApiResponse.success(workflowDraftService.saveGraph(tenantId, principal.userId(), workflowId, saveRequest), RequestIds.current(request));
    }
}
