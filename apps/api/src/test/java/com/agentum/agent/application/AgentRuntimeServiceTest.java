package com.agentum.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentum.agent.domain.ModelCallLogEntity;
import com.agentum.agent.infrastructure.ModelCallLogRepository;
import com.agentum.asset.infrastructure.TenantAssetCapabilityRepository;
import com.agentum.mcp.application.McpRuntimeService;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.security.FieldEncryptionService;
import com.agentum.system.domain.ModelProviderEntity;
import com.agentum.system.domain.TenantModelAssignmentEntity;
import com.agentum.system.infrastructure.ModelProviderRepository;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import com.agentum.system.infrastructure.TenantModelAssignmentRepository;
import com.agentum.workflow.domain.WorkflowNodeRunEntity;
import com.agentum.workflow.domain.WorkflowRunEntity;
import com.agentum.runtime.cancel.RunCancellationGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AgentRuntimeServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID OPERATOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-06-05T09:30:00Z");

    @Test
    void shouldLetModelReadSkillThenSubmitFinalAnswer() {
        TenantModelAssignmentRepository assignmentRepository = mock(TenantModelAssignmentRepository.class);
        ModelProviderRepository providerRepository = mock(ModelProviderRepository.class);
        SystemCapabilityRepository capabilityRepository = mock(SystemCapabilityRepository.class);
        TenantAssetCapabilityRepository assetRepository = mock(TenantAssetCapabilityRepository.class);
        McpRuntimeService mcpRuntimeService = mock(McpRuntimeService.class);
        SkillRuntimeService skillRuntimeService = mock(SkillRuntimeService.class);
        FieldEncryptionService encryptionService = mock(FieldEncryptionService.class);
        ModelCallLogRepository callLogRepository = mock(ModelCallLogRepository.class);
        ScriptedModelChatClient modelChatClient = new ScriptedModelChatClient();
        AgentRuntimeService service = new AgentRuntimeService(
            assignmentRepository,
            providerRepository,
            capabilityRepository,
            assetRepository,
            mcpRuntimeService,
            skillRuntimeService,
            encryptionService,
            callLogRepository,
            modelChatClient,
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            mock(RunCancellationGuard.class),
            new PromptContentResolver(capabilityRepository, assetRepository)
        );

        ModelProviderEntity provider = ModelProviderEntity.create(
            "OpenAI 兼容",
            "openai-compatible",
            "https://example.test",
            "gpt-4o-mini",
            "active",
            NOW
        );
        provider.getSettings().put("maxTokens", 8192);
        TenantModelAssignmentEntity assignment = TenantModelAssignmentEntity.create(TENANT_ID, provider.getId(), "gpt-4o-mini", "enabled", NOW);
        WorkflowRunEntity run = WorkflowRunEntity.create(
            TENANT_ID,
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            "授信复核",
            "授信报告流程",
            OPERATOR_ID,
            2,
            "20260605-TEST",
            NOW
        );
        WorkflowNodeRunEntity nodeRun = WorkflowNodeRunEntity.pending(
            run.getId(),
            TENANT_ID,
            run.getWorkflowId(),
            run.getWorkflowVersionId(),
            "agent_review",
            "agent",
            "智能体分析",
            Map.of(),
            Map.of(),
            Map.of("systemPrompt", "你是授信分析智能体", "userPrompt", "请分析 {{company}}", "skillIds", List.of("skill-id")),
            1,
            NOW
        );
        SkillRuntimeService.SkillToolBinding skillBinding = new SkillRuntimeService.SkillToolBinding(
            "skill_credit_read",
            UUID.randomUUID(),
            "credit",
            "授信分析 Skill",
            "授信分析方法",
            "capabilities/credit/SKILL.md"
        );

        when(assignmentRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).thenReturn(List.of(assignment));
        when(providerRepository.findById(provider.getId())).thenReturn(Optional.of(provider));
        when(mcpRuntimeService.resolveMcpTools(any())).thenReturn(List.of());
        when(skillRuntimeService.resolveSkillTools(TENANT_ID, nodeRun.getConfigSnapshot())).thenReturn(List.of(skillBinding));
        when(skillRuntimeService.readSkill(any(), any())).thenReturn(new SkillRuntimeService.SkillReadResult(
            "credit",
            "授信分析 Skill",
            "SKILL.md",
            "按主体、现金流和担保条件综合判断。",
            false
        ));
        when(callLogRepository.save(any(ModelCallLogEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgentRuntimeResult result = service.execute(new AgentRuntimeRequest(
            run,
            nodeRun,
            nodeRun.getConfigSnapshot(),
            Map.of("company", "云程科技"),
            Map.of(),
            OPERATOR_ID
        ));

        assertThat(result.outputs()).containsEntry("agentMode", "react");
        assertThat(result.outputs().get("final_answer")).asString().contains("可授信");
        assertThat(result.outputs().get("agent_response")).asString().contains("可授信");
        assertThat(result.outputs().get("toolCalls")).asList().hasSize(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) result.outputs().get("toolCalls");
        assertThat(toolCalls).singleElement().satisfies(tool -> {
            assertThat(tool.get("summary")).isEqualTo("已读取 SKILL.md，可展开查看内容");
            assertThat(tool.get("detail")).asString()
                .contains("读取文件：SKILL.md", "按主体、现金流和担保条件综合判断。");
        });
        assertThat(modelChatClient.requests()).hasSize(2);
        assertThat(modelChatClient.requests()).allSatisfy(request -> {
            assertThat(request.runId()).isEqualTo(run.getId());
            assertThat(request.nodeRunId()).isEqualTo(nodeRun.getId());
            assertThat(request.modelCallLogId()).isNotNull();
        });
        assertThat(modelChatClient.requests().get(0).tools()).extracting(ModelChatClient.ToolDefinition::name)
            .contains("skill_credit_read", "final_answer");
        assertThat(result.outputs().get("chatMessages")).asList().hasSize(2);
    }

    @Test
    void shouldContinueConversationFromFollowUpHistory() {
        TenantModelAssignmentRepository assignmentRepository = mock(TenantModelAssignmentRepository.class);
        ModelProviderRepository providerRepository = mock(ModelProviderRepository.class);
        SystemCapabilityRepository capabilityRepository = mock(SystemCapabilityRepository.class);
        TenantAssetCapabilityRepository assetRepository = mock(TenantAssetCapabilityRepository.class);
        McpRuntimeService mcpRuntimeService = mock(McpRuntimeService.class);
        SkillRuntimeService skillRuntimeService = mock(SkillRuntimeService.class);
        FieldEncryptionService encryptionService = mock(FieldEncryptionService.class);
        ModelCallLogRepository callLogRepository = mock(ModelCallLogRepository.class);
        FollowUpFinalAnswerChatClient modelChatClient = new FollowUpFinalAnswerChatClient();
        AgentRuntimeService service = new AgentRuntimeService(
            assignmentRepository,
            providerRepository,
            capabilityRepository,
            assetRepository,
            mcpRuntimeService,
            skillRuntimeService,
            encryptionService,
            callLogRepository,
            modelChatClient,
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            mock(RunCancellationGuard.class),
            new PromptContentResolver(capabilityRepository, assetRepository)
        );

        ModelProviderEntity provider = ModelProviderEntity.create(
            "OpenAI 兼容",
            "openai-compatible",
            "https://example.test",
            "gpt-4o-mini",
            "active",
            NOW
        );
        provider.getSettings().put("maxTokens", 8192);
        TenantModelAssignmentEntity assignment = TenantModelAssignmentEntity.create(TENANT_ID, provider.getId(), "gpt-4o-mini", "enabled", NOW);
        WorkflowRunEntity run = WorkflowRunEntity.create(
            TENANT_ID,
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            "授信复核",
            "授信报告流程",
            OPERATOR_ID,
            2,
            "20260605-TEST",
            NOW
        );
        Map<String, Object> config = new LinkedHashMap<>(Map.of(
            "systemPrompt", "你是授信分析智能体",
            "userPrompt", "请分析 {{company}}",
            "conversationHistory", List.of(
                Map.of("role", "user", "content", "请分析云程科技"),
                Map.of("role", "assistant", "content", "初步结论：可授信。"),
                Map.of("role", "user", "content", "担保条件有哪些？")
            )
        ));
        WorkflowNodeRunEntity nodeRun = WorkflowNodeRunEntity.pending(
            run.getId(),
            TENANT_ID,
            run.getWorkflowId(),
            run.getWorkflowVersionId(),
            "agent_review",
            "agent",
            "智能体分析",
            Map.of(),
            Map.of(),
            config,
            1,
            NOW
        );

        when(assignmentRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).thenReturn(List.of(assignment));
        when(providerRepository.findById(provider.getId())).thenReturn(Optional.of(provider));
        when(mcpRuntimeService.resolveMcpTools(any())).thenReturn(List.of());
        when(skillRuntimeService.resolveSkillTools(TENANT_ID, config)).thenReturn(List.of());
        when(callLogRepository.save(any(ModelCallLogEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgentRuntimeResult result = service.execute(new AgentRuntimeRequest(
            run,
            nodeRun,
            config,
            Map.of("company", "云程科技"),
            Map.of(),
            OPERATOR_ID
        ));

        assertThat(modelChatClient.requests()).hasSize(1);
        assertThat(modelChatClient.requests().get(0).tools())
            .extracting(ModelChatClient.ToolDefinition::name)
            .containsExactly("final_answer");
        assertThat(modelChatClient.requests().get(0).messages())
            .extracting(ModelChatClient.ChatMessage::role)
            .containsExactly("system", "user", "assistant", "user");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chatMessages = (List<Map<String, Object>>) result.outputs().get("chatMessages");
        assertThat(chatMessages)
            .extracting(message -> message.get("role"))
            .containsExactly("user", "assistant", "user", "assistant");
        assertThat(result.outputs().get("final_answer")).asString().contains("担保");
    }

    @Test
    void shouldLetModelContinueAfterRecoverableMcpFailure() {
        TenantModelAssignmentRepository assignmentRepository = mock(TenantModelAssignmentRepository.class);
        ModelProviderRepository providerRepository = mock(ModelProviderRepository.class);
        SystemCapabilityRepository capabilityRepository = mock(SystemCapabilityRepository.class);
        TenantAssetCapabilityRepository assetRepository = mock(TenantAssetCapabilityRepository.class);
        McpRuntimeService mcpRuntimeService = mock(McpRuntimeService.class);
        SkillRuntimeService skillRuntimeService = mock(SkillRuntimeService.class);
        ModelCallLogRepository callLogRepository = mock(ModelCallLogRepository.class);
        McpFailureThenFinalAnswerChatClient modelChatClient = new McpFailureThenFinalAnswerChatClient();
        AgentRuntimeService service = new AgentRuntimeService(
            assignmentRepository,
            providerRepository,
            capabilityRepository,
            assetRepository,
            mcpRuntimeService,
            skillRuntimeService,
            mock(FieldEncryptionService.class),
            callLogRepository,
            modelChatClient,
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            mock(RunCancellationGuard.class),
            new PromptContentResolver(capabilityRepository, assetRepository)
        );
        ModelProviderEntity provider = ModelProviderEntity.create(
            "OpenAI 兼容",
            "openai-compatible",
            "https://example.test",
            "gpt-4o-mini",
            "active",
            NOW
        );
        provider.getSettings().put("maxTokens", 8192);
        TenantModelAssignmentEntity assignment = TenantModelAssignmentEntity.create(TENANT_ID, provider.getId(), "gpt-4o-mini", "enabled", NOW);
        WorkflowRunEntity run = WorkflowRunEntity.create(
            TENANT_ID,
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            "金融月报",
            "金融月报流程",
            OPERATOR_ID,
            2,
            "20260618-MCP",
            NOW
        );
        Map<String, Object> config = Map.of("systemPrompt", "你是金融分析智能体", "userPrompt", "请生成金融月报");
        WorkflowNodeRunEntity nodeRun = WorkflowNodeRunEntity.pending(
            run.getId(),
            TENANT_ID,
            run.getWorkflowId(),
            run.getWorkflowVersionId(),
            "financial_agent",
            "agent",
            "金融月报智能体",
            Map.of(),
            Map.of(),
            config,
            1,
            NOW
        );
        McpRuntimeService.McpToolBinding binding = new McpRuntimeService.McpToolBinding(
            "mcp_financial_core_kpi",
            UUID.randomUUID(),
            "cap_financial_report",
            "金融月报 / 核心经营指标",
            "获取核心经营指标",
            "get_financial_work_report_core_kpi",
            "streamable_http",
            "http://127.0.0.1:3001/mcp",
            "http://127.0.0.1:3001/mcp",
            Map.of(
                "type", "object",
                "properties", Map.of("pdt", Map.of("type", "string")),
                "required", List.of("pdt")
            )
        );

        when(assignmentRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).thenReturn(List.of(assignment));
        when(providerRepository.findById(provider.getId())).thenReturn(Optional.of(provider));
        when(mcpRuntimeService.resolveMcpTools(any())).thenReturn(List.of(binding));
        when(mcpRuntimeService.executeResolvedTool(any(), any(), any())).thenThrow(new ApiException(
            HttpStatus.BAD_GATEWAY,
            "MCP_TOOL_EXECUTION_FAILED",
            "数据中台连接失败"
        ));
        when(skillRuntimeService.resolveSkillTools(TENANT_ID, config)).thenReturn(List.of());
        when(callLogRepository.save(any(ModelCallLogEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgentRuntimeResult result = service.execute(new AgentRuntimeRequest(
            run,
            nodeRun,
            config,
            Map.of(),
            Map.of(),
            OPERATOR_ID
        ));

        assertThat(modelChatClient.requests()).hasSize(2);
        assertThat(result.outputs().get("final_answer")).asString().contains("数据中台暂时不可用");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) result.outputs().get("toolCalls");
        assertThat(toolCalls).singleElement().satisfies(tool -> {
            assertThat(tool.get("status")).isEqualTo("failed");
            assertThat(tool.get("summary")).isEqualTo("数据中台连接失败");
            assertThat(tool.get("detail")).asString().contains("MCP_TOOL_EXECUTION_FAILED", "数据中台连接失败");
        });
    }

    private static final class ScriptedModelChatClient implements ModelChatClient {

        private final ArrayList<ChatRequest> requests = new ArrayList<>();

        @Override
        public ChatResult chat(ChatRequest request) {
            requests.add(request);
            if (requests.size() == 1) {
                return new ChatResult(
                    "",
                    Map.of("finishReason", "tool_calls"),
                    Map.of(),
                    12L,
                    List.of(new ToolCall("call-skill", "skill_credit_read", "{}")),
                    "tool_calls"
                );
            }
            return new ChatResult(
                "",
                Map.of("finishReason", "tool_calls"),
                Map.of(),
                18L,
                List.of(new ToolCall("call-final", "final_answer", "{\"answer\":\"## 结论\\n可授信，建议补充担保条件。\"}")),
                "tool_calls"
            );
        }

        private List<ChatRequest> requests() {
            return requests;
        }
    }

    private static final class McpFailureThenFinalAnswerChatClient implements ModelChatClient {

        private final ArrayList<ChatRequest> requests = new ArrayList<>();

        @Override
        public ChatResult chat(ChatRequest request) {
            requests.add(request);
            if (requests.size() == 1) {
                return new ChatResult(
                    "",
                    Map.of("finishReason", "tool_calls"),
                    Map.of(),
                    10L,
                    List.of(new ToolCall(
                        "call-mcp",
                        "mcp_financial_core_kpi",
                        "{\"pdt\":\"20260618\"}"
                    )),
                    "tool_calls"
                );
            }
            return new ChatResult(
                "",
                Map.of("finishReason", "tool_calls"),
                Map.of(),
                10L,
                List.of(new ToolCall(
                    "call-final",
                    "final_answer",
                    "{\"answer\":\"数据中台暂时不可用，无法获取真实指标，请检查连接配置后重试。\"}"
                )),
                "tool_calls"
            );
        }

        private List<ChatRequest> requests() {
            return requests;
        }
    }

    /** 追问续聊测试专用：首轮直接提交 final_answer，避免工具循环干扰 messages 断言。 */
    private static final class FollowUpFinalAnswerChatClient implements ModelChatClient {

        private final ArrayList<ChatRequest> requests = new ArrayList<>();

        @Override
        public ChatResult chat(ChatRequest request) {
            requests.add(request);
            return new ChatResult(
                "",
                Map.of("finishReason", "tool_calls"),
                Map.of(),
                12L,
                List.of(new ToolCall(
                    "call-final",
                    "final_answer",
                    "{\"answer\":\"## 结论\\n可授信，建议补充担保条件。\"}"
                )),
                "tool_calls"
            );
        }

        private List<ChatRequest> requests() {
            return requests;
        }
    }
}
