package com.agentum.mcp.interfaces;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.mcp.application.McpDatasetService;
import com.agentum.shared.api.RequestIds;
import com.agentum.workbench.application.WorkbenchAccess;
import com.agentum.workbench.application.WorkbenchRuntimeService;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 完整工具结果只通过流程授权下载，不把对象地址或完整行注入会话。 */
@RestController
@RequestMapping("/api/tenants/{tenantId}/workbench/runs/{runId}/mcp-results")
public class McpResultController {
    private final WorkbenchAccess access;
    private final WorkbenchRuntimeService runs;
    private final McpDatasetService results;
    public McpResultController(WorkbenchAccess access, WorkbenchRuntimeService runs, McpDatasetService results) {
        this.access = access; this.runs = runs; this.results = results;
    }
    @GetMapping("/{id}/download")
    public ResponseEntity<ByteArrayResource> download(@PathVariable UUID tenantId, @PathVariable UUID runId, @PathVariable UUID id,
        @AuthenticationPrincipal CurrentUserPrincipal user) {
        access.assertCanAccessWorkbenchOrSchedule(user, tenantId);
        // 先复核整个运行的查看权限，再检查结果的租户、运行及 MCP 能力，UUID 不能代替授权。
        runs.getRunDetail(tenantId, user, runId);
        byte[] bytes = results.download(tenantId, runId, user.userId(), id.toString());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("mcp-result.json").build().toString())
            .header("X-Request-Id", RequestIds.current()).contentLength(bytes.length).body(new ByteArrayResource(bytes));
    }
}
