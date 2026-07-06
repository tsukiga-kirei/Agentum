package com.agentum.schedule.interfaces;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.schedule.application.WorkflowScheduleService;
import com.agentum.shared.api.ApiResponse;
import com.agentum.shared.api.RequestIds;
import com.agentum.shared.pagination.PageResponse;
import com.agentum.workbench.application.WorkbenchAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tenants/{tenantId}/workbench/schedules")
public class WorkflowScheduleController {

    private final WorkbenchAccess workbenchAccess;
    private final WorkflowScheduleService scheduleService;

    public WorkflowScheduleController(WorkbenchAccess workbenchAccess, WorkflowScheduleService scheduleService) {
        this.workbenchAccess = workbenchAccess;
        this.scheduleService = scheduleService;
    }

    @GetMapping
    public ApiResponse<PageResponse<WorkflowScheduleApi.ScheduleRow>> list(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @RequestParam(defaultValue = "") String keyword,
        @RequestParam(defaultValue = "") String status,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(defaultValue = "updatedAt,desc") String sort,
        HttpServletRequest request
    ) {
        workbenchAccess.assertCanAccessSchedule(principal, tenantId);
        return ApiResponse.success(scheduleService.list(tenantId, principal, keyword, status, page, size, sort), RequestIds.current(request));
    }

    @GetMapping("/workflows/{workflowId}/input-fields")
    public ApiResponse<WorkflowScheduleApi.WorkflowInputFieldsResponse> inputFields(
        @PathVariable UUID tenantId,
        @PathVariable UUID workflowId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        workbenchAccess.assertCanAccessSchedule(principal, tenantId);
        return ApiResponse.success(scheduleService.inputFields(tenantId, principal, workflowId), RequestIds.current(request));
    }

    @PostMapping
    public ApiResponse<WorkflowScheduleApi.ScheduleRow> create(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @RequestBody WorkflowScheduleApi.CreateScheduleRequest body,
        HttpServletRequest request
    ) {
        workbenchAccess.assertCanAccessSchedule(principal, tenantId);
        return ApiResponse.success(scheduleService.create(tenantId, principal, body), RequestIds.current(request));
    }

    @PutMapping("/{scheduleId}")
    public ApiResponse<WorkflowScheduleApi.ScheduleRow> update(
        @PathVariable UUID tenantId,
        @PathVariable UUID scheduleId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @RequestBody WorkflowScheduleApi.UpdateScheduleRequest body,
        HttpServletRequest request
    ) {
        workbenchAccess.assertCanAccessSchedule(principal, tenantId);
        return ApiResponse.success(scheduleService.update(tenantId, principal, scheduleId, body), RequestIds.current(request));
    }

    @PatchMapping("/{scheduleId}/status")
    public ApiResponse<WorkflowScheduleApi.ScheduleRow> updateStatus(
        @PathVariable UUID tenantId,
        @PathVariable UUID scheduleId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @RequestBody WorkflowScheduleApi.UpdateScheduleRequest body,
        HttpServletRequest request
    ) {
        workbenchAccess.assertCanAccessSchedule(principal, tenantId);
        return ApiResponse.success(
            scheduleService.updateStatus(tenantId, principal, scheduleId, body == null ? "" : body.status()),
            RequestIds.current(request)
        );
    }

    @DeleteMapping("/{scheduleId}")
    public ApiResponse<Void> delete(
        @PathVariable UUID tenantId,
        @PathVariable UUID scheduleId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        workbenchAccess.assertCanAccessSchedule(principal, tenantId);
        scheduleService.delete(tenantId, principal, scheduleId);
        return ApiResponse.success(null, RequestIds.current(request));
    }

    @PostMapping("/{scheduleId}/trigger")
    public ApiResponse<WorkflowScheduleApi.TriggerScheduleResponse> trigger(
        @PathVariable UUID tenantId,
        @PathVariable UUID scheduleId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        workbenchAccess.assertCanAccessSchedule(principal, tenantId);
        return ApiResponse.success(scheduleService.triggerNow(tenantId, principal, scheduleId), RequestIds.current(request));
    }

    @GetMapping("/{scheduleId}/executions")
    public ApiResponse<PageResponse<WorkflowScheduleApi.ScheduleExecutionRow>> executions(
        @PathVariable UUID tenantId,
        @PathVariable UUID scheduleId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int size,
        HttpServletRequest request
    ) {
        workbenchAccess.assertCanAccessSchedule(principal, tenantId);
        return ApiResponse.success(scheduleService.executions(tenantId, principal, scheduleId, page, size), RequestIds.current(request));
    }
}
