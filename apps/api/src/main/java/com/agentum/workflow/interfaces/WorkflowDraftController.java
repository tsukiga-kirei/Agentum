package com.agentum.workflow.interfaces;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.shared.api.ApiResponse;
import com.agentum.shared.api.RequestIds;
import com.agentum.shared.pagination.PageResponse;
import com.agentum.workflow.application.WorkflowDesignAccess;
import com.agentum.workflow.application.WorkflowDesignerCatalogService;
import com.agentum.workflow.application.WorkflowDraftService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final WorkflowDesignerCatalogService workflowDesignerCatalogService;
    private final WorkflowDraftService workflowDraftService;

    public WorkflowDraftController(
        WorkflowDesignAccess workflowDesignAccess,
        WorkflowDesignerCatalogService workflowDesignerCatalogService,
        WorkflowDraftService workflowDraftService
    ) {
        this.workflowDesignAccess = workflowDesignAccess;
        this.workflowDesignerCatalogService = workflowDesignerCatalogService;
        this.workflowDraftService = workflowDraftService;
    }

    @GetMapping("/designer-catalog")
    public ApiResponse<WorkflowDraftApi.WorkflowDesignerCatalog> getDesignerCatalog(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        workflowDesignAccess.assertCanDesign(principal, tenantId);
        return ApiResponse.success(workflowDesignerCatalogService.getCatalog(tenantId), RequestIds.current(request));
    }

    @GetMapping
    public ApiResponse<PageResponse<WorkflowDraftApi.WorkflowDraftRow>> listDrafts(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @RequestParam(defaultValue = "") String keyword,
        @RequestParam(defaultValue = "all") String scope,
        @RequestParam(defaultValue = "all") String status,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(defaultValue = "updatedAt,desc") String sort,
        HttpServletRequest request
    ) {
        workflowDesignAccess.assertCanDesign(principal, tenantId);
        return ApiResponse.success(workflowDraftService.listDrafts(tenantId, principal.userId(), keyword, scope, status, page, size, sort), RequestIds.current(request));
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

    @PostMapping("/{workflowId}/copy")
    public ApiResponse<WorkflowDraftApi.WorkflowDraftRow> copyDraft(
        @PathVariable UUID tenantId,
        @PathVariable UUID workflowId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        workflowDesignAccess.assertCanDesign(principal, tenantId);
        return ApiResponse.success(workflowDraftService.copyDraft(tenantId, principal.userId(), workflowId), RequestIds.current(request));
    }

    @GetMapping("/{workflowId}/export")
    public ApiResponse<WorkflowDraftApi.WorkflowExportDocument> exportDraft(
        @PathVariable UUID tenantId,
        @PathVariable UUID workflowId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        workflowDesignAccess.assertCanDesign(principal, tenantId);
        return ApiResponse.success(workflowDraftService.exportDraft(tenantId, principal.userId(), workflowId), RequestIds.current(request));
    }

    @PostMapping("/imports")
    public ApiResponse<WorkflowDraftApi.WorkflowDraftDetail> importDraft(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @Valid @RequestBody WorkflowDraftApi.ImportWorkflowDraftRequest body,
        HttpServletRequest request
    ) {
        workflowDesignAccess.assertCanDesign(principal, tenantId);
        // 导入只创建当前操作者拥有的新草稿；能力引用、变量和发布约束仍由后端保存/发布链路复核。
        return ApiResponse.success(workflowDraftService.importDraft(tenantId, principal.userId(), body), RequestIds.current(request));
    }

    @GetMapping("/shareable-members")
    public ApiResponse<List<WorkflowDraftApi.ShareableMemberRow>> listShareableMembers(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        workflowDesignAccess.assertCanDesign(principal, tenantId);
        return ApiResponse.success(workflowDraftService.listShareableMembers(tenantId, principal.userId()), RequestIds.current(request));
    }

    @GetMapping("/{workflowId}")
    public ApiResponse<WorkflowDraftApi.WorkflowDraftDetail> getDraft(
        @PathVariable UUID tenantId,
        @PathVariable UUID workflowId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        workflowDesignAccess.assertCanDesign(principal, tenantId);
        return ApiResponse.success(workflowDraftService.getDraft(tenantId, principal.userId(), workflowId), RequestIds.current(request));
    }

    @PutMapping("/{workflowId}")
    public ApiResponse<WorkflowDraftApi.WorkflowDraftDetail> updateDraft(
        @PathVariable UUID tenantId,
        @PathVariable UUID workflowId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @Valid @RequestBody WorkflowDraftApi.UpdateWorkflowDraftRequest body,
        HttpServletRequest request
    ) {
        workflowDesignAccess.assertCanDesign(principal, tenantId);
        return ApiResponse.success(workflowDraftService.updateDraft(tenantId, principal.userId(), workflowId, body), RequestIds.current(request));
    }

    @PutMapping("/{workflowId}/access")
    public ApiResponse<WorkflowDraftApi.WorkflowDraftDetail> updateAccess(
        @PathVariable UUID tenantId,
        @PathVariable UUID workflowId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @Valid @RequestBody WorkflowDraftApi.UpdateWorkflowAccessRequest body,
        HttpServletRequest request
    ) {
        workflowDesignAccess.assertCanDesign(principal, tenantId);
        return ApiResponse.success(workflowDraftService.updateAccess(tenantId, principal.userId(), workflowId, body), RequestIds.current(request));
    }

    @PostMapping("/{workflowId}/publish-validation")
    public ApiResponse<WorkflowDraftApi.WorkflowPublishValidationResult> validateForPublish(
        @PathVariable UUID tenantId,
        @PathVariable UUID workflowId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        workflowDesignAccess.assertCanDesign(principal, tenantId);
        // 发布前先返回结构化问题列表，便于设计者在画布中修复；真正发布时仍需再次执行同一组规则。
        return ApiResponse.success(workflowDraftService.validateForPublish(tenantId, principal.userId(), workflowId), RequestIds.current(request));
    }

    @PostMapping("/{workflowId}/publish")
    public ApiResponse<WorkflowDraftApi.WorkflowPublishResult> publish(
        @PathVariable UUID tenantId,
        @PathVariable UUID workflowId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        workflowDesignAccess.assertCanDesign(principal, tenantId);
        // 正式发布会重新执行后端校验并冻结版本快照，不能依赖前端刚刚展示过的校验结果。
        return ApiResponse.success(workflowDraftService.publish(tenantId, principal.userId(), workflowId), RequestIds.current(request));
    }

    @PostMapping("/{workflowId}/recall-launch")
    public ApiResponse<WorkflowDraftApi.WorkflowDraftDetail> recallLaunch(
        @PathVariable UUID tenantId,
        @PathVariable UUID workflowId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        workflowDesignAccess.assertCanDesign(principal, tenantId);
        return ApiResponse.success(workflowDraftService.recallLaunch(tenantId, principal.userId(), workflowId), RequestIds.current(request));
    }

    @PostMapping("/{workflowId}/restore-launch")
    public ApiResponse<WorkflowDraftApi.WorkflowDraftDetail> restoreLaunch(
        @PathVariable UUID tenantId,
        @PathVariable UUID workflowId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        workflowDesignAccess.assertCanDesign(principal, tenantId);
        return ApiResponse.success(workflowDraftService.restoreLaunch(tenantId, principal.userId(), workflowId), RequestIds.current(request));
    }

    @DeleteMapping("/{workflowId}")
    public ApiResponse<Void> deleteDraft(
        @PathVariable UUID tenantId,
        @PathVariable UUID workflowId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        workflowDesignAccess.assertCanDesign(principal, tenantId);
        workflowDraftService.deleteDraft(tenantId, principal.userId(), workflowId);
        return ApiResponse.success(null, RequestIds.current(request));
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
