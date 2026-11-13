package com.agentum.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentum.agent.domain.ModelCallLogEntity;
import com.agentum.agent.infrastructure.ModelCallLogRepository;
import com.agentum.attachment.application.AttachmentVariableContentResolver;
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
            assetRepository,
            mcpRuntimeService,
            skillRuntimeService,
            encryptionService,
            callLogRepository,
            modelChatClient,
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            mock(RunCancellationGuard.class),
            new PromptContentResolver(capabilityRepository, assetRepository),
            runtimeProperties(),
            attachmentVariableContentResolver()
        );

        ModelProviderEntity provider = ModelProviderEntity.create(
            "OpenAI 兼容",
            "openai-compatible",
            "https://example.test",
            "gpt-4o-mini",
            true,
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
            Map.of(
                "systemPrompt", "你是授信分析智能体",
                "userPrompt", "请分析 {{company}}",
                "skillIds", List.of("skill-id"),
                "enableThinking", false,
                "maxAgentIterationsPerTurn", 4
            ),
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

        List<String> preparedPrompts = new ArrayList<>();
        AgentRuntimeResult result = service.executeStreaming(new AgentRuntimeRequest(
            run,
            nodeRun,
            nodeRun.getConfigSnapshot(),
            Map.of("company", "云程科技"),
            Map.of(),
            OPERATOR_ID
        ), new AgentRuntimeService.AgentRuntimeEventSink() {
            @Override
            public void onPromptPrepared(String renderedUserPrompt) {
                preparedPrompts.add(renderedUserPrompt);
            }
        });

        assertThat(preparedPrompts).containsExactly("请分析 云程科技");
        assertThat(result.outputs()).containsEntry("agentMode", "react");
        assertThat(result.outputs().get("final_answer")).asString().contains("可授信");
        assertThat(result.outputs().get("agent_response")).asString().contains("可授信");
        assertThat((List<?>) result.outputs().get("toolCalls")).hasSize(1);
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
            assertThat(request.options()).containsEntry("chat_template_kwargs", Map.of("enable_thinking", false));
        });
        assertThat(modelChatClient.requests().getFirst().tools()).extracting(ModelChatClient.ToolDefinition::name)
            .contains("skill_credit_read", "final_answer");
        assertThat((List<?>) result.outputs().get("chatMessages")).hasSize(2);
        assertThat(result.outputs().get("tokenUsage")).isEqualTo(Map.of(
            "inputTokens", 180L,
            "outputTokens", 50L,
            "totalTokens", 230L
        ));
    }

    @Test
    void shouldKeepFormalOutputWhenFollowUpReturnsOnlyExplanation() {
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
            assetRepository,
            mcpRuntimeService,
            skillRuntimeService,
            encryptionService,
            callLogRepository,
            modelChatClient,
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            mock(RunCancellationGuard.class),
            new PromptContentResolver(capabilityRepository, assetRepository),
            runtimeProperties(),
            attachmentVariableContentResolver()
        );

        ModelProviderEntity provider = ModelProviderEntity.create(
            "OpenAI 兼容",
            "openai-compatible",
            "https://example.test",
            "gpt-4o-mini",
            true,
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
            Map.of(
                "systemPrompt", "你是授信分析智能体",
                "userPrompt", "请分析 {{company}}",
                "skillIds", List.of("skill-id"),
                "_formalAnswer", "完整业务报告",
                "enableThinking", false,
                "maxAgentIterationsPerTurn", 4
            ),
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

        List<String> preparedPrompts = new ArrayList<>();
        AgentRuntimeResult result = service.executeStreaming(new AgentRuntimeRequest(
            run,
            nodeRun,
            nodeRun.getConfigSnapshot(),
            Map.of("company", "云程科技"),
            Map.of(),
            OPERATOR_ID
        ), new AgentRuntimeService.AgentRuntimeEventSink() {
            @Override
            public void onPromptPrepared(String renderedUserPrompt) {
                preparedPrompts.add(renderedUserPrompt);
            }
        });

        assertThat(result.outputs()).containsEntry("final_answer", "完整业务报告").containsEntry("agent_response", "完整业务报告").containsEntry("resultSelectionRequired", true);
        assertThat(result.outputs().get("candidate_answer")).isNotEqualTo("完整业务报告");
        assertThat(result.outputs().get("chatMessages").toString()).doesNotContain("完整业务报告");
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
            assetRepository,
            mcpRuntimeService,
            skillRuntimeService,
            encryptionService,
            callLogRepository,
            modelChatClient,
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            mock(RunCancellationGuard.class),
            new PromptContentResolver(capabilityRepository, assetRepository),
            runtimeProperties(),
            attachmentVariableContentResolver()
        );

        ModelProviderEntity provider = ModelProviderEntity.create(
            "OpenAI 兼容",
            "openai-compatible",
            "https://example.test",
            "gpt-4o-mini",
            false,
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
            "maxAgentIterationsPerTurn", 4,
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
        assertThat(modelChatClient.requests().getFirst().tools())
            .extracting(ModelChatClient.ToolDefinition::name)
            .containsExactly("final_answer");
        assertThat(modelChatClient.requests().getFirst().messages())
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
            assetRepository,
            mcpRuntimeService,
            skillRuntimeService,
            mock(FieldEncryptionService.class),
            callLogRepository,
            modelChatClient,
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            mock(RunCancellationGuard.class),
            new PromptContentResolver(capabilityRepository, assetRepository),
            runtimeProperties(),
            attachmentVariableContentResolver()
        );
        ModelProviderEntity provider = ModelProviderEntity.create(
            "OpenAI 兼容",
            "openai-compatible",
            "https://example.test",
            "gpt-4o-mini",
            false,
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
        Map<String, Object> config = Map.of(
            "systemPrompt", "你是金融分析智能体",
            "userPrompt", "请生成金融月报",
            "maxAgentIterationsPerTurn", 4
        );
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
            Map.of(
                "type", "object",
                "properties", Map.of("pdt", Map.of("type", "string")),
                "required", List.of("pdt")
            )
        );
        McpRuntimeService.McpToolBinding refreshedBinding = new McpRuntimeService.McpToolBinding(
            "mcp_financial_latest_kpi",
            binding.capabilityId(),
            binding.capabilityCode(),
            "金融月报 / 最新经营指标",
            "MCP 契约变化后重新发现的新工具",
            "get_financial_work_report_latest_kpi",
            binding.transportType(),
            binding.endpointUrl(),
            binding.parameters()
        );

        when(assignmentRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).thenReturn(List.of(assignment));
        when(providerRepository.findById(provider.getId())).thenReturn(Optional.of(provider));
        when(mcpRuntimeService.resolveMcpTools(any()))
            .thenReturn(List.of(binding))
            .thenReturn(List.of(refreshedBinding));
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
        assertThat(modelChatClient.requests().getFirst().tools()).extracting(ModelChatClient.ToolDefinition::name)
            .contains("mcp_financial_core_kpi")
            .doesNotContain("mcp_financial_latest_kpi");
        assertThat(modelChatClient.requests().getLast().tools()).extracting(ModelChatClient.ToolDefinition::name)
            .contains("mcp_financial_latest_kpi")
            .doesNotContain("mcp_financial_core_kpi");
        assertThat(result.outputs().get("final_answer")).asString().contains("数据中台暂时不可用");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) result.outputs().get("toolCalls");
        assertThat(toolCalls).singleElement().satisfies(tool -> {
            assertThat(tool.get("status")).isEqualTo("failed");
            assertThat(tool.get("summary")).isEqualTo("数据中台连接失败");
            assertThat(tool.get("detail")).asString().contains("MCP_TOOL_EXECUTION_FAILED", "数据中台连接失败");
        });
    }

    private static AgentRuntimeProperties runtimeProperties() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setSuggestedIterationsPerTurn(4);
        properties.setMaxIterationsPerTurn(20);
        return properties;
    }

    private static AttachmentVariableContentResolver attachmentVariableContentResolver() {
        AttachmentVariableContentResolver resolver = mock(AttachmentVariableContentResolver.class);
        when(resolver.renderValue(any(), any(), any(Boolean.class))).thenAnswer(invocation -> {
            Object value = invocation.getArgument(1);
            return value == null ? "" : value.toString();
        });
        return resolver;
    }

    @Test void requiredResultsBlockInventedFinalAnswersAndFollowUpKeepsFormalReferences() {
        var assignments = mock(TenantModelAssignmentRepository.class); var providers = mock(ModelProviderRepository.class);
        var capabilities = mock(SystemCapabilityRepository.class); var assets = mock(TenantAssetCapabilityRepository.class);
        var mcp = mock(McpRuntimeService.class); var skills = mock(SkillRuntimeService.class); var logs = mock(ModelCallLogRepository.class);
        var model = mock(ModelChatClient.class);
        var provider = ModelProviderEntity.create("测试", "openai-compatible", "https://example.test", "test", false, "active", NOW);
        provider.getSettings().put("maxTokens", 8192);
        var assignment = TenantModelAssignmentEntity.create(TENANT_ID, provider.getId(), "test", "enabled", NOW);
        when(assignments.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).thenReturn(List.of(assignment));
        when(providers.findById(provider.getId())).thenReturn(Optional.of(provider));
        when(mcp.resolveMcpTools(any())).thenReturn(List.of()); when(mcp.datasetReferences(any(), any())).thenReturn(Map.of());
        when(skills.resolveSkillTools(any(), any())).thenReturn(List.of());
        when(logs.save(any())).thenAnswer(call -> call.getArgument(0));
        when(model.chat(any())).thenReturn(new ModelChatClient.ChatResult("已经完成", Map.of(), Map.of(), 1));
        var service = new AgentRuntimeService(assignments, providers, assets, mcp, skills, mock(FieldEncryptionService.class), logs, model,
            new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), mock(RunCancellationGuard.class), new PromptContentResolver(capabilities, assets), runtimeProperties(), attachmentVariableContentResolver());
        var run = WorkflowRunEntity.create(TENANT_ID, UUID.randomUUID(), UUID.randomUUID(), 1, "工具结果", "工具结果", OPERATOR_ID, 2, "RUN-RESULT", NOW);
        var config = new LinkedHashMap<String, Object>();
        config.put("systemPrompt", "分析数据"); config.put("userPrompt", "生成报告"); config.put("maxAgentIterationsPerTurn", 4);
        config.put("_requiredMcpResults", List.of("cap_demo.rows"));
        var node = WorkflowNodeRunEntity.pending(run.getId(), TENANT_ID, run.getWorkflowId(), run.getWorkflowVersionId(), "agent", "agent", "采集", Map.of(), Map.of(), config, 1, NOW);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.execute(new AgentRuntimeRequest(run, node, config, Map.of(), Map.of(), OPERATOR_ID))).hasMessageContaining("尚未产生完整结果");
        org.mockito.Mockito.verify(model, org.mockito.Mockito.times(3)).chat(any());
        Map<String, Object> formal = Map.of("cap_demo", Map.of("rows", Map.of("datasetId", "original")));
        config.put("_formalAnswer", "原回答"); config.put("_formalMcpDatasets", formal);
        var result = service.execute(new AgentRuntimeRequest(run, node, config, Map.of(), Map.of(), OPERATOR_ID));
        assertThat(result.outputs()).containsEntry("final_answer", "原回答").containsEntry("mcp_datasets", formal);
        assertThat((List<?>) result.outputs().get("chatMessages")).anySatisfy(raw -> assertThat(((Map<?, ?>) raw).get("mcp_datasets")).isEqualTo(formal));
    }

    @Test
    void shouldExecuteToolsWhenModelOutputsPreambleBeforeToolCall() {
        var assignments = mock(TenantModelAssignmentRepository.class);
        var providers = mock(ModelProviderRepository.class);
        var capabilities = mock(SystemCapabilityRepository.class);
        var assets = mock(TenantAssetCapabilityRepository.class);
        var mcp = mock(McpRuntimeService.class);
        var skills = mock(SkillRuntimeService.class);
        var logs = mock(ModelCallLogRepository.class);
        var model = mock(ModelChatClient.class);
        var provider = ModelProviderEntity.create("测试", "openai-compatible", "https://example.test", "test", false, "active", NOW);
        provider.getSettings().put("maxTokens", 8192);
        var assignment = TenantModelAssignmentEntity.create(TENANT_ID, provider.getId(), "test", "enabled", NOW);
        when(assignments.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).thenReturn(List.of(assignment));
        when(providers.findById(provider.getId())).thenReturn(Optional.of(provider));
        when(logs.save(any())).thenAnswer(call -> call.getArgument(0));

        McpRuntimeService.McpToolBinding mcpBinding = new McpRuntimeService.McpToolBinding(
            "mcp_financial_loan_details",
            UUID.randomUUID(),
            "financial",
            "信贷明细",
            "获取信贷明细",
            "get_loan_details",
            "sse",
            "https://example.test/sse",
            Map.of("type", "object")
        );
        when(mcp.resolveMcpTools(any())).thenReturn(List.of(mcpBinding));
        when(mcp.executeResolvedTool(any(), any(), any())).thenReturn(
            new McpRuntimeService.ExecutedMcpTool(
                "mcp_financial_loan_details",
                "get_loan_details",
                "financial",
                Map.of("datasetId", "ds-1", "rowCount", 10, "complete", true),
                50L,
                UUID.randomUUID()
            )
        );
        when(mcp.datasetReferences(any(), any())).thenReturn(
            Map.of("cap_565b398c", Map.of("loan_details", Map.of("datasetId", "ds-1", "complete", true)))
        );

        // 第 1 轮：模型输出了 content（前置说明）并调用了 mcp 工具
        // 第 2 轮：模型输出了 final_answer 提交最终答案
        when(model.chat(any()))
            .thenReturn(new ModelChatClient.ChatResult(
                "请按顺序执行。Let me start by calling the first tool for loan details.",
                Map.of("finishReason", "tool_calls"),
                Map.of(),
                10L,
                List.of(new ModelChatClient.ToolCall("call-1", "mcp_financial_loan_details", "{\"pdt\":\"20261031\"}")),
                "tool_calls"
            ))
            .thenReturn(new ModelChatClient.ChatResult(
                "",
                Map.of("finishReason", "tool_calls"),
                Map.of(),
                15L,
                List.of(new ModelChatClient.ToolCall("call-2", "final_answer", "{\"answer\":\"信贷明细已采集完成，共 10 条。\"}")),
                "tool_calls"
            ));

        var service = new AgentRuntimeService(
            assignments, providers, assets, mcp, skills, mock(FieldEncryptionService.class), logs, model,
            new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), mock(RunCancellationGuard.class),
            new PromptContentResolver(capabilities, assets), runtimeProperties(), attachmentVariableContentResolver()
        );
        var run = WorkflowRunEntity.create(TENANT_ID, UUID.randomUUID(), UUID.randomUUID(), 1, "信贷明细", "采集流程", OPERATOR_ID, 2, "RUN-PREAMBLE", NOW);
        var config = new LinkedHashMap<String, Object>();
        config.put("systemPrompt", "你是数据采集智能体");
        config.put("userPrompt", "采集 2026-10 信贷数据");
        config.put("maxAgentIterationsPerTurn", 4);
        config.put("_requiredMcpResults", List.of("cap_565b398c.loan_details"));

        var node = WorkflowNodeRunEntity.pending(run.getId(), TENANT_ID, run.getWorkflowId(), run.getWorkflowVersionId(), "agent", "agent", "信贷采集", Map.of(), Map.of(), config, 1, NOW);

        var result = service.execute(new AgentRuntimeRequest(run, node, config, Map.of(), Map.of(), OPERATOR_ID));

        assertThat(result.outputs()).containsEntry("final_answer", "信贷明细已采集完成，共 10 条。");
        // 验证 MCP 工具被真实调用，且只有 2 轮交互，没有发生误判截胡或重试拦截
        org.mockito.Mockito.verify(mcp, org.mockito.Mockito.times(1)).executeResolvedTool(any(), any(), any());
        org.mockito.Mockito.verify(model, org.mockito.Mockito.times(2)).chat(any());
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
                    Map.of("prompt_tokens", 80, "completion_tokens", 20, "total_tokens", 100),
                    12L,
                    List.of(new ToolCall("call-skill", "skill_credit_read", "{}")),
                    "tool_calls"
                );
            }
            return new ChatResult(
                "",
                Map.of("finishReason", "tool_calls"),
                Map.of("prompt_tokens", 100, "completion_tokens", 30, "total_tokens", 130),
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
