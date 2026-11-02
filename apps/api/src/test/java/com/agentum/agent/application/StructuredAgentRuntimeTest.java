package com.agentum.agent.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.agentum.agent.infrastructure.ModelCallLogRepository;
import com.agentum.asset.infrastructure.TenantAssetCapabilityRepository;
import com.agentum.attachment.application.AttachmentVariableContentResolver;
import com.agentum.delivery.template.TemplateModels;
import com.agentum.mcp.application.McpRuntimeService;
import com.agentum.runtime.cancel.RunCancellationGuard;
import com.agentum.shared.security.FieldEncryptionService;
import com.agentum.system.domain.*;
import com.agentum.system.infrastructure.*;
import com.agentum.workflow.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class StructuredAgentRuntimeTest {
    @Test void retriesInvalidDataThenStoresSeveralFieldsAndKeepsReadableAnswer() {
        var requests = new ArrayList<ModelChatClient.ChatRequest>();
        var result = execute(request -> {
            requests.add(request);
            String data = requests.size() == 1 ? "{\"summary\":\"分析\",\"amount\":\"错误数字\"}" : "{\"summary\":\"分析\",\"amount\":12}";
            return new ModelChatClient.ChatResult("", Map.of(), Map.of(), 1, List.of(new ModelChatClient.ToolCall("final-" + requests.size(), "final_answer", "{\"answer\":\"报告说明\",\"data\":" + data + "}")), "tool_calls");
        });
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).messages()).anyMatch(message -> message.content().contains("必须是有限数字"));
        assertThat(result.outputs()).containsEntry("report_text", "报告说明");
        assertThat(((Map<?, ?>) result.outputs().get("report_data")).get("summary")).isEqualTo("分析");
        assertThat(((Map<?, ?>) result.outputs().get("report_data")).get("amount")).isEqualTo(12);
        assertThat(requests.getFirst().tools().stream().filter(tool -> tool.name().equals("final_answer")).findFirst().orElseThrow().parameters().toString()).contains("data", "summary", "amount");
    }
    @Test void rejectsRepeatedMalformedJsonRatherThanFallingBackToText() {
        assertThatThrownBy(() -> execute(request -> new ModelChatClient.ChatResult("", Map.of(), Map.of(), 1, List.of(new ModelChatClient.ToolCall("bad", "final_answer", "{\"answer\":\"看似成功\",\"data\":")), "tool_calls"))).hasMessageContaining("JSON");
    }
    private AgentRuntimeResult execute(ModelChatClient client) {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(); Instant now = Instant.parse("2026-10-31T00:00:00Z");
        var assignmentRepo = mock(TenantModelAssignmentRepository.class); var providerRepo = mock(ModelProviderRepository.class);
        var assetRepo = mock(TenantAssetCapabilityRepository.class); var capRepo = mock(SystemCapabilityRepository.class);
        var mcp = mock(McpRuntimeService.class); var skills = mock(SkillRuntimeService.class); var logs = mock(ModelCallLogRepository.class);
        var provider = ModelProviderEntity.create("测试", "openai-compatible", "https://example.test", "test", true, "active", now); provider.getSettings().put("maxTokens", 1000);
        var assignment = TenantModelAssignmentEntity.create(tenant, provider.getId(), "test", "enabled", now);
        when(assignmentRepo.findByTenantIdOrderByCreatedAtDesc(tenant)).thenReturn(List.of(assignment)); when(providerRepo.findById(provider.getId())).thenReturn(Optional.of(provider));
        when(mcp.resolveMcpTools(any())).thenReturn(List.of()); when(skills.resolveSkillTools(any(), any())).thenReturn(List.of()); when(logs.save(any())).thenAnswer(call -> call.getArgument(0));
        var limits = new AgentRuntimeProperties(); limits.setSuggestedIterationsPerTurn(4); limits.setMaxIterationsPerTurn(20);
        var service = new AgentRuntimeService(assignmentRepo, providerRepo, assetRepo, mcp, skills, mock(FieldEncryptionService.class), logs, client, new ObjectMapper(), Clock.fixed(now, ZoneOffset.UTC), mock(RunCancellationGuard.class), new PromptContentResolver(capRepo, assetRepo), limits, mock(AttachmentVariableContentResolver.class));
        var run = WorkflowRunEntity.create(tenant, UUID.randomUUID(), UUID.randomUUID(), 1, "报告", "流程", user, 3, "TEST", now);
        var config = Map.<String, Object>of("systemPrompt", "分析报告", "userPrompt", "生成报告", "maxAgentIterationsPerTurn", 4, "output", "report_text", "structuredOutput", Map.of("enabled", true, "variable", "report_data", "fields", List.of(
            new TemplateModels.Field("summary", "总结", "text", true, 1000, null, "", List.of()), new TemplateModels.Field("amount", "金额", "number", true, null, null, "", List.of()))));
        var node = WorkflowNodeRunEntity.pending(run.getId(), tenant, run.getWorkflowId(), run.getWorkflowVersionId(), "analysis", "agent", "分析", Map.of(), Map.of(), config, 1, now);
        return service.execute(new AgentRuntimeRequest(run, node, config, Map.of(), Map.of(), user));
    }
}
