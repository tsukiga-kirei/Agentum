package com.agentum.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentum.mcp.domain.McpCallLogEntity;
import com.agentum.mcp.infrastructure.McpCallLogRepository;
import com.agentum.shared.api.ApiException;
import com.agentum.system.domain.SystemCapabilityEntity;
import com.agentum.system.domain.TenantCapabilityGrantEntity;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import com.agentum.system.infrastructure.TenantCapabilityGrantRepository;
import com.agentum.workflow.domain.WorkflowNodeRunEntity;
import com.agentum.workflow.domain.WorkflowRunEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpRuntimeServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-18T08:00:00Z");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    private final SystemCapabilityRepository capabilityRepository = mock(SystemCapabilityRepository.class);
    private final TenantCapabilityGrantRepository grantRepository = mock(TenantCapabilityGrantRepository.class);
    private final McpCallLogRepository callLogRepository = mock(McpCallLogRepository.class);
    private final McpRuntimeClient runtimeClient = mock(McpRuntimeClient.class);
    private final WorkflowRunEntity run = mock(WorkflowRunEntity.class);
    private final WorkflowNodeRunEntity nodeRun = mock(WorkflowNodeRunEntity.class);
    private final List<McpCallLogEntity> savedLogs = new ArrayList<>();

    private final McpDatasetService datasets = mock(McpDatasetService.class);
    private McpRuntimeService service;
    private SystemCapabilityEntity capability;

    @BeforeEach
    void setUp() {
        Map<String, Object> pdtSchema = Map.of(
            "type", "object",
            "properties", Map.of("pdt", Map.of("type", "string", "pattern", "^\\d{8}$")),
            "required", List.of("pdt")
        );
        capability = SystemCapabilityEntity.create(
            "mcp",
            "金融月报",
            "cap_financial_report",
            "v1",
            "金融业务工作报告 MCP",
            "medium",
            "active",
            Map.of(
                "transport", "streamable_http",
                "endpointUrl", "http://127.0.0.1:3001/mcp",
                "tools", List.of(
                    Map.of(
                        "name", "stale_tool_name",
                        "description", "系统管理中的旧预览快照",
                        "inputSchema", pdtSchema
                    )
                )
            ),
            NOW
        );
        TenantCapabilityGrantEntity grant = TenantCapabilityGrantEntity.create(TENANT_ID, capability.getId(), "enabled", NOW);
        when(run.getTenantId()).thenReturn(TENANT_ID);
        when(run.getId()).thenReturn(UUID.randomUUID());
        when(run.getWorkflowId()).thenReturn(UUID.randomUUID());
        when(run.getWorkflowVersionId()).thenReturn(UUID.randomUUID());
        when(nodeRun.getId()).thenReturn(UUID.randomUUID());
        when(capabilityRepository.findById(capability.getId())).thenReturn(Optional.of(capability));
        when(grantRepository.findByTenantIdAndCapabilityId(TENANT_ID, capability.getId())).thenReturn(Optional.of(grant));
        when(callLogRepository.save(any(McpCallLogEntity.class))).thenAnswer(invocation -> {
            McpCallLogEntity log = invocation.getArgument(0);
            savedLogs.add(log);
            return log;
        });
        service = new McpRuntimeService(
            capabilityRepository,
            grantRepository,
            callLogRepository,
            runtimeClient,
            Clock.fixed(NOW, ZoneOffset.UTC), datasets, new com.fasterxml.jackson.databind.ObjectMapper(), mock(com.agentum.runtime.cancel.RunCancellationGuard.class)
        );
        when(runtimeClient.listTools(any())).thenReturn(new McpRuntimeClient.ToolListResult(
            List.of(
                new McpRuntimeClient.ToolDescriptor("get_financial_work_report_core_kpi", "获取核心经营指标", pdtSchema),
                new McpRuntimeClient.ToolDescriptor("get_financial_work_report_risk_indicators", "获取监管风险指标", pdtSchema)
            ),
            12L
        ));
    }

    @Test
    void shouldExposeEveryRuntimeDiscoveredToolInsteadOfSavedSnapshot() {
        List<McpRuntimeService.McpToolBinding> bindings = service.resolveMcpTools(request());

        assertThat(bindings).extracting(McpRuntimeService.McpToolBinding::remoteToolName).containsExactly(
            "get_financial_work_report_core_kpi",
            "get_financial_work_report_risk_indicators"
        );
        assertThat(bindings.getFirst().parameters()).containsEntry("required", List.of("pdt"));
        assertThat(bindings.getFirst().parameters().get("properties").toString()).contains("pdt");
    }

    @Test
    void shouldDiscoverChangedToolListAgainForEveryAgentTurn() {
        when(runtimeClient.listTools(any()))
            .thenReturn(new McpRuntimeClient.ToolListResult(
                List.of(new McpRuntimeClient.ToolDescriptor("old_tool", "旧工具", Map.of("type", "object"))),
                8L
            ))
            .thenReturn(new McpRuntimeClient.ToolListResult(
                List.of(new McpRuntimeClient.ToolDescriptor("new_tool", "新工具", Map.of("type", "object"))),
                9L
            ));

        List<McpRuntimeService.McpToolBinding> firstTurn = service.resolveMcpTools(request());
        List<McpRuntimeService.McpToolBinding> secondTurn = service.resolveMcpTools(request());

        assertThat(firstTurn).extracting(McpRuntimeService.McpToolBinding::remoteToolName).containsExactly("old_tool");
        assertThat(secondTurn).extracting(McpRuntimeService.McpToolBinding::remoteToolName).containsExactly("new_tool");
    }

    @Test
    void shouldRejectEmptyRuntimeToolListWithoutFallingBackToSavedSnapshot() {
        when(runtimeClient.listTools(any())).thenReturn(new McpRuntimeClient.ToolListResult(List.of(), 7L));

        assertThatThrownBy(() -> service.resolveMcpTools(request()))
            .isInstanceOf(ApiException.class)
            .satisfies(error -> assertThat(((ApiException) error).getCode()).isEqualTo("MCP_TOOL_LIST_EMPTY"));
    }

    @Test
    void shouldTreatMcpIsErrorResultAsFailedCall() {
        McpRuntimeService.McpToolBinding binding = service.resolveMcpTools(request()).getFirst();
        when(runtimeClient.callTool(any())).thenReturn(new McpRuntimeClient.ToolResult(
            Map.of(
                "isError", true,
                "text", "MCP error -32602: Tool not found"
            ),
            22L
        ));

        assertThatThrownBy(() -> service.executeResolvedTool(request(), binding, Map.of("pdt", "20260408")))
            .isInstanceOf(ApiException.class)
            .satisfies(error -> assertThat(((ApiException) error).getCode()).isEqualTo("MCP_TOOL_EXECUTION_FAILED"));

        assertThat(savedLogs).isNotEmpty();
        assertThat(savedLogs.getLast().getStatus()).isEqualTo("failed");
        assertThat(savedLogs.getLast().getErrorCode()).isEqualTo("MCP_TOOL_EXECUTION_FAILED");
        assertThat(savedLogs.getLast().getToolName()).isEqualTo("get_financial_work_report_core_kpi");
    }

    @Test
    void shouldRecursivelySanitizeSuccessfulMcpResponseBeforeReturningAndAuditing() {
        McpRuntimeService.McpToolBinding binding = service.resolveMcpTools(request()).getFirst();
        when(runtimeClient.callTool(any())).thenReturn(new McpRuntimeClient.ToolResult(
            Map.of(
                "isError", false,
                "structuredContent", Map.of(
                    "token", "top-secret-token",
                    "rows", List.of(Map.of(
                        "indicator", "利润总额",
                        "credentials", Map.of("apiKey", "nested-secret-key")
                    ))
                )
            ),
            18L
        ));

        McpRuntimeService.ExecutedMcpTool result = service.executeResolvedTool(
            request(),
            binding,
            Map.of("pdt", "20260408")
        );

        assertThat(result.responsePayload().toString())
            .contains("利润总额", "***")
            .doesNotContain("top-secret-token", "nested-secret-key");
        assertThat(savedLogs.getLast().getStatus()).isEqualTo("success");
        assertThat(savedLogs.getLast().getResponsePayload().toString())
            .doesNotContain("top-secret-token", "nested-secret-key");
    }

    private McpRuntimeRequest request() {
        return new McpRuntimeRequest(
            run,
            nodeRun,
            Map.of("mcpIds", List.of(capability.getId().toString())),
            Map.of(),
            UUID.randomUUID()
        );
    }
    @Test void collectsPagesOutsideModelAndLogsOnlyReference() {
        var binding = service.resolveMcpTools(request()).getFirst();
        var policy = Map.<String, Object>of("mode", "dataset", "rowsPath", "$.data.records", "hasMorePath", "$.hasMore", "totalPath", "$.total", "pagination", Map.of("type", "page", "pageSize", 1));
        var config = new java.util.HashMap<>(capability.getConfig()); config.put("toolResultPolicies", Map.of(binding.remoteToolName(), policy));
        capability.updateProfile("mcp", capability.getName(), "v1", "", "medium", "active", config, true, NOW);
        when(runtimeClient.callTool(any())).thenReturn(
            new McpRuntimeClient.ToolResult(Map.of("structuredContent", Map.of("data", Map.of("records", List.of(Map.of("balance", 10))), "total", 2, "hasMore", true)), 1),
            new McpRuntimeClient.ToolResult(Map.of("content", List.of(Map.of("type", "text", "text", "原始敏感明细")), "structuredContent", Map.of("data", Map.of("records", List.of(Map.of("balance", 20))), "total", 2, "hasMore", false)), 1));
        when(datasets.save(any(), any(), any(), any(), any(), any())).thenReturn(Map.of("datasetId", "ref", "rowCount", 2));
        var result = service.executeResolvedTool(request(), binding, Map.of("pdt", "20260831"));
        assertThat(result.responsePayload()).containsEntry("rowCount", 2).doesNotContainKey("structuredContent");
        assertThat(savedLogs.getLast().getResponsePayload().toString()).doesNotContain("balance", "原始敏感明细");
        org.mockito.Mockito.verify(datasets).save(any(), any(), any(), any(), org.mockito.ArgumentMatchers.argThat(rows -> rows.size() == 2), any());
        org.mockito.Mockito.verify(runtimeClient).callTool(org.mockito.ArgumentMatchers.argThat(call -> Integer.valueOf(2).equals(call.arguments().get("page"))));
    }
    @Test void rejectsMissingRowsPathInsteadOfPretendingEmptySuccess() {
        var binding = service.resolveMcpTools(request()).getFirst();
        var config = new java.util.HashMap<>(capability.getConfig()); config.put("toolResultPolicies", Map.of(binding.remoteToolName(), Map.of("mode", "dataset", "rowsPath", "$.missing")));
        capability.updateProfile("mcp", capability.getName(), "v1", "", "medium", "active", config, true, NOW);
        when(runtimeClient.callTool(any())).thenReturn(new McpRuntimeClient.ToolResult(Map.of("structuredContent", Map.of("rows", List.of())), 1));
        assertThatThrownBy(() -> service.executeResolvedTool(request(), binding, Map.of())).hasMessageContaining("路径不存在");
        assertThat(savedLogs.getLast().getStatus()).isEqualTo("failed");
    }

    @Test void refusesIncompleteOrRepeatedPagesWithoutSavingDataset() {
        var binding = service.resolveMcpTools(request()).getFirst();
        var config = new java.util.HashMap<>(capability.getConfig());
        config.put("toolResultPolicies", Map.of(binding.remoteToolName(), Map.of("mode", "dataset", "rowsPath", "$.rows", "hasMorePath", "$.hasMore", "pagination", Map.of("type", "page", "pageSize", 1))));
        capability.updateProfile("mcp", capability.getName(), "v1", "", "medium", "active", config, true, NOW);
        when(runtimeClient.callTool(any())).thenReturn(new McpRuntimeClient.ToolResult(Map.of("structuredContent", Map.of("rows", List.of(Map.of("no", "same")), "hasMore", true)), 1));
        assertThatThrownBy(() -> service.executeResolvedTool(request(), binding, Map.of())).hasMessageContaining("分页重复");
        org.mockito.Mockito.verifyNoInteractions(datasets);
    }
    @Test void autoModePreservesSmallResultsAndTextJsonSupportsRootArray() {
        var binding = service.resolveMcpTools(request()).getFirst();
        var config = new java.util.HashMap<>(capability.getConfig());
        config.put("toolResultPolicies", Map.of(binding.remoteToolName(), Map.of("mode", "auto")));
        capability.updateProfile("mcp", capability.getName(), "v1", "", "medium", "active", config, true, NOW);
        when(runtimeClient.callTool(any())).thenReturn(new McpRuntimeClient.ToolResult(Map.of("text", "小结果"), 1));
        assertThat(service.executeResolvedTool(request(), binding, Map.of()).responsePayload()).containsEntry("text", "小结果");
        config.put("toolResultPolicies", Map.of(binding.remoteToolName(), Map.of("mode", "dataset", "source", "text_json", "rowsPath", "$")));
        capability.updateProfile("mcp", capability.getName(), "v1", "", "medium", "active", config, true, NOW);
        when(runtimeClient.callTool(any())).thenReturn(new McpRuntimeClient.ToolResult(Map.of("content", List.of(Map.of("type", "text", "text", "[{\"no\":\"001\"}]"))), 1));
        when(datasets.save(any(), any(), any(), any(), any(), any())).thenReturn(Map.of("datasetId", "ref"));
        assertThat(service.executeResolvedTool(request(), binding, Map.of()).responsePayload()).containsEntry("datasetId", "ref");
    }

}
