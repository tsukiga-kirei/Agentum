package com.agentum.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.agentum.agent.infrastructure.ModelCallLogRepository;
import com.agentum.asset.infrastructure.TenantAssetCapabilityRepository;
import com.agentum.mcp.application.McpRuntimeService;
import com.agentum.shared.security.FieldEncryptionService;
import com.agentum.system.infrastructure.ModelProviderRepository;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import com.agentum.system.infrastructure.TenantModelAssignmentRepository;
import com.agentum.agent.application.ModelChatClient.ChatResult;
import com.agentum.agent.application.ModelChatClient.ToolCall;
import com.agentum.runtime.cancel.RunCancellationGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentRuntimeExtractFinalAnswerTest {

    private AgentRuntimeService newService() {
        return new AgentRuntimeService(
            mock(TenantModelAssignmentRepository.class),
            mock(ModelProviderRepository.class),
            mock(TenantAssetCapabilityRepository.class),
            mock(McpRuntimeService.class),
            mock(SkillRuntimeService.class),
            mock(FieldEncryptionService.class),
            mock(ModelCallLogRepository.class),
            mock(ModelChatClient.class),
            new ObjectMapper(),
            Clock.systemUTC(),
            mock(RunCancellationGuard.class),
            new PromptContentResolver(mock(SystemCapabilityRepository.class), mock(TenantAssetCapabilityRepository.class)),
            runtimeProperties()
        );
    }

    private static AgentRuntimeProperties runtimeProperties() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setSuggestedIterationsPerTurn(4);
        properties.setMaxIterationsPerTurn(20);
        return properties;
    }

    @Test
    void shouldExtractPartialAnswerFromTruncatedJson() {
        String truncated = "{\"answer\":\"## 结论\\n这是被截断的最终答案，缺少闭合引号";
        assertThat(AgentRuntimeService.extractPartialAnswerFromTruncatedJson(truncated))
            .isEqualTo("## 结论\n这是被截断的最终答案，缺少闭合引号");
    }

    @Test
    void shouldReturnEmptyWhenAnswerKeyMissing() {
        assertThat(AgentRuntimeService.extractPartialAnswerFromTruncatedJson("{\"summary\":\"无答案\"}"))
            .isBlank();
    }

    @Test
    void shouldDetectTruncatedFinalAnswerJson() {
        assertThat(AgentRuntimeService.looksLikeTruncatedFinalAnswerJson("{\"answer\":\"未闭合"))
            .isTrue();
        assertThat(AgentRuntimeService.looksLikeTruncatedFinalAnswerJson("{\"answer\":\"完整\"}"))
            .isFalse();
    }

    @Test
    void shouldPreferStreamedDisplayWhenToolJsonIsTruncated() {
        String truncated = "{\"answer\":\"## 结论\\n流式正文被截断";
        ChatResult result = new ChatResult(
            "",
            Map.of(),
            Map.of(),
            100L,
            List.of(new ToolCall("call-1", "final_answer", truncated)),
            "tool_calls"
        );
        assertThat(newService().resolveFinalAnswerContent(result, "## 结论\n流式正文完整版"))
            .isEqualTo("## 结论\n流式正文完整版");
    }
}
