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
                        "name", "get_financial_work_report_core_kpi",
                        "description", "获取核心经营指标",
                        "inputSchema", pdtSchema
                    ),
                    Map.of(
                        "name", "get_financial_work_report_risk_indicators",
                        "description", "获取监管风险指标",
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
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void shouldExposeEveryDiscoveredToolWithItsInputSchema() {
        List<McpRuntimeService.McpToolBinding> bindings = service.resolveMcpTools(request());

        assertThat(bindings).extracting(McpRuntimeService.McpToolBinding::remoteToolName).containsExactly(
            "get_financial_work_report_core_kpi",
            "get_financial_work_report_risk_indicators"
        );
        assertThat(bindings.getFirst().parameters()).containsEntry("required", List.of("pdt"));
        assertThat(bindings.getFirst().parameters().get("properties").toString()).contains("pdt");
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
}
